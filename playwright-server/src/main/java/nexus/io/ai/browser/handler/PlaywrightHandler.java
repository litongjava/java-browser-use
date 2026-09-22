package nexus.io.ai.browser.handler;

import java.util.Set;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ActionService;
import nexus.io.ai.browser.service.BrowserInstance;
import nexus.io.ai.browser.service.PlaywrightService;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpMethod;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.server.handler.HttpRequestHandler;
import nexus.io.tio.http.server.util.CORSUtils;

/**
 * 唯一的浏览器控制端点
 *
 * <p>所有操作都走一个 POST,请求体形如:
 *
 * <pre>
 * { "id": 1234567890,
 *   "method": "start",
 *   "params": { "headless": false } }
 * </pre>
 *
 * <ul>
 * <li>{@code id}:任务 ID。{@code start} 时可以传(作为任务标识)也可以不传(自动生成雪花 ID);
 * 其余方法必须传,用来定位这个任务的浏览器实例。</li>
 * <li>{@code method}:命令名,与 {@code commands} 批量里的键、与响应里的错误提示完全一致。</li>
 * <li>{@code params}:该命令的参数,可以省略。</li>
 * </ul>
 *
 * <p>响应统一是 {@code {"data":{},"code":1,"ok":true,"error":null,"msg":null}}。
 *
 * <p>「会改变页面」的命令执行成功后,这里会自动给当前页面截一张图,落到
 * {@code data/<id>/<seq>.png}(序号从 1 开始递增),并在响应的 data 里回填
 * {@code seq} / {@code screenshot}(可直接 GET 的 URL)/ {@code screenshot_path}。
 * 截图列表就是这次任务的页面变化历史,视觉模型可以直接按 URL 取图。
 */
@Slf4j
public class PlaywrightHandler implements HttpRequestHandler {

  /**
   * 会改变页面的命令:执行成功后自动截图
   *
   * <p>这里列的是「动作类」命令;纯读取类(get_url、get_cookies、is_visible……)不截图,否则
   * 每读一个值就多一张一模一样的图。{@code get_browser_state} 不在集合里 —— 它自己会截图并
   * 额外落一份同名的可交互结构化文本。
   */
  public static final Set<String> PAGE_CHANGING = Set.of(
      // 导航
      "navigate", "go_to_url", "go_back", "go_forward", "reload",
      // 点击与交互
      "click_element_by_index", "double_click_element_by_index", "click_element_by_selector",
      "click_element_by_text", "click_element_by_role", "hover_and_click", "hover_element_by_index",
      "focus_element_by_index", "check_element_by_index", "uncheck_element_by_index",
      "input_text", "input_text_by_selector", "input_text_by_label", "type_text", "clear_text",
      "send_keys", "key_down", "key_up", "select_dropdown_option", "upload_file", "drag_element_by_index",
      // 滚动与鼠标
      "scroll", "scroll_to_text", "mouse_move", "mouse_down", "mouse_up", "mouse_wheel",
      // 页签
      "new_tab", "switch_tab", "switch_tab_by_url", "close_tab", "close_other_tabs", "bring_to_front",
      // 等待(等到了页面往往就变了)
      "wait", "wait_for_element", "wait_for_text", "wait_for_url", "wait_for_load", "wait_for_function",
      // 脚本与设置
      "execute_js", "set_viewport", "set_media", "set_credentials");

  @Override
  public HttpResponse handle(HttpRequest request) throws Exception {
    HttpResponse response = TioRequestContext.getResponse();
    CORSUtils.enableCORS(response);
    response.body(dispatch(request));
    return response;
  }

  private RespBodyVo dispatch(HttpRequest request) {
    if (request.getMethod() != HttpMethod.POST) {
      return RespBodyVo.fail("浏览器控制接口只支持 POST,请把 {id, method, params} 放在 JSON 请求体里");
    }
    String body = request.getBodyString();
    if (body == null || body.isBlank()) {
      return RespBodyVo.fail("请求体不能为空,需要 {\"id\":123,\"method\":\"start\",\"params\":{}}");
    }
    JSONObject payload;
    try {
      payload = JSONObject.parseObject(body);
    } catch (Exception e) {
      return RespBodyVo.fail("请求体不是合法 JSON：" + PlaywrightService.briefMessage(e.getMessage()));
    }
    if (payload == null) {
      return RespBodyVo.fail("请求体不是合法 JSON 对象");
    }
    String method = payload.getString("method");
    if (method == null || method.isBlank()) {
      return RespBodyVo.fail("缺少参数 method");
    }
    Long id;
    try {
      id = payload.getLong("id");
    } catch (Exception e) {
      return RespBodyVo.fail("id 必须是数字或数字字符串");
    }
    JSONObject params = payload.getJSONObject("params");

    long startedAt = System.currentTimeMillis();
    RespBodyVo result = service().execute(id, method, params);
    attachCapture(result, id, method);
    if (log.isDebugEnabled()) {
      log.debug("id:{} method:{} ok:{} cost:{}ms", id, method, result.isOk(), System.currentTimeMillis() - startedAt);
    }
    return result;
  }

  /**
   * 动作类命令成功后补一张截图,写回 data 里
   *
   * <p>截图失败不影响命令本身的成功与否,失败原因放在 data.screenshot_error 里。
   */
  private void attachCapture(RespBodyVo result, Long id, String method) {
    if (id == null || !result.isOk() || !PAGE_CHANGING.contains(method)) {
      return;
    }
    BrowserInstance inst = playwright().getInstance(id);
    if (inst == null) {
      return;
    }
    Kv capture = playwright().capture(inst);
    Object data = result.getData();
    if (data instanceof Kv) {
      ((Kv) data).set(capture);
      return;
    }
    Kv merged = Kv.by("result", data);
    merged.set(capture);
    result.setData(merged);
  }

  /** Aop 容器在配置阶段可能还没初始化,所以延迟到请求时再取 */
  private static ActionService service() {
    return Aop.get(ActionService.class);
  }

  private static PlaywrightService playwright() {
    return Aop.get(PlaywrightService.class);
  }
}
