package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.SwitchTabParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class SwitchTabHandler implements CommandHandler<SwitchTabParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, SwitchTabParams params) {
    return svc.switchTab(browserId, params.getPageIndex());
  }
}
