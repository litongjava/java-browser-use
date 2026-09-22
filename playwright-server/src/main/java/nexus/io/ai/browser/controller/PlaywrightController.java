package nexus.io.ai.browser.controller;

import java.util.Map;

import com.jfinal.kit.Kv;
import nexus.io.ai.browser.service.ActionService;
import nexus.io.ai.browser.service.PlaywrightService;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;

import nexus.io.annotation.RequestPath;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.http.common.HttpMethod;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.utils.validator.ParameterValidationException;
import nexus.io.tio.utils.validator.ParameterValidator;

@RequestPath("/api/v1/playwright")
public class PlaywrightController {

  /** execute_js 允许的最大脚本长度(字符) */
  private static final int MAX_JS_LENGTH = 100_000;

  private final PlaywrightService svc = Aop.get(PlaywrightService.class);
  private final ActionService actionService = Aop.get(ActionService.class);

  @RequestPath("/start")
  public RespBodyVo start(Long id, boolean headless) {
    long newId = svc.start(id, headless, false);
    return RespBodyVo.ok(Kv.by("id", newId));
  }

  @RequestPath("/navigate")
  public RespBodyVo navigate(Long id, String url) {
    if (svc.getInstance(id) == null) {
      return RespBodyVo.fail("没有找到对应的浏览器实例：" + id);
    }
    try {
      Response rsp = svc.navigate(id, url);
      return RespBodyVo.ok(Kv.by("status", rsp == null ? 0 : rsp.status()));
    } catch (PlaywrightException e) {
      return RespBodyVo.fail("navigate 失败：" + PlaywrightService.briefMessage(e.getMessage()));
    }
  }

  @RequestPath("/go_to_url")
  public RespBodyVo goToUrl(Long id, String url) {
    return svc.goToUrl(id, url);
  }

  /**
   * 获取页面结构化文本(buildDomTree),供 AI 阅读理解页面并拿到元素索引
   *
   * <pre>
   * GET /api/v1/playwright/get_dom_text?id=1234567890
   * GET /api/v1/playwright/get_dom_text?id=1234567890&amp;highlight=false&amp;viewportExpansion=1000
   * </pre>
   *
   * 返回 data.text,每行形如 [index]&lt;tag attr='value'&gt;文本/&gt;;
   * 其中的 index 就是按索引操作元素的接口要用的元素索引:click_element_by_index、input_text、
   * upload_file、get_dropdown_options、select_dropdown_option、double_click_element_by_index、
   * hover_element_by_index、focus_element_by_index、check_element_by_index、uncheck_element_by_index、
   * type_text、drag_element_by_index、get_element_text、get_element_html、get_element_value、
   * get_element_attribute、get_element_box、is_visible、is_enabled、is_checked。
   * 页面变化后需要重新调用。
   */
  @RequestPath("/get_dom_text")
  public RespBodyVo getDomText(Long id, Boolean highlight, Integer viewportExpansion) {
    return svc.getDomText(id, highlight, viewportExpansion);
  }

  /** 前进,返回 data.status */
  @RequestPath("/go_forward")
  public RespBodyVo goForward(Long id) {
    return svc.goForward(id);
  }

  /** 刷新当前页,返回 data.status */
  @RequestPath("/reload")
  public RespBodyVo reload(Long id) {
    return svc.reload(id);
  }

  @RequestPath("/get_url")
  public RespBodyVo getUrl(Long id) {
    return svc.getUrl(id);
  }

  @RequestPath("/get_title")
  public RespBodyVo getTitle(Long id) {
    return svc.getTitle(id);
  }

  @RequestPath("/go_back")
  public RespBodyVo goBack(Long id) {
    return svc.goBack(id);
  }

  @RequestPath("/wait")
  public RespBodyVo wait(Long id, Integer seconds) {
    return svc.wait(id, seconds);
  }

  @RequestPath("/click_element_by_index")
  public RespBodyVo click(Long id, Integer index) {
    return svc.clickElementByIndex(id, index);
  }

  @RequestPath("/input_text")
  public RespBodyVo inputText(Long id, Integer index, String text) {
    return svc.inputTextByIndex(id, index, text);
  }

