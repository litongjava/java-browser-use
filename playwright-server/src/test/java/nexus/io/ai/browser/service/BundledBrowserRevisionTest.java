package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;

/**
 * 校验 browser.properties 里的 Chromium 修订号与当前 Playwright 版本要求的一致
 *
 * <p>发行包按这个修订号去 CDN 下载浏览器并内嵌进 jar。如果升级 Playwright 依赖时忘了同步这个值,
 * 内嵌的 Chromium 与驱动就对不上版本,只有用户运行起来才会发现。这里在构建阶段拦住它。
 *
 * <p>Playwright 把每个浏览器要求的修订号写在 driver-bundle jar 的
 * {@code driver/<平台>/package/browsers.json} 里,所以直接从测试类路径上的这个 jar 里读。
 */
public class BundledBrowserRevisionTest {

  private static final String PROPERTIES_RESOURCE = "browser.properties";

  @Test
  public void revisionMatchesPlaywrightDriver() throws Exception {
    String declared = declaredRevision();
    assertNotNull("browser.properties 里缺少 chromium.revision", declared);

    String actual = playwrightChromiumRevision();
    Assume.assumeTrue("测试类路径上没有 driver-bundle jar(例如 IDE 里单独跑这个类),跳过", actual != null);

    assertEquals("browser.properties 的 chromium.revision 与当前 Playwright 版本不一致,"
        + "升级 Playwright 后要同步这个值并重新打包", actual, declared);
  }

  private static String declaredRevision() throws Exception {
    try (InputStream in = BundledBrowserRevisionTest.class.getClassLoader()
        .getResourceAsStream(PROPERTIES_RESOURCE)) {
      assertNotNull("类路径上找不到 " + PROPERTIES_RESOURCE, in);
      Properties props = new Properties();
      props.load(in);
      String revision = props.getProperty("chromium.revision");
      return revision == null ? null : revision.trim();
    }
  }

  /** 从 driver-bundle jar 里读 chromium 的 revision */
  private static String playwrightChromiumRevision() throws Exception {
    Path driverJar = findDriverBundleJar();
    if (driverJar == null) {
      return null;
    }
    try (ZipFile zip = new ZipFile(driverJar.toFile())) {
      ZipEntry entry = zip.getEntry("driver/win32_x64/package/browsers.json");
      if (entry == null) {
        return null;
      }
      String json;
      try (InputStream in = zip.getInputStream(entry)) {
        json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      // 只取第一个 "name": "chromium" 后面的 revision,避免引入 JSON 解析依赖
      int nameAt = json.indexOf("\"name\": \"chromium\"");
      assertTrue("browsers.json 里没有 chromium 条目", nameAt >= 0);
      int revisionAt = json.indexOf("\"revision\":", nameAt);
      assertTrue("browsers.json 的 chromium 条目里没有 revision", revisionAt >= 0);
      int start = json.indexOf('"', revisionAt + "\"revision\":".length()) + 1;
      int end = json.indexOf('"', start);
      return json.substring(start, end);
    }
  }

  /** driver-bundle jar 就在测试类路径上,按文件名找 */
  private static Path findDriverBundleJar() {
    String classpath = System.getProperty("java.class.path", "");
    for (String part : classpath.split(java.io.File.pathSeparator)) {
      String name = Paths.get(part).getFileName() == null ? "" : Paths.get(part).getFileName().toString();
      if (name.startsWith("driver-bundle-") && name.endsWith(".jar") && Files.isRegularFile(Paths.get(part))) {
        return Paths.get(part);
      }
    }
    return null;
  }
}
