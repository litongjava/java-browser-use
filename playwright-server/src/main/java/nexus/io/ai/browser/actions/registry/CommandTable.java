package nexus.io.ai.browser.actions.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.service.PlaywrightService;
import nexus.io.model.body.RespBodyVo;

/**
 * 命令表:唯一的方法分发处
 *
 * <p>对外只有一个 HTTP 端点 {@code POST /playwright/command},请求体形如
 * {@code {"id":123,"method":"go_to_url","params":{"url":"https://example.com"}}}。
 * {@code method} 就是这张表的键,{@code params} 里的参数名与下面 Executor 里读的键完全一致。
 *
 * <p>批量调用 {@code commands} 里的命令名用的是同一套名字,所以模型在「单独调用」和「批量调用」
 * 之间不需要切换心智模型。
 */
public class CommandTable {

  public interface Executor {
    RespBodyVo run(PlaywrightService svc, Long id, JSONObject args);
  }

  private static final Map<String, Executor> TABLE = new LinkedHashMap<>();

  static {
    // ---------- 实例生命周期 ----------
    put("start", (svc, id, a) -> {
      // 不传 headless 时按无头处理,避免在服务器上误弹出窗口
      Boolean headless = a.getBoolean("headless");
      long newId = svc.start(id, headless == null || headless);
      return RespBodyVo.ok(Kv.by("id", newId));
    });
    put("close", (svc, id, a) -> svc.close(id));

    // ---------- 导航与页面信息 ----------
    put("navigate", (svc, id, a) -> svc.navigate(id, reqStr(a, "url")));
    put("go_to_url", (svc, id, a) -> svc.goToUrl(id, reqStr(a, "url")));
    put("go_back", (svc, id, a) -> svc.goBack(id));
    put("go_forward", (svc, id, a) -> svc.goForward(id));
    put("reload", (svc, id, a) -> svc.reload(id));
    put("get_url", (svc, id, a) -> svc.getUrl(id));
    put("get_title", (svc, id, a) -> svc.getTitle(id));
    put("get_browser_state", (svc, id, a) -> svc.getBrowserState(id, a.getBoolean("highlight"),
        a.getInteger("viewportExpansion")));
    put("wait", (svc, id, a) -> svc.waitSeconds(id, reqInt(a, "seconds")));

    // ---------- 元素交互(按索引) ----------
    put("click_element_by_index", (svc, id, a) -> svc.clickElementByIndex(id, reqInt(a, "index")));
    put("double_click_element_by_index", (svc, id, a) -> svc.doubleClickElementByIndex(id, reqInt(a, "index")));
    put("hover_element_by_index", (svc, id, a) -> svc.hoverElementByIndex(id, reqInt(a, "index")));
    put("focus_element_by_index", (svc, id, a) -> svc.focusElementByIndex(id, reqInt(a, "index")));
    put("check_element_by_index", (svc, id, a) -> svc.checkElementByIndex(id, reqInt(a, "index")));
    put("uncheck_element_by_index", (svc, id, a) -> svc.uncheckElementByIndex(id, reqInt(a, "index")));
    put("input_text", (svc, id, a) -> svc.inputTextByIndex(id, reqInt(a, "index"), reqStr(a, "text")));
    put("type_text", (svc, id, a) -> svc.typeText(id, reqInt(a, "index"), reqStr(a, "text")));
    put("upload_file", (svc, id, a) -> svc.uploadFile(id, reqInt(a, "index"), reqStr(a, "path")));
    put("drag_element_by_index",
        (svc, id, a) -> svc.dragElementByIndex(id, reqInt(a, "index"), reqInt(a, "targetIndex")));
    put("send_keys", (svc, id, a) -> svc.sendKeys(id, reqStr(a, "keys")));
    put("key_down", (svc, id, a) -> svc.keyDown(id, reqStr(a, "keys")));
    put("key_up", (svc, id, a) -> svc.keyUp(id, reqStr(a, "keys")));
    put("get_dropdown_options", (svc, id, a) -> svc.getDropdownOptions(id, reqInt(a, "index")));
    put("select_dropdown_option",
        (svc, id, a) -> svc.selectDropdownOption(id, reqInt(a, "index"), reqStr(a, "text")));
    put("scroll", (svc, id, a) -> svc.scroll(id, optBool(a, "down"), reqInt(a, "numPages"),
        a.getInteger("index")));
    put("scroll_to_text", (svc, id, a) -> svc.scrollToText(id, reqStr(a, "text")));

    // ---------- 读取元素信息与状态 ----------
    put("get_element_text", (svc, id, a) -> svc.getElementText(id, reqInt(a, "index")));
    put("get_element_html", (svc, id, a) -> svc.getElementHtml(id, reqInt(a, "index")));
    put("get_element_value", (svc, id, a) -> svc.getElementValue(id, reqInt(a, "index")));
    put("get_element_attribute",
        (svc, id, a) -> svc.getElementAttribute(id, reqInt(a, "index"), reqStr(a, "name")));
    put("get_element_count", (svc, id, a) -> svc.getElementCount(id, reqStr(a, "selector")));
    put("get_element_box", (svc, id, a) -> svc.getElementBox(id, reqInt(a, "index")));
    put("is_visible", (svc, id, a) -> svc.isVisible(id, reqInt(a, "index")));
    put("is_enabled", (svc, id, a) -> svc.isEnabled(id, reqInt(a, "index")));
    put("is_checked", (svc, id, a) -> svc.isChecked(id, reqInt(a, "index")));

    // ---------- 选择器与语义定位 ----------
    put("click_element_by_selector", (svc, id, a) -> svc.clickElementBySelector(id, reqStr(a, "selector")));
    put("input_text_by_selector",
        (svc, id, a) -> svc.inputTextBySelector(id, reqStr(a, "selector"), reqStr(a, "text")));
    put("click_element_by_text", (svc, id, a) -> svc.clickElementByText(id, reqStr(a, "text")));
    put("click_element_by_role",
        (svc, id, a) -> svc.clickElementByRole(id, reqStr(a, "role"), optStr(a, "name")));
    put("input_text_by_label", (svc, id, a) -> svc.inputTextByLabel(id, reqStr(a, "label"), reqStr(a, "text")));
    put("clear_text", (svc, id, a) -> svc.clearText(id, a.getInteger("index"), optStr(a, "selector")));
    put("hover_and_click", (svc, id, a) -> svc.hoverAndClick(id, a.getInteger("index"), optStr(a, "selector"),
        a.getInteger("hoverDelayMs")));

    // ---------- 标签页 ----------
    put("get_tabs", (svc, id, a) -> svc.getTabs(id));
    put("new_tab", (svc, id, a) -> svc.newTab(id, optStr(a, "url")));
    put("switch_tab", (svc, id, a) -> svc.switchTab(id, reqInt(a, "pageIndex")));
    put("switch_tab_by_url", (svc, id, a) -> svc.switchTabByUrl(id, reqStr(a, "url")));
    put("close_tab", (svc, id, a) -> svc.closeTab(id, reqInt(a, "pageIndex")));
    put("close_other_tabs", (svc, id, a) -> svc.closeOtherTabs(id, a.getInteger("pageIndex")));
    put("bring_to_front", (svc, id, a) -> svc.bringToFront(id, a.getInteger("pageIndex")));

    // ---------- 等待 ----------
    put("wait_for_element",
        (svc, id, a) -> svc.waitForElement(id, reqStr(a, "selector"), a.getDouble("timeoutSeconds")));
    put("wait_for_text", (svc, id, a) -> svc.waitForText(id, reqStr(a, "text"), a.getDouble("timeoutSeconds")));
    put("wait_for_url", (svc, id, a) -> svc.waitForUrl(id, reqStr(a, "url"), a.getDouble("timeoutSeconds")));
    put("wait_for_load", (svc, id, a) -> svc.waitForLoad(id, optStr(a, "state"), a.getDouble("timeoutSeconds")));
    put("wait_for_function",
        (svc, id, a) -> svc.waitForFunction(id, reqStr(a, "expression"), a.getDouble("timeoutSeconds")));

    // ---------- 鼠标 ----------
    put("mouse_move", (svc, id, a) -> svc.mouseMove(id, reqDouble(a, "x"), reqDouble(a, "y")));
    put("mouse_down", (svc, id, a) -> svc.mouseDown(id, optStr(a, "button")));
    put("mouse_up", (svc, id, a) -> svc.mouseUp(id, optStr(a, "button")));
    put("mouse_wheel", (svc, id, a) -> svc.mouseWheel(id, reqDouble(a, "deltaY")));

    // ---------- 截图与 PDF ----------
    put("screenshot", (svc, id, a) -> svc.screenshot(id, optStr(a, "path"), a.getBoolean("fullPage"),
        a.getInteger("index"), optStr(a, "selector"), a.getDouble("clipX"), a.getDouble("clipY"),
        a.getDouble("clipWidth"), a.getDouble("clipHeight")));
    put("get_element_screenshot", (svc, id, a) -> svc.getElementScreenshot(id, a.getInteger("index"),
        optStr(a, "selector"), optStr(a, "path")));
    put("pdf", (svc, id, a) -> svc.pdf(id, optStr(a, "path")));

    // ---------- Cookie 与本地存储 ----------
    put("get_cookies", (svc, id, a) -> svc.getCookies(id, optStr(a, "url")));
    put("set_cookie",
        (svc, id, a) -> svc.setCookie(id, reqStr(a, "name"), reqStr(a, "value"), optStr(a, "url")));
    put("clear_cookies", (svc, id, a) -> svc.clearCookies(id));
    put("get_local_storage", (svc, id, a) -> svc.getLocalStorage(id, optStr(a, "key")));
    put("set_local_storage",
        (svc, id, a) -> svc.setLocalStorage(id, reqStr(a, "key"), optStr(a, "value")));
    put("clear_local_storage", (svc, id, a) -> svc.clearLocalStorage(id));

    // ---------- 浏览器设置 ----------
    put("set_viewport", (svc, id, a) -> svc.setViewport(id, reqInt(a, "width"), reqInt(a, "height")));
    put("set_geolocation",
        (svc, id, a) -> svc.setGeolocation(id, reqDouble(a, "latitude"), reqDouble(a, "longitude")));
    put("set_offline", (svc, id, a) -> svc.setOffline(id, optBool(a, "offline")));
    put("set_headers", (svc, id, a) -> svc.setHeaders(id, reqStr(a, "headersJson")));
    put("set_credentials",
        (svc, id, a) -> svc.setCredentials(id, reqStr(a, "username"), reqStr(a, "password")));
    put("set_media", (svc, id, a) -> svc.setMedia(id, reqStr(a, "colorScheme")));

    // ---------- 弹窗与控制台 ----------
    put("get_dialog", (svc, id, a) -> svc.getDialog(id, a.getBoolean("consume")));
    put("clear_dialog", (svc, id, a) -> svc.clearDialog(id));
    put("set_dialog_behavior", (svc, id, a) -> svc.setDialogBehavior(id, optBool(a, "dismiss")));
    put("get_console_logs", (svc, id, a) -> svc.getConsoleLogs(id));
    put("clear_console_logs", (svc, id, a) -> svc.clearConsoleLogs(id));

    // ---------- 网络 ----------
    put("route", (svc, id, a) -> svc.route(id, reqStr(a, "urlPattern"), optStr(a, "action"), optStr(a, "body"),
        a.getInteger("status"), optStr(a, "contentType")));
    put("unroute", (svc, id, a) -> svc.unroute(id, optStr(a, "urlPattern")));
    put("get_requests", (svc, id, a) -> svc.getRequests(id, optStr(a, "filter")));
    put("wait_for_response", (svc, id, a) -> svc.waitForResponse(id, reqStr(a, "urlPattern"),
        a.getDouble("timeoutSeconds"), a.getInteger("maxChars"), a.getInteger("lookBackSeconds")));
    put("get_response_body", (svc, id, a) -> svc.getResponseBody(id, optStr(a, "filter"), a.getInteger("index"),
        a.getInteger("maxChars")));

    // ---------- 页面状态汇总与快照差异 ----------
    put("get_page_snapshot", (svc, id, a) -> svc.getPageSnapshot(id, a.getBoolean("includeConsole"),
        a.getBoolean("includeRequests"), optStr(a, "requestFilter")));
    put("diff_dom_text",
        (svc, id, a) -> svc.diffDomText(id, a.getBoolean("highlight"), a.getInteger("viewportExpansion")));
    put("get_interactive_map", (svc, id, a) -> svc.getInteractiveMap(id));

    // ---------- 其它 ----------
    put("extract_structured_data",
        (svc, id, a) -> svc.extractStructuredData(id, optStr(a, "query"), optBool(a, "extractLinks")));
    put("execute_js", (svc, id, a) -> svc.executeJs(id, reqStr(a, "body")));

    // ---------- 人机协同 ----------
    put("request_human_input", (svc, id, a) -> svc.requestHumanInput(id, reqStr(a, "prompt"), a.getInteger("index"),
        optStr(a, "selector"), a.getInteger("timeoutSeconds")));
    put("submit_human_input", (svc, id, a) -> svc.submitHumanInput(id, reqStr(a, "requestId"), optStr(a, "answer")));
    put("get_human_input", (svc, id, a) -> svc.getHumanInput(id, reqStr(a, "requestId"),
        a.getInteger("timeoutSeconds")));
  }

  private CommandTable() {
  }

  private static void put(String command, Executor executor) {
    TABLE.put(command, executor);
  }

  public static Executor get(String command) {
    return TABLE.get(command);
  }

  public static Set<String> names() {
    return Collections.unmodifiableSet(TABLE.keySet());
  }

  // ==================== 参数读取,缺失必填参数时给出中文原因 ====================

  private static int reqInt(JSONObject args, String key) {
    Integer value = args.getInteger(key);
    if (value == null) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static double reqDouble(JSONObject args, String key) {
    Double value = args.getDouble(key);
    if (value == null) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static String reqStr(JSONObject args, String key) {
    String value = args.getString(key);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static String optStr(JSONObject args, String key) {
    String value = args.getString(key);
    return value == null || value.isEmpty() ? null : value;
  }

  private static boolean optBool(JSONObject args, String key) {
    Boolean value = args.getBoolean(key);
    return value != null && value;
  }
}
