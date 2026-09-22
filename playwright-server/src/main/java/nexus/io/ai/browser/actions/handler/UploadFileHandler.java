package nexus.io.ai.browser.actions.handler;

import nexus.io.ai.browser.actions.model.UploadFileParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.service.PlaywrightService;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class UploadFileHandler implements CommandHandler<UploadFileParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, UploadFileParams params) {
    return svc.uploadFile(browserId, params.getIndex(), params.getPath());
  }
}
