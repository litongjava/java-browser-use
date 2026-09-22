package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.ExtractStructuredDataParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class ExtractStructuredDataHandler implements CommandHandler<ExtractStructuredDataParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, ExtractStructuredDataParams params) {
    return svc.extractStructuredData(browserId, params.getQuery(), params.getExtractLinks());
  }
}
