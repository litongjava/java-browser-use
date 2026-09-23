package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private static Path testProfileDir;

  @BeforeClass public static void start() throws Exception {
    // 测试必须用一份临时 profile:默认那份托管 profile(~/.config/browseruse/profiles/shared)是开发机
    // 上真正在用的登录态,跑测试不该往里写东西。浏览器本身仍然优先用本机安装的 Chrome。
    testProfileDir = Files.createTempDirectory("browser-use-test-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, testProfileDir.toString());
    ChromeBrowser.resetForTests();

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
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
    if (testProfileDir != null) {
      try (java.util.stream.Stream<Path> paths = Files.walk(testProfileDir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      } catch (Exception e) {
        // 临时目录删不掉不影响测试结果
      }
    }
  }

  private Page page() { return service.getInstance(id).page; }
  private Kv data(RespBodyVo response) {
    assertTrue(response.getMsg(), response.isOk());
    return (Kv) response.getData();
  }

  /**
   * 所有任务共用同一个浏览器与 profile,但页签各自独立
   *
   * <p>这是这次改动的核心:不再一个任务一份 profile(用户 profile 天生只能有一个 Chrome 进程),
   * 隔离改由页签承担。关掉一个任务不能影响另一个任务的页签。
   */
  @Test public void tasksShareOneBrowserButOwnTheirTabs() {
    Long second = service.start(null, true);
    try {
      BrowserInstance first = service.getInstance(id);
      BrowserInstance other = service.getInstance(second);
      assertSame("所有任务共用同一个浏览器上下文", first.context, other.context);
      assertEquals("所有任务共用同一个 profile", first.profileDir, other.profileDir);
      assertNotSame("页签不共用", first.page, other.page);

      // start 返回的 browser 信息要如实说明这次用的浏览器与 profile
      Kv info = service.browserInfo(id);
      assertEquals("本机装了 Chrome 就该用它", true, info.get("chrome"));
      assertEquals("默认不碰用户自己的 profile", false, info.get("userProfile"));
      assertEquals("默认走托管 profile(Playwright 持久化上下文)", "managed", info.getStr("mode"));
      assertEquals("托管 profile 目录要听配置的", testProfileDir.toAbsolutePath().toString(), info.getStr("profileDir"));

      int firstTabsBefore = ((List<?>) data(service.getBrowserState(id, false, 0)).get("tabs")).size();
      assertEquals("新任务的页签列表里只有自己的页签", 1,
          ((List<?>) data(service.getBrowserState(second, false, 0)).get("tabs")).size());

      assertTrue(service.close(second).isOk());
      assertNull("关掉的任务要从实例表里移除", service.getInstance(second));
      assertEquals("关掉一个任务不影响另一个任务的页签", firstTabsBefore,
          ((List<?>) data(service.getBrowserState(id, false, 0)).get("tabs")).size());
    } finally {
      if (service.getInstance(second) != null) service.close(second);
    }
  }

  /**
   * 走命令表调 {@code start}:返回里必须带 {@code data.browser}
   *
   * <p>调用方(智能体)靠它判断这次到底用没用上本机 Chrome 与用户的登录态,所以这是对外的契约,
   * 不能只在服务内部有这个信息。
   */
  @Test public void startCommandReportsWhichBrowserItUsed() {
    RespBodyVo response = actions.execute(null, "start", JSONObject.parseObject("{\"headless\":true}"));
    Kv data = data(response);
    Long started = ((Number) data.get("id")).longValue();
    try {
      Kv browser = (Kv) data.get("browser");
      assertNotNull("start 的返回里应当有 data.browser", browser);
      assertEquals("本机装了 Chrome 就该用它", true, browser.get("chrome"));
      assertEquals("默认不碰用户自己的 profile", false, browser.get("userProfile"));
      assertEquals("默认走托管 profile", "managed", browser.getStr("mode"));
      assertEquals(testProfileDir.toAbsolutePath().toString(), browser.getStr("profileDir"));
      assertEquals("应当能看出用的是哪个可执行文件", true, browser.getStr("executable") != null);
    } finally {
      service.close(started);
    }
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

  /**
   * {@code start} 的 {@code browser} 参数:返回里如实报类型,不认识的值直接拒掉
   *
   * <p>
   * 「不能中途换浏览器」这条是刻意的:浏览器与 profile 全进程共用,任务还在跑就换会连累别人的页签,
   * 所以宁可明确失败,也不悄悄重建。
   */
  @Test public void startRejectsUnknownBrowserAndSwitchingWhileRunning() {
    // 1) 返回里要能看出这次用的是哪个浏览器(auto 已经落成确定值)
    Kv info = service.browserInfo(id);
    String current = info.getStr("type");
    assertNotNull("start 的返回里应当有 data.browser.type", current);
    assertNotEquals("auto 不该出现在返回里,应当落成确定的类型", "auto", current);
    assertTrue("类型必须是文档里的取值之一:" + current, BrowserChoice.CHOICES.contains(current));

    // 2) 不认识的值:失败信息里要带可选值,免得调用方猜
    RespBodyVo unknown = actions.execute(null, "start",
        JSONObject.parseObject("{\"headless\":true,\"browser\":\"safari\"}"));
    assertFalse("不认识浏览器类型时 start 应当失败", unknown.isOk());
    assertTrue("失败原因要列出可选值,实际:" + unknown.getMsg(), unknown.getMsg().contains("chrome"));
    assertTrue("失败原因要提到 edge,实际:" + unknown.getMsg(), unknown.getMsg().contains("edge"));
    Object failedData = unknown.getData();
    assertTrue("失败时不该返回新建的实例 id",
        failedData == null || !((Kv) failedData).containsKey("id"));

    // 3) 任务还在跑的时候换浏览器类型:明确失败,不重建
    String other = "chrome".equals(current) ? "edge" : "chrome";
    RespBodyVo switched = actions.execute(null, "start",
        JSONObject.parseObject("{\"headless\":true,\"browser\":\"" + other + "\"}"));
    assertFalse("任务运行中换浏览器类型应当失败", switched.isOk());
    assertTrue("失败原因要说清是共用浏览器导致的,实际:" + switched.getMsg(),
        switched.getMsg().contains("不能中途切换"));
    assertTrue("失败原因要带上当前正在用的浏览器,实际:" + switched.getMsg(),
        switched.getMsg().contains(current));
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
