package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.SendKeysParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class SendKeysHandler implements CommandHandler<SendKeysParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, SendKeysParams params) {
    return svc.sendKeys(browserId, params.getKeys());
  }
}
