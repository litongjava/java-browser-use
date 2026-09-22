package nexus.io.ai.browser.demo;

import org.junit.Test;

import nexus.io.ai.browser.service.PlaywrightService;
import com.microsoft.playwright.Page;

import nexus.io.jfinal.aop.Aop;
import nexus.io.tio.utils.environment.EnvUtils;

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
