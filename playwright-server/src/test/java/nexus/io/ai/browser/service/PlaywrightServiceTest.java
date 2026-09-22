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
   * Playwright 的 chromiumSandbox 默认是 false,它自己会加 --no-sandbox。非 Linux 上必须显式开启
   * 沙箱,否则上面那条警告又会回来。
   */
  @Test
  public void sandboxIsOnOutsideLinux() {
    if (!PlaywrightService.isLinux()) {
      assertTrue("非 Linux 平台必须开启 Chromium 沙箱,否则 Playwright 会自己加 --no-sandbox",
          PlaywrightService.chromiumSandbox());
    }
  }

  /**
   * 一个任务一个实例,但不是「一个任务一个 driver」:Playwright 全进程共用一个,隔离靠
   * BrowserContext。所以 BrowserInstance 不该再持有 Playwright —— 一旦持有,close 时很容易
   * 顺手把它关掉,把其它任务的浏览器一起搞死。
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

  /** close 只该关任务自己的上下文,不能关共享的 driver */
  @Test
  public void closeDoesNotShutdownSharedDriver() throws Exception {
    String text = readServiceSource();
    assertTrue("共享 driver 的收尾应该交给 shutdown hook", text.contains("addShutdownHook"));
    // close(Long) 里不能出现任何对 Playwright 实例的 close()
    int closeMethod = text.indexOf("public RespBodyVo close(Long browserId)");
    assertTrue("找不到 close(Long) 方法,测试需要跟着改", closeMethod > 0);
    String body = stripComments(text.substring(closeMethod, text.indexOf("\n  }", closeMethod)));
    assertFalse("close(Long) 里不能关 Playwright:那是全进程共享的 driver,关掉会连累其它任务",
        body.contains("Playwright"));
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
