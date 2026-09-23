package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

public class PlaywrightServiceTest {

  @Test
  public void expressionIsEvaluatedAsIs() {
    assertEquals("document.title", PlaywrightService.normalizeScript("  document.title  "));
    assertEquals("1 + 1", PlaywrightService.normalizeScript("1 + 1"));
    assertEquals("document.title;", PlaywrightService.normalizeScript("document.title;"));
  }

  @Test
  public void functionIsEvaluatedAsIs() {
    assertEquals("() => document.title", PlaywrightService.normalizeScript("() => document.title"));
    assertEquals("() => { return document.title; }",
        PlaywrightService.normalizeScript("() => { return document.title; }"));
    assertEquals("function () { return 1; }", PlaywrightService.normalizeScript("function () { return 1; }"));
    assertEquals("async () => await fetch('/ping')",
        PlaywrightService.normalizeScript("async () => await fetch('/ping')"));
  }

  @Test
  public void statementWithReturnIsWrapped() {
    assertEquals("() => {const a = 40; return a + 2;}",
        PlaywrightService.normalizeScript("const a = 40; return a + 2;"));
    assertEquals("() => {return 42}", PlaywrightService.normalizeScript("return 42"));
    assertEquals("() => {if (a) {return 1} return 2;}", PlaywrightService.normalizeScript("if (a) {return 1} return 2;"));
  }

  @Test
  public void returnInsideStringIsNotTreatedAsStatement() {
    assertEquals("document.querySelector('[data-return]').value",
        PlaywrightService.normalizeScript("document.querySelector('[data-return]').value"));
  }

  @Test
  public void briefMessageKeepsFirstLineOfScriptError() {
    String message = "Error {\n  message='TypeError: Cannot read properties of null (reading 'click')\n"
        + "    at eval (eval at evaluate (:291:30), <anonymous>:1:32)\n  name='Error\n  stack='...\n}";
    assertEquals("TypeError: Cannot read properties of null (reading 'click')", PlaywrightService.briefMessage(message));
    assertEquals("Target page, context or browser has been closed",
        PlaywrightService.briefMessage("Target page, context or browser has been closed\nmore detail"));
    assertEquals("未知错误", PlaywrightService.briefMessage(null));
    assertEquals("未知错误", PlaywrightService.briefMessage("   "));
  }

  /**
   * 这几个标志会让 Chrome 打印「You are using an unsupported command-line flag ... Stability and
   * security will suffer.」并挂提示条,一个都不能出现在启动参数里。
   */
  @Test
  public void noUnsupportedCommandLineFlags() {
    List<String> args = PlaywrightService.chromiumArgs();
    assertFalse("不能传 --no-sandbox,Chrome 会打印不受支持的命令行标志警告", args.contains("--no-sandbox"));
    assertFalse("不能传 --disable-web-security,同样会触发那条警告", args.contains("--disable-web-security"));
    assertFalse("不能传 --disable-infobars", args.contains("--disable-infobars"));
    assertFalse("不能传 AutomationControlled 禁用参数,会触发不受支持的命令行标志提示",
        args.contains("--disable-blink-features=AutomationControlled"));
  }

  /**
   * 沙箱默认按平台来:普通桌面(Windows / macOS)开着,Linux 上关着
   *
   * <p>
   * 这条断言盯的是「别悄悄改回去」:沙箱关掉之后不只是隔离变差,窗口上还会多一条
   * {@code You are using an unsupported command-line flag: --no-sandbox} 提示条。Linux 保持关闭是因为
   * 容器里通常以 root 运行,Chrome 会直接拒绝启动。
   *
   * <p>
   * Edge 也一样开着 —— 前提是它走 CDP(端口)那条路,见下一条断言。
   */
  @Test
  public void sandboxFollowsPlatformByDefault() {
    boolean linux = PlaywrightService.isLinux();
    assertEquals("非 Linux(普通桌面)上的 Chrome 默认应当开启沙箱,Linux 默认关闭", !linux,
        PlaywrightService.chromiumSandbox(BrowserChoice.CHROME, true));
    assertEquals("内置 Chromium 与 Chrome 同一条路,默认值应当一致", !linux,
        PlaywrightService.chromiumSandbox(BrowserChoice.CHROMIUM, true));
    assertEquals("Edge 走 CDP 那条路,沙箱同样默认开着", !linux,
        PlaywrightService.chromiumSandbox(BrowserChoice.EDGE, false));
  }