  /**
   * 清空输入框,返回 data.value
   *
   * <pre>
   * GET /api/v1/playwright/clear_text?id=1&amp;index=3
   * </pre>
   *
   * <p>input_text 的 text 是必填参数,传空串会被参数校验挡掉,所以清空单独走这个接口。
   * index 与 selector 传一个即可。
   */
  @RequestPath("/clear_text")
  public RespBodyVo clearText(Long id, Integer index, String selector) {
    return svc.clearText(id, index, selector);
  }

  /**
   * 悬停后立刻点击同一个元素
   *
   * <pre>
   * GET /api/v1/playwright/hover_and_click?id=1&amp;index=5
   * GET /api/v1/playwright/hover_and_click?id=1&amp;selector=a%5Btitle%3D%27%E6%9F%A5%E8%AF%A2%27%5D
   * </pre>
   *
   * <p>悬浮菜单必须 hover 才渲染,分两步调用中间隔着一次推理往返,菜单早收起来了。
   * hoverDelayMs 默认 300 毫秒。返回点击回执与真正命中的 tag/outerHtml。
   */
  @RequestPath("/hover_and_click")
  public RespBodyVo hoverAndClick(Long id, Integer index, String selector, Integer hoverDelayMs) {
    return svc.hoverAndClick(id, index, selector, hoverDelayMs);
  }

  @RequestPath("/upload_file")
  public RespBodyVo uploadFile(Long id, Integer index, String path) {
    return svc.uploadFile(id, index, path);
  }

  @RequestPath("/switch_tab")
  public RespBodyVo switchTab(Long id, Integer pageIndex) {
    return svc.switchTab(id, pageIndex);
  }

  /**
   * 按 URL 匹配切换当前页签,返回 data.pageIndex
   *
   * <pre>
   * GET /api/v1/playwright/switch_tab_by_url?id=1&amp;url=**&#47;nsrxxcx*
   * </pre>
   *
   * <p>页签多了以后 pageIndex 不稳定(点一次菜单可能弹出两个同 URL 的重复页签),按 URL 找更可靠。
   * url 支持 `**` 通配与子串匹配;切过去的页签会被带到最前。
   */
  @RequestPath("/switch_tab_by_url")
  public RespBodyVo switchTabByUrl(Long id, String url) {
    return svc.switchTabByUrl(id, url);
  }

  @RequestPath("/close_tab")
  public RespBodyVo closeTab(Long id, Integer pageIndex) {
    return svc.closeTab(id, pageIndex);
  }

  /**
   * 关掉除指定页签之外的全部页签,返回 data.closed 与 data.remaining
   *
   * <pre>
   * GET /api/v1/playwright/close_other_tabs?id=1
   * </pre>
   *
   * <p>pageIndex 不传时保留当前页签。关重复页签用这个最省事:一个个 close_tab 会因为索引整体
   * 前移而关错。
   */
  @RequestPath("/close_other_tabs")
  public RespBodyVo closeOtherTabs(Long id, Integer pageIndex) {
    return svc.closeOtherTabs(id, pageIndex);
  }

  /**
   * 把指定页签(默认当前页签)带到窗口最前,返回 data.pageIndex
   *
   * <pre>
   * GET /api/v1/playwright/bring_to_front?id=1&amp;pageIndex=2
   * </pre>
   *
   * <p>只切窗口、不改当前操作页,适合「让操作的人看一眼这一页」。有头模式下人工能立刻看到。
   */
  @RequestPath("/bring_to_front")
  public RespBodyVo bringToFront(Long id, Integer pageIndex) {
    return svc.bringToFront(id, pageIndex);
  }

  @RequestPath("/extract_structured_data")
  public RespBodyVo extractStructuredData(Long id, String query, boolean extractLinks) {
    return svc.extractStructuredData(id, query, extractLinks);
  }

  @RequestPath("/scroll")
  public RespBodyVo scroll(Long id, boolean down, Integer numPages, Integer index) {
    return svc.scroll(id, down, numPages, index);
  }

  @RequestPath("/send_keys")
  public RespBodyVo sendKeys(Long id, String keys) {
    return svc.sendKeys(id, keys);
  }

  @RequestPath("/scroll_to_text")
  public RespBodyVo scrollToText(Long id, String text) {
    return svc.scrollToText(id, text);
  }

  @RequestPath("/get_dropdown_options")
  public RespBodyVo getDropdownOptions(Long id, Integer index) {
    return svc.getDropdownOptions(id, index);
  }

