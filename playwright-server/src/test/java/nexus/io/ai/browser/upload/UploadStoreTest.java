package nexus.io.ai.browser.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;

import nexus.io.ai.browser.service.ChromeBrowser;

/**
 * 文件暂存区的行为
 *
 * <p>这个端点把客户端发来的字节落到服务端磁盘上,所以「路径能不能逃出暂存目录」「文件名清洗得对不对」
 * 「上限有没有真的生效」都要有测试兜着 —— 这类问题出错就是安全问题。
 */
public class UploadStoreTest {

  private static Path tempDir;

  @BeforeClass
  public static void setUp() throws IOException {
    tempDir = Files.createTempDirectory("browser-use-upload-test");
    System.setProperty(UploadStore.KEY_DIR, tempDir.toString());
    ChromeBrowser.resetForTests();
  }

  @AfterClass
  public static void tearDown() throws IOException {
    System.clearProperty(UploadStore.KEY_DIR);
    System.clearProperty(UploadStore.KEY_MAX_BYTES);
    System.clearProperty(UploadStore.KEY_OVERWRITE);
    ChromeBrowser.resetForTests();
    if (tempDir != null) {
      try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  // ==================== 文件名清洗 ====================

  @Test
  public void sanitizeStripsPathsAndReservedCharacters() {
    assertEquals("只留基本名", "a.jpg", UploadStore.sanitize("../../etc/a.jpg"));
    assertEquals("Windows 反斜杠同样处理", "a.jpg", UploadStore.sanitize("C:\\Users\\me\\a.jpg"));
    assertEquals("去掉 Windows 保留字符", "ab.jpg", UploadStore.sanitize("a<b>:\"|?*.jpg".replace("*", "")));
    assertEquals("去掉控制字符", "ab.txt", UploadStore.sanitize("a\u0001b.txt"));
    assertEquals("去掉开头的点", "hidden.txt", UploadStore.sanitize(".hidden.txt"));
  }

  /** 中文文件名必须保留:商标图样这类文件的文件名本来就是汉字 */
  @Test
  public void sanitizeKeepsChineseNames() {
    assertEquals("示例图样.jpg", UploadStore.sanitize("示例图样.jpg"));
    assertEquals("带空格与括号也留着", "示例 图样(1).jpg", UploadStore.sanitize("示例 图样(1).jpg"));
  }

  @Test
  public void sanitizeRejectsMeaninglessNames() {
    assertNull("纯点号的名字不认", UploadStore.sanitize(".."));
    assertNull("空名字不认", UploadStore.sanitize("   "));
    assertNull("null 不认", UploadStore.sanitize(null));
  }

  @Test
  public void sanitizeTruncatesLongNamesButKeepsExtension() {
    String longName = "a".repeat(300) + ".jpg";
    String cleaned = UploadStore.sanitize(longName);
    assertNotNull(cleaned);
    assertTrue("长度要压到上限内,实际 " + cleaned.length(), cleaned.length() <= 120);
    assertTrue("扩展名要保住,实际:" + cleaned, cleaned.endsWith(".jpg"));
  }

  // ==================== 路径解析 ====================

  @Test
  public void relativePathResolvesInsideUploadDir() {
    Path resolved = UploadStore.resolve("a.jpg");
    assertNotNull(resolved);
    assertEquals(tempDir.toAbsolutePath().normalize().resolve("a.jpg"), resolved);
  }

  @Test
  public void absolutePathIsKeptAsIs() {
    Path absolute = tempDir.resolve("b.jpg").toAbsolutePath();
    assertEquals(absolute.normalize(), UploadStore.resolve(absolute.toString()));
  }

  /** 相对路径不许逃出暂存目录 —— 这是这个端点最基本的安全边界 */
  @Test
  public void relativePathCannotEscapeUploadDir() {
    assertNull("../../ 这种写法要判非法", UploadStore.resolve("../../secret.txt"));
    assertNull("中间夹一段 .. 同样非法", UploadStore.resolve("sub/../../secret.txt"));
    assertNull("空路径非法", UploadStore.resolve(""));
  }

  // ==================== 落盘 ====================

  @Test
  public void saveWritesFileAndReportsServerPath() throws IOException {
    byte[] data = "hello 图样".getBytes(StandardCharsets.UTF_8);
    Kv saved = UploadStore.save("示例.txt", "text/plain", data);
    assertEquals("示例.txt", saved.getStr("filename"));
    assertEquals("示例.txt", saved.getStr("relativePath"));
    assertEquals((long) data.length, ((Number) saved.get("size")).longValue());
    assertEquals(false, saved.get("existed"));
    assertEquals("内容要原样落盘", "hello 图样", Files.readString(Path.of(saved.getStr("path"))));
    assertNotNull("要给出内容摘要,客户端可据此核对", saved.getStr("sha256"));
    assertTrue("路径必须在暂存目录里", Path.of(saved.getStr("path")).startsWith(tempDir));
  }

  /** 客户端没给文件名时按内容类型补扩展名,不能让文件叫「upload-<id>」而没法判断类型 */
  @Test
  public void missingNameFallsBackToGeneratedNameWithExtension() throws IOException {
    Kv saved = UploadStore.save(null, "image/jpeg", new byte[] { 1, 2, 3 });
    assertTrue("要补 .jpg,实际:" + saved.getStr("filename"), saved.getStr("filename").endsWith(".jpg"));
  }

  @Test
  public void sameNameOverwritesByDefault() throws IOException {
    UploadStore.save("same.txt", "text/plain", "first".getBytes(StandardCharsets.UTF_8));
    Kv second = UploadStore.save("same.txt", "text/plain", "second".getBytes(StandardCharsets.UTF_8));
    assertEquals(true, second.get("existed"));
    assertEquals("second", Files.readString(Path.of(second.getStr("path"))));
  }

  @Test
  public void overwriteOffRenamesInstead() throws IOException {
    System.setProperty(UploadStore.KEY_OVERWRITE, "false");
    ChromeBrowser.resetForTests();
    try {
      UploadStore.save("keep.txt", "text/plain", "first".getBytes(StandardCharsets.UTF_8));
      Kv second = UploadStore.save("keep.txt", "text/plain", "second".getBytes(StandardCharsets.UTF_8));
      assertEquals("keep-1.txt", second.getStr("filename"));
      assertEquals(false, second.get("existed"));
    } finally {
      System.clearProperty(UploadStore.KEY_OVERWRITE);
      ChromeBrowser.resetForTests();
    }
  }

  @Test
  public void sizeLimitIsEnforced() {
    System.setProperty(UploadStore.KEY_MAX_BYTES, "4");
    ChromeBrowser.resetForTests();
    try {
      try {
        UploadStore.save("big.txt", "text/plain", "12345".getBytes(StandardCharsets.UTF_8));
        throw new AssertionError("超过上限的文件不该落盘");
      } catch (IOException e) {
        assertTrue("失败原因要说清是超限,实际:" + e.getMessage(), e.getMessage().contains("超过上限"));
      }
    } finally {
      System.clearProperty(UploadStore.KEY_MAX_BYTES);
      ChromeBrowser.resetForTests();
    }
  }

  // ==================== 列表与删除 ====================

  @Test
  public void listAndDelete() throws IOException {
    UploadStore.save("list-me.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
    List<Kv> files = UploadStore.list();
    assertTrue("列表里应当有刚传的文件", files.stream().anyMatch(f -> "list-me.txt".equals(f.getStr("name"))));
    Kv entry = files.stream().filter(f -> "list-me.txt".equals(f.getStr("name"))).findFirst().orElseThrow();
    assertTrue("列表要带大小与修改时间", entry.containsKey("size") && entry.containsKey("modifiedAt"));

    assertTrue("删除暂存目录里的文件", UploadStore.delete("list-me.txt"));
    assertFalse("删第二次应当返回 false", UploadStore.delete("list-me.txt"));
  }

  /** 删除只允许作用于暂存目录里的文件,不能拿它去删服务端别处的文件 */
  @Test
  public void deleteRefusesPathsOutsideUploadDir() throws IOException {
    Path outside = Files.createTempFile("browser-use-outside", ".txt");
    try {
      assertFalse("绝对路径指向暂存目录外时拒绝", UploadStore.delete(outside.toString()));
      assertTrue("文件还在", Files.exists(outside));
      assertFalse("逃逸路径同样拒绝", UploadStore.delete("../../" + outside.getFileName()));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  public void contentTypesMapToExtensions() {
    assertEquals(".jpg", UploadStore.extensionForContentType("image/jpeg"));
    assertEquals(".png", UploadStore.extensionForContentType("image/png; charset=binary"));
    assertEquals(".pdf", UploadStore.extensionForContentType("application/pdf"));
    assertEquals("", UploadStore.extensionForContentType("application/octet-stream"));
    assertEquals("", UploadStore.extensionForContentType(null));
  }

  @Test
  public void sha256IsStableAndDistinguishesContent() {
    byte[] a = "abc".getBytes(StandardCharsets.UTF_8);
    byte[] b = "abd".getBytes(StandardCharsets.UTF_8);
    assertEquals(UploadStore.sha256(a), UploadStore.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    assertFalse(UploadStore.sha256(a).equals(UploadStore.sha256(b)));
    assertEquals("SHA-256 是 64 个十六进制字符", 64, UploadStore.sha256(a).length());
  }
}