  /**
   * Edge 一旦走回 Playwright 的管道启动,沙箱必须自动关掉
   *
   * <p>
   * 实测(Edge 145 / Windows):开沙箱 + 管道启动 = Edge 启动即退出。这条断言是给「以后有人把 Edge 改回
   * {@code launchPersistentContext}」准备的保险 —— 那样改的话浏览器会起不来,而这里会先把沙箱关掉。
   */
  @Test
  public void sandboxTurnsItselfOffForEdgeOverThePipe() {
    assertFalse("Edge + 管道启动不能开沙箱,否则浏览器起不来",
        PlaywrightService.chromiumSandbox(BrowserChoice.EDGE, true));
  }

  /** 两种取值都要能显式覆盖:容器里关掉、Linux 桌面上打开 */
  @Test
  public void sandboxCanBeConfiguredBothWays() {
    try {
      System.setProperty(PlaywrightService.KEY_SANDBOX, "false");
      assertFalse("browser.chromium.sandbox=false 时应当关掉沙箱",
          PlaywrightService.chromiumSandbox(BrowserChoice.CHROME, true));
      System.setProperty(PlaywrightService.KEY_SANDBOX, "true");
      assertTrue("browser.chromium.sandbox=true 时应当开启沙箱",
          PlaywrightService.chromiumSandbox(BrowserChoice.CHROME, true));
      assertTrue("Edge 走 CDP 时,显式开启同样生效",
          PlaywrightService.chromiumSandbox(BrowserChoice.EDGE, false));
      assertFalse("但 Edge 走管道时仍然要关掉(开了浏览器就起不来)",
          PlaywrightService.chromiumSandbox(BrowserChoice.EDGE, true));
      System.setProperty(PlaywrightService.KEY_SANDBOX, " true ");
      assertTrue("配置值两端有空格也要认", PlaywrightService.chromiumSandbox(BrowserChoice.CHROME, true));
    } finally {
      System.clearProperty(PlaywrightService.KEY_SANDBOX);
    }
  }

  /** CDP 那条路(自己拉浏览器)不经过 Playwright,关沙箱时得自己补 --no-sandbox,开沙箱时不能补 */
  @Test
  public void cdpArgsFollowTheSandboxDecision() {
    try {
      System.setProperty(PlaywrightService.KEY_SANDBOX, "false");
      assertTrue("关沙箱时 CDP 那条路要自己加 --no-sandbox",
          PlaywrightService.cdpArgs(true, BrowserChoice.CHROME).contains("--no-sandbox"));
      System.setProperty(PlaywrightService.KEY_SANDBOX, "true");
      assertFalse("开沙箱时不能出现 --no-sandbox",
          PlaywrightService.cdpArgs(true, BrowserChoice.CHROME).contains("--no-sandbox"));
      assertFalse("Edge 走 CDP,同样不该出现 --no-sandbox",
          PlaywrightService.cdpArgs(true, BrowserChoice.EDGE).contains("--no-sandbox"));
    } finally {
      System.clearProperty(PlaywrightService.KEY_SANDBOX);
    }
  }

  /**
   * 一个任务一个实例,但不是「一个任务一个 driver」:Playwright 全进程共用一个,浏览器与 profile
   * 也是全进程共用的(见 PlaywrightService.SharedBrowser)。所以 BrowserInstance 不该再持有
   * Playwright —— 一旦持有,close 时很容易顺手把它关掉,把其它任务的浏览器一起搞死。
   */
  @Test
  public void browserInstanceDoesNotOwnPlaywright() {
    for (Field field : BrowserInstance.class.getFields()) {
      assertFalse("BrowserInstance 不应该有 playwright 字段:driver 是全进程共享的,不该由实例持有",
          "playwright".equals(field.getName()));
    }
  }

