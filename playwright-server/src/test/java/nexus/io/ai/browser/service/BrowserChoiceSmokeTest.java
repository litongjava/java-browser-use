package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * {@code browser=chromium} 这条路真正能起浏览器的冒烟测试
 *
 * <p>
 * 默认不跑(要求内置 Chromium 可用:发行包里内嵌的那份,开发态是 Playwright 自己下载的那份):
 *
 * <pre>
 * mvn test -Dtest=BrowserChoiceSmokeTest -Dsmoke.chromium=true
 * </pre>
 *
 * <p>
 * 验证的是「显式要内置 Chromium 就<b>真的</b>不碰本机 Chrome」:返回里 {@code type=chromium}、
 * {@code chrome=false}、可执行文件是内置那份(而不是 {@code chrome.exe})、UA 是服务伪装出来的 Chrome
 * (内置 Chromium 版本与真实 Chrome 不一致,见 {@code buildOptions})、页面能读能写、pdf 能出。
 *
 * <p>
 * 用的是**临时 profile 目录**,不碰开发机上那份登录态。
 */
public class BrowserChoiceSmokeTest {

  private Path profileDir;
  private PlaywrightService service;

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue("默认不跑,加 -Dsmoke.chromium=true 才跑", Boolean.getBoolean("smoke.chromium"));
    Path bundled = BundledBrowser.executablePath();
    Assume.assumeTrue("内置 Chromium 不可用(发行包里没有内嵌,Playwright 也没下载),跳过", bundled != null);
    profileDir = Files.createTempDirectory("chromium-smoke");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toAbsolutePath().toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
  }

  @After
  public void tearDown() throws IOException {
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
    if (profileDir != null) {
      try (Stream<Path> paths = Files.walk(profileDir)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void bundledChromiumLaunchesAndDoesNotTouchLocalChrome() {
    Path localChrome = ChromeBrowser.executablePath();
    long id = service.start(950001L, true, "chromium");
    try {
      Kv browser = service.browserInfo(id);
      assertNotNull("start 之后应当能拿到浏览器信息", browser);
      assertEquals("这次用的应当是内置 Chromium", "chromium", browser.getStr("type"));
      assertFalse("内置 Chromium 不是本机安装的 Google Chrome", browser.getBoolean("chrome"));
      String executable = browser.getStr("executable");
      assertNotNull("内置 Chromium 这条路应当能报出可执行文件:" + browser, executable);
      assertFalse("不能用到本机安装的 Chrome:" + executable,
          localChrome != null && Path.of(executable).toAbsolutePath().normalize()
              .equals(localChrome.toAbsolutePath().normalize()));
      assertTrue("应当报出没能用上本机 Chrome 的原因,实际:" + browser.getStr("note"),
          browser.getStr("note") != null && browser.getStr("note").contains("browser=chromium"));

      RespBodyVo opened = service.goToUrl(id, "data:text/html,<title>chromium-ok</title><h1>hi</h1>");
      assertTrue("打开页面失败:" + opened.getMsg(), opened.isOk());
      RespBodyVo title = service.getTitle(id);
      assertTrue("读标题失败:" + title.getMsg(), title.isOk());
      assertTrue("标题应当是 chromium-ok,实际是:" + title.getData(),
          String.valueOf(title.getData()).contains("chromium-ok"));

      // 内置 Chromium 会把自己伪装成 Windows 上的 Chrome(版本与真实 Chrome 不一致)
      RespBodyVo ua = service.executeJs(id, "navigator.userAgent");
      assertTrue("执行 JS 失败:" + ua.getMsg(), ua.isOk());
      assertTrue("内置 Chromium 的 UA 应当是伪装过的 Chrome,实际是:" + ua.getData(),
          String.valueOf(ua.getData()).contains("Chrome/127"));

      Path target = profileDir.resolve("chromium.pdf");
      RespBodyVo pdf = service.pdf(id, target.toString());
      assertTrue("内置 Chromium 是 Chromium 系,pdf 应当成功,实际:" + pdf.getMsg(), pdf.isOk());
      assertTrue("PDF 应当真的落盘了:" + target, Files.isRegularFile(target));
    } finally {
      service.close(id);
    }
  }
}
