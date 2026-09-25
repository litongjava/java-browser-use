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
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.model.body.RespBodyVo;

/**
 * frame / 监听器 / 上传回读 / 弹窗兜底 / 报错带下一步 的回归测试
 *
 * <p>
 * 这些能力都是「2026-09-24 企业微信全流程」那次实操踩坑之后补的(主站把第三方控制台套在跨域 iframe 里、
 * 上传的 file input 没挂监听器、弹窗只有自有类名……),坑本身在真实站点上很难复现,所以这里用 fixture
 * 页面把每个坑的最小形态固定下来:
 *
 * <ul>
 * <li><b>跨域 iframe</b>:主站把第三方控制台套在**另一个 origin** 的 iframe 里(两个本地端口 = 两个 origin)。
 * 只扫顶层 document 时一个元素都读不到;`list_frames` / `includeFrames` / 按索引自动路由要能读到、点到;</li>
 * <li><b>没有监听器的 file input</b>:`setInputFiles` 把文件放进去了,但页面不会处理 —— 回执必须如实报
 * `consumed=noListener`,而不是回一个光秃秃的 `ok:true`;</li>
 * <li><b>自有类名的弹窗</b>:`qui_dialog` 这种既没有 `role=dialog`、也不在框架选择器里的弹窗,靠类名线索
 * 兜底命中,并且能按 `class:` 子串关掉;</li>
 * <li><b>报错带下一步</b>:索引越界/元素失效时要把快照的年龄与元素范围说出来;实例不存在时要说明「服务重启
 * 过」;</li>
 * <li><b>点击回执在 iframe 里也要能看出变化</b>:探针必须取在目标所在的 frame 里,否则永远是 changed:false。</li>
 * </ul>
 */
public class BrowserFrameUpgradeTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  /** 主站(外壳 + 同源 iframe) */
  private static HttpServer mainServer;
  /** 另一个 origin 的「第三方控制台」 */
  private static HttpServer consoleServer;
  private static String mainBase;
  private static String consoleBase;
  private static Path profileDir;
  private static Path uploadDir;

  /** 外壳页:没有任何可交互元素,只有一个**跨域** iframe —— 「只扫顶层文档会得到空壳」的最小形态 */
  private static final String SHELL_PAGE = """
      <html><head><meta charset="utf-8"><title>shell</title></head><body>
        <div>外壳,一层可交互元素都没有</div>
        <iframe id="onlyFrame" src="__CONSOLE__/inner" width="500" height="220"></iframe>
      </body></html>
      """;

  /** 主站:顶层按钮 + 同源 iframe + 跨域 iframe + 两个 file input + 自有类名的弹窗 */
  private static final String MAIN_PAGE = """
      <html><head><meta charset="utf-8"><title>main</title>
      <style>
        .qui_dialog { position: fixed; left: 1040px; top: 400px; width: 220px; height: 110px;
          z-index: 900; background: #fff; }
        #plainOverlay { position: fixed; left: 1050px; top: 30px; width: 200px; height: 100px;
          z-index: 800; }
        #spacer { height: 2000px; }
      </style></head><body>
        <button id="topBtn">顶层按钮</button>
        <div id="topFlag">idle</div>
        <iframe id="sameFrame" src="/inner" width="500" height="220"></iframe>
        <iframe id="crossFrame" src="__CONSOLE__/inner" width="500" height="220"></iframe>
        <input type="file" id="deadFile" style="display:none">
        <input type="file" id="liveFile" style="display:none">
        <div id="fileFlag">none</div>
        <div id="plainOverlay"></div>
        <div class="qui_dialog" id="quidlg">
          <div class="qui_dialog_title">确认开通企业邮箱</div>
          <button class="qui_dialog_btn" id="quiCancel">取消</button>
        </div>
        <div id="spacer"></div>
        <button id="bottomBtn">在首屏之外的按钮</button>
        <script>
          document.getElementById('topBtn').addEventListener('click', function () {
            document.getElementById('topFlag').textContent = 'top-clicked';
          });
          document.getElementById('liveFile').addEventListener('change', function (e) {
            document.getElementById('fileFlag').textContent = e.target.files[0].name;
          });
          document.getElementById('quiCancel').addEventListener('click', function () {
            document.getElementById('quidlg').remove();
          });
        </script>
      </body></html>
      """;

  /** iframe 里的「第三方控制台」 */
  private static final String INNER_PAGE = """
      <html><head><meta charset="utf-8"><title>inner-console</title></head><body>
        <button id="innerBtn">控制台按钮</button>
        <div id="innerFlag">idle</div>
        <a id="navDomain" href="#/domain">邮箱域名</a>
        <script>
          document.getElementById('innerBtn').addEventListener('click', function () {
            document.getElementById('innerFlag').textContent = 'inner-clicked';
          });
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-frame-profile");
    uploadDir = Files.createTempDirectory("browser-use-frame-upload");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    System.setProperty(UploadStore.KEY_DIR, uploadDir.toString());
    System.setProperty(PlaywrightService.KEY_ACTION_TIMEOUT, "1500");
    ChromeBrowser.resetForTests();

    consoleServer = server(INNER_PAGE);
    consoleBase = "http://127.0.0.1:" + consoleServer.getAddress().getPort();

    mainServer = server(null);
    mainBase = "http://127.0.0.1:" + mainServer.getAddress().getPort();

    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  /** 起一个 fixture 服务器。{@code singlePage} 非空时所有路径都回它(「第三方控制台」那一台就用这个) */
  private static HttpServer server(String singlePage) throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String body;
      if (singlePage != null) {
        body = singlePage;
      } else if (path.startsWith("/shell")) {
        body = SHELL_PAGE.replace("__CONSOLE__", consoleBase);
      } else if (path.startsWith("/inner")) {
        body = INNER_PAGE;
      } else {
        body = MAIN_PAGE.replace("__CONSOLE__", consoleBase);
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    http.start();
    return http;
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
    if (mainServer != null) {
      mainServer.stop(0);
    }
    if (consoleServer != null) {
      consoleServer.stop(0);
    }
    ChromeBrowser.resetForTests();
  }

  private static Kv data(RespBodyVo response) {
    assertTrue("命令应当成功,实际:" + response.getMsg(), response.isOk());
    Object data = response.getData();
    return data instanceof Kv ? (Kv) data : new Kv();
  }

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

  @SuppressWarnings("unchecked")
  private static List<Object> listOf(Object value) {
    return value instanceof List ? (List<Object>) value : new java.util.ArrayList<>();
  }

  private static Kv mapToKv(Object value) {
    Kv kv = new Kv();
    if (value instanceof Map) {
      kv.putAll((Map<?, ?>) value);
    }
    return kv;
  }

  /** 打开主站页面并等 iframe 都加载完 */
  private void openMain() {
    service.getInstance(id).page.navigate(mainBase + "/");
    service.getInstance(id).page.waitForTimeout(600);
  }

  private static String innerText(String selector) {
    return String.valueOf(service.getInstance(id).page.evaluate(
        "(s) => { const el = document.querySelector(s); return el ? el.textContent : null; }", selector));
  }

  /** 在 URL 以 prefix 开头的那个 **iframe** 里读 #innerFlag(顶层文档读不到 iframe 里的东西) */
  private static String innerFlagOfFrame(String urlPrefix) {
    for (com.microsoft.playwright.Frame frame : service.getInstance(id).page.frames()) {
      if (frame.parentFrame() == null) {
        continue; // 跳过主 frame
      }
      if (!frame.url().startsWith(urlPrefix)) {
        continue;
      }
      Object value = frame
          .evaluate("() => { const el = document.getElementById('innerFlag'); return el ? el.textContent : null; }");
      return value == null ? null : String.valueOf(value);
    }
    return null;
  }

  // ==================== frame:读 ====================

  /** list_frames 要能看到跨域 iframe,并给出各自的元素数与索引区间 */
  @Test
  public void listFramesSeesCrossOriginFrame() {
    openMain();
    Kv frames = data(service.listFrames(id, null));
    assertTrue("主站 + 两个 iframe 至少 3 个 frame,实际:" + frames, intOf(frames, "count") >= 3);
    boolean main = false;
    boolean crossOrigin = false;
    boolean innerUrl = false;
    for (Object item : listOf(frames.get("frames"))) {
      Kv frame = mapToKv(item);
      if (Boolean.TRUE.equals(frame.getBoolean("isMain"))) {
        main = true;
        assertEquals("主 frame 的序号必须是 0", 0, intOf(frame, "index"));
      }
      String url = String.valueOf(frame.getStr("url"));
      if (url.startsWith(consoleBase)) {
        crossOrigin = true;
        assertTrue("跨域 frame 里的元素也要数出来,实际:" + frame, intOf(frame, "elementCount") > 0);
        assertNotNull("有元素就要给索引区间", frame.getStr("indexRange"));
      }
      if (url.contains("/inner")) {
        innerUrl = true;
      }
    }
    assertTrue("要认出主 frame", main);
    assertTrue("要看到跨域 iframe(另一个 origin)", crossOrigin);
    assertTrue("要看到 iframe 的 URL", innerUrl);
  }

  /** get_browser_state 加 includeFrames 之后,iframe 里的元素要进同一份快照,并且带 frame 分隔行 */
  @Test
  public void includeFramesReadsIframeElements() {
    openMain();
    Kv state = data(service.getBrowserState(id, false, 0, true, 200, Boolean.TRUE));
    String text = state.getStr("text");
    assertNotNull("带 frame 的快照要有 frames 清单", state.get("frames"));
    assertTrue("要有 frame 分隔行,实际开头:" + text.substring(0, Math.min(200, text.length())),
        text.contains("--- frame["));
    assertTrue("要能读到 iframe 里的导航项(邮箱域名),实际:" + text, text.contains("邮箱域名"));
    assertTrue("要能读到 iframe 里的按钮", text.contains("控制台按钮"));
    // 每一条元素都要带 frameIndex,并且至少有一条属于 iframe(frameIndex > 0)
    boolean sawFrameElement = false;
    for (Object item : listOf(state.get("elements"))) {
      Kv element = mapToKv(item);
      assertNotNull("带 frame 的快照里每个元素都要有 frameIndex,实际:" + element, element.get("frameIndex"));
      if (intOf(element, "frameIndex") > 0) {
        sawFrameElement = true;
        assertNotNull("iframe 里的元素要知道是哪个 frame", element.getStr("frameUrl"));
      }
    }
    assertTrue("元素清单里应当有属于 iframe 的条目", sawFrameElement);
  }

  /** 不带 includeFrames 时只看顶层;顶层一个元素都没有、但页面上有 iframe 时,要主动提示下一步 */
  @Test
  public void emptyTopLevelHintsAboutFrames() {
    service.getInstance(id).page.navigate(mainBase + "/shell");
    service.getInstance(id).page.waitForTimeout(500);
    Kv state = data(service.getBrowserState(id, false, 0, true, 200));
    String hint = state.getStr("frameHint");
    assertNotNull("顶层读不到元素而页面有 iframe 时,必须提示 includeFrames / list_frames,实际:" + state, hint);
    assertTrue("提示里要写清用哪个参数,实际:" + hint, hint.contains("includeFrames") && hint.contains("list_frames"));
  }

  /** 快照里的 frameIndex 与 frame 参数是同一套编号:照着快照里的数字填 frame 就该命中同一个 frame */
  @Test
  public void frameIndexMatchesFrameParameter() {
    openMain();
    // 默认快照不纳入跨域 frame,但要给出下一步
    Kv defaultState = data(service.getBrowserState(id, false, 0, true, 200));
    assertNotNull("有跨域 frame 没纳入时必须提示 includeFrames,实际:" + defaultState, defaultState.getStr("frameHint"));
    for (Object item : listOf(defaultState.get("elements"))) {
      Kv element = mapToKv(item);
      assertTrue("默认快照里不该有跨域 frame 的元素,实际:" + element,
          String.valueOf(element.getStr("frameUrl")).startsWith(mainBase));
    }

    Kv full = data(service.getBrowserState(id, false, 0, true, 200, Boolean.TRUE));
    Integer crossFrameIndex = null;
    for (Object item : listOf(full.get("elements"))) {
      Kv element = mapToKv(item);
      if (String.valueOf(element.getStr("frameUrl")).startsWith(consoleBase)) {
        crossFrameIndex = intOf(element, "frameIndex");
        break;
      }
    }
    assertNotNull("includeFrames 之后应当能看到跨域 frame 里的元素", crossFrameIndex);
    // 快照里的 frameIndex 直接当 frame 参数用,应当命中同一个 frame
    assertEquals("快照里的 frameIndex 与 frame 参数必须是同一套编号", 1,
        intOf(data(service.getElementCount(id, "#innerBtn", String.valueOf(crossFrameIndex))), "count"));
  }

  // ==================== frame:写(按索引自动路由) ====================

  /** 按索引点击 iframe 里的按钮:索引里带了 frame 信息,调用方不需要传 frame 参数 */
  @Test
  public void indexClickRoutesIntoFrameAutomatically() {
    openMain();
    Kv state = data(service.getBrowserState(id, false, 0, true, 200, Boolean.TRUE));
    Integer innerIndex = null;
    for (Object item : listOf(state.get("elements"))) {
      Kv element = mapToKv(item);
      if (intOf(element, "frameIndex") > 0 && "控制台按钮".equals(element.getStr("text"))) {
        innerIndex = intOf(element, "index");
        break;
      }
    }
    assertNotNull("快照里应当能找到 iframe 里的「控制台按钮」", innerIndex);

    Kv result = data(service.clickElementByIndex(id, innerIndex, "native", 3000));
    // 探针必须取在目标所在的 frame 里,否则点 iframe 里的东西永远是 changed:false
    assertTrue("iframe 里的点击也要能观察到变化,实际:" + result, result.getBoolean("changed"));
    assertNotNull("要说明探测的是哪个 frame", result.getStr("probedFrameUrl"));
    assertNotNull("要给出这次命中的元素", result.get("hit"));

    // 两个 iframe 里的按钮都叫这个名字(顺带还有一个同源的),所以看哪个 frame 里真的变了
    String sameOrigin = innerFlagOfFrame(mainBase);
    String crossOrigin = innerFlagOfFrame(consoleBase);
    assertTrue("iframe 里的点击要真的生效(同源或跨域之一),实际 sameOrigin=" + sameOrigin
        + " crossOrigin=" + crossOrigin,
        "inner-clicked".equals(sameOrigin) || "inner-clicked".equals(crossOrigin));
  }

  /** 按选择器操作要显式传 frame:不传够不着 iframe,传了就到位 */
  @Test
  public void selectorNeedsExplicitFrame() {
    openMain();
    String crossOrigin = consoleBase;
    RespBodyVo withoutFrame = service.clickElementBySelector(id, "#innerBtn", "native", 800);
    assertFalse("不传 frame 时选择器够不着 iframe 内部,应当失败", withoutFrame.isOk());

    Kv result = data(service.clickElementBySelector(id, "#innerBtn", "native", 3000, crossOrigin));
    assertTrue("传了 frame 就该点中,实际:" + result, result.getBoolean("changed"));
    assertNotNull(result.getStr("probedFrameUrl"));
    assertEquals("要数得到 iframe 里的元素", 1, intOf(data(service.getElementCount(id, "#innerBtn", crossOrigin)),
        "count"));
  }

  /** frame 传了但不匹配时要给出可用清单,而不是一句看不懂的失败 */
  @Test
  public void unknownFrameGivesAvailableList() {
    openMain();
    RespBodyVo response = service.clickElementBySelector(id, "#innerBtn", "native", 800, "no-such-frame.example");
    assertFalse(response.isOk());
    assertTrue("要列出可用的 frame,实际:" + response.getMsg(), response.getMsg().contains("可用"));
    assertTrue("要提示 list_frames", response.getMsg().contains("list_frames"));
  }

  /** execute_js 可以指定 frame;并且一定会 await Promise */
  @Test
  public void executeJsRunsInsideFrame() {
    openMain();
    Kv inFrame = data(service.executeJs(id,
        "async () => { await new Promise(r => setTimeout(r, 20)); return document.title; }", null, null,
        consoleBase));
    assertEquals("inner-console", inFrame.getStr("result"));
    assertTrue("要说明这次是在哪个 frame 里跑的,实际:" + inFrame.getStr("frameUrl"),
        String.valueOf(inFrame.getStr("frameUrl")).startsWith(consoleBase));
    assertEquals("要说明这次等了 Promise", Boolean.TRUE, inFrame.getBoolean("awaited"));

    Kv top = data(service.executeJs(id, "() => document.title", null, null, null));
    assertEquals("main", top.getStr("result"));
    assertNull("不传 frame 时不该有 frameUrl", top.getStr("frameUrl"));
  }

  // ==================== 监听器 ====================

  /** 有监听器的元素要认出它挂了什么事件 */
  @Test
  public void listenersDetectLiveInput() {
    openMain();
    Kv probe = data(service.getElementListeners(id, null, "#liveFile", null));
    assertTrue("要能找到元素", probe.getBoolean("found"));
    assertEquals("INPUT", probe.getStr("tag"));
    assertEquals("有监听器就是 true", Boolean.TRUE, probe.getBoolean("hasListeners"));
    assertTrue("要报出事件名,实际:" + probe.get("listeners"),
        String.valueOf(probe.get("listeners")).contains("change"));
    assertNull("有监听器时不该给「没人监听」的提示", probe.getStr("note"));
  }

  /**
   * 没有监听器的 file input 必须被认出来 —— 这就是 P2 那个「upload_file 报成功、页面没生效」的根因
   *
   * <p>Vue 2 的 `el._vei` 为空时,JS 派发的 change 到不了框架的 handler。判定成 `false`(而不是
   * 「未知」)是因为这里确实探到了框架痕迹之外的信息:内联 handler 属性一个都没有。
   */
  @Test
  public void listenersDetectDeadInput() {
    openMain();
    Kv probe = data(service.getElementListeners(id, null, "#deadFile", null));
    assertTrue(probe.getBoolean("found"));
    assertEquals("没有任何监听器", Boolean.FALSE, probe.getBoolean("hasListeners"));
    assertEquals("内联 handler 也是空", Boolean.FALSE, probe.getBoolean("inline"));
    assertNotNull("必须给一句可操作的提示", probe.getStr("note"));
    assertTrue("提示要说清下一步,实际:" + probe.getStr("note"),
        probe.getStr("note").contains("组件方法") || probe.getStr("note").contains("监听"));
  }

  /** get_interactive_map / get_browser_state 的元素清单里也要带 hasListeners */
  @Test
  public void interactiveMapCarriesListenerInfo() {
    openMain();
    service.getBrowserState(id, false, 0, true, 200);
    Kv map = data(service.getInteractiveMap(id));
    assertTrue(intOf(map, "count") > 0);
    for (Object item : listOf(map.get("elements"))) {
      Kv element = mapToKv(item);
      if ("file".equals(element.getStr("type"))) {
        assertNotNull("file input 必须带 hasListeners,实际:" + element, element.get("hasListeners"));
      }
    }
  }

  // ==================== 上传回读 ====================

  /** 上传到「没人监听」的 input:回执必须如实报 noListener + 一句可操作的提示 */
  @Test
  public void uploadReportsNoListenerHonestly() {
    openMain();
    RespBodyVo response = service.uploadFileInline(id, null, "#deadFile", "营业执照.png", "image/png",
        Base64.getEncoder().encodeToString("not-a-real-png".getBytes(StandardCharsets.UTF_8)), null, 5_000);
    Kv result = data(response);
    assertEquals("文件确实进了 input", 1, intOf(result, "filesLength"));
    assertEquals("要如实报出「这个 input 没人监听」", "noListener", result.getStr("consumed"));
    assertEquals(Boolean.FALSE, result.getBoolean("hasListeners"));
    assertNotNull("要给出下一步", result.getStr("hint"));
    assertFalse("页面不会处理它,所以 effective 必须是 false", Boolean.TRUE.equals(result.getBoolean("effective")));
    assertEquals("页面上的状态没变", "none", innerText("#fileFlag"));
  }

  /** 上传到有监听器的 input:consumed=listened,页面真的收到了文件 */
  @Test
  public void uploadToLiveInputIsConsumed() {
    openMain();
    RespBodyVo response = service.uploadFileInline(id, null, "#liveFile", "图样.jpg", "image/jpeg",
        Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3}), null, 5_000);
    Kv result = data(response);
    assertEquals("listened", result.getStr("consumed"));
    assertEquals(Boolean.TRUE, result.getBoolean("hasListeners"));
    assertEquals("页面要真的收到这个文件", "图样.jpg", innerText("#fileFlag"));
  }

  // ==================== 弹窗兜底 ====================

  /** 自有类名的弹窗(没有 role=dialog、不在框架选择器里)要靠类名线索被找到,并标注命中来源 */
  @Test
  public void modalsHeuristicFindsCustomClassDialog() {
    openMain();
    Kv probe = data(service.getModals(id));
    assertTrue("必须找到 .qui_dialog 这个自定义弹窗,实际:" + probe, intOf(probe, "count") >= 1);
    Kv dialog = null;
    for (Object item : listOf(probe.get("modals"))) {
      Kv modal = mapToKv(item);
      if (String.valueOf(modal.getStr("className")).contains("qui_dialog")) {
        dialog = modal;
        break;
      }
    }
    assertNotNull(".qui_dialog 应当在清单里,实际:" + probe.get("modals"), dialog);
    assertEquals("要标注命中来源(类名线索)", "heuristic:class-name", dialog.getStr("matchedBy"));
    assertTrue("要给出标题,实际:" + dialog, String.valueOf(dialog.getStr("title")).contains("确认开通企业邮箱"));
    assertTrue("要给出按钮", String.valueOf(dialog.get("buttons")).contains("取消"));
  }

  /** 几何兜底:没有类名线索、但 fixed + 高 z-index + 够大的浮层也要被列出来 */
  @Test
  public void modalsHeuristicFindsPlainOverlay() {
    openMain();
    Kv probe = data(service.getModals(id));
    boolean found = false;
    for (Object item : listOf(probe.get("modals"))) {
      Kv modal = mapToKv(item);
      if ("plainOverlay".equals(modal.getStr("id"))) {
        found = true;
        assertEquals("heuristic:fixed-overlay", modal.getStr("matchedBy"));
      }
    }
    assertTrue("没有类名线索的 fixed 浮层要靠几何兜底命中,实际:" + probe.get("modals"), found);
  }

  /** close_modal 支持 which:"class:<子串>" —— 很多站点的弹窗既没标题也没 role=dialog */
  @Test
  public void closeModalByClassSubstring() {
    openMain();
    Kv result = data(service.closeModal(id, "class:qui_dialog", null, "取消"));
    assertTrue("按类名子串关弹窗要真的关掉,实际:" + result, result.getBoolean("closed"));
    assertTrue("弹窗数量要减少", intOf(result, "countAfter") < intOf(result, "countBefore"));
  }

  /** 类名匹配不到时要说清楚是怎么匹配的,并且提示去看 className */
  @Test
  public void closeModalByUnknownClassExplainsItself() {
    openMain();
    RespBodyVo response = service.closeModal(id, "class:no_such_dialog_class", null, null);
    assertFalse(response.isOk());
    assertTrue("要说清是按 className 找的,实际:" + response.getMsg(),
        response.getMsg().contains("className"));
    assertTrue("要提示看 get_modals", response.getMsg().contains("get_modals"));
  }

  // ==================== 报错带下一步 ====================

  /** 索引越界时要把快照的年龄、元素总数、合法区间说清楚 */
  @Test
  public void indexOutOfRangeExplainsSnapshot() {
    openMain();
    service.getBrowserState(id, false, 0, true, 200);
    RespBodyVo response = service.clickElementByIndex(id, 9999, "native", 800);
    assertFalse(response.isOk());
    String message = response.getMsg();
    assertTrue("要带元素总数与合法区间,实际:" + message, message.contains("可交互元素") && message.contains("合法区间"));
    assertTrue("要提示重新取快照或改用选择器,实际:" + message,
        message.contains("get_browser_state") && message.contains("by_selector"));
  }

  /** 没有快照时的提示要指向 get_browser_state */
  @Test
  public void missingSnapshotIsExplained() {
    openMain();
    service.getInstance(id).domState = null;
    RespBodyVo response = service.clickElementByIndex(id, 3, "native", 800);
    assertFalse(response.isOk());
    assertTrue("要提示先取快照,实际:" + response.getMsg(), response.getMsg().contains("get_browser_state"));
  }

  /** 实例不存在时要说清「服务重启过」,而不是只回一句找不到 */
  @Test
  public void missingInstanceExplainsRestart() {
    RespBodyVo response = service.getUrl(2049L);
    assertFalse(response.isOk());
    String message = response.getMsg();
    assertTrue("要报出 id,实际:" + message, message.contains("2049"));
    assertTrue("要说明服务启动时间,实际:" + message, message.contains("启动于"));
    assertTrue("要说明实例只在内存里,实际:" + message, message.contains("重启即失效"));
    assertTrue("要给出下一步,实际:" + message, message.contains("list_tasks") && message.contains("start"));
  }

  /** 点击回执要带这次命中的元素(拿 changed=false 之后唯一的下一步线索) */
  @Test
  public void clickReceiptIncludesHitElement() {
    openMain();
    Kv state = data(service.getBrowserState(id, false, 0, true, 200));
    Integer topIndex = null;
    for (Object item : listOf(state.get("elements"))) {
      Kv element = mapToKv(item);
      if ("顶层按钮".equals(element.getStr("text"))) {
        topIndex = intOf(element, "index");
        break;
      }
    }
    assertNotNull(topIndex);
    Kv result = data(service.clickElementByIndex(id, topIndex, "native", 3000));
    Kv hit = mapToKv(result.get("hit"));
    assertEquals("BUTTON", hit.getStr("tag"));
    assertTrue("命中元素要带文本,实际:" + hit, String.valueOf(hit.getStr("text")).contains("顶层按钮"));
    assertEquals("点击要真的生效", "top-clicked", innerText("#topFlag"));
    assertTrue(result.getBoolean("changed"));
  }

  // ==================== profile 登录态 ====================

  /** start 回执要说清「这份 profile 之前用过吗」—— engineHonored 回答不了这件事 */
  @Test
  public void startReceiptExplainsProfileLoginState() {
    Kv browser = service.browserInfo(id);
    assertNotNull("要能拿到浏览器信息", browser);
    assertNotNull("要报 profileSeenBefore", browser.get("profileSeenBefore"));
    assertNotNull("要报 profileNote", browser.getStr("profileNote"));
    assertTrue("profileNote 要说清登录态,实际:" + browser.getStr("profileNote"),
        browser.getStr("profileNote").contains("登录") || browser.getStr("profileNote").contains("profile"));
  }

  // ==================== 人机协同 ====================

  /** 多步待办:一次交办、逐步回填,status 从 partial 变成 answered */
  @Test
  public void humanInputSupportsMultipleSteps() {
    openMain();
    JSONObject steps = new JSONObject();
    com.alibaba.fastjson2.JSONArray list = new com.alibaba.fastjson2.JSONArray();
    JSONObject first = new JSONObject();
    first.put("prompt", "请扫码登录");
    first.put("selector", "#topBtn");
    list.add(first);
    JSONObject second = new JSONObject();
    second.put("prompt", "请输入短信验证码");
    second.put("selector", "#topFlag");
    list.add(second);
    steps.put("steps", list);
    steps.put("prompt", "需要两步");

    RespBodyVo created = actions.execute(id, "request_human_input", steps);
    Kv createdData = data(created);
    String requestId = createdData.getStr("requestId");
    assertNotNull(requestId);
    assertEquals(2, intOf(createdData, "stepCount"));
    assertEquals("两个待办都要列出来", 2, listOf(createdData.get("steps")).size());
    assertNotNull("要把图落盘并给出 URL", createdData.getStr("imageUrl"));
    assertNotNull("要给出服务端路径", createdData.getStr("imagePath"));

    // 只回填第一步 → partial
    JSONObject answerOne = new JSONObject();
    answerOne.put("requestId", requestId);
    answerOne.put("stepId", "s1");
    answerOne.put("answer", "已扫码");
    Kv partial = data(actions.execute(id, "submit_human_input", answerOne));
    assertEquals("partial", partial.getStr("status"));

    // 一次回填剩下的 → answered
    JSONObject rest = new JSONObject();
    rest.put("requestId", requestId);
    JSONObject answers = new JSONObject();
    answers.put("s2", "582913");
    rest.put("answers", answers);
    assertEquals("answered", data(actions.execute(id, "submit_human_input", rest)).getStr("status"));

    JSONObject fetch = new JSONObject();
    fetch.put("requestId", requestId);
    Kv finalState = data(actions.execute(id, "get_human_input", fetch));
    assertEquals("answered", finalState.getStr("status"));
    assertTrue("两步的答复都要在,实际:" + finalState, String.valueOf(finalState.get("steps")).contains("582913"));
  }

  /** 多步待办不回填时要说清还剩几步,而不是静默成功 */
  @Test
  public void humanInputMultiStepRequiresStepId() {
    JSONObject params = new JSONObject();
    com.alibaba.fastjson2.JSONArray list = new com.alibaba.fastjson2.JSONArray();
    JSONObject step = new JSONObject();
    step.put("prompt", "请在浏览器里完成支付");
    list.add(step);
    params.put("steps", list);
    params.put("prompt", "支付确认");
    String requestId = data(actions.execute(id, "request_human_input", params)).getStr("requestId");

    JSONObject answer = new JSONObject();
    answer.put("requestId", requestId);
    answer.put("answer", "好了");
    RespBodyVo response = actions.execute(id, "submit_human_input", answer);
    assertFalse("多步待办必须按 stepId 回填,直接给 answer 要说清楚", response.isOk());
    assertTrue("要提示用 stepId 或 answers,实际:" + response.getMsg(),
        response.getMsg().contains("stepId") && response.getMsg().contains("answers"));
    assertTrue("要把待办列出来,实际:" + response.getMsg(), response.getMsg().contains("s1"));
  }

  /** expiresAt 已经过去时要直接说清楚,别让人等一个永远不来的答复 */
  @Test
  public void humanInputExpiredAtIsHonest() {
    openMain();
    JSONObject params = new JSONObject();
    params.put("prompt", "请扫码");
    params.put("selector", "#topBtn");
    params.put("expiresAt", System.currentTimeMillis() - 1000);
    Kv created = data(actions.execute(id, "request_human_input", params));
    assertEquals("expired", created.getStr("status"));
    assertNotNull("要说清这个请求没有生效", created.getStr("note"));
  }

  /**
   * {@code request_human_input} 要能把跨域 iframe 里的元素截给人看
   *
   * <p>
   * 实测企业微信登录页的二维码就在 iframe 里:{@code ocr_image} 与 {@code upload_file} 都支持
   * {@code frame},唯独 {@code request_human_input} 不支持,于是 selector 只在顶层文档找、回执里
   * {@code imageUrl} 是空的 —— 而「把人叫来看 iframe 里的验证码/二维码」恰恰是最需要它的场景。
   */
  @Test
  public void humanInputCanTargetElementInsideFrame() {
    openMain();
    JSONObject params = new JSONObject();
    params.put("prompt", "请扫描 iframe 里的二维码");
    params.put("selector", "#innerBtn");
    params.put("frame", consoleBase);
    Kv withFrame = data(actions.execute(id, "request_human_input", params));
    assertNotNull("给了 frame 就必须把图截出来,实际:" + withFrame, withFrame.getStr("imageUrl"));
    assertNotNull(withFrame.getStr("imagePath"));
    assertTrue("截的应当是 iframe 里的元素,实际:" + withFrame.getStr("imageTarget"),
        String.valueOf(withFrame.getStr("imageTarget")).contains("frame"));

    // 不给 frame 时顶层文档里没有这个元素:要如实报 imageError,而不是静默给一张空图
    JSONObject noFrame = new JSONObject();
    noFrame.put("prompt", "顶层找不到这个元素");
    noFrame.put("selector", "#innerBtn");
    Kv topOnly = data(actions.execute(id, "request_human_input", noFrame));
    assertNotNull("定位不到时必须说清楚(这正是不传 frame 的后果)", topOnly.getStr("imageError"));
  }

  // ==================== 截图 / OCR ====================

  /** ocr_image 必须给出结构化结果(有没有装 OCR 语言包都要能回话,不能抛异常) */
  @Test
  public void ocrImageReturnsStructuredResult() {
    openMain();
    Kv result = data(service.ocrImage(id, null, null, "#topBtn", null, "zh-Hans-CN"));
    assertNotNull("要给 imagePath", result.getStr("imagePath"));
    assertNotNull("要给 imageUrl", result.getStr("imageUrl"));
    assertNotNull("无论成败都要有 ok 字段", result.get("ok"));
    if (!Boolean.TRUE.equals(result.get("ok"))) {
      // 这台机器没装 OCR 语言包:要明确说出来,并给出怎么装
      assertNotNull("失败时要给原因", result.getStr("error"));
      assertNotNull("失败时要给下一步", result.getStr("hint"));
      Assume.assumeTrue("本机没有可用的 Windows OCR 语言包,跳过内容断言", false);
    }
    assertNotNull("成功时要给识别文本", result.get("text"));
  }
}
