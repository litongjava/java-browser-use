package nexus.io.ai.browser.actions.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;

import nexus.io.ai.browser.handler.PlaywrightHandler;

/**
 * 命令表的一致性检查
 *
 * <p>现在只有一个端点 {@code POST /playwright/command},方法名就是命令表的键,所以「表里写了但
 * 没有执行体」「自动截图集合里写了不存在的命令」这两类错都会直接变成线上 500 或莫名其妙的
 * 「不支持的方法」。这里把它们挡在构建阶段。
 */
public class CommandTableTest {

  /** 缺必填参数时应当在碰任何浏览器对象之前就报错,所以可以用 null 的 service 调用 */
  private static final Map<String, String> MISSING_PARAM_CASES = missingParamCases();

  private static Map<String, String> missingParamCases() {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("go_to_url", "url");
    cases.put("navigate", "url");
    cases.put("wait", "seconds");
    cases.put("click_element_by_index", "index");
    cases.put("double_click_element_by_index", "index");
    cases.put("hover_element_by_index", "index");
    cases.put("focus_element_by_index", "index");
    cases.put("check_element_by_index", "index");
    cases.put("uncheck_element_by_index", "index");
    cases.put("input_text", "index");
    cases.put("type_text", "index");
    cases.put("upload_file", "index");
    cases.put("drag_element_by_index", "index");
    cases.put("send_keys", "keys");
    cases.put("key_down", "keys");
    cases.put("key_up", "keys");
    cases.put("get_dropdown_options", "index");
    cases.put("select_dropdown_option", "index");
    cases.put("scroll", "numPages");
    cases.put("scroll_to_text", "text");
    cases.put("get_element_text", "index");
    cases.put("get_element_html", "index");
    cases.put("get_element_value", "index");
    cases.put("get_element_attribute", "index");
    cases.put("get_element_count", "selector");
    cases.put("get_element_box", "index");
    cases.put("is_visible", "index");
    cases.put("is_enabled", "index");
    cases.put("is_checked", "index");
    cases.put("click_element_by_selector", "selector");
    cases.put("input_text_by_selector", "selector");
    cases.put("click_element_by_text", "text");
    cases.put("click_element_by_role", "role");
    cases.put("input_text_by_label", "label");
    cases.put("switch_tab", "pageIndex");
    cases.put("switch_tab_by_url", "url");
    cases.put("close_tab", "pageIndex");
    cases.put("wait_for_element", "selector");
    cases.put("wait_for_text", "text");
    cases.put("wait_for_url", "url");
    cases.put("wait_for_function", "expression");
    cases.put("mouse_move", "x");
    cases.put("mouse_wheel", "deltaY");
    cases.put("set_viewport", "width");
    cases.put("set_geolocation", "latitude");
    cases.put("set_headers", "headersJson");
    cases.put("set_credentials", "username");
    cases.put("set_media", "colorScheme");
    cases.put("route", "urlPattern");
    cases.put("wait_for_response", "urlPattern");
    cases.put("request_human_input", "prompt");
    cases.put("submit_human_input", "requestId");
    cases.put("get_human_input", "requestId");
    cases.put("execute_js", "body");
    return cases;
  }

  @Test
  public void everyCommandHasAnExecutor() {
    for (String command : CommandTable.names()) {
      assertNotNull("命令没有执行体:" + command, CommandTable.get(command));
    }
  }

  /** commands 由 ActionService 自己处理,不能出现在命令表里,否则会绕开嵌套检查 */
  @Test
  public void batchCommandIsNotInTheTable() {
    assertFalse("commands 应当由 ActionService 处理", CommandTable.names().contains("commands"));
  }

  /** 改名后不能再留着旧名字,否则文档与代码会各说各话 */
  @Test
  public void legacyNamesAreGone() {
    Set<String> names = CommandTable.names();
    assertFalse("get_dom_text 已经改名为 get_browser_state", names.contains("get_dom_text"));
  }

  /** 自动截图的命令必须真实存在,否则写错名字就永远不截图且没人发现 */
  @Test
  public void captureSetOnlyContainsRealCommands() {
    List<String> unknown = new ArrayList<>();
    for (String command : PlaywrightHandler.PAGE_CHANGING) {
      if (CommandTable.get(command) == null) {
        unknown.add(command);
      }
    }
    assertTrue("自动截图集合里有不存在的命令:" + unknown, unknown.isEmpty());
  }

  /** get_browser_state 自己截图并落 .txt,不能同时被自动截图覆盖,否则一次调用产生两张图 */
  @Test
  public void browserStateIsNotDoubleCaptured() {
    assertFalse("get_browser_state 不应当在自动截图集合里",
        PlaywrightHandler.PAGE_CHANGING.contains("get_browser_state"));
  }

  /** 缺必填参数时给出中文原因,而不是 NPE 或 500 */
  @Test
  public void missingRequiredParameterIsReportedInChinese() {
    for (Map.Entry<String, String> entry : MISSING_PARAM_CASES.entrySet()) {
      CommandTable.Executor executor = CommandTable.get(entry.getKey());
      assertNotNull("命令表里没有这个命令:" + entry.getKey(), executor);
      try {
        executor.run(null, 1L, new JSONObject());
        throw new AssertionError(entry.getKey() + " 缺参数时没有报错");
      } catch (IllegalArgumentException e) {
        assertEquals(entry.getKey() + " 的报错参数名不对", "缺少参数 " + entry.getValue(), e.getMessage());
      }
    }
  }

  /** 每个命令都必须在缺参数测试里有覆盖,避免新增命令时忘了补用例 */
  @Test
  public void everyCommandIsCoveredByTheMissingParamCases() {
    Set<String> uncovered = new TreeSet<>(CommandTable.names());
    uncovered.removeAll(MISSING_PARAM_CASES.keySet());
    // 这几个命令的参数全是可选的,没有「缺必填参数」这一说
    uncovered.removeAll(Set.of("start", "close", "go_back", "go_forward", "reload", "get_url", "get_title",
        "get_browser_state", "get_tabs", "new_tab", "close_other_tabs", "bring_to_front", "clear_text",
        "hover_and_click", "wait_for_load", "mouse_down", "mouse_up", "screenshot", "get_element_screenshot",
        "pdf", "get_cookies", "set_cookie", "clear_cookies", "get_local_storage", "set_local_storage",
        "clear_local_storage", "set_offline", "get_dialog", "clear_dialog", "set_dialog_behavior",
        "get_console_logs", "clear_console_logs", "unroute", "get_requests", "get_response_body",
        "get_page_snapshot", "diff_dom_text", "get_interactive_map", "extract_structured_data"));
    assertTrue("这些命令没有缺参数用例:" + uncovered, uncovered.isEmpty());
  }
}
