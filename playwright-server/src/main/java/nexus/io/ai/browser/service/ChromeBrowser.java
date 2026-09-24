package nexus.io.ai.browser.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;
import nexus.io.tio.utils.environment.EnvUtils;

/**
 * 本机安装的 Google Chrome,以及它自带的用户 profile
 *
 * <p>
 * 服务不再默认使用 Playwright 自带的那份 Chromium:那是一份未签名的开源构建,缺专有编解码器,
 * 而且不少站点(尤其是要登录的站点)会把它识别成「非官方浏览器」并给出安全提示。改用用户自己
 * 安装的 Google Chrome 之后,浏览器指纹、版本号、扩展能力都与用户日常使用的一致。
 *
 * <p>
 * 配套的第二件事是 profile:Playwright 自带浏览器只能用它自己的托管 profile(每个任务一份,
 * 登录态要重新养),而真实的 Google Chrome 有用户自己的用户数据目录(User Data),
 * 里面的登录态、Cookie、localStorage 都是现成的。所以这里同时负责探测
 * 「用户数据目录」和「用哪个子 profile」,由 {@link PlaywrightService} 在启动时使用。
 *
 * <p>
 * 探测顺序(以 Windows 为例,可执行文件):
 * <ol>
 * <li>{@code browser.chrome.path} 显式配置</li>
 * <li>{@code %ProgramFiles%\Google\Chrome\Application\chrome.exe} 等常见安装位置</li>
 * <li>注册表 {@code App Paths\chrome.exe}(Chrome 官方安装包一定会写这一项)</li>
 * <li>PATH 里找 {@code chrome.exe}</li>
 * </ol>
 *
 * <p>
 * 配置项读法(优先级从高到低):系统属性 / 环境变量 / {@code app.properties}(由 tio-boot 的
 * {@code EnvUtils.load()} 载入,见 {@link EnvUtils#get(String)})→ 本类自带资源
 * {@code browser.properties} → 代码里的默认值。所以在 {@code app.properties}、{@code .env}
 * 或命令行 {@code -Dbrowser.chrome.path=...} 里覆盖都可以。
 *
 * <p>
 * 探测不到 Chrome 时全部返回 null,调用方会退回内嵌 Chromium(发行版)或 Playwright 自带的
 * 浏览器(开发态),服务不会因此起不来。
 */
@Slf4j
public final class ChromeBrowser {

  /** 是否启用本机 Google Chrome;false 时完全退回原来的内嵌 Chromium + 托管 profile */
  public static final String KEY_ENABLED = "browser.chrome.enabled";
  /** 显式指定 Chrome 可执行文件,留空时按平台惯例探测 */
  public static final String KEY_PATH = "browser.chrome.path";
  /** 显式指定用户数据目录(User Data),留空时按平台惯例探测 */
  public static final String KEY_USER_DATA_DIR = "browser.chrome.userDataDir";
  /** 使用用户数据目录里的哪个子 profile,默认 Default;填 auto 时读 Local State 里的 profile.last_used */
  public static final String KEY_PROFILE_DIRECTORY = "browser.chrome.profileDirectory";
  /** 用户 profile 被占用(Chrome 正在运行)或启动失败时,是否退回托管 profile */
  public static final String KEY_PROFILE_FALLBACK = "browser.chrome.profileFallback";
  /** 追加到 Chrome 命令行的额外参数,逗号分隔,例如 --disable-extensions,--lang=zh-CN */
  public static final String KEY_EXTRA_ARGS = "browser.chrome.extraArgs";
  /**
   * 是否直接用用户自己的 Chrome 用户数据目录(登录态现成)
   *
   * <p>
   * 默认关闭,原因是 Chrome 136 起**不允许在默认用户数据目录上开启远程调试**(pipe 与 port 都不行),
   * 详见 {@link ChromeLauncher}。只有给这台机器加上企业策略
   * {@code RemoteDebuggingAllowed=1}(或者用的是 Chrome 136 之前的版本)时,这条路才走得通。
   * 打开它之后如果 Chrome 仍然拒绝,会退回托管 profile,并把原因放进 {@code start} 的返回里。
   */
  public static final String KEY_USE_USER_PROFILE = "browser.chrome.useUserProfile";
  /**
   * 托管 profile 的目录(用不上用户 profile 时用的那一份)
   *
   * <p>
   * 留空时是 {@code ~/.config/browseruse/profiles/shared}。所有任务共用这一份,所以换目录等于换一套
   * 登录态;测试里把它指到临时目录,免得污染开发机上那份。
   */
  public static final String KEY_PROFILE_DIR = "browser.profileDir";

