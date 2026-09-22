package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import nexus.io.model.body.RespBodyVo;

/** Local browser fixtures only: no tax site, saved user profile or external website. */
public class BrowserResponseIntegrationTest {
  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static HttpServer server;
  private static String base;

  @BeforeClass public static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      boolean api = exchange.getRequestURI().getPath().equals("/query");
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String output = api ? "{\"query\":" + JSONObject.toJSONString(body) + ",\"total\":0,\"list\":[]}"
          : "<html><body><input id='field'><button id='noop'>No change</button></body></html>";
      byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", api ? "application/json" : "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  @AfterClass public static void stop() {
    if (id != null) service.close(id);
    if (server != null) server.stop(0);
  }

  private Page page() { return service.getInstance(id).page; }
  private Kv data(RespBodyVo response) {
    assertTrue(response.getMsg(), response.isOk());
    return (Kv) response.getData();
  }

  @Test public void currentFormStateAndReadonlyErrors() {
    page().setContent("""
        <div><div><div><form>
        <input id='live' name='identifier-longer-than-fifteen-characters' value='default'>
        <input id='date' readonly value='2026-08-01'>
        <input id='disabled' disabled value='disabled-value'>
        <input id='pw' type='password' value='DO-NOT-LEAK'>
        <input id='check' type='checkbox' checked>
        <select id='choice'><option value='a'>Alpha</option><option value='b'>Beta</option></select>
        </form></div></div></div>
        """);
    page().evaluate("() => {document.querySelector('#live').value='current-value-longer-than-fifteen';"
        + "document.querySelector('#check').checked=false; document.querySelector('#choice').value='b';}");
    String text = data(service.getBrowserState(id, false, 0)).getStr("text");
    assertTrue(text, text.contains("value='current-value-longer-than-fifteen'"));
    assertTrue(text, text.contains("name='identifier-longer-than-fifteen-characters'"));
    assertTrue(text, text.contains("readonly='true'"));
    assertTrue(text, text.contains("disabled='true'"));
    assertTrue(text, text.contains("checked='false'"));
    assertTrue(text, text.contains("selected-text='Beta'"));
    assertFalse(text, text.contains("DO-NOT-LEAK"));
    assertFalse(text, text.contains("\t\t\t\t\t\t\t"));
    RespBodyVo readonly = actions.execute(id, "input_text_by_selector",
        JSONObject.parseObject("{\"selector\":\"#date\",\"text\":\"2026-09-01\"}"));
    assertFalse(readonly.isOk());
    assertEquals("ELEMENT_READ_ONLY", ((Kv) readonly.getData()).getStr("errorCode"));
    RespBodyVo disabled = actions.execute(id, "input_text_by_selector",
        JSONObject.parseObject("{\"selector\":\"#disabled\",\"text\":\"x\"}"));
    assertEquals("ELEMENT_DISABLED", ((Kv) disabled.getData()).getStr("errorCode"));
  }

  @Test public void equalLengthAsyncAndFormOnlyChangesAreObserved() {
    page().setContent("""
        <span id='status'>AAAA</span><input id='value' value='one'>
        <button id='async' onclick="setTimeout(()=>document.querySelector('#status').textContent='BBBB',100)">Run</button>
        <button id='form' onclick="document.querySelector('#value').value='two'">Form</button>
        <button id='noop'>Nothing</button>
        """);
    Kv async = data(service.clickElementByRole(id, "button", "Run"));
    assertEquals(true, async.get("changed"));
    assertEquals(async.get("textLengthBefore"), async.get("textLengthAfter"));
    assertEquals(true, data(service.clickElementByRole(id, "button", "Form")).get("changed"));
    Kv noop = data(service.clickElementByRole(id, "button", "Nothing"));
    assertEquals(false, noop.get("changed"));
    assertEquals("not_observed", noop.get("changeStatus"));
    assertTrue(noop.getStr("hint").contains("不代表点击失败"));
  }

