package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.model.body.RespBodyVo;

/**
 * 本次改造新增/变更的动作命令(全部用本地 fixture,不碰外网)
 *
 * <p>覆盖四件事:
 * <ul>
 * <li><b>点击的降级链</b>:被遮挡的元素不得在 auto 模式下穿透点击，显式 js 模式仍可用;</li>
 * <li><b>输入的模式</b>:看得见就用真实输入(进框架模型),看不见才退回 JS 设值并标明
 * {@code committed=false};</li>
 * <li><b>隐藏 file input 的上传</b>:没有索引的元素用选择器就能传,不再需要先把它显示出来;</li>
 * <li><b>新增的观测能力</b>:{@code wait_for_idle}、{@code get_form_state}、截图默认落盘、
 * {@code get_requests} 空结果自解释。</li>
 * </ul>
 */
public class BrowserActionUpgradeTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;
  private static Path uploadDir;
  private static Path markFile;

  /** fixture 页面:一个表单 + 一个被遮挡的按钮 + 一个慢接口 + 一个隐藏的 file input */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>fixture</title></head><body>
        <form id="form">
          <div class="ant-form-item">
            <label for="name">申请人名称</label>
            <input id="name" value="">
          </div>
          <div class="ant-form-item ant-form-item-has-error">
            <label for="phone">联系人手机</label>
            <input id="phone" value="">
            <div class="ant-form-item-explain-error">联系人手机必填</div>
          </div>
          <input id="secret" type="password" value="p@ssw0rd">
          <input id="hiddenField" type="hidden" value="">
          <input id="fileField" type="file" accept=".jpg" style="display:none">
          <div id="uploaded">none</div>
          <div style="position:relative;width:200px;height:40px">
            <button id="covered" type="button">被遮住的按钮</button>
            <div id="overlay" style="position:absolute;top:0;left:0;right:0;bottom:0;z-index:10"></div>
          </div>
          <button id="slow" type="button">慢接口</button>
          <div id="flag">idle</div>
        </form>
        <script>
          document.getElementById('covered').addEventListener('click', function () {
            document.getElementById('flag').textContent = 'clicked';
          });
          document.getElementById('fileField').addEventListener('change', function (e) {
            document.getElementById('uploaded').textContent =
              (e.target.files && e.target.files[0]) ? e.target.files[0].name : 'none';
          });
          document.getElementById('slow').addEventListener('click', function () { fetch('/slow'); });
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-upgrade-profile");
    uploadDir = Files.createTempDirectory("browser-use-upgrade-upload");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    System.setProperty(UploadStore.KEY_DIR, uploadDir.toString());
    // 超时调小,让「原生点击失败」这一步别把测试拖慢
    System.setProperty(PlaywrightService.KEY_ACTION_TIMEOUT, "800");
    ChromeBrowser.resetForTests();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      byte[] bytes;
      String contentType;
      if ("/slow".equals(path)) {
        try {
          Thread.sleep(600);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        bytes = "{}".getBytes(StandardCharsets.UTF_8);
        contentType = "application/json";
      } else {
        bytes = PAGE.getBytes(StandardCharsets.UTF_8);
        contentType = "text/html; charset=utf-8";
      }
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();

    markFile = uploadDir.resolve("图样.jpg");
    Files.write(markFile, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 });

    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() throws IOException {
    if (id != null) {
      service.close(id);
    }
    if (server != null) {
      server.stop(0);
    }
    for (String key : List.of(ChromeBrowser.KEY_PROFILE_DIR, UploadStore.KEY_DIR,
        PlaywrightService.KEY_ACTION_TIMEOUT)) {
      System.clearProperty(key);
    }
    ChromeBrowser.resetForTests();
    deleteTree(profileDir);
    deleteTree(uploadDir);
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null) {
      return;
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static Kv data(RespBodyVo response) {
    assertTrue(response.getMsg(), response.isOk());
    return (Kv) response.getData();
  }

  private static String flag() {
    return String.valueOf(service.getInstance(id).page.evaluate("() => document.getElementById('flag').textContent"));
  }

  private void open() {
    service.getInstance(id).page.navigate(base);
    service.getInstance(id).page.evaluate("() => { document.getElementById('flag').textContent = 'idle'; }");
  }

  // ==================== 点击的降级链 ====================

  /** 被遮挡的元素不能通过自动降级触发背景提交。 */
  @Test
  public void coveredElementDoesNotSubmitThroughOverlay() {
    open();
    RespBodyVo nativeOnly = service.clickElementBySelector(id, "#covered", "native", 700);
    assertFalse("原生点击被遮挡的元素应当超时失败,实际:" + nativeOnly.getMsg(), nativeOnly.isOk());
    assertEquals("页面不该有任何反应", "idle", flag());

    RespBodyVo auto = service.clickElementBySelector(id, "#covered", "auto", 700);
    assertFalse("auto 不应穿透遮挡层", auto.isOk());
    assertEquals("ELEMENT_OBSCURED", ActionError.code(auto.getMsg()));
    assertEquals("idle", flag());
  }

  @Test
  public void duplicateSubmitTargetsForegroundDialog() {
    open();
    service.getInstance(id).page.evaluate("""
        () => {
          document.body.insertAdjacentHTML('beforeend',
            '<button type="submit" class="duplicate" onclick="window.backgroundClicks++">提交</button>' +
            '<div style="position:fixed;inset:0;background:white;z-index:9999">' +
            '<button type="submit" class="duplicate" onclick="window.dialogClicks++">确认</button></div>');
          window.backgroundClicks = 0;
          window.dialogClicks = 0;
        }
        """);
    Kv result = data(service.clickElementBySelector(id, ".duplicate", "auto", 700));
    assertEquals(1, result.getInt("chosenIndex").intValue());
    assertEquals(0, ((Number) service.getInstance(id).page.evaluate("() => window.backgroundClicks")).intValue());
    assertEquals(1, ((Number) service.getInstance(id).page.evaluate("() => window.dialogClicks")).intValue());
  }

  @Test
  public void releasedObjectFailureDoesNotDispatchAnotherClick() throws Exception {
    open();
    service.getInstance(id).page.evaluate("() => document.getElementById('overlay').remove()");
    Class<?> outcomeType = Class.forName(PlaywrightService.class.getName() + "$ActionOutcome");
    var constructor = outcomeType.getDeclaredConstructor();
    constructor.setAccessible(true);
    var click = PlaywrightService.class.getDeclaredMethod("clickWithMode",
        com.microsoft.playwright.Locator.class, String.class, outcomeType, Runnable.class);
    click.setAccessible(true);
    Runnable uncertainClick = () -> {
      throw new com.microsoft.playwright.PlaywrightException("Object doesn't exist: response@fixture");
    };
    try {
      click.invoke(null, service.getInstance(id).page.locator("#covered"), "auto",
          constructor.newInstance(), uncertainClick);
      org.junit.Assert.fail("Uncertain action must propagate without a second click");
    } catch (java.lang.reflect.InvocationTargetException expected) {
      assertTrue(ActionError.isSpuriousDispatch(expected.getCause().getMessage()));
    }
    assertEquals("idle", flag());
  }

  /** mode=js 时不做可操作性检查,直接派发 */
  @Test
  public void jsModeSkipsActionabilityCheck() {
    open();
    Kv result = data(service.clickElementBySelector(id, "#covered", "js", 700));
    assertEquals("js", result.getStr("mode"));
    assertNull("显式要求 js 时不算降级,不该有 fallbackReason", result.getStr("fallbackReason"));
    assertEquals("clicked", flag());
  }

  /** 关掉降级开关后,原生失败就是失败(不做「悄悄换一种方式」) */
  @Test
  public void fallbackCanBeDisabled() {
    open();
    System.setProperty(PlaywrightService.KEY_JS_FALLBACK, "false");
    ChromeBrowser.resetForTests();
    try {
      RespBodyVo response = service.clickElementBySelector(id, "#covered", "auto", 700);
      assertFalse("关掉降级后应当如实失败", response.isOk());
      assertEquals("idle", flag());
    } finally {
      System.clearProperty(PlaywrightService.KEY_JS_FALLBACK);
      ChromeBrowser.resetForTests();
    }
  }

  /** 按索引点击也要能用 mode(命令表走的就是这条重载) */
  @Test
  public void clickByIndexAcceptsMode() {
    open();
    int index = indexOf("#covered");
    Kv result = data(service.clickElementByIndex(id, index, "js", null));
    assertEquals("js", result.getStr("mode"));
    assertEquals("clicked", flag());
  }

  /**
   * 算一个元素在「可点击元素」索引空间里的位置
   *
   * <p>这里没有先取 get_browser_state(那会建立 DOM 快照,索引就按 xpath 走了),所以服务会退回
   * CSS 选择器索引空间,顺序就是 {@code a, button, input[type=button], input[type=submit]}。
   */
  private int indexOf(String selector) {
    Object index = service.getInstance(id).page.evaluate(
        "(sel) => Array.from(document.querySelectorAll('a, button, input[type=button], input[type=submit]'))"
            + ".findIndex(e => e.matches(sel))",
        selector);
    int position = ((Number) index).intValue();
    assertTrue("fixture 里没有找到 " + selector, position >= 0);
    return position;
  }

  // ==================== 输入的模式 ====================

  @Test
  public void visibleFieldUsesRealInput() {
    open();
    Kv result = data(service.inputTextBySelector(id, "#name", "示例名称", null));
    assertEquals("看得见的字段应当用真实输入(会进框架模型)", "fill", result.getStr("mode"));
    assertEquals(true, result.get("committed"));
    assertEquals("示例名称", service.getInstance(id).page.inputValue("#name"));
  }

  /** 隐藏字段:auto 模式退回 JS 设值,并如实标明 committed=false */
  @Test
  public void hiddenFieldFallsBackToJsValue() {
    open();
    RespBodyVo response = service.inputTextBySelector(id, "#hiddenField", "隐藏值", null);
    Kv result = data(response);
    assertEquals("js", result.getStr("mode"));
    assertEquals("JS 设值不保证进框架模型,回执必须说清楚", false, result.get("committed"));
    assertNotNull("要给出为什么、以及怎么改用真实输入", result.getStr("note"));
    assertEquals("值本身要真的写进去", "隐藏值", service.getInstance(id).page.inputValue("#hiddenField"));
  }

  /** 显式 native 时隐藏字段如实失败(不静默改方式) */
  @Test
  public void nativeModeOnHiddenFieldFails() {
    open();
    RespBodyVo response = service.inputTextBySelector(id, "#hiddenField", "值", "native");
    assertFalse("隐藏元素用真实输入应当失败", response.isOk());
    assertTrue("失败原因要能看出是元素不可见,实际:" + response.getMsg(),
        response.getMsg().contains("ELEMENT_HIDDEN"));
  }

  // ==================== 隐藏 file input 的上传 ====================

  @Test
  public void uploadBySelectorWorksOnHiddenFileInput() {
    open();
    Kv result = data(service.uploadFile(id, null, "#fileField", markFile.toString(), null));
    assertEquals("图样.jpg", result.getStr("filename"));
    assertEquals("selector=#fileField", result.getStr("target"));
    assertTrue("要报出文件大小", ((Number) result.get("size")).longValue() > 0);
    assertEquals("页面上的 change 监听器要收到这个文件", "图样.jpg",
        service.getInstance(id).page.textContent("#uploaded"));
  }

  /** 相对路径按服务端 upload 目录解析:客户端只要把 /playwright/upload 返回的 relativePath 原样回填 */
  @Test
  public void uploadAcceptsRelativePathFromUploadDir() {
    open();
    Kv result = data(service.uploadFile(id, null, "#fileField", "图样.jpg", null));
    assertEquals(markFile.toAbsolutePath().normalize().toString(), result.getStr("path"));
  }

  @Test
  public void uploadWithoutIndexOrSelectorIsRejected() {
    RespBodyVo response = service.uploadFile(id, null, null, markFile.toString(), null);
    assertFalse(response.isOk());
    assertTrue("要说清 index 与 selector 二选一,实际:" + response.getMsg(),
        response.getMsg().contains("index") && response.getMsg().contains("selector"));
  }

  @Test
  public void uploadMissingFilePointsAtTheUploadEndpoint() {
    RespBodyVo response = service.uploadFile(id, null, "#fileField", "not-there.jpg", null);
    assertFalse(response.isOk());
    assertTrue("客户端-服务器模式下要说清怎么把文件送上来,实际:" + response.getMsg(),
        response.getMsg().contains("/playwright/upload"));
  }

  // ==================== wait_for_idle ====================

  @Test
  public void waitForIdleWaitsForInFlightRequest() {
    open();
    service.getInstance(id).page.evaluate("() => document.getElementById('slow').click()");
    Kv result = data(service.waitForIdle(id, 200, 5.0, null, null));
    assertEquals(true, result.get("idle"));
    assertTrue("要等过那个 600ms 的接口,实际等了 " + result.get("waitedMs"),
        ((Number) result.get("waitedMs")).longValue() >= 500);
    assertEquals("结束时不该还有在途请求", 0, ((Number) result.get("inflight")).intValue());
  }

  /** 页面一直在变(定时器改 DOM)时如实超时,并给出当时的计数,便于判断卡在哪 */
  @Test
  public void waitForIdleReportsTimeoutWithCounts() {
    open();
    service.getInstance(id).page.evaluate(
        "() => { window.__noisy = setInterval(() => { document.getElementById('flag').textContent = 'x' + Date.now(); }, 50); }");
    try {
      RespBodyVo response = service.waitForIdle(id, 400, 1.5, null, null);
      assertFalse("一直有 DOM 变更时不该报成功", response.isOk());
      assertTrue("失败原因要提到没有安静下来,实际:" + response.getMsg(),
          response.getMsg().contains("安静"));
      Kv result = (Kv) response.getData();
      assertNotNull("要带上当时的计数", result);
      assertTrue("DOM 变更计数应当大于 0", ((Number) result.get("mutations")).intValue() > 0);
    } finally {
      service.getInstance(id).page.evaluate("() => clearInterval(window.__noisy)");
    }
  }

  // ==================== get_form_state ====================

  @Test
  public void formStateReportsValuesErrorsAndRedactsPasswords() {
    open();
    Kv result = data(service.getFormState(id, "#form", false, 100));
    // fixture 里一共有 5 个控件:name / phone / secret 可见,hiddenField(type=hidden)与
    // fileField(display:none)默认不算 —— 后两个正是「填表时最容易漏掉」的那类字段
    assertEquals("默认只返回可见控件", 3, ((Number) result.get("count")).intValue());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
    Map<String, Object> name = fields.stream().filter(f -> "name".equals(f.get("id"))).findFirst().orElseThrow();
    assertEquals("要能读出标签", "申请人名称", name.get("label"));
    assertNotNull("要能读出控件类型", name.get("type"));

    Map<String, Object> secret = fields.stream().filter(f -> "secret".equals(f.get("id"))).findFirst().orElseThrow();
    assertEquals("密码字段的值一律不回传", "[redacted]", secret.get("value"));

    assertFalse("隐藏控件默认不返回",
        fields.stream().anyMatch(f -> "hiddenField".equals(f.get("id"))));
    assertFalse("display:none 的 file input 同样默认不返回",
        fields.stream().anyMatch(f -> "fileField".equals(f.get("id"))));

    assertEquals("要报出校验错误条数", 1, ((Number) result.get("errorCount")).intValue());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
    assertEquals("联系人手机必填", errors.get(0).get("error"));
    assertEquals("联系人手机", errors.get(0).get("label"));
  }

  @Test
  public void formStateCanIncludeHiddenFields() {
    open();
    Kv result = data(service.getFormState(id, "#form", true, 100));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
    assertTrue("includeHidden=true 时应当包含隐藏控件",
        fields.stream().anyMatch(f -> "hiddenField".equals(f.get("id"))));
  }

  // ==================== 截图默认落盘 ====================

  @Test
  public void screenshotDefaultsToFileWithoutBase64() throws IOException {
    open();
    RespBodyVo response = actions.execute(id, "screenshot", new com.alibaba.fastjson2.JSONObject());
    Kv result = data(response);
    assertNotNull("默认要落盘", result.getStr("path"));
    assertTrue("要给出可直接 GET 的 URL,实际:" + result.getStr("url"),
        String.valueOf(result.getStr("url")).startsWith("/data/"));
    assertEquals("默认不回 base64", true, result.get("base64Omitted"));
    assertFalse("响应里不该有 base64", result.containsKey("base64"));
    assertTrue("文件要真的存在", Files.exists(Path.of(result.getStr("path"))));
  }

  @Test
  public void screenshotInlinesOnlyWhenAsked() {
    open();
    Kv result = data(actions.execute(id, "screenshot",
        com.alibaba.fastjson2.JSONObject.parseObject("{\"inline\":true}")));
    assertNotNull("显式要求内联时才回 base64", result.getStr("base64"));
    assertEquals(true, result.get("inline"));
  }

  // ==================== get_requests 的自解释 ====================

  @Test
  public void getRequestsExplainsEmptyResult() {
    open();
    Kv result = data(service.getRequests(id, "/definitely-not-there", null, null, null));
    assertEquals(0, ((Number) result.get("count")).intValue());
    assertNotNull("空结果要说明为什么可能是空的", result.getStr("note"));
    assertTrue("要带上「从什么时候开始记」", ((Number) result.get("recordedSince")).longValue() > 0);
  }

  @Test
  public void getRequestsFiltersByUrlAndResourceType() {
    open();
    service.getInstance(id).page.evaluate("async () => { await fetch('/slow'); }");
    Kv all = data(service.getRequests(id, null, null, null, null));
    assertTrue("至少要记到 /slow 这一次请求,实际 " + all.get("total"),
        ((Number) all.get("total")).intValue() >= 1);

    Kv filtered = data(service.getRequests(id, "/slow", "fetch", null, null));
    assertTrue("按 URL + 类型过滤要能命中", ((Number) filtered.get("count")).intValue() >= 1);

    Kv limited = data(service.getRequests(id, null, null, 1, null));
    assertEquals("limit 生效", 1, ((Number) limited.get("count")).intValue());
  }

  // ==================== 方法名建议 ====================

  @Test
  public void unknownMethodSuggestsNearMiss() {
    RespBodyVo response = actions.execute(id, "list_tabs", new com.alibaba.fastjson2.JSONObject());
    assertFalse(response.isOk());
    assertTrue("要给出近似建议,实际:" + response.getMsg(), response.getMsg().contains("get_tabs"));
  }

  private static com.alibaba.fastjson2.JSONObject json(String key, Object value) {
    com.alibaba.fastjson2.JSONObject object = new com.alibaba.fastjson2.JSONObject();
    object.put(key, value);
    return object;
  }
}