  @RequestPath("/select_dropdown_option")
  public RespBodyVo selectDropdownOption(Long id, Integer index, String text) {
    return svc.selectDropdownOption(id, index, text);
  }

  /**
   * 在浏览器当前页面执行 JavaScript
   *
   * <pre>
   * POST /api/v1/playwright/execute_js
   * { "id": 1234567890, "body": "document.title" }
   * </pre>
   *
   * 参数 id 和 body 可以放在 JSON 请求体中,也可以放在查询参数或表单中,JSON 中的同名字段优先。
   * body 支持表达式、函数和包含 return 的语句片段,返回值需要是 JSON 可序列化的值。
   */
  @RequestPath("/execute_js")
  public RespBodyVo executeJs(HttpRequest request) {
    try {
      Map<String, Object> params = request.getRequestMap();
      ParameterValidator.require(params.get("id") != null, "id is required");
      long id = ParameterValidator.id(params.get("id"), "id");
      String body = ParameterValidator.text(params.get("body"), "body", MAX_JS_LENGTH);
      return svc.executeJs(id, body);
    } catch (ParameterValidationException e) {
      return RespBodyVo.fail(paramFailure(e));
    }
  }

  // ==================== 交互 ====================

  @RequestPath("/double_click_element_by_index")
  public RespBodyVo doubleClick(Long id, Integer index) {
    return svc.doubleClickElementByIndex(id, index);
  }

  @RequestPath("/hover_element_by_index")
  public RespBodyVo hover(Long id, Integer index) {
    return svc.hoverElementByIndex(id, index);
  }

  @RequestPath("/focus_element_by_index")
  public RespBodyVo focus(Long id, Integer index) {
    return svc.focusElementByIndex(id, index);
  }

  @RequestPath("/check_element_by_index")
  public RespBodyVo check(Long id, Integer index) {
    return svc.checkElementByIndex(id, index);
  }

  @RequestPath("/uncheck_element_by_index")
  public RespBodyVo uncheck(Long id, Integer index) {
    return svc.uncheckElementByIndex(id, index);
  }

  /** 不清空原内容,逐字输入 */
  @RequestPath("/type_text")
  public RespBodyVo typeText(Long id, Integer index, String text) {
    return svc.typeText(id, index, text);
  }

  @RequestPath("/drag_element_by_index")
  public RespBodyVo drag(Long id, Integer index, Integer targetIndex) {
    return svc.dragElementByIndex(id, index, targetIndex);
  }

  @RequestPath("/key_down")
  public RespBodyVo keyDown(Long id, String keys) {
    return svc.keyDown(id, keys);
  }

  @RequestPath("/key_up")
  public RespBodyVo keyUp(Long id, String keys) {
    return svc.keyUp(id, keys);
  }

  // ==================== 读取元素信息 ====================

  @RequestPath("/get_element_text")
  public RespBodyVo getElementText(Long id, Integer index) {
    return svc.getElementText(id, index);
  }

  @RequestPath("/get_element_html")
  public RespBodyVo getElementHtml(Long id, Integer index) {
    return svc.getElementHtml(id, index);
  }

  @RequestPath("/get_element_value")
  public RespBodyVo getElementValue(Long id, Integer index) {
    return svc.getElementValue(id, index);
  }

  @RequestPath("/get_element_attribute")
  public RespBodyVo getElementAttribute(Long id, Integer index, String name) {
    return svc.getElementAttribute(id, index, name);
  }

  @RequestPath("/get_element_count")
  public RespBodyVo getElementCount(Long id, String selector) {
    return svc.getElementCount(id, selector);
  }

  @RequestPath("/get_element_box")
  public RespBodyVo getElementBox(Long id, Integer index) {
    return svc.getElementBox(id, index);
  }

  // ==================== 元素状态 ====================

  @RequestPath("/is_visible")
  public RespBodyVo isVisible(Long id, Integer index) {
    return svc.isVisible(id, index);
  }

  @RequestPath("/is_enabled")
  public RespBodyVo isEnabled(Long id, Integer index) {
    return svc.isEnabled(id, index);
  }

  @RequestPath("/is_checked")
  public RespBodyVo isChecked(Long id, Integer index) {
    return svc.isChecked(id, index);
  }

  // ==================== 按选择器/文本/语义定位 ====================

