package nexus.io.ai.browser.config;

import nexus.io.ai.browser.handler.PlaywrightHandler;
import nexus.io.ai.browser.handler.PlaywrightHealthHandler;
import nexus.io.context.BootConfiguration;
import nexus.io.tio.boot.http.handler.common.HttpFileDataHandler;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.http.server.router.HttpRequestRouter;

public class PlaywrightAppConfig implements BootConfiguration {

  @Override
  public void config() {
    TioBootServer me = TioBootServer.me();
    HttpRequestRouter r = me.getRequestRouter();
    if (r != null) {
      // 截图与可交互结构化文本:data/<id>/<seq>.png|.txt
      r.add("/data/**", new HttpFileDataHandler(false));
      r.add("/playwright/health", new PlaywrightHealthHandler()::ping);
      // 唯一的浏览器控制端点:POST {id, method, params}
      r.add("/playwright/command", new PlaywrightHandler());
    }
  }
}
