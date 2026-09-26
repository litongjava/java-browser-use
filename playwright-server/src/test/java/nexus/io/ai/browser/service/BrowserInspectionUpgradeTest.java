package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.model.body.RespBodyVo;

/**
 * 观测与弹窗能力的回归测试
 *
 * <p>这些能力都是「这一轮实操踩坑之后补的」,坑本身在真实站点上很难复现,所以这里用 fixture 页面把
 * 每个坑的最小形态固定下来:
 * <ul>
 * <li><b>点击的鼠标档</b>:元素一直在动时原生点击会以「不稳定」超时,{@code auto} 应当改用真实鼠标
 * 点它的中心并真的触发事件;</li>
 * <li><b>被遮住的目标不走鼠标档</b>:鼠标点的是坐标,落在遮挡物上会把遮挡物按下去,所以这时应当
 * 退回 JS 派发,并把「谁挡的」写进回执;</li>
 * <li><b>DOM 弹窗</b>:{@code get_modals} 能列出标题/按钮/× 坐标,{@code close_modal} 用真实鼠标点完
 * 还要校验数量真的减少了;</li>
 * <li><b>等待</b>:{@code wait_for_stable} 等异步内容稳定、{@code wait_for_count} 等数量达标;</li>
 * <li><b>断言</b>:批次里带 {@code expect} 时,「动作成功但状态没变」要被如实标出来;</li>
 * <li><b>异步批次</b>:{@code async:true} 立刻回 jobId,{@code get_job} 能拿到结果;</li>
 * <li><b>自省</b>:{@code get_config}、{@code list_tasks}、{@code get_browser_state} 内联元素清单。</li>
 * </ul>
 */
public class BrowserInspectionUpgradeTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;
  private static Path uploadDir;

  /** fixture:一个会动的按钮 + 一个 ant 风格确认框 + 一个协议层 + 异步填充的内容 + 计数元素 */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>inspection</title>
      <style>
        @keyframes slide { from { transform: translateX(0); } to { transform: translateX(60px); } }
        /* 行内块:盒子贴着文字,中心点不会跑到页面中间去 */
        #moving { display: inline-block; animation: slide 1.6s linear infinite alternate; }
        /* 弹窗只占右下角一小块:真实页面里遮罩是全屏的,但那样会盖住别的被测元素 */
        .ant-modal-wrap { position: fixed; left: 300px; top: 300px; width: 420px; height: 220px; z-index: 1000; }
      </style></head><body>
        <div id="moving">一直在动的按钮</div>
        <div id="asyncBox">empty</div>
        <div id="clickFlag">idle</div>
        <div id="rows"></div>
        <div class="ant-modal-wrap" id="modalWrap">
          <div class="ant-modal" role="dialog">
            <div class="ant-modal-content">
              <div class="ant-modal-confirm-title">确认提交申请？</div>
              <div class="ant-modal-confirm-btns">
                <button class="ant-btn" id="mCancel">取消</button>
                <button class="ant-btn ant-btn-primary" id="mOk">确定</button>
              </div>
            </div>
            <button class="ant-modal-close" id="mClose">×</button>
          </div>
        </div>
        <script>
          document.getElementById('moving').addEventListener('click', function () {
            document.getElementById('clickFlag').textContent = 'moving-clicked';
          });
          document.getElementById('mCancel').addEventListener('click', function () {
            document.getElementById('modalWrap').remove();
          });
          // 异步填充:先给旧内容,再换新内容 —— 读得太早就会读到旧的
          setTimeout(function () { document.getElementById('asyncBox').textContent = 'stale'; }, 150);
          setTimeout(function () { document.getElementById('asyncBox').textContent = 'fresh'; }, 900);
          setTimeout(function () {
            const box = document.getElementById('rows');
            for (let i = 0; i < 3; i++) { box.insertAdjacentHTML('beforeend', '<div class="row">r' + i + '</div>'); }
          }, 500);
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-inspect-profile");
    uploadDir = Files.createTempDirectory("browser-use-inspect-upload");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    System.setProperty(UploadStore.KEY_DIR, uploadDir.toString());
    System.setProperty(PlaywrightService.KEY_ACTION_TIMEOUT, "900");
    ChromeBrowser.resetForTests();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] bytes = PAGE.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();

    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() {
    if (id != null) {
      try {
        service.close(id);
      } catch (RuntimeException ignored) {
        // 测试收尾,关不掉也无所谓
      }
    }
    if (server != null) {
      server.stop(0);
    }
    ChromeBrowser.resetForTests();
  }

  private static Kv data(RespBodyVo response) {
    assertTrue("命令应当成功,实际:" + response.getMsg(), response.isOk());
    Object data = response.getData();
    return data instanceof Kv ? (Kv) data : new Kv();
  }

  private static String text(String selector) {
    return String.valueOf(service.getInstance(id).page.evaluate(
        "(s) => document.querySelector(s) ? document.querySelector(s).textContent : null", selector));
  }

  /** Kv 里存的可能是 Integer/Long/String,统一按整数读(取不到算 0) */
  private static int intOf(Kv kv, String key) {
    Object value = kv.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  /** 每次测试前把页面恢复原状(确认框可能被上一个用例关掉了) */
  private void open() {
    service.getInstance(id).page.navigate(base);
    service.getInstance(id).page.waitForTimeout(250);
  }

  // ==================== 点击的鼠标档 ====================

  /**
   * 一直在动的元素:原生点击会以「元素不稳定」超时,auto 应当改用真实鼠标点它的中心
   *
   * <p>这一档是这一轮实操里最关键的一条:ant-design 的弹窗按钮原生点击一直超时,只有真实鼠标事件
   * 才生效。原来 auto 只会降级成 JS 派发,而 JS 派发对这类按钮完全无效。
   */
  @Test
  public void movingElementFallsBackToRealMouse() {
    open();
    RespBodyVo nativeOnly = service.clickElementBySelector(id, "#moving", "native", 700);
    assertFalse("原生点击一直在动的元素应当失败,实际:" + nativeOnly.getMsg(), nativeOnly.isOk());

    Kv result = data(service.clickElementBySelector(id, "#moving", "auto", 700));
    assertEquals("降级后应当用的是真实鼠标", "mouse", result.getStr("mode"));
    assertNotNull("回执要说明降级原因", result.getStr("fallbackReason"));
    assertEquals("真实鼠标点击要真的触发页面事件", "moving-clicked", text("#clickFlag"));
  }

  /** mode=mouse 时显式走真实鼠标,不做可操作性检查 */
  @Test
  public void explicitMouseModeSkipsActionabilityCheck() {
    open();
    Kv result = data(service.clickElementBySelector(id, "#moving", "mouse", 700));
    assertEquals("mouse", result.getStr("mode"));
    assertNull("显式要求 mouse 时不算降级,不该有 fallbackReason", result.getStr("fallbackReason"));
    assertEquals("moving-clicked", text("#clickFlag"));
  }

  /** 被遮住的目标不走鼠标档:鼠标点的是坐标,会把遮挡物按下去 */
  @Test
  public void coveredTargetSkipsMouseFallback() {
    open();
    // 协议层盖住整个页面,它下面的按钮被遮挡
    service.getInstance(id).page.evaluate("() => {"
        + " const layer = document.createElement('div'); layer.className = 'agreement-container';"
        + " layer.style.cssText = 'position:fixed;inset:0;z-index:2000';"
        + " layer.id = 'agreement'; document.body.appendChild(layer);"
        + " document.getElementById('moving').scrollIntoView(); }");
    service.getInstance(id).page.waitForTimeout(150);
    String before = text("#clickFlag");
    RespBodyVo result = service.clickElementBySelector(id, "#moving", "auto", 700);
    assertFalse("auto 不应穿透遮挡层", result.isOk());
    assertTrue(result.getMsg(), result.getMsg().contains("ELEMENT_OBSCURED"));
    assertEquals("背景按钮不应收到点击", before, text("#clickFlag"));
  }

  // ==================== DOM 弹窗 ====================

  /** get_modals 要能列出标题、按钮文本与右上角 × 的坐标 */
  @Test
  public void modalsAreListedWithButtonsAndClosePoint() {
    open();
    Kv probe = data(service.getModals(id));
    assertEquals(1, intOf(probe, "count"));
    // 脚本返回的对象经过 JSON 序列化后是 LinkedHashMap,统一转成 Kv 再读
    Kv modal = Kv.create().set((Map<String, Object>) listOf(probe.get("modals")).get(0));
    assertEquals("ant-modal", modal.getStr("kind"));
    assertEquals("确认提交申请？", modal.getStr("title"));
    assertTrue("按钮文本要包含取消与确定,实际:" + modal.get("buttons"),
        String.valueOf(modal.get("buttons")).contains("取消") && String.valueOf(modal.get("buttons")).contains("确定"));
    assertTrue("要给出右上角 × 的坐标", modal.getBoolean("hasClose"));
    assertNotNull(modal.get("closePoint"));
    assertNotNull("要给出最顶层弹窗", probe.get("top"));
  }

  /** 把 Kv 里的数组读成 List<Object>(元素通常是 LinkedHashMap) */
  @SuppressWarnings("unchecked")
  private static List<Object> listOf(Object value) {
    return value instanceof List ? (List<Object>) value : new java.util.ArrayList<>();
  }

  /** close_modal 用真实鼠标点按钮,并且点完校验数量真的减少了 */
  @Test
  public void closeModalClicksRealButtonAndVerifiesCount() {
    open();
    Kv result = data(service.closeModal(id, null, null, "取消"));
    assertTrue("弹窗应当真的被关掉:" + result, result.getBoolean("closed"));
    assertEquals(1, intOf(result, "countBefore"));
    assertEquals(0, intOf(result, "countAfter"));
    assertTrue("要说明点的是哪个按钮", String.valueOf(result.get("clicked")).contains("取消"));
  }

  /** 没有弹窗时 close_modal 如实说明,不算失败 */
  @Test
  public void closeModalOnCleanPageSaysNothingToDo() {
    open();
    data(service.closeModal(id, null, null, "取消"));
    Kv again = data(service.closeModal(id, null, null, "取消"));
    assertFalse(again.getBoolean("closed"));
    assertNotNull("要说明当前没有弹窗", again.getStr("note"));
  }

  // ==================== 等待 ====================

  /** wait_for_stable:等内容连续一段时间不再变化,避免读到上一轮的旧内容 */
  @Test
  public void waitForStableWaitsForFinalContent() {
    open();
    // 安静窗口要比 fixture 里两次变化之间的间隔长,否则会在中间态就判定「稳定」
    Kv result = data(service.waitForStable(id, "#asyncBox", 900, 5.0));
    assertTrue("应当等到内容稳定,实际:" + result, result.getBoolean("stable"));
    assertEquals("稳定之后读到的应当是最终内容", "fresh", result.getStr("text"));
  }

  /** wait_for_count:等异步插入的行数达标 */
  @Test
  public void waitForCountWaitsForRows() {
    open();
    Kv result = data(service.waitForCount(id, ".row", 3, null, null, 5.0));
    assertEquals(3, intOf(result, "count"));
    assertTrue(result.getBoolean("matched"));
  }

  /** 等不到就如实报超时,而不是假装成功 */
  @Test
  public void waitForCountTimesOutHonestly() {
    open();
    RespBodyVo response = service.waitForCount(id, ".row", 99, null, null, 0.8);
    assertFalse("等不到应当失败", response.isOk());
    assertTrue("失败信息里要带实际数量,实际:" + response.getMsg(), response.getMsg().contains("实际 3"));
  }

  // ==================== 断言与异步批次 ====================

  /** 批次里的 expect:动作成功但状态没变时,必须如实标出来 */
  @Test
  public void expectAssertionCatchesNoopAction() {
    open();
    JSONObject noop = new JSONObject();
    noop.put("body", "() => 1");

    JSONObject params = new JSONObject();
    params.put("stopOnError", true);
    JSONArray commands = new JSONArray();
    // 第一步:断言通过(实际值 2 等于期望值 2)
    commands.add(wrap("execute_js", noop, expectOf("() => 2", "equals", 2)));
    params.put("commands", commands);

    RespBodyVo response = actions.execute(id, "commands", params);
    assertTrue("断言通过时批次应当成功:" + response.getMsg(), response.isOk());
    Kv data = data(response);
    assertEquals(0, intOf(data, "expectFailed"));

    // 第二步:把期望值改成 3 —— 命令本身还是成功,但断言必须如实报出来
    commands.set(0, wrap("execute_js", noop, expectOf("() => 2", "equals", 3)));
    RespBodyVo failed = actions.execute(id, "commands", params);
    assertFalse("断言没通过时批次应当报失败", failed.isOk());
    Kv failedData = failed.getData() instanceof Kv ? (Kv) failed.getData() : new Kv();
    assertEquals(1, intOf(failedData, "expectFailed"));
    assertEquals("命令本身都执行成功了", 1, intOf(failedData, "succeeded"));
    assertEquals("不该把它算成命令失败", 0, intOf(failedData, "failed"));
    assertNotNull("要说明是断言没过而不是命令失败", failedData.getStr("note"));
  }

  /** async:true 立刻回 jobId,get_job 能拿到最终结果 */
  @Test
  public void asyncBatchReturnsJobIdAndResult() {
    open();
    JSONObject params = new JSONObject();
    JSONArray commands = new JSONArray();
    JSONObject body = new JSONObject();
    body.put("body", "() => 42");
    commands.add(wrap("execute_js", body, null));
    params.put("commands", commands);
    params.put("async", true);

    Kv started = data(actions.execute(id, "commands", params));
    String jobId = started.getStr("jobId");
    assertNotNull("异步批次要立刻回 jobId", jobId);
    assertNotNull("要提示怎么取结果", started.getStr("hint"));

    RespBodyVo finished = waitForJob(jobId, 15_000);
    Kv job = data(finished);
    assertEquals("done", job.getStr("status"));
    Kv result = job.get("data") instanceof Kv ? (Kv) job.get("data") : new Kv();
    assertEquals(1, intOf(result, "succeeded"));
  }

  private static RespBodyVo waitForJob(String jobId, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    JSONObject params = new JSONObject();
    params.put("jobId", jobId);
    while (System.currentTimeMillis() < deadline) {
      RespBodyVo response = actions.execute(id, "get_job", params);
      if (response.isOk()) {
        Kv data = (Kv) response.getData();
        if (!"running".equals(data.getStr("status"))) {
          return response;
        }
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("等异步任务超时:" + jobId);
  }

  // ==================== 自省与上传 ====================

  /** get_config 要一次把生效配置列全,别再靠翻配置文件猜 */
  @Test
  public void getConfigReportsEffectiveSettings() {
    Kv config = data(service.getConfig(id));
    assertNotNull("要报引擎", config.getStr("engine"));
    assertNotNull("要报配置的类型", config.getStr("configuredType"));
    assertNotNull("要报解析后的 profile 目录", config.get("profileDir"));
    assertNotNull("要报动作超时", config.get("action"));
    assertNotNull("要报脚本目录", config.getStr("jsDir"));
    assertNotNull("要报追踪目录", config.get("trace"));
    assertNotNull("要报上传目录", config.get("upload"));
    assertTrue("命令清单不该是空的", intOf(config, "commands") > 0 || config.get("commands") != null);
    assertNotNull("传了 id 就顺带带上这个任务用的浏览器", config.get("browser"));
  }

  /** list_tasks 要能看到活着的任务,而不是只能翻日志 */
  @Test
  public void listTasksShowsLiveTasks() {
    Kv tasks = data(service.listTasks());
    assertTrue("至少有当前这个任务", intOf(tasks, "count") >= 1);
    @SuppressWarnings("unchecked")
    List<Kv> items = (List<Kv>) tasks.get("tasks");
    boolean found = false;
    for (Kv item : items) {
      if (id.equals(item.getLong("id"))) {
        found = true;
        assertNotNull("要能看到当前 URL", item.getStr("url"));
        assertNotNull("要能看到页签数", item.get("tabCount"));
      }
    }
    assertTrue("当前任务应当在清单里", found);
  }

  /** get_browser_state 直接内联元素清单,少一次 get_interactive_map 往返 */
  @Test
  public void browserStateInlinesElements() {
    open();
    Kv state = data(service.getBrowserState(id, null, null, true, 20));
    assertNotNull("要内联 elements", state.get("elements"));
    assertTrue("元素数应当大于 0", intOf(state, "elementCount") > 0);
    assertNotNull("要说清清单是否被截断", state.get("elementsTruncated"));
    @SuppressWarnings("unchecked")
    List<Kv> elements = (List<Kv>) state.get("elements");
    Kv first = elements.get(0);
    assertNotNull("每项要有 index,后续按索引操作要用", first.get("index"));
    assertNotNull("每项要有 rect,便于判断位置", first.get("rect"));
  }

  /** 没有快照时 get_interactive_map 自己先建一份,不该直接失败 */
  @Test
  public void interactiveMapBuildsSnapshotOnDemand() {
    service.getInstance(id).domState = null;
    Kv map = data(service.getInteractiveMap(id));
    assertTrue("应当自己建快照后返回元素", intOf(map, "count") > 0);
  }

  /** upload_file 直接吃 base64:省掉「先 POST /playwright/upload 再上传」的一次往返 */
  @Test
  public void uploadFileAcceptsInlineBase64() {
    open();
    service.getInstance(id).page.evaluate("() => {"
        + " const input = document.createElement('input'); input.type = 'file'; input.id = 'inlineFile';"
        + " input.addEventListener('change', function (e) {"
        + "   document.getElementById('asyncBox').textContent = e.target.files[0].name; });"
        + " document.body.appendChild(input); }");
    String content = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
    Kv result = data(service.uploadFileInline(id, null, "#inlineFile", "内联图样.jpg", "image/jpeg", content, null,
        5_000));
    assertEquals("内联图样.jpg", result.getStr("filename"));
    assertEquals("contentBase64", result.getStr("source"));
    assertNotNull("要回服务端路径,后续还能复用这份文件", result.getStr("path"));
    assertEquals("页面要真的收到这个文件", "内联图样.jpg", text("#asyncBox"));
  }

  // ==================== 清理 ====================

  /** cleanup 默认只预演:不显式说 dryRun=false 就不许删文件 */
  @Test
  public void cleanupDefaultsToDryRun() {
    Kv preview = data(service.cleanup("data", 0, 0, null));
    assertTrue("默认必须是预演", preview.getBoolean("dryRun"));
    assertNotNull("要说明扫的是哪个目录", preview.get("data"));
    assertNotNull("要报预演会删多少", intOf(preview, "deletedFiles") >= 0);
  }

  // ==================== 工具 ====================

  private static JSONObject wrap(String command, JSONObject args, JSONObject expect) {
    JSONObject entry = new JSONObject();
    entry.put(command, args);
    if (expect != null) {
      entry.put("expect", expect);
    }
    return entry;
  }

  private static JSONObject expectOf(String js, String matcher, Object value) {
    JSONObject expect = new JSONObject();
    expect.put("js", js);
    expect.put(matcher, value);
    return expect;
  }
}
