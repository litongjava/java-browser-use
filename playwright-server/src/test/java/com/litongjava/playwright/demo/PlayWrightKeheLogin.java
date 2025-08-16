package com.litongjava.playwright.demo;

import org.junit.Test;

import com.litongjava.ai.browser.dom.service.DomService;
import com.litongjava.ai.browser.service.BrowserInstance;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

import lombok.extern.slf4j.Slf4j;

@Slf4j

public class PlayWrightKeheLogin {

  @Test
  @SuppressWarnings("deprecation")
  public void testSearch() {
    EnvUtils.load();
    String username = EnvUtils.getStr("kehe.username");
    String password = EnvUtils.getStr("kehe.password");

    PlaywrightService playwrightService = Aop.get(PlaywrightService.class);
    long id = playwrightService.start(null, false, false);
    BrowserInstance instance = playwrightService.getInstance(id);
    Page page = instance.page;
    page.navigate("https://connect-identity-server.kehe.com/Account/Login");
    page.waitForLoadState();

    Page currentPage = playwrightService.currentPage(id);
    ElementHandle elementHandle = currentPage.querySelector("h1:has-text(\"Log into KeHE CONNECT\")");
    if (elementHandle != null) {
      // 输入账号
      currentPage.fill("input[name='username']", username);
      // 点击按钮
      // 告诉 Playwright：在 click 期间，我预期会有 popup
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      final Locator btn = page.locator("button.login-button");
      page.waitForNavigation(btn::click);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      // 切换 context 中的“当前” page
      currentPage.fill("input[name='password']", password);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      final Locator btn2 = page.locator("button.login-button");
      page.waitForNavigation(btn2::click);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      // 等待跳转或者检查登录是否成功
      page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    try (Page newPage = instance.context.newPage()) {
      String url = "https://connectretailer.kehe.com/every-day/products/027271103063";
      newPage.navigate(url);
      log.info("goto:{}", url);
      newPage.waitForLoadState(LoadState.NETWORKIDLE);
      String out = DomService.getSimpleText(newPage);
      String content = page.content();
      System.out.println(content);
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    try (Page newPage = instance.context.newPage()) {
      String url = "https://connectretailer.kehe.com/every-day/products/027271103063";
      newPage.navigate(url);
      log.info("goto:{}", url);
      newPage.waitForLoadState(LoadState.NETWORKIDLE);
      String content = page.content();
      System.out.println(content);
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    String out = DomService.getSimpleText(page);
    System.out.println("[Start of page]");
    System.out.println(out);
    System.out.println("[End of page]");
  }
}
