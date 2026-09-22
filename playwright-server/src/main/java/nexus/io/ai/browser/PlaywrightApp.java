package nexus.io.ai.browser;

import nexus.io.ai.browser.config.PlaywrightAppConfig;

import nexus.io.tio.boot.TioApplication;

public class PlaywrightApp {
  
  public static void main(String[] args) {
    TioApplication.run(PlaywrightApp.class,new PlaywrightAppConfig(), args);
  }
}
