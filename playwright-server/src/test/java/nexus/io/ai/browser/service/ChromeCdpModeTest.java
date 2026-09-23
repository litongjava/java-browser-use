package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * 用用户 profile 的那条路(自己拉 Chrome + {@code --remote-debugging-port} + {@code connectOverCDP})
 *
 * <p>
 * 这条路的由来:Chrome 136 起**不允许在默认用户数据目录上开远程调试**,Playwright 的
 * {@code launchPersistentContext}(走 {@code --remote-debugging-pipe})在用户自己的 profile 上会
 * 直接超时;想用用户现成的登录态就只能自己拉 Chrome、从 stderr 里读调试端口,再用 CDP 接上。
 *
 * <p>
 * 测试里用的是**临时目录**(非默认用户数据目录),这样 Chrome 会允许远程调试 —— 验证的是这条代码
 * 路径本身(拉进程、读端口、接 CDP、关掉时把进程收掉)。真机上想用用户自己的 profile,还需要给这台
 * 机器加企业策略 {@code RemoteDebuggingAllowed=1},否则 Chrome 会拒绝,{@code start} 会带着 note
 * 退回托管 profile(见 {@link ChromeUserProfileSmokeTest})。
 *
 * <p>
 * 只写一个测试方法:共享浏览器是静态的,两个方法之间会互相看见对方留下的浏览器。
 */
public class ChromeCdpModeTest {

  private Path userDataDir;
  private PlaywrightService service;

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue("这台机器上没有安装 Google Chrome,跳过", ChromeBrowser.executablePath() != null);
    userDataDir = Files.createTempDirectory("chrome-cdp-mode");
    System.setProperty(ChromeBrowser.KEY_USE_USER_PROFILE, "true");
    System.setProperty(ChromeBrowser.KEY_USER_DATA_DIR, userDataDir.toAbsolutePath().toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
  }

  @After
  public void tearDown() throws IOException {
    System.clearProperty(ChromeBrowser.KEY_USE_USER_PROFILE);
    System.clearProperty(ChromeBrowser.KEY_USER_DATA_DIR);
    ChromeBrowser.resetForTests();
    if (userDataDir != null) {
      deleteRecursively(userDataDir);
    }
  }

  @Test
  public void cdpModeWorksAndReleasesProfileOnClose() throws InterruptedException {
    long id = service.start(910001L, true);
    try {
      Kv browser = service.browserInfo(id);
      assertEquals("应当走自己拉 Chrome + CDP 这条路", "cdp", browser.getStr("mode"));
      assertEquals("应当用上本机安装的 Google Chrome", true, browser.get("chrome"));
      assertEquals("应当用上指定的用户数据目录", userDataDir.toAbsolutePath().toString(), browser.getStr("profileDir"));
      assertNotNull("应当能拿到 Chrome 可执行文件", browser.getStr("executable"));

      RespBodyVo navigated = service.goToUrl(id, "data:text/html,<title>cdp mode</title><input id='x'>");
      assertTrue(navigated.getMsg(), navigated.isOk());
      RespBodyVo title = service.getTitle(id);
      assertTrue(title.getMsg(), title.isOk());
      assertEquals("cdp mode", ((Kv) title.getData()).getStr("title"));

      // 新页签、cookie 这些常规动作在 CDP 模式下也要能用
      RespBodyVo opened = service.newTab(id, "about:blank");
      assertTrue(opened.getMsg(), opened.isOk());
      RespBodyVo cookies = service.getCookies(id, null);
      assertTrue(cookies.getMsg(), cookies.isOk());
      assertNotNull(((Kv) cookies.getData()).get("count"));

      // 这个 Chrome 是我们自己拉起来的(测试 JVM 的子进程),关掉最后一个任务后必须退出,
      // 否则用户的目录会被一直占着
      List<ProcessHandle> chrome = spawnedChromeProcesses();
      assertEquals("应当由我们自己拉起一个 Chrome 进程:" + describe(chrome), 1, chrome.size());
      service.close(id);
      for (int i = 0; i < 50 && chrome.get(0).isAlive(); i++) {
        Thread.sleep(100);
      }
      assertFalse("最后一个任务关闭后 Chrome 进程应当退出", chrome.get(0).isAlive());
      return;
    } finally {
      service.close(id);
    }
  }

  /** 测试 JVM 直接拉起来的 Chrome 进程(用户自己开的 Chrome 不是我们的子进程,不会算进来) */
  private static List<ProcessHandle> spawnedChromeProcesses() {
    return ProcessHandle.current().children()
        .filter(handle -> handle.info().command().map(command -> command.toLowerCase().contains("chrome")).orElse(false))
        .toList();
  }

  private static String describe(List<ProcessHandle> handles) {
    StringBuilder text = new StringBuilder();
    for (ProcessHandle handle : handles) {
      text.append(handle.pid()).append(':').append(handle.info().commandLine().orElse("?")).append(' ');
    }
    return text.toString();
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // 被 Chrome 占着的文件删不掉就算了,临时目录由系统回收
        }
      }
    }
  }
}
