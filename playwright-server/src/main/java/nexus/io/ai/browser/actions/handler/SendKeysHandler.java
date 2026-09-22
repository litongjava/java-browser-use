package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.SendKeysParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class SendKeysHandler implements CommandHandler<SendKeysParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, SendKeysParams params) {
    return svc.sendKeys(browserId, params.getKeys());
  }
}
