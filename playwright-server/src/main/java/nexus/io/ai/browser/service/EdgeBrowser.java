package nexus.io.ai.browser.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import nexus.io.tio.utils.environment.EnvUtils;

/**
 * 本机安装的 Microsoft Edge
 *
 * <p>
 * 与 {@link ChromeBrowser} 是同一类探测(可执行文件 + 托管 profile 目录),差别在两点:
 *
 * <ul>
 * <li><b>路径与产品名</b>:{@code msedge.exe} 而不是 {@code chrome.exe};Windows 上除了
 * {@code %ProgramFiles(x86)%} 还要看 {@code %LOCALAPPDATA%} —— Edge 默认按用户安装,这台机器上就是
 * {@code %LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe}。</li>
 * <li><b>profile 不能和 Chrome 混用</b>:Edge 打开 Chrome 的 User Data 会把它当成外来 profile 处理,
 * 而且两家 Cookie 的 App-Bound 加密密钥不同,混用只会得到一份读不出登录态的目录。所以 Edge 单独一份
 * 托管 profile(见 {@link #KEY_PROFILE_DIR}),默认
 * {@code ~/.config/browseruse/profiles/edge}。</li>
 * </ul>
 *
 * <p>
 * Edge 是 Chromium 系,{@link BrowserEngine} 层面看不到它:引擎还是 Chromium,只是这份浏览器是 Edge。
 * 「用哪个浏览器」由 {@link BrowserType#EDGE} 表达,启动时经 {@code PlaywrightService} 传给 Playwright 的
 * {@code executablePath}。这里**不用** Playwright 的 {@code channel=msedge}:channel 要求 Playwright 按
 * 自己的规则去猜安装位置,而本项目已经有自己的探测顺序(配置 → 常见位置 → 注册表 → PATH),两套规则并存
 * 只会让「为什么没找到 Edge」变得难查。
 *
 * <p>
 * 不做的事:不支持「用你自己日常那份 Edge profile」。原因与 Chrome 那边一致(Chrome/Edge 136 起不允许在
 * 默认用户数据目录上开远程调试),而且这条路的代价是给整机开远程调试策略 —— 收益不足以抵消,所以
 * Edge 只走托管 profile。想用现成的登录态就用本机 Chrome 加
 * {@code browser.chrome.useUserProfile}(见 {@link ChromeBrowser#KEY_USE_USER_PROFILE})。
 */
@Slf4j
public final class EdgeBrowser {

  /** 是否启用本机 Edge 探测;false 时 {@link #executablePath()} 一律返回 null */
  public static final String KEY_ENABLED = "browser.edge.enabled";
  /** 显式指定 Edge 可执行文件(msedge.exe),留空时按平台惯例探测 */
  public static final String KEY_PATH = "browser.edge.path";
  /** Edge 自己的托管 profile 目录;留空时是 {@code ~/.config/browseruse/profiles/edge} */
  public static final String KEY_PROFILE_DIR = "browser.edge.profileDir";
  /** 追加到 Edge 命令行的额外参数,逗号分隔,例如 --lang=zh-CN,--proxy-server=... */
  public static final String KEY_EXTRA_ARGS = "browser.edge.extraArgs";

  /** Edge 首启哨兵文件名:存在它,Edge 才认为「不是第一次运行」,不弹引导向导 */
  private static final String FIRST_RUN_SENTINEL = "First Run";

  private static final Object LOCK = new Object();

  private static volatile boolean resolved;
  private static volatile Path executablePath;

  private EdgeBrowser() {
  }

  /**
   * Edge 的可执行文件
   *
   * @return 绝对路径;没装(或 {@code browser.edge.enabled=false})时返回 null
   */
  public static Path executablePath() {
    if (resolved) {
      return executablePath;
    }
    synchronized (LOCK) {
      if (resolved) {
        return executablePath;
      }
      try {
        executablePath = findExecutable();
      } catch (RuntimeException e) {
        log.warn("探测 Microsoft Edge 失败:{}", e.getMessage());
        executablePath = null;
      }
      resolved = true;
      return executablePath;
    }
  }

  /**
   * Edge 自己的托管 profile 目录
   *
   * <p>
   * 留空时是 {@code ~/.config/browseruse/profiles/edge}。与 Chrome 那份
   * ({@code browser.profileDir})刻意分开:两家的 profile 不能互相读取。
   */
  public static Path managedProfileDir() {
    String configured = ChromeBrowser.config(KEY_PROFILE_DIR);
    if (configured != null) {
      return Paths.get(ChromeBrowser.stripQuotes(configured)).toAbsolutePath().normalize();
    }
    return Paths.get(EnvUtils.get("user.home", "."), ".config", "browseruse", "profiles", "edge");
  }

