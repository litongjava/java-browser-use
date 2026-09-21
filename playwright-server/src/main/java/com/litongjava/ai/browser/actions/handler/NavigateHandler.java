package com.litongjava.ai.browser.actions.handler;

import com.jfinal.kit.Kv;
import com.litongjava.ai.browser.actions.model.NavigateParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.service.PlaywrightService;
import com.microsoft.playwright.Response;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

public class NavigateHandler implements CommandHandler<NavigateParams> {
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  @Override
  public RespBodyVo handle(Long browserId, NavigateParams params) {
    Response rsp = svc.navigate(browserId, params.getUrl());
    return RespBodyVo.ok(Kv.by("status", rsp.status()));
  }
}