  /**
   * 没显式配 {@code browser.profileDir} 时,是否按服务端口派生托管 profile 目录(默认开)
   *
   * <p>
   * 开了之后默认目录从 {@code profiles/shared} 变成 {@code profiles/shared-<端口>},多个服务实例
   * 同时跑不会抢同一份 profile 锁;要沿用老的 {@code shared} 就显式配 {@code browser.profileDir}。
   */
  public static final String KEY_PROFILE_DIR_PER_PORT = "browser.profileDir.perPort";

  /** 本类自带的配置资源 */
  private static final String CONFIG_RESOURCE = "browser.properties";

  /** profileDirectory 配成它时,读 Chrome 自己的「上次使用的 profile」 */
  private static final String PROFILE_AUTO = "auto";

  private static final String DEFAULT_PROFILE_DIRECTORY = "Default";

  private static final Object LOCK = new Object();

  private static volatile boolean resolved;
  private static volatile Path executablePath;
  private static volatile Path userDataDir;
  private static volatile String profileDirectory = DEFAULT_PROFILE_DIRECTORY;

  /** browser.properties 只读一次 */
  private static volatile Properties configResource;
  private static volatile boolean configResourceLoaded;

  private ChromeBrowser() {
  }

  /**
   * 本机安装的 Google Chrome 的可执行文件
   *
   * @return 绝对路径;没有安装(或 {@code browser.chrome.enabled=false})时返回 null
   */
  public static Path executablePath() {
    resolve();
    return executablePath;
  }

  /**
   * 用户自己的 Chrome 用户数据目录(User Data)
   *
   * @return 绝对路径;找不到时返回 null
   */
  public static Path userDataDir() {
    resolve();
    return userDataDir;
  }

  /** 使用用户数据目录里的哪个子 profile(Default / Profile 1 / ...),取不到时返回 null */
  public static String profileDirectory() {
    resolve();
    return profileDirectory;
  }

  public static boolean enabled() {
    return booleanConfig(KEY_ENABLED, true);
  }

  /**
   * 是否直接用用户自己的 Chrome 用户数据目录
   *
   * <p>
   * 默认 {@code false}:Chrome 136 起不允许在默认用户数据目录上开远程调试,默认走托管 profile 更
   * 可预期。想让 agent 直接用上现成的登录态,把 {@code browser.chrome.useUserProfile} 打开,
   * 并确认这台机器允许远程调试默认 profile(企业策略 {@code RemoteDebuggingAllowed=1},
   * 或者 Chrome 版本低于 136)。
   */
  public static boolean useUserProfile() {
    return booleanConfig(KEY_USE_USER_PROFILE, false);
  }

  /**
   * 托管 profile 的目录:用不上用户自己的 profile 时,所有任务共用这一份
   *
   * <p>
   * 默认 {@code ~/.config/browseruse/profiles/shared}。这里是 agent 自己养登录态的地方 ——
   * 第一次登录之后,Cookie 就留在这份 profile 里,后续任务不用再登。
   *
   * <p>
   * <b>多实例安全</b>:同一个 profile 目录同时只能被一个浏览器进程使用,两个服务实例(比如一个发布包
   * 跑在 10049、一个开发态跑在 10050)共用同一份目录时,后启动的会撞上 profile 锁,而
   * {@code releaseStaleProfileLock} 的自愈逻辑还有可能把**对方正在用的**浏览器当成孤儿清掉。
   * 所以打开 {@code browser.profileDir.perPort}(默认开)时,没配 {@code browser.profileDir} 的情况
   * 会按服务端口派生目录名({@code shared-10050}),各实例互不干扰。
   *
   * <p>
   * 想沿用某一份已有的登录态(比如从单实例时代留下来的 {@code shared}),显式配
   * {@code browser.profileDir} 指过去即可 —— 显式配置永远优先,不会被端口派生覆盖。
   */
  public static Path managedProfileDir() {
    String configured = config(KEY_PROFILE_DIR);
    if (configured != null) {
      return Paths.get(configured).toAbsolutePath().normalize();
    }
    Path base = Paths.get(EnvUtils.get("user.home", "."), ".config", "browseruse", "profiles", "shared");
    if (!perPortProfileDir()) {
      return base;
    }
    String suffix = portSuffix();
    return suffix == null ? base : Paths.get(base.toString() + "-" + suffix);
  }

