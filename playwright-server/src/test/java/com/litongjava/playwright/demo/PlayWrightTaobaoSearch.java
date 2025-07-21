package com.litongjava.playwright.demo;

import org.junit.Test;

import com.litongjava.ai.browser.dom.service.DomService;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

public class PlayWrightTaobaoSearch {

  @Test
  public void testSearch() {
    EnvUtils.load();
    String username = EnvUtils.getStr("taobao.username");
    String password = EnvUtils.getStr("taobao.password");

    PlaywrightService playwrightService = Aop.get(PlaywrightService.class);
    long id = playwrightService.start(null, false);
    Page page = playwrightService.currentPage(id);
    page.navigate("https://www.taobao.com");
    page.waitForLoadState();

    Page currentPage = playwrightService.currentPage(id);
    ElementHandle elementHandle = currentPage.querySelector("text=亲，请登录");
    if (elementHandle != null) {
      // 点击顶部的“亲，请登录”
      playwrightService.clickElementBySelector(id, "text=亲，请登录");

      // 点完之后如果有跳转或弹窗，等它加载
      page.waitForLoadState();

      // 输入账号
      playwrightService.inputTextBySelector(id, "input[aria-label=\"账号名/邮箱/手机号\"]", username);

      // 输入密码
      playwrightService.inputTextBySelector(id, "input[aria-label=\"请输入登录密码\"]", password);

      // 提交登录
      playwrightService.clickElementBySelector(id, "button[type=submit]");
      // 等待跳转或者检查登录是否成功
      page.waitForLoadState();
    }

    playwrightService.inputTextBySelector(id, "input[aria-label=\"请输入搜索文字\"]", "Mac Mini M4");

    playwrightService.clickElementBySelector(id, "button[type=submit]");

    // 等待搜索结果加载完成
    page = playwrightService.currentPage(id);

    String out = DomService.getSimpleText(page);
    System.out.println("[Start of page]");
    System.out.println(out);
    System.out.println("[End of page]");

  }
}
