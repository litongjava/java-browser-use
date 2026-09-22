package nexus.io.ai.browser.actions.registry;

import nexus.io.model.body.RespBodyVo;

public interface CommandHandler<P> {
  RespBodyVo handle(Long browserId, P params);
}
