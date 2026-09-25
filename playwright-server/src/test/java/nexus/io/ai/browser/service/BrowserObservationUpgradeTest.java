package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

import nexus.io.model.body.RespBodyVo;

/**
 * 「智能体看到的东西与页面实情不符」的回归测试
 *
 * <p>
 * 三个坑都是 2026-09-25 在 DeepSeek 平台「充值 ¥10 + 开 ¥280 发票」时真踩到的,经过记在
 * {@code .agents/skills/deepseek-platform-topup-invoice/SKILL.md} 里:
 *
 * <ul>
 * <li><b>按文本点击点错了元素</b>:{@code click_element_by_text text=Submit} 命中的是开票页「Invoice Rules」
 * 那段说明文字 —— 因为第 4 条写着 "cannot be changed once <b>submit</b>ted",
 * 而 {@code getByText} 传字符串是**大小写不敏感的包含匹配**、老代码取的是文档顺序里的第一个候选。
 * 结果点了一个两千多字符的纯文本容器,回执 {@code ok:true} 而真正的 Submit 按钮一动没动。
 * 这里固定「完全相等优先于包含、可点击优先于不可点击、文本短的优先于长的」。</li>
 * <li><b>高亮层被截进图里</b>:收银台的微信支付二维码是 160×160 的 canvas,
 * {@code get_browser_state} 画的高亮层正好压在它上面,截出来的二维码是**彩色**的
 * (橙 {@code 255,165,0} / 钢蓝 {@code 70,130,180} / 绯红 {@code 220,20,60}),人拿手机怎么都扫不出来。
 * 文本里完全看不出这件事,只有把图给人看才会暴露。这里固定「截图期间高亮层必须隐藏,截完还原」。</li>
 * <li><b>自定义下拉的值与错误态读不出来</b>:{@code ds-select} 这类组件把真实取值放在**显示节点**
 * ({@code .ds-select__select})里、{@code input} 自己是空的,把 {@code ds-select--error} 挂在组件自己
 * 身上而不是外层 form-item 上 —— 于是 {@code get_form_state} 既把「已填好的必填项」报成空值,
 * 又把「字段已经报红」报成 {@code errorCount:0}。这里固定「读显示节点的值并标明来源、错误态看控件自己的类名」。</li>
 * </ul>
 */
public class BrowserObservationUpgradeTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;

  /**
   * fixture 页面:一个「文本包含 Submit 的长说明块 + 真正的 Submit 按钮」+ 一个被高亮层压住的白块
   * + 一个 ds-select 形态的自定义下拉
   */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>observation</title></head><body>
        <!-- 说明块:文档顺序在真按钮之前,而且包含 "submitted" —— 老逻辑会点它 -->
        <div class="rules" id="rules">1. The invoice title must match the real-name verification information.
          2. Invoices are processed within 7 working days.
          4. Invoice information cannot be changed once submitted.</div>
        <div role="button" class="ds-button" id="submitBtn"><span class="ds-button__content">Submit</span></div>
        <div id="clicked">none</div>

        <!-- 白块:高亮层会用纯红盖住它,截图里出现红色就说明高亮层被截进去了 -->
        <div id="qrbox" style="width:120px;height:120px;background:#ffffff"></div>

        <!-- ds-select 形态:真实取值在显示节点里,input 自己为空;error 类挂在组件自己身上 -->
        <div class="ant-form-item">
          <label for="titleInput">Invoice title</label>
          <div class="ds-select ds-select--filled ds-select--error">
            <div class="ds-select__select">上海某某信息科技有限公司</div>
            <input id="titleInput" class="ds-select__input" value="">
          </div>
        </div>

        <!-- 普通输入框:值来源应当是 DOM,不能因为引入了 display 取值就把所有字段都标成 display -->
        <div class="ant-form-item">
          <label for="plainInput">Email</label>
          <input id="plainInput" value="someone@example.com">
        </div>

        <script>
          document.getElementById('submitBtn').addEventListener('click', function () {
            document.getElementById('clicked').textContent = 'submitted';
          });
          // 照 buildDomTree 的形态造一层高亮覆盖层(fixed + 极高 z-index + pointer-events:none)
          var hl = document.createElement('div');
          hl.id = 'playwright-highlight-container';
          hl.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:2147483647';
          var box = document.createElement('div');
          box.style.cssText = 'position:absolute;left:0;top:0;width:800px;height:600px;background:#ff0000';
          hl.appendChild(box);
          document.body.appendChild(hl);
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-observation-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
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

  /** 每次用例前把页面加载回来(上一个用例可能已经点过按钮 / 改过 DOM) */
  private void open() {
    service.getInstance(id).page.navigate(base);
    service.getInstance(id).page.waitForTimeout(300);
  }

  // ==================== 按文本点击:包含匹配会点错元素 ====================

  /**
   * 说明块里那句 "once submitted" 就是当年误命中 Submit 的原因:
   * 必须点真正完全等于 Submit 的那个(或它的可点击祖先),而不是文档顺序里第一个"包含"它的容器。
   */
  @Test
  public void textClickPrefersExactMatchOverEarlierContainingContainer() {
    open();
    Kv result = data(service.clickElementByText(id, "Submit", null));
    assertEquals("要点中真正的按钮,而不是那段说明文字", "submitted",
        service.getInstance(id).page.locator("#clicked").innerText());

    assertNotNull("回执里要有真正命中的元素描述", result.get("outerHtml"));
    assertEquals("必须是完全相等匹配", "exact", result.get("textMatch"));
    assertEquals("命中元素应是那个按钮", Boolean.TRUE, result.get("textClickable"));
    assertEquals("命中元素的文本就该是 Submit", "Submit", String.valueOf(result.get("text")).trim());
  }

  /** 只有"包含"候选时也要能点中,并且回执要如实说明这是包含匹配、命中的是多长的文本 */
  @Test
  public void textClickReportsContainsMatchAndPicksShortestCandidate() {
    open();
    Kv result = data(service.clickElementByText(id, "cannot be changed", null));
    assertEquals("包含匹配要在回执里说明", "contains", result.get("textMatch"));
    assertEquals("这段说明文字本身不是可点击元素,回执要如实说", Boolean.FALSE, result.get("textClickable"));
    int length = ((Number) result.get("textLength")).intValue();
    assertTrue("应当选中短的那个候选(说明块),而不是把整页正文都算进去的长容器,实际长度:" + length,
        length > 0 && length < 300);
  }

  // ==================== 截图:高亮层不能进镜头 ====================

  /**
   * 纯红高亮层压在白块上:截图里只要出现红色,人拿到的二维码就是废的。
   * 同时固定「截完要还原」,不然高亮层会永久消失(它是给智能体看索引用的)。
   */
  @Test
  public void elementScreenshotExcludesHighlightOverlay() throws Exception {
    open();
    Kv result = data(service.getElementScreenshot(id, null, "#qrbox", null, null, null));
    String path = result.getStr("path");
    assertNotNull("元素截图默认落盘", path);

    BufferedImage image = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(Path.of(path))));
    assertNotNull("截图必须是一张能解码的图片", image);
    int red = 0;
    int white = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y) & 0xFFFFFF;
        if (rgb == 0xFF0000) {
          red++;
        } else if (rgb == 0xFFFFFF) {
          white++;
        }
      }
    }
    assertEquals("高亮层的纯红不该出现在截图里,实际有 " + red + " 个像素", 0, red);
    assertTrue("白块本身应当被截进来", white > 0);

    Object display = service.getInstance(id).page.evaluate(
        "() => { const c = document.getElementById('playwright-highlight-container');"
            + " return c ? c.style.display : 'missing'; }");
    assertEquals("截图后要把高亮层还原(置空 display),而不是留着隐藏状态", "", String.valueOf(display));
  }

  /** 自动截图(每次动作后落盘的那张)同样不能带上高亮层 —— 它是「事后回看画面」的唯一凭据 */
  @Test
  public void actionCaptureAlsoExcludesHighlightOverlay() throws Exception {
    open();
    // 自动截图挂在 ActionService 上(attachCapture),直接调 service 是拿不到的
    com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
    params.put("text", "Submit");
    Kv result = data(actions.execute(id, "click_element_by_text", params));
    String path = result.getStr("screenshot_path");
    assertNotNull("动作回执里应当带上这一张截图,实际:" + result, path);
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(Path.of(path))));
    assertNotNull(image);
    int red = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) & 0xFFFFFF) == 0xFF0000) {
          red++;
        }
      }
    }
    assertEquals("自动截图里也不该出现高亮层的纯红", 0, red);
  }

  // ==================== get_form_state:自定义下拉的值与错误态 ====================

  /**
   * ds-select 的取值在显示节点里:把「已填好的必填项」报成空值,调用方就会反复去填一个根本不用填的字段
   * (实测因此卡在提交校验上,还以为是自己没填进去)。
   */
  @Test
  public void formStateReadsValueOfCustomSelectFromDisplayNode() {
    open();
    Kv result = data(service.getFormState(id, null, false, 100));
    Map<String, Object> title = fieldById(result, "titleInput");
    assertNotNull("要能读到 ds-select 里的 input", title);
    assertEquals("自定义下拉的值要取显示节点里的文本,不能只报 input.value",
        "上海某某信息科技有限公司", title.get("value"));
    assertEquals("并且要标明这个值来自显示节点", "display", title.get("valueFrom"));
  }

  /** error 类挂在组件自己身上(而不是外层 form-item)时,同样要报出来 —— 否则 errorCount 是假的 0 */
  @Test
  public void formStateDetectsErrorClassOnTheControlItself() {
    open();
    Kv result = data(service.getFormState(id, null, false, 100));
    Map<String, Object> title = fieldById(result, "titleInput");
    assertNotNull(title);
    assertEquals("ds-select--error 挂在组件自己身上,也该算校验失败", Boolean.TRUE, title.get("invalid"));
  }

  /** 普通输入框的值来源仍然是 DOM,别因为引入了 display 取值就把所有字段都标成 display */
  @Test
  public void formStateMarksPlainInputsAsDomValues() {
    open();
    Kv result = data(service.getFormState(id, null, false, 100));
    Map<String, Object> plain = fieldById(result, "plainInput");
    assertNotNull("普通输入框要能读到", plain);
    assertEquals("someone@example.com", plain.get("value"));
    assertEquals("普通输入框的值来源是 DOM", "dom", plain.get("valueFrom"));
  }

  /** scope 限定仍然生效:限定到一段没有控件的正文时应当读到 0 个字段 */
  @Test
  public void formStateScopeSelectorLimitsFields() {
    open();
    assertEquals("#rules 里没有控件,应当读到 0 个字段", 0,
        ((Number) data(service.getFormState(id, "#rules", false, 100)).get("count")).intValue());
    assertTrue("整页至少要读到两个控件",
        ((Number) data(service.getFormState(id, null, false, 100)).get("count")).intValue() >= 2);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fieldById(Kv result, String id) {
    Object raw = result.get("fields");
    if (!(raw instanceof List)) {
      return null;
    }
    for (Object entry : (List<Object>) raw) {
      if (entry instanceof Map && id.equals(((Map<String, Object>) entry).get("id"))) {
        return (Map<String, Object>) entry;
      }
    }
    return null;
  }
}
