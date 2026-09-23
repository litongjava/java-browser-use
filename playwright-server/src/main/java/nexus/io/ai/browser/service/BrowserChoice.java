package nexus.io.ai.browser.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * 用哪个浏览器:内置 Chromium / 内置 Firefox / 本机 Chrome / 本机 Edge
 *
 * <p>
 * {@link BrowserEngine} 回答的是「用什么引擎」(Chromium / Firefox),这一层回答的是「用哪一份浏览器」——
 * 同样是 Chromium 引擎,内置的那份、用户装的 Google Chrome、用户装的 Microsoft Edge 是三件不同的东西:
 * 版本号、指纹、专有编解码器、装没装扩展、用哪份 profile 都不一样。有些站点只认其中一个,所以由调用方在
 * {@code start} 时按站点挑。
 *
 * <p>
 * 名字不叫 {@code BrowserType} 是因为 Playwright 自己就有一个 {@code BrowserType}(它启动浏览器的入口类,
 * 见 {@code PlaywrightService} 里的 {@code BrowserType.LaunchPersistentContextOptions}),同名会让那个类
 * 的引用必须全写成全限定名。
 *
 * <p>
 * {@code start} 的参数 {@code browser}(也接受配置项 {@code browser.type} 作为默认值):
 *
 * <table border="1">
 * <caption>取值</caption>
 * <tr><th>值</th><th>引擎</th><th>可执行文件</th><th>profile 目录</th></tr>
 * <tr><td>{@code auto}(默认)</td><td>Chromium</td><td>本机 Google Chrome,没装(或
 * {@code browser.chrome.enabled=false})则用内置 Chromium</td><td>{@code browser.profileDir}</td></tr>
 * <tr><td>{@code chromium}</td><td>Chromium</td><td><b>只</b>用内置的那份:发行包里内嵌的 Chromium,
 * 开发态是 Playwright 自带的 Chromium;<b>不碰本机 Chrome</b></td><td>{@code browser.profileDir}</td></tr>
 * <tr><td>{@code chrome}</td><td>Chromium</td><td><b>只</b>用本机安装的 Google Chrome,没装直接报错,
 * 不悄悄退回内置的</td><td>{@code browser.profileDir};打开
 * {@code browser.chrome.useUserProfile} 时是用户自己的 User Data</td></tr>
 * <tr><td>{@code edge}</td><td>Chromium</td><td><b>只</b>用本机安装的 Microsoft Edge,没装直接报错</td>
 * <td>{@code browser.edge.profileDir}(默认 {@code ~/.config/browseruse/profiles/edge})</td></tr>
 * <tr><td>{@code firefox}</td><td>Firefox</td><td>Playwright 自带的那份(本机装的普通 Firefox 接不上
 * juggler 协议)</td><td>{@code browser.profileDir}</td></tr>
 * </table>
 *
 * <p>
 * {@code auto} 与另外几个的区别是「能不能悄悄换一个」:不传 {@code browser} 时,服务会按老规矩
 * 「本机 Chrome → 内置 Chromium」退让,并把原因放进 {@code start} 返回的 {@code data.browser.note};而
 * 显式写了 {@code chrome} / {@code edge} 就表示「我就要这个」,没装会直接失败并说清怎么改 —— 否则
 * 「明明要了 Edge,结果用 Chrome 跑出另一种页面」这种问题很难查。
 *
 * <p>
 * 与 {@code browser.engine} 的关系:引擎级的开关仍然有效({@code browser.engine=firefox} 等价于
 * {@code browser.type=firefox}),但引擎只分 Chromium / Firefox 两档,表达不了 Edge 与 Chrome 的区别,
 * 所以浏览器类型一律走 {@code browser.type} 与 {@code start} 的 {@code browser} 参数。
 *
 * <p>
 * <b>一次只能有一个浏览器</b>:浏览器与 profile 是全进程共用的(见
 * {@link PlaywrightService.SharedBrowser}),所以类型可以在任务之间切换,但不能在任务运行中切换 ——
 * 换类型要先把正在跑的任务 close 掉,见 {@link PlaywrightService#start(Long, boolean, String)}。
 */
@Slf4j
public enum BrowserChoice {

  /** 不指定:本机 Google Chrome 优先,没装则内置 Chromium(与以前的版本完全一致) */
  AUTO,

  /** 内置 Chromium:发行包里内嵌的那份,开发态是 Playwright 自带的 Chromium,不碰本机 Chrome */
  CHROMIUM,

  /** 本机安装的 Google Chrome;没装直接报错 */
  CHROME,

  /** 本机安装的 Microsoft Edge;没装直接报错 */
  EDGE,

  /** 内置 Firefox:Playwright 自带的那份(本机装的普通 Firefox 接不上 juggler 协议) */
  FIREFOX;

  /** 默认浏览器类型({@code auto} / {@code chromium} / {@code chrome} / {@code edge} / {@code firefox}) */
  public static final String KEY_TYPE = "browser.type";

  /** 合法取值,报错信息与文档里都用这一份,免得两边写岔 */
  public static final List<String> CHOICES = List.of("auto", "chromium", "chrome", "edge", "firefox");

  /**
   * 解析调用方写的浏览器类型,认不出来时返回 {@code null}
   *
   * <p>
   * 只做解析,不落默认值:调用方要能区分「没传」(用配置里的默认值)与「传了一个不认识的值」(报错),
   * 所以未知值交给 {@link PlaywrightService#start(Long, boolean, String)} 去报。
   *
   * @param raw 形如 {@code chrome} / {@code msedge} / {@code ff};null 或空白返回 null
   */
  public static BrowserChoice parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "auto":
      case "default":
        return AUTO;
      case "chromium":
      case "bundled":
      case "bundled-chromium":
      case "playwright-chromium":
        return CHROMIUM;
      case "chrome":
      case "google-chrome":
      case "googlechrome":
        return CHROME;
      case "edge":
      case "msedge":
      case "ms-edge":
      case "microsoft-edge":
        return EDGE;
      case "firefox":
      case "ff":
      case "gecko":
        return FIREFOX;
      default:
        return null;
    }
  }

  /**
   * 配置里写的默认类型({@code browser.type}),没配时按老的 {@code browser.engine} 推
   *
   * <p>
   * {@code browser.engine=firefox} 等价于 {@code browser.type=firefox};{@code browser.engine=chromium}
   * (默认值)等价于 {@code auto} —— 保持「本机 Chrome 优先、没装退回内置 Chromium」这套老行为不变。
   */
  public static BrowserChoice configured() {
    String configured = ChromeBrowser.config(KEY_TYPE);
    if (configured != null) {
      BrowserChoice parsed = parse(configured);
      if (parsed != null) {
        return parsed;
      }
      log.warn("{} 的值无法识别:{},按 {} 处理", KEY_TYPE, configured, BrowserEngine.KEY_ENGINE);
    }
    return BrowserEngine.current().isFirefox() ? FIREFOX : AUTO;
  }

  /**
   * 把请求落成一个确定的类型:{@code auto} 要看这台机器上有什么
   *
   * <p>
   * {@code auto} 与「没传」是一回事,落点必须与以前完全一致:本机装了 Google Chrome(且
   * {@code browser.chrome.enabled=true})就用它,否则用内置 Chromium。
   *
   * @param requested 调用方要的类型;null 表示没指定,用 {@link #configured()}
   */
  public static BrowserChoice resolve(BrowserChoice requested) {
    BrowserChoice type = requested == null ? configured() : requested;
    if (type != AUTO) {
      return type;
    }
    return ChromeBrowser.executablePath() != null ? CHROME : CHROMIUM;
  }

  /** 类型名,用于日志与 {@code start} 返回里的 {@code data.browser.type} */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** 这个类型用的是哪个引擎(Chromium 还是 Firefox) */
  public BrowserEngine engine() {
    return this == FIREFOX ? BrowserEngine.FIREFOX : BrowserEngine.CHROMIUM;
  }

  public boolean isFirefox() {
    return this == FIREFOX;
  }

  /** 是不是本机安装的 Google Chrome(不是内置 Chromium,也不是 Edge) */
  public boolean isGoogleChrome() {
    return this == CHROME;
  }

  public boolean isEdge() {
    return this == EDGE;
  }

  /**
   * 这个类型要用的可执行文件
   *
   * @return 绝对路径;null 表示交给 Playwright 自己解析(内置 Chromium 在开发态、内置 Firefox 都是这条路)
   */
  public Path executable() {
    switch (resolve(this)) {
      case CHROMIUM:
        return BundledBrowser.executablePath();
      case CHROME:
        return ChromeBrowser.executablePath();
      case EDGE:
        return EdgeBrowser.executablePath();
      case FIREFOX:
        return BrowserEngine.firefoxExecutablePath();
      default:
        return null;
    }
  }

  /**
   * 这个类型共用的托管 profile 目录
   *
   * <p>
   * Chromium 系(内置 Chromium 与本机 Chrome)以及 Firefox 沿用 {@code browser.profileDir}
   * (默认 {@code ~/.config/browseruse/profiles/shared})—— 不动已有目录,免得把 agent 已经养起来的
   * 登录态挪走;Edge 单独一份,原因是 Edge 打开 Chrome 的 User Data 会把它当外来 profile 处理,而且
   * 两家 Cookie 的 App-Bound 加密密钥不同,混用只会得到一份读不出登录态的目录。
   */
  public Path profileDir() {
    return resolve(this) == EDGE ? EdgeBrowser.managedProfileDir() : ChromeBrowser.managedProfileDir();
  }

  /**
   * 这个类型要追加的额外启动参数
   *
   * <p>
   * 各家的配置项各管各的({@code browser.chrome.extraArgs} / {@code browser.edge.extraArgs}):
   * 同一个参数在两家的含义不一定一样,混着用迟早出怪事。
   */
  public List<String> extraArgs() {
    return resolve(this) == EDGE ? EdgeBrowser.extraArgs() : ChromeBrowser.extraArgs();
  }

  /**
   * 这个类型的 {@code --profile-directory} 启动参数
   *
   * <p>
   * 只有本机 Google Chrome 有「用户数据目录里的哪个子 profile」这个概念;内置 Chromium 与 Edge 用的是
   * 自己那份托管 profile,Firefox 更没有这一项。
   */
  public List<String> profileArgs() {
    return resolve(this) == CHROME ? ChromeBrowser.profileArgs() : List.of();
  }

  /** 显式要了某个类型但这台机器上没有时,给出的中文原因(含怎么改) */
  String notFoundMessage() {
    switch (resolve(this)) {
      case CHROME:
        return "browser=chrome：没有找到本机安装的 Google Chrome"
            + (ChromeBrowser.enabled() ? "(常见安装位置、注册表 App Paths\\chrome.exe、PATH 里都没有,"
                + "可以用 browser.chrome.path 显式指定可执行文件)" : "(browser.chrome.enabled=false 时不会去找 Chrome)")
            + ";想用内置 Chromium 就传 browser=chromium,想让服务自己挑就不要传 browser";
      case EDGE:
        return "browser=edge：没有找到本机安装的 Microsoft Edge"
            + "(常见安装位置、注册表 App Paths\\msedge.exe、PATH 里都没有,可以用 browser.edge.path 显式指定可执行文件)"
            + ";想用内置 Chromium 就传 browser=chromium,想用本机 Chrome 就传 browser=chrome";
      default:
        return "browser=" + id() + "：这台机器上找不到可执行文件";
    }
  }

  /** 合法取值,拼成一行给人看 */
  public static String choices() {
    return String.join(" / ", CHOICES);
  }
}