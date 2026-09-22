package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.SelectDropdownOptionParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class SelectDropdownOptionHandler implements CommandHandler<SelectDropdownOptionParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, SelectDropdownOptionParams params) {
    return svc.selectDropdownOption(browserId, params.getIndex(), params.getText());
  }
}
