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
 * {@code browser=edge} 这条路真正能起浏览器的冒烟测试
 *
 * <p>
 * 默认不跑(要求这台机器上装了 Microsoft Edge):
 *
 * <pre>
 * mvn test -Dtest=EdgeBrowserSmokeTest -Dsmoke.edge=true
 * </pre>
 *
 * <p>
 * 验证的是「{@code start} 时选 Edge」这件事真的成立,而不是只在配置里成立:起得来、{@code start} 的返回里
 * 报的是 {@code edge}(不是 chrome)、profile 用的是 Edge 自己那份、页面能读能写、<b>UA 里带 {@code Edg/}</b>
 * —— 最后这条是关键:如果实现里顺手把 UA 改成了 Chrome(内置 Chromium 那条路就是这么做的),站点看到的就
 * 还是 Chrome,「换个浏览器试试」这个需求等于没实现。
 *
 * <p>
 * 还有一条隐形的验证:<b>Edge 是开着沙箱跑起来的</b>。它走的是自己拉进程 + CDP(见
 * {@code PlaywrightService.launchOverCdp}),命令行由 {@code cdpArgs} 拼出来,而那条路径在开沙箱时
 * 不带 {@code --no-sandbox}(单元测试盯着)。如果哪天 Edge 被改回 Playwright 的管道启动,开沙箱会让它
 * 启动即退出 —— 这个测试会立刻红。
 *
 * <p>
 * 用的是**临时 profile 目录**:默认那份是开发机上真正在用的登录态,跑测试不该往里写东西。
 */
public class EdgeBrowserSmokeTest {

  private Path profileDir;
  private PlaywrightService service;

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue("默认不跑,加 -Dsmoke.edge=true 才跑", Boolean.getBoolean("smoke.edge"));
    Assume.assumeTrue("这台机器上没有 Microsoft Edge,跳过", EdgeBrowser.executablePath() != null);
    profileDir = Files.createTempDirectory("edge-smoke");
    System.setProperty(EdgeBrowser.KEY_PROFILE_DIR, profileDir.toAbsolutePath().toString());
    EdgeBrowser.resetForTests();
    service = new PlaywrightService();
  }

  @After
  public void tearDown() throws IOException {
    System.clearProperty(EdgeBrowser.KEY_PROFILE_DIR);
    EdgeBrowser.resetForTests();
    if (profileDir != null) {
      try (Stream<Path> paths = Files.walk(profileDir)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void edgeLaunchesAndDrivesAPage() {
    long id = service.start(930001L, true, "edge");
    try {
      Kv browser = service.browserInfo(id);
      assertNotNull("start 之后应当能拿到浏览器信息", browser);
      assertEquals("这次用的应当是 edge", "edge", browser.getStr("type"));
      assertEquals("引擎仍然是 chromium(Edge 是 Chromium 系)", "chromium", browser.getStr("engine"));
      assertFalse("Edge 不是本机安装的 Google Chrome", browser.getBoolean("chrome"));
      assertFalse("Edge 这条路没有「用户自己的 profile」这个概念", browser.getBoolean("userProfile"));
      assertEquals("Edge 走的是自己拉进程 + CDP（这样沙箱才能开着，见 PlaywrightService.chromiumSandbox）", "cdp",
          browser.getStr("mode"));
      assertEquals("profile 目录应当是 Edge 自己那份", profileDir.toAbsolutePath().normalize().toString(),
          Path.of(browser.getStr("profileDir")).toAbsolutePath().normalize().toString());
      assertTrue("可执行文件应当是 msedge:" + browser.getStr("executable"),
          browser.getStr("executable").toLowerCase().contains("msedge"));

      RespBodyVo opened = service.goToUrl(id, "data:text/html,<title>edge-ok</title><h1 id=h>hello</h1>");
      assertTrue("打开页面失败:" + opened.getMsg(), opened.isOk());

      RespBodyVo title = service.getTitle(id);
      assertTrue("读标题失败:" + title.getMsg(), title.isOk());
      assertTrue("标题应当是 edge-ok,实际是:" + title.getData(), String.valueOf(title.getData()).contains("edge-ok"));

      // 关键断言:UA 必须还是 Edge 的。改掉 UA 就等于「换了浏览器但站点看不出来」
      RespBodyVo evaluated = service.executeJs(id, "navigator.userAgent");
      assertTrue("执行 JS 失败:" + evaluated.getMsg(), evaluated.isOk());
      String ua = String.valueOf(evaluated.getData());
      assertTrue("UA 里应当出现 Edg/,实际是:" + ua, ua.contains("Edg/"));
      assertFalse("UA 不该被伪装成 Chrome(那样这次换浏览器就白换了):" + ua,
          ua.contains("Chrome/127") && !ua.contains("Edg/"));
    } finally {
      service.close(id);
    }
  }

  /** Edge 是 Chromium 系,pdf 这条路必须能走通(Firefox 那条路是不支持的) */
  @Test
  public void edgeSupportsPdf() {
    long id = service.start(930002L, true, "edge");
    try {
      RespBodyVo opened = service.goToUrl(id, "data:text/html,<title>edge-pdf</title><p>pdf</p>");
      assertTrue("打开页面失败:" + opened.getMsg(), opened.isOk());
      Path target = profileDir.resolve("edge.pdf");
      RespBodyVo pdf = service.pdf(id, target.toString());
      assertTrue("Edge 是 Chromium 系,pdf 应当成功,实际:" + pdf.getMsg(), pdf.isOk());
      assertTrue("PDF 应当真的落盘了:" + target, Files.isRegularFile(target));
    } finally {
      service.close(id);
    }
  }
}
