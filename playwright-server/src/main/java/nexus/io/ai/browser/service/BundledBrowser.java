package nexus.io.ai.browser.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import nexus.io.tio.utils.environment.EnvUtils;

/**
 * 发行包里内嵌的 Chromium
 *
 * <p>单文件发行版把目标平台的 Chromium 整个塞进 jar 的 {@code browsers/} 目录,首次运行时解压到
 * 用户缓存目录,之后直接用 {@code executablePath} 启动它。这样用户拿到 jar 就能跑,Playwright
 * 不会再去下载任何浏览器。
 *
 * <p>开发态(从 target/classes 运行、jar 里没有 browsers/index.txt)下 {@link #executablePath()}
 * 返回 null,服务会退回 Playwright 自己管理的浏览器,本地开发不受影响。
 *
 * <p>打包时由 {@code scripts/package} 下的脚本生成:
 * <ul>
 * <li>{@code browsers/index.txt}:内嵌文件清单,一行一个相对路径</li>
 * <li>{@code browsers/meta.properties}:{@code build}(构建标识,决定解压目录)与
 * {@code executable}(可执行文件相对路径)</li>
 * <li>{@code browsers/symlinks.txt}:符号链接清单(只有 macOS 的包才有),一行
 * {@code 相对路径<TAB>链接目标}</li>
 * </ul>
 *
 * <p>为什么符号链接要单独一个清单:macOS 的 {@code Chromium.app} 是标准的 framework 结构,
 * 靠 {@code Versions/Current -> 138.0.7204.23} 这类符号链接才能启动。而打包机不一定是 macOS
 * (Windows 上创建符号链接需要管理员特权),所以打包时把链接记下来,由这里在目标机器上重建。
 */
@Slf4j
public final class BundledBrowser {

  /** jar 里内嵌浏览器的资源前缀 */
  private static final String PREFIX = "browsers/";

  private static final String INDEX_RESOURCE = PREFIX + "index.txt";

  private static final String META_RESOURCE = PREFIX + "meta.properties";

  private static final String SYMLINKS_RESOURCE = PREFIX + "symlinks.txt";

  /** 解压完成标记:存在它才认为缓存目录可用,避免解压到一半被中断后拿到半个浏览器 */
  private static final String COMPLETE_MARKER = ".complete";

  private static volatile boolean resolved;

  private static volatile Path executablePath;

  private BundledBrowser() {
  }

  /**
   * 内嵌 Chromium 的可执行文件路径
   *
   * @return 可执行文件绝对路径;当前 jar 没有内嵌浏览器(开发态)时返回 null
   */
  public static Path executablePath() {
    if (resolved) {
      return executablePath;
    }
    synchronized (BundledBrowser.class) {
      if (resolved) {
        return executablePath;
      }
      try {
        executablePath = resolve();
      } catch (IOException | RuntimeException e) {
        log.warn("解压内嵌 Chromium 失败,改用 Playwright 自己管理的浏览器:{}", e.getMessage());
        executablePath = null;
      }
      resolved = true;
      return executablePath;
    }
  }

  private static Path resolve() throws IOException {
    List<String> entries = readIndex();
    if (entries.isEmpty()) {
      return null;
    }
    String build = readMeta("build", "chromium");
    String executable = readMeta("executable", null);
    Path root = cacheRoot().resolve(build);

    if (Files.exists(root.resolve(COMPLETE_MARKER))) {
      Path existing = executable == null ? null : root.resolve(executable);
      if (existing != null && Files.isRegularFile(existing)) {
        return existing;
      }
    }

    log.info("首次运行:解压内嵌 Chromium 到 {} ({} 个文件)", root, entries.size());
    long startedAt = System.currentTimeMillis();
    extract(entries, root);
    createSymlinks(root);
    Files.write(root.resolve(COMPLETE_MARKER), build.getBytes(StandardCharsets.UTF_8));
    log.info("内嵌 Chromium 解压完成,耗时 {}ms", System.currentTimeMillis() - startedAt);

    Path found = executable == null ? null : root.resolve(executable);
    if (found == null || !Files.isRegularFile(found)) {
      found = findExecutable(root);
    }
    if (found == null) {
      log.warn("内嵌 Chromium 解压后找不到可执行文件,改用 Playwright 自己管理的浏览器");
      return null;
    }
    makeExecutable(found);
    return found;
  }

  /** 缓存根目录:用户主目录下的 .cache/deepseek-browser-use */
  private static Path cacheRoot() {
    String home = EnvUtils.get("user.home", ".");
    return Paths.get(home, ".cache", "deepseek-browser-use");
  }

  private static List<String> readIndex() throws IOException {
    return readResourceLines(INDEX_RESOURCE);
  }