  @RequestPath("/click_element_by_selector")
  public RespBodyVo clickBySelector(Long id, String selector) {
    return svc.clickElementBySelector(id, selector);
  }

  @RequestPath("/input_text_by_selector")
  public RespBodyVo inputBySelector(Long id, String selector, String text) {
    return svc.inputTextBySelector(id, selector, text);
  }

  @RequestPath("/click_element_by_text")
  public RespBodyVo clickByText(Long id, String text) {
    return svc.clickElementByText(id, text);
  }

  /** role 取 Playwright 的 role 名,如 button、link、textbox、checkbox */
  @RequestPath("/click_element_by_role")
  public RespBodyVo clickByRole(Long id, String role, String name) {
    return svc.clickElementByRole(id, role, name);
  }

  @RequestPath("/input_text_by_label")
  public RespBodyVo inputByLabel(Long id, String label, String text) {
    return svc.inputTextByLabel(id, label, text);
  }

  // ==================== 标签页 ====================

  @RequestPath("/get_tabs")
  public RespBodyVo getTabs(Long id) {
    return svc.getTabs(id);
  }

  /** 新建标签页并切换过去,返回 data.pageIndex */
  @RequestPath("/new_tab")
  public RespBodyVo newTab(Long id, String url) {
    return svc.newTab(id, url);
  }

  // ==================== 等待 ====================

  @RequestPath("/wait_for_element")
  public RespBodyVo waitForElement(Long id, String selector, Double timeoutSeconds) {
    return svc.waitForElement(id, selector, timeoutSeconds);
  }

  @RequestPath("/wait_for_text")
  public RespBodyVo waitForText(Long id, String text, Double timeoutSeconds) {
    return svc.waitForText(id, text, timeoutSeconds);
  }

  @RequestPath("/wait_for_url")
  public RespBodyVo waitForUrl(Long id, String url, Double timeoutSeconds) {
    return svc.waitForUrl(id, url, timeoutSeconds);
  }

  /** state 取 load / domcontentloaded / networkidle */
  @RequestPath("/wait_for_load")
  public RespBodyVo waitForLoad(Long id, String state, Double timeoutSeconds) {
    return svc.waitForLoad(id, state, timeoutSeconds);
  }

  /** 等待 JS 表达式为真 */
  @RequestPath("/wait_for_function")
  public RespBodyVo waitForFunction(Long id, String expression, Double timeoutSeconds) {
    return svc.waitForFunction(id, expression, timeoutSeconds);
  }

  // ==================== 鼠标 ====================

  @RequestPath("/mouse_move")
  public RespBodyVo mouseMove(Long id, Double x, Double y) {
    return svc.mouseMove(id, x, y);
  }

  /** button 取 left / right / middle */
  @RequestPath("/mouse_down")
  public RespBodyVo mouseDown(Long id, String button) {
    return svc.mouseDown(id, button);
  }

  @RequestPath("/mouse_up")
  public RespBodyVo mouseUp(Long id, String button) {
    return svc.mouseUp(id, button);
  }

  @RequestPath("/mouse_wheel")
  public RespBodyVo mouseWheel(Long id, Double deltaY) {
    return svc.mouseWheel(id, deltaY);
  }

  // ==================== 截图与 PDF ====================

  /**
   * 传 path 存到服务器本地文件并返回 data.path;不传则返回 data.base64
   *
   * <pre>
   * GET /api/v1/playwright/screenshot?id=1&amp;path=D:/shot.png&amp;fullPage=true
   * </pre>
   */
  @RequestPath("/screenshot")
  public RespBodyVo screenshot(Long id, String path, Boolean fullPage, Integer index, String selector, Double clipX,
      Double clipY, Double clipWidth, Double clipHeight) {
    return svc.screenshot(id, path, fullPage, index, selector, clipX, clipY, clipWidth, clipHeight);
  }

  /**
   * 只截一个元素(验证码、二维码、图表这类必须看图的元素)
   *
   * <pre>
   * GET /api/v1/playwright/get_element_screenshot?id=1&amp;index=7
   * GET /api/v1/playwright/get_element_screenshot?id=1&amp;selector=%23captcha&amp;path=D:/captcha.png
   * </pre>
   *
   * <p>index 与 selector 传一个即可;不传 path 时返回 data.base64,可以直接交给视觉模型或人工看。
   */
  @RequestPath("/get_element_screenshot")
  public RespBodyVo getElementScreenshot(Long id, Integer index, String selector, String path) {
    return svc.getElementScreenshot(id, index, selector, path);
  }

