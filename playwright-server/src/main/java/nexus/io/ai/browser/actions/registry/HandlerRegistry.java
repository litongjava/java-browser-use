package nexus.io.ai.browser.actions.registry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import nexus.io.ai.browser.actions.handler.ClickElementByIndexHandler;
import nexus.io.ai.browser.actions.handler.CloseHandler;
import nexus.io.ai.browser.actions.handler.CloseTabHandler;
import nexus.io.ai.browser.actions.handler.ExtractStructuredDataHandler;
import nexus.io.ai.browser.actions.handler.ExecuteJsHandler;
import nexus.io.ai.browser.actions.handler.GetDropdownOptionsHandler;
import nexus.io.ai.browser.actions.handler.GoBackHandler;
import nexus.io.ai.browser.actions.handler.GoToUrlHandler;
import nexus.io.ai.browser.actions.handler.InputTextHandler;
import nexus.io.ai.browser.actions.handler.NavigateHandler;
import nexus.io.ai.browser.actions.handler.ScrollHandler;
import nexus.io.ai.browser.actions.handler.ScrollToTextHandler;
import nexus.io.ai.browser.actions.handler.SelectDropdownOptionHandler;
import nexus.io.ai.browser.actions.handler.SendKeysHandler;
import nexus.io.ai.browser.actions.handler.SwitchTabHandler;
import nexus.io.ai.browser.actions.handler.UploadFileHandler;
import nexus.io.ai.browser.actions.handler.WaitHandler;

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
    handlers.put("execute_js", new ExecuteJsHandler());
    handlers.put("close", new CloseHandler());
  }

  public CommandHandler<?> get(String cmd) {
    return handlers.get(cmd);
  }

  /** 老式命令名集合,批量接口的覆盖度检查会用到 */
  public Set<String> names() {
    return Collections.unmodifiableSet(handlers.keySet());
  }
}
