// src/main/java/com/litongjava/ai/browser/cmd/GetDropdownOptionsHandler.java
package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.GetDropdownOptionsParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class GetDropdownOptionsHandler implements CommandHandler<GetDropdownOptionsParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, GetDropdownOptionsParams params) {
    return svc.getDropdownOptions(browserId, params.getIndex());
  }
}
