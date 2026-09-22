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
import java.util.concurrent.atomic.AtomicLong;

import com.jfinal.kit.Kv;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;

import nexus.io.ai.browser.dom.model.DOMState;

public class BrowserInstance {
  public final Playwright playwright;
  public BrowserContext context;
  public Page page;
  public Path profileDir;
  public LaunchPersistentContextOptions opts;
  /** 最近一次 get_dom_text 的 DOM 树快照,按索引操作元素时使用 */
  public DOMState domState;

  /** 最近一次 get_dom_text 的文本,供 diff_dom_text 比较(每次快照后覆盖) */
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

    public RecordedResponse(Response response, long at) {
      this.response = response;
      this.at = at;
    }
  }

  /** 人机协同请求:requestId -> {prompt, answer, status, createdAt, expiresAt} */
  public final Map<String, Kv> humanRequests = new ConcurrentHashMap<>();
  /** 人机协同请求的序号,用来生成 requestId */
  public final AtomicLong humanSeq = new AtomicLong();

  /** 已注册的路由规则:urlPattern -> {action, body, status, contentType} */
  public final Map<String, Kv> routes = new ConcurrentHashMap<>();
  public final Set<String> routedPatterns = ConcurrentHashMap.newKeySet();

  public BrowserInstance(Playwright playwright, BrowserContext ctx, Page pg) {
    this.playwright = playwright;
    this.context = ctx;
    this.page = pg;
  }

  public BrowserInstance(Playwright playwright, BrowserContext ctx, Page pg, Path profileDir, LaunchPersistentContextOptions opts) {
    this.playwright = playwright;
    this.context = ctx;
    this.page = pg;
    this.profileDir = profileDir;
    this.opts = opts;
  }
}