  /** 读一个内嵌的文本清单,一行一条,忽略空行与 # 注释;资源不存在时返回空列表 */
  private static List<String> readResourceLines(String resource) throws IOException {
    InputStream in = BundledBrowser.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      return new ArrayList<>();
    }
    List<String> entries = new ArrayList<>();
    try (InputStream stream = in) {
      for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          entries.add(trimmed);
        }
      }
    }
    return entries;
  }

  private static String readMeta(String key, String defaultValue) throws IOException {
    InputStream in = BundledBrowser.class.getClassLoader().getResourceAsStream(META_RESOURCE);
    if (in == null) {
      return defaultValue;
    }
    try (InputStream stream = in) {
      java.util.Properties props = new java.util.Properties();
      props.load(stream);
      String value = props.getProperty(key);
      return value == null || value.isBlank() ? defaultValue : value.trim();
    }
  }

  private static void extract(List<String> entries, Path root) throws IOException {
    boolean posix = !isWindows();
    Set<PosixFilePermission> dirPermissions = EnumSet.of(PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
    for (String entry : entries) {
      Path target = root.resolve(entry).normalize();
      if (!target.startsWith(root)) {
        // 清单里出现 .. 这类越界路径时直接跳过,不要写到缓存目录外面
        log.warn("跳过越界的内嵌资源:{}", entry);
        continue;
      }
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
        if (posix) {
          setPermissions(parent, dirPermissions);
        }
      }
      try (InputStream in = BundledBrowser.class.getClassLoader().getResourceAsStream(PREFIX + entry)) {
        if (in == null) {
          log.warn("内嵌资源缺失:{}", PREFIX + entry);
          continue;
        }
        try (OutputStream out = Files.newOutputStream(target)) {
          in.transferTo(out);
        }
      }
      if (posix) {
        makeExecutable(target);
      }
    }
  }

  /**
   * 重建符号链接
   *
   * <p>macOS 的 {@code Chromium.app} 靠 framework 里的符号链接启动,而打包机不一定能创建它们,
   * 所以打包时把链接清单写进 {@code browsers/symlinks.txt},这里在目标机器上重建。
   *
   * <p>已经有同名实体文件时先删掉;创建失败只记日志,不让整个解压失败 —— 万一某个平台不允许
   * 建链接,至少浏览器主体是完整的,后面找不到可执行文件时还会退回 Playwright 自带的浏览器。
   */
  private static void createSymlinks(Path root) {
    List<String> lines;
    try {
      lines = readResourceLines(SYMLINKS_RESOURCE);
    } catch (IOException e) {
      log.warn("读取内嵌符号链接清单失败:{}", e.getMessage());
      return;
    }
    int created = 0;
    for (String line : lines) {
      int tab = line.indexOf('\t');
      if (tab <= 0) {
        continue;
      }
      String linkName = line.substring(0, tab).trim();
      String targetName = line.substring(tab + 1).trim();
      Path link = root.resolve(linkName).normalize();
      if (targetName.isEmpty() || !link.startsWith(root)) {
        log.warn("跳过越界的内嵌符号链接:{}", line);
        continue;
      }
      try {
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Paths.get(targetName));
        created++;
      } catch (IOException | UnsupportedOperationException e) {
        log.warn("创建符号链接失败 {} -> {}:{}", linkName, targetName, e.getMessage());
      }
    }
    if (created > 0) {
      log.info("重建内嵌符号链接 {} 个", created);
    }
  }

  private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (IOException | UnsupportedOperationException e) {
      log.debug("设置权限失败:{}", path);
    }
  }

  /** Linux/macOS 下把解压出来的文件标成可执行:Chromium 的二进制与辅助进程都要求 +x */
  private static void makeExecutable(Path path) {
    setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
  }

  /** 清单里没写可执行文件时,按平台惯例在解压结果里找 */
  private static Path findExecutable(Path root) throws IOException {
    List<String> candidates = isWindows()
        ? List.of("chrome-win/chrome.exe", "chrome-win64/chrome.exe")
        : isMac()
            ? List.of("chrome-mac/Chromium.app/Contents/MacOS/Chromium",
                "chrome-mac-arm64/Chromium.app/Contents/MacOS/Chromium")
            : List.of("chrome-linux/chrome", "chrome-linux64/chrome");
    for (String candidate : candidates) {
      Path path = root.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path;
      }
    }
    // 兜底:直接在解压目录里搜同名的可执行文件
    String name = isWindows() ? "chrome.exe" : isMac() ? "Chromium" : "chrome";
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(name)).findFirst()
          .orElse(null);
    }
  }

  private static boolean isWindows() {
    return EnvUtils.get("os.name", "").toLowerCase().contains("win");
  }

  private static boolean isMac() {
    String os = EnvUtils.get("os.name", "").toLowerCase();
    return os.contains("mac") || os.contains("darwin");
  }
}
