package nexus.io.ai.browser.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import nexus.io.model.body.RespBodyVo;

/** Optional wire projection. Never traverses arbitrary website/script payloads. */
public final class ResponseFormatter {
  private ResponseFormatter() { }

  public static RespBodyVo format(RespBodyVo response, String requestBody) {
    JSONObject request;
    try {
      request = JSONObject.parseObject(requestBody);
    } catch (RuntimeException e) {
      return response;
    }
    if (request == null || !"compact".equals(request.getString("responseMode"))) return response;
    response.setData(project(request.getString("method"), response.getData(),
        Boolean.TRUE.equals(request.getBoolean("diagnostics"))));
    return response; // Keep ok/code/error/msg, including nulls, exactly as before.
  }

  static Object project(String method, Object data, boolean diagnostics) {
    if (!(data instanceof Map)) return data;
    Map<?, ?> original = (Map<?, ?>) data;
    Map<String, Object> result = new LinkedHashMap<>();
    original.forEach((key, value) -> result.put(String.valueOf(key), value));
    if ("commands".equals(method) && original.get("results") instanceof Iterable) {
      ArrayList<Object> steps = new ArrayList<>();
      for (Object item : (Iterable<?>) original.get("results")) {
        if (!(item instanceof Map)) { steps.add(item); continue; }
        Map<?, ?> step = (Map<?, ?>) item;
        Map<String, Object> copy = new LinkedHashMap<>();
        step.forEach((key, value) -> copy.put(String.valueOf(key), value));
        copy.put("data", project(String.valueOf(step.get("command")), step.get("data"), diagnostics));
        steps.add(copy);
      }
      result.put("results", steps);
    }
    // Only remove fields owned by the command protocol, never body/result/request payloads.
    result.remove("screenshot_path");
    if ("get_browser_state".equals(method) || "get_page_snapshot".equals(method)) {
      result.remove("browser_state");
      if (result.containsKey("tabs")) { result.remove("url"); result.remove("title"); }
    }
    if (!diagnostics && (method.startsWith("click_") || "double_click_element_by_index".equals(method)
        || "hover_and_click".equals(method))) {
      for (String key : new String[] {"urlBefore", "urlAfter", "tabCountBefore", "tabCountAfter",
          "textLengthBefore", "textLengthAfter", "outerHtml"}) result.remove(key);
    }
    return result;
  }
}