  @RequestPath("/pdf")
  public RespBodyVo pdf(Long id, String path) {
    return svc.pdf(id, path);
  }

  // ==================== Cookie 与本地存储 ====================

  /** 传 url 只返回该站点可见的 Cookie */
  @RequestPath("/get_cookies")
  public RespBodyVo getCookies(Long id, String url) {
    return svc.getCookies(id, url);
  }

  /** 建议同时传 url,否则需要 Cookie 自带 domain */
  @RequestPath("/set_cookie")
  public RespBodyVo setCookie(Long id, String name, String value, String url) {
    return svc.setCookie(id, name, value, url);
  }

  @RequestPath("/clear_cookies")
  public RespBodyVo clearCookies(Long id) {
    return svc.clearCookies(id);
  }

  /** 不传 key 时返回整个 localStorage 的 JSON 字符串 */
  @RequestPath("/get_local_storage")
  public RespBodyVo getLocalStorage(Long id, String key) {
    return svc.getLocalStorage(id, key);
  }

  @RequestPath("/set_local_storage")
  public RespBodyVo setLocalStorage(Long id, String key, String value) {
    return svc.setLocalStorage(id, key, value);
  }

  @RequestPath("/clear_local_storage")
  public RespBodyVo clearLocalStorage(Long id) {
    return svc.clearLocalStorage(id);
  }

  // ==================== 浏览器设置 ====================

  @RequestPath("/set_viewport")
  public RespBodyVo setViewport(Long id, Integer width, Integer height) {
    return svc.setViewport(id, width, height);
  }

  @RequestPath("/set_geolocation")
  public RespBodyVo setGeolocation(Long id, Double latitude, Double longitude) {
    return svc.setGeolocation(id, latitude, longitude);
  }

  @RequestPath("/set_offline")
  public RespBodyVo setOffline(Long id, boolean offline) {
    return svc.setOffline(id, offline);
  }

  /** headersJson 形如 {"X-Key":"v"} */
  @RequestPath("/set_headers")
  public RespBodyVo setHeaders(Long id, String headersJson) {
    return svc.setHeaders(id, headersJson);
  }

  /** 会重建上下文,当前页面会丢失 */
  @RequestPath("/set_credentials")
  public RespBodyVo setCredentials(Long id, String username, String password) {
    return svc.setCredentials(id, username, password);
  }

  /** colorScheme 取 light / dark / no-preference */
  @RequestPath("/set_media")
  public RespBodyVo setMedia(Long id, String colorScheme) {
    return svc.setMedia(id, colorScheme);
  }

  // ==================== 弹窗 / 控制台 ====================

  /**
   * 返回最近一次弹窗,data.dialog 里带 seq 与 timestamp
   *
   * <p>弹窗记录**不会自动清除**,可能来自很早以前的一次操作,所以别把它的内容当成当前这一步的
   * 结果。consume=true 时读后即清,推荐在每次提交动作前先 consume 一次。
   */
  @RequestPath("/get_dialog")
  public RespBodyVo getDialog(Long id, Boolean consume) {
    return svc.getDialog(id, consume);
  }

  /** 清空弹窗记录,返回 data.cleared */
  @RequestPath("/clear_dialog")
  public RespBodyVo clearDialog(Long id) {
    return svc.clearDialog(id);
  }

  /** dismiss=true 时后续弹窗自动取消 */
  @RequestPath("/set_dialog_behavior")
  public RespBodyVo setDialogBehavior(Long id, boolean dismiss) {
    return svc.setDialogBehavior(id, dismiss);
  }

  @RequestPath("/get_console_logs")
  public RespBodyVo getConsoleLogs(Long id) {
    return svc.getConsoleLogs(id);
  }

  @RequestPath("/clear_console_logs")
  public RespBodyVo clearConsoleLogs(Long id) {
    return svc.clearConsoleLogs(id);
  }

  // ==================== 网络 ====================

  /** action 取 abort(拦截)或 mock(返回自定义响应) */
  @RequestPath("/route")
  public RespBodyVo route(Long id, String urlPattern, String action, String body, Integer status, String contentType) {
    return svc.route(id, urlPattern, action, body, status, contentType);
  }

