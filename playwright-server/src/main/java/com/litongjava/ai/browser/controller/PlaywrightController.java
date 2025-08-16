package com.litongjava.ai.browser.controller;

import com.jfinal.kit.Kv;
import com.litongjava.ai.browser.service.ActionService;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.annotation.RequestPath;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;
import com.microsoft.playwright.Response;

@RequestPath("/api/v1/playwright")
public class PlaywrightController {

  private final PlaywrightService svc = Aop.get(PlaywrightService.class);
  private final ActionService actionService = Aop.get(ActionService.class);

  @RequestPath("/start")
  public RespBodyVo start(Long id, boolean headless) {
    long newId = svc.start(id, headless, false);
    return RespBodyVo.ok(Kv.by("id", newId));
  }

  @RequestPath("/navigate")
  public RespBodyVo navigate(Long id, String url) {
    Response rsp = svc.navigate(id, url);
    return RespBodyVo.ok(Kv.by("status", rsp.status()));
  }

  @RequestPath("/go_to_url")
  public RespBodyVo goToUrl(Long id, String url) {
    return svc.goToUrl(id, url);
  }

  @RequestPath("/go_back")
  public RespBodyVo goBack(Long id) {
    return svc.goBack(id);
  }

  @RequestPath("/wait")
  public RespBodyVo wait(Long id, Integer seconds) {
    return svc.wait(id, seconds);
  }

  @RequestPath("/click_element_by_index")
  public RespBodyVo click(Long id, Integer index) {
    return svc.clickElementByIndex(id, index);
  }

  @RequestPath("/input_text")
  public RespBodyVo inputText(Long id, Integer index, String text) {
    return svc.inputTextByIndex(id, index, text);
  }

  @RequestPath("/upload_file")
  public RespBodyVo uploadFile(Long id, Integer index, String path) {
    return svc.uploadFile(id, index, path);
  }

  @RequestPath("/switch_tab")
  public RespBodyVo switchTab(Long id, Integer pageIndex) {
    return svc.switchTab(id, pageIndex);
  }

  @RequestPath("/close_tab")
  public RespBodyVo closeTab(Long id, Integer pageIndex) {
    return svc.closeTab(id, pageIndex);
  }

  @RequestPath("/extract_structured_data")
  public RespBodyVo extractStructuredData(Long id, String query, boolean extractLinks) {
    return svc.extractStructuredData(id, query, extractLinks);
  }

  @RequestPath("/scroll")
  public RespBodyVo scroll(Long id, boolean down, Integer numPages, Integer index) {
    return svc.scroll(id, down, numPages, index);
  }

  @RequestPath("/send_keys")
  public RespBodyVo sendKeys(Long id, String keys) {
    return svc.sendKeys(id, keys);
  }

  @RequestPath("/scroll_to_text")
  public RespBodyVo scrollToText(Long id, String text) {
    return svc.scrollToText(id, text);
  }

  @RequestPath("/get_dropdown_options")
  public RespBodyVo getDropdownOptions(Long id, Integer index) {
    return svc.getDropdownOptions(id, index);
  }

  @RequestPath("/select_dropdown_option")
  public RespBodyVo selectDropdownOption(Long id, Integer index, String text) {
    return svc.selectDropdownOption(id, index, text);
  }

  @RequestPath("/close")
  public RespBodyVo close(Long id) {
    return svc.close(id);
  }

  /**
   * POST /api/v1/playwright/commands body: [ { "go_to_url": {
   * "url":"https://www.taobao.com" } }, { "click_element_by_index": { "index": 2
   * } }, … ]
   */
  @RequestPath("/commands")
  public RespBodyVo batchExecute(Long id, String bodyJson) {
    return actionService.batchExecute(id, bodyJson);
  }
}
