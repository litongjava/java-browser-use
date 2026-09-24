package nexus.io.ai.browser.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ChromeBrowser;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;

/**
 * 服务端的文件暂存区:客户端把要上传给网站的文件先送到这里,再由 {@code upload_file} 交给页面
 *
 * <p>
 * <b>为什么需要它</b>:这是一个客户端-服务器模式的项目——智能体跑在客户端,浏览器跑在服务端,两边
 * 往往不是同一台机器。而 {@code upload_file} 的 {@code path} 必须是**服务端**能打开的文件路径,
 * 客户端本地的一个 {@code D:\...\商标.jpg} 在服务端根本不存在。所以客户端先把文件 POST 到
 * {@code /playwright/upload},拿到服务端返回的路径(或相对名),再把它交给 {@code upload_file}。
 *
 * <p>
 * 配置项(读法与 {@link ChromeBrowser#config(String)} 一致:系统属性 / 环境变量 / properties 文件):
 *
 * <table border="1">
 * <caption>配置</caption>
 * <tr><th>键</th><th>默认值</th><th>说明</th></tr>
 * <tr><td>{@code browser.upload.enabled}</td><td>{@code true}</td><td>关掉后 {@code /playwright/upload} 一律拒绝</td></tr>
 * <tr><td>{@code browser.upload.dir}</td><td>{@code <启动目录>/upload}</td><td>暂存目录,不存在会自动创建</td></tr>
 * <tr><td>{@code browser.upload.maxBytes}</td><td>{@code 67108864}(64MB)</td><td>单文件上限,{@code 0} 表示不限</td></tr>
 * <tr><td>{@code browser.upload.overwrite}</td><td>{@code true}</td><td>同名文件是覆盖还是自动改名</td></tr>
 * </table>
 *
 * <p>
 * 文件名会被清洗(只留基本名、去掉路径分隔符与控制字符),并且**只能落在暂存目录里**;
 * 相对路径交给 {@link #resolve(String)} 时会先规范化再校验前缀,{@code ../../} 这类写法会被拒掉。
 */
@Slf4j
public final class UploadStore {

  public static final String KEY_ENABLED = "browser.upload.enabled";
  public static final String KEY_DIR = "browser.upload.dir";
  public static final String KEY_MAX_BYTES = "browser.upload.maxBytes";
  public static final String KEY_OVERWRITE = "browser.upload.overwrite";

  /** 默认单文件上限 64MB:浏览器自动化要传的图样、PDF、证件照都远小于这个量级 */
  private static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

  /** 文件名长度上限(含扩展名),防止把文件系统写崩 */
  private static final int MAX_NAME_CHARS = 120;

  private UploadStore() {
  }

  /** 暂存目录:默认 {@code <启动目录>/upload},读配置时按需创建 */
  public static Path dir() {
    String configured = ChromeBrowser.config(KEY_DIR);
    Path root = configured != null ? Paths.get(configured)
        : Paths.get("").toAbsolutePath().resolve("upload");
    return root.toAbsolutePath().normalize();
  }

  /** 暂存目录不存在时创建;失败返回 false,由调用方决定怎么报错 */
  public static boolean ensureDir() {
    try {
      Files.createDirectories(dir());
      return true;
    } catch (IOException e) {
      log.warn("创建上传目录失败:{} ({})", dir(), e.toString());
      return false;
    }
  }

  public static boolean enabled() {
    String configured = ChromeBrowser.config(KEY_ENABLED);
    return configured == null || Boolean.parseBoolean(configured);
  }

  public static long maxBytes() {
    String configured = ChromeBrowser.config(KEY_MAX_BYTES);
    if (configured == null) {
      return DEFAULT_MAX_BYTES;
    }
    try {
      long parsed = Long.parseLong(configured.trim());
      return parsed < 0 ? DEFAULT_MAX_BYTES : parsed;
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_BYTES;
    }
  }

  public static boolean overwrite() {
    String configured = ChromeBrowser.config(KEY_OVERWRITE);
    return configured == null || Boolean.parseBoolean(configured);
  }

  // ==================== 路径 ====================

  /**
   * 把 {@code upload_file} 拿到的 path 解析成服务端可用的绝对路径
   *
   * <p>
   * 绝对路径原样返回(服务端自己的文件仍然可以直接用);相对路径按暂存目录解析,并且必须落在暂存目录
   * 之内——{@code ../../../etc/passwd} 这类写法直接判非法。这样客户端只需要把 {@code /playwright/upload}
   * 返回的 {@code relativePath} 原样回填给 {@code upload_file} 就够了。
   *
   * @return 规范化后的绝对路径;非法时返回 null
   */
  public static Path resolve(String nameOrPath) {
    if (nameOrPath == null || nameOrPath.isBlank()) {
      return null;
    }
    String raw = nameOrPath.trim();
    Path candidate = Paths.get(raw);
    if (candidate.isAbsolute()) {
      return candidate.normalize();
    }
    Path root = dir();
    Path resolved = root.resolve(raw).normalize();
    if (!resolved.startsWith(root)) {
      return null;
    }
    return resolved;
  }

