package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;

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
}
