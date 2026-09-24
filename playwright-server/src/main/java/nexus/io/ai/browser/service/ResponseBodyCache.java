package nexus.io.ai.browser.service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import com.microsoft.playwright.PlaywrightException;

/**
 * 响应体缓存:收到响应时就把 body 抄下来
 *
 * <p>
 * <b>为什么需要它</b>:{@code Response.text()} 是惰性的,浏览器只在很短一段时间内保留响应体,之后再去读就是
 * {@code Protocol error (Network.getResponseBody): No resource with given identifier found}。实测在
 * 12306 这种每秒轮询的页面上,<b>7 秒前</b>的 XHR 就已经读不到了 —— 于是 {@code get_response_body}
 * 声称的「保留最近 100 个响应」在真实站点的表现是「一个都读不到」,而调用方最想知道的是「我这一步提交
 * 到底成功了没有」。
 *
 * <p>
 * 所以在 {@code onResponse} 里就把 body 抄一份存进 {@link BrowserInstance.RecordedResponse},之后
 * {@code get_response_body} / {@code wait_for_response} 一律先读缓存,读不到再退回原来的惰性读法。
 *
 * <p>
 * 三条边界:
 * <ul>
 * <li>只抄 {@code xhr} / {@code fetch} —— 页面、脚本、图片的 body 又大又不是「接口返回」,
 * 抄它们只会把内存和带宽吃光;</li>
 * <li>单条最多留 {@link #MAX_CHARS} 个字符,超出部分丢掉并标 {@code bodyTruncated};</li>
 * <li>抄写放在独立线程上:Playwright 的事件回调线程里做同步 API 调用有阻塞事件分发的风险,
 * 而且抄 body 也不该拖慢网络记录本身。同时抄的数量有上限({@link #MAX_OUTSTANDING}),
 * 超出就放弃并说明原因,而不是无限堆线程。</li>
 * </ul>
 */
public final class ResponseBodyCache {

  /** 单条响应最多缓存多少字符(get_response_body 默认只回 2 万,留 5 倍余量) */
  public static final int MAX_CHARS = 100_000;

  /** 同时在抄的响应上限 */
  public static final int MAX_OUTSTANDING = 64;

  /** 只有取数接口的响应体才值得留 */
  private static final Set<String> CACHEABLE_TYPES = Set.of("xhr", "fetch");

  private static final AtomicLong SEQ = new AtomicLong();
  private static final Semaphore SLOTS = new Semaphore(MAX_OUTSTANDING);
  private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, new ThreadFactory() {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "response-body-" + SEQ.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  });

  private ResponseBodyCache() {
  }

  /** 这个资源类型值得抄 body 吗 */
  public static boolean cacheable(String resourceType) {
    return resourceType != null && CACHEABLE_TYPES.contains(resourceType.trim().toLowerCase(Locale.ROOT));
  }

  /** 超长只留前缀(截断与否由调用方按 {@link #MAX_CHARS} 判断并回报) */
  public static String clip(String body) {
    if (body == null || body.length() <= MAX_CHARS) {
      return body;
    }
    return body.substring(0, MAX_CHARS);
  }

  /**
   * 把这条响应的 body 抄进 {@code recorded};不阻塞调用方(抄写在线程池里做)
   *
   * <p>
   * 无论成功与否都会把 {@code bodyCaptured} 置为 true,调用方据此判断「是还在抄,还是抄失败了」。
   */
  public static void capture(BrowserInstance.RecordedResponse recorded) {
    if (recorded == null) {
      return;
    }
    String resourceType = recorded.request == null ? null : recorded.request.getStr("resourceType");
    if (!cacheable(resourceType)) {
      return;
    }
    if (!SLOTS.tryAcquire()) {
      recorded.bodyCaptured = true;
      recorded.bodyCaptureError = "响应体缓存名额已满,这条没抄下来(需要它的 body 请用 wait_for_response 重新等一次)";
      return;
    }
    try {
      WORKERS.execute(() -> {
        try {
          String body = recorded.response.text();
          recorded.bodyTruncated = body != null && body.length() > MAX_CHARS;
          recorded.body = clip(body);
        } catch (PlaywrightException e) {
          recorded.bodyCaptureError = brief(e.getMessage());
        } catch (Throwable t) {
          recorded.bodyCaptureError = brief(String.valueOf(t));
        } finally {
          recorded.bodyAt = System.currentTimeMillis();
          recorded.bodyCaptured = true;
          SLOTS.release();
        }
      });
    } catch (RuntimeException rejected) {
      SLOTS.release();
      recorded.bodyCaptured = true;
      recorded.bodyCaptureError = "响应体抄写任务没能提交(线程池已关闭):" + brief(rejected.getMessage());
    }
  }

  private static String brief(String message) {
    if (message == null) {
      return "未知原因";
    }
    String single = message.replaceAll("\\s+", " ").trim();
    return single.length() <= 200 ? single : single.substring(0, 200) + "...";
  }
}
