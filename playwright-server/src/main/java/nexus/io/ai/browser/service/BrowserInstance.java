package nexus.io.ai.browser.service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;

import nexus.io.ai.browser.dom.model.DOMState;

public class BrowserInstance {
  /** 任务 ID:一个任务一个实例,截图与结构化文本都落在 data/&lt;id&gt;/ 下 */
  public final long id;
  public BrowserContext context;
  public Page page;
  public Path profileDir;
  public LaunchPersistentContextOptions opts;
  /** 最近一次 get_browser_state 的 DOM 树快照,按索引操作元素时使用 */
  public DOMState domState;

  /**
   * 这份快照是什么时候建的(毫秒时间戳)
   *
   * <p>
   * 「索引越界 / 元素已脱离页面」这类报错以前只说现象,不说原因,调用方只能自己想到「是不是快照过期了」。
   * 记下建快照的时刻,报错时就能直接给出「当前索引来自 12.3 秒前的快照」这种能立刻定位问题的线索。
   */
  public volatile long domStateAt;

  /**
   * 建快照时页面上的 DOM 变更计数
   *
   * <p>与 {@link #domStateAt} 配合,报错时能说出「这期间页面发生过 N 次 DOM 变更」——索引失效的真正原因。
   */
  public volatile int domStateMutations;

  /**
   * 这个任务自己的页签
   *
   * <p>
   * 浏览器与 profile 现在是全进程共用的(见 {@code PlaywrightService.SharedBrowser}),任务之间的
   * 隔离靠的就是这个集合:每个任务只看得到自己的页签,{@code get_tabs} / {@code switch_tab} 的
   * 索引也只在这个集合里数。页签被关掉后由 {@code page.onClose} 回调移除。
   */
  public final Set<Page> pages = ConcurrentHashMap.newKeySet();

  /**
   * 任务是否正在被关闭(close / 重建浏览器)
   *
   * <p>
   * 关闭过程中页签会一个个关掉,{@code onClose} 回调不该再自动补新页签,否则关不干净。
   */
  public volatile boolean detached;

  /**
   * 截图序号:每次「可能改变页面」的指令执行后自增一次
   *
   * <p>
   * 落盘文件名就是 {@code data/&lt;id&gt;/&lt;seq&gt;.png},同一个序号的 .txt 是同一时刻的
   * 可交互结构化文本,两者一一对应。
   */
  public final AtomicInteger captureSeq = new AtomicInteger();
  /** A detected mixed snapshot must not fall back to the unrelated CSS index space. */
  public boolean snapshotInvalidated;

  /**
   * 手动截图(screenshot / get_element_screenshot)的序号
   *
   * <p>与 {@link #captureSeq} 分开计数:自动截图是 {@code &lt;seq&gt;.png},手动截图是
   * {@code shot-&lt;n&gt;.png},两套编号互不影响,免得手动截的那几张把自动截图的序号顶乱。
   */
  public final AtomicInteger shotSeq = new AtomicInteger();

  /** 最近一次 get_browser_state 的文本,供 diff_dom_text 比较(每次快照后覆盖) */
  public volatile String lastDomText;

  /**
   * 自动截图连续失败了几次 / 熔断到什么时刻
   *
   * <p>
   * <b>为什么要有这个</b>:2026-09-25 在 B 站投稿页上,{@code page.screenshot()} 一次都没成功过,
   * 每次都要等满 30 秒超时。于是**每一条命令都白等 30 秒**,而回执里只有一行
   * {@code screenshot_error: Timeout 30000ms exceeded.} —— 看起来像「这次运气不好」,
   * 实际上是「这个页面根本截不出图」。更糟的是调用方从此**没有任何画面可看**,却不知道这件事:
   * 页面上出现过整页白屏,只靠文本根本判断不出来。
   *
   * <p>
   * 所以连续失败到阈值就**熔断**一段时间:不再白等,改在每条命令的回执里明说
   * {@code capture_degraded: true} 与原因,让调用方知道自己现在是「盲操作」,该改用文本取证
   * 或者请人看一眼。
   */
  public final AtomicInteger captureFailures = new AtomicInteger();

  /** 熔断到什么时候(毫秒时间戳);0 表示没熔断 */
  public volatile long captureCooldownUntil;

  /** 第一次失败的原因,熔断期间一直带着它(否则调用方只知道「停了」不知道为什么) */
  public volatile String captureFailureReason;

