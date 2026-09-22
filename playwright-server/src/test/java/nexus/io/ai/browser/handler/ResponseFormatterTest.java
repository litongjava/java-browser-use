package nexus.io.ai.browser.handler;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.jfinal.kit.Kv;
import nexus.io.model.body.RespBodyVo;

public class ResponseFormatterTest {
  @Test public void defaultResponseIsUntouchedAndCompactKeepsEnvelope() {
    Kv data = Kv.by("url", "https://example.test/").set("title", "title")
        .set("tabs", List.of(Kv.by("index", 0).set("current", true)))
        .set("browser_state", "duplicate").set("text", "page").set("screenshot_path", "local")
        .set("screenshot", "/data/1/2.png");
    RespBodyVo full = RespBodyVo.ok(data);
    assertSame(data, ResponseFormatter.format(full, "{\"method\":\"get_browser_state\"}").getData());
    String before = com.alibaba.fastjson2.JSON.toJSONString(full);
    RespBodyVo compact = ResponseFormatter.format(full,
        "{\"method\":\"get_browser_state\",\"responseMode\":\"compact\"}");
    assertSame(full, compact);
    assertTrue(compact.isOk());
    Map<?, ?> projected = (Map<?, ?>) compact.getData();
    assertFalse(projected.containsKey("browser_state"));
    assertFalse(projected.containsKey("url"));
    assertEquals("page", projected.get("text"));
    assertEquals("/data/1/2.png", projected.get("screenshot"));
    assertTrue(data.containsKey("url")); // no mutation of nested source data
    var beforeJson = com.alibaba.fastjson2.JSON.parseObject(before);
    var afterJson = com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(compact));
    for (String key : List.of("ok", "code", "error", "msg")) {
      assertEquals(beforeJson.containsKey(key), afterJson.containsKey(key));
      assertEquals(beforeJson.get(key), afterJson.get(key));
    }
  }

  @Test public void batchProjectionNeverStripsUserPayloadKeys() {
    Kv payload = Kv.by("urlBefore", "website data").set("screenshot_path", "business data");
    Kv batch = Kv.by("results", List.of(
        Kv.by("command", "execute_js").set("data", Kv.by("result", payload)),
        Kv.by("command", "click_element_by_index").set("data", Kv.by("changed", false)
            .set("changeStatus", "not_observed").set("urlBefore", "debug").set("outerHtml", "debug"))));
    Map<?, ?> result = (Map<?, ?>) ResponseFormatter.project("commands", batch, false);
    List<?> steps = (List<?>) result.get("results");
    Map<?, ?> script = (Map<?, ?>) ((Map<?, ?>) steps.get(0)).get("data");
    assertSame(payload, script.get("result"));
    Map<?, ?> click = (Map<?, ?>) ((Map<?, ?>) steps.get(1)).get("data");
    assertFalse(click.containsKey("urlBefore"));
    assertEquals("not_observed", click.get("changeStatus"));
    Map<?, ?> diagnostic = (Map<?, ?>) ResponseFormatter.project("click_element_by_index",
        Kv.by("urlBefore", "debug"), true);
    assertEquals("debug", diagnostic.get("urlBefore"));
  }
}
