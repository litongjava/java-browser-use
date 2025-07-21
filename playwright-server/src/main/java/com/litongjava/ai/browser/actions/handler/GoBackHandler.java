package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.GoBackParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class GoBackHandler implements CommandHandler<GoBackParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, GoBackParams params) {
    return svc.goBack(browserId);
  }
}
