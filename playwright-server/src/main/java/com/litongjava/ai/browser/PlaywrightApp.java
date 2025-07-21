package com.litongjava.ai.browser;

import com.litongjava.ai.browser.config.PlaywrightAppConfig;
import com.litongjava.tio.boot.TioApplication;

public class PlaywrightApp {
  
  public static void main(String[] args) {
    TioApplication.run(PlaywrightApp.class,new PlaywrightAppConfig(), args);
  }
}
