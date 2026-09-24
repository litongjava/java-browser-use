package nexus.io.ai.browser.service;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import nexus.io.ai.browser.consts.BrowserUserAgent;
import nexus.io.ai.browser.dom.model.DOMElementNode;
import nexus.io.ai.browser.dom.model.DOMState;
import nexus.io.ai.browser.dom.model.FrameSnapshot;
import nexus.io.ai.browser.dom.service.DomService;
import nexus.io.ai.browser.handler.CommandTraceLog;
import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.ai.browser.util.ListenerProbe;
import nexus.io.ai.browser.util.WindowsOcr;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Geolocation;
import com.microsoft.playwright.options.HttpCredentials;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

import lombok.extern.slf4j.Slf4j;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.utils.collect.Lists;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

@Slf4j
public class PlaywrightService {

  private static final ConcurrentHashMap<Long, BrowserInstance> INSTANCES = new ConcurrentHashMap<>();

  /** 本进程的启动时刻:「实例不存在」的报错要用它解释「是不是服务重启过了」 */
  static final long STARTED_AT = System.currentTimeMillis();

  /** 启动时刻的可读形式(报错信息与自省接口共用) */
  static final String STARTED_AT_TEXT = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
      .format(new java.util.Date(STARTED_AT));

  /**
   * 全进程共享的浏览器
   *
   * <p>
   * 一个 Chrome 进程 + 一个 profile(默认就是用户自己那份 Chrome profile),所有任务共用。任务不是
   * 「一套独立的浏览器」,而是共用浏览器上的一组页签。
   *
   * <p>
   * 为什么不能一个任务一份 profile 了:用户数据目录天生是单例 —— 同一个 User Data 目录同时只允许
   * 一个 Chrome 进程,第二个进程会把命令行交给已有实例然后自己退出,Playwright 只会看到「进程退了」。
   * 所以「用用户自己的 profile」与「一个任务一个浏览器」二选一,这里选了前者。
   */
  private static volatile SharedBrowser sharedBrowser;

  /**
   * 全进程共享的 Playwright(driver)
   *
   * <p>
   * 只在 {@link #playwright()} 与 {@link #discardPlaywright()} 里读写,都用类锁保护。
   */
  private static volatile Playwright sharedPlaywright;

