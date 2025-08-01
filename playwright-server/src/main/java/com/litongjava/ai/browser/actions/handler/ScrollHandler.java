package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.ScrollParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class ScrollHandler implements CommandHandler<ScrollParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, ScrollParams params) {
    return svc.scroll(browserId, params.getDown(), params.getNumPages(), params.getIndex());
  }
}
