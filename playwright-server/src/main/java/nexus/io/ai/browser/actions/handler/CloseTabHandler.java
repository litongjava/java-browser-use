package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.CloseTabParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class CloseTabHandler implements CommandHandler<CloseTabParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, CloseTabParams params) {
    return svc.closeTab(browserId, params.getPageIndex());
  }
}
