package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.InputTextParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class InputTextHandler implements CommandHandler<InputTextParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, InputTextParams params) {
    return svc.inputTextByIndex(browserId, params.getIndex(), params.getText());
  }
}