  /** 不传 urlPattern 时移除全部路由 */
  @RequestPath("/unroute")
  public RespBodyVo unroute(Long id, String urlPattern) {
    return svc.unroute(id, urlPattern);
  }

  /** 返回 data.requests(method/url/resourceType/status,带请求体时另有 postData),最多 200 条 */
  @RequestPath("/get_requests")
  public RespBodyVo getRequests(Long id, String filter) {
    return svc.getRequests(id, filter);
  }

  /**
   * 等一个网络响应并返回响应体
   *
   * <pre>
   * GET /api/v1/playwright/wait_for_response?id=1&amp;urlPattern=**&#47;api&#47;query*&amp;timeoutSeconds=20
   * </pre>
   *
   * <p>urlPattern 用 Playwright 通配。**先回看再等**：lookBackSeconds（默认 10 秒）内已经收到过的
   * 匹配响应会直接返回并把 data.ageMs 设成它距今的毫秒数 —— 这样批量里「点击 → wait_for_response」
   * 的顺序写法也能命中（响应往往在点击返回前就到了），不必并发对同一个实例发请求。
   * lookBackSeconds=0 表示只等新响应。返回 data.url、data.status、data.body(默认最多 20000 字符，
   * 可用 maxChars 调整)、data.bodyLength、data.ageMs、data.fromLookBack。
   */
  @RequestPath("/wait_for_response")
  public RespBodyVo waitForResponse(Long id, String urlPattern, Double timeoutSeconds, Integer maxChars,
      Integer lookBackSeconds) {
    return svc.waitForResponse(id, urlPattern, timeoutSeconds, maxChars, lookBackSeconds);
  }

  /**
   * 回看最近一个匹配的响应体(保留最近 100 个响应)
   *
   * <pre>
   * GET /api/v1/playwright/get_response_body?id=1&amp;filter=/api/query
   * </pre>
   *
   * <p>filter 按 URL 子串过滤,不传取最近一个;index 在多个匹配里选第几个(默认最后一个)。
   */
  @RequestPath("/get_response_body")
  public RespBodyVo getResponseBody(Long id, String filter, Integer index, Integer maxChars) {
    return svc.getResponseBody(id, filter, index, maxChars);
  }

  /**
   * 一次拿到页面当前状态:url、title、tabs、dialog、loading
   *
   * <pre>
   * GET /api/v1/playwright/get_page_snapshot?id=1&amp;includeConsole=true&amp;includeRequests=true
   * </pre>
   *
   * <p>替代 get_url + get_title + get_tabs + get_dialog + get_console_logs + get_requests 六次调用。
   * includeConsole=true 时额外返回 data.logs / data.errors;includeRequests=true 时额外返回
   * data.requests(可用 requestFilter 按 URL 子串过滤)。不含 DOM 快照文本。
   */
  @RequestPath("/get_page_snapshot")
  public RespBodyVo getPageSnapshot(Long id, Boolean includeConsole, Boolean includeRequests, String requestFilter) {
    return svc.getPageSnapshot(id, includeConsole, includeRequests, requestFilter);
  }

  /**
   * 与上一次快照比较,返回新增/消失的行
   *
   * <pre>
   * GET /api/v1/playwright/diff_dom_text?id=1
   * </pre>
   *
   * <p>会重新执行一次 buildDomTree。data.changed=false 说明页面快照和上一次完全一样;
   * data.added / data.removed 各最多 200 行。
   */
  @RequestPath("/diff_dom_text")
  public RespBodyVo diffDomText(Long id, Boolean highlight, Integer viewportExpansion) {
    return svc.diffDomText(id, highlight, viewportExpansion);
  }

  /**
   * 返回「索引 → 元素定位信息」的映射
   *
   * <pre>
   * GET /api/v1/playwright/get_interactive_map?id=1
   * </pre>
   *
   * <p>快照文本里没有 id/class/href,这个接口按当前快照的 xpath 一次回查全部元素,给出
   * index、tag、xpath、id、className、href、name、text。索引仍来自最近一次 get_dom_text。
   */
  @RequestPath("/get_interactive_map")
  public RespBodyVo getInteractiveMap(Long id) {
    return svc.getInteractiveMap(id);
  }

  // ==================== 人机协同 ====================

