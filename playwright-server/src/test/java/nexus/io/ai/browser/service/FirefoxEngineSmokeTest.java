package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * Firefox 引擎真正能起浏览器的冒烟测试
 *
 * <p>
 * 默认不跑(需要 Playwright 自带的那份 Firefox 已经在缓存里,首次使用会去外网下载):
 *
 * <pre>
 * mvn test -Dtest=FirefoxEngineSmokeTest -Dsmoke.firefox=true
 * </pre>
 *
 * <p>
 * 验证的是「{@code browser.engine=firefox} 这条路走不走得通」:起得来、{@code start} 的返回里报的是
 * firefox、页面能读能写、profile 目录是托管那份;以及两条**引擎能力差异**要有明确的中文失败原因,
 * 而不是让调用方对着 Playwright 的英文报错猜:
 * <ul>
 * <li>{@code pdf} 只支持 Chromium,Firefox 下必须直接说清楚;</li>
 * <li>{@code browser.chrome.useUserProfile}(用用户自己的 Chrome profile)与 Firefox 无关,不该被
 * 误当成「用上了用户的登录态」。</li>
 * </ul>
 *
 * <p>
 * 用的是**临时 profile 目录**:默认那份托管 profile 是开发机上真正在用的登录态,跑测试不该往里写东西。
 */
public class FirefoxEngineSmokeTest {

  private Path profileDir;
  private PlaywrightService service;

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue("默认不跑,加 -Dsmoke.firefox=true 才跑", Boolean.getBoolean("smoke.firefox"));
    profileDir = Files.createTempDirectory("firefox-engine-smoke");
    System.setProperty(BrowserEngine.KEY_ENGINE, "firefox");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toAbsolutePath().toString());
    // 故意把「用用户自己的 Chrome profile」打开:Firefox 这条路必须完全无视它
    System.setProperty(ChromeBrowser.KEY_USE_USER_PROFILE, "true");
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
  }

  @After
  public void tearDown() throws IOException {
    System.clearProperty(BrowserEngine.KEY_ENGINE);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    System.clearProperty(ChromeBrowser.KEY_USE_USER_PROFILE);
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
  public void firefoxLaunchesAndDrivesAPage() {
    long id = service.start(920001L, true);
    try {
      Kv browser = service.browserInfo(id);
      assertNotNull("start 之后应当能拿到浏览器信息", browser);
      assertEquals("引擎应当是 firefox", "firefox", browser.getStr("engine"));
      assertFalse("Firefox 不是本机安装的 Google Chrome", browser.getBoolean("chrome"));
      assertFalse("Firefox 这条路没有用户 profile 的概念", browser.getBoolean("userProfile"));
      assertEquals("托管 profile 模式", "managed", browser.getStr("mode"));
      assertNull("Chromium 的 --profile-directory 不该出现在 Firefox 的返回里", browser.get("profileDirectory"));
      assertEquals(profileDir.toAbsolutePath().normalize().toString(),
          Path.of(browser.getStr("profileDir")).toAbsolutePath().normalize().toString());

      RespBodyVo opened = service.goToUrl(id, "data:text/html,<title>firefox-ok</title><h1 id=h>hello</h1>");
      assertTrue("打开页面失败:" + opened.getMsg(), opened.isOk());

      RespBodyVo title = service.getTitle(id);
      assertTrue("读标题失败:" + title.getMsg(), title.isOk());
      assertTrue("标题应当是 firefox-ok,实际是:" + title.getData(),
          String.valueOf(title.getData()).contains("firefox-ok"));

      RespBodyVo evaluated = service.executeJs(id, "navigator.userAgent");
      assertTrue("执行 JS 失败:" + evaluated.getMsg(), evaluated.isOk());
      assertTrue("UA 里应当出现 Firefox,实际是:" + evaluated.getData(),
          String.valueOf(evaluated.getData()).contains("Firefox"));
    } finally {
      service.close(id);
    }
  }

  /** pdf 只有 Chromium 支持:Firefox 下要给出明确原因,而不是让调用方猜 */
  @Test
  public void pdfExplainsItIsChromiumOnly() {
    long id = service.start(920002L, true);
    try {
      RespBodyVo pdf = service.pdf(id, null);
      assertFalse("Firefox 下 pdf 应当失败", pdf.isOk());
      assertTrue("失败原因要提到 Chromium,实际是:" + pdf.getMsg(), pdf.getMsg().contains("Chromium"));
      assertTrue("失败原因要提到 browser.engine,实际是:" + pdf.getMsg(), pdf.getMsg().contains("browser.engine"));
    } finally {
      service.close(id);
    }
  }
}