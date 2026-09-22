package nexus.io.ai.browser.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.actions.model.ClickElementByIndexParams;
import nexus.io.ai.browser.actions.model.CloseParams;
import nexus.io.ai.browser.actions.model.CloseTabParams;
import nexus.io.ai.browser.actions.model.ExtractStructuredDataParams;
import nexus.io.ai.browser.actions.model.ExecuteJsParams;
import nexus.io.ai.browser.actions.model.GetDropdownOptionsParams;
import nexus.io.ai.browser.actions.model.GoBackParams;
import nexus.io.ai.browser.actions.model.GoToUrlParams;
import nexus.io.ai.browser.actions.model.InputTextParams;
import nexus.io.ai.browser.actions.model.NavigateParams;
import nexus.io.ai.browser.actions.model.ScrollParams;
import nexus.io.ai.browser.actions.model.ScrollToTextParams;
import nexus.io.ai.browser.actions.model.SelectDropdownOptionParams;
import nexus.io.ai.browser.actions.model.SendKeysParams;
import nexus.io.ai.browser.actions.model.SwitchTabParams;
import nexus.io.ai.browser.actions.model.UploadFileParams;
import nexus.io.ai.browser.actions.model.WaitParams;
import nexus.io.ai.browser.actions.registry.CommandHandler;
import nexus.io.ai.browser.actions.registry.CommandTable;
import nexus.io.ai.browser.actions.registry.HandlerRegistry;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

/**
 * 批量指令执行器(PTC:一次请求下发一整个计划)
 *
 * <p>载荷有两种写法,效果一样:
 *
 * <pre>
 * 数组(兼容旧写法): [{"go_to_url":{"url":"https://a.com"}},{"get_dom_text":{}}]
 * 对象(推荐,id 和开关一起带上):
 *   {"id":123,"stopOnError":false,"commands":[{"get_dom_text":{}}]}
 * </pre>
 *
 * <p>每一步的结果都会返回在 data.results 里,所以一个批次可以同时做动作和读取
 * (点击 → get_dom_text → 再点击),模型一次推理就能拿到全部观察结果。
 *
 * <p>命令覆盖除 commands 本身之外的全部接口:老的 17 个走 HandlerRegistry,
 * 其余走 CommandTable。
 */
public class ActionService {

  private final HandlerRegistry registry = new HandlerRegistry();
  private final PlaywrightService svc = Aop.get(PlaywrightService.class);

  /**
   * 老命令(HandlerRegistry 那 17 个)的必填参数
   *
   * <p>它们的 Params 类用的是包装类型,handler 直接传给 service 的 primitive 形参,
   * 缺参数会在拆箱时抛 NPE,所以这里先校验,给出和其它命令一致的「缺少参数 xxx」。
   */
  private static final Map<String, String[]> LEGACY_REQUIRED = Map.ofEntries(
      Map.entry("go_to_url", new String[] { "url" }), Map.entry("navigate", new String[] { "url" }),
      Map.entry("wait", new String[] { "seconds" }),
      Map.entry("click_element_by_index", new String[] { "index" }),
      Map.entry("input_text", new String[] { "index", "text" }),
      Map.entry("upload_file", new String[] { "index", "path" }),
      Map.entry("switch_tab", new String[] { "pageIndex" }), Map.entry("close_tab", new String[] { "pageIndex" }),
      Map.entry("scroll", new String[] { "numPages" }), Map.entry("send_keys", new String[] { "keys" }),
      Map.entry("scroll_to_text", new String[] { "text" }),
      Map.entry("get_dropdown_options", new String[] { "index" }),
      Map.entry("select_dropdown_option", new String[] { "index", "text" }),
      Map.entry("execute_js", new String[] { "body" }));

  /** 老命令里同样会被拆箱的布尔参数,缺失时补 false,和单独调用时 primitive 的默认值一致 */
  private static final Map<String, String[]> LEGACY_BOOL_ARGS = Map.of( //
      "scroll", new String[] { "down" }, //
      "extract_structured_data", new String[] { "extractLinks" });

