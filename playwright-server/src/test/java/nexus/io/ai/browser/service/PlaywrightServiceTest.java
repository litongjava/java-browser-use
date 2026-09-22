package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    assertTrue("应当保留反自动化检测的启动参数", args.contains("--disable-blink-features=AutomationControlled"));
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
}