  @Test public void batchAndSingleActionsCaptureExactlyOnce() {
    int before = service.getInstance(id).captureSeq.get();
    JSONObject args = new JSONObject();
    args.put("commands", List.of(Map.of("go_to_url", Map.of("url", base)),
        Map.of("input_text_by_selector", Map.of("selector", "#field", "text", "updated")),
        Map.of("get_browser_state", Map.of("highlight", false))));
    Kv batch = data(actions.execute(id, "commands", args));
    List<?> results = (List<?>) batch.get("results");
    for (int i = 0; i < results.size(); i++) {
      Kv step = (Kv) ((Kv) results.get(i)).get("data");
      assertEquals(before + i + 1, step.getInt("seq").intValue());
      assertTrue(Files.exists(Paths.get(step.getStr("screenshot_path"))));
    }
    Kv single = data(actions.execute(id, "input_text_by_selector",
        JSONObject.parseObject("{\"selector\":\"#field\",\"text\":\"single\"}")));
    assertEquals(before + 4, single.getInt("seq").intValue());
  }

  @Test public void failedBatchStepDoesNotCaptureOrLoseRemainingResults() {
    page().setContent("<input id='readonly' readonly><input id='enabled'>");
    int before = service.getInstance(id).captureSeq.get();
    JSONObject args = JSONObject.parseObject("""
        {"stopOnError":false,"commands":[
          {"input_text_by_selector":{"selector":"#readonly","text":"blocked"}},
          {"input_text_by_selector":{"selector":"#enabled","text":"works"}},
          {"get_browser_state":{"highlight":false}}]}
        """);
    RespBodyVo result = actions.execute(id, "commands", args);
    assertFalse(result.isOk());
    Kv batch = (Kv) result.getData();
    assertEquals(1, batch.getInt("failed").intValue());
    assertEquals(2, batch.getInt("succeeded").intValue());
    List<?> steps = (List<?>) batch.get("results");
    Kv error = (Kv) ((Kv) steps.get(0)).get("data");
    assertEquals("ELEMENT_READ_ONLY", error.getStr("errorCode"));
    assertFalse(error.containsKey("seq"));
    assertEquals(before + 2, service.getInstance(id).captureSeq.get());
    assertTrue(((Kv) ((Kv) steps.get(2)).get("data")).getStr("text").contains("value='works'"));
  }

  @Test public void classOnlyChangeAndDelayedPopupAreObserved() {
    page().setContent("""
        <div id='panel' class='initial'></div>
        <button onclick="document.querySelector('#panel').className='updated'">Style</button>
        <button onclick="setTimeout(()=>window.open('about:blank'),100)">Popup</button>
        """);
    assertEquals(true, data(service.clickElementByRole(id, "button", "Style")).get("changed"));
    Kv popup = data(service.clickElementByRole(id, "button", "Popup"));
    assertEquals(true, popup.get("changed"));
    assertEquals(popup.getInt("tabCountBefore") + 1, popup.getInt("tabCountAfter").intValue());
    Page original = page();
    for (Page other : service.getInstance(id).context.pages()) if (other != original) other.close();
  }

  @Test public void repeatedUrlResponsesAreCorrelatedAndTruncationIsExplicit() {
    page().navigate(base);
    page().evaluate("async () => {for(const month of ['2026-07','2026-08'])"
        + "await fetch('/query',{method:'POST',body:JSON.stringify({month})}).then(r=>r.text());}");
    List<?> requests = (List<?>) data(service.getRequests(id, "/query")).get("requests");
    Kv first = (Kv) requests.get(requests.size() - 2);
    Kv second = (Kv) requests.get(requests.size() - 1);
    assertNotEquals(first.get("requestId"), second.get("requestId"));
    Kv response = data(service.getResponseBody(id, "/query", null, 1000, first.getStr("requestId")));
    assertEquals(first.get("requestId"), response.get("requestId"));
    assertTrue(response.getStr("body").contains("2026-07"));
    assertEquals(first.get("postData"), ((Kv) response.get("request")).get("postData"));
    assertEquals(false, response.get("truncated"));
    Kv limited = data(service.getResponseBody(id, "/query", null, 5, second.getStr("requestId")));
    assertEquals(true, limited.get("truncated"));
    assertTrue(limited.getInt("bodyLength") > 5);
    Kv lookback = data(service.waitForResponse(id, "/query", 2.0, 1000, 10));
    assertEquals(second.get("requestId"), lookback.get("requestId"));
    assertEquals(true, lookback.get("fromLookBack"));
    assertTrue(lookback.containsKey("respondedAt"));
  }
}