  private Class<?> resolveParamClass(String cmdName) {
    switch (cmdName) {
    case "go_to_url":
      return GoToUrlParams.class;
    case "navigate":
      return NavigateParams.class;
    case "go_back":
      return GoBackParams.class;
    case "wait":
      return WaitParams.class;
    case "click_element_by_index":
      return ClickElementByIndexParams.class;
    case "input_text":
      return InputTextParams.class;
    case "upload_file":
      return UploadFileParams.class;
    case "switch_tab":
      return SwitchTabParams.class;
    case "close_tab":
      return CloseTabParams.class;
    case "extract_structured_data":
      return ExtractStructuredDataParams.class;
    case "scroll":
      return ScrollParams.class;
    case "send_keys":
      return SendKeysParams.class;
    case "scroll_to_text":
      return ScrollToTextParams.class;
    case "get_dropdown_options":
      return GetDropdownOptionsParams.class;
    case "select_dropdown_option":
      return SelectDropdownOptionParams.class;
    case "execute_js":
      return ExecuteJsParams.class;
    case "close":
      return CloseParams.class;
    default:
      throw new IllegalArgumentException("未知命令：" + cmdName);
    }
  }

  public RespBodyVo batchExecute(Long id, String json) {
    Long browserId = id;
    boolean stopOnError = true;
    JSONArray commands;
    try {
      String text = json == null ? "" : json.trim();
      if (text.isEmpty()) {
        return RespBodyVo.fail("请求体不能为空,需要命令数组或含 commands 的对象");
      }
      if (text.startsWith("[")) {
        commands = JSON.parseArray(text);
      } else {
        JSONObject payload = JSON.parseObject(text);
        if (payload.getLong("id") != null) {
          browserId = payload.getLong("id");
        }
        if (payload.getBoolean("stopOnError") != null) {
          stopOnError = payload.getBoolean("stopOnError");
        }
        commands = payload.getJSONArray("commands");
        if (commands == null) {
          if (payload.containsKey("bodyJson")) {
            // 老写法提示:bodyJson 字段已经不支持了
            return RespBodyVo.fail(
                "不再支持 bodyJson 字段,请把命令直接放在请求体里,例如 {\"id\":1,\"commands\":[{\"get_title\":{}}]}");
          }
          // 宽容处理:只含一个命令键的对象(例如 {"get_title":{}})当成单条命令
          JSONObject single = new JSONObject();
          for (String key : payload.keySet()) {
            if (!"id".equals(key) && !"stopOnError".equals(key)) {
              single.put(key, payload.get(key));
            }
          }
          if (single.size() != 1) {
            return RespBodyVo.fail("对象载荷必须包含 commands 数组,或者只写一条命令");
          }
          commands = new JSONArray();
          commands.add(single);
        }
      }
    } catch (Exception e) {
      return RespBodyVo.fail("请求体解析失败,批量指令需要 JSON：" + PlaywrightService.briefMessage(e.getMessage()));
    }

    if (browserId == null) {
      return RespBodyVo.fail("缺少参数 id,可以放在查询串里,也可以放在对象载荷里");
    }
    if (commands.isEmpty()) {
      return RespBodyVo.fail("命令数组为空");
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
          JSONObject args = entry.getJSONObject(cmdName);
          result = executeOne(browserId, cmdName, args == null ? new JSONObject() : args);
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

  private RespBodyVo executeOne(Long browserId, String cmdName, JSONObject args) {
    if ("commands".equals(cmdName)) {
      return RespBodyVo.fail("批量接口不支持嵌套调用 commands");
    }
    CommandHandler<?> handler = registry.get(cmdName);
    if (handler != null) {
      String[] bools = LEGACY_BOOL_ARGS.get(cmdName);
      if (bools != null) {
        for (String name : bools) {
          if (args.get(name) == null) {
            args.put(name, false);
          }
        }
      }
      String[] required = LEGACY_REQUIRED.get(cmdName);
      if (required != null) {
        for (String name : required) {
          Object value = args.get(name);
          if (value == null || (value instanceof String && ((String) value).isEmpty())) {
            return RespBodyVo.fail("缺少参数 " + name);
          }
        }
      }
      Object params = args.toJavaObject(resolveParamClass(cmdName));
      @SuppressWarnings("unchecked")
      CommandHandler<Object> typed = (CommandHandler<Object>) handler;
      return typed.handle(browserId, params);
    }
    CommandTable.Executor executor = CommandTable.get(cmdName);
    if (executor != null) {
      return executor.run(svc, browserId, args);
    }
    return RespBodyVo.fail("不支持的命令：" + cmdName
        + "(批量接口支持除 commands 之外的全部接口,命令名就是接口路径去掉斜杠)");
  }

}