  /** 托管 profile 目录是否按端口派生(默认开,见 {@link #managedProfileDir()}) */
  public static boolean perPortProfileDir() {
    return booleanConfig(KEY_PROFILE_DIR_PER_PORT, true);
  }

  /**
   * 当前服务端口,用于派生 profile 目录名
   *
   * @return 端口字符串;取不到(或非数字)时返回 null,调用方退回不分端口的老目录
   */
  private static String portSuffix() {
    String port = config("server.port");
    if (port == null) {
      port = EnvUtils.get("server.port");
    }
    if (port == null || port.isBlank()) {
      port = EnvUtils.get("SERVER_PORT");
    }
    if (port == null) {
      return null;
    }
    String trimmed = port.trim();
    return trimmed.matches("\\d+") ? trimmed : null;
  }

  /**
   * 用户 profile 用不了时是否退回托管 profile
   *
   * <p>
   * 最常见的原因是 Chrome 正在运行:同一个用户数据目录同时只能有一个 Chrome 进程,新的
   * Chrome 会把命令行交给已有实例然后自己退出,Playwright 只会看到「进程退出了」。
   */
  public static boolean profileFallback() {
    return booleanConfig(KEY_PROFILE_FALLBACK, true);
  }

  /**
   * 启动参数里的 {@code --profile-directory}
   *
   * @return 形如 {@code ["--profile-directory=Default"]};没有子 profile 概念时返回空列表
   */
  static List<String> profileArgs() {
    String directory = profileDirectory();
    List<String> args = new ArrayList<>();
    if (directory != null && !directory.isBlank()) {
      args.add("--profile-directory=" + directory);
    }
    return args;
  }

  /**
   * 追加到 Chrome 命令行的额外参数
   *
   * <p>
   * 用用户自己的 profile 时,Chrome 是我们自己拉起来的(见 {@link ChromeLauncher}),Playwright
   * 那些默认参数都不在,留一个口子给调用方补自己需要的参数(比如 {@code --disable-extensions}
   * 关掉用户装的扩展、{@code --lang=zh-CN} 指定界面语言)。
   *
   * @return 形如 {@code ["--disable-extensions"]};没配时返回空列表
   */
  static List<String> extraArgs() {
    String configured = config(KEY_EXTRA_ARGS);
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
   * 用户数据目录是否**确定**正被一个正在运行的 Chrome 占着
   *
   * <p>
   * 两条实证,任意一条成立才算「被占用」:
   * <ol>
   * <li>某个 Chrome 进程的命令行里带 {@code --user-data-dir=<这个目录>} —— 命令行读得到时最准;</li>
   * <li>目录里有 {@code SingletonLock} 且它记的那个 PID 还活着 —— 类 Unix 上 Chrome 用它做单例锁
   * (内容是 {@code <主机名>-<PID>});Windows 上 Chrome 用的是命名对象、不落文件,所以这条在 Windows
   * 上永远不成立,不会误报。</li>
   * </ol>
   *
   * <p>
   * 以前这里把「命令行读不到」当成「被占用」,理由是保守一点更安全。代价比想象中大:只要这台机器上有
   * **任意**一个 Chrome 进程的命令行读不到(实测:以管理员身份运行时,Windows 上
   * {@code ProcessHandle} 常读不到别的进程的命令行,连本进程自己拉起来的子进程也读不到),
   * {@code browser.chrome.useUserProfile} 就**永远**用不上,而且给出的理由是一句查无实据的
   * 「Chrome 正在运行」。
   *
   * <p>
   * 现在改成「没有实证就当没占用」:调用方({@link PlaywrightService})会真的去试一次 CDP 启动 ——
   * profile 真被占着时,新进程会把命令行交给已有实例然后自己退出,{@link ChromeLauncher} 立刻就能从
   * 「进程已退出」判断出来并退回托管 profile,不必等超时。猜测换成了实测。
   *
   * @param userDataDir 用户数据目录;null 返回 false
   */
  public static boolean profileInUse(Path userDataDir) {
    if (userDataDir == null) {
      return false;
    }
    Path dir = userDataDir.toAbsolutePath().normalize();
    if (lockedBySingletonLock(dir)) {
      return true;
    }
    String needle = normalize(dir.toString());
    try {
      for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
        Optional<String> command = handle.info().command();
        if (command.isEmpty() || !isChromeExecutable(command.get())) {
          continue;
        }
        // 只认「读得到,而且确实提到了这个目录」;读不到不再当作被占用
        if (normalize(handle.info().commandLine().orElse("")).contains(needle)) {
          return true;
        }
      }
    } catch (RuntimeException e) {
      log.debug("检查 Chrome 进程失败:{}", e.getMessage());
    }
    return false;
  }

