package nexus.io.ai.browser.service;

import java.util.ArrayList;
import java.util.List;

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
 * <p>对外只有一个端点 {@code POST /playwright/command},请求体是
 * {@code {"id":123,"method":"get_browser_state","params":{}}}。{@link #execute} 负责把
 * {@code method} 分发到 {@link CommandTable},批量调用走 {@code method=commands}:
 *
 * <pre>
 * {"id":123,"method":"commands","params":{
 *    "stopOnError":false,
 *    "commands":[{"click_element_by_index":{"index":12}},{"get_browser_state":{}}]}}
 * </pre>
 *
 * <p>批量里每一步的结果都在 data.results 里,所以一个批次可以同时做动作和读取(点击 →
 * get_browser_state → 再点击),模型一次推理就能拿到全部观察结果。
 */
public class ActionService {

  /** 命令数组里最多允许多少条,挡住一次请求塞进上万个动作 */
  private static final int MAX_COMMANDS = 200;

  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

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
      return executor.run(svc, id, params == null ? new JSONObject() : params);
    } catch (Exception e) {
      return RespBodyVo.fail(method + " 失败：" + PlaywrightService.briefMessage(e.getMessage()));
    }
  }

  /**
   * 批量指令:params 里是 {@code stopOnError} 与 {@code commands}
   *
   * <p>{@code commands} 每项只能有一个键,键是命令名、值是参数对象:
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

      results.add(Kv.by("index", i).set("command", cmdName).set("ok", result.isOk())
          .set("data", result.getData()).set("msg", result.getMsg()));

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
