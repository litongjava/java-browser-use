package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.ClickElementByIndexParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class ClickElementByIndexHandler implements CommandHandler<ClickElementByIndexParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, ClickElementByIndexParams params) {
    return svc.clickElementByIndex(browserId, params.getIndex());
  }
}
