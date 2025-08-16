package com.litongjava.playwright.demo;

import org.junit.Test;

import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.microsoft.playwright.Page;

public class PlayWrightAmazonSearch {

  @Test
  public void testSearch() {
    EnvUtils.load();

    PlaywrightService playwrightService = Aop.get(PlaywrightService.class);
    long id = playwrightService.start(null, false, false);
    Page page = playwrightService.currentPage(id);
    page.navigate("https://www.amazon.com");
    page.waitForLoadState();

    page.fill("input[name='field-keywords']", "Mac Mini M4");
    page.click("#nav-search-submit-button");

    page.waitForLoadState();

    String content = page.content();
    System.out.println(content);

  }
}