  static {
    // driver 是一个 node 子进程,服务被 Ctrl+C / kill 时要把一起收掉,别留下孤儿进程
    // 用用户 profile 时我们自己拉的那个 Chrome 同理,不然会一直占着人家的 profile
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      SharedBrowser browser = sharedBrowser;
      if (browser != null) {
        ChromeLauncher.stop(browser.process);
      }
      Playwright current = sharedPlaywright;
      if (current != null) {
        try {
          current.close();
        } catch (RuntimeException ignored) {
          // 正在退出,关不掉也无所谓
        }
      }
    }, "playwright-driver-shutdown"));
  }

  /** 以函数形式提交的脚本:function、async function、箭头函数 */
  private static final Pattern FUNCTION_LIKE = Pattern
      .compile("^(async\\s+)?(function\\b|\\(|[A-Za-z_$][\\w$]*\\s*=>)");

  /** 语句中的 return,用于识别需要包装成函数体的脚本片段 */
  private static final Pattern RETURN_STATEMENT = Pattern.compile("(^|[;{}\\n])\\s*return[\\s;(]");

  /** 还没有页面快照时回退使用的 CSS 选择器索引空间 */
  private static final String CLICKABLE_SELECTOR = "a, button, input[type=button], input[type=submit]";
  private static final String INPUT_SELECTOR = "input, textarea";
  private static final String FILE_SELECTOR = "input[type=file]";
  private static final String SELECT_SELECTOR = "select";

  /** 按索引操作元素时的等待上限(毫秒):元素已失效时尽快失败,不要挂满 30 秒 */
  private static final double INDEX_ACTION_TIMEOUT_MS = 5_000;

  /** get_browser_state 内联元素清单的默认上限(条):够覆盖绝大多数页面,又不至于把回执撑爆 */
  private static final int DEFAULT_MAX_INLINE_ELEMENTS = 200;

  /** 自动截图开关:关掉后动作照常执行,只是不再每次落图 */
  public static final String KEY_CAPTURE_ENABLED = "browser.capture.enabled";

  /** 动作类命令的超时(毫秒),可用配置项覆盖 */
  public static final String KEY_ACTION_TIMEOUT = "browser.action.timeoutMs";

  /** 原生点击超时后是否自动改用 JS 派发事件,默认开 */
  public static final String KEY_JS_FALLBACK = "browser.action.jsFallback";

  /**
   * 原生点击失败后是否自动改用「真实鼠标点击」(取元素盒子中心派发浏览器真实事件),默认开
   *
   * <p>
   * 它排在 JS 派发**之前**:实测 ant-design 的 {@code Modal.confirm} 确定按钮、对话框右上角 ×
   * 对 JS 派发完全无响应(点了没反应、弹窗不关,反复点还会把确认框一层层叠起来),只有真实鼠标事件才认。
   */
  public static final String KEY_MOUSE_FALLBACK = "browser.action.mouseFallback";

  /** execute_js 的脚本目录(bodyFile 只允许读这个目录下的文件) */
  public static final String KEY_JS_DIR = "browser.js.dir";

  /** JS 设值模式返回给调用方的提醒:它只改 DOM,不保证进入框架(React/Vue)的模型 */
  private static final String JS_MODE_NOTE = "js 模式只改了 DOM 的值并派发了 input/change 事件,"
      + "不保证进入框架(React/Vue)的模型:提交或预览时可能仍然是空的。"
      + "能看见的元素请用 mode=native(默认的 auto 也会优先用真实输入)";


  /** 等待类接口的默认超时(毫秒) */
  private static final double DEFAULT_WAIT_TIMEOUT_MS = 30_000;

  /** 启动浏览器的超时(毫秒),可用配置项覆盖;Playwright 自己默认 180 秒,这里收到 60 秒以便尽快报错 */
  public static final String KEY_LAUNCH_TIMEOUT = "browser.launch.timeoutMs";

  /** 启动超时默认值(毫秒):正常启动只要几秒,60 秒足够覆盖冷启动与杀毒软件扫描 */
  private static final double DEFAULT_LAUNCH_TIMEOUT_MS = 60_000;

  /**
   * 是否开启 Chromium 沙箱
   *
   * <p>
   * 不配时按平台与浏览器默认(非 Linux 且不是 Edge 时开,其余关),见 {@link #chromiumSandbox(BrowserChoice)}。
   */
  public static final String KEY_SANDBOX = "browser.chromium.sandbox";

  /** 用用户 profile 时,等 Chrome 把调试端口打出来的上限(毫秒) */
  private static final long CDP_LAUNCH_TIMEOUT_MS = 60_000;

  /** 用用户 profile 时,connectOverCDP 的连接超时(毫秒) */
  private static final double CDP_CONNECT_TIMEOUT_MS = 30_000;

  /** 记录请求体/返回体时的截断长度(字符),避免把大响应塞进内存和日志 */
  private static final int MAX_RECORDED_BODY_CHARS = 4_000;

  /** wait_for_response / get_response_body 返回响应体时的默认截断长度 */
  private static final int DEFAULT_RESPONSE_BODY_CHARS = 20_000;

  /** 人机协同请求的默认等待时长(秒) */
  private static final int DEFAULT_HUMAN_TIMEOUT_SECONDS = 300;

  /** diff_dom_text 单侧最多返回的行数 */
  private static final int MAX_DIFF_LINES = 200;

  /** wait_for_response 默认先回看多少秒内已经收到过的响应 */
  private static final int DEFAULT_RESPONSE_LOOKBACK_SECONDS = 10;

  /** 截图与可交互结构化文本的落盘根目录(相对进程工作目录,由 /data/** 静态路由对外提供) */
  public static final String DATA_DIR = "data";

  /** 每次截图前最多等页面进入 DOMCONTENTLOADED 多久(毫秒),等不到也照常截图 */
  private static final double CAPTURE_SETTLE_TIMEOUT_MS = 1_500;

  /**
   * 启动一个任务的浏览器(浏览器类型按配置里的默认值)
   *
   * <p>等价于 {@code start(id, headless, null)},见 {@link #start(Long, boolean, String)}。
   */
  public long start(Long id, boolean headless) {
    return start(id, headless, null);
  }

  /**
   * 启动一个任务的浏览器,并指定用哪个浏览器
   *
   * <p>
   * 浏览器与 profile 是全进程共用的(见 {@link SharedBrowser}):所有任务用同一个浏览器进程、同一份
   * profile,任务之间靠**页签**隔离。所以这里的 {@code browser} 决定的是「这台服务当前用哪个浏览器」,
   * 而不是「这个任务单独起一个浏览器」。
   *
   * <p>
   * {@code browser} 的取值见 {@link BrowserChoice}:{@code auto}(不传时的默认,行为与以前完全一致:本机
   * Google Chrome 优先,没装退回内置 Chromium)、{@code chromium}(只内置那一份,不碰本机 Chrome)、
   * {@code chrome}(只本机 Google Chrome,没装直接失败)、{@code edge}(只本机 Microsoft Edge,没装直接
   * 失败)、{@code firefox}(Playwright 自带的那份)。
   *
   * <p>
   * 换浏览器等于换一个进程(profile 也跟着换):正在跑的任务与这个类型不一致时,这里会明确报错而不是
   * 悄悄换掉别人的浏览器 —— 先 {@code close} 掉在跑的任务再换。
   *
   * @param id       任务 ID,传 null 时自动生成雪花 ID
   * @param headless 是否无头
   * @param browser  浏览器类型;null 或空串表示用配置里的默认值({@code browser.type} / 老的
   *                 {@code browser.engine})
   * @return 该任务使用的 ID
   * @throws IllegalArgumentException 传了不认识的值
   */
  public long start(Long id, boolean headless, String browser) {
    BrowserChoice requested = null;
    if (browser != null && !browser.isBlank()) {
      requested = BrowserChoice.parse(browser);
      if (requested == null) {
        throw new IllegalArgumentException("无法识别的浏览器类型：" + browser + "，可选值：" + BrowserChoice.choices());
      }
    }
    long taskId = id == null ? SnowflakeIdUtils.id() : id;
    if (INSTANCES.containsKey(taskId)) {
      throw new IllegalStateException("该 id 已经有正在运行的浏览器实例：" + taskId + "，请先调用 close，或换一个 id");
    }
    long startedAt = System.currentTimeMillis();
    // 「这份 profile 之前用过吗」必须在启动**之前**判断:启动之后目录一定存在,再问就永远是「用过」
    java.util.Set<String> existedBefore = profileDirsExistingBeforeLaunch(requested);
    SharedBrowser shared = sharedBrowser(headless, requested);
    applyProfileHistory(shared, existedBefore);
    BrowserInstance instance = newTaskInstance(taskId, shared);
    INSTANCES.put(taskId, instance);
    log.info("task {} 浏览器就绪(browser={}),耗时 {}ms", taskId, shared.resolvedType.id(),
        System.currentTimeMillis() - startedAt);
    return taskId;
  }

  // ==================== profile 的登录态来历 ====================

  /**
   * profile 目录里的标记文件:记下「上次是哪次启动、哪个引擎用的这份 profile」
   *
   * <p>放在 profile 目录里而不是别处,因为它描述的正是「这份 profile」;随 profile 一起被清掉也是对的。
   */
  static final String PROFILE_MARKER = ".dsh-browser-use-profile.json";

  /**
   * 启动前把「候选 profile 目录里已经有内容」的那些记下来
   *
   * <p>启动之后目录一定存在(浏览器自己会建),再问就永远是「用过」,所以必须在启动前问。
   * 判断口径是「目录存在**且非空**」:空目录(比如刚创建的临时目录)等于没有登录态。
   */
  private static java.util.Set<String> profileDirsExistingBeforeLaunch(BrowserChoice requested) {
    java.util.Set<String> existing = new java.util.HashSet<>();
    BrowserChoice type = BrowserChoice.resolve(requested);
    List<Path> candidates = new ArrayList<>();
    candidates.add(type.profileDir());
    candidates.add(ChromeBrowser.managedProfileDir());
    candidates.add(EdgeBrowser.managedProfileDir());
    candidates.add(ChromeBrowser.userDataDir());
    for (Path dir : candidates) {
      if (dir == null) {
        continue;
      }
      try {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
          continue;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(normalized)) {
          if (entries.findFirst().isPresent()) {
            existing.add(normalized.toString());
          }
        }
      } catch (IOException | RuntimeException e) {
        // 拿不到就当不存在:这只是「顺便优化一下提示语」,不该影响启动
      }
    }
    return existing;
  }

  /**
   * 记下这份 profile 的来历,并刷新标记文件
   *
   * <p>
   * <b>为什么要做这件事</b>:P6 那次踩坑是「默认引擎被配成了 firefox、而登录态都在 Chromium 的 profile 里」,
   * 于是 {@code start} 之后所有站点都退登录 —— 而回执里的 {@code engineHonored:true} 只说明参数被采纳了,
   * 不说明这份 profile 里有登录态。这里把「之前用过吗、上次是什么引擎」记在 {@link SharedBrowser} 上,
   * 由 {@code browserInfo} 写进回执。
   *
   * <p>用户自己的 Chrome profile 不写标记(那是人家的目录,不该塞私有文件);这种情况靠
   * {@code userProfile=true} 本身就能说明「登录态是用户日常那份」。
   */
  private static void applyProfileHistory(SharedBrowser shared, java.util.Set<String> existedBefore) {
    if (shared == null || shared.profileDir == null) {
      return;
    }
    Path marker = shared.profileDir.resolve(PROFILE_MARKER);
    Kv recorded = readProfileMarker(marker);
    shared.previousProfileEngine = recorded == null ? null : recorded.getStr("engine");
    shared.previousProfileBrowser = recorded == null ? null : recorded.getStr("browser");
    boolean existed = existedBefore.contains(shared.profileDir.toAbsolutePath().normalize().toString());
    shared.profileSeenBefore = recorded != null || existed;
    if (shared.userProfile) {
      return;
    }
    writeProfileMarker(marker, shared.engine.id(), shared.resolvedType.id());
  }

  /** 读标记文件;不存在或读不出来都返回 null */
  private static Kv readProfileMarker(Path marker) {
    try {
      if (!Files.isRegularFile(marker)) {
        return null;
      }
      JSONObject json = JSON.parseObject(Files.readString(marker, StandardCharsets.UTF_8));
      return json == null ? null : new Kv().set((Map<String, Object>) json);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static void writeProfileMarker(Path marker, String engine, String browser) {
    try {
      Files.createDirectories(marker.getParent());
      JSONObject json = new JSONObject();
      json.put("engine", engine);
      json.put("browser", browser);
      json.put("updatedAt", System.currentTimeMillis());
      Files.writeString(marker, json.toJSONString(), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      log.debug("写 profile 标记失败:{}", e.getMessage());
    }
  }

  /**
   * 一句话说清「这份 profile 里有没有登录态」
   *
   * <p>{@code engineHonored:true} 只说明浏览器参数被采纳了;「这份 profile 之前用过吗」是另一件事,而这
   * 件事决定了「start 之后要不要重新登录」。所以这里把两者分开说清楚。
   */
  static String profileLoginNote(SharedBrowser browser) {
    if (browser == null) {
      return null;
    }
    StringBuilder note = new StringBuilder();
    if (browser.userProfile) {
      note.append("用的是用户自己的 Chrome profile(userProfile=true):登录态就是用户日常那份,通常不需要重新登录。");
    } else if (Boolean.FALSE.equals(browser.profileSeenBefore)) {
      note.append("该 profile 目录本次是**首次创建**,里面没有任何登录态:任何需要登录的站点都要重新登录一次")
          .append("(登录之后会留在这份 profile 里,后续任务不用再登)。");
    } else if (browser.previousProfileEngine != null && !browser.previousProfileEngine.equals(browser.engine.id())) {
      note.append("引擎从 ").append(browser.previousProfileEngine).append(" 切到 ").append(browser.engine.id())
          .append(":两种引擎的 profile 格式不通用,**登录态不通用**,需要重新登录。");
    } else if (Boolean.TRUE.equals(browser.profileSeenBefore)) {
      note.append("这份 profile 之前用过(上次是 browser=")
          .append(browser.previousProfileBrowser == null ? "未知" : browser.previousProfileBrowser)
          .append("):里面已有的登录态应该还在。");
    }
    if (browser.profileNote != null && !browser.profileNote.isEmpty()) {
      if (note.length() > 0) {
        note.append(' ');
      }
      note.append(browser.profileNote);
    }
    return note.length() == 0 ? null : note.toString();
  }

  /**
   * 共享浏览器:一个浏览器进程、一份 profile,多个任务共用
   *
   * <p>
   * 用哪个浏览器由 {@link #resolvedType} 记着(内置 Chromium / 本机 Chrome / 本机 Edge / Firefox,
   * 见 {@link BrowserChoice}):类型、可执行文件、profile 目录都是**浏览器级**属性,同一个浏览器进程
   * 不能中途换,所以换类型要等任务都关掉(见 {@link #sharedBrowser(boolean, BrowserChoice)})。
   *
   * <p>
   * 之所以不能再「一个任务一个浏览器」:用户数据目录天生是单例 —— 同一个 User Data 目录同时只允许
   * 一个浏览器进程,第二个进程会把命令行交给已有实例然后自己退出。所以「用用户自己的 profile」与
   * 「一个任务一个浏览器」只能二选一,这里选了前者。
   */
  static final class SharedBrowser {
    /** 浏览器实际使用的 profile 目录 */
    final Path profileDir;
    /** 实际使用的可执行文件;null 表示交给 Playwright 自己解析 */
    final Path executable;
    /** 用的是本机安装的 Google Chrome(而不是内嵌/Playwright 自带的 Chromium) */
    final boolean chrome;
    /** profileDir 是用户自己的 Chrome 用户数据目录 */
    final boolean userProfile;
    final boolean headless;
    /** 这次用的是哪个引擎(见 {@link BrowserEngine},由 {@link BrowserChoice} 决定) */
    final BrowserEngine engine;
    /**
     * 实际用的浏览器类型,已经落成确定的那个值({@code auto} 不会出现在这里)
     *
     * <p>请求方要 {@code auto} 时,这里记的是它最终落到的那个(本机 Chrome 或内置 Chromium),
     * 否则「这次到底用的哪个浏览器」就没法如实回报了。
     */
    final BrowserChoice resolvedType;
    /** 持久化上下文模式的启动参数;CDP 模式(自己拉 Chrome)下为 null */
    final LaunchPersistentContextOptions opts;
    /** 没能用上用户 profile 时的原因,会一起返回给调用方 */
    final String profileNote;

    /**
     * 这份 profile 在这次启动**之前**就已经有内容了吗
     *
     * <p>
     * {@code start} 回执里的 {@code engineHonored:true} 只说明「浏览器参数被采纳了」,不说明「这份 profile 里
     * 有登录态」。实测踩过:服务端默认引擎被配成 firefox,而登录态都在 Chromium 那份 profile 里,于是
     * 「所有站点都退登录了」,但回执里没有任何线索。这个字段直接回答「这份 profile 之前用过吗」。
     *
     * <p>null 表示没测到(还没启动过或目录不可读)。
     */
    volatile Boolean profileSeenBefore;
    /** 上次用这份 profile 的引擎(读自 profile 目录里的标记文件);没记录过为 null */
    volatile String previousProfileEngine;
    /** 上次用这份 profile 的浏览器类型(读自标记文件);没记录过为 null */
    volatile String previousProfileBrowser;
    volatile BrowserContext context;
    /** CDP 模式(自己拉 Chrome)下的连接;托管 profile 模式为 null */
    volatile Browser browser;
    /** CDP 模式下我们自己拉起来的 Chrome 进程 */
    volatile Process process;
    /**
     * 接上时就已经存在的页签
     *
     * <p>
     * 用用户自己的 profile 时,Chrome 可能把上次的会话恢复出来 —— 那些页签是用户自己的,不属于任何
     * 任务,任何任务都不该认领它们(否则第一个任务会直接操作人家正在看的页面)。
     */
    final Set<Page> foreignPages = ConcurrentHashMap.newKeySet();

    SharedBrowser(Path profileDir, Path executable, boolean chrome, boolean userProfile, boolean headless,
        LaunchPersistentContextOptions opts, String profileNote, BrowserChoice resolvedType) {
      this.profileDir = profileDir;
      this.executable = executable;
      this.chrome = chrome;
      this.userProfile = userProfile;
      this.headless = headless;
      this.opts = opts;
      this.profileNote = profileNote;
      this.resolvedType = resolvedType == null ? BrowserChoice.resolve(null) : resolvedType;
      // 引擎由类型推出来,不单独记:两者能不一致的写法迟早会不一致
      this.engine = this.resolvedType.engine();
    }

    /**
     * 这次请求要的浏览器,是不是就是正在跑的这个
     *
     * <p>
     * 浏览器类型、引擎、有头/无头、可执行文件、profile 目录都是浏览器级别的属性,只有全对得上才能复用;
     * 对不上就得换一个进程(所以在任务运行中不能切换,见 {@link #sharedBrowser(boolean, BrowserChoice)})。
     *
     * @param requested 这次请求的类型;null 表示没指定(按配置里的默认值算)
     */
    boolean matches(boolean requestedHeadless, BrowserChoice requested) {
      BrowserChoice want = BrowserChoice.resolve(requested);
      if (resolvedType != want || engine != want.engine()) {
        return false;
      }
      if (headless != requestedHeadless) {
        return false;
      }
      if (!Objects.equals(executable, want.executable())) {
        return false;
      }
      Path configured = userProfile ? ChromeBrowser.userDataDir() : want.profileDir();
      return configured != null && configured.toAbsolutePath().normalize().equals(profileDir.toAbsolutePath().normalize());
    }

    /** 一句话说清「现在是哪个浏览器」,用在「不能中途切换」的报错里 */
    String describe() {
      return "browser=" + resolvedType.id() + "，headless=" + headless + "，profile=" + profileDir;
    }
  }

  /**
   * 拿共享浏览器,没有就起一个
   *
   * <p>
   * 浏览器类型、有头/无头、可执行文件、profile 目录都是浏览器级别的属性,同一个浏览器不能中途切换:
   * <ul>
   * <li>正在跑的浏览器与这次请求**完全一致** → 直接复用;</li>
   * <li>不一致但已经没有任务在跑 → 关掉它,按这次请求重新起一个(所以换浏览器类型、改配置都不用重启服务);</li>
   * <li>不一致而还有任务在跑 → 明确报错,既不悄悄沿用旧配置,也不把别人的页签弄没。</li>
   * </ul>
   *
   * @param requested 这次请求的浏览器类型;null 表示没指定(按配置里的默认值算)
   */
  private static SharedBrowser sharedBrowser(boolean headless, BrowserChoice requested) {
    SharedBrowser current = sharedBrowser;
    if (current != null) {
      if (current.matches(headless, requested)) {
        return current;
      }
      if (!INSTANCES.isEmpty()) {
        throw new IllegalStateException("浏览器已经在运行（" + current.describe() + "）：所有任务共用同一个浏览器，"
            + "不能中途切换浏览器类型、有头/无头或 profile，请先 close 掉正在运行的任务；"
            + "想同时用两个浏览器（例如一边 Chrome 一边 Edge）请再起一个服务进程，并给它们配不同的端口与 profile 目录");
      }
      log.info("浏览器配置与正在运行的不一致,重建共享浏览器({} -> browser={}, headless={})", current.describe(),
          BrowserChoice.resolve(requested).id(), headless);
      closeSharedBrowser();
    }
    synchronized (PlaywrightService.class) {
      if (sharedBrowser == null) {
        sharedBrowser = launchSharedBrowser(headless, requested);
      }
      return sharedBrowser;
    }
  }

  /**
   * 起浏览器:按 {@link BrowserChoice} 分成几条路
   *
   * <p>
   * 共同点是「托管 profile + 持久化上下文」,差别只在用哪个可执行文件、哪份 profile,以及要不要走
   * 「用户自己的 Chrome profile + CDP」那条路:
   * <ul>
   * <li><b>firefox</b>:Playwright 自带的那份 Firefox,没有 CDP、没有 {@code --profile-directory};</li>
   * <li><b>edge</b>:本机 Edge + Edge 自己一份托管 profile(Edge 打不开 Chrome 的 profile);</li>
   * <li><b>chromium</b>:内置 Chromium(发行包内嵌,开发态是 Playwright 自带的那份),不碰本机 Chrome;</li>
   * <li><b>chrome</b>:本机 Google Chrome,必要时走 CDP(见 {@link #launchOverCdp});</li>
   * <li><b>auto</b>(不传 {@code browser} 时的默认):本机 Chrome 优先,没装退回内置 Chromium,并把原因放进
   * {@code start} 返回的 {@code data.browser.note} —— 与以前的版本完全一致。</li>
   * </ul>
   *
   * <p>
   * 显式的 {@code chrome} / {@code edge} 与 {@code auto} 的关键差别是**不悄悄退让**:明明要了 Edge 却起了
   * Chrome,页面表现不一样,事后很难查,所以这条路直接失败并说清怎么改(见
   * {@link BrowserChoice#notFoundMessage()})。
   *
   * @param requested 这次请求的浏览器类型;null 表示没指定(按配置里的默认值算)
   */
  private static SharedBrowser launchSharedBrowser(boolean headless, BrowserChoice requested) {
    BrowserChoice type = BrowserChoice.resolve(requested);
    if (type.isFirefox()) {
      return launchFirefox(headless);
    }
    if (type.isEdge()) {
      return launchEdge(headless);
    }
    return launchChromiumFamily(headless, requested);
  }

  /**
   * Firefox:Playwright 自带的那份,配同一份托管 profile
   *
   * <p>
   * 这条路没有「本机安装的 Chrome / 用户自己的 profile / CDP」这些概念:Playwright 的 Firefox 是一份
   * 打过补丁的构建(走 juggler 协议),本机装的普通 Firefox 接不上,所以能用上的只有它自己那份。
   */
  private static SharedBrowser launchFirefox(boolean headless) {
    Path firefox = BrowserEngine.firefoxExecutablePath();
    String note = firefox == null
        ? "browser=firefox：使用 Playwright 自带的 Firefox（本机安装的普通 Firefox 接不上 Playwright 的 juggler 协议，所以不复用）"
        : null;
    log.info("启动 Firefox（browser=firefox）：profileDir={}, headless={}", BrowserChoice.FIREFOX.profileDir(), headless);
    return launch(firefox, BrowserChoice.FIREFOX.profileDir(), false, headless, note, null, BrowserChoice.FIREFOX);
  }

  /**
   * Edge:本机安装的 Microsoft Edge + Edge 自己一份托管 profile
   *
   * <p>
   * 没装就明确失败,不回退 —— 想要别的浏览器请显式改 {@code browser}(见 {@link BrowserChoice#choices()})。
   */
  private static SharedBrowser launchEdge(boolean headless) {
    Path edge = EdgeBrowser.executablePath();
    if (edge == null) {
      throw new IllegalStateException(BrowserChoice.EDGE.notFoundMessage());
    }
    Path profileDir = BrowserChoice.EDGE.profileDir();
    // 全新的空 profile 目录会让 Edge 走首启引导(向导页/默认浏览器询问),补一个哨兵文件跳过它
    EdgeBrowser.prepareProfileDir(profileDir);
    String note = "browser=edge：使用本机安装的 Microsoft Edge（profile 是 Edge 自己一份，与本机 Chrome 那份不通用）";
    log.info("启动 Microsoft Edge（browser=edge）：executable={}, profileDir={}, headless={}", edge, profileDir,
        headless);
    // 走「自己拉进程 + CDP」那条路,而不是 Playwright 的 launchPersistentContext:后者用管道调试,
    // 而 Edge 配上沙箱时管道启动会立刻退出(见 chromiumSandbox 的说明)
    return launchOverCdp(edge, profileDir, false, headless, note, BrowserChoice.EDGE);
  }

  /**
   * Chromium 系:内置 Chromium、本机 Chrome,以及 {@code auto} 的「本机 Chrome 优先」这条路
   *
   * <p>
   * {@code useUserProfile}(用你日常那份 Chrome profile)只对本机 Chrome 有意义:内置 Chromium 没有用户
   * 数据目录这个概念,而 Edge 走的是它自己的方法。
   */
  private static SharedBrowser launchChromiumFamily(boolean headless, BrowserChoice requested) {
    BrowserChoice type = BrowserChoice.resolve(requested);
    if (type == BrowserChoice.CHROMIUM) {
      Path bundled = BundledBrowser.executablePath();
      String note;
      if (requested == BrowserChoice.CHROMIUM) {
        note = bundled == null
            ? "browser=chromium：开发态没有内嵌 Chromium，用 Playwright 自带的 Chromium（首次使用会自动下载）"
            : "browser=chromium：使用内置 Chromium，不使用本机安装的 Google Chrome";
      } else {
        // 只有 auto 会走到这里:本机没有可用的 Chrome,按老规矩退回内置 Chromium
        note = "没有找到本机安装的 Google Chrome，改用内嵌/Playwright 自带的 Chromium";
      }
      return launch(bundled, BrowserChoice.CHROMIUM.profileDir(), false, headless, note, null, BrowserChoice.CHROMIUM);
    }

    // 到这里 type 一定是 CHROME:显式要了本机 Chrome,或者在 auto 下这台机器上确实有 Chrome
    Path chrome = ChromeBrowser.executablePath();
    if (chrome == null) {
      throw new IllegalStateException(BrowserChoice.CHROME.notFoundMessage());
    }
    Path userDataDir = !ChromeBrowser.useUserProfile() ? null : ChromeBrowser.userDataDir();
    boolean userProfileFallback = ChromeBrowser.profileFallback();
    String note = null;
    if (userDataDir == null && ChromeBrowser.useUserProfile()) {
      note = "没有找到 Google Chrome 的用户数据目录，改用托管 profile";
    } else if (userDataDir != null && ChromeBrowser.profileInUse(userDataDir)) {
      note = "Google Chrome 正在运行，用户 profile 被占用，改用托管 profile（关掉 Chrome 后重新 start 即可用上用户 profile）";
      userDataDir = null;
    }

    if (userDataDir != null) {
      try {
        return launchOverCdp(chrome, userDataDir, true, headless, null, BrowserChoice.CHROME);
      } catch (RuntimeException e) {
        if (!userProfileFallback) {
          throw e;
        }
        note = "用用户 profile 启动失败，改用托管 profile：" + briefMessage(e.getMessage())
            + profileBusyHint(e);
        log.warn("用 Google Chrome 用户 profile 启动失败,改用托管 profile:{}", briefMessage(e.getMessage()));
      }
    } else if (note != null && !userProfileFallback) {
      throw new IllegalStateException("无法使用 Google Chrome 用户 profile：" + note
          + "（browser.chrome.profileFallback=false 时不会退回托管 profile）");
    }
    return launch(chrome, BrowserChoice.CHROME.profileDir(), false, headless, note, null, BrowserChoice.CHROME);
  }

  /**
   * 自己拉进程、再用 CDP 接上
   *
   * <p>
   * 走这条路而不是 Playwright 的 {@code launchPersistentContext},是因为那条路用
   * {@code --remote-debugging-pipe}:
   * <ul>
   * <li>本机 Chrome 的用户 profile:Chrome 136 起拒绝在默认用户数据目录上用 pipe 调试,只能换成
   * {@code --remote-debugging-port}(见 {@link ChromeLauncher});</li>
   * <li>本机 Edge:开沙箱时 pipe 启动会让 Edge 立刻退出,换端口就正常(见
   * {@link #chromiumSandbox(BrowserChoice)})。</li>
   * </ul>
   *
   * <p>
   * 代价是拿不到 Playwright 持久化上下文那些便利(视口、下载目录、HTTP 认证都要在创建时给),所以视口与
   * 弹窗相关的东西改成启动参数,见 {@link #cdpArgs(boolean, BrowserChoice)};HTTP 认证凭据
   * ({@code set_credentials})在这条路上用不了,调用时会明确失败。
   *
   * @param executable  要拉起来的浏览器可执行文件
   * @param profileDir  用户数据目录(Chrome 的用户 profile,或 Edge 自己那份托管 profile)
   * @param userProfile 这次用的是不是用户自己的 Chrome profile(决定要不要认领已有页签、要不要带
   *                    {@code --profile-directory})
   * @param type        浏览器类型:决定额外启动参数取哪一份、以及返回里的 {@code type}
   */
  private static SharedBrowser launchOverCdp(Path executable, Path profileDir, boolean userProfile, boolean headless,
      String profileNote, BrowserChoice type) {
    List<String> args = cdpArgs(headless, type);
    // --profile-directory 是本机 Chrome 用户数据目录里的概念,Edge 自己那份托管 profile 没有
    args.addAll(type.profileArgs());
    args.addAll(type.extraArgs());
    ChromeLauncher.Launched launched = ChromeLauncher.launch(executable, profileDir, args, CDP_LAUNCH_TIMEOUT_MS);
    try {
      Browser browser = playwright().chromium().connectOverCDP(launched.endpoint(),
          new BrowserType.ConnectOverCDPOptions().setTimeout(CDP_CONNECT_TIMEOUT_MS));
      List<BrowserContext> contexts = browser.contexts();
      if (contexts.isEmpty()) {
        throw new IllegalStateException("连上了浏览器,但没拿到默认浏览器上下文");
      }
      BrowserContext context = contexts.get(0);
      log.info("已接上浏览器(自己拉进程 + CDP):browser={}, profile={}, 调试端点 {}", type.id(), profileDir,
          launched.endpoint());

      SharedBrowser shared = new SharedBrowser(profileDir, executable, type.isGoogleChrome(), userProfile, headless,
          null, profileNote, type);
      shared.browser = browser;
      shared.process = launched.process();
      shared.context = context;
      // CDP 模式下**一律不认领接上时已经存在的页签**,任务宁可自己新开一个,原因有两个:
      // 1. 用用户 profile 时,那些页签是用户自己的(可能恢复了上次的会话),任何任务都不该动;
      // 2. 自己拉进程时,浏览器启动时那个页签(新标签页/会话恢复)会被它自己的启动流程换掉 ——
      //    实测认领它之后第一个 go_to_url 会报「Frame has been detached」。
      shared.foreignPages.addAll(context.pages());
      if (!shared.foreignPages.isEmpty()) {
        log.info("接上时已有 {} 个页签,不会被任何任务认领(任务各自新开页签)", shared.foreignPages.size());
      }
      browser.onDisconnected(disconnected -> onSharedBrowserClosed(shared));
      return shared;
    } catch (RuntimeException e) {
      ChromeLauncher.stop(launched.process());
      throw e;
    }
  }

  /**
   * 「启动后立即退出」时补一句最可能的原因
   *
   * <p>
   * 这个失败几乎只有一个来源:那个 profile 上已经有一个浏览器在运行,新进程把命令行交给它然后自己退出。
   * 命令行读得到时 {@link ChromeBrowser#profileInUse(Path)} 会提前拦住,读不到时只能走到这里 —— 所以
   * 这句话是调用方唯一能得到的解释,不能只丢一句「进程退出码 0」。
   */
  private static String profileBusyHint(RuntimeException e) {
    if (e instanceof ChromeLauncher.ExitedEarlyException) {
      return "（这个 profile 上很可能已经有一个浏览器在运行：新进程会把命令行交给它然后自己退出。"
          + "关掉之后重新 start 就能用上用户 profile）";
    }
    return "";
  }

  /**
   * 真正启动浏览器(托管 profile 路径):参数已经算好,这里只负责 launchPersistentContext 与上下文级监听
   *
   * @param executable 这次要用的浏览器可执行文件;null 表示交给 Playwright 自己解析(内置 Chromium 在
   *                   开发态、内置 Firefox 都是这条路)
   * @param type       这次用的浏览器类型,决定引擎、启动参数体例,以及返回里的 {@code browser}
   */
  private static SharedBrowser launch(Path executable, Path profileDir, boolean userProfile, boolean headless,
      String profileNote, LaunchPersistentContextOptions existingOptions, BrowserChoice type) {
    BrowserChoice resolved = type == null ? BrowserChoice.resolve(null) : type;
    LaunchPersistentContextOptions opts = existingOptions != null ? existingOptions
        : resolved.isFirefox() ? buildFirefoxOptions(headless, executable)
            : buildOptions(headless, executable, userProfile, resolved);
    try {
      Files.createDirectories(profileDir);
    } catch (IOException e) {
      log.warn("创建 profile 目录失败 {}:{}", profileDir, e.getMessage());
    }
    log.info("启动浏览器:browser={}, engine={}, executable={}, profileDir={}, 用户 profile={}, headless={}",
        resolved.id(), resolved.engine().id(), executable, profileDir, userProfile, headless);
    BrowserContext context = launchContext(profileDir, opts, resolved.engine());
    SharedBrowser browser = new SharedBrowser(profileDir, executable, resolved.isGoogleChrome(), userProfile,
        headless, opts, profileNote, resolved);
    browser.context = context;
    context.onClose(closed -> onSharedBrowserClosed(browser));
    return browser;
  }

  /**
   * 共享浏览器被关掉:最后一个窗口被人工关掉、浏览器崩了,或者我们自己 close
   *
   * <p>
   * 页签跟着浏览器一起没了,所以把所有任务一起清掉,下一次 {@code start} 会重新拉起浏览器。这里
   * **不抢类锁**:主动 {@code context.close()} 时这个回调是在关闭流程里触发的,抢锁会和调用方互相等待。
   */
  private static void onSharedBrowserClosed(SharedBrowser browser) {
    if (sharedBrowser == browser) {
      sharedBrowser = null;
    }
    for (BrowserInstance instance : INSTANCES.values()) {
      if (instance.context == browser.context) {
        instance.detached = true;
        INSTANCES.remove(instance.id);
        log.info("浏览器已关闭,任务 {} 一并移除", instance.id);
      }
    }
  }

  /**
   * 给任务分配页签
   *
   * <p>
   * 优先用浏览器里还没有归属的页签(启动时那个 about:blank),没有就新开一个。任务之间靠页签隔离,
   * 所以每个任务拿到的都是自己的一组页签。
   */
  private BrowserInstance newTaskInstance(long taskId, SharedBrowser browser) {
    BrowserContext context = browser.context;
    Page page = unclaimedPage(browser);
    if (page == null) {
      page = context.newPage();
    }
    BrowserInstance instance = new BrowserInstance(taskId, context, page, browser.profileDir, browser.opts);
    claimPage(instance, page);
    return instance;
  }

  /**
   * 浏览器里还没有归属任务的页签(通常是启动时那个 about:blank)
   *
   * <p>
   * 用用户自己的 profile 时,Chrome 可能把上次的会话恢复出来,那些页签是用户自己的
   * ({@link SharedBrowser#foreignPages}),不算「没人认领」,任务宁可自己新开一个页签。
   */
  private static Page unclaimedPage(SharedBrowser browser) {
    Set<Page> claimed = new HashSet<>(browser.foreignPages);
    for (BrowserInstance instance : INSTANCES.values()) {
      claimed.addAll(instance.pages);
    }
    for (Page page : browser.context.pages()) {
      if (!claimed.contains(page)) {
        return page;
      }
    }
    return null;
  }

  /**
   * 把一个页签划给某个任务
   *
   * <p>
   * 新开的页签(弹窗、new_tab、最后一个页签被人工关掉后的补页签)都要走这里:登记归属、挂监听、
   * 补上任务已经注册过的路由规则。重复认领同一个页签是安全的,监听不会挂两遍。
   */
  private void claimPage(BrowserInstance instance, Page page) {
    if (page == null || !instance.pages.add(page)) {
      return;
    }
    // 屏蔽 navigator.webdriver:每个页签都要挂一次,弹窗也算
    try {
      page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => false});");
    } catch (PlaywrightException e) {
      log.debug("挂载 webdriver 屏蔽脚本失败:{}", briefMessage(e.getMessage()));
    }
    attachListeners(instance, page);
    attachRequestRecorder(instance, page);
    applyRoutes(instance, page);
    if (instance.page == null || instance.page.isClosed()) {
      instance.page = page;
    }
  }

  /** 任务自己的页签,顺序与浏览器里的顺序一致(索引即 switch_tab 的 pageIndex) */
  static List<Page> pagesOf(BrowserInstance instance) {
    List<Page> all = instance.context.pages();
    if (instance.pages.size() > all.size()) {
      // 页签被外部关掉后清掉残留引用,避免集合无限增长
      instance.pages.retainAll(new HashSet<>(all));
    }
    List<Page> mine = new ArrayList<>();
    for (Page page : all) {
      if (instance.pages.contains(page)) {
        mine.add(page);
      }
    }
    return mine;
  }

  /**
   * 这次任务实际用的浏览器与 profile,放在 {@code start} 的返回里,便于确认有没有用上用户的登录态
   *
   * <p>
   * {@code type} 是实际用的浏览器类型(见 {@link BrowserChoice},{@code auto} 已经落成确定值),
   * {@code chrome} 表示「用的是不是本机安装的 Google Chrome」,{@code userProfile} 表示「用的是不是用户
   * 自己那份 Chrome profile(现成的登录态)」,{@code mode} 是 {@code cdp} / {@code managed}。
   */
  public Kv browserInfo(Long taskId) {
    BrowserInstance instance = INSTANCES.get(taskId);
    SharedBrowser browser = sharedBrowser;
    if (instance == null || browser == null) {
      return null;
    }
    Kv info = Kv.by("chrome", browser.chrome).set("userProfile", browser.userProfile)
        .set("engine", browser.engine.id())
        // 这次实际用的浏览器类型:已经落成确定的值(auto 不会出现在这里),觉得「怎么不是我用惯的那个」
        // 时先看它
        .set("type", browser.resolvedType.id())
        .set("profileDir", browser.profileDir.toAbsolutePath().toString())
        .set("headless", browser.headless)
        // cdp = 自己拉的用户 Chrome(connectOverCDP),managed = Playwright 持久化上下文
        .set("mode", browser.browser != null ? "cdp" : "managed");
    if (browser.resolvedType.isGoogleChrome()) {
      // --profile-directory 是本机 Chrome 的概念:内置 Chromium 不传它,Edge 与 Firefox 也没有这一项
      info.set("profileDirectory", ChromeBrowser.profileDirectory());
    }
    if (browser.executable != null) {
      info.set("executable", browser.executable.toAbsolutePath().toString());
    }
    if (browser.profileNote != null) {
      info.set("note", browser.profileNote);
    }
    // 「这份 profile 里有没有登录态」:engineHonored:true 回答不了这件事,但它决定了要不要重新登录
    if (browser.profileSeenBefore != null) {
      info.set("profileSeenBefore", browser.profileSeenBefore);
    }
    if (browser.previousProfileEngine != null) {
      info.set("previousEngine", browser.previousProfileEngine);
      info.set("previousBrowser", browser.previousProfileBrowser);
    }
    String loginNote = profileLoginNote(browser);
    if (loginNote != null) {
      info.set("profileNote", loginNote);
    }
    // 这次任务的调用追踪日志落在哪、暂存上传目录在哪:客户端-服务器模式下用户要知道去哪清理敏感数据
    info.set("trace", Kv.by("dir", CommandTraceLog.currentDir().toString())
        .set("enabled", Boolean.parseBoolean(String.valueOf(
            ChromeBrowser.config(CommandTraceLog.KEY_ENABLED) == null ? "true"
                : ChromeBrowser.config(CommandTraceLog.KEY_ENABLED))))
        .set("redact", CommandTraceLog.redactEnabled()));
    info.set("upload", Kv.by("dir", UploadStore.dir().toString()).set("enabled", UploadStore.enabled())
        .set("maxBytes", UploadStore.maxBytes()));
    return info;
  }

  /**
   * 当前活着的任务一览
   *
   * <p>
   * 以前「现在有哪些任务、各自停在哪一页、浏览器是不是还活着」只能靠翻日志猜。这个接口把
   * 进程内的实况直接列出来:每个任务一行(URL、标题、页签数、截图序号、在途请求),外加共享浏览器的
   * 类型与 profile 目录 —— 排查「孤儿浏览器占着 profile」「任务是不是没关干净」时先看它。
   */
  public RespBodyVo listTasks() {
    List<Kv> tasks = new ArrayList<>();
    for (BrowserInstance inst : INSTANCES.values()) {
      tasks.add(taskInfo(inst));
    }
    Kv data = Kv.by("count", tasks.size()).set("tasks", tasks);
    SharedBrowser browser = sharedBrowser;
    if (browser != null) {
      data.set("browser", Kv.by("type", browser.resolvedType.id()).set("engine", browser.engine.id())
          .set("profileDir", browser.profileDir.toAbsolutePath().toString()).set("headless", browser.headless)
          .set("mode", browser.browser != null ? "cdp" : "managed"));
    } else {
      data.set("browser", null);
    }
    return RespBodyVo.ok(data);
  }

  private static Kv taskInfo(BrowserInstance inst) {
    Kv info = Kv.by("id", inst.id)
        .set("profileDir", inst.profileDir == null ? null : inst.profileDir.toAbsolutePath().toString())
        .set("tabCount", pagesOf(inst).size()).set("captureSeq", inst.captureSeq.get())
        .set("inflight", inst.inflight.get()).set("recorderAttachedAt", inst.recorderAttachedAt);
    try {
      info.set("url", inst.page.url()).set("title", safeTitle(inst.page));
    } catch (PlaywrightException e) {
      info.set("pageError", briefMessage(e.getMessage()));
    }
    return info;
  }

  /**
   * 服务端运行时配置一览
   *
   * <p>
   * 「为什么这次不是我要的浏览器」「脚本目录在哪」「降级开关开着没」「日志落在哪」这类问题,
   * 以前只能靠翻配置文件加猜。这个接口把生效值一次列全:引擎/类型、解析后的 profile 目录、
   * 动作超时、两个降级开关、脚本目录、追踪与上传目录、以及当前任务数。
   *
   * @param browserId 可选:传了就顺带带上这个任务实际用的浏览器信息(与 start 回执里的 data.browser 同源)
   */
  public RespBodyVo getConfig(Long browserId) {
    Kv data = new Kv();
    data.set("workDir", Paths.get("").toAbsolutePath().normalize().toString());
    data.set("jsDir", scriptDir().toString());
    // 引擎与类型:engine 只分 chromium/firefox 两档,type 才是 auto/chromium/chrome/edge/firefox
    data.set("engine", BrowserEngine.current().id());
    data.set("configuredType", BrowserChoice.configured().id());
    data.set("typeChoices", BrowserChoice.CHOICES);
    data.set("profileDir", Kv.by("resolved", ChromeBrowser.managedProfileDir().toString())
        .set("perPort", ChromeBrowser.perPortProfileDir())
        .set("configured", ChromeBrowser.config(ChromeBrowser.KEY_PROFILE_DIR))
        .set("note", "没显式配 browser.profileDir 且 perPort 开着时,目录按服务端口派生(shared-<端口>),"
            + "多个服务实例同时跑不会抢同一份 profile 锁"));
    data.set("action", Kv.by("timeoutMs", actionTimeoutMs()).set("jsFallback", jsFallbackEnabled())
        .set("mouseFallback", mouseFallbackEnabled()));
    data.set("launchTimeoutMs", launchTimeoutMs());
    data.set("trace", Kv.by("dir", CommandTraceLog.currentDir().toString())
        .set("enabled", Boolean.parseBoolean(String.valueOf(ChromeBrowser.config(CommandTraceLog.KEY_ENABLED) == null
            ? "true" : ChromeBrowser.config(CommandTraceLog.KEY_ENABLED))))
        .set("redact", CommandTraceLog.redactEnabled()));
    data.set("upload", Kv.by("dir", UploadStore.dir().toString()).set("enabled", UploadStore.enabled())
        .set("maxBytes", UploadStore.maxBytes()));
    data.set("tasks", INSTANCES.size());
    data.set("commands", CommandTableNamesHolder.names());
    data.set("java", System.getProperty("java.version"));
    Package playwrightPackage = Playwright.class.getPackage();
    data.set("playwright", playwrightPackage == null ? null : playwrightPackage.getImplementationVersion());
    if (browserId != null) {
      data.set("browser", browserInfo(browserId));
    }
    return RespBodyVo.ok(data);
  }

  /** 命令名清单(避免 service 直接依赖 CommandTable 造成的循环引用歧义) */
  private static final class CommandTableNamesHolder {
    private static java.util.Set<String> names() {
      return nexus.io.ai.browser.actions.registry.CommandTable.names();
    }

    private CommandTableNamesHolder() {
    }
  }

  /**
   * 主动关停:把所有任务与共享浏览器关掉
   *
   * <p>
   * 与直接杀进程的区别:这里会先关任务页签、再关共享浏览器、最后关 Playwright driver,浏览器进程
   * 能正常退出,不会留下占着 profile 目录的孤儿;也不会留下 {@code .startup-incomplete} 标记。
   * 服务进程本身不退出(HTTP 还能应答),要退出进程请用操作系统的停机方式。
   */
  public RespBodyVo shutdown() {
    List<Long> closed = new ArrayList<>();
    for (Long id : new ArrayList<>(INSTANCES.keySet())) {
      try {
        close(id);
        closed.add(id);
      } catch (RuntimeException e) {
        log.warn("关停任务 {} 失败:{}", id, briefMessage(e.getMessage()));
      }
    }
    closeSharedBrowser();
    return RespBodyVo.ok(Kv.by("closedTasks", closed).set("remainingTasks", INSTANCES.size()));
  }

  /**
   * 启动参数(Chromium 系:内置 Chromium、本机 Chrome、本机 Edge 共用这一套)
   *
   * @param executable  这次要用的可执行文件;null 表示交给 Playwright 自己解析(开发态的内置 Chromium)
   * @param userProfile 用用户自己的 Chrome 用户数据目录时才需要子 profile
   * @param type        浏览器类型:只有「本机 Chrome」才按它自己的身份出现,内置 Chromium 要伪装 UA,
   *                    Edge 则必须保留它自己的 UA(见下)
   */
  private static LaunchPersistentContextOptions buildOptions(boolean headless, Path executable, boolean userProfile,
      BrowserChoice type) {
    LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions().setHeadless(headless);
    opts.setTimeout(launchTimeoutMs());

    opts.setIgnoreDefaultArgs(Lists.of("--enable-automation"));

    Dimension window = windowSize();
    int screenWidth = window.width;
    int screenHeight = window.height;

    // 覆写/追加启动参数
    List<String> args = chromiumArgs();
    if (userProfile) {
      // 用户数据目录里可能有多个子 profile(Default / Profile 1 / ...),指定用哪一个
      args.addAll(ChromeBrowser.profileArgs());
    }
    if (type.isEdge()) {
      // Edge 自己的参数(默认没有;调用方可以用 browser.edge.extraArgs 补)
      args.addAll(EdgeBrowser.extraArgs());
    } else {
      // 调用方自己补的参数(语言、代理、--disable-extensions 之类)
      args.addAll(ChromeBrowser.extraArgs());
    }
    opts.setArgs(args);

    // Chromium 沙箱:见 chromiumSandbox 的说明(这条路径走管道,Edge 走不了这里)
    opts.setChromiumSandbox(chromiumSandbox(type, true));

    // 可执行文件:本机 Chrome / Edge / 发行包内嵌的 Chromium;都没有时交给 Playwright 自己解析
    // (开发态会用它自己下载的浏览器)
    if (executable != null) {
      opts.setExecutablePath(executable);
      log.info("浏览器可执行文件:{}", executable);
    }

    // 下载
    opts.setAcceptDownloads(true);
    opts.setDownloadsPath(Paths.get(userHome(), "Downloads", "broswer"));

    // 视窗 & 设备仿真
    opts.setViewportSize(screenWidth, screenHeight);
    opts.setDeviceScaleFactor(1.0);
    opts.setIsMobile(false);
    opts.setHasTouch(false);

    // 权限 & HTTP 头
    opts.setPermissions(Arrays.asList("clipboard-read", "clipboard-write", "notifications"));

    if (type == BrowserChoice.CHROMIUM) {
      // 内置/Playwright 自带的 Chromium 版本与真实 Chrome 不一致,统一伪装成 Windows 上的 Chrome;
      // 本机 Chrome 与 Edge 直接用自己的 UA —— 尤其 Edge:改掉的话 UA 里就没有 Edg/ 了,
      // 「这次到底用的哪个浏览器」在站点侧也就看不出来了
      opts.setUserAgent(BrowserUserAgent.CHROME_127_WIN_10);
    }
    opts.setServiceWorkers(ServiceWorkerPolicy.ALLOW);

    return opts;
  }

  /**
   * Firefox 的启动参数
   *
   * <p>
   * 与 Chromium 那份的差别不是「少抄了几行」,而是**这些选项在 Firefox 下根本不存在**:
   * <ul>
   * <li>{@code ignoreDefaultArgs} 里的 {@code --enable-automation} 是 Chromium 的启动标志;</li>
   * <li>{@code chromiumSandbox} 只对 Chromium 生效(Playwright 会把 {@code chromiumSandbox} 传给它,
   * Firefox 会报「选项不被支持」);</li>
   * <li>{@code serviceWorkers} 策略也是 Chromium 特有;</li>
   * <li>UA 不能覆写成 Chrome:注释里说得很清楚,Firefox 的价值就在于它是 Firefox —— 覆写 UA 会让
   * 「用哪个引擎」这件事失去意义,而且 UA 与引擎行为不一致本身就是异常信号。这里直接用 Firefox
   * 自己的 UA。</li>
   * </ul>
   *
   * <p>
   * 保留的是两边通用的部分:持久化上下文、下载、视口、设备仿真、权限(见
   * {@link BrowserEngine#firefoxPermissions()})与 {@code userPrefs}(见
   * {@link BrowserEngine#firefoxUserPrefs()})。
   */
  private static LaunchPersistentContextOptions buildFirefoxOptions(boolean headless, Path executable) {
    LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions().setHeadless(headless);
    opts.setTimeout(launchTimeoutMs());

    Dimension window = windowSize();
    opts.setArgs(BrowserEngine.firefoxExtraArgs());
    opts.setFirefoxUserPrefs(BrowserEngine.firefoxUserPrefs());

    if (executable != null) {
      opts.setExecutablePath(executable);
      log.info("Firefox 可执行文件:{}", executable);
    }

    opts.setAcceptDownloads(true);
    opts.setDownloadsPath(Paths.get(userHome(), "Downloads", "broswer"));

    opts.setViewportSize(window.width, window.height);
    opts.setDeviceScaleFactor(1.0);
    opts.setIsMobile(false);
    opts.setHasTouch(false);

    opts.setPermissions(BrowserEngine.firefoxPermissions());

    return opts;
  }

  /**
   * 窗口/视口尺寸:屏幕宽度的 28/32,高度取满
   *
   * <p>Chromium 与 Firefox 共用同一套算法,免得两个引擎的窗口大小不一致(排查时看到的页面布局也就不一致)。
   */
  static Dimension windowSize() {
    Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((screenSize.width / 32) * 28, screenSize.height);
  }

  /**
   * 进程内共享的 Playwright(driver)
   *
   * <p>
   * {@code Playwright.create()} 会拉起一个 node driver 进程并完成握手,每次大约几百毫秒, 而且每个实例都常驻一个
   * node 进程。但服务里根本不需要多个 driver:浏览器本身就只有一份(见 {@link SharedBrowser}),一个 driver
   * 能同时管着它下面的所有上下文与页签。所以全进程共用一个 driver,{@code start} 只在这个共享浏览器上开一个
   * 页签。
   */
  private static Playwright playwright() {
    Playwright current = sharedPlaywright;
    if (current != null) {
      return current;
    }
    synchronized (PlaywrightService.class) {
      if (sharedPlaywright == null) {
        long startedAt = System.currentTimeMillis();
        sharedPlaywright = Playwright.create();
        log.info("创建共享 Playwright(driver),耗时 {}ms", System.currentTimeMillis() - startedAt);
      }
      return sharedPlaywright;
    }
  }

  /**
   * 把共享的 Playwright 判死并关掉,下次 {@link #playwright()} 会重新创建
   *
   * <p>
   * 只在 driver 已经不可用时调用。传进来的实例可能已经半死,close() 本身也可能抛异常, 所以这里的失败只记 debug 日志。
   */
  private static void discardPlaywright() {
    synchronized (PlaywrightService.class) {
      Playwright dead = sharedPlaywright;
      sharedPlaywright = null;
      if (dead != null) {
        try {
          dead.close();
        } catch (RuntimeException e) {
          log.debug("关闭失效的 Playwright 失败:{}", e.getMessage());
        }
      }
    }
  }

  /**
   * 用共享的 Playwright 起一个持久化上下文(按引擎选 chromium / firefox)
   *
   * <p>
   * 第一次失败时把共享实例判死、重建一个再试一次:driver 进程可能已经被上一次任务带崩了, 重建比让用户去重启整个服务划算。第二次还失败就把异常抛出去。
   */
  private static BrowserContext launchContext(Path profileDir, LaunchPersistentContextOptions opts,
      BrowserEngine engine) {
    releaseStaleProfileLock(profileDir);
    try {
      return playwrightType(engine).launchPersistentContext(profileDir, opts);
    } catch (RuntimeException first) {
      log.warn("启动浏览器失败,重建共享 Playwright 后重试一次:{}", first.getMessage());
      discardPlaywright();
      releaseStaleProfileLock(profileDir);
      try {
        return playwrightType(engine).launchPersistentContext(profileDir, opts);
      } catch (RuntimeException second) {
        throw new IllegalStateException(launchFailureMessage(profileDir, engine, second), second);
      }
    }
  }

  /**
   * 清掉上一次「服务被强杀」留下的 profile 痕迹
   *
   * <p>
   * 实测坑:直接 kill 掉服务进程**不会**关掉它启动的浏览器(浏览器是独立进程,driver 死了它就变成孤儿),
   * 孤儿浏览器会一直占着 profile 目录。下次服务启动时,Firefox 的表现是<b>既不连管道也不退出</b>,
   * {@code start} 一直卡到启动超时 —— 排查时完全看不出是「上次没退干净」。
   *
   * <p>
   * 要清的是两个文件:
   * <ul>
   * <li>{@code parent.lock}:Firefox / Chromium 的「这个 profile 正在被使用」标记;</li>
   * <li>{@code .startup-incomplete}:Firefox 在启动开始时写下、启动完成时删掉。上一次启动没走完
   * (进程被强杀)它就留在那里,下一次启动会走「上次崩过」那条路,同样卡住。</li>
   * </ul>
   *
   * <p>
   * 判断「有没有活着的实例」只在 Windows 上做:Windows 不允许删除带字节范围锁的文件,所以
   * <b>parent.lock 删得掉就证明没有活着的持有者</b>(删不掉说明真有一个实例在用,那就什么都不动)。
   * POSIX 没有这个保证(删除成功也不代表没人在用),贸然删锁会让两个实例共用一个 profile 而损坏数据,
   * 所以非 Windows 一律不动。
   */
  private static void releaseStaleProfileLock(Path profileDir) {
    if (profileDir == null || !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
      return;
    }
    Path lock = profileDir.resolve("parent.lock");
    Path startupMarker = profileDir.resolve(".startup-incomplete");
    if (Files.exists(lock)) {
      try {
        Files.delete(lock);
        log.info("清掉残留的 profile 锁(上一次服务被强杀后遗留):{}", lock);
      } catch (IOException e) {
        // 删不掉 = 真有一个活着的浏览器实例在用它,保持原样
        log.debug("profile 锁还在被占用,保留:{}", lock);
        return;
      }
    }
    if (Files.exists(startupMarker)) {
      try {
        Files.delete(startupMarker);
        log.info("清掉上一次没走完启动留下的标记(不清它,Firefox 下次启动会卡住):{}", startupMarker);
      } catch (IOException e) {
        log.debug("启动标记删不掉,保留:{}", startupMarker);
      }
    }
  }

  /**
   * 启动浏览器的超时(毫秒)
   *
   * <p>
   * Playwright 默认给 180 秒,而正常启动只要几秒。实测「profile 被残留进程占住」时它会**一直卡到超时**,
   * 180 秒的等待让排查变成「等三分钟才看到一句话」,所以默认收到 60 秒;慢机器上可以用
   * {@code browser.launch.timeoutMs} 调大。
   */
  private static double launchTimeoutMs() {
    String configured = ChromeBrowser.config(KEY_LAUNCH_TIMEOUT);
    if (configured != null) {
      try {
        double parsed = Double.parseDouble(configured.trim());
        if (parsed > 0) {
          return parsed;
        }
      } catch (NumberFormatException e) {
        log.warn("{} 不是合法数字,按默认值处理:{}", KEY_LAUNCH_TIMEOUT, configured);
      }
    }
    return DEFAULT_LAUNCH_TIMEOUT_MS;
  }

  /** 启动失败时把「最可能的原因」写进错误里,免得只看到一句 Timeout 180000ms exceeded */  private static String launchFailureMessage(Path profileDir, BrowserEngine engine, RuntimeException cause) {
    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
    StringBuilder text = new StringBuilder("启动浏览器失败(").append(engine.id()).append(",profile=")
        .append(profileDir).append("):").append(message.replaceAll("\\s+", " ").trim());
    if (message.contains("Timeout")) {
      text.append("。常见原因:上一次服务是被强制结束的,它启动的浏览器进程还活着并占着这个 profile 目录")
          .append("(profile 里的 parent.lock)。先结束残留的浏览器进程")
          .append(engine.isFirefox() ? "(firefox.exe)" : "(chrome.exe / msedge.exe)")
          .append(",或换一个 browser.profileDir 再重试。");
    }
    return text.toString();
  }

  /** 引擎 → Playwright 的浏览器类型({@code playwright().chromium()} / {@code playwright().firefox()}) */
  private static com.microsoft.playwright.BrowserType playwrightType(BrowserEngine engine) {
    return engine.isFirefox() ? playwright().firefox() : playwright().chromium();
  }

  /**
   * 浏览器启动参数
   *
   * <p>
   * 这里**不传** {@code --no-sandbox} 与 {@code --disable-web-security}:Chrome 把这两个
   * 标志当成「不受支持的命令行标志」,启动时会打印
   * {@code You are using an unsupported command-line flag: --no-sandbox. Stability and security will suffer.}
   * 并在窗口上挂一条提示。
   *
   * <p>
   * 注意 {@code --no-sandbox} 不是这里加的,而是 Playwright 自己加的:它的
   * {@code chromiumSandbox} 默认就是 {@code false},见驱动的
   * {@code _innerDefaultArgs}:{@code if (options.chromiumSandbox !== true) chromeArguments.push("--no-sandbox")}。
   * 本项目默认**开启**沙箱(见 {@link #chromiumSandbox(BrowserChoice)}),所以那条警告条默认不会再出现;
   * 只有把 {@code browser.chromium.sandbox=false}(或者跑在 Linux / Edge 上)时才会有。
   */
  static List<String> chromiumArgs() {
    List<String> args = new ArrayList<>();
    // 不禁用 AutomationControlled：该启动参数会触发 Chromium 的不受支持标志提示。
    if (isLinux()) {
      // 容器里 /dev/shm 往往只有 64MB,不加这个 Chrome 会随机崩
      args.add("--disable-dev-shm-usage");
    }
    return args;
  }

  /**
   * 是否开启 Chromium 沙箱
   *
   * <p>
   * 默认**开着**(Windows / macOS 这类普通桌面环境):本机 Chrome 与内置 Chromium 的进程沙箱都能正常
   * 工作,关掉它换不来任何东西,只换来窗口上那条
   * {@code You are using an unsupported command-line flag: --no-sandbox. Stability and security will suffer.}
   * 提示条和更差的隔离。开着之后 Playwright 不会再往命令行里塞 {@code --no-sandbox}
   * (见驱动的 {@code _innerDefaultArgs}:{@code if (options.chromiumSandbox !== true) chromeArguments.push("--no-sandbox")}),
   * CDP 那条路(自己拉 Chrome)也不会再加它 —— 两边都看这个开关,不会一半开一半关。
   *
   * <p>
   * Linux 上默认**关着**,与以前的版本一致:服务多数跑在容器里、以 root 运行,而 Chrome 以 root 启动时
   * 会直接报 {@code Running as root without --no-sandbox is not supported} 并退出。Linux 桌面(非 root、
   * 内核允许用户命名空间)想开就配 {@code browser.chromium.sandbox=true}。
   *
   * <p>
   * <b>Edge 默认也开着,但它必须走 CDP 那条路</b>(见 {@link #launchOverCdp}):实测
   * (Edge 145.0.3800.97 / Windows)开沙箱时,Playwright 的 {@code launchPersistentContext}
   * (用 {@code --remote-debugging-pipe})拉起来的 Edge 会启动即退出,报
   * {@code Target page, context or browser has been closed};把启动方式换成
   * {@code --remote-debugging-port} 就一切正常。也就是说问题在「沙箱 + 管道」这个组合,不在 Edge 或
   * 沙箱本身 —— 所以 {@code browser=edge} 走自己拉进程 + CDP,沙箱照样开着。下面这个
   * {@code overPipe} 参数就是为这件事留的:谁把 Edge 弄回管道启动,这里会自动把沙箱关掉(并打一条
   * 警告),而不是让浏览器起不来。
   *
   * <p>
   * 三种取值都用 {@code browser.chromium.sandbox} 覆盖:
   * <ul>
   * <li>{@code true}:始终开启(容器里以 root 跑时 Chrome 会起不来,见上;Edge 走管道时会被忽略);</li>
   * <li>{@code false}:始终关闭,回到以前那种「带提示条、没有沙箱」的跑法;</li>
   * <li>不配:按平台默认(非 Linux 开,Linux 关)。</li>
   * </ul>
   *
   * @param type    这次用的是哪个浏览器
   * @param overPipe 这次是不是用 Playwright 的 {@code launchPersistentContext}(管道)启动的
   */
  static boolean chromiumSandbox(BrowserChoice type, boolean overPipe) {
    boolean edgeOverPipe = overPipe && type != null && type.isEdge();
    String configured = ChromeBrowser.config(KEY_SANDBOX);
    if (configured != null) {
      boolean wanted = Boolean.parseBoolean(configured.trim());
      if (wanted && edgeOverPipe) {
        log.warn("browser.chromium.sandbox=true 与 Edge 的管道启动不兼容（实测 Edge 会启动即退出），"
            + "这次仍按关闭处理；用 browser=edge 时它走的是 CDP,沙箱是开着的");
      }
      return wanted && !edgeOverPipe;
    }
    if (edgeOverPipe) {
      return false;
    }
    return !isLinux();
  }

  /**
   * 用用户自己的 profile 时,我们自己拉 Chrome 用的启动参数
   *
   * <p>
   * 这条路径上没有 Playwright 帮忙兜底(它那些默认参数是配 {@code launchPersistentContext} 的),
   * 所以只挑真正需要的:不弹首启/默认浏览器询问、别拦弹窗(服务靠弹窗切页签)、后台页签不要被降频
   * (否则等待会莫名超时)、沙箱按 {@link #chromiumSandbox(BrowserChoice)} 的决定走(关着时这条路径得自己加
   * {@code --no-sandbox},Playwright 管不到它)。视口在 CDP 模式下没有 {@code setViewportSize} 的初始值,
   * 用 {@code --window-size} 给一个,页面级视口后续仍可用 {@code set_viewport} 调整。
   *
   * @param type 这次拉起来的是哪个浏览器:只影响沙箱这一个开关
   */
  static List<String> cdpArgs(boolean headless, BrowserChoice type) {
    List<String> args = new ArrayList<>(chromiumArgs());
    // 0 = 让浏览器自己挑端口,端口号从它的 stderr("DevTools listening on ws://...")里读
    args.add("--remote-debugging-port=0");
    args.add("--no-first-run");
    args.add("--no-default-browser-check");
    args.add("--disable-search-engine-choice-screen");
    args.add("--disable-breakpad");
    // 点击 target=_blank / window.open 会开新页签,不能让弹窗拦截把这条路掐了
    args.add("--disable-popup-blocking");
    args.add("--disable-prompt-on-repost");
    args.add("--disable-hang-monitor");
    args.add("--disable-background-timer-throttling");
    args.add("--disable-renderer-backgrounding");
    args.add("--disable-backgrounding-occluded-windows");
    args.add("--metrics-recording-only");
    args.add("--force-color-profile=srgb");
    if (!chromiumSandbox(type, false)) {
      // 这条路径不经过 Playwright,chromiumSandbox=false 不会自动变成 --no-sandbox,得自己加
      args.add("--no-sandbox");
    }
    Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    args.add("--window-size=" + (screenSize.width / 32) * 28 + "," + screenSize.height);
    if (headless) {
      args.add("--headless=new");
      args.add("--hide-scrollbars");
      args.add("--mute-audio");
    }
    return args;
  }

  /** 当前系统是不是 Linux:命令行标志与路径差异都靠它判断 */
  static boolean isLinux() {
    String os = EnvUtils.get("os.name", "");
    return os.toLowerCase().contains("linux");
  }

  private static String userHome() {
    return EnvUtils.get("user.home", ".");
  }

  /** 弹窗、控制台日志、页面错误、弹窗页签:每个页签都要挂一次 */
  private void attachListeners(BrowserInstance inst, Page page) {
    page.onDialog(dialog -> {
      Kv info = Kv.by("type", dialog.type()).set("message", dialog.message()).set("defaultValue", dialog.defaultValue())
          .set("seq", inst.dialogSeq.incrementAndGet()).set("timestamp", System.currentTimeMillis());
      inst.lastDialog = info;
      log.info("弹窗:{} {}", info.getStr("type"), info.getStr("message"));
      if (inst.dismissDialogs) {
        dialog.dismiss();
      } else {
        dialog.accept();
      }
    });
    page.onConsoleMessage(msg -> addBounded(inst.consoleLogs, msg.type() + ": " + msg.text()));
    page.onPageError(error -> addBounded(inst.pageErrors, error));
    // 这个页签弹出的新窗口归同一个任务,别人看不见
    page.onPopup(popup -> claimPage(inst, popup));
    // 页签被人工关掉(共用浏览器时很常见)后要有人接管「当前页」
    page.onClose(closed -> onPageClosed(inst, closed));
  }

  /**
   * 页签被关掉后的收尾
   *
   * <p>
   * 关掉的是任务当前页时,换成该任务还活着的页签;一个都不剩就补一个新页签,别让任务直接变成不可用
   * (共用用户 profile 时,人工在窗口里点掉一个页签是很常见的操作)。任务自己正在 close 时跳过。
   */
  private void onPageClosed(BrowserInstance inst, Page page) {
    inst.pages.remove(page);
    if (inst.detached || inst.page != page) {
      return;
    }
    recoverCurrentPage(inst);
  }

  /** 当前页没了:换成任务里还活着的页签,一个都不剩就补一个新的 */
  private void recoverCurrentPage(BrowserInstance inst) {
    List<Page> live = pagesOf(inst);
    if (!live.isEmpty()) {
      inst.page = live.get(0);
      activate(inst.page);
      return;
    }
    try {
      Page fresh = inst.context.newPage();
      inst.page = fresh;
      claimPage(inst, fresh);
      log.info("任务 {} 已经没有可用页签,已补一个新页签", inst.id);
    } catch (PlaywrightException e) {
      log.warn("任务 {} 补页签失败(浏览器可能已经关闭):{}", inst.id, briefMessage(e.getMessage()));
    }
  }

  /**
   * 记录网络请求,onResponse 时回填状态码;带请求体的记下 postData,便于排查提交了什么
   *
   * <p>
   * 挂在**页签**上而不是上下文上:上下文是所有任务共用的,挂上下文会把别人的流量也记进这个任务的
   * {@code get_requests}。
   */
  private void attachRequestRecorder(BrowserInstance inst, Page page) {
    inst.recorderAttachedAt = System.currentTimeMillis();
    page.onRequest(request -> safely("onRequest", () -> {
      Kv entry = requestInfo(request);
      addBoundedRequest(inst.requests, entry);
      if (request != null) {
        inst.requestIndex.put(request, entry);
        inst.inflight.incrementAndGet();
      }
    }));
    page.onResponse(response -> safely("onResponse", () -> {
      Request request = response == null ? null : response.request();
      Kv entry = request == null ? null : inst.requestIndex.remove(request);
      if (entry != null) {
        entry.set("status", response.status()).set("respondedAt", System.currentTimeMillis());
        // 只对「自己记过的请求」减计数,保证与 onRequest 的加计数一一对应,不会减成负数
        inst.inflight.decrementAndGet();
      }
      if (response != null) {
        rememberResponse(inst, response, entry == null ? requestInfo(request) : entry);
      }
    }));
    page.onRequestFailed(request -> safely("onRequestFailed", () -> {
      if (request == null) {
        return;
      }
      Kv entry = inst.requestIndex.remove(request);
      if (entry != null) {
        entry.set("failure", request.failure()).set("finishedAt", System.currentTimeMillis());
        inst.inflight.decrementAndGet();
      }
    }));
  }

  /**
   * 观测类钩子的安全边界:异常只记日志,绝不外抛
   *
   * <p>
   * 监听器里抛出的异常会被 Playwright 带到**后续 API 调用**上重新抛出,表现成「脚本明明已经跑完了,
   * 命令却报失败、返回值还丢了」(实测踩过:一条 20 秒的 execute_js 结果全丢,白重跑一遍流程)。
   * 记录/观测永远不该决定命令成败,所以这里一律兜住。
   */
  private static void safely(String hook, Runnable body) {
    try {
      body.run();
    } catch (Throwable t) {
      String message = briefMessage(String.valueOf(t.getMessage()));
      String key = hook + "|" + message;
      if (WARNED.size() < 200 && WARNED.add(key)) {
        log.warn("网络记录钩子 {} 异常,已忽略(不影响命令结果):{}", hook, message);
      } else {
        log.debug("网络记录钩子 {} 异常,已忽略:{}", hook, message);
      }
    }
  }

  /** 同一类钩子异常只告警一次,避免每来一个请求刷一行日志 */
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  /**
   * 把任务已经注册过的路由规则补挂到新页签上
   *
   * <p>
   * 路由挂在页签上(而不是共用的上下文上),所以弹窗、new_tab 出来的新页签要自己补一遍,
   * 否则「只在这个任务里 mock 接口」会莫名其妙失效。
   */
  private static void applyRoutes(BrowserInstance inst, Page page) {
    for (String urlPattern : inst.routedPatterns) {
      registerRoute(inst, page, urlPattern);
    }
  }

  private static void registerRoute(BrowserInstance inst, Page page, String urlPattern) {
    page.route(urlPattern, route -> {
      Kv current = inst.routes.get(urlPattern);
      if (current == null || "resume".equals(current.getStr("action"))) {
        route.resume();
      } else if ("mock".equals(current.getStr("action"))) {
        route.fulfill(new Route.FulfillOptions().setStatus(current.getInt("status"))
            .setBody(current.getStr("body") == null ? "" : current.getStr("body"))
            .setContentType(current.getStr("contentType")));
      } else {
        route.abort();
      }
    });
  }

  private static Kv requestInfo(Request request) {
    Kv entry = Kv.by("requestId", String.valueOf(SnowflakeIdUtils.id())).set("requestedAt", System.currentTimeMillis())
        .set("status", null);
    if (request == null) {
      // 请求对象可能已被回收(响应回调里 response.request() 返回 null 就是这种情况),
      // 这里必须空指针安全:以前直接 request.method() 会抛出去,把整条命令判成失败
      return entry.set("method", null).set("url", null).set("resourceType", null).set("requestUnavailable", true);
    }
    try {
      entry.set("method", request.method()).set("url", request.url()).set("resourceType", request.resourceType());
    } catch (PlaywrightException e) {
      entry.set("requestInfoError", briefMessage(e.getMessage()));
      return entry;
    }
    String body = safePostData(request);
    if (body != null)
      entry.set("postData", truncate(body, MAX_RECORDED_BODY_CHARS)).set("postDataLength", body.length())
          .set("postDataTruncated", body.length() > MAX_RECORDED_BODY_CHARS);
    return entry;
  }

  /** 请求体只在少数请求上有,取不到时不要影响记录本身 */
  private static String safePostData(Request request) {
    try {
      return request.postData();
    } catch (PlaywrightException e) {
      return null;
    }
  }

  /** 保留最近 100 个响应(带时间戳),get_response_body / wait_for_response 靠它回捞响应体 */
  private static void rememberResponse(BrowserInstance inst, Response response, Kv request) {
    synchronized (inst.recentResponses) {
      inst.recentResponses.addLast(new BrowserInstance.RecordedResponse(response, System.currentTimeMillis(), request));
      while (inst.recentResponses.size() > 100) {
        inst.recentResponses.removeFirst();
      }
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max) + "...(截断)";
  }

  private static void addBounded(List<String> list, String item) {
    synchronized (list) {
      if (list.size() >= 200) {
        list.remove(0);
      }
      list.add(item);
    }
  }

  private static void addBoundedRequest(List<Kv> list, Kv item) {
    synchronized (list) {
      if (list.size() >= 200) {
        list.remove(0);
      }
      list.add(item);
    }
  }

  public BrowserInstance getInstance(long id) {
    return INSTANCES.get(id);

  }

  /** navigate 与 go_to_url 等价,返回 data.status */
  public RespBodyVo navigate(Long browserId, String url) {
    return goToUrl(browserId, url);
  }

  /**
   * 取浏览器当前状态:页签信息 + 页面结构化文本(可交互元素索引)
   *
   * <p>
   * 执行 buildDomTree 把当前页面转成 AI 可读的结构化文本,并缓存本次快照。返回的 text 每行 形如
   * {@code [index]<tag attr='value'>文本/>},其中 index 就是 click_element_by_index、
   * input_text、upload_file、get_dropdown_options、select_dropdown_option 要用的元素索引。
   * 页面变化后需要重新调用。
   *
   * <p>
   * 同时会做两件落盘动作(都放在 {@code data/&lt;id&gt;/} 下,由 /data/** 静态路由对外提供):
   * <ol>
   * <li>截一张图,文件名是自增序号 {@code &lt;seq&gt;.png}</li>
   * <li>把页签文本 + 可交互结构化文本写成同名 {@code &lt;seq&gt;.txt}</li>
   * </ol>
   * 返回的 data.seq / data.screenshot / data.state_file 就是这一对文件。
   *
   * @param browserId         任务 ID
   * @param highlight         是否在页面上绘制高亮框,默认 true
   * @param viewportExpansion 视口外扩像素,默认 0
   */
  public RespBodyVo getBrowserState(Long browserId, Boolean highlight, Integer viewportExpansion) {
    return getBrowserState(browserId, highlight, viewportExpansion, null, null);
  }

  /**
   * 同上,额外控制「可交互元素清单」的返回
   *
   * @param includeElements 是否在回执里内联 {@code data.elements}(索引 + 标签 + 文本 + 是否可见 +
   *                        盒子),默认 true。索引型命令要用的 index 就在这里,内联之后不必再单独调一次
   *                        {@code get_interactive_map}
   * @param maxElements     内联条数上限,默认 200(超出时回执带 {@code elementsTruncated=true},
   *                        完整清单仍可用 {@code get_interactive_map} 取)
   */
  public RespBodyVo getBrowserState(Long browserId, Boolean highlight, Integer viewportExpansion,
      Boolean includeElements, Integer maxElements) {
    return getBrowserState(browserId, highlight, viewportExpansion, includeElements, maxElements, null);
  }

  /**
   * 同上,额外控制「要不要连跨域 iframe 里的元素一起纳入快照」
   *
   * <p>
   * <b>为什么要有 {@code includeFrames}</b>:实测企业微信后台把「邮件」应用套在 {@code exmail.qq.com} 的
   * 跨域 iframe 里(微盘 / 文档 / 会议同理),顶层 {@code document} 里一个元素都没有 —— 常规手段全部失效。
   * 而 Playwright 本身能跨 frame(走浏览器协议,不依赖往页面里注入 JS),缺的只是把 {@code page.frames()}
   * 暴露出来。传 {@code includeFrames:true} 之后:
   *
   * <ul>
   * <li>每个 frame 的元素都进**同一个索引空间**,索引全局唯一,每项带 {@code frameIndex};</li>
   * <li>{@code data.text} 里用 {@code --- frame[i] <url> elements=N index=a..b ---} 标出边界;</li>
   * <li>按索引命令(click_element_by_index / input_text / …)**自动路由**到索引所在的 frame,调用方不必
   * 再传 frame 参数 —— 索引里已经带了 frame 信息。</li>
   * </ul>
   *
   * <p>
   * 默认 {@code false},行为与以前完全一致(只看顶层文档):带 frame 要多跑几轮脚本,而且绝大多数页面没有
   * 跨域 iframe,不该让所有调用方都付这个代价。
   *
   * @param includeFrames 是否把跨域 iframe 里的元素也纳入快照,默认 {@code false}
   */
  public RespBodyVo getBrowserState(Long browserId, Boolean highlight, Integer viewportExpansion,
      Boolean includeElements, Integer maxElements, Boolean includeFrames) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    boolean doHighlight = highlight == null || highlight;
    int expansion = viewportExpansion == null ? 0 : viewportExpansion;
    boolean withFrames = Boolean.TRUE.equals(includeFrames);

    DOMState state;
    try {
      // 一律走「按 frame 逐个求值」这条路:同源 iframe 的元素仍然全部在快照里(和以前顺着 iframe 递归
      // 的结果一致),但每个索引现在都记着它属于哪个 frame,按索引命令**自动路由**到正确的文档 ——
      // 以前同源 iframe 里的元素拿不到正确路由(它们的 xpath 相对 iframe 文档,在主文档里求值必然失败)。
      // includeFrames 控制的是「要不要连跨域 iframe 也纳入」。
      state = DomService.getFrameState(inst.page, doHighlight, expansion, !withFrames);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("构建页面结构失败：" + briefMessage(e.getMessage()));
    }
    recordSnapshot(inst, state);

    Kv kv = Kv.by("url", inst.page.url());
    String text = frameText(state);
    String browserState = browserStateText(inst);
    kv.set("title", safeTitle(inst.page));
    // 给模型读的页签块,格式见 browserStateText
    kv.set("browser_state", browserState);
    kv.set("text", text);
    kv.set("tabs", tabs(inst));
    kv.set("pixels_above", state.getPixelsAbove());
    kv.set("pixels_below", state.getPixelsBelow());
    kv.set("viewport_height", state.getViewportHeight());
    kv.set("page_height", state.getPageHeight());
    // 供 diff_dom_text 比较"这一次快照和上一次差在哪"
    inst.lastDomText = text;

    if (withFrames) {
      kv.set("frames", frameList(state));
      kv.set("frameCount", state.getFrames().size());
      kv.set("includeFrames", true);
    } else {
      // 顶层看不到元素时主动提一句:这类站点的下一步不是「换个选择器」,而是 includeFrames / list_frames
      String hint = frameHint(inst, state);
      if (hint != null) {
        kv.set("frameHint", hint);
      }
    }

    // 索引清单:text 是给人读的树,index 才是按索引命令要用的东西,直接内联免得再跑一趟
    if (includeElements == null || includeElements) {
      int cap = maxElements == null || maxElements <= 0 ? DEFAULT_MAX_INLINE_ELEMENTS : maxElements;
      List<Kv> all = interactiveElements(inst);
      List<Kv> inline = all.size() > cap ? new ArrayList<>(all.subList(0, cap)) : all;
      // elementsTruncated 无论是否截断都要给:调用方靠它判断「清单是不是全的」,缺字段会让人以为没截断
      kv.set("elements", inline).set("elementCount", all.size()).set("elementsTruncated", all.size() > cap);
      if (all.size() > cap) {
        kv.set("elementsHint", "元素清单已截断到 " + cap + " 条,完整清单用 get_interactive_map");
      }
    }

    // 截图 + 同名的可交互结构化文本
    Kv capture = capture(inst);
    kv.set(capture);
    String stateFile = writeStateFile(inst, capture.getInt("seq"), browserState, text);
    if (stateFile != null) {
      kv.set("state_file", stateFile);
    }
    return RespBodyVo.ok(kv);
  }

  /**
   * 带 frame 的快照文本:每段前面一行 {@code --- frame[i] ... ---}
   *
   * <p>
   * 分隔行是**必须的**:不带边界的话,模型无法判断某个 {@code [index]} 到底属于哪个文档,也就无法解释
   * 「为什么这个索引点下去没反应」。
   */
  static String frameText(DOMState state) {
    if (state == null || !state.hasFrames()) {
      return state == null || state.getElementTree() == null ? ""
          : state.getElementTree().clickableElementsToString(null);
    }
    List<FrameSnapshot> included = new ArrayList<>();
    for (FrameSnapshot frame : state.getFrames()) {
      if (!frame.skipped) {
        included.add(frame);
      }
    }
    // 只有一个 frame(绝大多数页面)时不加分隔行,文本与以前逐字节一致
    boolean withHeaders = included.size() > 1;
    StringBuilder sb = new StringBuilder();
    for (FrameSnapshot frame : included) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      if (withHeaders) {
        sb.append(frame.header()).append('\n');
      }
      if (frame.text != null && !frame.text.isEmpty()) {
        sb.append(frame.text).append('\n');
      }
    }
    return sb.toString().stripTrailing();
  }

  /** frame 清单(给 list_frames 与带 frame 的 get_browser_state 共用) */
  static List<Kv> frameList(DOMState state) {
    List<Kv> items = new ArrayList<>();
    if (state == null || !state.hasFrames()) {
      return items;
    }
    for (FrameSnapshot frame : state.getFrames()) {
      Kv item = Kv.by("index", frame.index).set("url", frame.url).set("name", frame.name)
          .set("isMain", frame.main).set("depth", frame.depth).set("elementCount", frame.elementCount);
      if (frame.parentIndex >= 0) {
        item.set("parentIndex", frame.parentIndex);
      }
      if (frame.hasElements()) {
        item.set("indexRange", frame.firstIndex + "-" + frame.lastIndex);
      }
      if (frame.readFailure != null) {
        item.set("readError", frame.readFailure);
      }
      if (frame.skipped) {
        item.set("skipped", true).set("skipReason", frame.skipReason);
      }
      items.add(item);
    }
    return items;
  }

  /**
   * 顶层一个元素都没读到、但页面上确实有 iframe 时,主动指出下一步
   *
   * <p>
   * 这类站点的症状很有迷惑性:{@code get_browser_state} 只回一个主站外壳,看起来像「页面还没加载完」或
   * 「选择器写错了」,于是反复重取快照。真实原因是内容在跨域 iframe 里,下一步应该是 {@code includeFrames}
   * 或 {@code list_frames},而不是重试。
   *
   * <p>顶层一个元素都没读到时说得更重一点 —— 那几乎肯定就是「内容全在 iframe 里」。
   */
  private static String frameHint(BrowserInstance inst, DOMState state) {
    int skipped = 0;
    if (state != null) {
      for (FrameSnapshot frame : state.getFrames()) {
        if (frame.skipped) {
          skipped++;
        }
      }
    }
    if (skipped == 0) {
      return null;
    }
    String next = "用 get_browser_state 传 includeFrames:true 一次拿到全部 frame 的元素"
        + "(索引全局唯一、自动路由),或先 list_frames 看有哪些 frame";
    if (state.getSelectorMap().isEmpty()) {
      return "顶层文档里没有可交互元素,而页面上还有 " + skipped + " 个跨域 iframe 没纳入本次快照:"
          + "内容很可能就在它们里面(企业微信的邮件/微盘/文档/会议都是这种形态)。" + next;
    }
    return "页面上还有 " + skipped + " 个跨域 iframe 没纳入本次快照,它们里面的元素这次读不到。" + next;
  }

  /**
   * 列出页面上所有 frame
   *
   * <p>
   * 顺序固定:主 frame 是 0,其余按 frame 树深度优先编号(自己从 {@code mainFrame()} 递归 {@code childFrames()}
   * 展开,不用顺序没有写进契约的 {@code page.frames()})。带上每个 frame 的可交互元素数与索引区间,便于判断
   * 「要读的内容在哪个 frame 里」。
   *
   * @param refresh 是否顺便重建一次带 frame 的快照(默认 true):没有快照时只回 URL/名称,拿不到元素数与
   *                索引区间,对定位帮助有限
   */
  public RespBodyVo listFrames(Long browserId, Boolean refresh) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    boolean rebuild = refresh == null || refresh;
    if (rebuild) {
      try {
        recordSnapshot(inst, DomService.getFrameState(inst.page, false, 0));
      } catch (PlaywrightException e) {
        return RespBodyVo.fail("list_frames 失败：读取页面 frame 失败（" + briefMessage(e.getMessage()) + "）");
      }
    }
    List<Kv> frames = frameList(inst.domState);
    if (frames.isEmpty()) {
      // 快照不是带 frame 的那种时,至少把 frame 的 URL/名称给出来,别让调用方以为「一个 frame 都没有」
      for (DomService.FrameNode node : DomService.frameTree(inst.page)) {
        Kv item = Kv.by("index", frames.size()).set("url", safeFrameUrl(node.frame))
            .set("name", safeFrameName(node.frame)).set("isMain", node.parentIndex < 0).set("depth", node.depth);
        if (node.parentIndex >= 0) {
          item.set("parentIndex", node.parentIndex);
        }
        frames.add(item);
      }
    }
    Kv data = Kv.by("count", frames.size()).set("frames", frames)
        .set("note", "index 0 是主 frame;跨域 iframe 也能读到 URL(这是识别它的主要依据)。"
            + "要读 iframe 里的元素用 get_browser_state 的 includeFrames:true");
    return RespBodyVo.ok(data);
  }

  private static String safeFrameUrl(Frame frame) {
    try {
      String url = frame.url();
      return url == null ? "" : url;
    } catch (PlaywrightException e) {
      return "";
    }
  }

  private static String safeFrameName(Frame frame) {
    try {
      String name = frame.name();
      return name == null ? "" : name;
    } catch (PlaywrightException e) {
      return "";
    }
  }

  /**
   * 只重取一次快照,不落盘
   *
   * <p>
   * diff_dom_text 用:它每次都要重新 buildDomTree,但不需要产生一对新的截图/文本文件, 否则对比一次页面就多两张垃圾文件。
   */
  private RespBodyVo buildState(BrowserInstance inst, Boolean highlight, Integer viewportExpansion) {
    boolean doHighlight = highlight == null || highlight;
    int expansion = viewportExpansion == null ? 0 : viewportExpansion;
    DOMState state;
    try {
      state = DomService.getClickableElements(inst.page, doHighlight, -1, expansion);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("构建页面结构失败：" + briefMessage(e.getMessage()));
    }
    recordSnapshot(inst, state);
    String text = state.getElementTree().clickableElementsToString(null);
    inst.lastDomText = text;
    return RespBodyVo.ok(Kv.by("url", inst.page.url()).set("title", safeTitle(inst.page))
        .set("browser_state", browserStateText(inst)).set("text", text).set("tabs", tabs(inst)));
  }

  /**
   * 页签信息文本块,给模型直接读
   *
   * <p>
   * 格式固定为(编号从 1 开始):
   *
   * <pre>
   * Browser tab: 1, Title: "哔哩哔哩 (゜-゜)つロ 干杯~-bilibili", URL: "https://www.bilibili.com/".
   * Browser tab: 2,
   * current tab is: 1
   * </pre>
   *
   * <p>
   * 标题和 URL 都取不到的页签(新建但还没加载完的空白页)只输出 {@code Browser tab: N, }。 注意这里的编号是 1 基,而
   * data.tabs 里的 index 与 switch_tab 的 pageIndex 仍然是 0 基。
   */
  public static String browserStateText(BrowserInstance inst) {
    List<Page> pages = pagesOf(inst);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < pages.size(); i++) {
      Page page = pages.get(i);
      String title = safeTitle(page);
      String url = safeUrl(page);
      sb.append("Browser tab: ").append(i + 1).append(", ");
      if (title.isEmpty() && url.isEmpty()) {
        sb.append('\n');
        continue;
      }
      if (!title.isEmpty()) {
        sb.append("Title: \"").append(title).append('"');
      }
      if (!url.isEmpty()) {
        if (!title.isEmpty()) {
          sb.append(", ");
        }
        sb.append("URL: \"").append(url).append('"');
      }
      sb.append(".\n");
    }
    sb.append("current tab is: ").append(pages.indexOf(inst.page) + 1);
    return sb.toString();
  }

  private static String safeUrl(Page page) {
    try {
      String url = page.url();
      return url == null ? "" : url;
    } catch (PlaywrightException e) {
      return "";
    }
  }

  /** 截图与结构化文本的落盘目录:data/&lt;id&gt;/ */
  public static Path dataDir(long taskId) {
    return Paths.get(DATA_DIR, String.valueOf(taskId));
  }

  /**
   * 给当前页面截图,序号自增
   *
   * <p>
   * 文件落在 {@code data/&lt;id&gt;/&lt;seq&gt;.png},返回值里的 screenshot 是可以直接 GET 的
   * URL({@code /data/&lt;id&gt;/&lt;seq&gt;.png})。截图前会尽力等页面进入 DOMCONTENTLOADED,
   * 等不到(超时)也照常截图,不会因为等待失败而丢掉这一张。
   *
   * @return 含 seq / screenshot / screenshot_path 的 Kv;截图失败时含 screenshot_error
   */
  public Kv capture(BrowserInstance inst) {
    int seq = inst.captureSeq.incrementAndGet();
    Kv kv = Kv.by("seq", seq);
    Path dir = dataDir(inst.id);
    Path png = dir.resolve(seq + ".png");
    // 截图策略:每次页面变化都自动截一张,长时间跑下来 data/ 会涨得很快。关掉之后动作照常执行,
    // 只是不再落图(需要时仍可显式调 screenshot 命令,那条不受这个开关影响)
    if (!captureEnabled()) {
      kv.set("screenshot_skipped", "browser.capture.enabled=false");
      return kv;
    }
    try {
      Files.createDirectories(dir);
      settle(inst);
      inst.page.screenshot(new Page.ScreenshotOptions().setPath(png));
      kv.set("screenshot", "/" + DATA_DIR + "/" + inst.id + "/" + seq + ".png");
      kv.set("screenshot_path", png.toAbsolutePath().toString());
    } catch (PlaywrightException e) {
      kv.set("screenshot_error", briefMessage(e.getMessage()));
      log.warn("任务 {} 第 {} 张截图失败:{}", inst.id, seq, briefMessage(e.getMessage()));
    } catch (IOException e) {
      kv.set("screenshot_error", e.getMessage());
      log.warn("任务 {} 第 {} 张截图写文件失败:{}", inst.id, seq, e.getMessage());
    }
    return kv;
  }

  /** 自动截图开关({@code browser.capture.enabled},默认开) */
  public static boolean captureEnabled() {
    String configured = ChromeBrowser.config(KEY_CAPTURE_ENABLED);
    return configured == null || Boolean.parseBoolean(configured.trim());
  }

  /**
   * 清理落盘产物:按保留时长/数量删掉截图、结构化文本、追踪日志与暂存文件
   *
   * <p>
   * <b>为什么要有它</b>:一次商标申请的操作就能攒下上百个追踪文件与几十张截图,长期跑下来磁盘只会
   * 单向增长。这里给出一个**显式**的清理入口(不做后台定时清理:删文件这种事不该在调用方不知情的
   * 情况下发生),支持两种口径:{@code olderThanHours}(默认 24 小时)与 {@code keepLatest}(每个任务
   * 目录至少保留最近几张)。
   *
   * @param scope {@code all}(默认)/ {@code data} / {@code trace} / {@code upload}
   * @param dryRun  默认 {@code true}:只统计不删。要真删必须显式传 {@code dryRun:false}
   * @return 删了多少文件、释放了多少字节,以及被跳过的原因
   */
  public RespBodyVo cleanup(String scope, Integer olderThanHours, Integer keepLatest, Boolean dryRun) {
    String target = scope == null || scope.isBlank() ? "all" : scope.trim().toLowerCase(java.util.Locale.ROOT);
    long cutoff = System.currentTimeMillis()
        - (olderThanHours == null ? 24L : Math.max(0, olderThanHours)) * 3600_000L;
    int keep = keepLatest == null ? 0 : Math.max(0, keepLatest);
    // 删文件是不可逆的:默认只预演,要真删必须显式关掉
    boolean preview = dryRun == null || dryRun;
    Kv report = new Kv();
    long[] totals = new long[] {0, 0};
    List<String> notes = new ArrayList<>();
    if ("all".equals(target) || "data".equals(target)) {
      Path data = Paths.get(DATA_DIR).toAbsolutePath().normalize();
      report.set("data", sweep(data, cutoff, keep, preview, totals, notes));
    }
    if ("all".equals(target) || "trace".equals(target)) {
      report.set("trace", sweep(CommandTraceLog.currentDir(), cutoff, keep, preview, totals, notes));
    }
    if ("all".equals(target) || "upload".equals(target)) {
      // 暂存文件是「等会儿要交给页面」的,按时间清会把正在用的文件删掉,所以只在显式点名 upload 时才动
      if ("upload".equals(target)) {
        report.set("upload", sweep(UploadStore.dir(), cutoff, keep, preview, totals, notes));
      } else {
        notes.add("upload 目录没动:暂存文件可能正在被某个任务使用,要清请显式指定 scope:\"upload\"");
      }
    }
    report.set("scope", target).set("olderThanHours", olderThanHours == null ? 24 : olderThanHours)
        .set("keepLatest", keep).set("dryRun", preview).set("deletedFiles", totals[0])
        .set("freedBytes", totals[1]);
    if (!notes.isEmpty()) {
      report.set("notes", notes);
    }
    return RespBodyVo.ok(report);
  }

  /** 扫一个目录(递归),删掉超时且不在「最近 keep 个」里的文件 */
  private static Kv sweep(Path root, long cutoff, int keep, boolean dryRun, long[] totals, List<String> notes) {
    Kv result = new Kv().set("dir", root.toString());
    if (root == null || !Files.isDirectory(root)) {
      result.set("skipped", "目录不存在");
      return result;
    }
    List<Path> files = new ArrayList<>();
    try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile).forEach(files::add);
    } catch (IOException e) {
      result.set("error", briefMessage(e.getMessage()));
      return result;
    }
    // 每个目录内按修改时间从新到旧排序,前 keep 个一律保留
    Map<Path, List<Path>> byDir = new java.util.LinkedHashMap<>();
    for (Path file : files) {
      byDir.computeIfAbsent(file.getParent(), key -> new ArrayList<>()).add(file);
    }
    int deleted = 0;
    long bytes = 0;
    for (Map.Entry<Path, List<Path>> entry : byDir.entrySet()) {
      List<Path> group = entry.getValue();
      group.sort(java.util.Comparator.comparingLong((Path path) -> modifiedAt(path)).reversed());
      for (int i = 0; i < group.size(); i++) {
        Path file = group.get(i);
        if (i < keep || modifiedAt(file) >= cutoff) {
          continue;
        }
        long size = sizeOf(file);
        if (dryRun) {
          deleted++;
          bytes += size;
          continue;
        }
        try {
          Files.deleteIfExists(file);
          deleted++;
          bytes += size;
        } catch (IOException e) {
          notes.add("删不掉:" + file + "(" + briefMessage(e.getMessage()) + ")");
        }
      }
    }
    totals[0] += deleted;
    totals[1] += bytes;
    result.set("deletedFiles", deleted).set("freedBytes", bytes).set("scannedFiles", files.size());
    return result;
  }

  private static long modifiedAt(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  private static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return 0;
    }
  }

  /** 截图前等页面稳定:页面已经加载完时立即返回,没加载完最多等 CAPTURE_SETTLE_TIMEOUT_MS */
  private static void settle(BrowserInstance inst) {
    try {
      inst.page.waitForLoadState(LoadState.DOMCONTENTLOADED,
          new Page.WaitForLoadStateOptions().setTimeout(CAPTURE_SETTLE_TIMEOUT_MS));
    } catch (PlaywrightException e) {
      log.debug("截图前等待页面稳定超时:{}", briefMessage(e.getMessage()));
    }
  }

  /** 把页签文本 + 可交互结构化文本写成与截图同名的 .txt,返回对外 URL */
  private static String writeStateFile(BrowserInstance inst, int seq, String browserState, String text) {
    Path txt = dataDir(inst.id).resolve(seq + ".txt");
    try {
      Files.createDirectories(txt.getParent());
      Files.write(txt, (browserState + "\n\n" + text).getBytes(StandardCharsets.UTF_8));
      return "/" + DATA_DIR + "/" + inst.id + "/" + seq + ".txt";
    } catch (IOException e) {
      log.warn("任务 {} 第 {} 份结构化文本写文件失败:{}", inst.id, seq, e.getMessage());
      return null;
    }
  }

  /** 这个任务自己的标签页,index 可直接用于 switch_tab 与 close_tab(别的任务的页签不算在内) */
  private List<Kv> tabs(BrowserInstance inst) {
    List<Page> pages = pagesOf(inst);
    List<Kv> tabs = new ArrayList<>();
    for (int i = 0; i < pages.size(); i++) {
      Page page = pages.get(i);
      Kv tab = Kv.by("index", i).set("url", page.url()).set("title", safeTitle(page)).set("current", page == inst.page);
      tabs.add(tab);
    }
    return tabs;
  }

  private static String safeTitle(Page page) {
    try {
      return page.title();
    } catch (PlaywrightException e) {
      return "";
    }
  }

  /**
   * 把元素索引解析成定位器
   *
   * <p>
   * 优先使用最近一次 get_browser_state 的 DOM 树快照,索引即结构化文本里的 [index]; 还没有快照时回退为 CSS
   * 选择器顺序索引。
   *
   * @return 索引越界或没有对应元素时返回 null
   */
  private Locator resolveIndex(BrowserInstance inst, int index, String selector) {
    DOMState state = inst.domState;
    if (state != null) {
      DOMElementNode node = state.getSelectorMap().get(index);
      if (node == null) {
        return null;
      }
      // 带 frame 的快照里,索引可能属于某个 iframe:xpath 必须**相对该 frame 的 document** 求值。
      // 索引里已经带了 frame 信息,所以调用方不必传 frame 参数(见 get_browser_state 的 includeFrames)
      Frame frame = frameForIndex(inst, state, index);
      if (frame == null) {
        return inst.page.locator("xpath=/" + node.getXpath()).first();
      }
      return frame.locator("xpath=/" + node.getXpath()).first();
    }
    if (selector == null) {
      return null;
    }
    int size = inst.page.locator(selector).count();
    if (index < 0 || index >= size) {
      return null;
    }
    return inst.page.locator(selector).nth(index);
  }

  /**
   * 取某个索引所属的 frame;返回 null 表示「就在顶层文档里」
   *
   * <p>
   * frame 是运行期对象,页面一导航或 iframe 被重建,快照里那个句柄就 detach 了。所以这里先按 URL 在**当前**
   * 的 frame 树里重新认一次(同 URL 只认第一个,认不出来再退回快照句柄)。这样做的好处是:同一个快照里的
   * 索引在 iframe 重新加载之后仍然可用,不会报一句「元素不存在」就让人重取快照。
   */
  private static Frame frameForIndex(BrowserInstance inst, DOMState state, int index) {
    if (state == null) {
      // 没有快照时索引走的是「CSS 选择器索引空间」,只在顶层文档里数,没有 frame 可路由
      return null;
    }
    FrameSnapshot snapshot = state.frameOf(index);
    if (snapshot == null || snapshot.main) {
      return null;
    }
    Frame current = findFrameByUrl(inst, snapshot.url, snapshot.name);
    if (current != null) {
      return current;
    }
    // 认不出来(URL 也变了)就退回快照时的句柄:能用就用,不能用会让上层报元素不存在
    return snapshot.frame == null || snapshot.frame.isDetached() ? null : snapshot.frame;
  }

  /** 在当前 frame 树里按 URL(其次 name)认一个 frame */
  private static Frame findFrameByUrl(BrowserInstance inst, String url, String name) {
    try {
      for (DomService.FrameNode node : DomService.frameTree(inst.page)) {
        if (node.parentIndex < 0) {
          continue;
        }
        String candidate = safeFrameUrl(node.frame);
        if (url != null && !url.isEmpty() && url.equals(candidate)) {
          return node.frame;
        }
      }
      if (name != null && !name.isEmpty()) {
        for (DomService.FrameNode node : DomService.frameTree(inst.page)) {
          if (node.parentIndex < 0) {
            continue;
          }
          if (name.equals(safeFrameName(node.frame))) {
            return node.frame;
          }
        }
      }
    } catch (PlaywrightException e) {
      return null;
    }
    return null;
  }

  /**
   * 按 {@code frame} 参数取一个 frame
   *
   * <p>
   * 给「按选择器 / 按文本」这类**不带索引**的命令用:它们的定位目标是 {@code page.locator(...)},够不着
   * iframe 内部。取值可以是:
   * <ul>
   * <li>整数或数字串:frame 序号(见 {@code list_frames} 的 {@code index},0 是主 frame);</li>
   * <li>字符串:按 URL / name 子串匹配,例如 {@code "exmail.qq.com"}。</li>
   * </ul>
   *
   * @return 命中的 frame;{@code frame} 参数为空时返回 {@code page.mainFrame()}
   * @throws IllegalArgumentException 指定了但没匹配到
   */
  static Frame frameOf(BrowserInstance inst, Object frame) {
    String wanted = frame == null ? null : String.valueOf(frame).trim();
    if (wanted == null || wanted.isEmpty()) {
      return inst.page.mainFrame();
    }
    List<DomService.FrameNode> nodes = DomService.frameTree(inst.page);
    Integer ordinal = null;
    try {
      ordinal = Integer.valueOf(wanted);
    } catch (NumberFormatException ignored) {
      // 不是数字就按 URL / name 子串匹配
    }
    if (ordinal != null) {
      if (ordinal < 0 || ordinal >= nodes.size()) {
        throw new IllegalArgumentException("frame 序号越界：" + ordinal + "，当前共 " + nodes.size()
            + " 个 frame（0 是主 frame），用 list_frames 看清单");
      }
      return nodes.get(ordinal).frame;
    }
    for (DomService.FrameNode node : nodes) {
      String url = safeFrameUrl(node.frame);
      String name = safeFrameName(node.frame);
      if ((url != null && url.contains(wanted)) || (name != null && name.contains(wanted))) {
        return node.frame;
      }
    }
    StringBuilder available = new StringBuilder();
    for (int i = 0; i < nodes.size(); i++) {
      if (available.length() > 0) {
        available.append(" / ");
      }
      available.append(i).append(':').append(safeFrameUrl(nodes.get(i).frame));
    }
    throw new IllegalArgumentException("没有匹配 frame 的 frame：" + wanted + "（可用：" + available
        + "；也可以用 list_frames 看清单）");
  }

  /** 索引越界时补充说明,提醒客户端索引应当来自 get_browser_state */
  private static String indexHint(BrowserInstance inst) {
    DOMState state = inst.domState;
    if (state == null) {
      return ",当前没有页面快照,请先调用 get_browser_state 获取元素索引";
    }
    return "," + snapshotSuffix(inst);
  }

  /**
   * 一句话说清「当前这份索引的来历」
   *
   * <p>
   * <b>为什么要把这些数字放进报错里</b>:P3 那类问题的共性是「错误信息描述现象,但不指向原因,也不给动作」——
   * 拿到「索引越界」或「元素已脱离页面」之后,调用方只能自己想到「是不是快照过期了」,再去取一次快照对比。
   * 把「快照建于 12.3 秒前、共 38 个可交互元素(0–37)、这期间页面发生过 3 次 DOM 变更」直接写在报错里,
   * 一眼就能判断是索引取错了、还是页面早就变了。
   */
  private static String snapshotSuffix(BrowserInstance inst) {
    DOMState state = inst.domState;
    if (state == null) {
      return "当前没有页面快照,请先调用 get_browser_state 获取元素索引";
    }
    int total = state.getSelectorMap() == null ? 0 : state.getSelectorMap().size();
    StringBuilder sb = new StringBuilder("当前索引来自快照(");
    long age = inst.domStateAt <= 0 ? -1 : System.currentTimeMillis() - inst.domStateAt;
    if (age >= 0) {
      sb.append(String.format(java.util.Locale.ROOT, "%.1f 秒前", age / 1000.0));
    } else {
      sb.append("时间未知");
    }
    sb.append("),共 ").append(total).append(" 个可交互元素");
    if (total > 0) {
      sb.append("(合法区间 0-").append(total - 1).append(")");
    }
    if (inst.domStateMutations > 0) {
      int now = readMutationCount(inst);
      int delta = now - inst.domStateMutations;
      if (delta > 0) {
        sb.append(",这期间页面发生过 ").append(delta).append(" 次 DOM 变更");
      }
    }
    if (state.hasFrames()) {
      sb.append(",含 ").append(state.getFrames().size()).append(" 个 frame(索引已全局编号,自动路由)");
    }
    sb.append("。页面变化后索引会全部重算,请重新调用 get_browser_state,或改用 *_by_selector 这类不依赖索引的命令");
    return sb.toString();
  }

  /** 给一份快照记上「什么时候建的」与「当时页面变过多少次」,供报错时解释索引为什么失效 */
  private static void recordSnapshot(BrowserInstance inst, DOMState state) {
    inst.domState = state;
    inst.domStateAt = System.currentTimeMillis();
    inst.domStateMutations = readMutationCount(inst);
  }

  /**
   * 实例不存在
   *
   * <p>
   * <b>为什么要带上服务启动时间</b>:P5 那次实测里,服务重启(进程换了一对 PID)之后操作原任务拿到的是
   * {@code retryable:false} + 「没有找到对应的浏览器实例」,于是第一反应是「id 是不是写错了」——真实原因
   * 是**实例只在内存里,重启即失效**。把「服务于 X 启动」写进报错,这件事就不用靠人再想一遍,也不会被
   * {@code retryable:false} 误导(它不是「这个 id 不存在」,而是「这个进程里没有这个 id」)。
   */
  private static RespBodyVo notFound(Long browserId) {
    StringBuilder message = new StringBuilder("没有找到对应的浏览器实例：").append(browserId);
    message.append("（本服务进程启动于 ").append(STARTED_AT_TEXT)
        .append(",任务实例只存在内存里、重启即失效。先用 list_tasks 看现存任务;"
            + "确认是重启导致的话重新 start 一个即可——登录态在 profile 里,不会因为这个丢）");
    return RespBodyVo.fail(message.toString());
  }

  /**
   * 按索引取元素,索引必须来自最近一次 get_browser_state
   *
   * @return 越界或没有快照时返回 null
   */
  private Locator locatorOf(BrowserInstance inst, int index) {
    return resolveIndex(inst, index, null);
  }

  /** 元素操作失败时的提示:超时只表示未满足可操作条件，完整调用日志用于区分具体原因 */
  private static String actionFailure(String action, PlaywrightException e) {
    return ActionError.describe(action, e.getMessage());
  }

  /** 等待类接口失败时的提示:等待超时和元素失效不是一回事 */
  private static String waitFailure(String action, PlaywrightException e) {
    String message = briefMessage(e.getMessage());
    if (message.startsWith("Timeout")) {
      return action + " 超时：等待时间内条件一直没有满足";
    }
    return action + " 失败：" + message;
  }

  /**
   * 选择器/文本/角色/标签定位失败时的提示
   *
   * <p>
   * 这些接口和快照索引无关,所以不能说「请重新调用 get_browser_state」;超时基本都是元素不存在或不可见 (实测百度首页的搜索框 #kw
   * 被隐藏,fill 会等满 5 秒后失败)。
   */
  private static String locateFailure(String action, String target, PlaywrightException e) {
    return ActionError.describe(action, e.getMessage()) + ": " + target;
  }

  /** 按索引做一次点击类操作(dblclick/hover/focus/check/uncheck 共用),带索引失效重试 */
  private RespBodyVo clickLike(Long browserId, int index, String action, Consumer<Locator> consumer) {
    return clickLike(browserId, index, action, consumer, false);
  }

  private RespBodyVo clickLike(Long browserId, int index, String action, Consumer<Locator> consumer,
      boolean withReceipt) {
    return clickLike(browserId, index, action, consumer, withReceipt, null);
  }

  /**
   * 按索引点击类动作的统一入口
   *
   * <p>
   * {@code mode} 非空时走「原生 → JS 派发」的降级链(见 {@link #clickWithMode}),并把这次实际用的方式
   * 写进回执的 {@code data.mode};不传 mode 时保持原来的行为(只有原生点击)。
   */
  private RespBodyVo clickLike(Long browserId, int index, String action, Consumer<Locator> consumer,
      boolean withReceipt, String mode) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (mode == null) {
      return indexAction(inst, index, action, null, consumer, withReceipt);
    }
    ActionOutcome outcome = new ActionOutcome();
    return indexAction(inst, index, action, null,
        (locator) -> clickWithMode(locator, mode, outcome, () -> consumer.accept(locator)), withReceipt,
        outcome.extra);
  }

  /** 按索引读取一个值,返回 data.<key> */
  private RespBodyVo read(Long browserId, int index, String action, String key, Function<Locator, Object> reader) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator locator = locatorOf(inst, index);
    if (locator == null) {
      return RespBodyVo.fail(action + " 索引越界: " + index + indexHint(inst));
    }
    try {
      return RespBodyVo.ok(Kv.by(key, reader.apply(locator)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure(action, e));
    }
  }

  private RespBodyVo actBySelector(Long browserId, String action, String selector, Consumer<Locator> consumer) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return act(action, inst.page.locator(selector).first(), consumer, "选择器 " + selector);
  }

  /**
   * 选择器定位的根:不传 {@code frame} 就是主 frame,传了就把选择器限定在指定 frame 里
   *
   * <p>
   * 按选择器 / 按文本的命令用的是 {@code page.locator(...)},天生够不着 iframe 内部(跨域 iframe 里
   * {@code document.querySelector} 也穿不透)。要给这类命令加上 frame 能力,只需要换定位的根 ——
   * Playwright 的 {@code frame.locator(...)} 是在该 frame 自己的文档里求值的。
   *
   * @param frame frame 序号(0 是主 frame,见 {@code list_frames})或 URL/name 子串;为空表示主 frame
   * @throws IllegalArgumentException 指定了但没匹配到
   */
  private static Locator locatorIn(BrowserInstance inst, String frame, String selector) {
    return frameOf(inst, frame).locator(selector).first();
  }

  private RespBodyVo actByLocator(Long browserId, String action, String target,
      Function<BrowserInstance, Locator> locatorFn, Consumer<Locator> consumer) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return act(action, locatorFn.apply(inst), consumer, target);
  }

  private static RespBodyVo act(String action, Locator locator, Consumer<Locator> consumer) {
    return act(action, locator, consumer, null);
  }

  /**
   * @param target 选择器/文本/角色这类定位描述,失败提示里带上它;按索引定位时传 null
   */
  private static RespBodyVo act(String action, Locator locator, Consumer<Locator> consumer, String target) {
    try {
      consumer.accept(locator);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(target == null ? actionFailure(action, e) : locateFailure(action, target, e));
    }
    return RespBodyVo.ok();
  }

  private static void requireEditable(Locator locator) {
    // Return the actual reason before spending the timeout waiting on a read-only
    // date picker.
    if (locator.count() > 0) {
      if (!locator.isEnabled())
        throw new PlaywrightException("ELEMENT_DISABLED");
      if ("true".equals(locator.getAttribute("aria-readonly")) || locator.getAttribute("readonly") != null)
        throw new PlaywrightException("ELEMENT_READ_ONLY");
    }
  }

  private static void fillEditable(Locator locator, String value) {
    requireEditable(locator);
    locator.fill(value, new Locator.FillOptions().setTimeout(actionTimeoutMs()));
  }

  /**
   * 动作类命令的超时(毫秒)
   *
   * <p>默认 5 秒,按索引操作元素时「元素已失效就尽快失败」；但有的站点(实测 ant-design 的
   * SPA)可操作性检查本身就要好几秒,5 秒会把本来能成的点击判死。用
   * {@code browser.action.timeoutMs} 调大,或按次传 {@code timeoutMs}。
   */
  private static double actionTimeoutMs() {
    String configured = ChromeBrowser.config(KEY_ACTION_TIMEOUT);
    if (configured == null) {
      return INDEX_ACTION_TIMEOUT_MS;
    }
    try {
      double parsed = Double.parseDouble(configured.trim());
      return parsed > 0 ? parsed : INDEX_ACTION_TIMEOUT_MS;
    } catch (NumberFormatException e) {
      return INDEX_ACTION_TIMEOUT_MS;
    }
  }

  /** 按次覆盖的超时:没传就用全局配置 */
  private static double actionTimeoutMs(Integer timeoutMs) {
    return timeoutMs == null || timeoutMs <= 0 ? actionTimeoutMs() : timeoutMs;
  }

  /** 原生点击超时后是否自动降级为 JS 派发事件 */
  private static boolean jsFallbackEnabled() {
    String configured = ChromeBrowser.config(KEY_JS_FALLBACK);
    return configured == null || Boolean.parseBoolean(configured);
  }

  /** 原生点击失败后是否自动改用真实鼠标点击(排在 JS 派发之前) */
  private static boolean mouseFallbackEnabled() {
    String configured = ChromeBrowser.config(KEY_MOUSE_FALLBACK);
    return configured == null || Boolean.parseBoolean(configured);
  }

  private static Locator.ClickOptions clickOptions() {
    return new Locator.ClickOptions().setTimeout(actionTimeoutMs());
  }

  private static Locator.ClickOptions clickOptions(Integer timeoutMs) {
    return new Locator.ClickOptions().setTimeout(actionTimeoutMs(timeoutMs));
  }

  // ==================== 点击方式:原生 / JS 派发 / 自动降级 ====================

  /**
   * 用 JS 派发完整鼠标事件序列点击
   *
   * <p>
   * 有的站点(ant-design 的 Vue SPA 最典型)上 Playwright 的可操作性检查会一直不满足:元素明明在
   * 那里、点上去也该有反应,接口却等满超时返回「等待元素可操作超时」。这时唯一稳的办法就是在页面里
   * 直接派发事件——这也是这类站点上人手排障时最先试的一招。这里把它做成命令的一等选项,免得每个
   * 调用方都自己写一遍 {@code execute_js}。
   *
   * <p>
   * 顺带处理两种特例:复选框/单选框在事件派发后若 {@code checked} 没变,显式改一次并补
   * {@code input}/{@code change};元素先滚进视口,免得点到视口外的坐标。
   */
  private static final String JS_CLICK = """
      (el) => {
        if (!el) return 'no-element';
        try { el.scrollIntoView({block: 'center', inline: 'center'}); } catch (e) {}
        const rect = el.getBoundingClientRect();
        const at = {clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2};
        const base = {bubbles: true, cancelable: true, view: window, ...at};
        const wasChecked = el.checked;
        for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
          const Ctor = (type.startsWith('pointer') && window.PointerEvent) ? window.PointerEvent : MouseEvent;
          el.dispatchEvent(new Ctor(type, base));
        }
        if ((el.type === 'checkbox' || el.type === 'radio') && el.checked === wasChecked) {
          el.checked = !wasChecked;
          el.dispatchEvent(new Event('input', {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
        }
        return el.tagName;
      }
      """;

  /** JS 设值:走原生 setter 再派发 input/change,对「元素不可见但必须填」的字段是唯一办法 */
  private static final String JS_SET_VALUE = """
      (el, value) => {
        if (!el) return 'no-element';
        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
            : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
        const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
        if (descriptor && descriptor.set) { descriptor.set.call(el, value); } else { el.value = value; }
        el.dispatchEvent(new Event('input', {bubbles: true}));
        el.dispatchEvent(new Event('change', {bubbles: true}));
        return el.value;
      }
      """;

  /**
   * 一次动作实际用的方式
   *
   * <p>
   * 回执要如实说明「这次是原生点击还是 JS 降级」,而回执是在动作**之后**才组装的:所以这里持有一个
   * Kv,动作过程中往里写,回执组装时读到的就是最终值(直接传一个「当时算好的 Kv」会被 Java 的
   * 立即求值坑到——拿到的是动作前的默认值)。
   */
  private static final class ActionOutcome {
    final Kv extra = new Kv();
    String mode = "native";
    String fallbackReason;
    String timeoutHint;

    /** 把当前状态写进与回执共享的 Kv */
    void record() {
      extra.set("mode", mode);
      if (fallbackReason != null) {
        extra.set("fallbackReason", fallbackReason).set("fallbackFrom", "native");
        if ("js".equals(mode)) {
          extra.set("note", "原生点击没成功(通常是元素可操作性检查不满足),已降级为 JS 派发事件。"
              + "注意:**JS 派发不保证生效**——实测部分站点(ant-design 的 Modal.confirm 确定按钮、"
              + "对话框右上角关闭按钮)只认真实鼠标事件,遇到「点了没反应」请用 mode:\"mouse\" 重试");
        } else {
          extra.set("note", "原生点击没成功(可操作性检查不满足),已改用真实鼠标点击"
              + "(跳过检查,但事件仍是浏览器真实事件);这类站点上这是正常现象,不是配置错了");
        }
      }
      if (timeoutHint != null) {
        extra.set("hint", timeoutHint);
      }
    }
  }

  /**
   * 按方式执行一次点击类动作
   *
   * <p>
   * {@code auto}(默认)的降级链是 **原生点击 → 真实鼠标 → JS 派发**,三档各有各的适用面:
   *
   * <ol>
   * <li><b>原生</b>({@code locator.click})带可操作性检查:可见、稳定、不被遮挡、能接收事件,
   * 检查不满足就等满超时;</li>
   * <li><b>真实鼠标</b>({@code page.mouse.click} 打在元素盒子中心)跳过这些检查,但派发出的仍是
   * **浏览器真实事件**——实测 ant-design 的 {@code Modal.confirm} 确定按钮、对话框右上角 ×
   * 只认这一种,JS 派发完全无效(点了没反应、弹窗不关,反复点还会把确认框一层层叠起来);</li>
   * <li><b>JS 派发</b>是最后手段,对「必须走框架/真实事件」的按钮**可能完全无效**,所以回执只声明
   * 「已执行」,不再谎称「已成功」。</li>
   * </ol>
   *
   * <p>
   * 另外,点击**之前**先做一次命中测试:目标中心点上命中的若是别的元素(常见:用户服务协议层
   * {@code .agreement-container}、弹窗遮罩),就写进回执的 {@code coveredBy}——这是「点了没反应」
   * 最常见的原因,以前只能靠人肉发现。
   *
   * @param mode {@code auto}(默认:原生失败依次降级)、{@code native}(只用原生)、
   *             {@code mouse}(只用真实鼠标)、{@code js}(只用 JS 派发)
   */
  private static void clickWithMode(Locator locator, String mode, ActionOutcome outcome, Runnable nativeAction) {
    String resolved = normalizeMode(mode);
    boolean covered = recordBlocker(locator, outcome);
    if ("js".equals(resolved)) {
      jsClick(locator);
      outcome.mode = "js";
      outcome.record();
      return;
    }
    if ("mouse".equals(resolved)) {
      if (!mouseClick(locator)) {
        throw new PlaywrightException("mouse 模式失败:拿不到元素盒子(元素可能不可见或已从页面移除)");
      }
      outcome.mode = "mouse";
      outcome.record();
      return;
    }
    try {
      nativeAction.run();
      outcome.mode = "native";
    } catch (PlaywrightException nativeFailure) {
      if (!"auto".equals(resolved)) {
        throw nativeFailure;
      }
      outcome.fallbackReason = briefMessage(nativeFailure.getMessage());
      if (covered) {
        // 目标被盖住时**刻意不用真实鼠标**:鼠标点的是那个坐标,落在遮挡物上,会把遮挡物按下去
        // (实测最坑的一种「点错了」)。JS 派发虽然可能被框架忽略,但事件至少是给目标的。
        outcome.extra.set("mouseSkipped", "target-covered");
        if (jsFallbackEnabled()) {
          jsClick(locator);
          outcome.mode = "js";
        } else {
          throw nativeFailure;
        }
      } else if (mouseFallbackEnabled() && mouseClick(locator)) {
        outcome.mode = "mouse";
      } else if (jsFallbackEnabled()) {
        jsClick(locator);
        outcome.mode = "js";
      } else {
        throw nativeFailure;
      }
    }
    outcome.record();
  }

  /**
   * 真实鼠标点击:元素滚进视口后,取盒子中心派发浏览器真实事件
   *
   * <p>
   * 与 {@code locator.click} 的区别是**不做可操作性检查**(不判断遮挡/稳定/可接收事件),
   * 与 {@code JS_CLICK} 的区别是**事件是真的**。跳过检查 + 真实事件这个组合,正好覆盖
   * 「元素在、能点、但 Playwright 认为它不可操作」和「框架只认真实事件」两类场景。
   *
   * @return 是否成功派发(拿不到盒子就返回 false,交给下一档)
   */
  private static boolean mouseClick(Locator locator) {
    try {
      Locator target = locator.first();
      try {
        target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(2_000));
      } catch (PlaywrightException ignored) {
        // 滚不动也继续:盒子可能本来就在视口里
      }
      BoundingBox box = target.boundingBox();
      if (box == null || box.width <= 0 || box.height <= 0) {
        return false;
      }
      target.page().mouse().click(box.x + box.width / 2, box.y + box.height / 2);
      return true;
    } catch (PlaywrightException e) {
      return false;
    }
  }

  /** 目标中心点上实际命中的是谁——被覆盖层挡住是「点了没反应」的头号原因 */
  private static final String JS_HIT_TEST = """
      (self, point) => {
        const describe = (el) => el ? {
          tag: el.tagName,
          id: el.id || null,
          className: typeof el.className === 'string' ? el.className.slice(0, 120) : null,
          text: (el.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 60)
        } : null;
        const hit = document.elementFromPoint(point[0], point[1]);
        if (!hit) return { covered: false, reason: 'no-element-at-point' };
        const isSelf = hit === self || self.contains(hit) || hit.contains(self);
        return { covered: !isSelf, hit: describe(hit), target: describe(self) };
      }
      """;

  /** 命中测试:目标被别的元素盖住时把「谁盖的」写进回执 */
  private static boolean recordBlocker(Locator locator, ActionOutcome outcome) {
    Kv probe = hitTest(locator);
    if (probe == null || !Boolean.TRUE.equals(probe.getBoolean("covered"))) {
      return false;
    }
    outcome.extra.set("coveredBy", probe.get("hit")).set("coveredHint",
        "目标中心点上实际命中的是别的元素(常见:用户服务协议层、弹窗遮罩、叠起来的确认框),"
            + "这类点击很容易被吃掉:先关掉遮挡物再点(close_modal);确实要点被遮住的元素时用 mode:\"js\"");
    return true;
  }

  /** @return 命中信息;元素不可见/拿不到盒子/脚本报错时返回 null(诊断失败不该影响动作) */
  private static Kv hitTest(Locator locator) {
    try {
      Locator target = locator.first();
      BoundingBox box = target.boundingBox();
      if (box == null || box.width <= 0 || box.height <= 0) {
        return null;
      }
      Object raw = target.evaluate(JS_HIT_TEST, List.of(box.x + box.width / 2, box.y + box.height / 2));
      if (!(raw instanceof Map)) {
        return null;
      }
      Kv probe = new Kv();
      probe.putAll((Map<?, ?>) raw);
      return probe;
    } catch (PlaywrightException e) {
      return null;
    }
  }

  /** 点击失败时附一句「被谁挡住了」,失败信息里直接给出原因 */
  private static String blockerSuffix(Locator locator) {
    Kv probe = hitTest(locator);
    if (probe == null || !Boolean.TRUE.equals(probe.getBoolean("covered"))) {
      return "";
    }
    Object hit = probe.get("hit");
    String description = hit instanceof Map ? String.valueOf(((Map<?, ?>) hit).get("text")) + " <"
        + String.valueOf(((Map<?, ?>) hit).get("tag")) + " class=" + String.valueOf(((Map<?, ?>) hit).get("className"))
        + ">" : String.valueOf(hit);
    return "；目标中心点上实际命中的是别的元素:" + description + "(被遮挡,先关掉遮挡物或改用 mode:\"mouse\")";
  }

  private static void jsClick(Locator locator) {
    locator.first().evaluate(JS_CLICK);
  }

  /** auto / native / mouse / js / dispatch / force 的归一化 */
  private static String normalizeMode(String mode) {
    if (mode == null || mode.isBlank()) {
      return "auto";
    }
    String value = mode.trim().toLowerCase(java.util.Locale.ROOT);
    if ("dispatch".equals(value) || "force".equals(value) || "js".equals(value) || "event".equals(value)) {
      return "js";
    }
    if ("mouse".equals(value) || "real".equals(value) || "coordinate".equals(value) || "coords".equals(value)) {
      return "mouse";
    }
    if ("native".equals(value) || "playwright".equals(value)) {
      return "native";
    }
    return "auto";
  }

  /** 输入方式归一化:auto / native(fill) / type / js */
  private static String normalizeInputMode(String mode) {
    if (mode == null || mode.isBlank()) {
      return "auto";
    }
    String value = mode.trim().toLowerCase(java.util.Locale.ROOT);
    if ("js".equals(value) || "setvalue".equals(value) || "set_value".equals(value)) {
      return "js";
    }
    if ("type".equals(value) || "press".equals(value)) {
      return "type";
    }
    if ("native".equals(value) || "fill".equals(value)) {
      return "native";
    }
    return "auto";
  }

  /**
   * 填一个输入框
   *
   * <p>
   * {@code auto}(默认)会先看元素可不可见:<b>能看见就用 Playwright 的真实输入</b>(会派发正常事件、
   * 进框架模型),看不见(分步表单里非当前步的字段、{@code display:none} 的 textarea)才退回 JS 设值,
   * 并在回执里明确写 {@code mode=js} 与 {@code committed=false}——这类值提交时可能是空的。
   */
  private static void fillWithMode(Locator locator, String value, String mode, ActionOutcome outcome) {
    String resolved = normalizeInputMode(mode);
    if ("js".equals(resolved)) {
      jsSetValue(locator, value);
      outcome.mode = "js";
      outcome.timeoutHint = JS_MODE_NOTE;
      outcome.record();
      return;
    }
    if ("type".equals(resolved)) {
      requireEditable(locator);
      locator.pressSequentially(value, new Locator.PressSequentiallyOptions().setTimeout(actionTimeoutMs()));
      outcome.mode = "type";
      outcome.record();
      return;
    }
    if ("native".equals(resolved)) {
      fillEditable(locator, value);
      outcome.mode = "fill";
      outcome.record();
      return;
    }
    if (isUsableForInput(locator)) {
      fillEditable(locator, value);
      outcome.mode = "fill";
    } else {
      jsSetValue(locator, value);
      outcome.mode = "js";
      outcome.timeoutHint = JS_MODE_NOTE;
    }
    outcome.record();
  }

  private static void jsSetValue(Locator locator, String value) {
    locator.first().evaluate(JS_SET_VALUE, value);
  }

  /** 元素是否「看得见、能真实输入」:隐藏或 0×0 的元素走 fill 会得到 ELEMENT_HIDDEN */
  private static boolean isUsableForInput(Locator locator) {
    try {
      if (locator.count() == 0) {
        return false;
      }
      Locator first = locator.first();
      if (!first.isVisible()) {
        return false;
      }
      BoundingBox box = first.boundingBox();
      return box != null && box.width > 0 && box.height > 0;
    } catch (PlaywrightException e) {
      return false;
    }
  }

  /** 输入类动作的公开实现:回执里带上 mode/committed */
  private static RespBodyVo inputResult(ActionOutcome outcome) {
    Kv data = Kv.by("mode", outcome.mode).set("committed", !"js".equals(outcome.mode));
    if ("js".equals(outcome.mode)) {
      data.set("note", JS_MODE_NOTE);
    }
    return RespBodyVo.ok(data);
  }

  // ==================== 点击回执与索引失效重试 ====================

  /** 动作前后探针:url、页签数、正文长度,用于观察变化，不能证明业务成功或失败 */
  private static Kv stateProbe(BrowserInstance inst) {
    return stateProbe(inst, null);
  }

  /**
   * 同上,但探针在指定的 frame 里取
   *
   * <p>
   * <b>为什么必须分 frame</b>:点 iframe 里的按钮时,顶层文档的 `body.innerText` 与 DOM 结构**一点都不会变**
   * (iframe 的内容不在顶层文档里),于是回执永远是 `changed:false` —— 动作明明生效了,却被报成「没变化」,
   * 这比不报还误导。所以动作目标在哪个 frame,探针就在哪个 frame 里取。
   *
   * @param frame 探针所在的 frame;null 表示顶层文档
   */
  private static Kv stateProbe(BrowserInstance inst, Frame frame) {
    Frame target = frame == null ? inst.page.mainFrame() : frame;
    Kv probe = Kv.by("url", inst.page.url()).set("tabCount", pagesOf(inst).size());
    if (frame != null) {
      // 一并记住 frame 自己的 URL:iframe 内部跳页时顶层 URL 不变,靠它才能看出变化
      probe.set("frameUrl", safeFrameUrl(frame));
    }
    try {
      Object fingerprint = target.evaluate("""
          () => {
            const text = document.body ? document.body.innerText : '';
            const controls = Array.from(document.querySelectorAll('input,textarea,select')).map(e =>
              [e.type === 'password' ? '[redacted]' : e.value, e.checked, e.disabled, e.readOnly]);
            const structure = Array.from(document.querySelectorAll('body *'))
              .filter(e => !e.closest('#playwright-highlight-container'))
              .map(e => [e.tagName,e.className?.baseVal ?? e.className,e.getAttribute('style'),
                e.getAttribute('aria-expanded'),e.getAttribute('aria-selected'),e.hidden]);
            const source = JSON.stringify([text, controls, structure]);
            let hash = 2166136261;
            for (let i=0;i<source.length;i++) hash = Math.imul(hash ^ source.charCodeAt(i), 16777619);
            return {textLength:text.length, fingerprint:String(hash >>> 0)};
          }
          """);
      if (fingerprint instanceof Map)
        probe.putAll((Map) fingerprint);
    } catch (PlaywrightException e) {
      probe.set("probeError", briefMessage(e.getMessage()));
    }
    return probe;
  }

  private static boolean probeChanged(Kv before, Kv after) {
    return !java.util.Objects.equals(before.get("url"), after.get("url"))
        || !java.util.Objects.equals(before.get("tabCount"), after.get("tabCount"))
        || !java.util.Objects.equals(before.get("frameUrl"), after.get("frameUrl"))
        || (before.containsKey("fingerprint") && after.containsKey("fingerprint")
            && !java.util.Objects.equals(before.get("fingerprint"), after.get("fingerprint")));
  }

  private static Kv observeAfter(Kv before, BrowserInstance inst, Frame frame) {
    Kv after = stateProbe(inst, frame);
    long deadline = System.nanoTime() + 500_000_000L;
    while (!probeChanged(before, after) && !after.containsKey("probeError") && System.nanoTime() < deadline) {
      // Pump Playwright events while waiting, allowing delayed popups and framework
      // updates to arrive.
      inst.page.waitForTimeout(50);
      after = stateProbe(inst, frame);
    }
    return after;
  }

  private static int asInt(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }

  /**
   * 动作回执
   *
   * <p>
   * 「点一下到底生效没有」光看 ok=true 判断不出来:实测点悬浮菜单项时命中的是纯文本节点,
   * 接口返回成功但页面毫无变化。这里把前后状态一起返回,changed 为 false 只说明观察窗口内尚未发现变化。
   */
  private static Kv receipt(Kv before, BrowserInstance inst) {
    return receipt(before, inst, null);
  }

  /** 同上,{@code frame} 非空时探针取在该 frame 里(见 {@link #stateProbe(BrowserInstance, Frame)}) */
  private static Kv receipt(Kv before, BrowserInstance inst, Frame frame) {
    Kv after = observeAfter(before, inst, frame);
    String urlBefore = String.valueOf(before.get("url"));
    String urlAfter = String.valueOf(after.get("url"));
    int tabBefore = asInt(before.get("tabCount"));
    int tabAfter = asInt(after.get("tabCount"));
    int lenBefore = asInt(before.get("textLength"));
    int lenAfter = asInt(after.get("textLength"));
    boolean changed = probeChanged(before, after);
    Kv report = Kv.by("urlBefore", urlBefore).set("urlAfter", urlAfter).set("tabCountBefore", tabBefore)
        .set("tabCountAfter", tabAfter).set("textLengthBefore", lenBefore).set("textLengthAfter", lenAfter)
        .set("changed", changed).set("changeStatus", changed ? "observed" : "not_observed")
        .set("observationComplete", !before.containsKey("probeError") && !after.containsKey("probeError"))
        .set("observationWindowMs", 500);
    if (frame != null) {
      report.set("probedFrameUrl", after.get("frameUrl"));
    }
    return report;
  }

  /** 动作成功但页面毫无变化时,把 changed=false 和提示一起返回 */
  private static RespBodyVo okWithReceipt(Kv before, BrowserInstance inst, String action) {
    return okWithReceipt(before, inst, action, null);
  }

  /** extra 非空时并进回执(点击方式 mode、命中元素信息这类) */
  private static RespBodyVo okWithReceipt(Kv before, BrowserInstance inst, String action, Kv extra) {
    return okWithReceipt(before, inst, action, extra, null);
  }

  /** frame 非空时探针取在该 frame 里(点 iframe 里的元素时必传,否则 changed 永远是 false) */
  private static RespBodyVo okWithReceipt(Kv before, BrowserInstance inst, String action, Kv extra, Frame frame) {
    Kv report = receipt(before, inst, frame);
    if (extra != null) {
      report.set(extra);
    }
    boolean changed = Boolean.TRUE.equals(report.getBoolean("changed"));
    // effective 是给智能体用的机器可读结论:动作发出去了,但观察窗口内页面没动 = 不保证生效
    report.set("effective", changed);
    if (!changed && !report.containsKey("hint")) {
      report.set("hint", action + " 已执行，但观察窗口内尚未发现变化；不代表点击失败，请等待目标条件或读取新状态");
    }
    if (!changed && report.containsKey("coveredBy")) {
      report.set("hint", action + " 已执行但页面没变化，且目标中心点上命中的是别的元素——很可能被遮挡物吃掉了(见 coveredBy)");
    }
    return RespBodyVo.ok(report);
  }

  /**
   * 按索引动作,带索引失效重试(见下面的七参重载)
   */
  private RespBodyVo indexAction(BrowserInstance inst, int index, String action, String fallbackSelector,
      Consumer<Locator> consumer, boolean withReceipt) {
    return indexAction(inst, index, action, fallbackSelector, consumer, withReceipt, null);
  }

  /**
   * 按索引动作,带索引失效重试
   *
   * <p>
   * 索引来自最近一次 get_browser_state 的 xpath。Vue/React 一重渲染(悬浮菜单尤其明显), xpath
   * 指向的节点就没了,原来只能报「元素不存在或页面已变化」。这里失败后做两级补救: 先等 300ms 用同一个 xpath
   * 重试(动画或异步渲染的抖动),再重取一次临时快照,按 「同 tag + 同文本且全页唯一」把元素找回来。都失败就照旧报错,不会乱点别的元素。
   *
   * <p>
   * 补救用的临时快照不覆盖 inst.domState,避免索引悄悄漂移。
   *
   * @param extra 并进成功回执的附加信息(例如这次点击的 {@code mode});闭包可以在动作过程中往里写,
   *              回执是在动作之后组装的
   */
  private RespBodyVo indexAction(BrowserInstance inst, int index, String action, String fallbackSelector,
      Consumer<Locator> consumer, boolean withReceipt, Kv extra) {
    Locator locator = resolveIndex(inst, index, fallbackSelector);
    if (locator == null) {
      return RespBodyVo.fail(action + " 索引越界: " + index + indexHint(inst));
    }
    // 命中元素的信息一起带回去:拿到 changed=false 之后,「这次到底点中了什么」是唯一的下一步线索。
    // click_element_by_text 一直是这么做的,这里把它推广到按索引的动作
    Kv hit = describe(locator, "index=" + index);
    if (hit != null) {
      if (extra == null) {
        extra = new Kv();
      }
      extra.set("hit", hit);
    }
    // 目标在哪个 frame,探针就在哪个 frame 里取:否则点 iframe 里的元素永远是 changed:false
    Frame probeFrame = frameForIndex(inst, inst.domState, index);
    Kv before = withReceipt ? stateProbe(inst, probeFrame) : null;
    PlaywrightException failure;
    try {
      consumer.accept(locator);
      return withReceipt ? okWithReceipt(before, inst, action, extra, probeFrame) : RespBodyVo.ok(extra);
    } catch (PlaywrightException e) {
      failure = e;
    }
    sleepQuietly(300);
    try {
      consumer.accept(locator);
      return withReceipt ? okWithReceipt(before, inst, action, extra, probeFrame) : RespBodyVo.ok(extra);
    } catch (PlaywrightException e) {
      failure = e;
    }
    Locator recovered = recoverByIdentity(inst, index);
    if (recovered != null) {
      try {
        consumer.accept(recovered);
        return withReceipt ? okWithReceipt(before, inst, action, extra, probeFrame) : RespBodyVo.ok(extra);
      } catch (PlaywrightException e) {
        failure = e;
      }
    }
    // 失败信息里也带上快照来历:是索引过期、还是元素真的不可操作,这两件事的下一步完全不同
    return RespBodyVo.fail(actionFailure(action, failure) + "（" + snapshotSuffix(inst) + "）");
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 按「同 tag + 同文本」在重取的临时快照里找回元素
   *
   * <p>
   * 只有全页唯一匹配才返回;匹配到多个或一个都没有时返回 null,宁可失败也不猜。
   */
  private Locator recoverByIdentity(BrowserInstance inst, int index) {
    DOMState snapshot = inst.domState;
    if (snapshot == null) {
      return null;
    }
    DOMElementNode old = snapshot.getSelectorMap().get(index);
    if (old == null) {
      return null;
    }
    String text = old.getAllTextTillNextClickableElement(-1);
    if (text == null || text.isEmpty()) {
      return null;
    }
    DOMState fresh;
    try {
      fresh = DomService.getClickableElements(inst.page, false, -1, 0);
    } catch (PlaywrightException e) {
      return null;
    }
    List<String> matches = new ArrayList<>();
    for (DOMElementNode node : fresh.getSelectorMap().values()) {
      if (!old.getTagName().equals(node.getTagName())) {
        continue;
      }
      if (text.equals(node.getAllTextTillNextClickableElement(-1))) {
        matches.add(node.getXpath());
      }
    }
    if (matches.size() != 1) {
      return null;
    }
    return inst.page.locator("xpath=/" + matches.get(0)).first();
  }

  private static double timeoutMillis(Double seconds) {
    return seconds == null ? DEFAULT_WAIT_TIMEOUT_MS : seconds * 1_000;
  }

  private static MouseButton mouseButton(String button) {
    if ("right".equalsIgnoreCase(button)) {
      return MouseButton.RIGHT;
    }
    if ("middle".equalsIgnoreCase(button)) {
      return MouseButton.MIDDLE;
    }
    return MouseButton.LEFT;
  }

  private static String defaultPdfPath() {
    return Paths.get(System.getProperty("user.home"), "Downloads", "broswer", "page-" + SnowflakeIdUtils.id() + ".pdf")
        .toString();
  }

  /** 截图/PDF 落盘前先建目录 */
  private static void ensureParent(String path) {
    Path parent = Paths.get(path).toAbsolutePath().getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException e) {
      log.warn("创建目录失败:{}", parent);
    }
  }

  public Page currentPage(Long id) {
    BrowserInstance inst = INSTANCES.get(id);
    return inst.page;
  }

  /** 新增 go_to_url （功能同 navigate，但单独接口） */
  public RespBodyVo goToUrl(Long browserId, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      Response rsp = inst.page.navigate(url);
      return RespBodyVo.ok(Kv.by("status", rsp == null ? 0 : rsp.status()));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("go_to_url 失败：" + briefMessage(e.getMessage()));
    }
  }

  /**
   * 后退一页
   *
   * <p>
   * goBack() 返回 null 只表示目标页没有 HTTP 响应(例如 about:blank),并不代表后退失败, 因此这里用 URL
   * 是否变化来判断。
   */
  public RespBodyVo goBack(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    String before = inst.page.url();
    Response rsp;
    try {
      rsp = inst.page.goBack();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("go_back 失败：" + briefMessage(e.getMessage()));
    }
    String after = inst.page.url();
    if (after.equals(before)) {
      return RespBodyVo.fail("无法后退：没有可用历史记录");
    }
    return RespBodyVo.ok(Kv.by("status", rsp == null ? 0 : rsp.status()).set("url", after));
  }

  /**
   * 固定等待若干秒
   *
   * <p>
   * 方法名不叫 wait:Object.wait 会抢走重载解析,调用处只能写成 this.wait 才不歧义。
   */
  public RespBodyVo waitSeconds(Long browserId, Integer seconds) {
    if (!INSTANCES.containsKey(browserId)) {
      return notFound(browserId);
    }
    if (seconds == null || seconds <= 0) {
      return RespBodyVo.fail("wait 的 seconds 必须是正整数");
    }
    try {
      Thread.sleep(seconds * 1_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return RespBodyVo.fail("等待被中断");
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo clickElementByIndex(Long browserId, int index) {
    return clickElementByIndex(browserId, index, null, null);
  }

  /**
   * 按索引点击
   *
   * @param mode      {@code auto}(默认)/{@code native}/{@code js},见 {@link #clickWithMode}
   * @param timeoutMs 按次覆盖超时(毫秒),不传用 {@code browser.action.timeoutMs}
   */
  public RespBodyVo clickElementByIndex(Long browserId, int index, String mode, Integer timeoutMs) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    ActionOutcome outcome = new ActionOutcome();
    return indexAction(inst, index, "click_element_by_index", CLICKABLE_SELECTOR,
        (locator) -> clickWithMode(locator, mode, outcome, () -> locator.click(clickOptions(timeoutMs))), true,
        outcome.extra);
  }

  /**
   * 点击后如果有新页签弹出,把它切成当前页并带到最前
   *
   * <p>
   * 最多等 1.5 秒;没有新页签就什么也不做。
   */
  private void adoptNewTab(BrowserInstance inst, int tabCountBefore) {
    for (int i = 0; i < 15; i++) {
      List<Page> pages = pagesOf(inst);
      if (pages.size() > tabCountBefore) {
        Page newest = pages.get(pages.size() - 1);
        inst.page = newest;
        activate(newest);
        try {
          newest.waitForLoadState(LoadState.DOMCONTENTLOADED,
              new Page.WaitForLoadStateOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
        } catch (PlaywrightException e) {
          log.debug("新页签等待加载超时:{}", briefMessage(e.getMessage()));
        }
        return;
      }
      sleepQuietly(100);
    }
  }

  public RespBodyVo inputTextByIndex(Long browserId, int index, String value) {
    return inputTextByIndex(browserId, index, value, null);
  }

  /**
   * 按索引填文本
   *
   * @param mode {@code auto}(默认:能看见就用真实输入,看不见退回 JS 设值)/{@code native}/{@code type}/{@code js}
   */
  public RespBodyVo inputTextByIndex(Long browserId, int index, String value, String mode) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    Locator locator = resolveIndex(inst, index, INPUT_SELECTOR);
    if (locator == null) {
      return RespBodyVo.fail("input_text 索引越界: " + index + indexHint(inst));
    }
    ActionOutcome outcome = new ActionOutcome();
    try {
      fillWithMode(locator, value, mode, outcome);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("input_text", e));
    }
    return inputResult(outcome);
  }

  public RespBodyVo uploadFile(Long browserId, int index, String path) {
    return uploadFile(browserId, index, null, path, null);
  }

  /**
   * 把服务端本地文件交给页面的 {@code input[type=file]}
   *
   * <p>
   * 三件事比原来好用了:
   * <ul>
   * <li><b>可以用选择器</b>:{@code display:none} 的 file input 根本进不了快照(没有索引),而
   * {@code setInputFiles} 本来就不要求元素可见——传 {@code selector} 就能直接传文件,不必先
   * 用 execute_js 把元素显示出来再重新取快照;</li>
   * <li><b>相对路径按服务端 upload 目录解析</b>:客户端先把文件 POST 到 {@code /playwright/upload},再把回执里的
   * {@code relativePath} 原样回填到这里即可(客户端-服务器模式下两边不是同一台机器);</li>
   * <li><b>文件不存在时说清怎么办</b>,而不是抛一句 Playwright 的英文错误。</li>
   * </ul>
   */
  public RespBodyVo uploadFile(Long browserId, Integer index, String selector, String path, Integer timeoutMs) {
    return uploadFile(browserId, index, selector, path, timeoutMs, null);
  }

  /**
   * 同上,额外支持 {@code frame}(file input 在跨域 iframe 里时用)
   */
  public RespBodyVo uploadFile(Long browserId, Integer index, String selector, String path, Integer timeoutMs,
      String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    if ((selector == null || selector.isBlank()) && index == null) {
      return RespBodyVo.fail("upload_file 需要 index 或 selector 之一");
    }
    Path file = UploadStore.resolve(path);
    if (file == null) {
      return RespBodyVo.fail("upload_file 路径非法：" + path);
    }
    if (!Files.isRegularFile(file)) {
      return RespBodyVo.fail("upload_file 找不到文件：" + file
          + "（path 必须是**服务端**能打开的路径；客户端-服务器模式下请先把文件 POST 到 /playwright/upload，"
          + "再把回执里的 path 或 relativePath 传进来）");
    }
    Locator locator;
    String target;
    Frame probeFrame;
    if (selector != null && !selector.isBlank()) {
      try {
        // 权威的监听器探测(CDP)必须知道元素在哪个 frame 里:不传 frame 参数就是主 frame
        probeFrame = frame == null || frame.isBlank() ? inst.page.mainFrame() : frameOf(inst, frame);
        locator = locatorIn(inst, frame, selector);
      } catch (IllegalArgumentException e) {
        return RespBodyVo.fail("upload_file 失败：" + e.getMessage());
      }
      target = "selector=" + selector + frameSuffix(frame);
    } else {
      locator = locatorOf(inst, index);
      target = "index=" + index;
      if (locator == null) {
        return RespBodyVo.fail("upload_file 索引越界: " + index + indexHint(inst));
      }
      probeFrame = frameForIndex(inst, inst.domState, index);
      if (probeFrame == null) {
        probeFrame = inst.page.mainFrame();
      }
    }
    Kv before = stateProbe(inst, probeFrame);
    try {
      locator.setInputFiles(file, new Locator.SetInputFilesOptions().setTimeout(actionTimeoutMs(timeoutMs)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("upload_file", target, e));
    }
    Kv data = Kv.by("filename", file.getFileName().toString()).set("path", file.toString())
        .set("size", UploadStore.sizeOf(file)).set("target", target).set("mode", "native");
    // setInputFiles 的语义只是「把文件放进 input」,页面有没有消费完全是另一回事 —— 所以必须回读
    appendUploadReadback(locator, probeFrame, elementResolveScript(inst, index, selector), data);
    Kv report = receipt(before, inst, probeFrame);
    data.set("changed", report.get("changed")).set("changeStatus", report.get("changeStatus"))
        .set("observationWindowMs", report.get("observationWindowMs"));
    if ("noListener".equals(data.getStr("consumed")) && Boolean.FALSE.equals(report.getBoolean("changed"))) {
      data.set("effective", false);
    }
    return RespBodyVo.ok(data);
  }

  /**
   * 给 CDP 用的「解析这个元素」表达式
   *
   * <p>优先用调用方给的 CSS 选择器;按索引定位时用快照里的 xpath。两条都拿不到就返回 null,
   * 这时只走启发式探测(结论会如实标成「未知」,而不是「没有」)。
   */
  private static String elementResolveScript(BrowserInstance inst, Integer index, String selector) {
    if (selector != null && !selector.isBlank()) {
      return "document.querySelector(" + JSON.toJSONString(selector) + ")";
    }
    if (index == null || inst.domState == null) {
      return null;
    }
    DOMElementNode node = inst.domState.getSelectorMap().get(index);
    if (node == null) {
      return null;
    }
    return "document.evaluate(" + JSON.toJSONString("/" + node.getXpath())
        + ", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue";
  }

  /**
   * 上传之后回读 input 的真实状态
   *
   * <p>
   * <b>为什么必须回读</b>:实测企业微信后台的营业执照上传,{@code upload_file} 回了 {@code ok:true},页面却
   * 一直停在「请上传工商营业执照」。根因是那个 {@code <input class="uploadInput">} **没有挂任何事件监听器**
   * (Vue 2 的 {@code el._vei} 为空):{@code setInputFiles} 把 {@code input.files} 设好了,但派发的
   * {@code change} 到不了框架的 handler,组件的 {@code upload()} 从来没被调用。
   *
   * <p>
   * 「报成功但没生效」比报错危险得多 —— 它会把排查引到选择器、文件、接口上去。所以这里把三件事一起回读:
   * <ul>
   * <li>{@code filesLength}:input 里现在到底有几个文件(正常应当是 1);</li>
   * <li>{@code listeners}/{@code hasListeners}:这个 input 挂了哪些事件(见 {@link ListenerProbe#probe});</li>
   * <li>{@code consumed}:启发式结论 {@code listened} / {@code noListener} / {@code unknown}。</li>
   * </ul>
   *
   * @param frame        {@code locator} 所在的 frame;null 表示主 frame(权威探测要拿它开 CDP 会话)
   * @param elementScript 解析该元素的 JS 表达式,给 CDP 用;为 null 时只走启发式
   */
  private static void appendUploadReadback(Locator locator, Frame frame, String elementScript, Kv data) {
    try {
      Object raw = locator.first().evaluate("(el) => ({"
          + " filesLength: el && el.files ? el.files.length : null,"
          + " value: el && el.value ? String(el.value).slice(0, 200) : '',"
          + " disabled: el ? !!el.disabled : null,"
          + " accept: el && el.getAttribute ? el.getAttribute('accept') : null })");
      if (raw instanceof Map) {
        data.putAll((Map<?, ?>) raw);
      }
    } catch (PlaywrightException e) {
      data.set("readbackError", briefMessage(e.getMessage()));
    }
    Kv probe = ListenerProbe.probe(locator, frame, elementScript);
    if (!Boolean.TRUE.equals(probe.getBoolean("found"))) {
      data.set("consumed", "unknown");
      return;
    }
    data.set("listeners", Kv.by("vue2", probe.get("vue2")).set("vue3", probe.get("vue3"))
        .set("react", probe.get("react")).set("inline", probe.get("inline"))
        .set("jquery", probe.get("jquery")).set("events", probe.get("listeners")))
        .set("listenerDetection", probe.get("detection"));
    Object has = probe.get("hasListeners");
    if (Boolean.TRUE.equals(has)) {
      data.set("hasListeners", true).set("consumed", "listened");
    } else if (Boolean.FALSE.equals(has)) {
      // 浏览器自己报的监听器清单是空的 —— 这就是「报成功但没生效」的根因,必须明说
      data.set("hasListeners", false).set("consumed", "noListener");
      String note = ListenerProbe.note(probe);
      if (note != null) {
        data.set("hint", note);
      }
    } else {
      // 没探到不等于没有:原生 addEventListener 在元素上不留痕迹,只能报「未知」
      data.set("hasListeners", null).set("consumed", "unknown");
      String note = ListenerProbe.note(probe);
      if (note != null) {
        data.set("hint", note);
      }
    }
  }

  /**
   * 一步到位上传:直接把文件内容(base64 或 URL)交给页面,不必先 POST 到 {@code /playwright/upload}
   *
   * <p>
   * <b>为什么加它</b>:客户端-服务器模式下,原本要「先 POST /playwright/upload 拿服务端路径,再调
   * {@code upload_file}」两次往返;文件小(图样、证件照几十 KB)的时候,直接内联传更省事。
   *
   * <p>
   * 两种来源二选一:
   * <ul>
   * <li>{@code contentBase64}:内容直接内联(base64 文本,允许带 {@code data:image/jpeg;base64,} 前缀);</li>
   * <li>{@code url}:由**服务端**去下载(适合文件已经在某个可访问的地址上,不必先下载到客户端)。</li>
   * </ul>
   *
   * <p>
   * 内容会先按 {@code filename} 落进服务端暂存目录(与 {@code /playwright/upload} 同一个目录、同一套
   * 文件名清洗与大小上限),所以回执里同样有 {@code path}/{@code relativePath},后续还能用
   * {@code upload_file} 复用这份文件。
   *
   * @param filename      文件名(会被清洗);只给 contentType 时会自动补扩展名
   * @param contentType   内容类型,可选(用于补扩展名与回执)
   * @param contentBase64 base64 内容,与 url 二选一
   * @param url           服务端下载地址,与 contentBase64 二选一
   */
  public RespBodyVo uploadFileInline(Long browserId, Integer index, String selector, String filename,
      String contentType, String contentBase64, String url, Integer timeoutMs) {
    return uploadFileInline(browserId, index, selector, filename, contentType, contentBase64, url, timeoutMs, null);
  }

  /** 同上,额外支持 {@code frame} */
  public RespBodyVo uploadFileInline(Long browserId, Integer index, String selector, String filename,
      String contentType, String contentBase64, String url, Integer timeoutMs, String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    if ((contentBase64 == null || contentBase64.isBlank()) && (url == null || url.isBlank())) {
      return RespBodyVo.fail("upload_file 需要 path,或 contentBase64 / url 之一");
    }
    byte[] data;
    String resolvedContentType = contentType;
    try {
      if (contentBase64 != null && !contentBase64.isBlank()) {
        String payload = contentBase64.trim();
        // 允许直接贴 data URI
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
          String header = payload.substring(5, comma);
          int semicolon = header.indexOf(';');
          if (resolvedContentType == null) {
            resolvedContentType = semicolon > 0 ? header.substring(0, semicolon) : header;
          }
          payload = payload.substring(comma + 1);
        }
        data = Base64.getDecoder().decode(payload.replaceAll("\\s+", ""));
      } else {
        data = download(url, resolvedContentType);
        if (resolvedContentType == null) {
          resolvedContentType = contentTypeOf(url);
        }
      }
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("upload_file 失败：contentBase64 不是合法的 base64（" + e.getMessage() + "）");
    } catch (IOException e) {
      return RespBodyVo.fail("upload_file 失败：下载 " + url + " 出错（" + briefMessage(e.getMessage()) + "）");
    }
    Kv saved;
    try {
      saved = UploadStore.save(filename, resolvedContentType, data);
    } catch (IOException e) {
      return RespBodyVo.fail("upload_file 失败：写入服务端暂存目录出错（" + briefMessage(e.getMessage()) + "）");
    }
    RespBodyVo uploaded = uploadFile(browserId, index, selector, saved.getStr("path"), timeoutMs, frame);
    if (!uploaded.isOk()) {
      return uploaded;
    }
    Kv data2 = uploaded.getData() instanceof Kv ? (Kv) uploaded.getData() : new Kv();
    data2.set(saved);
    data2.set("source", contentBase64 != null && !contentBase64.isBlank() ? "contentBase64" : "url");
    uploaded.setData(data2);
    return uploaded;
  }

  /** 服务端下载一个 URL 的内容(有大小上限与超时,避免把内存或磁盘撑爆) */
  private static byte[] download(String url, String contentType) throws IOException {
    long limit = UploadStore.maxBytes();
    java.net.URI uri;
    try {
      uri = java.net.URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new IOException("URL 不合法");
    }
    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15)).followRedirects(
            java.net.http.HttpClient.Redirect.NORMAL)
        .build();
    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
        .timeout(java.time.Duration.ofSeconds(60)).GET().build();
    try {
      java.net.http.HttpResponse<byte[]> response = client.send(request,
          java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 != 2) {
        throw new IOException("HTTP " + response.statusCode());
      }
      byte[] body = response.body();
      if (limit > 0 && body.length > limit) {
        throw new IOException("下载内容超过上限:" + body.length + " 字节 > " + limit + " 字节");
      }
      return body;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("下载被中断");
    }
  }

  /** 从 URL 猜内容类型(猜不到返回 null,交给 UploadStore 处理) */
  private static String contentTypeOf(String url) {
    String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (lower.endsWith(".png")) {
      return "image/png";
    }
    if (lower.endsWith(".webp")) {
      return "image/webp";
    }
    if (lower.endsWith(".pdf")) {
      return "application/pdf";
    }
    return null;
  }

  public RespBodyVo switchTab(Long browserId, int pageIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Page> pages = pagesOf(inst);
    if (pageIndex < 0 || pageIndex >= pages.size()) {
      return RespBodyVo.fail("switch_tab 页签索引越界: " + pageIndex);
    }
    inst.page = pages.get(pageIndex);
    // 有头模式下把页签带到最前,否则人工看到的还是原来那个页签
    activate(inst.page);
    return RespBodyVo.ok();
  }

  public RespBodyVo closeTab(Long browserId, int pageIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Page> pages = pagesOf(inst);
    if (pageIndex < 0 || pageIndex >= pages.size()) {
      return RespBodyVo.fail("close_tab 页签索引越界: " + pageIndex);
    }
    Page toClose = pages.get(pageIndex);
    boolean wasCurrent = inst.page == toClose;
    toClose.close();
    inst.pages.remove(toClose);
    if (wasCurrent) {
      // 关掉的是当前页:换一个还活着的页签,人工看到的窗口也跟着切
      recoverCurrentPage(inst);
    }
    return RespBodyVo.ok();
  }

  /**
   * 把页面带到最前(激活页签)。
   *
   * <p>
   * Playwright 的 switch_tab / new_tab 只改服务端记录的当前页,有头模式下浏览器窗口里
   * 显示的仍是原来那个页签,人工看不到智能体正在操作哪一页。这里统一调用 bringToFront
   * 让窗口跟着切换;无头模式调用无副作用,页面已关闭时忽略异常。
   */
  private void activate(Page page) {
    try {
      page.bringToFront();
    } catch (PlaywrightException e) {
      // 页面已关闭或上下文已销毁,不影响后续动作
    }
  }

  /**
   * 把指定页签(默认当前页签)带到窗口最前,返回 data.pageIndex
   *
   * <p>
   * switch_tab / new_tab 已经会自动带前,这个接口是给"我只是想让人看一眼这一页"用的:
   * 不改当前操作页,只切窗口。有头模式下人工能立刻看到。
   */
  public RespBodyVo bringToFront(Long browserId, Integer pageIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Page> pages = pagesOf(inst);
    if (pageIndex == null) {
      activate(inst.page);
      return RespBodyVo.ok(Kv.by("pageIndex", pages.indexOf(inst.page)).set("url", inst.page.url()));
    }
    if (pageIndex < 0 || pageIndex >= pages.size()) {
      return RespBodyVo.fail("bring_to_front 页签索引越界: " + pageIndex);
    }
    activate(pages.get(pageIndex));
    return RespBodyVo.ok(Kv.by("pageIndex", pageIndex).set("url", pages.get(pageIndex).url()));
  }

  /**
   * 按 URL 匹配切换当前页签,返回 data.pageIndex
   *
   * <p>
   * 页签多了以后 index 不稳定(实测点一次菜单会弹出两个同 URL 的重复页签),按 URL 找更可靠。 url 支持 Playwright
   * 通配写法(`**` 匹配任意字符),也支持子串;匹配到多个时取第一个。
   */
  public RespBodyVo switchTabByUrl(Long browserId, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Page> pages = pagesOf(inst);
    for (int i = 0; i < pages.size(); i++) {
      String current = pages.get(i).url();
      if (urlMatches(current, url)) {
        inst.page = pages.get(i);
        activate(inst.page);
        return RespBodyVo.ok(Kv.by("pageIndex", i).set("url", current).set("title", safeTitle(pages.get(i))));
      }
    }
    return RespBodyVo.fail("switch_tab_by_url 没匹配到页签: " + url + ",当前页签:" + tabs(inst));
  }

  /**
   * 关掉除指定页签之外的全部页签,返回 data.closed 与 data.remaining
   *
   * <p>
   * pageIndex 不传时保留当前页签。关重复页签用这个最省事:一个个 close_tab 会因为 索引整体前移而关错。
   */
  public RespBodyVo closeOtherTabs(Long browserId, Integer pageIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Page> pages = pagesOf(inst);
    Page keep;
    if (pageIndex == null) {
      keep = inst.page;
    } else {
      if (pageIndex < 0 || pageIndex >= pages.size()) {
        return RespBodyVo.fail("close_other_tabs 页签索引越界: " + pageIndex);
      }
      keep = pages.get(pageIndex);
    }
    int closed = 0;
    for (Page page : pages) {
      if (page == keep) {
        continue;
      }
      try {
        page.close();
        closed++;
      } catch (PlaywrightException e) {
        log.debug("关闭页签失败:{}", briefMessage(e.getMessage()));
      }
    }
    inst.page = keep;
    activate(keep);
    List<Page> remaining = pagesOf(inst);
    return RespBodyVo.ok(Kv.by("closed", closed).set("remaining", remaining.size())
        .set("pageIndex", remaining.indexOf(keep)).set("url", keep.url()));
  }

  /**
   * 提取页面可见文本,extractLinks 为 true 时同时返回页面链接
   *
   * <p>
   * 文本取 document.body.innerText,最多 20000 字符。读取前会临时隐藏 get_browser_state 画的
   * 高亮层,避免高亮序号混进正文。
   */
  public RespBodyVo extractStructuredData(Long browserId, String query, boolean extractLinks) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      Object text = inst.page
          .evaluate("() => {" + " const c = document.getElementById('playwright-highlight-container');"
              + " const prev = c ? c.style.display : null;" + " if (c) c.style.display = 'none';"
              + " const t = document.body ? document.body.innerText.slice(0, 20000) : '';"
              + " if (c) c.style.display = prev || '';" + " return t; }");
      Kv kv = Kv.by("query", query).set("text", text);
      if (extractLinks) {
        Object links = inst.page.evaluate("() => Array.from(document.querySelectorAll('a[href]'))"
            + ".map(a => ({text: (a.innerText || '').trim().slice(0, 80), href: a.href}))");
        kv.set("links", links);
      }
      return RespBodyVo.ok(kv);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("extract_structured_data 失败：" + briefMessage(e.getMessage()));
    }
  }

  public RespBodyVo scroll(Long browserId, boolean down, int numPages, Integer index) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    for (int i = 0; i < numPages; i++) {
      if (index == null) {
        inst.page.keyboard().press(down ? "PageDown" : "PageUp");
      } else {
        Locator locator = resolveIndex(inst, index, "*");
        if (locator == null) {
          return RespBodyVo.fail("scroll 索引越界: " + index + indexHint(inst));
        }
        locator.evaluate("(el, down) => el.scrollBy(0, down ? window.innerHeight : -window.innerHeight)", down);
      }
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo sendKeys(Long browserId, String keys) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.keyboard().press(keys);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("send_keys 失败：" + briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo scrollToText(Long browserId, String text) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.locator("text=" + text).first().scrollIntoViewIfNeeded();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("scroll_to_text 失败：" + briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo getDropdownOptions(Long browserId, int index) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    Locator locator = resolveIndex(inst, index, SELECT_SELECTOR);
    if (locator == null) {
      return RespBodyVo.fail("get_dropdown_options 索引越界: " + index + indexHint(inst));
    }
    try {
      Object options = locator.evaluate("el => Array.from(el.options).map(o => o.textContent)");
      return RespBodyVo.ok(Kv.by("options", options));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("get_dropdown_options", e));
    }
  }

  public RespBodyVo selectDropdownOption(Long browserId, int index, String text) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    Locator locator = resolveIndex(inst, index, SELECT_SELECTOR);
    if (locator == null) {
      return RespBodyVo.fail("select_dropdown_option 索引越界: " + index + indexHint(inst));
    }
    try {
      locator.selectOption(new SelectOption().setLabel(text),
          new Locator.SelectOptionOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("select_dropdown_option", e));
    }
    return RespBodyVo.ok();
  }

  /**
   * 在浏览器当前页面执行 JavaScript
   *
   * <p>
   * body 支持三种写法:
   * <ul>
   * <li>表达式:document.title</li>
   * <li>函数:() =&gt; document.title 或 () =&gt; { ...; return value; }</li>
   * <li>语句片段:const el = document.querySelector('#kw'); return el.value;</li>
   * </ul>
   * Playwright 会对脚本求值,结果为函数时自动调用,返回值必须是 JSON 可序列化的值, DOM 元素等对象不会报错但只会返回 ref:
   * &lt;Node&gt; 这样的引用,请先转换为 outerHTML、textContent 等基本类型。
   *
   * @param browserId 浏览器实例 ID
   * @param body      需要执行的 JavaScript
   * @return 执行结果,数据位于 data.result
   */
  public RespBodyVo executeJs(Long browserId, String body) {
    return executeJs(browserId, body, null, null);
  }

  /**
   * 执行 JavaScript(bodyFile / vars 见下)
   *
   * <p>
   * 除了 {@code body} 直接写脚本,还可以用 {@code bodyFile} 从**服务端脚本目录**读脚本,再用
   * {@code vars} 注入变量——脚本里写 {@code {{name}}},服务端会替换成 JSON 编码后的值(字符串自动
   * 带引号)。这样中文、引号、换行都不用在客户端拼字符串,客户端-服务器模式下尤其省事
   * (以前为了让 PowerShell 把中文和引号原样送过来,只能把脚本一条条写成文件)。
   *
   * <p>
   * 失败时除了 {@code msg}(第一行),还会在 {@code data.error} 里给出 {@code name/message/stack},
   * 其中 stack 只保留页面自己的帧(去掉 Playwright 内部与本地路径),定位到脚本哪一行出的错。
   *
   * @param body     直接给的脚本,与 bodyFile 二选一(body 优先)
   * @param bodyFile 脚本文件路径,相对「脚本目录」(默认 {@code <工作目录>/scripts/js},可用配置项
   *                 {@code browser.js.dir} 改);不许跳出该目录
   * @param vars     注入脚本的变量,脚本里用 {@code {{key}}} 引用
   */
  public RespBodyVo executeJs(Long browserId, String body, String bodyFile, JSONObject vars) {
    return executeJs(browserId, body, bodyFile, vars, null);
  }

  /**
   * 同上,额外支持在指定的 frame 里执行
   *
   * <p>
   * <b>为什么要在 frame 里执行</b>:跨域 iframe 里 {@code page.evaluate} 是够不着的 —— 它在顶层文档执行,
   * {@code document.querySelector} 穿不透 iframe。而「找到主站发 token 的接口、用同源 XHR 现取一个未被
   * 消费的 token,再把它当顶层页面打开」这类绕过手法,恰恰必须在**那个 frame 的文档里**发请求。传
   * {@code frame} 即可(序号见 {@code list_frames},或写 URL/name 子串)。
   *
   * <p>
   * <b>会不会 await Promise</b>:会。Playwright 的求值语义是「返回值是 Promise 就等它 settle,是函数就先调用
   * 再等」,所以 {@code async () =&gt; { const r = await fetch(...); return r.json(); }} 能直接拿到结果,
   * 不需要用已废弃的同步 XHR 绕。回执里的 {@code data.awaited} 会说明这次是不是等了 Promise。
   *
   * @param frame frame 序号或 URL/name 子串;为空表示主 frame
   */
  public RespBodyVo executeJs(Long browserId, String body, String bodyFile, JSONObject vars, String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    Frame target;
    try {
      target = frameOf(inst, frame);
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("execute_js 失败：" + e.getMessage());
    }
    String raw = body;
    if ((raw == null || raw.isBlank()) && bodyFile != null && !bodyFile.isBlank()) {
      String fromFile = readScriptFile(bodyFile);
      if (fromFile == null) {
        return RespBodyVo.fail("execute_js 失败：读不到脚本文件 " + bodyFile
            + "（只允许读脚本目录下的文件,见 get_config 的 jsDir）");
      }
      raw = fromFile;
    }
    if (raw == null || raw.isBlank()) {
      return RespBodyVo.fail("execute_js 需要 body 或 bodyFile");
    }
    String script = normalizeScript(applyVars(raw, vars));
    try {
      Object result = target.evaluate(script);
      Kv data = Kv.by("result", result);
      if (vars != null && !vars.isEmpty()) {
        data.set("varsApplied", new ArrayList<>(vars.keySet()));
      }
      if (frame != null && !frame.isBlank()) {
        data.set("frame", frame).set("frameUrl", safeFrameUrl(target));
      }
      // 「到底等没等 Promise」以前只能靠猜,于是有人退回同步 XHR 来规避。这里如实回报
      data.set("awaited", true);
      return RespBodyVo.ok(data);
    } catch (PlaywrightException e) {
      String message = briefMessage(e.getMessage());
      log.error("execute_js 执行失败,id:{},script:{},error:{}", browserId, script, message, e);
      RespBodyVo failure = RespBodyVo.fail("执行 JavaScript 失败：" + message);
      failure.setData(Kv.by("error", scriptError(e)).set("scriptPreview", truncate(script, 400)));
      return failure;
    }
  }

  /**
   * 从 Playwright 的报错里拆出脚本自己的错误信息
   *
   * <p>
   * Playwright 的异常文本里带着页面侧的 JS 栈,但混着它自己的帧和本机路径。这里只留
   * {@code name/message} 与页面帧(形如 {@code at ... (https://站点/...js:1:234)})。
   */
  private static Kv scriptError(PlaywrightException e) {
    String raw = e.getMessage() == null ? "" : e.getMessage();
    String name = null;
    String message = null;
    java.util.regex.Matcher head = Pattern.compile("([A-Za-z]*Error):\\s*([^\\n]*)").matcher(raw);
    if (head.find()) {
      name = head.group(1);
      message = head.group(2).trim();
    }
    List<String> frames = new ArrayList<>();
    java.util.regex.Matcher frame = Pattern.compile("at [^\\n]*").matcher(raw);
    while (frame.find() && frames.size() < 12) {
      String line = frame.group().trim();
      // 只保留页面自己的帧:带 URL 的;Playwright 内部帧(本地路径)丢掉
      if (line.contains("://")) {
        frames.add(truncate(line, 240));
      }
    }
    Kv error = Kv.by("name", name).set("message", message == null ? briefMessage(raw) : message);
    if (!frames.isEmpty()) {
      error.set("stack", frames);
    }
    return error;
  }

  /** 脚本目录:默认 <工作目录>/scripts/js,可用 browser.js.dir 覆盖 */
  private static Path scriptDir() {
    String configured = ChromeBrowser.config(KEY_JS_DIR);
    Path dir = configured == null || configured.isBlank() ? Paths.get("scripts", "js") : Paths.get(configured.trim());
    return dir.toAbsolutePath().normalize();
  }

  /** 读脚本文件;只允许读脚本目录下的文件(防越权读任意文件) */
  private static String readScriptFile(String bodyFile) {
    try {
      Path base = scriptDir();
      Path target = base.resolve(bodyFile).normalize();
      if (!target.startsWith(base) || !Files.isRegularFile(target)) {
        return null;
      }
      return Files.readString(target, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * 把 {@code {{key}}} 替换成 JSON 编码后的变量值
   *
   * <p>
   * 用 JSON 编码(而不是裸拼)是有意的:字符串会自带引号并正确转义,中文/引号/换行都不用调用方操心;
   * 数字、布尔、数组、对象也都能直接塞进脚本。
   *
   * <p>
   * 两种写法:裸 {@code {{key}}}(如 {@code var n = {{n}};})与连引号的 {@code "{{key}}"}
   * (如 {@code querySelector("{{sel}}")})。**连引号的那种要先替换**,否则会留下 {@code ""值""} 这种坏脚本。
   */
  static String applyVars(String body, JSONObject vars) {
    if (body == null || vars == null || vars.isEmpty() || body.indexOf("{{") < 0) {
      return body;
    }
    String result = body;
    for (String key : vars.keySet()) {
      Object value = vars.get(key);
      String encoded = value == null ? "null" : JSON.toJSONString(value);
      result = result.replace("\"{{" + key + "}}\"", encoded).replace("{{" + key + "}}", encoded);
    }
    return result;
  }

  /**
   * 语句片段(包含 return 的代码)包装成箭头函数,其它情况保持原样交给 Playwright 求值
   */
  static String normalizeScript(String body) {
    String script = body.trim();
    if (FUNCTION_LIKE.matcher(script).find() || !RETURN_STATEMENT.matcher(script).find()) {
      return script;
    }
    return "() => {" + script + "}";
  }

  /**
   * Playwright 抛出的脚本异常带有完整调用栈和本地路径,这里只保留 message 的第一行返回给客户端
   */
  public static String briefMessage(String message) {
    if (message == null) {
      return "未知错误";
    }
    String brief = message;
    int start = brief.indexOf("message='");
    if (start >= 0) {
      start += "message='".length();
      int end = brief.indexOf('\n', start);
      brief = end > start ? brief.substring(start, end) : brief.substring(start);
    } else {
      int end = brief.indexOf('\n');
      if (end > 0) {
        brief = brief.substring(0, end);
      }
    }
    brief = brief.trim();
    // fastjson 的异常信息结尾会带上库版本号,对外没必要暴露
    int versionAt = brief.indexOf("fastjson-version");
    if (versionAt > 0) {
      brief = brief.substring(0, versionAt).trim();
      brief = brief.endsWith(",") ? brief.substring(0, brief.length() - 1).trim() : brief;
    }
    return brief.isEmpty() ? "未知错误" : brief;
  }

  // ==================== 导航 ====================

  public RespBodyVo goForward(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    String before = inst.page.url();
    Response rsp;
    try {
      rsp = inst.page.goForward();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("go_forward 失败：" + briefMessage(e.getMessage()));
    }
    String after = inst.page.url();
    if (after.equals(before)) {
      return RespBodyVo.fail("无法前进：没有可用历史记录");
    }
    return RespBodyVo.ok(Kv.by("status", rsp == null ? 0 : rsp.status()).set("url", after));
  }

  public RespBodyVo reload(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Response rsp;
    try {
      rsp = inst.page.reload();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("reload 失败：" + briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok(Kv.by("status", rsp == null ? 0 : rsp.status()));
  }

  public RespBodyVo getUrl(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return RespBodyVo.ok(Kv.by("url", inst.page.url()));
  }

  public RespBodyVo getTitle(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return RespBodyVo.ok(Kv.by("title", safeTitle(inst.page)));
  }

  // ==================== 按索引操作元素 ====================

  public RespBodyVo doubleClickElementByIndex(Long browserId, int index) {
    return doubleClickElementByIndex(browserId, index, null);
  }

  public RespBodyVo doubleClickElementByIndex(Long browserId, int index, String mode) {
    return clickLike(browserId, index, "double_click_element_by_index",
        (locator) -> locator.dblclick(new Locator.DblclickOptions().setTimeout(actionTimeoutMs())), true, mode);
  }

  public RespBodyVo hoverElementByIndex(Long browserId, int index) {
    return clickLike(browserId, index, "hover_element_by_index",
        (locator) -> locator.hover(new Locator.HoverOptions().setTimeout(actionTimeoutMs())));
  }

  public RespBodyVo focusElementByIndex(Long browserId, int index) {
    return clickLike(browserId, index, "focus_element_by_index",
        (locator) -> locator.focus(new Locator.FocusOptions().setTimeout(actionTimeoutMs())));
  }

  public RespBodyVo checkElementByIndex(Long browserId, int index) {
    return checkElementByIndex(browserId, index, null);
  }

  /**
   * 勾选复选框
   *
   * <p>
   * {@code mode=auto} 时原生 {@code check()} 超时会自动降级为 JS 派发事件(ant-design 的表格行勾选
   * 就属于这类:点上去有反应,可操作性检查却一直不满足)。
   */
  public RespBodyVo checkElementByIndex(Long browserId, int index, String mode) {
    return clickLike(browserId, index, "check_element_by_index",
        (locator) -> locator.check(new Locator.CheckOptions().setTimeout(actionTimeoutMs())), false, mode);
  }

  public RespBodyVo uncheckElementByIndex(Long browserId, int index) {
    return uncheckElementByIndex(browserId, index, null);
  }

  public RespBodyVo uncheckElementByIndex(Long browserId, int index, String mode) {
    return clickLike(browserId, index, "uncheck_element_by_index",
        (locator) -> locator.uncheck(new Locator.UncheckOptions().setTimeout(actionTimeoutMs())), false, mode);
  }

  /** 不清空原内容,逐字输入 */
  public RespBodyVo typeText(Long browserId, int index, String text) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator locator = locatorOf(inst, index);
    if (locator == null) {
      return RespBodyVo.fail("type_text 索引越界: " + index + indexHint(inst));
    }
    try {
      requireEditable(locator);
      locator.pressSequentially(text, new Locator.PressSequentiallyOptions().setTimeout(actionTimeoutMs()));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("type_text", e));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo dragElementByIndex(Long browserId, int index, int targetIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator from = locatorOf(inst, index);
    if (from == null) {
      return RespBodyVo.fail("drag_element_by_index 源索引越界: " + index + indexHint(inst));
    }
    Locator to = locatorOf(inst, targetIndex);
    if (to == null) {
      return RespBodyVo.fail("drag_element_by_index 目标索引越界: " + targetIndex + indexHint(inst));
    }
    try {
      from.dragTo(to, new Locator.DragToOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("drag_element_by_index", e));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo keyDown(Long browserId, String keys) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.keyboard().down(keys);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("key_down 失败：" + briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo keyUp(Long browserId, String keys) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.keyboard().up(keys);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("key_up 失败：" + briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok();
  }

  // ==================== 读取元素信息 ====================

  public RespBodyVo getElementText(Long browserId, int index) {
    return read(browserId, index, "get_element_text", "text", (locator) -> locator.innerText());
  }

  public RespBodyVo getElementHtml(Long browserId, int index) {
    return read(browserId, index, "get_element_html", "html", (locator) -> locator.innerHTML());
  }

  public RespBodyVo getElementValue(Long browserId, int index) {
    return read(browserId, index, "get_element_value", "value", (locator) -> locator.inputValue());
  }

  public RespBodyVo getElementAttribute(Long browserId, int index, String name) {
    return read(browserId, index, "get_element_attribute", "value", (locator) -> locator.getAttribute(name));
  }

  /**
   * 看一个元素到底挂了哪些事件监听器
   *
   * <p>
   * 「这个元素有没有挂事件」是 SPA 自动化的**基础诊断信息**,以前只能靠手写 {@code execute_js} 去摸
   * ({@code el._vei} 之类)。它直接回答的是最贵的那个坑:{@code upload_file} / {@code input_text} 报了成功、
   * 页面却毫无反应 —— 根因往往不是选择器错了,而是**这个 input 根本没人监听**,JS 派发的事件到不了框架的
   * handler。
   *
   * <p>
   * 判定依据与三态语义见 {@link ListenerProbe#probe}。返回 {@code data.found} 为 false 时说明定位没命中,
   * 其余字段才有意义。
   *
   * @param index    元素索引(来自 {@code get_browser_state});与 {@code selector} 二选一
   * @param selector CSS 选择器;与 {@code index} 二选一
   * @param frame    {@code selector} 在跨域 iframe 里时传(序号见 {@code list_frames},或 URL/name 子串)
   */
  public RespBodyVo getElementListeners(Long browserId, Integer index, String selector, String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if ((selector == null || selector.isBlank()) && index == null) {
      return RespBodyVo.fail("get_element_listeners 需要 index 或 selector 之一");
    }
    Locator locator;
    String target;
    if (selector != null && !selector.isBlank()) {
      try {
        locator = locatorIn(inst, frame, selector);
      } catch (IllegalArgumentException e) {
        return RespBodyVo.fail("get_element_listeners 失败：" + e.getMessage());
      }
      target = "selector=" + selector + frameSuffix(frame);
    } else {
      locator = locatorOf(inst, index);
      target = "index=" + index;
      if (locator == null) {
        return RespBodyVo.fail("get_element_listeners 索引越界: " + index + indexHint(inst));
      }
    }
    Kv probe;
    Frame probeFrame = null;
    String elementScript;
    try {
      if (locator.count() == 0) {
        return RespBodyVo.ok(Kv.by("found", false).set("target", target)
            .set("note", "没找到这个元素:选择器/索引可能已经过期,重新 get_browser_state 再试"));
      }
      // CDP 的权威探测需要 frame 与本元素的解析表达式;拿不到就退回启发式,并把结论标成「未知」
      if (selector != null && !selector.isBlank()) {
        probeFrame = frame == null || frame.isBlank() ? inst.page.mainFrame() : frameOf(inst, frame);
      } else {
        probeFrame = frameForIndex(inst, inst.domState, index);
        if (probeFrame == null) {
          probeFrame = inst.page.mainFrame();
        }
      }
      elementScript = elementResolveScript(inst, index, selector);
      probe = ListenerProbe.probe(locator, probeFrame, elementScript);
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("get_element_listeners 失败：" + e.getMessage());
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("get_element_listeners 失败：" + briefMessage(e.getMessage()));
    }
    if (!Boolean.TRUE.equals(probe.getBoolean("found"))) {
      return RespBodyVo.ok(Kv.by("found", false).set("target", target)
          .set("note", "没找到这个元素:选择器/索引可能已经过期,重新 get_browser_state 再试"));
    }
    Kv data = Kv.by("found", true).set("target", target).set(probe);
    String note = ListenerProbe.note(probe);
    if (note != null) {
      data.set("note", note);
    }
    return RespBodyVo.ok(data);
  }

  public RespBodyVo getElementCount(Long browserId, String selector) {
    return getElementCount(browserId, selector, null);
  }

  /** 同上,{@code frame} 非空时只数指定 frame 里的命中(跨域 iframe 里的元素在主文档里数不到) */
  public RespBodyVo getElementCount(Long browserId, String selector, String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      return RespBodyVo.ok(Kv.by("count", frameOf(inst, frame).locator(selector).count()));
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("get_element_count 失败：" + e.getMessage());
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("get_element_count 失败：" + briefMessage(e.getMessage()));
    }
  }

  public RespBodyVo getElementBox(Long browserId, int index) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator locator = locatorOf(inst, index);
    if (locator == null) {
      return RespBodyVo.fail("get_element_box 索引越界: " + index + indexHint(inst));
    }
    try {
      BoundingBox box = locator.boundingBox();
      if (box == null) {
        return RespBodyVo.fail("get_element_box 失败：元素不可见或没有布局信息");
      }
      return RespBodyVo.ok(Kv.by("x", box.x).set("y", box.y).set("width", box.width).set("height", box.height));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("get_element_box", e));
    }
  }

  // ==================== 元素状态 ====================

  public RespBodyVo isVisible(Long browserId, int index) {
    return read(browserId, index, "is_visible", "visible", (locator) -> locator.isVisible());
  }

  public RespBodyVo isEnabled(Long browserId, int index) {
    return read(browserId, index, "is_enabled", "enabled", (locator) -> locator.isEnabled());
  }

  public RespBodyVo isChecked(Long browserId, int index) {
    return read(browserId, index, "is_checked", "checked", (locator) -> locator.isChecked());
  }

  // ==================== 按选择器/文本/语义定位 ====================

  /**
   * 按选择器点击
   *
   * <p>
   * 点完如果弹出了新页签就自动切过去(并带到最前),否则留在原页签。返回点击回执。
   *
   * <p>
   * 注意参数类型必须是 Long:控制器传的就是 Long,如果这里再放一个 long 重载,重载解析会
   * 选中参数类型完全匹配的那个,另一个就成了永远不生效的死代码(改接口行为时尤其容易踩)。
   */
  public RespBodyVo clickElementBySelector(Long browserId, String selector) {
    return clickElementBySelector(browserId, selector, null, null);
  }

  /**
   * 按选择器点击
   *
   * @param mode      {@code auto}(默认:原生失败自动降级为 JS 派发)/{@code native}/{@code js}
   * @param timeoutMs 按次覆盖超时(毫秒)
   */
  public RespBodyVo clickElementBySelector(Long browserId, String selector, String mode, Integer timeoutMs) {
    return clickElementBySelector(browserId, selector, mode, timeoutMs, null);
  }

  /**
   * 按选择器点击
   *
   * @param mode      {@code auto}(默认:原生失败自动降级为 JS 派发)/{@code native}/{@code js}
   * @param timeoutMs 按次覆盖超时(毫秒)
   * @param frame     frame 序号(见 {@code list_frames},0 是主 frame)或 URL/name 子串;不传就是主 frame。
   *                  目标在跨域 iframe 里时必传 —— {@code page.locator} 够不着 iframe 内部
   */
  public RespBodyVo clickElementBySelector(Long browserId, String selector, String mode, Integer timeoutMs,
      String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    int tabCountBefore = pagesOf(inst).size();
    Locator target;
    Frame probeFrame;
    try {
      probeFrame = frame == null || frame.isBlank() ? null : frameOf(inst, frame);
      target = locatorIn(inst, frame, selector);
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("click_element_by_selector 失败：" + e.getMessage());
    }
    Kv before = stateProbe(inst, probeFrame);
    ActionOutcome outcome = new ActionOutcome();
    try {
      Locator locator = target;
      clickWithMode(locator, mode, outcome, () -> locator.click(clickOptions(timeoutMs)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(
          locateFailure("click_element_by_selector", "选择器 " + selector + frameSuffix(frame), e)
              + blockerSuffix(target));
    }
    adoptNewTab(inst, tabCountBefore);
    return okWithReceipt(before, inst, "click_element_by_selector", outcome.extra, probeFrame);
  }

  public RespBodyVo inputTextBySelector(Long browserId, String selector, String value) {
    return inputTextBySelector(browserId, selector, value, null);
  }

  public RespBodyVo inputTextBySelector(Long browserId, String selector, String value, String mode) {
    return inputTextBySelector(browserId, selector, value, mode, null);
  }

  /** 同上,{@code frame} 非空时把选择器限定在指定 frame 里(见 {@code click_element_by_selector} 的说明) */
  public RespBodyVo inputTextBySelector(Long browserId, String selector, String value, String mode, String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    ActionOutcome outcome = new ActionOutcome();
    Locator target;
    try {
      target = locatorIn(inst, frame, selector);
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("input_text_by_selector 失败：" + e.getMessage());
    }
    try {
      fillWithMode(target, value, mode, outcome);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("input_text_by_selector", "选择器 " + selector + frameSuffix(frame), e));
    }
    return inputResult(outcome);
  }

  /** 报错信息里带上 frame,免得「选择器明明对」却查不出是找错了文档 */
  private static String frameSuffix(String frame) {
    return frame == null || frame.isBlank() ? "" : "（frame=" + frame + "）";
  }

  /**
   * 按可见文本点击
   *
   * <p>
   * 直接点 `getByText` 命中的那个节点是靠不住的:实测政务站点的悬浮菜单里,文本命中的是 `<div class="scrollList">`
   * 这类纯文本容器,接口返回成功但什么也没发生。所以这里先向上找
   * **最近的可点击祖先**(`a`/`button`/`[role=button]`/`[onclick]`),找到就点它,找不到才退回
   * 点文本节点本身;返回的 data 里带上真正点中的 `tag` 与 `outerHtml`,假成功一眼可见。
   */
  public RespBodyVo clickElementByText(Long browserId, String text) {
    return clickElementByText(browserId, text, null);
  }

  public RespBodyVo clickElementByText(Long browserId, String text, String mode) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv before = stateProbe(inst);
    TextHit hit = resolveByText(inst, text);
    if (hit == null) {
      return RespBodyVo.fail("click_element_by_text 失败：没找到文本为「" + text + "」的元素");
    }
    try {
      Kv info = describe(hit.locator, hit.marker);
      ActionOutcome outcome = new ActionOutcome();
      try {
        clickWithMode(hit.locator, mode, outcome, () -> hit.locator.click(clickOptions()));
      } catch (PlaywrightException e) {
        return RespBodyVo.fail(locateFailure("click_element_by_text", "文本 " + text, e));
      }
      info.set(outcome.extra);
      return okWithHit(before, inst, "click_element_by_text", info);
    } finally {
      // 定位用的临时属性必须等动作做完再摘:定位器是惰性求值的,提前摘掉就选不中元素了
      hit.cleanup();
    }
  }

  /** 文本命中的元素与它的临时标记属性 */
  private static final class TextHit {
    final Locator locator;
    final String marker;

    TextHit(Locator locator, String marker) {
      this.locator = locator;
      this.marker = marker;
    }

    void cleanup() {
      if (marker == null) {
        return;
      }
      try {
        locator.evaluate("(e, m) => e.removeAttribute(m)", marker);
      } catch (PlaywrightException e) {
        log.debug("清理临时标记失败:{}", briefMessage(e.getMessage()));
      }
    }
  }

  /**
   * 文本定位:优先返回"最近的可点击祖先"
   *
   * <p>
   * 给命中的元素打一个临时属性,再用属性选择器取回,这样拿到的定位器只指向这一个元素, 不受祖先/兄弟节点同名文本的影响。属性由调用方在动作完成后清理(见
   * {@link TextHit#cleanup()})。
   */
  private TextHit resolveByText(BrowserInstance inst, String text) {
    Locator candidates = inst.page.getByText(text);
    int count;
    try {
      count = candidates.count();
    } catch (PlaywrightException e) {
      return null;
    }
    if (count == 0) {
      return null;
    }
    String marker = "data-br-hit-" + System.nanoTime();
    for (int i = 0; i < count; i++) {
      Locator candidate = candidates.nth(i);
      try {
        candidate.evaluate("(e, m) => { const c = e.closest('a,button,[role=button],input[type=button],"
            + "input[type=submit],[onclick]') || e; c.setAttribute(m, '1'); return c.tagName; }", marker);
        return new TextHit(inst.page.locator("[" + marker + "='1']").first(), marker);
      } catch (PlaywrightException e) {
        log.debug("文本定位候选不可用:{}", briefMessage(e.getMessage()));
      }
    }
    return new TextHit(candidates.first(), null);
  }

  /** 命中元素的可读描述:tag、文本、outerHtml 片段,用来判断到底点中了什么 */
  private static Kv describe(Locator locator) {
    return describe(locator, null);
  }

  /** marker 非空时从 outerHtml 里摘掉定位用的临时属性,免得把实现细节报给调用方 */
  private static Kv describe(Locator locator, String marker) {
    Kv info = Kv.by("tag", null).set("text", null).set("outerHtml", null);
    try {
      info.set("tag", locator.evaluate("e => e.tagName"));
      info.set("text", truncate(String.valueOf(locator.evaluate("e => (e.innerText || '').trim()")), 200));
      String html = String.valueOf(locator.evaluate("e => e.outerHTML"));
      if (marker != null) {
        html = html.replaceAll("\\s*" + Pattern.quote(marker) + "=\"1\"", "");
      }
      info.set("outerHtml", truncate(html, 300));
    } catch (PlaywrightException e) {
      log.debug("读取命中元素信息失败:{}", briefMessage(e.getMessage()));
    }
    return info;
  }

  /** 带回执与命中信息的成功响应 */
  private static RespBodyVo okWithHit(Kv before, BrowserInstance inst, String action, Kv hit) {
    Kv report = receipt(before, inst);
    report.set(hit);
    if (!Boolean.TRUE.equals(report.getBoolean("changed"))) {
      report.set("hint", action + " 已执行，但观察窗口内尚未发现变化；不代表点击失败，请等待目标条件或读取新状态");
    }
    return RespBodyVo.ok(report);
  }

  public RespBodyVo clickElementByRole(Long browserId, String role, String name) {
    return clickElementByRole(browserId, role, name, null);
  }

  public RespBodyVo clickElementByRole(Long browserId, String role, String name, String mode) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv before = stateProbe(inst);
    Locator locator = inst.page
        .getByRole(AriaRole.valueOf(role.toUpperCase()), new Page.GetByRoleOptions().setName(name)).first();
    Kv info = describe(locator);
    ActionOutcome outcome = new ActionOutcome();
    try {
      clickWithMode(locator, mode, outcome, () -> locator.click(clickOptions()));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("click_element_by_role", "角色 " + role + "[name=" + name + "]", e));
    }
    info.set(outcome.extra);
    return okWithHit(before, inst, "click_element_by_role", info);
  }

  public RespBodyVo inputTextByLabel(Long browserId, String label, String value) {
    return inputTextByLabel(browserId, label, value, null);
  }

  public RespBodyVo inputTextByLabel(Long browserId, String label, String value, String mode) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    ActionOutcome outcome = new ActionOutcome();
    try {
      fillWithMode(inst.page.getByLabel(label).first(), value, mode, outcome);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("input_text_by_label", "标签 " + label, e));
    }
    return inputResult(outcome);
  }

  /**
   * 悬停后立刻点击同一个元素
   *
   * <p>
   * 悬浮菜单必须 hover 才渲染,而 hover_element_by_index → click_element_by_index 两步走,
   * 中间隔着一次推理往返,菜单早收起来了(实测政务站点的「我要查询」下拉菜单)。这里把两步合成 一次调用,中间只留一个可调的 hoverDelayMs(默认
   * 300 毫秒)。
   */
  public RespBodyVo hoverAndClick(Long browserId, Integer index, String selector, Integer hoverDelayMs) {
    return hoverAndClick(browserId, index, selector, hoverDelayMs, null);
  }

  public RespBodyVo hoverAndClick(Long browserId, Integer index, String selector, Integer hoverDelayMs, String mode) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator locator;
    String target;
    if (index != null) {
      locator = locatorOf(inst, index);
      target = "index=" + index;
      if (locator == null) {
        return RespBodyVo.fail("hover_and_click 索引越界: " + index + indexHint(inst));
      }
    } else if (selector != null && !selector.isEmpty()) {
      locator = inst.page.locator(selector).first();
      target = "选择器 " + selector;
    } else {
      return RespBodyVo.fail("hover_and_click 需要 index 或 selector");
    }
    Kv before = stateProbe(inst);
    Kv info = describe(locator);
    ActionOutcome outcome = new ActionOutcome();
    try {
      locator.hover(new Locator.HoverOptions().setTimeout(actionTimeoutMs()));
      sleepQuietly(hoverDelayMs == null ? 300 : hoverDelayMs);
      clickWithMode(locator, mode, outcome, () -> locator.click(clickOptions()));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("hover_and_click", target, e));
    }
    info.set(outcome.extra);
    return okWithHit(before, inst, "hover_and_click", info);
  }

  /**
   * 清空输入框,返回 data.value(清空后的值)
   *
   * <p>
   * `input_text` 的 text 是必填参数,传空串会被参数校验挡掉(HTTP 500「缺少参数 text」),
   * 所以清空要单独走这个接口。index 与 selector 传一个即可。
   */
  public RespBodyVo clearText(Long browserId, Integer index, String selector) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator locator;
    String target;
    if (index != null) {
      locator = locatorOf(inst, index);
      target = "index=" + index;
      if (locator == null) {
        return RespBodyVo.fail("clear_text 索引越界: " + index + indexHint(inst));
      }
    } else if (selector != null && !selector.isEmpty()) {
      locator = inst.page.locator(selector).first();
      target = "选择器 " + selector;
    } else {
      return RespBodyVo.fail("clear_text 需要 index 或 selector");
    }
    Kv data = Kv.by("value", null);
    try {
      fillEditable(locator, "");
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("clear_text", target, e));
    }
    try {
      data.set("value", locator.inputValue());
    } catch (PlaywrightException e) {
      log.debug("clear_text 读回值失败:{}", briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok(data);
  }

  // ==================== 标签页 ====================

  public RespBodyVo getTabs(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return RespBodyVo.ok(Kv.by("tabs", tabs(inst)));
  }

  public RespBodyVo newTab(Long browserId, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Page page = inst.context.newPage();
    claimPage(inst, page);
    if (url != null && !url.isEmpty()) {
      page.navigate(url);
    }
    inst.page = page;
    // 新建页签后同样带到最前,便于人工观察
    activate(page);
    List<Page> pages = pagesOf(inst);
    return RespBodyVo.ok(Kv.by("pageIndex", pages.indexOf(page)).set("url", page.url()));
  }

  // ==================== 等待 ====================

  public RespBodyVo waitForElement(Long browserId, String selector, Double timeoutSeconds) {
    return waitForElement(browserId, selector, timeoutSeconds, null);
  }

  /** 同上,{@code frame} 非空时只等指定 frame 里的元素 */
  public RespBodyVo waitForElement(Long browserId, String selector, Double timeoutSeconds, String frame) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator target;
    try {
      target = locatorIn(inst, frame, selector);
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("wait_for_element 失败：" + e.getMessage());
    }
    try {
      target.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis(timeoutSeconds)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(waitFailure("wait_for_element", e));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo waitForText(Long browserId, String text, Double timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.getByText(text).first().waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis(timeoutSeconds)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(waitFailure("wait_for_text", e));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo waitForUrl(Long browserId, String url, Double timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.waitForURL(url, new Page.WaitForURLOptions().setTimeout(timeoutMillis(timeoutSeconds)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(waitFailure("wait_for_url", e));
    }
    return RespBodyVo.ok(Kv.by("url", inst.page.url()));
  }

  public RespBodyVo waitForLoad(Long browserId, String state, Double timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    LoadState loadState = "networkidle".equalsIgnoreCase(state) ? LoadState.NETWORKIDLE
        : "domcontentloaded".equalsIgnoreCase(state) ? LoadState.DOMCONTENTLOADED : LoadState.LOAD;
    try {
      inst.page.waitForLoadState(loadState,
          new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis(timeoutSeconds)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(waitFailure("wait_for_load", e));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo waitForFunction(Long browserId, String expression, Double timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.waitForFunction(expression, null,
          new Page.WaitForFunctionOptions().setTimeout(timeoutMillis(timeoutSeconds)));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(waitFailure("wait_for_function", e));
    }
    return RespBodyVo.ok();
  }

  /** 页面「安静下来」的判据:DOM 变更计数与在途请求数都不再变化 */
  private static final String MUTATION_PROBE = """
      () => {
        if (!window.__brIdle) {
          window.__brIdle = {mutations: 0, since: Date.now()};
          try {
            new MutationObserver((records) => {
              window.__brIdle.mutations += records.length;
              window.__brIdle.since = Date.now();
            }).observe(document.documentElement || document, {
              childList: true, subtree: true, attributes: true, characterData: true
            });
          } catch (e) {
            window.__brIdle.error = String(e);
          }
        }
        return {mutations: window.__brIdle.mutations, since: window.__brIdle.since,
                error: window.__brIdle.error || null};
      }
      """;

  /**
   * 等页面安静下来:DOM 不再变动 + 没有在途请求
   *
   * <p>
   * <b>为什么需要它</b>:分步表单/SPA 上「点一下 → 等它自己保存/渲染完」是最常见的节奏,而固定
   * {@code wait} 要么等不够(点击被吞、读到旧状态)要么等太久(每次白等几秒,几十步下来就是几分钟)。
   * 这里给一个与站点无关的判据:连续 {@code quietMs} 毫秒内既没有 DOM 变更、也没有在途请求,就算安静。
   *
   * <p>
   * 与 {@code wait_for_function} 的区别:那个要你自己写条件;这个不需要知道站点内部在干什么。
   *
   * @param quietMs   需要连续安静多久(毫秒),默认 500
   * @param selector  额外条件:这个选择器出现才算数(可选)
   * @param text      额外条件:页面上出现这段文本才算数(可选)
   * @return {@code {idle:true, waitedMs, mutations, inflight, quietMs}};超时返回失败并带上当时的计数,
   *         便于判断是「网络一直没停」还是「DOM 一直在变」
   */
  public RespBodyVo waitForIdle(Long browserId, Integer quietMs, Double timeoutSeconds, String selector, String text) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    long quiet = quietMs == null || quietMs < 0 ? 500 : quietMs;
    long timeout = (long) timeoutMillis(timeoutSeconds);
    long startedAt = System.currentTimeMillis();
    long deadline = startedAt + timeout;
    int mutations = -1;
    long stableSince = startedAt;
    int inflight = 0;
    while (true) {
      long now = System.currentTimeMillis();
      int current = readMutationCount(inst);
      inflight = inst.inflight.get();
      boolean extra = (selector == null || selector.isBlank() || countOf(inst, selector) > 0)
          && (text == null || text.isBlank() || bodyContains(inst, text));
      if (current != mutations) {
        mutations = current;
        stableSince = now;
      }
      if (extra && inflight == 0 && now - stableSince >= quiet) {
        return RespBodyVo.ok(Kv.by("idle", true).set("waitedMs", now - startedAt).set("mutations", mutations)
            .set("inflight", 0).set("quietMs", quiet).set("timeoutSeconds", timeout / 1000.0)
            .set("url", safeUrl(inst.page)));
      }
      if (now >= deadline) {
        Kv data = Kv.by("idle", false).set("waitedMs", now - startedAt).set("mutations", mutations)
            .set("inflight", inflight).set("quietMs", quiet).set("url", safeUrl(inst.page))
            .set("selectorMatched", selector == null || selector.isBlank() || countOf(inst, selector) > 0)
            .set("textMatched", text == null || text.isBlank() || bodyContains(inst, text));
        RespBodyVo failure = RespBodyVo.fail("wait_for_idle 失败：" + (timeout / 1000.0) + " 秒内页面没有安静下来"
            + "（在途请求 " + inflight + " 个，DOM 变更累计 " + mutations + " 次）");
        failure.setData(data);
        return failure;
      }
      inst.page.waitForTimeout(100);
    }
  }

  /** 读页面里的 DOM 变更计数(第一次调用会装上 MutationObserver) */
  private static int readMutationCount(BrowserInstance inst) {
    try {
      Object result = inst.page.evaluate(MUTATION_PROBE);
      if (result instanceof Map) {
        Object mutations = ((Map<?, ?>) result).get("mutations");
        return mutations instanceof Number ? ((Number) mutations).intValue() : 0;
      }
    } catch (PlaywrightException e) {
      log.debug("读取 DOM 变更计数失败:{}", briefMessage(e.getMessage()));
    }
    return 0;
  }

  private static int countOf(BrowserInstance inst, String selector) {
    try {
      return inst.page.locator(selector).count();
    } catch (PlaywrightException e) {
      return 0;
    }
  }

  private static boolean bodyContains(BrowserInstance inst, String text) {
    try {
      return Boolean.TRUE.equals(inst.page.evaluate(
          "(t) => (document.body ? document.body.innerText : '').includes(t)", text));
    } catch (PlaywrightException e) {
      return false;
    }
  }

  // ==================== 等内容稳定 / 等数量达标 ====================

  /** 内容指纹:正文 + 控件值 + 表格单元文本。与「DOM 变更计数」不同,它只看内容有没有变 */
  private static final String CONTENT_FINGERPRINT = """
      (scope) => {
        const root = scope ? document.querySelector(scope) : document.body;
        if (!root) return null;
        const parts = [root.innerText || ''];
        root.querySelectorAll('input,textarea,select').forEach(e => parts.push(String(e.value), String(e.checked)));
        root.querySelectorAll('table tr').forEach(r => parts.push((r.innerText || '').replace(/\\s+/g, ' ')));
        const source = parts.join('|');
        let hash = 2166136261;
        for (let i = 0; i < source.length; i++) hash = Math.imul(hash ^ source.charCodeAt(i), 16777619);
        return {
          length: source.length,
          fingerprint: String(hash >>> 0),
          // 稳定之后调用方多半就是要读这段内容:顺手带回去,省掉一次 get_element_text / execute_js
          text: (root.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 2000)
        };
      }
      """;

  /**
   * 等内容稳定:正文/表单值/表格内容在连续 quietMs 毫秒内不再变化
   *
   * <p>
   * 与 {@code wait_for_idle} 的分工:idle 看的是「DOM 变更计数 + 在途请求」,适合「点一下、
   * 等它保存完」;stable 看的是**内容**,适合「搜索/查询结果异步刷新」——这时页面可能一直在动
   * (动画、计时器),但真正要读的内容会在某一刻定下来。
   *
   * <p>
   * 实测踩过的坑:点「查询」后立刻读结果,读到的是**上一次**的搜索结果,表现成「明明有这一行却
   * 找不到」。先 stable 再读就不会错位。
   *
   * @param selector 只盯这个容器(可选,默认整个 body)
   * @param quietMs  需要连续稳定多久,默认 800
   * @return {@code {stable:true, waitedMs, changes, length, fingerprint, text}};超时返回失败并带上
   *         「已变化几次 / 距上次变化多久」,便于判断是内容一直在刷还是压根没加载
   */
  public RespBodyVo waitForStable(Long browserId, String selector, Integer quietMs, Double timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    long quiet = quietMs == null || quietMs < 0 ? 800 : quietMs;
    long timeout = (long) timeoutMillis(timeoutSeconds);
    long startedAt = System.currentTimeMillis();
    long deadline = startedAt + timeout;
    String last = null;
    long stableSince = startedAt;
    int changes = 0;
    int length = 0;
    while (true) {
      long now = System.currentTimeMillis();
      Kv probe = contentProbe(inst, selector);
      if (probe == null) {
        RespBodyVo failure = RespBodyVo.fail("wait_for_stable 失败：读不到内容指纹"
            + (selector == null || selector.isBlank() ? "" : "（选择器 " + selector + " 没有命中元素）"));
        failure.setData(Kv.by("stable", false).set("selector", selector).set("url", safeUrl(inst.page)));
        return failure;
      }
      String fingerprint = probe.getStr("fingerprint");
      length = asInt(probe.get("length"));
      if (!Objects.equals(fingerprint, last)) {
        last = fingerprint;
        stableSince = now;
        changes++;
      }
      if (now - stableSince >= quiet) {
        return RespBodyVo.ok(Kv.by("stable", true).set("waitedMs", now - startedAt).set("quietMs", quiet)
            .set("changes", changes).set("length", length).set("fingerprint", fingerprint)
            .set("text", probe.get("text")).set("selector", selector).set("url", safeUrl(inst.page)));
      }
      if (now >= deadline) {
        RespBodyVo failure = RespBodyVo.fail("wait_for_stable 失败：" + (timeout / 1000.0)
            + " 秒内内容一直没稳定下来（已变化 " + changes + " 次，最近一次变化在 " + (now - stableSince) + " ms 前）");
        failure.setData(Kv.by("stable", false).set("waitedMs", now - startedAt).set("changes", changes)
            .set("quietMs", quiet).set("msSinceLastChange", now - stableSince).set("url", safeUrl(inst.page)));
        return failure;
      }
      inst.page.waitForTimeout(150);
    }
  }

  /** 读内容指纹;读不到(容器不存在/脚本报错)返回 null */
  private static Kv contentProbe(BrowserInstance inst, String selector) {
    try {
      Object raw = inst.page.evaluate(CONTENT_FINGERPRINT, selector == null || selector.isBlank() ? null : selector);
      if (!(raw instanceof Map)) {
        return null;
      }
      Kv probe = new Kv();
      probe.putAll((Map<?, ?>) raw);
      return probe;
    } catch (PlaywrightException e) {
      return null;
    }
  }

  /**
   * 等某个选择器的命中数量达到条件
   *
   * <p>
   * 两个最常用的用途:等弹窗/遮罩**全部消失**({@code max:0})、等结果行**出现**({@code min:1})。
   * 以前这两件事都只能手写 {@code execute_js} 轮询,每写一次就多一段容易写错的循环。
   *
   * @param min/max/equals 三个条件可以只给一个,也可以一起给(都要满足)
   * @return {@code {matched:true, count, waitedMs}};超时返回失败并带上当时的 count
   */
  public RespBodyVo waitForCount(Long browserId, String selector, Integer min, Integer max, Integer equals,
      Double timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (min == null && max == null && equals == null) {
      return RespBodyVo.fail("wait_for_count 需要 min / max / equals 里至少一个条件");
    }
    long timeout = (long) timeoutMillis(timeoutSeconds);
    long startedAt = System.currentTimeMillis();
    long deadline = startedAt + timeout;
    int count = 0;
    while (true) {
      count = countOf(inst, selector);
      if ((min == null || count >= min) && (max == null || count <= max) && (equals == null || count == equals)) {
        return RespBodyVo.ok(Kv.by("matched", true).set("count", count).set("waitedMs",
            System.currentTimeMillis() - startedAt).set("selector", selector).set("url", safeUrl(inst.page)));
      }
      long now = System.currentTimeMillis();
      if (now >= deadline) {
        RespBodyVo failure = RespBodyVo.fail("wait_for_count 失败：" + (timeout / 1000.0) + " 秒内 " + selector
            + " 的命中数没达到条件（实际 " + count + describeCountCondition(min, max, equals) + "）");
        failure.setData(Kv.by("matched", false).set("count", count).set("waitedMs", now - startedAt)
            .set("selector", selector).set("url", safeUrl(inst.page)));
        return failure;
      }
      inst.page.waitForTimeout(120);
    }
  }

  private static String describeCountCondition(Integer min, Integer max, Integer equals) {
    StringBuilder sb = new StringBuilder("（要求");
    if (equals != null) {
      sb.append("等于 ").append(equals);
    }
    if (min != null) {
      sb.append(equals != null ? "、" : "").append("≥ ").append(min);
    }
    if (max != null) {
      sb.append((equals != null || min != null) ? "、" : "").append("≤ ").append(max);
    }
    return sb.append("）").toString();
  }

  // ==================== 表单状态 ====================

  /**
   * 读表单状态:每个控件的标签、值、可见性、禁用/只读、校验态,外加一份错误清单
   *
   * <p>
   * <b>为什么需要它</b>:填长表单时「这个值到底进没进框架的模型」是最难判断的事——用
   * {@code execute_js} 改 DOM 的值,页面上看着填好了,站点自己的预览页/提交内容里却可能是空的。
   * 有了这个命令,一次调用就能把整张表单读回来(包括每个字段的错误文案),不用每次手写一段 JS
   * 去遍历 {@code .ant-form-item}。密码字段的值一律不返回,只返回 {@code [redacted]}。
   *
   * @param selector      作用范围,默认整页({@code document})
   * @param includeHidden 是否包含不可见控件,默认 false
   * @param max           最多返回多少个控件,默认 200
   */
  public RespBodyVo getFormState(Long browserId, String selector, Boolean includeHidden, Integer max) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    JSONObject args = new JSONObject();
    args.put("selector", selector);
    args.put("includeHidden", includeHidden != null && includeHidden);
    args.put("max", max == null || max <= 0 ? 200 : max);
    Object result;
    try {
      result = inst.page.evaluate(FORM_STATE, args);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("get_form_state 失败：" + briefMessage(e.getMessage()));
    }
    if (!(result instanceof Map)) {
      return RespBodyVo.fail("get_form_state 失败：页面没有返回表单信息");
    }
    Kv data = new Kv();
    data.putAll((Map) result);
    data.set("url", safeUrl(inst.page));
    data.set("scope", selector == null || selector.isBlank() ? "document" : selector);
    return RespBodyVo.ok(data);
  }

  /**
   * 表单状态探针
   *
   * <p>标签的取法按优先级来:显式 {@code label[for]} / {@code aria-label} / 最近的表单项容器里的
   * {@code label} / 前一个兄弟节点 / {@code placeholder}——政务与后台系统的表单实现千差万别,只认一种
   * 写法会漏掉大半字段。
   */
  private static final String FORM_STATE = """
      (args) => {
        const scope = args.selector ? document.querySelector(args.selector) : document;
        if (!scope) return {error: 'scope-not-found', count: 0, fields: [], errors: []};
        const labelOf = (el) => {
          try {
            if (el.labels && el.labels.length) return (el.labels[0].innerText || '').replace(/\\s+/g, ' ').trim();
            const aria = el.getAttribute('aria-label');
            if (aria) return aria.trim();
            const item = el.closest('.ant-form-item, .form-item, .el-form-item, [class*="form-item"]');
            if (item) {
              const l = item.querySelector('label');
              if (l && l.innerText) return l.innerText.replace(/[:：*\\s]+$/g, '').replace(/\\s+/g, ' ').trim();
            }
            const prev = el.previousElementSibling;
            if (prev && prev.innerText) return prev.innerText.replace(/\\s+/g, ' ').trim().slice(0, 60);
            return el.getAttribute('placeholder') || null;
          } catch (e) { return null; }
        };
        const fields = [];
        const controls = Array.from(scope.querySelectorAll('input,textarea,select'));
        for (const el of controls) {
          const type = (el.getAttribute('type') || el.tagName.toLowerCase()).toLowerCase();
          if (type === 'hidden' && !args.includeHidden) continue;
          const rect = el.getBoundingClientRect();
          const style = window.getComputedStyle(el);
          const visible = rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden'
            && style.display !== 'none' && (el.offsetParent !== null || style.position === 'fixed');
          if (!visible && !args.includeHidden) continue;
          const item = el.closest('.ant-form-item, .form-item, .el-form-item, [class*="form-item"]');
          let error = null;
          let invalid = false;
          if (item) {
            const message = item.querySelector('.ant-form-item-explain-error, [class*="error-message"], .error');
            if (message && message.innerText && message.innerText.trim()) error = message.innerText.trim();
            invalid = /has-error|is-error|error/.test(String(item.className || ''));
          }
          fields.push({
            label: labelOf(el),
            id: el.id || null,
            name: el.getAttribute('name') || null,
            type: type,
            value: type === 'password' ? '[redacted]' : (el.value === undefined ? null : el.value),
            checked: typeof el.checked === 'boolean' ? el.checked : null,
            disabled: !!el.disabled,
            readOnly: !!el.readOnly,
            required: !!el.required || el.getAttribute('aria-required') === 'true',
            visible: visible,
            invalid: invalid,
            error: error,
            placeholder: el.getAttribute('placeholder') || null
          });
          if (fields.length >= args.max) break;
        }
        const errors = fields.filter(f => f.error || f.invalid)
          .map(f => ({label: f.label, id: f.id, error: f.error}));
        return {count: fields.length, errorCount: errors.length, errors: errors, fields: fields,
                truncated: fields.length >= args.max};
      }
      """;

  // ==================== 鼠标 ====================

  public RespBodyVo mouseMove(Long browserId, double x, double y) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.page.mouse().move(x, y);
    return RespBodyVo.ok();
  }

  public RespBodyVo mouseDown(Long browserId, String button) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.page.mouse().down(new Mouse.DownOptions().setButton(mouseButton(button)));
    return RespBodyVo.ok();
  }

  public RespBodyVo mouseUp(Long browserId, String button) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.page.mouse().up(new Mouse.UpOptions().setButton(mouseButton(button)));
    return RespBodyVo.ok();
  }

  public RespBodyVo mouseWheel(Long browserId, double deltaY) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.page.mouse().wheel(0, deltaY);
    return RespBodyVo.ok();
  }

  /**
   * 按坐标真实鼠标点击(一条命令顶原来的 mouse_move + mouse_down + mouse_up 三条)
   *
   * <p>
   * 坐标是**视口坐标**(左上角为原点),与 {@code get_modals} 回的 {@code closePoint}、
   * {@code get_element_box} 回的盒子可以直接配合使用。
   *
   * <p>
   * 什么时候需要它:JS 派发 click 无效的按钮(ant-design 的 {@code Modal.confirm} 确定按钮、
   * 对话框右上角 ×)、被覆盖层挡住但确实要点到的元素、以及需要「真实事件序列」的场景
   * (pointerdown/mousedown/pointerup/mouseup/click 全套由浏览器发出)。
   */
  public RespBodyVo mouseClick(Long browserId, double x, double y, String button, Integer clickCount) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv before = stateProbe(inst);
    try {
      inst.page.mouse().click(x, y, new Mouse.ClickOptions().setButton(mouseButton(button))
          .setClickCount(clickCount == null || clickCount <= 0 ? 1 : clickCount));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("mouse_click 失败：" + briefMessage(e.getMessage()));
    }
    return okWithReceipt(before, inst, "mouse_click", Kv.by("mode", "mouse").set("x", x).set("y", y));
  }

  /**
   * 按选择器真实鼠标点击:元素滚进视口后点它的盒子中心
   *
   * <p>
   * 与 {@code click_element_by_selector mode:"mouse"} 等价,单独留一个命令是为了「一眼看出这次
   * 是真实鼠标点击」,不必记住 mode 的取值。
   */
  public RespBodyVo mouseClickBySelector(Long browserId, String selector, String button, Integer clickCount,
      Integer timeoutMs) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Locator locator = inst.page.locator(selector).first();
    Kv before = stateProbe(inst);
    ActionOutcome outcome = new ActionOutcome();
    recordBlocker(locator, outcome);
    if (!mouseClickAt(locator, button, clickCount)) {
      return RespBodyVo.fail("mouse_click_by_selector 失败：拿不到元素盒子（选择器 " + selector
          + " 没有命中可见元素）" + blockerSuffix(locator));
    }
    outcome.mode = "mouse";
    outcome.record();
    return okWithReceipt(before, inst, "mouse_click_by_selector", outcome.extra);
  }

  /** 真实鼠标点击某个元素(可指定键与次数);拿不到盒子返回 false */
  private static boolean mouseClickAt(Locator locator, String button, Integer clickCount) {
    try {
      Locator target = locator.first();
      try {
        target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(2_000));
      } catch (PlaywrightException ignored) {
        // 滚不动也继续试
      }
      BoundingBox box = target.boundingBox();
      if (box == null || box.width <= 0 || box.height <= 0) {
        return false;
      }
      target.page().mouse().click(box.x + box.width / 2, box.y + box.height / 2,
          new Mouse.ClickOptions().setButton(mouseButton(button))
              .setClickCount(clickCount == null || clickCount <= 0 ? 1 : clickCount));
      return true;
    } catch (PlaywrightException e) {
      return false;
    }
  }

  // ==================== 截图与 PDF ====================

  /**
   * 页面截图
   *
   * <p>
   * 传了 index 或 selector 时只截该元素;否则截整页,可以用 clipX/clipY/clipWidth/clipHeight
   * 指定裁剪区域(四个都传才生效)。
   *
   * <p>
   * <b>默认落盘,不回 base64</b>:没给 {@code path} 时服务自己写到 {@code data/&lt;id&gt;/shot-N.png},
   * 只返回路径与可直接 GET 的 URL。理由是 base64 会把整张图塞进模型上下文——一次几十 KB,读几次就
   * 把上下文淹了,而定位与操作要的信息全在结构化文本里。确实需要内联图片时显式传 {@code inline:true}。
   */
  public RespBodyVo screenshot(Long browserId, String path, Boolean fullPage, Integer index, String selector,
      Double clipX, Double clipY, Double clipWidth, Double clipHeight, Boolean inline) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (index != null || (selector != null && !selector.isEmpty())) {
      return elementScreenshot(inst, index, selector, path, inline);
    }
    boolean wantInline = inline != null && inline;
    String target = path;
    if ((target == null || target.isEmpty()) && !wantInline) {
      target = defaultShotPath(inst);
    }
    Page.ScreenshotOptions options = new Page.ScreenshotOptions().setFullPage(fullPage != null && fullPage);
    if (clipX != null && clipY != null && clipWidth != null && clipHeight != null) {
      options.setClip(clipX, clipY, clipWidth, clipHeight);
    }
    if (target != null && !target.isEmpty()) {
      ensureParent(target);
      options.setPath(Paths.get(target));
    }
    try {
      byte[] bytes = inst.page.screenshot(options);
      Kv data = Kv.by("size", bytes.length);
      if (target != null && !target.isEmpty()) {
        data.set("path", target);
        data.set("url", shotUrl(inst, target));
      }
      if (wantInline) {
        data.set("base64", Base64.getEncoder().encodeToString(bytes));
        data.set("inline", true);
      } else {
        data.set("inline", false).set("base64Omitted", true).set("note",
            "默认不返回 base64(会把整张图塞进上下文):要看图请 GET data.url,确实需要内联再传 inline=true");
      }
      return RespBodyVo.ok(data);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("screenshot 失败：" + briefMessage(e.getMessage()));
    }
  }

  /** 服务端自己给截图起的名:data/<id>/shot-N.png,N 只增不减 */
  private static String defaultShotPath(BrowserInstance inst) {
    int n = inst.shotSeq.incrementAndGet();
    return dataDir(inst.id).resolve("shot-" + n + ".png").toString();
  }

  /** 落盘路径在 data/<id>/ 下时给出可直接 GET 的 URL,否则只报路径 */
  private static String shotUrl(BrowserInstance inst, String path) {
    try {
      Path file = Paths.get(path).toAbsolutePath().normalize();
      Path dir = dataDir(inst.id).toAbsolutePath().normalize();
      if (file.startsWith(dir)) {
        return "/" + DATA_DIR + "/" + inst.id + "/" + dir.relativize(file).toString().replace('\\', '/');
      }
    } catch (RuntimeException e) {
      log.debug("计算截图 URL 失败:{}", briefMessage(e.getMessage()));
    }
    return null;
  }

  /**
   * 只截一个元素,返回 data.path+data.url(默认)或 data.base64(inline=true)
   *
   * <p>
   * 和用 execute_js + canvas 抠图相比,这里走 Playwright 自己的截图,不受 canvas 跨域污染限制,
   * 验证码、二维码、图表这类"必须看图"的元素都能拿到。默认同样落盘不回 base64,理由见
   * {@link #screenshot}。
   */
  public RespBodyVo getElementScreenshot(Long browserId, Integer index, String selector, String path) {
    return getElementScreenshot(browserId, index, selector, path, null);
  }

  public RespBodyVo getElementScreenshot(Long browserId, Integer index, String selector, String path, Boolean inline) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return elementScreenshot(inst, index, selector, path, inline);
  }

  private RespBodyVo elementScreenshot(BrowserInstance inst, Integer index, String selector, String path,
      Boolean inline) {
    return elementScreenshot(inst, index, selector, path, inline, null);
  }

  /** 同上,{@code frame} 非空时把选择器限定在指定 frame 里(验证码在跨域 iframe 里时要传) */
  private RespBodyVo elementScreenshot(BrowserInstance inst, Integer index, String selector, String path,
      Boolean inline, String frame) {
    Locator locator;
    String target;
    if (index != null) {
      locator = locatorOf(inst, index);
      target = "index=" + index;
      if (locator == null) {
        return RespBodyVo.fail("get_element_screenshot 索引越界: " + index + indexHint(inst));
      }
    } else if (selector != null && !selector.isEmpty()) {
      try {
        locator = locatorIn(inst, frame, selector);
      } catch (IllegalArgumentException e) {
        return RespBodyVo.fail("get_element_screenshot 失败：" + e.getMessage());
      }
      target = "selector=" + selector + frameSuffix(frame);
    } else {
      return RespBodyVo.fail("get_element_screenshot 需要 index 或 selector");
    }
    boolean wantInline = inline != null && inline;
    String file = path;
    if ((file == null || file.isEmpty()) && !wantInline) {
      file = defaultShotPath(inst);
    }
    try {
      byte[] bytes = locator.screenshot(new Locator.ScreenshotOptions().setTimeout(actionTimeoutMs()));
      Kv data = Kv.by("size", bytes.length).set("target", target);
      if (file != null && !file.isEmpty()) {
        ensureParent(file);
        Files.write(Paths.get(file), bytes);
        data.set("path", file);
        data.set("url", shotUrl(inst, file));
      }
      if (wantInline) {
        data.set("base64", Base64.getEncoder().encodeToString(bytes)).set("inline", true);
      } else {
        data.set("inline", false).set("base64Omitted", true).set("note",
            "默认不返回 base64:要看图请 GET data.url,确实需要内联再传 inline=true");
      }
      return RespBodyVo.ok(data);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("get_element_screenshot", target, e));
    } catch (IOException e) {
      return RespBodyVo.fail("get_element_screenshot 写文件失败：" + e.getMessage());
    }
  }

  public RespBodyVo pdf(Long browserId, String path) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    SharedBrowser browser = sharedBrowser;
    if (browser != null && browser.engine.isFirefox()) {
      // 与其让 Playwright 抛一句英文的「only supported in Chromium」,不如直接说清怎么改
      return RespBodyVo.fail("pdf 失败：PDF 导出只有 Chromium 支持，当前引擎是 firefox"
          + "（browser=firefox 或 browser.engine=firefox），需要 PDF 时请换成 Chromium 系的浏览器再 start："
          + "browser=chrome / browser=chromium / browser=edge");
    }
    String target = (path == null || path.isEmpty()) ? defaultPdfPath() : path;
    try {
      ensureParent(target);
      inst.page.pdf(new Page.PdfOptions().setPath(Paths.get(target)));
      return RespBodyVo.ok(Kv.by("path", target));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("pdf 失败：" + briefMessage(e.getMessage()));
    }
  }

  // ==================== Cookie 与本地存储 ====================

  public RespBodyVo getCookies(Long browserId, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Cookie> cookies = (url == null || url.isEmpty()) ? inst.context.cookies() : inst.context.cookies(url);
    List<Kv> result = new ArrayList<>();
    for (Cookie cookie : cookies) {
      result.add(Kv.by("name", cookie.name).set("value", cookie.value).set("domain", cookie.domain)
          .set("path", cookie.path).set("expires", cookie.expires).set("httpOnly", cookie.httpOnly)
          .set("secure", cookie.secure).set("sameSite", cookie.sameSite == null ? null : cookie.sameSite.name()));
    }
    return RespBodyVo.ok(Kv.by("cookies", result).set("count", result.size()));
  }

  public RespBodyVo setCookie(Long browserId, String name, String value, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Cookie cookie = new Cookie(name, value);
    if (url != null && !url.isEmpty()) {
      cookie.setUrl(url);
    }
    try {
      inst.context.addCookies(Arrays.asList(cookie));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("set_cookie 失败：" + briefMessage(e.getMessage()));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo clearCookies(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.context.clearCookies();
    return RespBodyVo.ok();
  }

  /** key 为空时返回全部 localStorage 的 JSON 字符串 */
  public RespBodyVo getLocalStorage(Long browserId, String key) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      Object value = inst.page.evaluate("(key) => key ? localStorage.getItem(key) : JSON.stringify(localStorage)",
          key == null ? "" : key);
      return RespBodyVo.ok(Kv.by("value", value));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("get_local_storage 失败：" + briefMessage(e.getMessage()));
    }
  }

  public RespBodyVo setLocalStorage(Long browserId, String key, String value) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.evaluate("([k, v]) => localStorage.setItem(k, v)", Arrays.asList(key, value));
      return RespBodyVo.ok();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("set_local_storage 失败：" + briefMessage(e.getMessage()));
    }
  }

  public RespBodyVo clearLocalStorage(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.evaluate("() => localStorage.clear()");
      return RespBodyVo.ok();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("clear_local_storage 失败：" + briefMessage(e.getMessage()));
    }
  }

  // ==================== 浏览器设置 ====================

  public RespBodyVo setViewport(Long browserId, int width, int height) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.page.setViewportSize(width, height);
    return RespBodyVo.ok(Kv.by("width", width).set("height", height));
  }

  public RespBodyVo setGeolocation(Long browserId, double latitude, double longitude) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.context.grantPermissions(Arrays.asList("geolocation"));
      inst.context.setGeolocation(new Geolocation(latitude, longitude));
      return RespBodyVo.ok();
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("set_geolocation 失败：" + briefMessage(e.getMessage()));
    }
  }

  public RespBodyVo setOffline(Long browserId, boolean offline) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.context.setOffline(offline);
    return RespBodyVo.ok(Kv.by("offline", offline));
  }

  /** headersJson 形如 {"X-Key":"v"} */
  public RespBodyVo setHeaders(Long browserId, String headersJson) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      JSONObject obj = JSON.parseObject(headersJson);
      Map<String, String> headers = new LinkedHashMap<>();
      for (String key : obj.keySet()) {
        headers.put(key, String.valueOf(obj.get(key)));
      }
      inst.context.setExtraHTTPHeaders(headers);
      return RespBodyVo.ok(Kv.by("headers", headers));
    } catch (Exception e) {
      return RespBodyVo.fail("set_headers 失败：" + briefMessage(e.getMessage()));
    }
  }

  /**
   * HTTP 基本认证凭据只能在上下文创建时设置,因此该接口会重建整个浏览器
   *
   * <p>
   * 浏览器是所有任务共用的,重建会换掉整个 Chrome 进程(其它任务的页签会一起消失),所以只允许在
   * 「当前只有一个任务」时调用。登录态在 profile 里,重建后还在。
   */
  public RespBodyVo setCredentials(Long browserId, String username, String password) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (inst.opts == null) {
      // opts 为空 = 这次浏览器是「自己拉进程 + CDP 接上」起来的(用户自己的 Chrome profile,或 Edge):
      // 这种上下文的创建参数不在我们手里,HTTP 认证凭据没地方设
      SharedBrowser shared = sharedBrowser;
      boolean edge = shared != null && shared.resolvedType.isEdge();
      return RespBodyVo.fail("set_credentials 失败：当前浏览器走的是「自己拉进程 + CDP 接入」这条启动路径（"
          + (edge ? "browser=edge" : "用用户自己的 Chrome profile") + "），HTTP 认证凭据只能在 Playwright "
          + "创建上下文时设置，这条路径上没法重建上下文"
          + (edge ? "；需要 HTTP 基本认证时改用 browser=chrome 或 browser=chromium（托管 profile 那条路）" : ""));
    }
    if (INSTANCES.size() > 1) {
      return RespBodyVo.fail("set_credentials 会重建整个浏览器，而所有任务共用同一个浏览器与 profile；请先 close 掉其它任务再试");
    }
    inst.opts.setHttpCredentials(new HttpCredentials(username, password));
    restartContext(inst);
    return RespBodyVo.ok(Kv.by("restarted", true));
  }

  /** colorScheme 取 light / dark / no-preference */
  public RespBodyVo setMedia(Long browserId, String colorScheme) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      ColorScheme scheme = "dark".equalsIgnoreCase(colorScheme) ? ColorScheme.DARK
          : "light".equalsIgnoreCase(colorScheme) ? ColorScheme.LIGHT : ColorScheme.NO_PREFERENCE;
      inst.page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(scheme));
      return RespBodyVo.ok(Kv.by("colorScheme", scheme.name()));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("set_media 失败：" + briefMessage(e.getMessage()));
    }
  }

  // ==================== 弹窗 / 控制台 ====================

  /**
   * 读最近一次弹窗
   *
   * <p>
   * 弹窗记录不会自动清除,所以它可能来自很早以前的一次操作。data.dialog 里带 seq 与
   * timestamp,客户端可以据此判断是不是新弹窗;consume 为 true 时读后即清,下一次读只会 返回这之后新产生的弹窗(推荐在每次提交动作前
   * consume 一次,避免把旧弹窗当成新结果)。
   */
  public RespBodyVo getDialog(Long browserId, Boolean consume) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv dialog = inst.lastDialog;
    if (consume != null && consume) {
      inst.lastDialog = null;
    }
    if (dialog == null) {
      return RespBodyVo.ok(Kv.by("dialog", null));
    }
    return RespBodyVo.ok(Kv.by("dialog", dialog));
  }

  /** 清空弹窗记录,返回 data.cleared */
  public RespBodyVo clearDialog(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    boolean cleared = inst.lastDialog != null;
    inst.lastDialog = null;
    return RespBodyVo.ok(Kv.by("cleared", cleared));
  }

  /** dismiss 为 true 时后续弹窗自动取消,默认自动确认 */
  public RespBodyVo setDialogBehavior(Long browserId, boolean dismiss) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.dismissDialogs = dismiss;
    return RespBodyVo.ok(Kv.by("dismiss", dismiss));
  }

  // ==================== DOM 弹窗(与上面的原生 alert/confirm 是两回事) ====================

  /**
   * 页面上当前可见的 DOM 弹窗
   *
   * <p>
   * <b>和 {@code get_dialog} 的区别</b>:那个是浏览器**原生**对话框({@code window.alert/confirm/prompt}),
   * 由 Playwright 的事件接住;这里是页面里的 **DOM 弹窗**——ant-design 的 {@code .ant-modal-wrap}、
   * 各类 {@code [role=dialog]}、以及「用户服务协议」这类自定义遮罩层({@code .agreement-container})。
   * 两者名字相近但完全不是一回事,以前只有前者,于是「页面上明明有个弹窗挡着」在接口里是看不见的。
   *
   * <p>
   * 返回的每一项都带 {@code buttons}(按钮文本)、{@code rect} 和 {@code closePoint}(右上角 × 的
   * 视口坐标,可直接喂给 {@code mouse_click}),以及 {@code coveredHint} 需要的信息。
   */
  public RespBodyVo getModals(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv probe = modalProbe(inst);
    if (probe == null) {
      return RespBodyVo.fail("get_modals 失败：读取页面弹窗失败");
    }
    return RespBodyVo.ok(probe);
  }

  /**
   * 页面上可见的弹窗清单(DOM 顺序:旧的在前,最顶层/最新的是最后一个)
   *
   * <p>
   * <b>为什么要加通用兜底</b>:原来只认框架专用的类名({@code .ant-modal-wrap} / {@code .el-dialog} /
   * {@code .layui-layer} …),而企业微信自己的弹窗用的是 {@code qui_dialog} / {@code ww_dialog} /
   * {@code .mall_invoice_dialog_container} 这类自有类名 —— 一个都命中不了,于是 {@code count:0} 被理解成
   * 「没有弹窗」,继续去点被它挡住的元素。这种**假阴性比假阳性危险**:假阳性是多看一条,假阴性是把人误导到
   * 错的方向。所以这里在框架选择器之后补两轮启发式扫描(类名线索 + 几何线索)。
   *
   * <p>
   * 每条结果都带 {@code matchedBy},调用方据此判断置信度:{@code selector:…} 是框架/类名直接命中,
   * {@code heuristic:class-name} 是类名里有 dialog/modal/popup 这类词,{@code heuristic:fixed-overlay}
   * 是「可见 + 够大 + fixed 或高 z-index」的几何兜底。
   */
  private static final String MODALS_PROBE = """
      () => {
        const vis = (el) => {
          if (!el) return false;
          const cs = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0'
            && r.width > 0 && r.height > 0;
        };
        const text = (el) => el ? (el.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 80) : null;
        const center = (el) => {
          const r = el.getBoundingClientRect();
          return [Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2)];
        };
        const classOf = (el) => (typeof el.className === 'string' ? el.className : '')
          || (el.className && el.className.baseVal) || '';
        const describe = (el, kind, matchedBy) => {
          const titleEl = el.querySelector('.ant-modal-title, .ant-modal-confirm-title, .ant-drawer-title, [class*=title]');
          const buttons = [];
          el.querySelectorAll('button, .ant-btn, a[role=button], [role=button]').forEach(b => {
            if (!vis(b)) return;
            const label = (b.innerText || '').trim().replace(/\\s+/g, '');
            if (label) buttons.push({ label: label, point: center(b) });
          });
          const closeEl = el.querySelector('.ant-modal-close, .ant-modal-close-x, [aria-label=Close], [class*=close]');
          const r = el.getBoundingClientRect();
          return {
            kind: kind,
            matchedBy: matchedBy,
            className: classOf(el).slice(0, 200),
            id: el.id || null,
            title: text(titleEl),
            text: text(el).slice(0, 80),
            buttons: buttons.map(b => b.label),
            buttonPoints: buttons,
            hasClose: !!closeEl,
            closePoint: closeEl && vis(closeEl) ? center(closeEl) : null,
            rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
            zIndex: getComputedStyle(el).zIndex
          };
        };
        const modals = [];
        // 同一个弹窗会被多个选择器命中(ant 的弹窗里就有 role=dialog),所以收集时要去重:
        // 已经收过的元素、以及被已收元素包住的元素都不再单独算一个,否则一个弹窗会数成两个
        const seen = [];
        const add = (el, kind, matchedBy) => {
          if (!vis(el)) return;
          if (el.id === 'playwright-highlight-container') return;
          if (el.tagName === 'HTML' || el.tagName === 'BODY') return;
          for (const s of seen) {
            if (s === el || s.contains(el) || el.contains(s)) return;
          }
          seen.push(el);
          modals.push(describe(el, kind, matchedBy));
        };
        // 第一轮:框架专用选择器(最高置信度)
        const FRAMEWORK = [
          ['.ant-modal-wrap, .ant-drawer-open', 'ant-modal'],
          ['.agreement-container, [class*=agreement]', 'agreement'],
          ['[role=dialog], [role=alertdialog], .el-dialog, .vxe-modal--wrapper, .layui-layer', 'dialog']
        ];
        for (const [selector, kind] of FRAMEWORK) {
          document.querySelectorAll(selector).forEach(el => add(el, kind, 'selector:' + selector));
        }
        // 第二轮:类名线索。企业微信 / 微信系的弹窗用的是 qui_dialog / ww_dialog / xxx_dialog_container
        // 这类自有类名,选择器命中不了,但类名里有 dialog/modal/popup 这样的词
        const CLASS_HINT = /(dialog|modal|popup|pop-box|popover|overlay|mask|drawer|lightbox|confirm|tips?|alert)/i;
        const bigEnough = (el) => {
          const r = el.getBoundingClientRect();
          return r.width >= 120 && r.height >= 50;
        };
        // 只保留「最内层」的候选:包住另一个候选的元素通常是整页遮罩,真正带标题和按钮的是它里面那个。
        // 不加这一层的话,整页 mask 会先把真弹窗吃掉(contains 去重会认为真弹窗已经被收过)。
        const innermost = (candidates) => candidates.filter(
          el => !candidates.some(other => other !== el && el.contains(other)));
        const classCandidates = Array.from(document.querySelectorAll('div,section,aside,dialog,form,ul'))
          .filter(el => CLASS_HINT.test(classOf(el)) && vis(el) && bigEnough(el));
        let heuristicCount = 0;
        for (const el of innermost(classCandidates)) {
          if (heuristicCount >= 12) break;
          add(el, 'class-hint', 'heuristic:class-name');
          heuristicCount++;
        }
        // 第三轮:几何兜底。可见 + 面积够大 + (position:fixed 或 z-index 高于阈值)
        const Z_THRESHOLD = 50;
        const MIN_W = 150;
        const MIN_H = 60;
        const geo = Array.from(document.querySelectorAll('div,section,aside,dialog'))
          .filter(el => {
            if (el.id === 'playwright-highlight-container') return false;
            const cs = getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
            const r = el.getBoundingClientRect();
            if (r.width < MIN_W || r.height < MIN_H) return false;
            const z = parseInt(cs.zIndex, 10);
            return cs.position === 'fixed' || (!isNaN(z) && z > Z_THRESHOLD);
          });
        for (const el of innermost(geo)) {
          if (heuristicCount >= 24) break;
          add(el, 'heuristic', 'heuristic:fixed-overlay');
          heuristicCount++;
        }
        // 后出现的通常是后弹出来的,排在最后当作「最顶层」
        return {
          count: modals.length,
          modals: modals,
          top: modals.length ? modals[modals.length - 1] : null,
          scannedBy: ['selector', 'heuristic:class-name', 'heuristic:fixed-overlay'],
          note: modals.length
            ? 'modals 按 DOM 顺序排列,最后一个是最后弹出来的(通常就是最顶层);关它用 close_modal'
              + '每条都带 matchedBy 说明命中来源:selector:… 置信度最高,heuristic:… 是启发式兜底'
            : '当前没有可见的 DOM 弹窗(已跑过框架选择器 + 类名线索 + 几何兜底三轮扫描;'
              + 'get_dialog 看的是原生 alert/confirm,两者不同)'
        };
      }
      """;

  /**
   * 读弹窗:主 frame 与每个 iframe 各扫一遍,iframe 里的坐标换算到主页面视口
   *
   * <p>
   * <b>为什么必须扫 iframe</b>:主站把第三方控制台套在 iframe 里时,控制台自己的弹窗(确认框、协议层)
   * 也在那个 frame 的文档里。只扫顶层文档同样会得到 {@code count:0} 的假阴性,而那个弹窗照样挡着点击。
   *
   * <p>
   * 坐标换算:{@code getBoundingClientRect} 在 frame 里给的是**相对该 frame 视口**的坐标,而
   * {@code ElementHandle.boundingBox()} 给的是 iframe 元素**相对主 frame 视口**的位置(嵌套也已经是累加的),
   * 两者相加就是可以直接喂给 {@code mouse_click} 的主页面坐标。
   */
  private static Kv modalProbe(BrowserInstance inst) {
    Kv merged = new Kv();
    List<Kv> modals = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    boolean any = false;
    List<DomService.FrameNode> nodes;
    try {
      nodes = DomService.frameTree(inst.page);
    } catch (PlaywrightException e) {
      return null;
    }
    for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
      DomService.FrameNode node = nodes.get(ordinal);
      Kv probe;
      try {
        Object raw = node.frame.evaluate(MODALS_PROBE);
        if (!(raw instanceof Map)) {
          continue;
        }
        probe = new Kv();
      probe.putAll((Map<?, ?>) raw);
      } catch (PlaywrightException e) {
        errors.add("frame[" + ordinal + "]: " + briefMessage(e.getMessage()));
        continue;
      }
      any = true;
      double[] offset = ordinal == 0 ? new double[] {0, 0} : frameOffset(node.frame);
      Object rawModals = probe.get("modals");
      if (!(rawModals instanceof List)) {
        continue;
      }
      for (Object item : (List<?>) rawModals) {
        if (!(item instanceof Map)) {
          continue;
        }
        Kv modal = new Kv();
          modal.putAll((Map<?, ?>) item);
        if (ordinal > 0) {
          shiftPoints(modal, offset);
          modal.set("frameIndex", ordinal).set("frameUrl", safeFrameUrl(node.frame));
        } else {
          modal.set("frameIndex", 0);
        }
        modals.add(modal);
      }
      if (ordinal == 0) {
        merged.set("note", probe.get("note")).set("scannedBy", probe.get("scannedBy"));
      }
    }
    if (!any) {
      return null;
    }
    merged.set("count", modals.size()).set("modals", modals)
        .set("top", modals.isEmpty() ? null : modals.get(modals.size() - 1));
    if (!errors.isEmpty()) {
      merged.set("frameErrors", errors);
    }
    return merged;
  }

  /** iframe 元素在主 frame 视口里的位置;取不到时按 0 处理 */
  private static double[] frameOffset(Frame frame) {
    try {
      com.microsoft.playwright.ElementHandle handle = frame.frameElement();
      if (handle == null) {
        return new double[] {0, 0};
      }
      BoundingBox box = handle.boundingBox();
      return box == null ? new double[] {0, 0} : new double[] {box.x, box.y};
    } catch (PlaywrightException e) {
      return new double[] {0, 0};
    }
  }

  /** 把 frame 内坐标平移到主页面视口坐标:rect / closePoint / buttonPoints[].point */
  @SuppressWarnings("unchecked")
  private static void shiftPoints(Kv modal, double[] offset) {
    Object rect = modal.get("rect");
    if (rect instanceof List && ((List<Object>) rect).size() >= 2) {
      List<Object> values = (List<Object>) rect;
      values.set(0, asInt(values.get(0)) + (int) Math.round(offset[0]));
      values.set(1, asInt(values.get(1)) + (int) Math.round(offset[1]));
      modal.set("rect", values);
    }
    Object closePoint = modal.get("closePoint");
    if (closePoint instanceof List) {
      modal.set("closePoint", shiftPoint((List<Object>) closePoint, offset));
    }
    Object points = modal.get("buttonPoints");
    if (points instanceof List) {
      for (Object entry : (List<Object>) points) {
        if (entry instanceof Map) {
          Map<String, Object> map = (Map<String, Object>) entry;
          Object point = map.get("point");
          if (point instanceof List) {
            map.put("point", shiftPoint((List<Object>) point, offset));
          }
        }
      }
    }
  }

  private static List<Object> shiftPoint(List<Object> point, double[] offset) {
    if (point.size() < 2) {
      return point;
    }
    List<Object> shifted = new ArrayList<>(point);
    shifted.set(0, asInt(point.get(0)) + (int) Math.round(offset[0]));
    shifted.set(1, asInt(point.get(1)) + (int) Math.round(offset[1]));
    return shifted;
  }

  /**
   * 关掉一个 DOM 弹窗
   *
   * <p>
   * <b>为什么必须有这个命令</b>:实测 ant-design 的 {@code Modal.confirm}「确定/取消」和对话框右上角
   * 的 × **只认真实鼠标事件**,JS 派发 click(甚至元素原生 {@code el.click()})完全无效——点了没反应、
   * 弹窗不关,而接口照样回成功。反复点还会把确认框一层层叠起来(实测叠到 16 个),之后所有
   * 「取第一个可见弹窗」的逻辑都在操作最老的那个。所以这里**一律用真实鼠标**点,并且点完**校验**
   * 弹窗数量是否真的减少了,把结果如实写进回执。
   *
   * @param which  选哪个弹窗:{@code top}(默认,最后一个弹出来的)、{@code first}(最早那个)、
   *               {@code all}(依次关掉全部)、{@code class:<子串>}(按 className 子串匹配,例如
   *               {@code class:mall_invoice_dialog_container});很多站点的弹窗既没有标题、也没有
   *               {@code role=dialog},只能靠类名认
   * @param title  按标题/文本子串匹配(给了就以它为准,忽略 which)
   * @param button 点哪个按钮:不给则优先右上角 ×,其次「取消/关闭/知道了/我接受」这类非提交按钮;
   *               要给就写按钮文本(空格会被忽略,如 {@code 确定}、{@code 我接受})
   * @return {@code {closed, countBefore, countAfter, clicked, closedAll}}
   */
  public RespBodyVo closeModal(Long browserId, String which, String title, String button) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv before = modalProbe(inst);
    if (before == null) {
      return RespBodyVo.fail("close_modal 失败：读取页面弹窗失败");
    }
    int countBefore = asInt(before.get("count"));
    if (countBefore == 0) {
      return RespBodyVo.ok(Kv.by("closed", false).set("countBefore", 0).set("countAfter", 0)
          .set("note", "当前没有可见的 DOM 弹窗,无需关闭"));
    }
    String mode = which == null || which.isBlank() ? "top" : which.trim().toLowerCase(java.util.Locale.ROOT);
    List<Kv> targets = pickModals(before, mode, title);
    if (targets.isEmpty()) {
      return RespBodyVo.fail("close_modal 失败：没有匹配的弹窗（" + (mode.startsWith("class:")
          ? "className 含「" + mode.substring("class:".length()) + "」的弹窗不存在"
          : "标题/文本含「" + title + "」的弹窗不存在")
          + ",当前共 " + countBefore + " 个,用 get_modals 看清单（每项都有 className 与 matchedBy））");
    }
    List<Kv> clicked = new ArrayList<>();
    for (Kv target : targets) {
      Kv hit = clickModalButton(inst, target, button);
      if (hit != null) {
        clicked.add(hit);
      }
      // 关掉一个就重新探一次:数量真的减少了才算成功
      if (!"all".equals(mode)) {
        break;
      }
      Kv now = modalProbe(inst);
      if (now == null || asInt(now.get("count")) == 0) {
        break;
      }
    }
    Kv after = modalProbe(inst);
    int countAfter = after == null ? countBefore : asInt(after.get("count"));
    boolean closed = countAfter < countBefore;
    Kv data = Kv.by("closed", closed).set("countBefore", countBefore).set("countAfter", countAfter)
        .set("clicked", clicked).set("closedAll", countAfter == 0).set("which", mode);
    if (title != null && !title.isBlank()) {
      data.set("title", title);
    }
    if (!closed) {
      data.set("hint", "点了按钮但弹窗数量没减少:该弹窗可能只认真实鼠标事件、或点中的不是它的关闭按钮。"
          + "用 get_modals 拿 closePoint / buttonPoints,再直接 mouse_click 那个坐标");
    }
    return RespBodyVo.ok(data);
  }

  /** 按 which/title 选出要关的弹窗 */
  @SuppressWarnings("unchecked")
  private static List<Kv> pickModals(Kv probe, String which, String title) {
    List<Kv> all = new ArrayList<>();
    Object raw = probe.get("modals");
    if (raw instanceof List) {
      for (Object item : (List<Object>) raw) {
        if (item instanceof Map) {
          Kv modal = new Kv();
          modal.putAll((Map<?, ?>) item);
          all.add(modal);
        }
      }
    }
    if (title != null && !title.isBlank()) {
      String needle = title.replaceAll("\\s+", "");
      List<Kv> matched = new ArrayList<>();
      for (Kv modal : all) {
        String haystack = String.valueOf(modal.getStr("title")) + String.valueOf(modal.getStr("text"));
        if (haystack.replaceAll("\\s+", "").contains(needle)) {
          matched.add(modal);
        }
      }
      return matched;
    }
    // class:<子串>:很多站点的弹窗既没有标题也没有 role=dialog,只能靠类名认
    // (企业微信/微信系用 qui_dialog / ww_dialog / xxx_dialog_container 这类自有类名)
    if (which != null && which.startsWith("class:")) {
      String needle = which.substring("class:".length()).trim().toLowerCase(java.util.Locale.ROOT);
      if (needle.isEmpty()) {
        return new ArrayList<>();
      }
      List<Kv> matched = new ArrayList<>();
      for (Kv modal : all) {
        String className = String.valueOf(modal.getStr("className")).toLowerCase(java.util.Locale.ROOT);
        if (className.contains(needle)) {
          matched.add(modal);
        }
      }
      return matched;
    }
    if ("all".equals(which)) {
      return all;
    }
    if ("first".equals(which)) {
      return all.isEmpty() ? all : List.of(all.get(0));
    }
    return all.isEmpty() ? all : List.of(all.get(all.size() - 1));
  }

  /** 真实鼠标点弹窗里的一个按钮;成功返回点了什么 */
  @SuppressWarnings("unchecked")
  private static Kv clickModalButton(BrowserInstance inst, Kv modal, String button) {
    List<Kv> points = new ArrayList<>();
    Object rawButtons = modal.get("buttonPoints");
    if (rawButtons instanceof List) {
      for (Object item : (List<Object>) rawButtons) {
        if (item instanceof Map) {
          Kv entry = new Kv();
          entry.putAll((Map<?, ?>) item);
          points.add(entry);
        }
      }
    }
    Kv chosen = null;
    if (button != null && !button.isBlank()) {
      String wanted = button.replaceAll("\\s+", "");
      for (Kv entry : points) {
        if (wanted.equals(String.valueOf(entry.getStr("label")).replaceAll("\\s+", ""))) {
          chosen = entry;
          break;
        }
      }
      if (chosen == null) {
        // 指定的按钮不存在:退回默认策略,但把这件事说清楚
        chosen = defaultModalButton(modal, points);
      }
    } else {
      chosen = defaultModalButton(modal, points);
    }
    if (chosen == null) {
      return null;
    }
    Object point = chosen.get("point");
    if (!(point instanceof List) || ((List<Object>) point).size() < 2) {
      return null;
    }
    List<Object> coords = (List<Object>) point;
    double x = ((Number) coords.get(0)).doubleValue();
    double y = ((Number) coords.get(1)).doubleValue();
    try {
      // 关键:真实鼠标事件。JS 派发对这类按钮无效
      inst.page.mouse().click(x, y);
      inst.page.waitForTimeout(400);
    } catch (PlaywrightException e) {
      return Kv.by("label", chosen.getStr("label")).set("point", point)
          .set("error", briefMessage(e.getMessage()));
    }
    return Kv.by("label", chosen.getStr("label")).set("point", point).set("via", "mouse");
  }

  /**
   * 默认点哪个按钮:先右上角 ×,再「取消/关闭/知道了/我接受」这类**非提交**按钮
   *
   * <p>
   * 刻意把「确定/提交」排在最后:关弹窗的命令不该顺手把「删除」「提交」按下去。
   */
  @SuppressWarnings("unchecked")
  private static Kv defaultModalButton(Kv modal, List<Kv> points) {
    Object closePoint = modal.get("closePoint");
    if (closePoint instanceof List && ((List<Object>) closePoint).size() >= 2) {
      return Kv.by("label", "关闭按钮(×)").set("point", closePoint);
    }
    List<String> preferred = List.of("取消", "关闭", "知道了", "我接受", "取 消", "关 闭", "确定", "确 定");
    for (String name : preferred) {
      String wanted = name.replaceAll("\\s+", "");
      for (Kv entry : points) {
        if (wanted.equals(String.valueOf(entry.getStr("label")).replaceAll("\\s+", ""))) {
          return entry;
        }
      }
    }
    return null;
  }

  public RespBodyVo getConsoleLogs(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return RespBodyVo
        .ok(Kv.by("logs", new ArrayList<>(inst.consoleLogs)).set("errors", new ArrayList<>(inst.pageErrors)));
  }

  public RespBodyVo clearConsoleLogs(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    inst.consoleLogs.clear();
    inst.pageErrors.clear();
    return RespBodyVo.ok();
  }

  // ==================== 网络 ====================

  /**
   * action 取 abort(拦截)或 mock(返回自定义响应)
   *
   * <p>
   * 规则挂在**这个任务的页签**上(而不是共用的上下文上):共用用户 profile 之后所有任务在同一个
   * 浏览器里,挂上下文会让别的任务也被拦。弹窗、new_tab 出来的新页签会自动补上已有规则,
   * 见 {@link #applyRoutes(BrowserInstance, Page)}。
   */
  public RespBodyVo route(Long browserId, String urlPattern, String action, String body, Integer status,
      String contentType) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv rule = Kv.by("action", action == null || action.isEmpty() ? "abort" : action);
    rule.set("body", body);
    rule.set("status", status == null ? 200 : status);
    rule.set("contentType", contentType == null ? "application/json" : contentType);
    inst.routes.put(urlPattern, rule);

    if (inst.routedPatterns.add(urlPattern)) {
      for (Page page : pagesOf(inst)) {
        registerRoute(inst, page, urlPattern);
      }
    }
    return RespBodyVo.ok(Kv.by("urlPattern", urlPattern).set("action", rule.getStr("action")));
  }

  public RespBodyVo unroute(Long browserId, String urlPattern) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (urlPattern == null || urlPattern.isEmpty()) {
      for (Page page : pagesOf(inst)) {
        page.unrouteAll();
      }
      inst.routes.clear();
      inst.routedPatterns.clear();
    } else {
      inst.routes.remove(urlPattern);
      if (inst.routedPatterns.remove(urlPattern)) {
        for (Page page : pagesOf(inst)) {
          page.unroute(urlPattern);
        }
      }
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo getRequests(Long browserId, String filter) {
    return getRequests(browserId, filter, null, null, null);
  }

  /**
   * 读这个任务页签上记录到的请求
   *
   * <p>
   * <b>为什么以前会「什么都读不到」</b>:记录是挂在**页签**上的,而且是从页签被这个任务认领那一刻
   * 才开始记。所以「页面在 start 之前就加载完的请求」「发生在另一个页签里的请求」都不在这里,
   * 空结果并不代表记录坏了。现在回执里带上 {@code recordedSince}(从什么时候开始记)、{@code inflight}
   * (此刻还有几个在途请求)与一句 {@code note},空的时候一眼能看出是哪种情况。
   *
   * @param filter       按 URL 子串过滤
   * @param resourceType 按类型过滤(document/xhr/fetch/script/image…),可选
   * @param limit        最多返回多少条(取**最新**的 N 条),可选
   * @param since        只返回这个时间戳(毫秒)之后发起的请求,可选
   */
  public RespBodyVo getRequests(Long browserId, String filter, String resourceType, Integer limit, Long since) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<Kv> matched = filterRequests(inst, filter, resourceType, since);
    if (limit != null && limit > 0 && matched.size() > limit) {
      matched = new ArrayList<>(matched.subList(matched.size() - limit, matched.size()));
    }
    Kv data = Kv.by("requests", matched).set("count", matched.size()).set("total", inst.requests.size())
        .set("recordedSince", inst.recorderAttachedAt).set("inflight", inst.inflight.get())
        .set("filter", filter).set("resourceType", resourceType);
    if (matched.isEmpty()) {
      data.set("note", inst.recorderAttachedAt == 0
          ? "这个任务还没有认领页签,所以什么都没记到"
          : "没有匹配的请求。记录挂在页签上、且从认领那一刻开始:页面在 start 之前发出的请求、"
              + "以及发生在别的页签里的请求都不会出现在这里。先做一次动作(导航/点击)再读,"
              + "或检查 filter/resourceType 是否把结果过滤掉了");
    }
    return RespBodyVo.ok(data);
  }

  /** 按 URL 子串/类型/时间过滤请求记录(最多 200 条),都不传时返回全部 */
  private static List<Kv> filterRequests(BrowserInstance inst, String filter) {
    return filterRequests(inst, filter, null, null);
  }

  private static List<Kv> filterRequests(BrowserInstance inst, String filter, String resourceType, Long since) {
    List<Kv> all = new ArrayList<>(inst.requests);
    List<Kv> matched = new ArrayList<>();
    for (Kv kv : all) {
      if (filter != null && !filter.isEmpty()) {
        String url = kv.getStr("url");
        if (url == null || !url.contains(filter)) {
          continue;
        }
      }
      if (resourceType != null && !resourceType.isEmpty()) {
        String type = kv.getStr("resourceType");
        if (type == null || !type.equalsIgnoreCase(resourceType)) {
          continue;
        }
      }
      if (since != null) {
        Object at = kv.get("requestedAt");
        if (at instanceof Number && ((Number) at).longValue() < since) {
          continue;
        }
      }
      matched.add(kv);
    }
    return matched;
  }

  /**
   * 回看最近一个匹配的响应体
   *
   * <p>
   * 保留最近 100 个响应,filter 按 URL 子串过滤(不传取最近一个)。响应体已经被释放 (例如中间发生过跳转)时,data.bodyError
   * 里会说明原因。
   */
  public RespBodyVo getResponseBody(Long browserId, String filter, Integer index, Integer maxChars) {
    return getResponseBody(browserId, filter, index, maxChars, null);
  }

  public RespBodyVo getResponseBody(Long browserId, String filter, Integer index, Integer maxChars, String requestId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    List<BrowserInstance.RecordedResponse> matched = new ArrayList<>();
    for (BrowserInstance.RecordedResponse recorded : snapshotResponses(inst)) {
      if ((filter == null || filter.isEmpty() || recorded.response.url().contains(filter))
          && (requestId == null || requestId.equals(recorded.request.getStr("requestId")))) {
        matched.add(recorded);
      }
    }
    if (matched.isEmpty()) {
      return RespBodyVo.fail("get_response_body 没有匹配的响应: " + filter + "(只保留最近 100 个响应)");
    }
    int position = index == null ? matched.size() - 1 : index;
    if (position < 0 || position >= matched.size()) {
      return RespBodyVo.fail("get_response_body 索引越界: " + position + ",匹配到 " + matched.size() + " 个响应");
    }
    return RespBodyVo.ok(responseInfo(matched.get(position), maxChars));
  }

  private static List<BrowserInstance.RecordedResponse> snapshotResponses(BrowserInstance inst) {
    synchronized (inst.recentResponses) {
      return new ArrayList<>(inst.recentResponses);
    }
  }

  /**
   * 等一个网络响应并返回它的响应体
   *
   * <p>
   * SPA 页面的数据都在 XHR 里,而 get_requests 只有 url 和状态码,看不到返回内容。这个接口 等
   * urlPattern(Playwright 通配,如 `**&#47;api&#47;nsrxx&#47;query`)匹配的响应出现,然后直接把
   * 响应体给出来 —— 「点一下 → 等接口 → 读 JSON」一次调用完成。
   *
   * <p>
   * **先回看再等**:`lookBackSeconds`(默认 10 秒)内已经收到过的匹配响应会直接返回,并把 `data.ageMs`
   * 设成它距今的毫秒数。这样在批量里「点击 → wait_for_response」的顺序写法也能
   * 命中(响应往往在点击返回前就到了),不必并发对同一个实例发请求。
   *
   * <p>
   * `lookBackSeconds=0` 表示只等新响应。要看已经发生过的响应,用 get_response_body。
   */
  public RespBodyVo waitForResponse(Long browserId, String urlPattern, Double timeoutSeconds, Integer maxChars,
      Integer lookBackSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    int lookBack = lookBackSeconds == null ? DEFAULT_RESPONSE_LOOKBACK_SECONDS : lookBackSeconds;
    if (lookBack > 0) {
      long earliest = System.currentTimeMillis() - lookBack * 1_000L;
      BrowserInstance.RecordedResponse hit = null;
      for (BrowserInstance.RecordedResponse recorded : snapshotResponses(inst)) {
        if (recorded.at >= earliest && urlMatches(recorded.response.url(), urlPattern)) {
          hit = recorded;
        }
      }
      if (hit != null) {
        Kv kv = responseInfo(hit, maxChars);
        kv.set("ageMs", System.currentTimeMillis() - hit.at).set("fromLookBack", true);
        return RespBodyVo.ok(kv);
      }
    }
    Response response;
    try {
      // 用谓词而不是字符串重载:回看和等待共用同一套匹配,避免"回看命中但等待等不到"
      response = inst.page.waitForResponse(candidate -> urlMatches(candidate.url(), urlPattern),
          new Page.WaitForResponseOptions().setTimeout(timeoutMillis(timeoutSeconds)), () -> {
          });
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(waitFailure("wait_for_response", e));
    }
    BrowserInstance.RecordedResponse recorded = snapshotResponses(inst).stream()
        .filter(item -> item.response == response).findFirst()
        .orElseGet(() -> new BrowserInstance.RecordedResponse(response, System.currentTimeMillis(),
            requestInfo(response.request())));
    Kv kv = responseInfo(recorded, maxChars);
    kv.set("ageMs", 0).set("fromLookBack", false);
    return RespBodyVo.ok(kv);
  }

  /**
   * url 是否匹配 pattern:先按子串,再按通配
   *
   * <p>
   * 通配里的 `*` 可以跨 `/`(即 `**&#47;example.com*` 能匹配 `https://example.com/`)。 这一点比
   * Playwright 原生的通配宽松:原生把 `*` 当成"不含 / 的一串",`https://example.com/` 末尾那个 `/` 会让
   * `**&#47;example.com*` 匹配不上。回看和等待用同一套匹配,行为一致。
   */
  private static boolean urlMatches(String url, String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return true;
    }
    if (url.contains(pattern)) {
      return true;
    }
    try {
      String regex = globToRegex(pattern);
      if (url.matches(regex)) {
        return true;
      }
      // 模式没写尾部通配时,允许 URL 后面还有内容:实测页签 URL 常带 #fragment 或 ?query,
      // 写 **/smoke.html 却匹配不上 file:///.../smoke.html# 会很费解。
      return url.matches(regex + ".*");
    } catch (PatternSyntaxException e) {
      return false;
    }
  }

  /** 把通配转成正则:`*` 与 `**` 都表示任意字符(可跨 `/`),其余正则元字符按字面处理 */
  private static String globToRegex(String glob) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        sb.append(".*");
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          i++;
        }
      } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
        sb.append('\\').append(c);
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static Kv responseInfo(BrowserInstance.RecordedResponse recorded, Integer maxChars) {
    Kv kv = responseInfo(recorded.response, maxChars);
    kv.set("requestId", recorded.request.get("requestId")).set("request", recorded.request)
        .set("respondedAt", recorded.at).set("ageMs", Math.max(0, System.currentTimeMillis() - recorded.at));
    return kv;
  }

  private static Kv responseInfo(Response response, Integer maxChars) {
    int limit = maxChars == null || maxChars <= 0 ? DEFAULT_RESPONSE_BODY_CHARS : maxChars;
    Kv kv = Kv.by("url", response.url()).set("status", response.status());
    String body = null;
    try {
      body = response.text();
    } catch (PlaywrightException e) {
      kv.set("bodyError", briefMessage(e.getMessage())).set("bodyAvailable", false);
    }
    if (body != null) {
      kv.set("body", truncate(body, limit));
      kv.set("bodyLength", body.length()).set("truncated", body.length() > limit).set("bodyAvailable", true);
    }
    return kv;
  }

  // ==================== 页面状态汇总 ====================

  /**
   * 一次拿到「页面现在是什么状态」需要的全部信息
   *
   * <p>
   * 替代 get_url + get_title + get_tabs + get_dialog + get_console_logs +
   * get_requests 六次调用。 不返回 DOM 快照文本(那是 get_browser_state 的活),只做状态汇总。
   *
   * <p>
   * 返回 data.url、data.title、data.tabs、data.dialog(带 seq/timestamp)、data.loading、
   * data.logs /
   * data.errors(includeConsole=true)、data.requests(includeRequests=true)。
   */
  public RespBodyVo getPageSnapshot(Long browserId, Boolean includeConsole, Boolean includeRequests,
      String requestFilter) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv kv = Kv.by("url", inst.page.url()).set("title", safeTitle(inst.page)).set("tabs", tabs(inst))
        .set("dialog", inst.lastDialog).set("loading", isPageLoading(inst));
    if (includeConsole != null && includeConsole) {
      kv.set("logs", new ArrayList<>(inst.consoleLogs));
      kv.set("errors", new ArrayList<>(inst.pageErrors));
    }
    if (includeRequests != null && includeRequests) {
      kv.set("requests", filterRequests(inst, requestFilter));
    }
    return RespBodyVo.ok(kv);
  }

  /** 页面是否还在加载(document.readyState !== 'complete') */
  private static boolean isPageLoading(BrowserInstance inst) {
    try {
      Object state = inst.page.evaluate("() => document.readyState");
      return state != null && !"complete".equals(String.valueOf(state));
    } catch (PlaywrightException e) {
      return false;
    }
  }

  // ==================== 快照差异与元素定位信息 ====================

  /**
   * 与上一次快照比较,返回新增/消失的行
   *
   * <p>
   * 会重新执行一次 buildDomTree(和 get_browser_state 一样),再和上一次的文本按行做多重集差集。
   * 判断「刚才那一下到底有没有让页面变化」比重新读整页省 token:data.changed=false 就说明 快照内容一模一样。
   *
   * <p>
   * 返回 data.changed、data.first(第一次快照没有可比对象)、data.added、data.removed、
   * data.url、data.title、data.tabs;added/removed 各最多 200 行。
   */
  public RespBodyVo diffDomText(Long browserId, Boolean highlight, Integer viewportExpansion) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    String previous = inst.lastDomText;
    RespBodyVo current = buildState(inst, highlight, viewportExpansion);
    if (!current.isOk() || !(current.getData() instanceof Kv)) {
      return current;
    }
    Kv data = (Kv) current.getData();
    String text = data.getStr("text");
    Kv diff = Kv.by("url", data.get("url")).set("title", data.get("title")).set("tabs", data.get("tabs"));
    if (previous == null) {
      diff.set("first", true).set("changed", true).set("added", firstLines(text)).set("removed", new ArrayList<>());
      return RespBodyVo.ok(diff);
    }
    Map<String, Integer> before = countLines(previous);
    Map<String, Integer> after = countLines(text);
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : after.entrySet()) {
      int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
      for (int i = 0; i < delta && added.size() < MAX_DIFF_LINES; i++) {
        added.add(entry.getKey());
      }
    }
    for (Map.Entry<String, Integer> entry : before.entrySet()) {
      int delta = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
      for (int i = 0; i < delta && removed.size() < MAX_DIFF_LINES; i++) {
        removed.add(entry.getKey());
      }
    }
    diff.set("first", false).set("changed", !added.isEmpty() || !removed.isEmpty());
    diff.set("added", added).set("removed", removed);
    return RespBodyVo.ok(diff);
  }

  private static Map<String, Integer> countLines(String text) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (text == null) {
      return counts;
    }
    for (String line : text.split("\n")) {
      counts.merge(line, 1, Integer::sum);
    }
    return counts;
  }

  private static List<String> firstLines(String text) {
    List<String> lines = new ArrayList<>();
    if (text == null) {
      return lines;
    }
    for (String line : text.split("\n")) {
      if (lines.size() >= MAX_DIFF_LINES) {
        break;
      }
      lines.add(line);
    }
    return lines;
  }

  /**
   * 返回「索引 → 元素定位信息」的映射
   *
   * <p>
   * 快照文本里只有语义属性,没有 id/class/href,想拿这些以前只能上 execute_js。这个接口按 当前快照的 xpath
   * 一次回查所有元素,给出 index、tag、xpath、id、className、href、name、text。
   *
   * <p>
   * 索引仍然来自最近一次 get_browser_state;没有快照时直接报错。
   */
  public RespBodyVo getInteractiveMap(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (inst.domState == null) {
      // 以前这里直接失败,要求调用方「先调 get_browser_state」——多一次往返,而且很容易漏。
      // 索引本来就是本命令的产物,自己建一次快照即可(不落盘,不产生截图/文本垃圾文件)。
      RespBodyVo built = buildState(inst, false, 0);
      if (!built.isOk()) {
        return built;
      }
    }
    List<Kv> items = interactiveElements(inst);
    return RespBodyVo.ok(Kv.by("count", items.size()).set("elements", items)
        .set("source", "get_browser_state 快照(索引与 click_element_by_index / input_text / "
            + "check_element_by_index 等按索引命令共用)"));
  }

  /**
   * 当前快照里的可交互元素清单
   *
   * <p>
   * 索引型命令(click_element_by_index、input_text、check_element_by_index、get_element_text …)靠的就是
   * 这份清单里的 {@code index};以前只有 {@code get_interactive_map} 能拿到它,而 {@code get_browser_state}
   * 只回一棵文本树,调用方很容易以为索引 API 不可用(实测踩过:整场任务全部退回手写 execute_js)。
   */
  private static List<Kv> interactiveElements(BrowserInstance inst) {
    DOMState state = inst.domState;
    List<Kv> items = new ArrayList<>();
    if (state == null || state.getSelectorMap() == null) {
      return items;
    }
    List<Integer> indices = new ArrayList<>(state.getSelectorMap().keySet());
    indices.sort(null);

    // 一批 xpath 一次求值(每个元素一次 evaluate 会把往返数乘以元素数)。带 frame 的快照必须**按 frame 分组**:
    // xpath 只能在它自己的文档里求值,在主文档里 document.evaluate 找不到 iframe 内部的节点
    Map<Integer, List<Integer>> byFrame = new LinkedHashMap<>();
    for (Integer index : indices) {
      FrameSnapshot snapshot = state.frameOf(index);
      int ordinal = snapshot == null ? -1 : snapshot.index;
      byFrame.computeIfAbsent(ordinal, key -> new ArrayList<>()).add(index);
    }

    Map<Integer, Object> resolved = new LinkedHashMap<>();
    for (Map.Entry<Integer, List<Integer>> group : byFrame.entrySet()) {
      List<Integer> groupIndices = group.getValue();
      List<String> xpaths = new ArrayList<>(groupIndices.size());
      for (Integer index : groupIndices) {
        DOMElementNode node = state.getSelectorMap().get(index);
      xpaths.add("/" + (node == null ? "" : node.getXpath()));
      }
      Frame root = null;
      if (group.getKey() != null && group.getKey() >= 0) {
        FrameSnapshot snapshot = state.getFrames().get(group.getKey());
        root = findFrameByUrl(inst, snapshot.url, snapshot.name);
        if (root == null && snapshot.frame != null && !snapshot.frame.isDetached()) {
          root = snapshot.frame;
        }
      }
      Object raw;
      try {
        raw = (root == null ? inst.page.mainFrame() : root).evaluate(ELEMENTS_PROBE, xpaths);
      } catch (PlaywrightException e) {
        continue;
      }
    List<?> list = raw instanceof List ? (List<?>) raw : new ArrayList<>();
      for (int i = 0; i < groupIndices.size(); i++) {
        resolved.put(groupIndices.get(i), i < list.size() ? list.get(i) : null);
      }
    }

    for (Integer index : indices) {
      DOMElementNode node = state.getSelectorMap().get(index);
      Kv item = Kv.by("index", index);
      Object entry = resolved.get(index);
      if (entry instanceof Map) {
        item.set((Map<?, ?>) entry);
      }
      if (node != null) {
        item.set("xpath", node.getXpath());
      }
      FrameSnapshot snapshot = state.frameOf(index);
      if (snapshot != null) {
        item.set("frameIndex", snapshot.index).set("frameUrl", snapshot.url);
      }
      items.add(item);
    }
    return items;
  }

  /**
   * 一次回查一批元素的属性 + 监听器情况
   *
   * <p>
   * 顺手把「这个元素有没有挂事件监听器」带上(见 {@link ListenerProbe#probe}):成本只是一次 evaluate 里的几行
   * 属性检查,却能让「哪些元素是死的」一眼可见 —— 实测最贵的那个坑(upload_file 报成功、页面没生效)就卡在
   * 「这个 input 根本没人监听」上,而当时只能靠手写 execute_js 一点点摸。
   */
  private static final String ELEMENTS_PROBE = "(list) => {"
      + " const probe = " + ListenerProbe.FRAMEWORK_TRACES + ";"
      + " return list.map(xpath => {"
      + " const r = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);"
      + " const e = r.singleNodeValue; if (!e) return null;"
      + " const rect = e.getBoundingClientRect();"
      + " const info = probe(e);"
      + " const traces = info && info.traces ? info.traces : [];"
      + " return { tag: e.tagName, id: e.id || null,"
      + " className: typeof e.className === 'string' ? e.className : null,"
      + " href: e.getAttribute ? e.getAttribute('href') : null,"
      + " name: e.getAttribute ? e.getAttribute('name') : null,"
      + " type: e.getAttribute ? e.getAttribute('type') : null,"
      + " value: (e.value === undefined ? null : String(e.value).slice(0, 120)),"
      + " checked: (e.checked === undefined ? null : !!e.checked),"
      // 批量路径上不能对每个元素都开一次 CDP 会话,所以只能说「有痕迹」或「未知」——
      // 绝不把「没探到」写成 false(那是假阴性,正是这个坑最危险的地方)
      + " hasListeners: traces.length > 0 ? true : null,"
      + " listeners: traces,"
      + " listenerDetection: 'heuristic',"
      + " visible: rect.width > 0 && rect.height > 0,"
      + " rect: [Math.round(rect.left), Math.round(rect.top), Math.round(rect.width), Math.round(rect.height)],"
      + " text: (e.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 120) }; }); }";

  // ==================== 读图(OCR) ====================

  /**
   * 用本机 OCR 读图上的文字
   *
   * <p>
   * <b>为什么要有这条命令</b>:有些模型不支持图片输入({@code read_image} 直接报「does not declare image
   * input」),于是「必须看图」的环节(验证码、二维码、公告维护图)全都断了。以前只能靠一句
   * 「用 Windows 自带 OCR」在每个任务 skill 里各写一遍,换个人写就重踩一遍。这里把它固化下来:
   *
   * <ul>
   * <li>{@code path}:直接读服务端上已有的一张图(截图、上传的图样都行);</li>
   * <li>{@code index} / {@code selector}:先在页面上截这个元素,再读它(验证码最常用)。</li>
   * </ul>
   *
   * <p>
   * 走的是 Windows 自带的 {@code Windows.Media.Ocr},不需要装任何东西,也不需要联网。识别中文需要系统装了
   * 对应语言包;没装时会明确说「本机没有可用的 OCR 语言包」并列出已装的语言,而不是给一段乱码。
   *
   * @param path     图片路径(服务端本地路径);与 index/selector 三选一
   * @param index    元素索引
   * @param selector 元素选择器
   * @param frame    元素在跨域 iframe 里时传
   * @param language OCR 语言,默认 {@code zh-Hans-CN}
   */
  public RespBodyVo ocrImage(Long browserId, String path, Integer index, String selector, String frame,
      String language) {
    Path image = null;
    String imageUrl = null;
    String target;
    if (path != null && !path.isBlank()) {
      image = Paths.get(path).toAbsolutePath().normalize();
      target = "path=" + path;
      BrowserInstance inst = INSTANCES.get(browserId);
      if (inst != null) {
        // 落盘在 data/<id>/ 下的图可以直接 GET,贴给用户看
        imageUrl = shotUrl(inst, image.toString());
      }
    } else if (index != null || (selector != null && !selector.isBlank())) {
      BrowserInstance inst = INSTANCES.get(browserId);
      if (inst == null) {
        return notFound(browserId);
      }
      String shotPath = defaultShotPath(inst);
      RespBodyVo shot = elementScreenshot(inst, index, selector, shotPath, Boolean.FALSE, frame);
      if (!shot.isOk()) {
        return shot;
      }
      Kv shotData = shot.getData() instanceof Kv ? (Kv) shot.getData() : new Kv();
      image = shotData.getStr("path") == null ? null : Paths.get(shotData.getStr("path"));
      imageUrl = shotData.getStr("url");
      target = String.valueOf(shotData.get("target"));
    } else {
      return RespBodyVo.fail("ocr_image 需要 path,或 index / selector 之一");
    }
    if (image == null || !Files.isRegularFile(image)) {
      return RespBodyVo.fail("ocr_image 找不到图片：" + image);
    }
    Kv read = WindowsOcr.read(image, language);
    Kv data = new Kv();
    data.putAll(read);
    data.set("target", target).set("imagePath", image.toAbsolutePath().toString());
    if (imageUrl != null) {
      data.set("imageUrl", imageUrl);
    }
    if (!Boolean.TRUE.equals(read.get("ok"))) {
      data.set("ocrSupported", WindowsOcr.available());
    }
    return RespBodyVo.ok(data);
  }

  // ==================== 人机协同 ====================

  /**
   * 发起一个人工介入请求
   *
   * <p>
   * 验证码、短信码、人工登录这类环节,智能体既读不了图也拿不到凭证,只能请人来做。这个接口 把「请人」这件事固定下来:
   *
   * <ol>
   * <li>request_human_input 建一个待办,可选把某个元素(验证码图)截成 base64 + 落盘路径一起返回,并把当前页签带到最前</li>
   * <li>人看到图和页面后,把答案用 submit_human_input 提交(或者直接在有头浏览器里自己操作完)</li>
   * <li>智能体用 get_human_input 取答案;带 timeoutSeconds 时可以当长轮询用</li>
   * </ol>
   *
   * <p>
   * <b>读不了图的模型怎么办</b>:只回 {@code imageBase64} 对「不支持图片输入」的模型仍然没用。所以这里同时回
   * {@code imagePath}(服务端本地路径)与 {@code imageUrl}(可直接 GET 的地址,能贴给用户),并在 {@code ocr:true}
   * 时用**本机 OCR**(Windows 自带 {@code Windows.Media.Ocr})把图上的文字一并读出来 —— 「验证码是什么」
   * 这类问题读不了图的模型也能自己答一部分。
   *
   * @param prompt         要人做什么(不传 steps 时必填)
   * @param index          要截图的元素索引(可选)
   * @param selector       要截图的元素选择器(可选)
   * @param timeoutSeconds 等待时长(秒),默认 300
   * @param steps          一次带多个待办:{@code [{prompt, index?, selector?}, ...]},人一次做完,少几次往返
   * @param expiresAt      绝对过期时刻(毫秒时间戳):二维码这类**有短时效**的场景用它,比 timeoutSeconds 直白,
   *                       并且过期后会明确回 {@code status:"expired"} 而不是让人干等
   * @param ocr            是否用本机 OCR 读图上的文字,默认 false
   * @param ocrLanguage    OCR 语言,默认 {@code zh-Hans-CN}
   * @param inline         是否内联 base64,默认 true(要省 token 可以关掉,只用 imagePath/imageUrl)
   */
  public RespBodyVo requestHumanInput(Long browserId, String prompt, Integer index, String selector,
      Integer timeoutSeconds, List<Kv> steps, Long expiresAt, Boolean ocr, String ocrLanguage, Boolean inline) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if ((prompt == null || prompt.isBlank()) && (steps == null || steps.isEmpty())) {
      return RespBodyVo.fail("request_human_input 需要 prompt,或用 steps 给出待办清单");
    }
    long now = System.currentTimeMillis();
    long deadline;
    if (expiresAt != null && expiresAt > 0) {
      deadline = expiresAt;
    } else {
    int ttl = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_HUMAN_TIMEOUT_SECONDS : timeoutSeconds;
      deadline = now + ttl * 1_000L;
    }
    String requestId = "hr-" + inst.humanSeq.incrementAndGet() + "-" + SnowflakeIdUtils.id();
    Kv request = Kv.by("requestId", requestId).set("prompt", prompt).set("status", "pending").set("answer", null)
        .set("createdAt", now).set("expiresAt", deadline);
    inst.humanRequests.put(requestId, request);
    // 需要人工介入时把页面带到最前,人才能直接看到验证码/表单
    activate(inst.page);
    Kv data = Kv.by("requestId", requestId).set("prompt", prompt).set("expiresAt", deadline)
        .set("expiresInSeconds", Math.max(0, (deadline - now) / 1000)).set("url", inst.page.url());
    if (deadline <= now) {
      // 过期时刻已经过去了:直接说清楚,别让人等一个永远不会来的答复
      request.set("status", "expired");
      data.set("status", "expired").set("note", "expiresAt 已经过去了,这个请求没有生效;请重新发起");
      return RespBodyVo.ok(data);
    }

    boolean wantOcr = Boolean.TRUE.equals(ocr);
    boolean wantInline = inline == null || inline;
    // 第一个要看图的目标:老写法(index/selector)或 steps 里第一个带目标的步骤
    Integer shotIndex = index;
    String shotSelector = selector;
    String shotPrompt = prompt;
    if (steps != null && !steps.isEmpty()) {
      List<Kv> items = new ArrayList<>();
      int seq = 0;
      for (Kv step : steps) {
        String stepId = "s" + (++seq);
        Kv item = Kv.by("stepId", stepId).set("prompt", step.getStr("prompt")).set("status", "pending")
            .set("answer", null);
        if (step.get("index") != null) {
          item.set("index", step.get("index"));
        }
        if (step.getStr("selector") != null) {
          item.set("selector", step.getStr("selector"));
        }
        items.add(item);
      }
      request.set("steps", items).set("status", "pending");
      data.set("steps", items).set("stepCount", items.size());
      if (shotPrompt == null || shotPrompt.isBlank()) {
        shotPrompt = steps.get(0).getStr("prompt");
      }
      if (shotIndex == null && (shotSelector == null || shotSelector.isBlank())) {
        Object stepIndex = steps.get(0).get("index");
        String stepSelector = steps.get(0).getStr("selector");
        shotIndex = stepIndex instanceof Number ? ((Number) stepIndex).intValue() : null;
        shotSelector = stepSelector;
      }
      data.set("note", "这是一次带多步待办的请求:让人一次做完,再用 submit_human_input 按 stepId 逐个回填;"
          + "全部回填后 get_human_input 的 status 会变成 answered");
    }

    if (shotIndex != null || (shotSelector != null && !shotSelector.isEmpty())) {
      // 请人看验证码/二维码是「确实必须看图」的场景:落盘 + 内联 + 可 GET 的 URL 一起给,
      // 读不了图的模型至少还能把 imageUrl 贴给用户,或走 OCR
      String path = defaultShotPath(inst);
      RespBodyVo shot = elementScreenshot(inst, shotIndex, shotSelector, path, wantInline);
      if (shot.isOk() && shot.getData() instanceof Kv) {
        Kv shotData = (Kv) shot.getData();
        if (wantInline) {
          data.set("imageBase64", shotData.getStr("base64"));
        }
        data.set("imageSize", shotData.get("size"))
            .set("imagePath", shotData.getStr("path"))
            .set("imageUrl", shotData.getStr("url"));
      } else {
        data.set("imageError", shot.getMsg());
      }
    }

    if (wantOcr) {
      String imagePath = data.getStr("imagePath");
      if (imagePath == null || imagePath.isEmpty()) {
        data.set("ocrError", "ocr:true 需要 index / selector(或 steps 里的第一个带目标的步骤)来指定要读的图");
      } else {
        Kv read = WindowsOcr.read(Paths.get(imagePath), ocrLanguage);
        data.set("ocr", read);
        if (Boolean.TRUE.equals(read.get("ok"))) {
          data.set("ocrText", read.getStr("text"));
        } else {
          data.set("ocrError", read.getStr("error"));
        }
      }
    }
    return RespBodyVo.ok(data);
  }

  /** 提交人工答复,返回 data.status 与 data.answer */
  public RespBodyVo submitHumanInput(Long browserId, String requestId, String answer) {
    return submitHumanInput(browserId, requestId, answer, null, null);
  }

  /**
   * 同上,支持按 {@code stepId} 逐个回填多步待办
   *
   * @param stepId  只回填某一步;为空时回填整条请求(answers 优先)
   * @param answers {@code {stepId: answer}} 一次回填多步
   */
  public RespBodyVo submitHumanInput(Long browserId, String requestId, String answer, String stepId,
      JSONObject answers) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv request = inst.humanRequests.get(requestId);
    if (request == null) {
      return RespBodyVo.fail("submit_human_input 没有这个请求: " + requestId);
    }
    long now = System.currentTimeMillis();
    List<Kv> steps = stepList(request);
    // 多步待办:必须按 stepId / answers 逐条回填 —— 直接给一个 answer 说不清它对应哪一步,静默接受
    // 只会让剩下的步骤一直挂在 pending 上,所以这里明确报错并给出待办清单
    if (!steps.isEmpty()) {
      Map<String, String> filled = new LinkedHashMap<>();
      if (answers != null) {
        for (String key : answers.keySet()) {
          filled.put(key, answers.getString(key));
        }
      }
      if (stepId != null) {
        filled.put(stepId, answer);
      }
      if (filled.isEmpty()) {
        List<String> pendingIds = new ArrayList<>();
        for (Kv step : steps) {
          pendingIds.add(step.getStr("stepId") + "(" + step.getStr("prompt") + ")");
        }
        return RespBodyVo.fail("submit_human_input 这条请求有 " + steps.size() + " 个待办步骤,"
            + "请用 stepId 指定回填哪一步,或用 answers 一次回填多步。待办:" + String.join(" / ", pendingIds));
      }
      List<String> unknown = new ArrayList<>();
      for (Map.Entry<String, String> entry : filled.entrySet()) {
        Kv target = null;
        for (Kv step : steps) {
          if (entry.getKey().equals(step.getStr("stepId"))) {
            target = step;
            break;
          }
        }
        if (target == null) {
          unknown.add(entry.getKey());
          continue;
        }
        target.set("answer", entry.getValue()).set("status", "answered").set("answeredAt", now);
      }
      if (!unknown.isEmpty()) {
        return RespBodyVo.fail("submit_human_input 不认识的 stepId: " + String.join(" / ", unknown));
      }
      boolean allDone = true;
      List<Kv> pendingSteps = new ArrayList<>();
      for (Kv step : steps) {
        if (!"answered".equals(step.getStr("status"))) {
          allDone = false;
          pendingSteps.add(Kv.by("stepId", step.getStr("stepId")).set("prompt", step.getStr("prompt")));
        }
      }
      if (allDone) {
        request.set("status", "answered").set("answeredAt", now);
      }
      Kv data = Kv.by("requestId", requestId)
          .set("status", allDone ? "answered" : "partial").set("steps", steps);
      if (!allDone) {
        data.set("pendingSteps", pendingSteps)
            .set("note", "还有 " + pendingSteps.size() + " 步没回填;全部回填后 status 才是 answered");
      }
      return RespBodyVo.ok(data);
    }
    if (answer == null) {
      return RespBodyVo.fail("submit_human_input 需要 answer(或用 stepId / answers 逐条回填)");
    }
    request.set("answer", answer).set("status", "answered").set("answeredAt", now);
    return RespBodyVo.ok(Kv.by("requestId", requestId).set("status", "answered").set("answer", answer));
  }

  /** 把 Kv 里存的 steps 读成 List<Kv>(存进去的是 Kv,取出来还是 Kv) */
  @SuppressWarnings("unchecked")
  private static List<Kv> stepList(Kv request) {
    Object raw = request.get("steps");
    if (!(raw instanceof List)) {
      return new ArrayList<>();
    }
    List<Kv> steps = new ArrayList<>();
    for (Object item : (List<Object>) raw) {
      if (item instanceof Kv) {
        steps.add((Kv) item);
      } else if (item instanceof Map) {
        Kv step = new Kv();
        step.putAll((Map<?, ?>) item);
        steps.add(step);
      }
    }
    return steps;
  }

  /**
   * 取人工答复
   *
   * <p>
   * data.status 为 pending / answered / expired。传 timeoutSeconds 时长轮询等待答复,到时间
   * 还没答复就返回当前状态(不算失败)。人在浏览器里自己把事情做完了、始终没提交答复时,这里 会一直是 pending 直到过期,智能体可以直接继续后续步骤。
   *
   * <p>
   * <b>过期是显式的</b>:二维码这类有短时效的场景,等到超时才知道「早就过期了」是纯浪费。所以过期时回执里
   * 直接给 {@code expired:true} 与一句可操作的提示,而不是让人继续等。
   */
  public RespBodyVo getHumanInput(Long browserId, String requestId, Integer timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv request = inst.humanRequests.get(requestId);
    if (request == null) {
      return RespBodyVo.fail("get_human_input 没有这个请求: " + requestId);
    }
    long deadline = timeoutSeconds == null || timeoutSeconds <= 0 ? 0
        : System.currentTimeMillis() + timeoutSeconds * 1_000L;
    while ("pending".equals(humanStatus(request)) && System.currentTimeMillis() < deadline) {
      sleepQuietly(500);
    }
    String status = humanStatus(request);
    Kv data = Kv.by("requestId", requestId).set("status", status).set("answer", request.get("answer"))
        .set("prompt", request.get("prompt")).set("expiresAt", request.get("expiresAt"));
    List<Kv> steps = stepList(request);
    if (!steps.isEmpty()) {
      data.set("steps", steps);
      boolean anyAnswered = false;
      for (Kv step : steps) {
        if ("answered".equals(step.getStr("status"))) {
          anyAnswered = true;
          break;
        }
      }
      if (anyAnswered && "pending".equals(status)) {
        data.set("status", "partial");
        status = "partial";
      }
    }
    if ("expired".equals(status)) {
      data.set("expired", true)
          .set("hint", "这个人工请求已经过期:二维码/短信码这类有短时效的凭证通常也已经失效,"
              + "请重新发起 request_human_input(可以用 expiresAt 明确告诉它什么时候过期)");
    }
    return RespBodyVo.ok(data);
  }

  /** pending 且已过 expiresAt 时算 expired */
  private static String humanStatus(Kv request) {
    Object status = request.get("status");
    Object expiresAt = request.get("expiresAt");
    if ("pending".equals(status) && expiresAt instanceof Number
        && System.currentTimeMillis() > ((Number) expiresAt).longValue()) {
      return "expired";
    }
    return String.valueOf(status);
  }

  /**
   * 结束一个任务
   *
   * <p>
   * 只关掉这个任务自己的页签:浏览器、profile 与 Playwright driver 都是共用的,关掉会连累别的任务。
   * 最后一个任务关闭时才把浏览器一起收掉(用户 profile 的占用也随之释放)。
   */
  public RespBodyVo close(Long browserId) {
    BrowserInstance inst = INSTANCES.remove(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    inst.detached = true;
    for (Page page : pagesOf(inst)) {
      try {
        page.close();
      } catch (RuntimeException e) {
        // 页签可能已经因为浏览器崩溃而失效,这里不该让 close 变成失败
        log.warn("关闭任务 {} 的页签失败:{}", browserId, e.getMessage());
      }
    }
    inst.pages.clear();
    if (INSTANCES.isEmpty()) {
      closeSharedBrowser();
    }
    // 注意:这里**不关** Playwright。它是全进程共享的 driver,关掉会把其它任务的浏览器一起搞死;
    // 它由 JVM 退出时的 shutdown hook 负责收尾。
    return RespBodyVo.ok();
  }

  /**
   * 关掉共享浏览器(最后一个任务关闭、或者要重建上下文时调用)
   *
   * <p>
   * 用用户 profile 时浏览器是我们自己拉起来的,得连进程一起收掉,否则用户下次打开 Chrome 会被告知
   * 「Chrome 未正常关闭」;{@code browser.close()} 会通过 CDP 让 Chrome 自己优雅退出,
   * {@link ChromeLauncher#stop(Process)} 只是兜底。
   */
  private static void closeSharedBrowser() {
    SharedBrowser browser;
    synchronized (PlaywrightService.class) {
      browser = sharedBrowser;
      // 先置空再 close:onClose 回调里也会置空,但它不抢锁,顺序反了也没关系
      sharedBrowser = null;
    }
    if (browser == null || (browser.context == null && browser.process == null)) {
      return;
    }
    try {
      if (browser.browser != null) {
        browser.browser.close();
      } else if (browser.context != null) {
        browser.context.close();
      }
      log.info("共享浏览器已关闭(profile:{})", browser.profileDir);
    } catch (RuntimeException e) {
      log.warn("关闭共享浏览器失败:{}", e.getMessage());
    } finally {
      ChromeLauncher.stop(browser.process);
    }
  }

  /**
   * 重建共享浏览器
   *
   * <p>
   * 用在 {@code set_credentials} 这类只能在上文创建时生效的设置上(HTTP 基本认证、代理)。因为浏览器
   * 是所有任务共用的,重建会换掉整个 Chrome 进程,所以只允许在「只有这一个任务」时调用。
   */
  public BrowserInstance restartContext(BrowserInstance instance) {
    SharedBrowser browser = sharedBrowser;
    if (browser == null) {
      return instance;
    }
    // 重建前后都要保住这个任务的页签归属:先摘掉,重建完再认领一个新页签
    instance.detached = true;
    instance.pages.clear();
    closeSharedBrowser();
    SharedBrowser fresh;
    synchronized (PlaywrightService.class) {
      fresh = launch(browser.executable, browser.profileDir, browser.userProfile, browser.headless, browser.profileNote,
          browser.opts, browser.resolvedType);
      sharedBrowser = fresh;
    }
    instance.detached = false;
    instance.context = fresh.context;
    instance.profileDir = fresh.profileDir;
    instance.opts = fresh.opts;
    Page page = unclaimedPage(fresh);
    if (page == null) {
      page = fresh.context.newPage();
    }
    instance.page = page;
    claimPage(instance, page);
    log.info("任务 {} 的浏览器上下文已重建:{}", instance.id, fresh.context);
    return instance;
  }

}