  /**
   * 清洗文件名
   *
   * <p>
   * 只保留基本名,去掉路径分隔符、控制字符与 Windows 保留字符({@code <>:"/\|?*}),同时**保留中文**
   * ——商标图样这类文件的文件名本来就常是汉字,而商标网自己的规则也要求文件名只用汉字/字母/数字。
   * 清洗后为空(例如传了个 {@code ..})时返回 null,由调用方换一个名字。
   */
  public static String sanitize(String filename) {
    if (filename == null) {
      return null;
    }
    String name = filename.trim().replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    StringBuilder cleaned = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        continue;
      }
      if ("<>:\"/\\|?*".indexOf(c) >= 0) {
        continue;
      }
      cleaned.append(c);
    }
    String result = cleaned.toString().trim();
    // 纯点号的名字(".", "..", "...")一律不认:它们在文件系统里有特殊含义
    if (result.isEmpty() || result.chars().allMatch(c -> c == '.')) {
      return null;
    }
    while (result.startsWith(".")) {
      result = result.substring(1);
    }
    if (result.isEmpty()) {
      return null;
    }
    if (result.length() > MAX_NAME_CHARS) {
      String extension = extensionOf(result);
      int keep = MAX_NAME_CHARS - extension.length();
      result = result.substring(0, Math.max(1, keep)) + extension;
    }
    return result;
  }

  /** 按内容类型补一个扩展名,用于客户端没给文件名的情况 */
  public static String extensionForContentType(String contentType) {
    if (contentType == null) {
      return "";
    }
    String type = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    switch (type) {
      case "image/jpeg":
      case "image/jpg":
        return ".jpg";
      case "image/png":
        return ".png";
      case "image/webp":
        return ".webp";
      case "image/gif":
        return ".gif";
      case "application/pdf":
        return ".pdf";
      case "text/plain":
        return ".txt";
      case "application/json":
        return ".json";
      case "application/zip":
        return ".zip";
      default:
        return "";
    }
  }

  private static String extensionOf(String name) {
    int dot = name.lastIndexOf('.');
    if (dot <= 0 || dot == name.length() - 1) {
      return "";
    }
    String extension = name.substring(dot);
    return extension.length() > 12 ? "" : extension;
  }

  // ==================== 落盘 / 列表 / 删除 ====================

  /**
   * 把字节写进暂存目录
   *
   * @param requestedName 客户端给的文件名(会被清洗),为空时按 {@code contentType} 生成
   * @param contentType   内容类型,只用于生成扩展名与回执
   * @param data          文件内容
   * @return 落盘信息(含服务端绝对路径);失败时抛 {@link IOException}
   */
  public static Kv save(String requestedName, String contentType, byte[] data) throws IOException {
    if (!ensureDir()) {
      throw new IOException("上传目录不可用:" + dir());
    }
    long limit = maxBytes();
    if (limit > 0 && data.length > limit) {
      throw new IOException("文件超过上限:" + data.length + " 字节 > " + limit + " 字节(" + KEY_MAX_BYTES + ")");
    }
    String name = sanitize(requestedName);
    if (name == null) {
      name = "upload-" + SnowflakeIdUtils.id() + extensionForContentType(contentType);
    }
    Path target = dir().resolve(name).normalize();
    if (!target.startsWith(dir())) {
      throw new IOException("文件名非法:" + requestedName);
    }
    boolean existed = Files.exists(target);
    if (existed && !overwrite()) {
      target = uniqueTarget(target);
      existed = false;
    }
    Files.write(target, data);
    return Kv.by("filename", target.getFileName().toString()).set("path", target.toString())
        .set("relativePath", dir().relativize(target).toString().replace('\\', '/')).set("size", data.length)
        .set("existed", existed).set("sha256", sha256(data)).set("dir", dir().toString())
        .set("contentType", contentType);
  }

  /** 不覆盖时给同名文件找一个没被占用的名字:{@code a.jpg} → {@code a-1.jpg} */
  private static Path uniqueTarget(Path target) {
    String name = target.getFileName().toString();
    String extension = extensionOf(name);
    String stem = extension.isEmpty() ? name : name.substring(0, name.length() - extension.length());
    for (int i = 1; i < 10_000; i++) {
      Path candidate = target.getParent().resolve(stem + "-" + i + extension);
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
    return target.getParent().resolve(stem + "-" + SnowflakeIdUtils.id() + extension);
  }

  /** 暂存目录里的文件列表(按修改时间倒序,最多 500 条) */
  public static List<Kv> list() {
    List<Kv> files = new ArrayList<>();
    Path root = dir();
    if (!Files.isDirectory(root)) {
      return files;
    }
    try (Stream<Path> stream = Files.list(root)) {
      stream.filter(Files::isRegularFile).sorted(Comparator.comparingLong(UploadStore::modifiedAt).reversed())
          .limit(500).forEach(path -> files.add(Kv.by("name", path.getFileName().toString())
              .set("size", sizeOf(path)).set("modifiedAt", modifiedAt(path)).set("path", path.toString())));
    } catch (IOException e) {
      log.warn("读取上传目录失败:{} ({})", root, e.toString());
    }
    return files;
  }

  /** 删除暂存目录里的一个文件;只能删暂存目录里的,绝对路径与外逃路径一律拒绝 */
  public static boolean delete(String name) {
    Path target = resolve(name);
    if (target == null || !target.startsWith(dir()) || target.equals(dir())) {
      return false;
    }
    try {
      return Files.deleteIfExists(target);
    } catch (IOException e) {
      log.warn("删除上传文件失败:{} ({})", target, e.toString());
      return false;
    }
  }

  public static long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return -1;
    }
  }

  public static long modifiedAt(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  /** 内容摘要:客户端可以拿它核对「服务端收到的确实是我发的那份文件」 */
  public static String sha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }

  /** 供测试与排查用:当前配置的一句话描述 */
  public static String describe() {
    return "dir=" + dir() + " enabled=" + enabled() + " maxBytes=" + maxBytes() + " overwrite=" + overwrite();
  }
}
