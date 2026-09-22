package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.InputTextParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class InputTextHandler implements CommandHandler<InputTextParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);
  
  @Override
  public RespBodyVo handle(Long browserId, InputTextParams params) {
    return svc.inputTextByIndex(browserId, params.getIndex(), params.getText());
  }
}
