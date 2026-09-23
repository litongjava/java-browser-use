package nexus.io.ai.browser.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * 用哪个浏览器引擎
 *
 * <p>
 * 服务默认用<b>本机安装的 Google Chrome</b>(没有才退回内嵌/Playwright 自带的 Chromium)。但有些站点
 * 并不吃这一套:中国商标网统一身份认证(sso.cnipa.gov.cn)的 SPA 会做开发者工具检测,并在 Chromium
 * 下走向空白页,而同一个流程在 <b>Playwright 自带的 Firefox</b> 下能正常渲染出登录表单(见
 * {@code playwright-server/target/cnipa-diagnosis/} 下的对照记录)。所以这里给出一个引擎开关,让调用方
 * 按站点挑浏览器,而不是把 Chrome 写死。
 *
 * <p>
 * 配置项 {@code browser.engine}:
 *
 * <table border="1">
 * <caption>取值</caption>
 * <tr><th>值</th><th>含义</th></tr>
 * <tr><td>{@code chromium}(默认)</td><td>原来的行为:本机 Google Chrome,没装则用内嵌/Playwright 自带的 Chromium。<b>默认值不变</b>,所以不配这一项时服务与以前完全一致</td></tr>
 * <tr><td>{@code firefox}</td><td>{@code playwright().firefox().launchPersistentContext(...)}:用 Playwright 自带的 Firefox(版本由 Playwright 依赖决定,本项目 1.53.0 → Firefox 139),配同一份托管 profile</td></tr>
 * </table>
 *
 * <p>
 * 读法与 {@link ChromeBrowser} 一致(系统属性 / 环境变量 / {@code app.properties} / {@code browser.properties},
 * 优先级从高到低),所以在命令行上加 {@code -Dbrowser.engine=firefox} 就能切过去。
 *
 * <p>
 * Firefox 与 Chromium 的差别不止版本号,有几处是引擎能力差异,不是配置问题:
 * <ul>
 * <li>没有 {@code --no-sandbox} / {@code chromiumSandbox} / {@code --profile-directory} 这些 Chromium 概念,
 * 所以这些参数在 Firefox 下**不传**(传了 Playwright 会报选项不被支持);</li>
 * <li>{@code page.pdf()}(命令 {@code pdf})只支持 Chromium,Firefox 下会明确失败;</li>
 * <li>没有 CDP:「直接用用户日常那份 Chrome profile」那条路({@code browser.chrome.useUserProfile})
 * 与 Firefox 无关,只影响 Chromium;</li>
 * <li>不能自己指定「本机装的 Firefox」并指望它可用:Playwright 的 Firefox 是一份打过补丁的构建
 * (走 juggler 协议),普通 Firefox 接不上。所以 {@code browser.firefox.path} 留空时用 Playwright
 * 自己那份,一般不需要配。</li>
 * </ul>
 */
@Slf4j
public enum BrowserEngine {

  /** 本机 Google Chrome(没装则内嵌/Playwright 自带的 Chromium),默认 */
  CHROMIUM,

  /** Playwright 自带的 Firefox */
  FIREFOX;

  /** 引擎名,取值 {@code chromium} / {@code firefox} */
  public static final String KEY_ENGINE = "browser.engine";
  /** 显式指定 Firefox 可执行文件;留空时交给 Playwright 解析它自己那份(推荐) */
  public static final String KEY_FIREFOX_PATH = "browser.firefox.path";
  /** 追加到 Firefox 命令行的额外参数,逗号分隔 */
  public static final String KEY_FIREFOX_EXTRA_ARGS = "browser.firefox.extraArgs";

  /** 当前配置要用的引擎;不认识的值按默认处理并留一条警告,不让服务起不来 */
  public static BrowserEngine current() {
    String configured = ChromeBrowser.config(KEY_ENGINE);
    if (configured == null) {
      return CHROMIUM;
    }
    switch (configured.trim().toLowerCase(Locale.ROOT)) {
      case "firefox":
      case "ff":
      case "gecko":
        return FIREFOX;
      case "chromium":
      case "chrome":
      case "chromium-based":
        return CHROMIUM;
      default:
        log.warn("{} 的值无法识别:{},按默认的 {} 处理", KEY_ENGINE, configured, CHROMIUM.id());
        return CHROMIUM;
    }
  }

  /** 配置里写的引擎名,用于日志与 {@code start} 的返回 */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public boolean isFirefox() {
    return this == FIREFOX;
  }

  public boolean isChromium() {
    return this == CHROMIUM;
  }

  /**
   * Firefox 的可执行文件
   *
   * <p>
   * 只有显式配了 {@code browser.firefox.path} 且文件真的存在时才返回它;否则返回 {@code null},
   * 交给 Playwright 解析它自己下载的那份 Firefox —— Playwright 的 Firefox 是打过补丁的构建
   * (普通 Firefox 接不上它的 juggler 协议),所以默认不要指到本机装的 Firefox 上。
   */
  static Path firefoxExecutablePath() {
    String configured = ChromeBrowser.config(KEY_FIREFOX_PATH);
    if (configured == null) {
      return null;
    }
    Path path = Paths.get(stripQuotes(configured)).toAbsolutePath().normalize();
    if (Files.isRegularFile(path)) {
      return path;
    }
    log.warn("{} 指向的文件不存在,改用 Playwright 自带的 Firefox:{}", KEY_FIREFOX_PATH, configured);
    return null;
  }

  /**
   * Firefox 追加的启动参数
   *
   * <p>
   * 只在确实需要时用:比如 {@code -private} 之类。默认空,Playwright 的默认参数已经够用。
   */
  static List<String> firefoxExtraArgs() {
    List<String> args = new ArrayList<>();
    String configured = ChromeBrowser.config(KEY_FIREFOX_EXTRA_ARGS);
    if (configured == null) {
      return args;
    }
    for (String arg : configured.split(",")) {
      String trimmed = arg.trim();
      if (!trimmed.isEmpty()) {
        args.add(trimmed);
      }
    }
    return args;
  }

  /**
   * Firefox 的 {@code userPrefs}
   *
   * <p>
   * 目的只有一个:让这份自动化浏览器在站点眼里更接近普通人用的 Firefox。
   * <ul>
   * <li>{@code dom.webdriver.enabled=false}:配合服务注入的 {@code navigator.webdriver=false},
   * 不给页面留「这是自动化」的直接信号;</li>
   * <li>{@code privacy.fingerprintingProtection=false}:Playwright 的 Firefox 默认开着指纹保护,
   * 会改写 {@code screen.availWidth/availHeight}(日志里那条 Fingerprinting Protection 警告就是它),
   * 让页面上读到的屏幕尺寸和实际窗口对不上 —— 政务服务类站点常用屏幕信息做校验,这里把它关掉。</li>
   * </ul>
   */
  static Map<String, Object> firefoxUserPrefs() {
    Map<String, Object> prefs = new LinkedHashMap<>();
    prefs.put("dom.webdriver.enabled", false);
    prefs.put("privacy.fingerprintingProtection", false);
    return prefs;
  }

  /**
   * Firefox 下要授予的权限
   *
   * <p>
   * Chromium 那边授的是 {@code clipboard-read} / {@code clipboard-write} / {@code notifications};
   * 剪贴板权限是 Chromium 特有的,Firefox 的权限模型里没有这两项,传进去会直接启动失败,所以这里只留
   * 两边都认的 {@code notifications}(地理位置的权限由 {@code set_geolocation} 在用到时单独授)。
   */
  static List<String> firefoxPermissions() {
    return List.of("notifications");
  }

  private static String stripQuotes(String value) {
    String trimmed = value.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }
}