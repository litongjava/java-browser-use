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

  /** 最近一次 get_browser_state 的文本,供 diff_dom_text 比较(每次快照后覆盖) */
  public volatile String lastDomText;

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

  /** 最近收到的响应(最多 100 条,带时间戳),get_response_body 与 wait_for_response 回看用 */
  public final Deque<RecordedResponse> recentResponses = new ArrayDeque<>();

  /** 一个已经收到的响应:留着响应对象,需要时再取 body */
  public static class RecordedResponse {
    public final Response response;
    public final long at;
    public final Kv request;

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
