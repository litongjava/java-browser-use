package com.litongjava.ai.browser.config;

import java.util.ArrayList;
import java.util.List;

import com.litongjava.context.BootConfiguration;
import com.litongjava.tio.boot.http.handler.controller.TioBootHttpControllerRouter;
import com.litongjava.tio.boot.server.TioBootServer;

public class PlaywrightAppConfig implements BootConfiguration {

  @Override
  public void config() {
    TioBootHttpControllerRouter controllerRouter = TioBootServer.me().getControllerRouter();
    if (controllerRouter != null) {
      List<Class<?>> scannedClasses = new ArrayList<>();
      scannedClasses.add(com.litongjava.ai.browser.controller.PlaywrightController.class);
      controllerRouter.addControllers(scannedClasses);
    }
  }
}
