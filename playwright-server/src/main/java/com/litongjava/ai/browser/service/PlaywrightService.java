package com.litongjava.ai.browser.service;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.jfinal.kit.Kv;
import com.litongjava.ai.browser.consts.BrowserUserAgent;
import com.litongjava.model.body.RespBodyVo;
import com.litongjava.tio.utils.collect.Lists;
import com.litongjava.tio.utils.snowflake.SnowflakeIdUtils;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RecordVideoSize;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlaywrightService {

  private static final ConcurrentHashMap<Long, BrowserInstance> INSTANCES = new ConcurrentHashMap<>();

  public long start(Long id, boolean headless) {
    Playwright pw = Playwright.create();
    Path profileDir = Paths.get(System.getProperty("user.home"), ".config", "browseruse", "profiles", "default");
    Path downloadDir = Paths.get(System.getProperty("user.home"), "Downloads", "broswer");
    Path videosDir = Paths.get(System.getProperty("user.home"), "Videos", "broswer");
    log.info("user dir:{}", profileDir);
    LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions().setHeadless(headless);

    opts.setIgnoreDefaultArgs(Lists.of("--enable-automation"));

    Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    int screenWidth = (screenSize.width / 32) * 28;
    int screenHeight = screenSize.height;
    log.info("size {} x {}", screenWidth, screenHeight);

    // 覆写/追加启动参数
    opts.setArgs(Arrays.asList(
        //
        "--no-sandbox", "--disable-dev-shm-usage",
        //
        "--disable-web-security", "--disable-infobars",
        //
        "--disable-blink-features=AutomationControlled"));

    // 下载与录制
    opts.setAcceptDownloads(true);

    opts.setDownloadsPath(downloadDir);
    // opts.setRecordHarPath(Paths.get("trace.har"));
    // opts.setRecordHarMode(HarMode.FULL);
    // opts.setRecordHarContent(HarContentPolicy.EMBED);

    opts.setRecordVideoDir(videosDir);
    opts.setRecordVideoSize(new RecordVideoSize(screenWidth, screenHeight));

    // 视窗 & 设备仿真
    opts.setViewportSize(screenWidth, screenHeight);
    opts.setDeviceScaleFactor(1.0);
    opts.setIsMobile(false);
    opts.setHasTouch(false);

    // 权限 & HTTP 头
    opts.setPermissions(Arrays.asList("clipboard-read", "clipboard-write", "notifications"));

    opts.setUserAgent(BrowserUserAgent.CHROME_127_WIN_10);
    // opts.setGeolocation(new Geolocation(21.3, -157.8));
    opts.setServiceWorkers(ServiceWorkerPolicy.ALLOW);

    BrowserType chromium = pw.chromium();
    BrowserContext ctx = chromium.launchPersistentContext(profileDir, opts);
    Page firstPage = ctx.pages().get(0);
    if (id == null) {
      id = SnowflakeIdUtils.id();
    }

    Page page = ctx.pages().get(0);
    // 屏蔽 navigator.webdriver
    page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => false});");

    INSTANCES.put(id, new BrowserInstance(pw, ctx, firstPage, profileDir, opts));
    return id;
  }

  public BrowserInstance getInstance(long id) {
    return INSTANCES.get(id);

  }

  /** 保留原 navigate */
  public Response navigate(Long browserId, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    return inst.page.navigate(url);
  }

  public Page currentPage(Long id) {
    BrowserInstance inst = INSTANCES.get(id);
    return inst.page;
  }

  /** 新增 go_to_url （功能同 navigate，但单独接口） */
  public RespBodyVo goToUrl(Long browserId, String url) {
    BrowserInstance inst = INSTANCES.get(browserId);
    Response rsp = inst.page.navigate(url);
    return RespBodyVo.ok(Kv.by("status", rsp.status()));
  }

  public RespBodyVo goBack(Long browserId) {
    BrowserInstance inst = INSTANCES.get(browserId);
    Response rsp = inst.page.goBack();
    if (rsp == null)
      return RespBodyVo.fail("无法后退：没有可用历史记录");
    return RespBodyVo.ok(Kv.by("status", rsp.status()));
  }

  public RespBodyVo wait(Long browserId, Integer seconds) {
    try {
      Thread.sleep(seconds * 1_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return RespBodyVo.fail("等待被中断");
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo clickElementByIndex(Long browserId, int index) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<ElementHandle> elements = inst.page.querySelectorAll("a, button, input[type=button], input[type=submit]");
    if (index < 0 || index >= elements.size()) {
      return RespBodyVo.fail("click_element_by_index 索引越界: " + index);
    }
    elements.get(index).click();
    return RespBodyVo.ok();
  }

  public RespBodyVo clickElementBySelector(long id, String selector) {
    BrowserInstance inst = INSTANCES.get(id);
    try {
      // 告诉 Playwright：在 click 期间，我预期会有 popup
      Page newPage = inst.page.waitForPopup(() -> inst.page.click(selector));
      // 切换 context 中的“当前” page
      inst.page = newPage;

      newPage.waitForLoadState();
    } catch (PlaywrightException e) {
      log.error(e.getMessage());
    }

    return RespBodyVo.ok();
  }

  public RespBodyVo inputTextByIndex(Long browserId, int index, String value) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<ElementHandle> inputs = inst.page.querySelectorAll("input, textarea");
    if (index < 0 || index >= inputs.size()) {
      return RespBodyVo.fail("input_text 索引越界: " + index);
    }
    inputs.get(index).fill(value);
    return RespBodyVo.ok();
  }

  public RespBodyVo inputTextBySelector(long id, String selector, String value) {
    BrowserInstance inst = INSTANCES.get(id);
    inst.page.fill(selector, value);
    return RespBodyVo.ok();
  }

  public RespBodyVo uploadFile(Long browserId, int index, String path) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<ElementHandle> fileInputs = inst.page.querySelectorAll("input[type=file]");
    if (index < 0 || index >= fileInputs.size()) {
      return RespBodyVo.fail("upload_file 索引越界: " + index);
    }
    fileInputs.get(index).setInputFiles(Paths.get(path));
    return RespBodyVo.ok();
  }

  public RespBodyVo switchTab(Long browserId, int pageIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<Page> pages = inst.context.pages();
    if (pageIndex < 0 || pageIndex >= pages.size()) {
      return RespBodyVo.fail("switch_tab 页签索引越界: " + pageIndex);
    }
    inst.page = pages.get(pageIndex);
    return RespBodyVo.ok();
  }

  public RespBodyVo closeTab(Long browserId, int pageIndex) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<Page> pages = inst.context.pages();
    if (pageIndex < 0 || pageIndex >= pages.size()) {
      return RespBodyVo.fail("close_tab 页签索引越界: " + pageIndex);
    }
    Page toClose = pages.get(pageIndex);
    toClose.close();
    // 如果关闭了当前页，则切换到第一个可用页
    if (inst.page == toClose && pages.size() > 1) {
      inst.page = pages.get(0);
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo extractStructuredData(Long browserId, String query, boolean extractLinks) {

    return RespBodyVo.ok();
  }

  public RespBodyVo scroll(Long browserId, boolean down, int numPages, Integer index) {
    BrowserInstance inst = INSTANCES.get(browserId);
    for (int i = 0; i < numPages; i++) {
      if (index == null) {
        inst.page.keyboard().press(down ? "PageDown" : "PageUp");
      } else {
        List<ElementHandle> all = inst.page.querySelectorAll("*");
        if (index < 0 || index >= all.size()) {
          return RespBodyVo.fail("scroll 索引越界: " + index);
        }
        ElementHandle elm = all.get(index);
        elm.evaluate("el, down => el.scrollBy(0, down ? window.innerHeight : -window.innerHeight)", down);
      }
    }
    return RespBodyVo.ok();
  }

  public RespBodyVo sendKeys(Long browserId, String keys) {
    BrowserInstance inst = INSTANCES.get(browserId);
    inst.page.keyboard().press(keys);
    return RespBodyVo.ok();
  }

  public RespBodyVo scrollToText(Long browserId, String text) {
    BrowserInstance inst = INSTANCES.get(browserId);
    inst.page.locator("text=" + text).first().scrollIntoViewIfNeeded();
    return RespBodyVo.ok();
  }

  public RespBodyVo getDropdownOptions(Long browserId, int index) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<ElementHandle> selects = inst.page.querySelectorAll("select");
    if (index < 0 || index >= selects.size()) {
      return RespBodyVo.fail("get_dropdown_options 索引越界: " + index);
    }
    ElementHandle elementHandle = selects.get(index);
    Object options = elementHandle.evaluate("el => Array.from(el.options).map(o => o.textContent)");
    return RespBodyVo.ok(Kv.by("options", options));
  }

  public RespBodyVo selectDropdownOption(Long browserId, int index, String text) {
    BrowserInstance inst = INSTANCES.get(browserId);
    List<ElementHandle> selects = inst.page.querySelectorAll("select");
    if (index < 0 || index >= selects.size()) {
      return RespBodyVo.fail("select_dropdown_option 索引越界: " + index);
    }
    selects.get(index).selectOption(new SelectOption().setLabel(text));
    return RespBodyVo.ok();
  }

  public RespBodyVo close(Long browserId) {
    BrowserInstance inst = INSTANCES.remove(browserId);
    if (inst != null && inst.context != null) {
      inst.context.close();
      inst.playwright.close();
      return RespBodyVo.ok();
    } else {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + browserId);
    }
  }

  public BrowserInstance restartContext(BrowserInstance instance) {
    try {
      instance.page.close();
      instance.context.close();
    } catch (Exception e) {
      log.error(e.getMessage());
    }
    try {
      BrowserContext context = instance.playwright.chromium().launchPersistentContext(instance.profileDir,
          instance.opts);
      log.info("new context:{}", context);
      Page firstPage = context.pages().get(0);
      instance.context = context;
      instance.page = firstPage;
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    return instance;

  }

}