  /**
   * 追加到 Edge 命令行的额外参数
   *
   * @return 形如 {@code ["--lang=zh-CN"]};没配时返回空列表
   */
  static List<String> extraArgs() {
    String configured = ChromeBrowser.config(KEY_EXTRA_ARGS);
    List<String> args = new ArrayList<>();
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
   * 补上 Edge 的首启哨兵文件
   *
   * <p>
   * Chromium 系用 User Data 目录下的 {@code First Run} 文件判断「是不是第一次运行」:文件不存在时会走首启
   * 流程(引导页 / 默认浏览器询问 / 导入数据)。托管 profile 是服务自己新建的空目录,所以每次启动 Edge 前
   * 补一个空文件,免得自动化跑起来先撞上一个向导页。写不进去只记日志:最坏就是多一个向导页,不该让启动失败。
   */
  static void prepareProfileDir(Path profileDir) {
    if (profileDir == null) {
      return;
    }
    try {
      Files.createDirectories(profileDir);
      Path sentinel = profileDir.resolve(FIRST_RUN_SENTINEL);
      if (!Files.exists(sentinel)) {
        Files.write(sentinel, new byte[0]);
        log.info("已补上 Edge 首启哨兵:{}", sentinel);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("准备 Edge profile 目录失败 {}:{}", profileDir, e.getMessage());
    }
  }

  /** 清掉探测缓存,下次重新探测(测试里改完系统属性用) */
  static void resetForTests() {
    synchronized (LOCK) {
      resolved = false;
      executablePath = null;
    }
  }

  private static Path findExecutable() {
    if (!ChromeBrowser.booleanConfig(KEY_ENABLED, true)) {
      log.info("{} 为 false,不使用本机安装的 Microsoft Edge", KEY_ENABLED);
      return null;
    }
    String configured = ChromeBrowser.config(KEY_PATH);
    if (configured != null) {
      Path path = Paths.get(ChromeBrowser.stripQuotes(configured)).toAbsolutePath().normalize();
      if (Files.isRegularFile(path)) {
        return path;
      }
      log.warn("{} 指向的文件不存在:{}", KEY_PATH, configured);
    }
    for (String candidate : candidateExecutables()) {
      if (candidate != null && Files.isRegularFile(Paths.get(candidate))) {
        return Paths.get(candidate).toAbsolutePath().normalize();
      }
    }
    Path fromRegistry = windowsRegistryExecutable();
    if (fromRegistry != null) {
      return fromRegistry;
    }
    Path fromPath = fromPath();
    if (fromPath == null) {
      log.warn("没有找到本机安装的 Microsoft Edge");
    }
    return fromPath;
  }

  /** 各平台常见的安装位置 */
  private static List<String> candidateExecutables() {
    List<String> candidates = new ArrayList<>();
    if (ChromeBrowser.isWindows()) {
      // Edge 默认是「按用户安装」,所以 %LOCALAPPDATA% 这一项最常见,不能漏
      addUnder(candidates, ChromeBrowser.env("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe");
      addUnder(candidates, ChromeBrowser.env("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe");
      addUnder(candidates, ChromeBrowser.env("LOCALAPPDATA"), "Microsoft", "Edge", "Application", "msedge.exe");
      addUnder(candidates, ChromeBrowser.env("ProgramW6432"), "Microsoft", "Edge", "Application", "msedge.exe");
    } else if (ChromeBrowser.isMac()) {
      candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
      addUnder(candidates, ChromeBrowser.env("HOME"), "Applications", "Microsoft Edge.app", "Contents", "MacOS",
          "Microsoft Edge");
    } else {
      candidates.add("/usr/bin/microsoft-edge");
      candidates.add("/usr/bin/microsoft-edge-stable");
      candidates.add("/opt/microsoft/msedge/msedge");
      candidates.add("/snap/bin/microsoft-edge");
    }
    return candidates;
  }

  private static void addUnder(List<String> candidates, String root, String... parts) {
    if (root == null || root.isBlank()) {
      return;
    }
    Path path = Paths.get(root);
    for (String part : parts) {
      path = path.resolve(part);
    }
    candidates.add(path.toString());
  }

  /** Windows 注册表里的 Edge 路径:Edge 安装包会写 {@code App Paths\msedge.exe} */
  private static Path windowsRegistryExecutable() {
    if (!ChromeBrowser.isWindows()) {
      return null;
    }
    String[] keys = { "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe",
        "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe" };
    for (String key : keys) {
      String value = ChromeBrowser.regQueryDefault(key);
      if (value != null && Files.isRegularFile(Paths.get(value))) {
        return Paths.get(value).toAbsolutePath().normalize();
      }
    }
    return null;
  }

  /** PATH 里找一遍:便携版/自定义安装只会把 msedge 放进 PATH */
  private static Path fromPath() {
    String path = ChromeBrowser.env("PATH");
    if (path == null || path.isBlank()) {
      return null;
    }
    String name = ChromeBrowser.isWindows() ? "msedge.exe" : "microsoft-edge";
    for (String directory : path.split(java.io.File.pathSeparator)) {
      if (directory.isBlank()) {
        continue;
      }
      Path candidate = Paths.get(directory.trim(), name);
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }
}