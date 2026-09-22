package nexus.io.ai.browser.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.actions.registry.CommandTable;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

/**
 * 方法分发与批量指令执行器
 *
 * <p>
 * 对外只有一个端点 {@code POST /playwright/command},请求体是
 * {@code {"id":123,"method":"get_browser_state","params":{}}}。{@link #execute}
 * 负责把 {@code method} 分发到 {@link CommandTable},批量调用走 {@code method=commands}:
 *
 * <pre>
 * {"id":123,"method":"commands","params":{
 *    "stopOnError":false,
 *    "commands":[{"click_element_by_index":{"index":12}},{"get_browser_state":{}}]}}
 * </pre>
 *
 * <p>
 * 批量里每一步的结果都在 data.results 里,所以一个批次可以同时做动作和读取(点击 → get_browser_state →
 * 再点击),模型一次推理就能拿到全部观察结果。
 */
public class ActionService {

  public static final Set<String> PAGE_CHANGING = Set.of(
      // 导航
      "navigate", "go_to_url", "go_back", "go_forward", "reload",
      // 点击与交互
      "click_element_by_index", "double_click_element_by_index", "click_element_by_selector", "click_element_by_text",
      "click_element_by_role", "hover_and_click", "hover_element_by_index", "focus_element_by_index",
      "check_element_by_index", "uncheck_element_by_index", "input_text", "input_text_by_selector",
      "input_text_by_label", "type_text", "clear_text", "send_keys", "key_down", "key_up", "select_dropdown_option",
      "upload_file", "drag_element_by_index",
      // 滚动与鼠标
      "scroll", "scroll_to_text", "mouse_move", "mouse_down", "mouse_up", "mouse_wheel",
      // 页签
      "new_tab", "switch_tab", "switch_tab_by_url", "close_tab", "close_other_tabs", "bring_to_front",
      // 等待(等到了页面往往就变了)
      "wait", "wait_for_element", "wait_for_text", "wait_for_url", "wait_for_load", "wait_for_function",
      // 脚本与设置
      "execute_js", "set_viewport", "set_media", "set_credentials");

  /** 命令数组里最多允许多少条,挡住一次请求塞进上万个动作 */
  private static final int MAX_COMMANDS = 200;

  private final PlaywrightService svc;

  public ActionService() {
    this(Aop.get(PlaywrightService.class));
  }

  public ActionService(PlaywrightService svc) {
    this.svc = svc;
  }

  private void attachCapture(RespBodyVo result, Long id, String method) {
    if (id == null || !result.isOk() || !PAGE_CHANGING.contains(method))
      return;
    BrowserInstance inst = svc.getInstance(id);
    if (inst == null)
      return;
    Kv capture = svc.capture(inst);
    Object data = result.getData();
    Kv merged = data instanceof Kv ? (Kv) data : Kv.by("result", data);
    merged.set(capture);
    result.setData(merged);
  }

  /**
   * 执行一条命令
   *
   * @param id     任务 ID,start 时可以为空
   * @param method 命令名,见 {@link CommandTable}
   * @param params 命令参数,可以为空
   */
  public RespBodyVo execute(Long id, String method, JSONObject params) {
    if (method == null || method.isBlank()) {
      return RespBodyVo.fail("缺少参数 method");
    }
    if ("commands".equals(method)) {
      return batchExecute(id, params);
    }
    CommandTable.Executor executor = CommandTable.get(method);
    if (executor == null) {
      return RespBodyVo.fail("不支持的方法：" + method);
    }
    try {
      RespBodyVo result = executor.run(svc, id, params == null ? new JSONObject() : params);
      if (!result.isOk() && result.getMsg() != null) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\[([A-Z_]+)\\]").matcher(result.getMsg());
        String errorCode = match.find() ? match.group(1) : ActionError.code(result.getMsg());
        Kv detail = result.getData() instanceof Kv ? (Kv) result.getData() : new Kv();
        result.setData(detail.set("errorCode", errorCode));
      }
      attachCapture(result, id, method);
      return result;
    } catch (Exception e) {
      return RespBodyVo.fail(method + " 失败：" + PlaywrightService.briefMessage(e.getMessage()));
    }
  }

  /**
   * 批量指令:params 里是 {@code stopOnError} 与 {@code commands}
   *
   * <p>
   * {@code commands} 每项只能有一个键,键是命令名、值是参数对象:
   * {@code [{"click_element_by_index":{"index":12}},{"get_browser_state":{}}]}。
   */
  public RespBodyVo batchExecute(Long id, JSONObject params) {
    Long browserId = id;
    boolean stopOnError = true;
    if (params == null) {
      return RespBodyVo.fail("commands 需要 params.commands 命令数组");
    }
    if (params.getBoolean("stopOnError") != null) {
      stopOnError = params.getBoolean("stopOnError");
    }
    JSONArray commands = params.getJSONArray("commands");
    if (commands == null && params.size() == 1 && !params.containsKey("stopOnError")) {
      // 宽容处理:只写一条命令时,params 本身就可以是那一条
      commands = new JSONArray();
      commands.add(params);
    }
    if (commands == null) {
      return RespBodyVo.fail("commands 需要 params.commands 命令数组");
    }
    if (commands.isEmpty()) {
      return RespBodyVo.fail("命令数组为空");
    }
    if (commands.size() > MAX_COMMANDS) {
      return RespBodyVo.fail("命令数组最多 " + MAX_COMMANDS + " 条,当前 " + commands.size() + " 条");
    }
    if (browserId == null) {
      return RespBodyVo.fail("缺少参数 id");
    }

    List<Object> results = new ArrayList<>(commands.size());
    int succeeded = 0;
    int failed = 0;
    String firstFailure = null;

    for (int i = 0; i < commands.size(); i++) {
      String cmdName = "?";
      RespBodyVo result;
      try {
        JSONObject entry = commands.getJSONObject(i);
        if (entry == null || entry.size() != 1) {
          result = RespBodyVo.fail("每条命令对象只能包含一个键");
        } else {
          cmdName = entry.keySet().iterator().next();
          if ("commands".equals(cmdName)) {
            result = RespBodyVo.fail("批量接口不支持嵌套调用 commands");
          } else {
            JSONObject args = entry.getJSONObject(cmdName);
            result = execute(browserId, cmdName, args == null ? new JSONObject() : args);
          }
        }
      } catch (Exception e) {
        result = RespBodyVo.fail(PlaywrightService.briefMessage(e.getMessage()));
      }

      results.add(Kv.by("index", i).set("command", cmdName).set("ok", result.isOk()).set("data", result.getData())
          .set("msg", result.getMsg()));

      if (result.isOk()) {
        succeeded++;
      } else {
        failed++;
        if (firstFailure == null) {
          firstFailure = "第 " + i + " 条命令 " + cmdName + " 失败：" + result.getMsg();
        }
        if (stopOnError) {
          break;
        }
      }
    }

    Kv data = Kv.by("count", results.size()).set("succeeded", succeeded).set("failed", failed)
        .set("stopped", stopOnError && failed > 0).set("results", results);
    if (failed == 0) {
      return RespBodyVo.ok(data);
    }
    RespBodyVo resp = RespBodyVo.fail(firstFailure);
    resp.setData(data);
    return resp;
  }

  /** 解析 JSON 请求体,失败时给出中文原因 */
  public static JSONObject parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    return JSON.parseObject(body);
  }
}
