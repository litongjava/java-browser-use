package com.litongjava.ai.browser.actions.registry;

import com.litongjava.model.body.RespBodyVo;

public interface CommandHandler<P> {
  RespBodyVo handle(Long browserId, P params);
}
