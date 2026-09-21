package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.CloseParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class CloseHandler implements CommandHandler<CloseParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, CloseParams params) {
    return svc.close(browserId);
  }
}
