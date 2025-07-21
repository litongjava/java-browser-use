package com.litongjava.ai.browser.actions.registry;

import java.util.HashMap;
import java.util.Map;

import com.litongjava.ai.browser.actions.handler.ClickElementByIndexHandler;
import com.litongjava.ai.browser.actions.handler.CloseHandler;
import com.litongjava.ai.browser.actions.handler.CloseTabHandler;
import com.litongjava.ai.browser.actions.handler.ExtractStructuredDataHandler;
import com.litongjava.ai.browser.actions.handler.GetDropdownOptionsHandler;
import com.litongjava.ai.browser.actions.handler.GoBackHandler;
import com.litongjava.ai.browser.actions.handler.GoToUrlHandler;
import com.litongjava.ai.browser.actions.handler.InputTextHandler;
import com.litongjava.ai.browser.actions.handler.NavigateHandler;
import com.litongjava.ai.browser.actions.handler.ScrollHandler;
import com.litongjava.ai.browser.actions.handler.ScrollToTextHandler;
import com.litongjava.ai.browser.actions.handler.SelectDropdownOptionHandler;
import com.litongjava.ai.browser.actions.handler.SendKeysHandler;
import com.litongjava.ai.browser.actions.handler.SwitchTabHandler;
import com.litongjava.ai.browser.actions.handler.UploadFileHandler;
import com.litongjava.ai.browser.actions.handler.WaitHandler;

public class HandlerRegistry {
  private final Map<String, CommandHandler<?>> handlers = new HashMap<>();

  public HandlerRegistry() {
    handlers.put("go_to_url", new GoToUrlHandler());
    handlers.put("navigate", new NavigateHandler());
    handlers.put("go_back", new GoBackHandler());
    handlers.put("wait", new WaitHandler());
    handlers.put("click_element_by_index", new ClickElementByIndexHandler());
    handlers.put("input_text", new InputTextHandler());
    handlers.put("upload_file", new UploadFileHandler());
    handlers.put("switch_tab", new SwitchTabHandler());
    handlers.put("close_tab", new CloseTabHandler());
    handlers.put("extract_structured_data", new ExtractStructuredDataHandler());
    handlers.put("scroll", new ScrollHandler());
    handlers.put("send_keys", new SendKeysHandler());
    handlers.put("scroll_to_text", new ScrollToTextHandler());
    handlers.put("get_dropdown_options", new GetDropdownOptionsHandler());
    handlers.put("select_dropdown_option", new SelectDropdownOptionHandler());
    handlers.put("close", new CloseHandler());
  }

  public CommandHandler<?> get(String cmd) {
    return handlers.get(cmd);
  }
}
