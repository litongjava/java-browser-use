package com.litongjava.ai.browser.actions.handler;

import com.litongjava.ai.browser.actions.model.UploadFileParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.model.body.RespBodyVo;

public class UploadFileHandler implements CommandHandler<UploadFileParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, UploadFileParams params) {
    return svc.uploadFile(browserId, params.getIndex(), params.getPath());
  }
}
