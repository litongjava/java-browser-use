// src/main/java/com/litongjava/ai/browser/cmd/GetDropdownOptionsHandler.java
package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.GetDropdownOptionsParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class GetDropdownOptionsHandler implements CommandHandler<GetDropdownOptionsParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, GetDropdownOptionsParams params) {
    return svc.getDropdownOptions(browserId, params.getIndex());
  }
}
