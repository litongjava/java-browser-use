// src/main/java/com/litongjava/ai/browser/cmd/WaitHandler.java
package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.WaitParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class WaitHandler implements CommandHandler<WaitParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, WaitParams params) {
    return svc.wait(browserId, params.getSeconds());
  }
}
