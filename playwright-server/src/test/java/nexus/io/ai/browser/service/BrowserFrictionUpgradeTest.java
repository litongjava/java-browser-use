package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
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

import nexus.io.model.body.RespBodyVo;

/**
 * 「实测踩到的坑」的回归测试
 *
 * <p>
 * 两个坑都不是猜出来的,是 2026-09-24 在 12306 上买武汉→民权北的车票时真踩到的,原文记在
 * {@code .agents/skills/railway-12306-ticket/SKILL.md} 里:
 *
 * <ul>
 * <li><b>弹窗按钮只认框架类名</b>:12306 的「确认」是 {@code <a id="qr_submit_id" class="btn92s">确认</a>},
 * 既不是 {@code button} 也没有 {@code role=button},{@code get_modals} 给出 {@code buttons:[]},
 * {@code close_modal} 连确认都点不到 —— 只能退回手写 JS。这里用同样的形态固定住。</li>
 * <li><b>页头混进弹窗清单</b>:同一页 {@code get_modals} 报了 5 个「弹窗」,其中 4 个是页头
 * ({@code .header},position:fixed + 高 z-index)和两个静态提示条;{@code top} 还正好落在页头上。
 * 这里固定「整页遮罩不算弹窗、没有按钮也没有标题的悬浮条不算弹窗、top 优先取真的挡着页面的那个」。</li>
 * <li><b>响应体拿到手就没了</b>:浏览器只短暂保留响应体,实测 7 秒前的 XHR 再取 body 就是
 * {@code No resource with given identifier found};而且一导航就彻底没了。这里固定「收到响应时就抄一份,
 * 之后(哪怕已经跳走)仍然读得到」。</li>
 * </ul>
 */
public class BrowserFrictionUpgradeTest {

  private static PlaywrightService service;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;

  /** 一条取数接口:响应体里有个一眼能认出来的标记 */
  private static final String API_BODY = "{\"token\":\"abc123\",\"total\":7}";

