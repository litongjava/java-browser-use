package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.GoToUrlParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class GoToUrlHandler implements CommandHandler<GoToUrlParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, GoToUrlParams params) {
    return svc.goToUrl(browserId, params.getUrl());
  }
}
