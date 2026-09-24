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
    // upload_file 现在 index 与 selector 二选一,唯一必填的是 path(两个都没传时由服务层报错)
    cases.put("upload_file", "path");
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
    cases.put("mouse_click", "x");
    cases.put("mouse_click_by_selector", "selector");
    cases.put("wait_for_count", "selector");
    cases.put("run_recipe", "name");
    cases.put("get_job", "jobId");
    cases.put("cancel_job", "jobId");
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
        "get_page_snapshot", "diff_dom_text", "get_interactive_map", "extract_structured_data",
        // 这两个的参数全是可选的(超时/安静时长/作用范围都有默认值)
        "wait_for_idle", "get_form_state",
        // 新增的自省/弹窗/任务类命令:参数全可选,或压根不需要参数
        "wait_for_stable", "get_modals", "close_modal", "list_methods", "get_config", "list_tasks",
        "shutdown", "list_recipes", "list_jobs", "get_js_dialog", "clear_js_dialog", "cleanup",
        // frame 相关:list_frames 参数全可选;get_element_listeners 是 index/selector 二选一,
        // 两个都没传时由服务层给出「需要 index 或 selector 之一」
        "list_frames", "get_element_listeners",
        // ocr_image 同理:path 或 index/selector 三选一,服务层报「需要 path,或 index / selector 之一」
        "ocr_image"));
    assertTrue("这些命令没有缺参数用例:" + uncovered, uncovered.isEmpty());
  }

  /**
   * 方法名写错时要给出近似建议
   *
   * <p>实测最常见的错法是凭直觉猜名字:把页签列表写成 {@code list_tabs}(实际叫 {@code get_tabs})。
   * 只回一句「不支持的方法」会让模型再猜一轮,给出候选一次就能纠正。
   */
  @Test
  public void nearMissMethodNamesGetSuggestions() {
    assertTrue("list_tabs 应当建议 get_tabs,实际:" + CommandTable.suggest("list_tabs", 3),
        CommandTable.suggest("list_tabs", 3).contains("get_tabs"));
    assertTrue("click_by_index 应当建议 click_element_by_index,实际:" + CommandTable.suggest("click_by_index", 3),
        CommandTable.suggest("click_by_index", 3).contains("click_element_by_index"));
    assertTrue("input_text_by_select 应当建议 input_text_by_selector",
        CommandTable.suggest("input_text_by_select", 3).contains("input_text_by_selector"));
  }

  /** 完全不沾边的名字不要乱猜(宁可没有建议,也不要指向一个无关的命令) */
  @Test
  public void unrelatedNamesGetNoSuggestion() {
    assertTrue("毫无关系的名字不该给建议,实际:" + CommandTable.suggest("zzzzzzzzzzzz", 3),
        CommandTable.suggest("zzzzzzzzzzzz", 3).isEmpty());
  }

  /** 新的两个命令必须在表里,而且截图命令要能接受 inline */
  @Test
  public void newCommandsAreRegistered() {
    for (String command : new String[] { "wait_for_idle", "get_form_state" }) {
      assertNotNull("命令表里没有 " + command, CommandTable.get(command));
    }
  }
}
