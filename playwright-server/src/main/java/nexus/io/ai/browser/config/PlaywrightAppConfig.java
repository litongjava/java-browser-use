package nexus.io.ai.browser.config;

import java.util.ArrayList;
import java.util.List;

import nexus.io.ai.browser.handler.PlaywrightHealthHandler;
import nexus.io.context.BootConfiguration;
import nexus.io.tio.boot.http.handler.common.HttpFileDataHandler;
import nexus.io.tio.boot.http.handler.controller.TioBootHttpControllerRouter;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.http.server.router.HttpRequestRouter;

public class PlaywrightAppConfig implements BootConfiguration {

  @Override
  public void config() {
    TioBootServer me = TioBootServer.me();
    HttpRequestRouter r = me.getRequestRouter();
    if (r != null) {
      r.add("/data/**",  new HttpFileDataHandler(false));
      r.add("/playwright/health", new PlaywrightHealthHandler()::ping);
    }

    TioBootHttpControllerRouter controllerRouter = me.getControllerRouter();
    if (controllerRouter != null) {
      List<Class<?>> scannedClasses = new ArrayList<>();
      scannedClasses.add(nexus.io.ai.browser.controller.PlaywrightController.class);
      controllerRouter.addControllers(scannedClasses);
    }
  }
}
