package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.ScrollToTextParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class ScrollToTextHandler implements CommandHandler<ScrollToTextParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, ScrollToTextParams params) {
    return svc.scrollToText(browserId, params.getText());
  }
}