  /**
   * 类 Unix 上的单例锁:Chrome 运行时在用户数据目录里放一个 {@code SingletonLock} 软链,指向
   * {@code <主机名>-<PID>}
   *
   * <p>
   * 光看文件在不在不够:Chrome 崩溃后会留下残留,下次启动会把它接管过去。所以还要看它记的那个 PID
   * 是不是真的活着 —— 那样「锁在但进程没了」就不会被当成占用。
   */
  private static boolean lockedBySingletonLock(Path dir) {
    Path lock = dir.resolve("SingletonLock");
    try {
      if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      return singletonLockHeld(Files.readSymbolicLink(lock).toString());
    } catch (IOException | RuntimeException e) {
      // 不是软链(Windows 上就是这种情况)、没权限读:都按「没有这条证据」处理
      log.debug("读取单例锁 {} 失败:{}", lock, e.getMessage());
      return false;
    }
  }

  /**
   * 单例锁的内容({@code <主机名>-<PID>})是不是还被人拿着
   *
   * <p>
   * 单独拆出来是为了能直接测:建软链在 Windows 上要额外权限,而这段判断才是真正容易写错的地方。
   * 认不出 PID 时按「还拿着」算 —— 宁可让调用方退回托管 profile,也别去抢人家正在用的目录。
   */
  static boolean singletonLockHeld(String target) {
    if (target == null) {
      return true;
    }
    int dash = target.lastIndexOf('-');
    if (dash < 0 || dash == target.length() - 1) {
      return true;
    }
    long pid;
    try {
      pid = Long.parseLong(target.substring(dash + 1));
    } catch (NumberFormatException e) {
      return true;
    }
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /**
   * 清掉探测缓存,下次重新探测(测试里改完系统属性用)
   *
   * <p>public 是给**其它包的测试**用的:配置读取({@code ChromeBrowser.config})被 UploadStore、
   * CommandTraceLog 这些同样走配置的类共用,而它们各自的测试在别的包里 —— 改完系统属性后必须能清掉
   * 这份缓存,否则读到的还是上一个用例的设置。
   */
  public static void resetForTests() {
    synchronized (LOCK) {
      resolved = false;
      executablePath = null;
      userDataDir = null;
      profileDirectory = DEFAULT_PROFILE_DIRECTORY;
    }
  }

  private static void resolve() {
    if (resolved) {
      return;
    }
    synchronized (LOCK) {
      if (resolved) {
        return;
      }
      try {
        if (!enabled()) {
          log.info("{} 为 false,不使用本机安装的 Google Chrome", KEY_ENABLED);
        } else {
          executablePath = findExecutable();
          if (executablePath == null) {
            log.warn("没有找到本机安装的 Google Chrome,改用内嵌/Playwright 自带的 Chromium");
          } else {
            log.info("使用本机安装的 Google Chrome:{}", executablePath);
            userDataDir = findUserDataDir();
            profileDirectory = findProfileDirectory(userDataDir);
            if (userDataDir == null) {
              log.warn("没有找到 Google Chrome 的用户数据目录,改用托管 profile");
            } else {
              log.info("使用 Google Chrome 用户 profile:{}(profile-directory={})", userDataDir, profileDirectory);
            }
          }
        }
      } catch (RuntimeException e) {
        log.warn("探测 Google Chrome 失败,改用内嵌/Playwright 自带的 Chromium:{}", e.getMessage());
        executablePath = null;
        userDataDir = null;
      }
      resolved = true;
    }
  }

  // ==================== 可执行文件 ====================

  private static Path findExecutable() {
    String configured = config(KEY_PATH);
    if (configured != null) {
      Path path = Paths.get(stripQuotes(configured)).toAbsolutePath().normalize();
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
    return fromPath();
  }

  /** 各平台常见的安装位置 */
  private static List<String> candidateExecutables() {
    List<String> candidates = new ArrayList<>();
    if (isWindows()) {
      addUnder(candidates, env("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe");
      addUnder(candidates, env("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe");
      addUnder(candidates, env("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe");
      addUnder(candidates, env("ProgramW6432"), "Google", "Chrome", "Application", "chrome.exe");
    } else if (isMac()) {
      candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
      addUnder(candidates, env("HOME"), "Applications", "Google Chrome.app", "Contents", "MacOS", "Google Chrome");
    } else {
      candidates.add("/usr/bin/google-chrome");
      candidates.add("/usr/bin/google-chrome-stable");
      candidates.add("/usr/local/bin/google-chrome");
      candidates.add("/opt/google/chrome/google-chrome");
      candidates.add("/opt/google/chrome/chrome");
      candidates.add("/snap/bin/google-chrome");
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

  /**
   * Windows 注册表里的 Chrome 路径
   *
   * <p>
   * Chrome 官方安装包会把安装路径写进 {@code App Paths\chrome.exe},装在非默认目录(或者装在
   * 用户目录下)时,只有这一项能查到。查不到不影响其它探测方式。
   */
  private static Path windowsRegistryExecutable() {
    if (!isWindows()) {
      return null;
    }
    String[] keys = { "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe",
        "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe" };
    for (String key : keys) {
      String value = regQueryDefault(key);
      if (value != null && Files.isRegularFile(Paths.get(value))) {
        return Paths.get(value).toAbsolutePath().normalize();
      }
    }
    return null;
  }

  /** 读注册表某个键的默认值(reg query ... /ve),失败一律返回 null */
  static String regQueryDefault(String key) {
    Process process = null;
    try {
      process = new ProcessBuilder("reg", "query", key, "/ve").redirectErrorStream(true).start();
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      for (String line : output.toString().split("\n")) {
        int regSz = line.indexOf("REG_SZ");
        if (regSz >= 0) {
          String value = stripQuotes(line.substring(regSz + "REG_SZ".length()).trim());
          if (!value.isEmpty()) {
            return value;
          }
        }
      }
    } catch (IOException e) {
      log.debug("读取注册表失败 {}:{}", key, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
    return null;
  }

  /** PATH 里找一遍,有些环境(便携版、自定义安装)只把 chrome 放进了 PATH */
  private static Path fromPath() {
    String path = env("PATH");
    if (path == null || path.isBlank()) {
      return null;
    }
    String[] names = isWindows() ? new String[] { "chrome.exe" }
        : new String[] { "google-chrome", "google-chrome-stable" };
    for (String directory : path.split(java.io.File.pathSeparator)) {
      if (directory.isBlank()) {
        continue;
      }
      for (String name : names) {
        Path candidate = Paths.get(directory.trim(), name);
        if (Files.isRegularFile(candidate)) {
          return candidate.toAbsolutePath().normalize();
        }
      }
    }
    return null;
  }

  // ==================== 用户 profile ====================

  private static Path findUserDataDir() {
    String configured = config(KEY_USER_DATA_DIR);
    if (configured != null) {
      Path path = Paths.get(stripQuotes(configured)).toAbsolutePath().normalize();
      if (!Files.isDirectory(path)) {
        // 显式配置的目录不存在也照用:Chrome 自己会创建,用户可能就是想让它新建一份
        log.info("{} 指向的目录不存在,Chrome 会创建它:{}", KEY_USER_DATA_DIR, path);
      }
      return path;
    }
    for (String candidate : candidateUserDataDirs()) {
      if (candidate != null && Files.isDirectory(Paths.get(candidate))) {
        return Paths.get(candidate).toAbsolutePath().normalize();
      }
    }
    return null;
  }

  /** 各平台 Chrome 用户数据目录的默认位置 */
  private static List<String> candidateUserDataDirs() {
    List<String> candidates = new ArrayList<>();
    if (isWindows()) {
      addUnder(candidates, env("LOCALAPPDATA"), "Google", "Chrome", "User Data");
    } else if (isMac()) {
      addUnder(candidates, env("HOME"), "Library", "Application Support", "Google", "Chrome");
    } else {
      String configHome = env("XDG_CONFIG_HOME");
      if (configHome != null && !configHome.isBlank()) {
        addUnder(candidates, configHome, "google-chrome");
      }
      addUnder(candidates, env("HOME"), ".config", "google-chrome");
    }
    return candidates;
  }

  /**
   * 用哪个子 profile
   *
   * <p>
   * 默认 {@code Default}。配成 {@code auto} 时读 {@code Local State} 里的
   * {@code profile.last_used},也就是 Chrome 自己记的「上次用的 profile」——多 profile 的用户
   * 不必手动指定。
   */
  private static String findProfileDirectory(Path userDataDir) {
    String configured = configRaw(KEY_PROFILE_DIRECTORY);
    if (configured == null) {
      return DEFAULT_PROFILE_DIRECTORY;
    }
    if (configured.isBlank()) {
      // 显式配成空串 = 不传 --profile-directory
      return null;
    }
    if (!PROFILE_AUTO.equalsIgnoreCase(configured.trim())) {
      return configured.trim();
    }
    return lastUsedProfile(userDataDir);
  }

  private static String lastUsedProfile(Path userDataDir) {
    if (userDataDir == null) {
      return DEFAULT_PROFILE_DIRECTORY;
    }
    Path localState = userDataDir.resolve("Local State");
    if (!Files.isRegularFile(localState)) {
      return DEFAULT_PROFILE_DIRECTORY;
    }
    try {
      JSONObject root = JSONObject.parseObject(new String(Files.readAllBytes(localState), StandardCharsets.UTF_8));
      JSONObject profile = root == null ? null : root.getJSONObject("profile");
      String lastUsed = profile == null ? null : profile.getString("last_used");
      if (lastUsed == null || lastUsed.isBlank()) {
        return DEFAULT_PROFILE_DIRECTORY;
      }
      log.info("从 Local State 读到上次使用的 profile:{}", lastUsed);
      return lastUsed;
    } catch (IOException | RuntimeException e) {
      log.debug("读取 Local State 失败:{}", e.getMessage());
      return DEFAULT_PROFILE_DIRECTORY;
    }
  }

  // ==================== 配置与平台工具 ====================

  /**
   * 读一个配置项,空串按「没配」处理
   *
   * <p>
   * 先问 {@link EnvUtils}:它覆盖了命令行参数、系统属性、环境变量,以及 tio-boot 启动时载入的
   * {@code app.properties} / {@code .env};都没有时再看本类自带的 {@code browser.properties}。
   *
   * <p>
   * public 是给 {@code handler} 包用的(调用追踪日志 {@code CommandTraceLog} 也按同一套优先级读配置),
   * 免得每个包各自实现一遍配置读取。
   */
  public static String config(String key) {
    String value = configRaw(key);
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** 读一个配置项,保留空串(配置成空串是有意义的,例如 profileDirectory 填空表示不传参数) */
  static String configRaw(String key) {
    String value = EnvUtils.get(key);
    if (value != null) {
      return value;
    }
    Properties properties = loadConfigResource();
    if (properties == null) {
      return null;
    }
    String fromResource = properties.getProperty(key);
    if (fromResource != null) {
      return fromResource;
    }
    // 也认全大写下划线写法,方便用环境变量覆盖
    return properties.getProperty(key.replace('.', '_').toUpperCase(Locale.ROOT));
  }

  /**
   * 读一个布尔配置项
   *
   * <p>package-private 是给同包的浏览器探测类复用的(见 {@link EdgeBrowser})。
   */
  static boolean booleanConfig(String key, boolean defaultValue) {
    String value = config(key);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }

  /** browser.properties 只读一次 */
  private static Properties loadConfigResource() {
    if (configResourceLoaded) {
      return configResource;
    }
    synchronized (ChromeBrowser.class) {
      if (!configResourceLoaded) {
        Properties properties = new Properties();
        try (InputStream in = ChromeBrowser.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
          if (in != null) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
          }
        } catch (IOException e) {
          log.debug("读取 {} 失败:{}", CONFIG_RESOURCE, e.getMessage());
        }
        configResource = properties;
        configResourceLoaded = true;
      }
      return configResource;
    }
  }

  /** 进程名里带 chrome 就算 Chrome(Windows 的 chrome.exe、macOS 的 Google Chrome、Linux 的 google-chrome) */
  private static boolean isChromeExecutable(String command) {
    try {
      Path path = Paths.get(command);
      Path fileName = path.getFileName();
      return normalize(fileName == null ? command : fileName.toString()).contains("chrome");
    } catch (RuntimeException e) {
      return normalize(command).contains("chrome");
    }
  }

  /** 读环境变量,空白按「没有」处理(package-private:同包的 {@link EdgeBrowser} 也要用) */
  static String env(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value;
  }

  /** 去掉配置值两端的引号(package-private:同包的 {@link EdgeBrowser} 也要用) */
  static String stripQuotes(String value) {
    String trimmed = value.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String normalize(String value) {
    return value.replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  static boolean isWindows() {
    return EnvUtils.get("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  static boolean isMac() {
    String os = EnvUtils.get("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("mac") || os.contains("darwin");
  }
}
