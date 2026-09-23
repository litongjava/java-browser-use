package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.junit.Assume;
import org.junit.Test;

import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * 用「用户自己安装的 Google Chrome + 用户自己的 profile」真的起一次浏览器
 *
 * <p>
 * 默认不跑:它会往用户真实的 Chrome profile 里写东西(历史记录、会话),不该在每次构建里发生。
 * 需要在这台机器上确认「用上用户现成的登录态」这条路通不通时,显式加参数跑:
 *
 * <pre>
 * mvn test -Dtest=ChromeUserProfileSmokeTest -Dsmoke.chrome.userProfile=true
 * </pre>
 *
 * <p>
 * 注意这条路在 Chrome 136 及以后的版本上**默认是不通的**:Chrome 不允许在默认用户数据目录上开启远程
 * 调试(pipe 与 port 都拒绝,Playwright/Selenium/Puppeteer 一视同仁),除非这台机器加了企业策略
 * {@code RemoteDebuggingAllowed=1}。不通时 {@code start} 会带着 note 退回托管 profile,这个测试会
 * 失败并把 Chrome 的原话打出来 —— 那正是它存在的意义:把「没用上用户的登录态」这件事说清楚。
 */
public class ChromeUserProfileSmokeTest {

  private static final String ENABLE = "smoke.chrome.userProfile";

  @Test
  public void realChromeWithUserProfileWorks() {
    Assume.assumeTrue("默认跳过:加 -D" + ENABLE + "=true 才会用真实 Chrome profile 起浏览器", Boolean.getBoolean(ENABLE));

    Path executable = ChromeBrowser.executablePath();
    Assume.assumeTrue("这台机器上没有安装 Google Chrome,跳过", executable != null);
    Path userDataDir = ChromeBrowser.userDataDir();
    Assume.assumeTrue("没有找到 Google Chrome 的用户数据目录,跳过", userDataDir != null);
    Assume.assumeFalse("Google Chrome 正在运行,用户 profile 被占用,跳过", ChromeBrowser.profileInUse(userDataDir));

    System.setProperty(ChromeBrowser.KEY_USE_USER_PROFILE, "true");
    ChromeBrowser.resetForTests();

    PlaywrightService service = new PlaywrightService();
    long id = service.start(900001L, true);
    try {
      Kv browser = service.browserInfo(id);
      String note = browser.getStr("note");
      assertEquals("应当用上用户自己的 profile(没有就是 Chrome 拒绝了默认目录上的远程调试;note: " + note + ")", true,
          browser.get("userProfile"));
      assertEquals("应当走自己拉 Chrome + CDP 这条路", "cdp", browser.getStr("mode"));
      assertEquals("应当用上本机安装的 Google Chrome", true, browser.get("chrome"));
      assertEquals("profile 目录应当是用户的 User Data 目录", userDataDir.toAbsolutePath().toString(),
          browser.getStr("profileDir"));
      assertEquals(executable.toAbsolutePath().toString(), browser.getStr("executable"));

      RespBodyVo navigated = service.goToUrl(id, "data:text/html,<title>profile smoke</title><input id='x'>");
      assertTrue(navigated.getMsg(), navigated.isOk());
      RespBodyVo title = service.getTitle(id);
      assertTrue(title.getMsg(), title.isOk());
      assertEquals("profile smoke", ((Kv) title.getData()).getStr("title"));

      // 用户 profile 里本来就有 cookie,能读到才说明「用的是人家那份登录态」
      RespBodyVo cookies = service.getCookies(id, null);
      assertTrue(cookies.getMsg(), cookies.isOk());
      int count = ((Number) ((Kv) cookies.getData()).get("count")).intValue();
      assertTrue("用户 profile 里应当能读到 cookie,实际 " + count + " 个", count > 0);
    } finally {
      service.close(id);
      System.clearProperty(ChromeBrowser.KEY_USE_USER_PROFILE);
      ChromeBrowser.resetForTests();
    }
  }
}
