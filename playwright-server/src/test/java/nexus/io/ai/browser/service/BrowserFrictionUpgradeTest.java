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
 * {@code skills/railway-12306-ticket/SKILL.md} 里:
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
        <script>
          document.getElementById('qr_submit_id').addEventListener('click', function () {
            document.getElementById('confirmBox').remove();
            document.getElementById('mark').remove();
            document.getElementById('result').textContent = 'confirmed';
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
}
