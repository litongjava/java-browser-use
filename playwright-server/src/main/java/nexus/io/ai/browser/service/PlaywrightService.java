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
import nexus.io.ai.browser.dom.service.DomService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
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

  /** 等待类接口的默认超时(毫秒) */
  private static final double DEFAULT_WAIT_TIMEOUT_MS = 30_000;

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
    SharedBrowser shared = sharedBrowser(headless, requested);
    BrowserInstance instance = newTaskInstance(taskId, shared);
    INSTANCES.put(taskId, instance);
    log.info("task {} 浏览器就绪(browser={}),耗时 {}ms", taskId, shared.resolvedType.id(),
        System.currentTimeMillis() - startedAt);
    return taskId;
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
    return info;
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
    try {
      return playwrightType(engine).launchPersistentContext(profileDir, opts);
    } catch (RuntimeException first) {
      log.warn("启动浏览器失败,重建共享 Playwright 后重试一次:{}", first.getMessage());
      discardPlaywright();
      return playwrightType(engine).launchPersistentContext(profileDir, opts);
    }
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
    page.onRequest(request -> {
      Kv entry = requestInfo(request);
      addBoundedRequest(inst.requests, entry);
      inst.requestIndex.put(request, entry);
    });
    page.onResponse(response -> {
      Kv entry = inst.requestIndex.remove(response.request());
      if (entry != null) {
        entry.set("status", response.status()).set("respondedAt", System.currentTimeMillis());
      }
      rememberResponse(inst, response, entry == null ? requestInfo(response.request()) : entry);
    });
    page.onRequestFailed(request -> {
      Kv entry = inst.requestIndex.remove(request);
      if (entry != null)
        entry.set("failure", request.failure()).set("finishedAt", System.currentTimeMillis());
    });
  }

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
        .set("method", request.method()).set("url", request.url()).set("resourceType", request.resourceType())
        .set("status", null);
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    boolean doHighlight = highlight == null || highlight;
    int expansion = viewportExpansion == null ? 0 : viewportExpansion;

    DOMState state;
    try {
      state = DomService.getClickableElements(inst.page, doHighlight, -1, expansion);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("构建页面结构失败：" + briefMessage(e.getMessage()));
    }
    inst.domState = state;

    Kv kv = Kv.by("url", inst.page.url());
    String text = state.getElementTree().clickableElementsToString(null);
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
    inst.domState = state;
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
      return inst.page.locator("xpath=/" + node.getXpath()).first();
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

  /** 索引越界时补充说明,提醒客户端索引应当来自 get_browser_state */
  private static String indexHint(BrowserInstance inst) {
    return inst.domState == null ? ",当前没有页面快照,请先调用 get_browser_state 获取元素索引" : "";
  }

  private static RespBodyVo notFound(Long browserId) {
    return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return indexAction(inst, index, action, null, consumer, withReceipt);
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
    locator.fill(value, new Locator.FillOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
  }

  private static Locator.ClickOptions clickOptions() {
    return new Locator.ClickOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS);
  }

  // ==================== 点击回执与索引失效重试 ====================

  /** 动作前后探针:url、页签数、正文长度,用于观察变化，不能证明业务成功或失败 */
  private static Kv stateProbe(BrowserInstance inst) {
    Kv probe = Kv.by("url", inst.page.url()).set("tabCount", pagesOf(inst).size());
    try {
      Object fingerprint = inst.page.evaluate("""
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
        || (before.containsKey("fingerprint") && after.containsKey("fingerprint")
            && !java.util.Objects.equals(before.get("fingerprint"), after.get("fingerprint")));
  }

  private static Kv observeAfter(Kv before, BrowserInstance inst) {
    Kv after = stateProbe(inst);
    long deadline = System.nanoTime() + 500_000_000L;
    while (!probeChanged(before, after) && !after.containsKey("probeError") && System.nanoTime() < deadline) {
      // Pump Playwright events while waiting, allowing delayed popups and framework
      // updates to arrive.
      inst.page.waitForTimeout(50);
      after = stateProbe(inst);
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
    Kv after = observeAfter(before, inst);
    String urlBefore = String.valueOf(before.get("url"));
    String urlAfter = String.valueOf(after.get("url"));
    int tabBefore = asInt(before.get("tabCount"));
    int tabAfter = asInt(after.get("tabCount"));
    int lenBefore = asInt(before.get("textLength"));
    int lenAfter = asInt(after.get("textLength"));
    boolean changed = probeChanged(before, after);
    return Kv.by("urlBefore", urlBefore).set("urlAfter", urlAfter).set("tabCountBefore", tabBefore)
        .set("tabCountAfter", tabAfter).set("textLengthBefore", lenBefore).set("textLengthAfter", lenAfter)
        .set("changed", changed).set("changeStatus", changed ? "observed" : "not_observed")
        .set("observationComplete", !before.containsKey("probeError") && !after.containsKey("probeError"))
        .set("observationWindowMs", 500);
  }

  /** 动作成功但页面毫无变化时,把 changed=false 和提示一起返回 */
  private static RespBodyVo okWithReceipt(Kv before, BrowserInstance inst, String action) {
    Kv report = receipt(before, inst);
    if (!Boolean.TRUE.equals(report.getBoolean("changed"))) {
      report.set("hint", action + " 已执行，但观察窗口内尚未发现变化；不代表点击失败，请等待目标条件或读取新状态");
    }
    return RespBodyVo.ok(report);
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
   */
  private RespBodyVo indexAction(BrowserInstance inst, int index, String action, String fallbackSelector,
      Consumer<Locator> consumer, boolean withReceipt) {
    Locator locator = resolveIndex(inst, index, fallbackSelector);
    if (locator == null) {
      return RespBodyVo.fail(action + " 索引越界: " + index + indexHint(inst));
    }
    Kv before = withReceipt ? stateProbe(inst) : null;
    PlaywrightException failure;
    try {
      consumer.accept(locator);
      return withReceipt ? okWithReceipt(before, inst, action) : RespBodyVo.ok();
    } catch (PlaywrightException e) {
      failure = e;
    }
    sleepQuietly(300);
    try {
      consumer.accept(locator);
      return withReceipt ? okWithReceipt(before, inst, action) : RespBodyVo.ok();
    } catch (PlaywrightException e) {
      failure = e;
    }
    Locator recovered = recoverByIdentity(inst, index);
    if (recovered != null) {
      try {
        consumer.accept(recovered);
        return withReceipt ? okWithReceipt(before, inst, action) : RespBodyVo.ok();
      } catch (PlaywrightException e) {
        failure = e;
      }
    }
    return RespBodyVo.fail(actionFailure(action, failure));
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    return indexAction(inst, index, "click_element_by_index", CLICKABLE_SELECTOR,
        (locator) -> locator.click(clickOptions()), true);
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    Locator locator = resolveIndex(inst, index, INPUT_SELECTOR);
    if (locator == null) {
      return RespBodyVo.fail("input_text 索引越界: " + index + indexHint(inst));
    }
    try {
      fillEditable(locator, value);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("input_text", e));
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo uploadFile(Long browserId, int index, String path) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    Locator locator = resolveIndex(inst, index, FILE_SELECTOR);
    if (locator == null) {
      return RespBodyVo.fail("upload_file 索引越界: " + index + indexHint(inst));
    }
    try {
      locator.setInputFiles(Paths.get(path), new Locator.SetInputFilesOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(actionFailure("upload_file", e));
    }
    return RespBodyVo.ok();
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
    String script = normalizeScript(body);
    try {
      Object result = inst.page.evaluate(script);
      return RespBodyVo.ok(Kv.by("result", result));
    } catch (PlaywrightException e) {
      String message = briefMessage(e.getMessage());
      log.error("execute_js 执行失败,id:{},script:{},error:{}", browserId, script, message, e);
      return RespBodyVo.fail("执行 JavaScript 失败：" + message);
    }
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
    return clickLike(browserId, index, "double_click_element_by_index",
        (locator) -> locator.dblclick(new Locator.DblclickOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS)), true);
  }

  public RespBodyVo hoverElementByIndex(Long browserId, int index) {
    return clickLike(browserId, index, "hover_element_by_index",
        (locator) -> locator.hover(new Locator.HoverOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS)));
  }

  public RespBodyVo focusElementByIndex(Long browserId, int index) {
    return clickLike(browserId, index, "focus_element_by_index",
        (locator) -> locator.focus(new Locator.FocusOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS)));
  }

  public RespBodyVo checkElementByIndex(Long browserId, int index) {
    return clickLike(browserId, index, "check_element_by_index",
        (locator) -> locator.check(new Locator.CheckOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS)));
  }

  public RespBodyVo uncheckElementByIndex(Long browserId, int index) {
    return clickLike(browserId, index, "uncheck_element_by_index",
        (locator) -> locator.uncheck(new Locator.UncheckOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS)));
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
      locator.pressSequentially(text, new Locator.PressSequentiallyOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
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

  public RespBodyVo getElementCount(Long browserId, String selector) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      return RespBodyVo.ok(Kv.by("count", inst.page.locator(selector).count()));
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    int tabCountBefore = pagesOf(inst).size();
    Kv before = stateProbe(inst);
    try {
      inst.page.locator(selector).first().click(clickOptions());
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("click_element_by_selector", "选择器 " + selector, e));
    }
    adoptNewTab(inst, tabCountBefore);
    return okWithReceipt(before, inst, "click_element_by_selector");
  }

  public RespBodyVo inputTextBySelector(Long browserId, String selector, String value) {
    return actBySelector(browserId, "input_text_by_selector", selector, (locator) -> fillEditable(locator, value));
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
      try {
        hit.locator.click(clickOptions());
      } catch (PlaywrightException e) {
        return RespBodyVo.fail(locateFailure("click_element_by_text", "文本 " + text, e));
      }
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv before = stateProbe(inst);
    Locator locator = inst.page
        .getByRole(AriaRole.valueOf(role.toUpperCase()), new Page.GetByRoleOptions().setName(name)).first();
    Kv info = describe(locator);
    try {
      locator.click(clickOptions());
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("click_element_by_role", "角色 " + role + "[name=" + name + "]", e));
    }
    return okWithHit(before, inst, "click_element_by_role", info);
  }

  public RespBodyVo inputTextByLabel(Long browserId, String label, String value) {
    return actByLocator(browserId, "input_text_by_label", "标签 " + label, (inst) -> inst.page.getByLabel(label).first(),
        (locator) -> fillEditable(locator, value));
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
    try {
      locator.hover(new Locator.HoverOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
      sleepQuietly(hoverDelayMs == null ? 300 : hoverDelayMs);
      locator.click(clickOptions());
    } catch (PlaywrightException e) {
      return RespBodyVo.fail(locateFailure("hover_and_click", target, e));
    }
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    try {
      inst.page.locator(selector).first()
          .waitFor(new Locator.WaitForOptions().setTimeout(timeoutMillis(timeoutSeconds)));
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

  // ==================== 截图与 PDF ====================

  /**
   * 页面截图
   *
   * <p>
   * 传了 index 或 selector 时只截该元素;否则截整页,可以用 clipX/clipY/clipWidth/clipHeight
   * 指定裁剪区域(四个都传才生效)。path 为空时返回 data.base64。
   */
  public RespBodyVo screenshot(Long browserId, String path, Boolean fullPage, Integer index, String selector,
      Double clipX, Double clipY, Double clipWidth, Double clipHeight) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    if (index != null || (selector != null && !selector.isEmpty())) {
      return elementScreenshot(inst, index, selector, path);
    }
    Page.ScreenshotOptions options = new Page.ScreenshotOptions().setFullPage(fullPage != null && fullPage);
    if (clipX != null && clipY != null && clipWidth != null && clipHeight != null) {
      options.setClip(clipX, clipY, clipWidth, clipHeight);
    }
    if (path != null && !path.isEmpty()) {
      ensureParent(path);
      options.setPath(Paths.get(path));
    }
    try {
      byte[] bytes = inst.page.screenshot(options);
      if (path != null && !path.isEmpty()) {
        return RespBodyVo.ok(Kv.by("path", path).set("size", bytes.length));
      }
      return RespBodyVo.ok(Kv.by("base64", Base64.getEncoder().encodeToString(bytes)).set("size", bytes.length));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("screenshot 失败：" + briefMessage(e.getMessage()));
    }
  }

  /**
   * 只截一个元素,返回 data.path+data.size 或 data.base64
   *
   * <p>
   * 和用 execute_js + canvas 抠图相比,这里走 Playwright 自己的截图,不受 canvas 跨域污染限制,
   * 验证码、二维码、图表这类"必须看图"的元素都能拿到。
   */
  public RespBodyVo getElementScreenshot(Long browserId, Integer index, String selector, String path) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return elementScreenshot(inst, index, selector, path);
  }

  private RespBodyVo elementScreenshot(BrowserInstance inst, Integer index, String selector, String path) {
    Locator locator;
    String target;
    if (index != null) {
      locator = locatorOf(inst, index);
      target = "index=" + index;
      if (locator == null) {
        return RespBodyVo.fail("get_element_screenshot 索引越界: " + index + indexHint(inst));
      }
    } else if (selector != null && !selector.isEmpty()) {
      locator = inst.page.locator(selector).first();
      target = "selector=" + selector;
    } else {
      return RespBodyVo.fail("get_element_screenshot 需要 index 或 selector");
    }
    try {
      byte[] bytes = locator.screenshot(new Locator.ScreenshotOptions().setTimeout(INDEX_ACTION_TIMEOUT_MS));
      if (path != null && !path.isEmpty()) {
        ensureParent(path);
        Files.write(Paths.get(path), bytes);
        return RespBodyVo.ok(Kv.by("path", path).set("size", bytes.length).set("target", target));
      }
      return RespBodyVo.ok(
          Kv.by("base64", Base64.getEncoder().encodeToString(bytes)).set("size", bytes.length).set("target", target));
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
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    return RespBodyVo.ok(Kv.by("requests", filterRequests(inst, filter)));
  }

  /** 按 URL 子串过滤请求记录(最多 200 条),filter 为空时返回全部 */
  private static List<Kv> filterRequests(BrowserInstance inst, String filter) {
    List<Kv> all = new ArrayList<>(inst.requests);
    if (filter == null || filter.isEmpty()) {
      return all;
    }
    List<Kv> matched = new ArrayList<>();
    for (Kv kv : all) {
      if (kv.getStr("url") != null && kv.getStr("url").contains(filter)) {
        matched.add(kv);
      }
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
    DOMState state = inst.domState;
    if (state == null) {
      return RespBodyVo.fail("get_interactive_map 需要先调用 get_browser_state 获取元素索引");
    }
    List<Integer> indices = new ArrayList<>(state.getSelectorMap().keySet());
    indices.sort(null);
    List<String> xpaths = new ArrayList<>();
    for (Integer index : indices) {
      DOMElementNode node = state.getSelectorMap().get(index);
      xpaths.add("/" + (node == null ? "" : node.getXpath()));
    }
    List<Kv> items = new ArrayList<>();
    Object raw;
    try {
      raw = inst.page.evaluate("(list) => list.map(xpath => {"
          + " const r = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);"
          + " const e = r.singleNodeValue; if (!e) return null;" + " return { tag: e.tagName, id: e.id || null,"
          + " className: typeof e.className === 'string' ? e.className : null,"
          + " href: e.getAttribute ? e.getAttribute('href') : null,"
          + " name: e.getAttribute ? e.getAttribute('name') : null,"
          + " text: (e.innerText || '').trim().slice(0, 120) }; })", xpaths);
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("get_interactive_map 失败：" + briefMessage(e.getMessage()));
    }
    List<?> list = raw instanceof List ? (List<?>) raw : new ArrayList<>();
    for (int i = 0; i < indices.size(); i++) {
      DOMElementNode node = state.getSelectorMap().get(indices.get(i));
      Kv item = Kv.by("index", indices.get(i));
      Object entry = i < list.size() ? list.get(i) : null;
      if (entry instanceof Map) {
        item.set((Map<?, ?>) entry);
      }
      if (node != null) {
        item.set("xpath", node.getXpath());
      }
      items.add(item);
    }
    return RespBodyVo.ok(Kv.by("count", items.size()).set("elements", items));
  }

  // ==================== 人机协同 ====================

  /**
   * 发起一个人工介入请求
   *
   * <p>
   * 验证码、短信码、人工登录这类环节,智能体既读不了图也拿不到凭证,只能请人来做。这个接口 把「请人」这件事固定下来:
   *
   * <ol>
   * <li>request_human_input 建一个待办,可选把某个元素(验证码图)截成 base64 一起返回,并把当前页签带到最前</li>
   * <li>人看到图和页面后,把答案用 submit_human_input 提交(或者直接在有头浏览器里自己操作完)</li>
   * <li>智能体用 get_human_input 取答案;带 timeoutSeconds 时可以当长轮询用</li>
   * </ol>
   *
   * <p>
   * 返回 data.requestId、data.prompt、data.expiresAt、data.url,以及传了 index/selector 时的
   * data.imageBase64 与 data.imageSize。
   */
  public RespBodyVo requestHumanInput(Long browserId, String prompt, Integer index, String selector,
      Integer timeoutSeconds) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    int ttl = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_HUMAN_TIMEOUT_SECONDS : timeoutSeconds;
    long expiresAt = System.currentTimeMillis() + ttl * 1_000L;
    String requestId = "hr-" + inst.humanSeq.incrementAndGet() + "-" + SnowflakeIdUtils.id();
    Kv request = Kv.by("requestId", requestId).set("prompt", prompt).set("status", "pending").set("answer", null)
        .set("createdAt", System.currentTimeMillis()).set("expiresAt", expiresAt);
    inst.humanRequests.put(requestId, request);
    // 需要人工介入时把页面带到最前,人才能直接看到验证码/表单
    activate(inst.page);
    Kv data = Kv.by("requestId", requestId).set("prompt", prompt).set("expiresAt", expiresAt).set("url",
        inst.page.url());
    if (index != null || (selector != null && !selector.isEmpty())) {
      RespBodyVo shot = elementScreenshot(inst, index, selector, null);
      if (shot.isOk() && shot.getData() instanceof Kv) {
        Kv shotData = (Kv) shot.getData();
        data.set("imageBase64", shotData.getStr("base64"));
        data.set("imageSize", shotData.get("size"));
      } else {
        data.set("imageError", shot.getMsg());
      }
    }
    return RespBodyVo.ok(data);
  }

  /** 提交人工答复,返回 data.status 与 data.answer */
  public RespBodyVo submitHumanInput(Long browserId, String requestId, String answer) {
    BrowserInstance inst = INSTANCES.get(browserId);
    if (inst == null) {
      return notFound(browserId);
    }
    Kv request = inst.humanRequests.get(requestId);
    if (request == null) {
      return RespBodyVo.fail("submit_human_input 没有这个请求: " + requestId);
    }
    request.set("answer", answer).set("status", "answered").set("answeredAt", System.currentTimeMillis());
    return RespBodyVo.ok(Kv.by("requestId", requestId).set("status", "answered").set("answer", answer));
  }

  /**
   * 取人工答复
   *
   * <p>
   * data.status 为 pending / answered / expired。传 timeoutSeconds 时长轮询等待答复,到时间
   * 还没答复就返回当前状态(不算失败)。人在浏览器里自己把事情做完了、始终没提交答复时,这里 会一直是 pending 直到过期,智能体可以直接继续后续步骤。
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
    return RespBodyVo.ok(Kv.by("requestId", requestId).set("status", humanStatus(request))
        .set("answer", request.get("answer")).set("prompt", request.get("prompt")));
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