  /**
   * 发起一个人工介入请求(验证码、短信码、人工登录)
   *
   * <pre>
   * GET /api/v1/playwright/request_human_input?id=1&amp;prompt=请输入验证码&amp;index=7
   * </pre>
   *
   * <p>传 index 或 selector 时会把该元素(通常是验证码图)截成 base64 一起返回,并把当前页签
   * 带到最前。返回 data.requestId、data.prompt、data.expiresAt、data.url、data.imageBase64。
   * 拿到答复的流程见 submit_human_input 与 get_human_input。
   */
  @RequestPath("/request_human_input")
  public RespBodyVo requestHumanInput(Long id, String prompt, Integer index, String selector, Integer timeoutSeconds) {
    return svc.requestHumanInput(id, prompt, index, selector, timeoutSeconds);
  }

  /** 提交人工答复,返回 data.status 与 data.answer */
  @RequestPath("/submit_human_input")
  public RespBodyVo submitHumanInput(Long id, String requestId, String answer) {
    return svc.submitHumanInput(id, requestId, answer);
  }

  /**
   * 取人工答复
   *
   * <pre>
   * GET /api/v1/playwright/get_human_input?id=1&amp;requestId=hr-1-123&amp;timeoutSeconds=60
   * </pre>
   *
   * <p>data.status 为 pending / answered / expired;传 timeoutSeconds 时长轮询等待,到时间还没
   * 答复就返回当前状态(不算失败)。
   */
  @RequestPath("/get_human_input")
  public RespBodyVo getHumanInput(Long id, String requestId, Integer timeoutSeconds) {
    return svc.getHumanInput(id, requestId, timeoutSeconds);
  }

  @RequestPath("/close")
  public RespBodyVo close(Long id) {
    return svc.close(id);
  }

  /**
   * 批量执行指令:一次请求跑完一整个计划,用于 PTC(Programmatic Tool Calling)
   *
   * <pre>
   * POST /api/v1/playwright/commands
   * { "id": 1234567890, "stopOnError": false,
   *   "commands": [ { "go_to_url": { "url": "https://www.taobao.com" } },
   *                 { "get_dom_text": {} } ] }
   * </pre>
   *
   * 只支持 POST + JSON 请求体。请求体可以直接是命令数组、对象载荷(id/stopOnError/commands),
   * 也可以只写一条命令;数组体带不了 id,所以数组体时 id 要放在查询串里(POST 带查询串是允许的)。
   *
   * <p>不支持 GET、不支持表单、不支持 bodyJson 字段:批量指令会改浏览器状态(点击、跳转),
   * 不是只读接口;而且 JSON 塞进 URL 要做百分号编码、还有长度上限。
   *
   * 除 commands 自身外的全部接口都能作为批量命令,命令名就是接口路径去掉斜杠。
   */
  @RequestPath("/commands")
  public RespBodyVo batchExecute(HttpRequest request) {
    if (request.getMethod() != HttpMethod.POST) {
      return RespBodyVo.fail("批量指令只支持 POST + JSON 请求体,请用 POST 并把命令放在请求体里");
    }
    String body = request.getBodyString();
    Long id;
    try {
      // 查询串里的 id 是可选的,请求体里的 id 优先(由 ActionService 判断)
      id = queryId(request);
    } catch (ParameterValidationException e) {
      return RespBodyVo.fail(paramFailure(e));
    }
    return actionService.batchExecute(id, body == null ? null : body.trim());
  }

  /** 框架的参数校验异常是英文的(例如 Invalid JSON),请求体格式这类错误补一句中文说明 */
  private static String paramFailure(ParameterValidationException e) {
    String message = e.getMessage();
    if (message == null) {
      return "参数校验失败";
    }
    if (message.contains("Invalid JSON") || message.contains("JSON object required")) {
      return "请求体不是合法 JSON：" + message;
    }
    return message;
  }

  /**
   * 取查询串里的 id,不解析请求体
   *
   * <p>不用 getRequestMap():它遇到 JSON 数组体会直接抛「JSON object required」,
   * 而请求体要原样交给 ActionService 解析,这里只负责查询串这一路。
   */
  private static Long queryId(HttpRequest request) {
    String value = request.getParam("id");
    if (value == null || value.isBlank()) {
      return null;
    }
    return ParameterValidator.id(value, "id");
  }
}