  /**
   * start 应当直接从 launchPersistentContext 开始。
   *
   * <p>直接读源码断言 {@code Playwright.create()} 只出现一次:这个调用一旦被挪回 start 里,
   * 编译和运行都不会报错,只会让每个任务白白多花几百毫秒并多一个 node 进程,靠单元测试挡住。
   */
  @Test
  public void playwrightIsCreatedOnlyOncePerProcess() throws Exception {
    String text = stripComments(readServiceSource());
    int calls = text.split("Playwright\\.create\\(\\)", -1).length - 1;
    assertEquals("Playwright.create() 应该只在共享 driver 的懒加载里出现一次,实际 " + calls + " 次", 1, calls);
  }

  /** close 只该关任务自己的页签与共用的浏览器上下文,不能关共享的 driver */
  @Test
  public void closeDoesNotShutdownSharedDriver() throws Exception {
    String text = readServiceSource();
    assertTrue("共享 driver 的收尾应该交给 shutdown hook", text.contains("addShutdownHook"));
    // close(Long) 里不能出现任何对 Playwright 实例的 close()(注释里提到不算)
    int closeMethod = text.indexOf("public RespBodyVo close(Long browserId)");
    assertTrue("找不到 close(Long) 方法,测试需要跟着改", closeMethod > 0);
    String body = stripComments(text.substring(closeMethod, text.indexOf("\n  }", closeMethod)));
    assertFalse("close(Long) 里不能关 Playwright:那是全进程共享的 driver,关掉会连累其它任务",
        body.contains("Playwright"));
  }

  /**
   * 浏览器与 profile 全进程共用,任务之间靠页签隔离
   *
   * <p>所以取页签一律走 {@code pagesOf(inst)},不能直接用 {@code inst.context.pages()} —— 后者是
   * 所有任务的页签,会让 get_tabs / switch_tab 的索引串到别的任务上。
   */
  @Test
  public void tabListingGoesThroughTaskPages() throws Exception {
    String text = stripComments(readServiceSource());
    assertFalse("不能直接用 inst.context.pages():页签要按任务过滤,用 pagesOf(inst)",
        text.contains("inst.context.pages()"));
  }

  /**
   * 启动浏览器必须走「先本机 Chrome、再内嵌 Chromium」的顺序
   *
   * <p>用 Playwright 自带的 Chromium 是这次改动要去掉的默认行为,一旦被挪回去,只有跑起来才会发现。
   */
  @Test
  public void chromeIsPreferredOverBundledChromium() throws Exception {
    String text = stripComments(readServiceSource());
    assertTrue("启动时要先问本机安装的 Google Chrome", text.contains("ChromeBrowser.executablePath()"));
    assertTrue("没有 Chrome 时才退回内嵌 Chromium", text.contains("BundledBrowser.executablePath()"));
  }

  private static String readServiceSource() throws Exception {
    Path source = Paths.get("src", "main", "java", "nexus", "io", "ai", "browser", "service", "PlaywrightService.java");
    return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
  }

  /** 去掉注释,避免 javadoc 里提到的 API 名字被当成真实调用统计进去 */
  private static String stripComments(String text) {
    StringBuilder out = new StringBuilder();
    boolean inBlock = false;
    for (String line : text.split("\n")) {
      String trimmed = line.trim();
      if (inBlock) {
        if (trimmed.contains("*/")) {
          inBlock = false;
        }
        continue;
      }
      if (trimmed.startsWith("/*")) {
        inBlock = !trimmed.contains("*/");
        continue;
      }
      int lineComment = line.indexOf("//");
      out.append(lineComment >= 0 ? line.substring(0, lineComment) : line).append('\n');
    }
    return out.toString();
  }
}