  /** 最近一次弹窗信息 */
  public volatile Kv lastDialog;
  /** 弹窗序号:每次弹窗自增,客户端据此判断读到的是不是新弹窗 */
  public final AtomicLong dialogSeq = new AtomicLong();
  /** true 时自动关闭弹窗,默认自动确认 */
  public volatile boolean dismissDialogs;

  /** 控制台日志与页面错误(最多保留 200 条) */
  public final List<String> consoleLogs = Collections.synchronizedList(new ArrayList<String>());
  public final List<String> pageErrors = Collections.synchronizedList(new ArrayList<String>());

  /** 网络请求记录(最多保留 200 条) */
  public final List<Kv> requests = Collections.synchronizedList(new ArrayList<Kv>());
  public final Map<Request, Kv> requestIndex = Collections.synchronizedMap(new IdentityHashMap<Request, Kv>());

  /**
   * 此刻还有几个在途请求
   *
   * <p>记录挂在页签上,所以这个计数也是「这个任务当前页签」的。{@code wait_for_idle} 用它判断网络是否
   * 已经停了——比等固定秒数可靠得多。
   */
  public final AtomicInteger inflight = new AtomicInteger();

  /** 请求记录是从什么时候开始记的(毫秒时间戳):get_requests 用它解释「为什么这里没有你要的请求」 */
  public volatile long recorderAttachedAt;

  /** 最近收到的响应(最多 100 条,带时间戳),get_response_body 与 wait_for_response 回看用 */
  public final Deque<RecordedResponse> recentResponses = new ArrayDeque<>();

  /**
   * 一个已经收到的响应
   *
   * <p>
   * <b>为什么必须把响应体当场抄下来</b>:{@code Response.text()} 是惰性的,浏览器只在很短一段时间内
   * 保留响应体。实测 12306 这种高频轮询的页面上,一条 7 秒前的 XHR 再取 body 就已经是
   * {@code Protocol error (Network.getResponseBody): No resource with given identifier found} ——
   * 「保留最近 100 个响应」于是等于「一个都读不到」,而调用方最想看的恰恰是刚刚提交成功没有。
   * 所以收到响应时(仅在 xhr/fetch 上)立刻异步抄一份 {@link #body}。
   */
  public static class RecordedResponse {
    public final Response response;
    public final long at;
    public final Kv request;

    /** 当场抄下来的响应体(只对 xhr/fetch;抄不到时为 null) */
    public volatile String body;
    /** body 太长时只留前 {@code ResponseBodyCache.MAX_CHARS} 个字符 */
    public volatile boolean bodyTruncated;
    /** 抄完了(无论成败)。没抄完时调用方可以自己再去读一次 */
    public volatile boolean bodyCaptured;
    /** 抄失败的原因(已释放 / 队列满 / 非 xhr-fetch 之外的真实错误) */
    public volatile String bodyCaptureError;
    /** 抄下来的时刻 */
    public volatile long bodyAt;

    public RecordedResponse(Response response, long at) {
      this(response, at, new Kv());
    }

    public RecordedResponse(Response response, long at, Kv request) {
      this.response = response;
      this.at = at;
      this.request = Kv.by("requestId", request.get("requestId")).set(request);
    }
  }

  /** 人机协同请求:requestId -> {prompt, answer, status, createdAt, expiresAt} */
  public final Map<String, Kv> humanRequests = new ConcurrentHashMap<>();
  /** 人机协同请求的序号,用来生成 requestId */
  public final AtomicLong humanSeq = new AtomicLong();

  /** 已注册的路由规则:urlPattern -> {action, body, status, contentType} */
  public final Map<String, Kv> routes = new ConcurrentHashMap<>();
  public final Set<String> routedPatterns = ConcurrentHashMap.newKeySet();

  /**
   * 一个任务的运行时状态
   *
   * <p>
   * 注意这里**不持有** Playwright:它是整个进程共用的 driver(见
   * {@link PlaywrightService#playwright()}),任务之间靠 BrowserContext 里的页签集合与 profile 目录区分。
   */
  public BrowserInstance(long id, BrowserContext ctx, Page pg, Path profileDir, LaunchPersistentContextOptions opts) {
    this.id = id;
    this.context = ctx;
    this.page = pg;
    this.profileDir = profileDir;
    this.opts = opts;
  }
}
