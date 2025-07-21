// src/main/java/com/litongjava/ai/browser/cmd/WaitHandler.java
package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.WaitParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class WaitHandler implements CommandHandler<WaitParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, WaitParams params) {
    return svc.wait(browserId, params.getSeconds());
  }
}