  /** 12306 式页面:sticky 页头 + 整页遮罩 + 一个只有 <a class=btn…> 按钮的确认框 + 一条 XHR */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>friction</title>
      <style>
        /* 页头:fixed + 高 z-index + 面积够大 —— 老逻辑会把它算成「弹窗」 */
        .header { position: fixed; left: 0; top: 0; width: 100%; height: 90px; z-index: 2000; background: #eee; }
        .mark { position: fixed; left: 0; top: 0; width: 100%; height: 100%; z-index: 9000; background: rgba(0,0,0,.4); }
        .up-box { position: fixed; left: 300px; top: 200px; width: 420px; height: 220px; z-index: 9100; background: #fff; }
        .tips-txt { position: static; width: 600px; height: 60px; }
      </style></head><body>
        <div class="header">中国铁路12306 页头,不是弹窗</div>
        <div class="tips-txt">静态提示条,也不是弹窗</div>
        <div class="mark" id="mark"></div>
        <div class="up-box" id="confirmBox">
          <div class="up-box-title">请核对以下信息</div>
          <a id="back_edit_id" class="btn92" href="javascript:;">返回修改</a>
          <a id="qr_submit_id" class="btn92s" href="javascript:;">确认</a>
        </div>
        <div id="result">idle</div>
        <div id="xhrOut">none</div>
        <input type="file" id="swapFile">
        <div id="swapFlag">none</div>
        <button id="growBtn">点我就改页面</button>
        <div id="growOut">none</div>
        <script>
          document.getElementById('qr_submit_id').addEventListener('click', function () {
            document.getElementById('confirmBox').remove();
            document.getElementById('mark').remove();
            document.getElementById('result').textContent = 'confirmed';
          });
          // 模拟 SPA 上传组件:收下文件之后把原来的 input **换掉**(企业微信的上传组件就是这个行为)。
          // 老版本的回读走 Locator,此时节点已脱离文档,Locator.evaluate 会一直等到 30 秒超时。
          document.getElementById('swapFile').addEventListener('change', function (e) {
            var old = document.getElementById('swapFile');
            var fresh = document.createElement('input');
            fresh.type = 'file';
            fresh.id = 'swappedIn';
            old.parentNode.replaceChild(fresh, old);
            document.getElementById('swapFlag').textContent = 'replaced:' + e.target.files.length;
          });
          document.getElementById('growBtn').addEventListener('click', function () {
            document.getElementById('growOut').textContent = 'grown';
          });
          fetch('/api/data').then(function (r) { return r.text(); }).then(function (t) {
            document.getElementById('xhrOut').textContent = t;
          });
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-friction-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    ChromeBrowser.resetForTests();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      boolean api = exchange.getRequestURI().getPath().equals("/api/data");
      String body = api ? API_BODY : PAGE;
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type",
          api ? "application/json; charset=utf-8" : "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    service = new PlaywrightService();
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() {
    if (id != null) {
      try {
        service.close(id);
      } catch (RuntimeException ignored) {
        // 收尾,关不掉不影响结论
      }
    }
    if (server != null) {
      server.stop(0);
    }
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
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
    return 0;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> modals(Kv probe) {
    Object raw = probe.get("modals");
    return raw instanceof List ? (List<Map<String, Object>>) raw : List.of();
  }

  private static Map<String, Object> modalWithId(Kv probe, String id) {
    for (Map<String, Object> modal : modals(probe)) {
      if (id.equals(modal.get("id"))) {
        return modal;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> topOf(Kv probe) {
    Object top = probe.get("top");
    return top instanceof Map ? (Map<String, Object>) top : null;
  }

  /** 每次用例前重新把页面加载回来(上一个用例可能把确认框点掉了) */
  private void open() {
    service.getInstance(id).page.navigate(base);
    service.getInstance(id).page.waitForTimeout(400);
  }

  // ==================== 弹窗按钮:只认框架类名是不够的 ====================

  /** 12306 的「确认」是 <a class="btn92s">:必须在 buttons 里,否则 close_modal 没法提交 */
  @Test
  public void nonFrameworkAnchorIsReportedAsButton() {
    open();
    Kv probe = data(service.getModals(id));
    Map<String, Object> box = modalWithId(probe, "confirmBox");
    assertNotNull("确认框应当被 get_modals 认出来,当前:" + modals(probe), box);
    @SuppressWarnings("unchecked")
    List<String> buttons = (List<String>) box.get("buttons");
    assertTrue("确认/返回修改 都要出现在 buttons 里,实际:" + buttons,
        buttons != null && buttons.contains("确认") && buttons.contains("返回修改"));
    assertEquals("its own class name should be kept for close_modal(which=class:…)", "up-box",
        String.valueOf(box.get("className")).split(" ")[0]);
    assertTrue("有按钮有标题的悬浮框应当判为 blocking", Boolean.TRUE.equals(box.get("blocking")));
  }

  /** close_modal 指哪个按钮就点哪个:点「确认」要走真实鼠标事件,而且要校验弹窗真的没了 */
  @Test
  public void closeModalClicksNamedAnchorButton() {
    open();
    Kv result = data(service.closeModal(id, "class:up-box", null, "确认"));
    assertTrue("close_modal 应当报告关掉了,实际:" + result, Boolean.TRUE.equals(result.get("closed")));
    assertEquals("关掉后不该还有弹窗", 0, intOf(result, "countAfter"));
    assertEquals("点「确认」要真的触发页面事件", "confirmed",
        String.valueOf(service.getInstance(id).page.evaluate(
            "() => document.getElementById('result') ? document.getElementById('result').textContent : null")));
  }

  // ==================== 弹窗清单:页头/遮罩/提示条不该混进来 ====================

  /**
   * 页头、整页遮罩、静态提示条都不是「要关的弹窗」
   *
   * <p>
   * 老逻辑在 12306 上给出 {@code count:5},其中 4 条是这类东西,而 {@code top} 正好是页头 ——
   * 「按 top 去关弹窗」会去点一个页头。
   */
  @Test
  public void pageChromeIsNotReportedAsModal() {
    open();
    Kv probe = data(service.getModals(id));
    for (Map<String, Object> modal : modals(probe)) {
      String className = String.valueOf(modal.get("className"));
      assertFalse("sticky 页头不该被当成弹窗:" + className, className.startsWith("header"));
      assertFalse("静态提示条不该被当成弹窗:" + className, className.startsWith("tips-txt"));
      assertFalse("整页遮罩不该被当成弹窗:" + className, className.startsWith("mark"));
    }
    assertEquals("这一页只有一个真弹窗", 1, intOf(probe, "count"));
    assertEquals("真弹窗是启发式命中的,但它是 blocking 的", 1, intOf(probe, "countBlocking"));
    assertEquals("没有任何一条是框架选择器命中的", 0, intOf(probe, "countStrict"));
    Map<String, Object> top = topOf(probe);
    assertEquals("top 必须落在真的挡着页面的那个弹窗上", "confirmBox", top.get("id"));
    assertTrue("topIsBlocking 要如实回报", Boolean.TRUE.equals(probe.get("topIsBlocking")));
  }

  /** 遮罩没了(弹窗关掉之后)就不该再报弹窗 */
  @Test
  public void closingModalClearsTheList() {
    open();
    data(service.closeModal(id, "class:up-box", null, "确认"));
    Kv after = data(service.getModals(id));
    assertEquals("关掉之后 count 要归零", 0, intOf(after, "count"));
    assertNull("没有弹窗时 top 必须是 null", after.get("top"));
  }

  // ==================== 响应体:收到就抄一份,之后还能读 ====================

  /**
   * XHR 的响应体在很久之后(甚至已经跳走之后)仍然读得到
   *
   * <p>
   * 实测的失败形态:同一条 {@code queryOrderWaitTime} 请求,7 秒后再取 body 就是
   * {@code No resource with given identifier found};12306 的下单结果因此只能靠猜。
   */
  @Test
  public void responseBodySurvivesNavigation() throws Exception {
    open();
    // 等 XHR 被记下来(记的是响应,不是请求)
    long deadline = System.currentTimeMillis() + 5_000;
    boolean recorded = false;
    while (System.currentTimeMillis() < deadline && !recorded) {
      Kv requests = data(service.getRequests(id, "/api/data"));
      Object list = requests.get("requests");
      recorded = list instanceof List && !((List<?>) list).isEmpty();
      if (!recorded) {
        Thread.sleep(150);
      }
    }
    assertTrue("这条 XHR 应当被记下来", recorded);

    // 跳走:浏览器会把上一个文档的响应体释放掉 —— 这正是老实现读不到 body 的场景
    service.getInstance(id).page.navigate(base + "/other");
    service.getInstance(id).page.waitForTimeout(1200);

    Kv body = data(service.getResponseBody(id, "/api/data", null, null));
    assertTrue("响应体应当读得到,实际:" + body, Boolean.TRUE.equals(body.get("bodyAvailable")));
    assertTrue("读到的应当是缓存那份", Boolean.TRUE.equals(body.get("bodyFromCache")));
    assertTrue("内容要完整:" + body.get("body"), String.valueOf(body.get("body")).contains("abc123"));
    assertNotNull("requestId 才是回查重复 URL 的凭据", body.get("requestId"));
  }

  /**
   * requestId 要能不被打码地回填给 get_response_body
   *
   * <p>
   * 这条在客户端侧(脱敏规则)也有对应测试,这里确认服务端回的值本身是可用的。
   */
  @Test
  public void responseBodyCanBeReadByRequestId() throws Exception {
    open();
    long deadline = System.currentTimeMillis() + 5_000;
    String requestId = null;
    while (System.currentTimeMillis() < deadline && requestId == null) {
      Kv requests = data(service.getRequests(id, "/api/data"));
      Object list = requests.get("requests");
      if (list instanceof List && !((List<?>) list).isEmpty()) {
        requestId = String.valueOf(((Map<?, ?>) ((List<?>) list).get(0)).get("requestId"));
      } else {
        Thread.sleep(150);
      }
    }
    assertNotNull("要先拿到 requestId", requestId);
    Kv body = data(service.getResponseBody(id, null, null, null, requestId));
    assertTrue("按 requestId 也要读得到响应体", Boolean.TRUE.equals(body.get("bodyAvailable")));
    assertEquals("requestId 要原样回带", requestId, String.valueOf(body.get("requestId")));
  }

  // ==================== 上传回读:框架把 input 换掉之后不能等 30 秒超时 ====================

  /**
   * 上传后元素被框架替换:回读要如实说明,**不能**报成 {@code Timeout 30000ms exceeded}
   *
   * <p>
   * 实测企业微信的授权书上传就是这样:{@code setInputFiles} 之后组件把原 input 换掉,
   * 老版本回读走 {@code Locator.evaluate},它会在节点脱离文档后一直等到默认 30 秒超时才抛,
   * 于是「上传成功」被写成 {@code readbackError: Timeout 30000ms exceeded.} + {@code consumed: unknown}
   * —— 一次成功的操作看起来像失败了,调用方一重试就可能传两份。
   *
   * <p>这里断言两件事:① 回读没有超时错误;② 页面上确实收到了文件(即上传本身是成功的)。
   */
  @Test
  public void uploadReadbackSurvivesInputReplacement() {
    open();
    long startedAt = System.currentTimeMillis();
    RespBodyVo response = service.uploadFileInline(id, null, "#swapFile", "授权书.pdf", "application/pdf",
        java.util.Base64.getEncoder().encodeToString("%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8)),
        null, 5_000);
    long elapsed = System.currentTimeMillis() - startedAt;
    Kv result = data(response);

    assertNull("不该再有 30 秒超时的回读错误,实际:" + result.getStr("readbackError"),
        result.getStr("readbackError"));
    assertTrue("上传本身必须是最快的:回读不该把一次上传拖成几十秒(实测 " + elapsed + "ms)",
        elapsed < 20_000);
    assertEquals("页面要真的收到了这个文件", "replaced:1", innerText("#swapFlag"));
    assertTrue("回执要说明清楚「回读时元素已被换掉」,而不是让人以为上传失败,实际:"
        + result.getStr("readbackNote"), result.getStr("readbackNote") != null);
  }

  // ==================== 抛异常 ≠ 没生效:点下载类按钮的假失败 ====================

  /**
   * 「动作抛了异常、但页面已经变了」必须回 {@code ok:true} + 明确的 warning
   *
   * <p>
   * 实测点「下载合同」时 {@code locator.click(...)} 抛 {@code Object doesn't exist: response@…},
   * 点 Chrome 内置 PDF 查看器的下载按钮时 {@code page.mouse().click(...)} 抛 {@code artifact@…},
   * 而**文件都已经落盘**。老写法回 {@code ok:false},调用方看到失败就会重试 ——
   * 重试的代价是重复下载 / 重复提交。真实触发条件(浏览器内置查看器)没法在 fixture 里复现,
   * 所以这里直接对 {@code actionErrorOrEffect} 的输入组合做断言,把契约固定住。
   */
  @Test
  public void actionErrorIsNotFailureWhenPageChanged() {
    open();
    BrowserInstance inst = service.getInstance(id);
    Kv before = (Kv) PlaywrightService.stateProbe(inst, null);
    // 让页面真的发生变化 —— 模拟「下载已经发生 / 页面已经跳走」
    service.clickElementBySelector(id, "#growBtn", null, null);
    assertEquals("grown", innerText("#growOut"));

    RespBodyVo changed = PlaywrightService.actionErrorOrEffect("click_element_by_selector", before, inst, null,
        new com.microsoft.playwright.PlaywrightException("Object doesn't exist: response@deadbeef"),
        "click_element_by_selector 失败：Object doesn't exist: response@deadbeef");
    assertTrue("页面确实变了,就不能报失败", changed.isOk());
    Kv data = data(changed);
    assertTrue("要给出 warning 说明「很可能已生效、先读状态再重试」,实际:" + data.getStr("warning"),
        String.valueOf(data.getStr("warning")).contains("不要直接重试"));
    assertNotNull("原始异常要原样留下供追查", data.getStr("actionError"));
    assertEquals("要标出这次回执是异常路径产生的", Boolean.TRUE, data.getBoolean("changedByActionError"));

    // 页面没变时照旧报失败:不能把「抛异常」一律洗成成功
    Kv quietBefore = (Kv) PlaywrightService.stateProbe(inst, null);
    RespBodyVo unchanged = PlaywrightService.actionErrorOrEffect("click_element_by_selector", quietBefore, inst,
        null, new com.microsoft.playwright.PlaywrightException("boom"), "click_element_by_selector 失败：boom");
    assertFalse("页面没变就该照旧失败", unchanged.isOk());
    assertTrue(unchanged.getMsg().contains("boom"));

    // **超时 / 元素不稳定这类异常即使页面变了也不能放宽**:那是「动作根本没做完」。
    // 实测就是这个区别救了回归用例 —— 一直在动的元素会持续改变 DOM 指纹,只看「页面变了」就判成功的话,
    // 原生点击超时会被误判成点击成功(BrowserInspectionUpgradeTest#movingElementFallsBackToRealMouse)。
    Kv movingBefore = (Kv) PlaywrightService.stateProbe(inst, null);
    service.clickElementBySelector(id, "#growBtn", null, null); // 制造一次真实的 DOM 变化
    RespBodyVo timeout = PlaywrightService.actionErrorOrEffect("click_element_by_selector", movingBefore, inst,
        null, new com.microsoft.playwright.PlaywrightException(
            "Locator.click: Timeout 700ms exceeded. waiting for element to be stable"),
        "click_element_by_selector 失败：超时");
    assertFalse("超时不属于「句柄失效」,页面变了也只能算失败", timeout.isOk());
  }

  // ==================== 参数名混淆要给提示 ====================

  /**
   * {@code switch_tab} 的参数叫 {@code pageIndex},而回执里的字段叫 {@code index}
   *
   * <p>照着回执传 {@code index} 只会得到一句「缺少参数 pageIndex」,不提示两个名字的关系,
   * 于是要在两个名字之间再来回猜一轮。这里要求把对应关系直接写出来。
   */
  @Test
  public void switchTabExplainsParamNameConfusion() {
    open();
    ActionService actions = new ActionService(service);
    com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
    params.put("index", 0);
    RespBodyVo response = actions.execute(id, "switch_tab", params);
    assertFalse("传错名字本来就该失败", response.isOk());
    String msg = response.getMsg();
    assertTrue("要指出「你传的是 index」,实际:" + msg, msg.contains("你传的是 index"));
    assertTrue("要说明回执里的字段名和参数名不一样,实际:" + msg, msg.contains("pageIndex"));

    // 参数真正缺失(什么都没传)时,保持原来的简洁措辞,不要硬塞一段解释
    RespBodyVo empty = actions.execute(id, "switch_tab", new com.alibaba.fastjson2.JSONObject());
    assertFalse(empty.isOk());
    assertEquals("缺少参数 pageIndex", empty.getMsg().replaceFirst("^switch_tab 失败：", ""));
  }

  private static String innerText(String selector) {
    return String.valueOf(service.getInstance(id).page.evaluate(
        "() => { const el = document.querySelector('" + selector + "');"
            + " return el ? el.textContent : null; }"));
  }
}
