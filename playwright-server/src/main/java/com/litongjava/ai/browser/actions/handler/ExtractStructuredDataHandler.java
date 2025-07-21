package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.ExtractStructuredDataParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class ExtractStructuredDataHandler implements CommandHandler<ExtractStructuredDataParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, ExtractStructuredDataParams params) {
    return svc.extractStructuredData(browserId, params.getQuery(), params.getExtractLinks());
  }
}
