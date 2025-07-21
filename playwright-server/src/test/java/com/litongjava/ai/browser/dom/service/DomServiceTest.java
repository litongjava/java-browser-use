package com.litongjava.ai.browser.dom.service;

import java.util.Map;

import org.junit.Test;

import com.litongjava.ai.browser.dom.model.DOMState;
import com.litongjava.tio.utils.hutool.FileUtil;
import com.litongjava.tio.utils.json.JsonUtils;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

public class DomServiceTest {

  @Test
  public void getExpression() {
    String expression = DomService.buildExpression();
    System.out.println(expression);
  }

  @Test
  public void test() {
    String url = "https://www.baidu.com";

    try (Playwright pw = Playwright.create()) {
      Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
      BrowserContext ctx = browser.newContext();
      Page page = ctx.newPage();
      page.navigate(url);

      System.out.println("[Start of page]");
      String out = DomService.getSimpleText(page);
      System.out.println(out);
      System.out.println("[End of page]");
    }
  }

  @Test
  public void testTaoBao() {
    String url = "https://www.taobao.com";

    try (Playwright pw = Playwright.create()) {
      Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
      BrowserContext ctx = browser.newContext();
      Page page = ctx.newPage();
      page.navigate(url);

      DOMState state = DomService.getClickableElements(page, true, -1, 0);

      if (state.getPixelsAbove() > 0) {
        System.out.println(state.getPixelsAbove() + " pixels above - scroll or extract content to see more");
      }

      String out = state.getElementTree().clickableElementsToString(null);
      System.out.println(out);
      if (state.getPixelsBelow() > 0) {
        System.out.println(state.getPixelsBelow() + " pixels below - scroll or extract content to see more");
      }

    }
  }

  @Test
  public void evaluateBaidu() {
    String url = "https://www.baidu.com";

    try (Playwright pw = Playwright.create()) {
      Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
      BrowserContext ctx = browser.newContext();
      Page page = ctx.newPage();
      page.navigate(url);

      Map<String, Object> evaluate = DomService.evaluate(page, true, -1, 0);

      String json = JsonUtils.toJson(evaluate);
      FileUtil.writeString(json, "taobao.json");
    }
  }

  @Test
  public void evaluateTaobao() {
    String url = "https://www.taobao.com/";

    try (Playwright pw = Playwright.create()) {
      Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
      BrowserContext ctx = browser.newContext();
      Page page = ctx.newPage();
      page.navigate(url);

      Map<String, Object> evaluate = DomService.evaluate(page, true, -1, 0);

      String json = JsonUtils.toJson(evaluate);
      FileUtil.writeString(json, "taobao.json");
    }
  }

}
