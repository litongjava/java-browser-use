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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

import nexus.io.model.body.RespBodyVo;

/**
 * 「盲操作」的回归测试:回执必须自己说出「这次取证不可信」
 *
 * <p>
 * 全部来自 2026-09-25 在 B 站投稿页发布视频时真踩到的坑(当时 {@code deepseek-browser-use} 驱动真实浏览器
 * 完成了投稿,但中间误判了两次)。三件事:
 *
 * <ul>
 * <li><b>截不出图却没人说</b>:该页面上 {@code page.screenshot()} 一张都没成功过,每次都等满 30 秒,
 * 于是**每条命令都白等 30 秒**,而调用方只看到一行 {@code screenshot_error},不知道自己已经完全没有
 * 画面可看 —— 页面上出现过整页白屏,靠文本判断不出来,直到人来说「屏幕都白了」。现在连续失败会熔断,
 * 并在每条回执里给出 {@code capture_degraded} 与原因。</li>
 * <li><b>选择器匹配 0 个却报「等待元素可操作超时」</b>:有一个选择器把 input 的父元素与兄弟关系搞错了,
 * 匹配 0 个,却报成 {@code ACTION_TIMEOUT}(「不能据此确定元素不存在」),于是先去查监听器、又去查遮挡,
 * 白花两轮。现在匹配 0 个当场以 {@code ELEMENT_NOT_FOUND} 失败,并说明「不是超时也不是被遮挡」。</li>
 * <li><b>探针不可信却当成结论</b>:点击回执里出现了 {@code observationComplete:false} +
 * {@code textLengthAfter:0} + {@code coveredBy:div.header},提示还说「很可能被遮挡物吃掉了」——
 * 而那次点击其实**生效了**。真实情况是页面正在整页重建,这一刻的探针什么都说明不了。现在会先等页面
 * 回来再下结论,等不到就明说 {@code probeTrustworthy:false} 并盖掉遮挡提示。</li>
 * </ul>
 *
 * <p>另外固定两条「挑对元素」的行为:同名控件优先挑**可见**的那个(隐藏弹窗里常还有一份),
 * 以及匹配到多个时把「匹配了几个、用第几个」写进回执。
 */
public class BrowserBlindnessUpgradeTest {

  private static PlaywrightService service;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;

  /**
   * fixture:隐藏弹窗里的同名 input(模拟 B 站的标签输入框)+ 一个会整页重建的按钮 + 一个永远白掉的按钮
   *
   * <p>顺序很关键:第一份 {@code input.tag} 在 {@code display:none} 的容器里,**先**出现在文档中,
   * 所以 {@code locator.first()} 会挑到它 —— 这正是「明明有元素却点不动」的成因。
   */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>blindness</title></head><body>
        <div id="stage">
          <div class="dlg" style="display:none">
            <input class="tag" id="hiddenTag" placeholder="按回车键创建标签">
          </div>
          <div class="live">
            <input class="tag" id="liveTag" placeholder="按回车键创建标签">
          </div>
          <div class="dlg" style="display:none">
            <input id="onlyHidden" value="">
          </div>
          <input id="typeHere" value="">
          <button id="rebuild" type="button">会整页重建的按钮</button>
          <button id="blankForever" type="button">点了就再也不回来的按钮</button>
          <div id="stageText">stage-before</div>
        </div>
        <script>
          // 点一下先把正文清空,2.5 秒后再把整页重建回来(SPA 整页重建的真实形态:
          // B 站投稿页打开封面弹窗 / 提交时就是这样,空白窗口有几秒)
          document.getElementById('rebuild').addEventListener('click', function () {
            document.body.innerHTML = '';
            setTimeout(function () {
              document.body.innerHTML =
                '<div id="rebuilt">rebuilt-ok</div>';
            }, 2500);
          });
          // 点了就白掉,永远不恢复:探针再也取不到有意义的内容
          document.getElementById('blankForever').addEventListener('click', function () {
            document.body.innerHTML = '';
          });
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-blindness-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    // 动作超时调小:老逻辑下「匹配 0 个」会等满这个值,用例要能分辨「当场失败」与「等满超时」
    System.setProperty(PlaywrightService.KEY_ACTION_TIMEOUT, "4000");
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
    System.clearProperty(PlaywrightService.KEY_ACTION_TIMEOUT);
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
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }

  /** 每次用例前把页面加载回来(上一个用例可能已经把正文清掉了) */
  private void open() {
    service.getInstance(id).page.navigate(base);
    service.getInstance(id).page.waitForTimeout(300);
  }

  // ==================== 选择器匹配 0 个:当场失败,别报成超时 ====================

  /**
   * 匹配 0 个必须是 {@code ELEMENT_NOT_FOUND},而不是等满超时后报 {@code ACTION_TIMEOUT}
   *
   * <p>老行为最误导的地方在于两者都报 {@code ACTION_TIMEOUT} + 一句「不能据此确定元素不存在」,
   * 于是调用方分不清「选择器写错了」还是「元素在、只是暂时不可点」,只能两条路都试一遍。
   */
  @Test
  public void selectorMatchingNothingFailsFastAsNotFound() {
    open();
    long begin = System.currentTimeMillis();
    RespBodyVo response = service.inputTextBySelector(id, "#tag-pre-wrp input.input-val", "值");
    long elapsed = System.currentTimeMillis() - begin;

    assertFalse("匹配 0 个应当失败", response.isOk());
    assertTrue("要给出 ELEMENT_NOT_FOUND,实际:" + response.getMsg(),
        response.getMsg().contains("ELEMENT_NOT_FOUND"));
    assertFalse("不能再报成「等待元素可操作超时」,实际:" + response.getMsg(),
        response.getMsg().contains("ACTION_TIMEOUT"));
    assertTrue("提示要说清这不是超时也不是被遮挡,实际:" + response.getMsg(),
        response.getMsg().contains("一个都没匹配到"));
    assertTrue("要给出下一步(get_element_count 复核数量),实际:" + response.getMsg(),
        response.getMsg().contains("get_element_count"));
    assertTrue("要当场失败,不能等满动作超时(4000ms),实际耗时 " + elapsed + "ms",
        elapsed < 2_000);
  }

  /** 点击类命令同样当场失败(以前这里会 await 满 4 秒) */
  @Test
  public void clickOnMissingSelectorAlsoFailsFast() {
    open();
    long begin = System.currentTimeMillis();
    RespBodyVo response = service.clickElementBySelector(id, ".no-such-thing-here");
    long elapsed = System.currentTimeMillis() - begin;

    assertFalse(response.isOk());
    assertTrue("实际:" + response.getMsg(), response.getMsg().contains("ELEMENT_NOT_FOUND"));
    assertTrue("要当场失败,实际耗时 " + elapsed + "ms", elapsed < 2_000);
  }

  // ==================== 同名控件:优先挑可见的那个 ====================

  /**
   * 隐藏副本排在文档前面时,必须挑到可见的那个
   *
   * <p>老行为取 {@code locator.first()},挑中的正是隐藏弹窗里那份,于是「填了没反应 / 报不可见」。
   */
  @Test
  public void visibleTwinIsPreferredOverHiddenCopy() {
    open();
    Kv result = data(service.inputTextBySelector(id, "input.tag", "可见的那个"));

    assertEquals("选择器确实匹配到两份(一份隐藏、一份可见)", 2, intOf(result, "matched"));
    assertEquals("要挑第 1 个(0 基)也就是那个可见的", 1, intOf(result, "chosenIndex"));
    assertNotNull("匹配到多个时要说明挑了哪个", result.getStr("selectorNote"));
    assertEquals("值要落到可见的那个输入框里", "可见的那个",
        service.getInstance(id).page.inputValue("#liveTag"));
    assertEquals("隐藏副本不该被写到", "", service.getInstance(id).page.inputValue("#hiddenTag"));
  }

  /**
   * 匹配到的元素**全都不可见**时不判死:auto 模式要靠 JS 设值把值填进去
   *
   * <p>{@code type=hidden} 字段、以及只能靠 JS 设值的隐藏控件都走这条,当场判死会把本来能成的命令挡掉。
   * 但回执必须写明「可见的 0 个」,否则调用方会以为选的是一份正常控件。
   */
  @Test
  public void allHiddenMatchesStillReachJsFallbackAndSaySo() {
    open();
    Kv result = data(service.inputTextBySelector(id, "#onlyHidden", "隐藏值"));

    assertEquals("js", result.getStr("mode"));
    assertEquals(false, result.get("committed"));
    assertEquals("要写明可见的有 0 个", 0, intOf(result, "visibleMatched"));
    assertNotNull("要解释为什么选中了隐藏副本、以及为什么仍然填得进去",
        result.getStr("hiddenMatchNote"));
    assertEquals("值本身要真的写进去", "隐藏值",
        service.getInstance(id).page.inputValue("#onlyHidden"));
  }

  // ==================== 探针不可信:先等,再如实说 ====================

  /**
   * 点击过程中页面整页重建:要等到正文恢复再下结论,并把「重建过」写进回执
   *
   * <p>这正是 B 站「打开封面弹窗 / 点立即投稿」的形态:动完页面先空掉再长回来。老逻辑在空掉的
   * 那一瞬取探针,于是 {@code changed:false} + 一个并不存在的 {@code coveredBy},把一次**成功**的
   * 点击报成「很可能被遮挡物吃掉了」。
   */
  @Test
  public void blankMidFlightIsWaitedForAndStillJudged() {
    open();
    Kv result = data(service.clickElementBySelector(id, "#rebuild"));

    assertTrue("要说出「观察窗口内页面白过」", Boolean.TRUE.equals(result.get("page_appears_blank")));    assertTrue("要等到正文恢复", Boolean.TRUE.equals(result.get("probeRecovered")));
    assertTrue("恢复之后取证才可信", Boolean.TRUE.equals(result.get("probeTrustworthy")));
    assertTrue("恢复之后 observationComplete 应当是 true", Boolean.TRUE.equals(result.get("observationComplete")));
    assertNotNull(result.getStr("probeNote"));
    assertTrue("页面确实重建了(正文变了),这次点击是生效的", Boolean.TRUE.equals(result.get("changed")));
    assertEquals("rebuilt-ok", service.getInstance(id).page.evaluate(
        "() => { const el = document.getElementById('rebuilt'); return el ? el.textContent : null; }"));
  }

  /**
   * 页面白掉且不恢复:必须明说「这次取证不可信」,并且**不许**再给遮挡结论
   *
   * <p>老逻辑在这种情形下会把 {@code changed:false} 和 {@code coveredBy} 一起交出去,还附一句
   * 「很可能被遮挡物吃掉了」—— 两个字段都是假象,提示还把方向带偏。现在要盖掉遮挡提示,
   * 改成一条能直接执行的自证路径。
   */
  @Test
  public void neverRecoveringBlankIsReportedAsUntrustworthyEvidence() {
    open();
    Kv result = data(service.clickElementBySelector(id, "#blankForever"));

    assertFalse("取证不可信时 probeTrustworthy 必须是 false",
        Boolean.TRUE.equals(result.get("probeTrustworthy")));
    assertFalse("observationComplete 也要如实为 false",
        Boolean.TRUE.equals(result.get("observationComplete")));
    String hint = result.getStr("hint");
    assertNotNull("必须给出提示", hint);
    assertTrue("提示要说清取证不可信,实际:" + hint, hint.contains("取证不可信"));
    assertTrue("提示必须给自证路径,实际:" + hint, hint.contains("get_browser_state"));
    assertFalse("不许再把「被遮挡」当结论交出去,实际:" + hint, hint.contains("遮挡物吃掉"));
  }

  /** 正常页面上的点击不该被误标成「取证不可信」(否则这条新信号会被自己用滥) */
  @Test
  public void normalClickKeepsEvidenceTrustworthy() {
    open();
    Kv result = data(service.clickElementBySelector(id, "#typeHere"));

    assertTrue("正常页面上取证应当可信", Boolean.TRUE.equals(result.get("probeTrustworthy")));
    assertFalse("没白过就不该有 page_appears_blank", Boolean.TRUE.equals(result.get("page_appears_blank")));
  }

  // ==================== send_keys:按键发给了谁 ====================

  /**
   * 回执要说清按键时焦点在哪
   *
   * <p>实测「填完标签输入框 → send_keys Enter」会悄悄什么都不做(焦点已经不在那个 input 上),
   * 而回执只有一句 {@code ok:true};调用方只能靠「标签没多出来」反推,白跑一轮。
   */
  @Test
  public void sendKeysReportsFocusedElement() {
    open();
    data(service.clickElementBySelector(id, "#typeHere"));
    Kv result = data(service.sendKeys(id, "End"));

    Object focused = result.get("focused");
    assertTrue("要回报焦点元素,实际:" + focused, focused instanceof Kv);
    assertEquals("INPUT", ((Kv) focused).getStr("tag"));
    assertEquals("typeHere", ((Kv) focused).getStr("id"));
  }

  /** 焦点不在任何输入控件里时要给出可操作的提示(而不是让调用方自己猜为什么 Enter 没反应) */
  @Test
  public void sendKeysWarnsWhenFocusIsOnBody() {
    open();
    service.getInstance(id).page.evaluate("() => { if (document.activeElement) document.activeElement.blur(); }");
    Kv result = data(service.sendKeys(id, "Enter"));

    Object focused = result.get("focused");
    assertTrue(focused instanceof Kv);
    assertTrue("要认出焦点在 body 上", Boolean.TRUE.equals(((Kv) focused).get("isBody")));
    String note = result.getStr("focusNote");
    assertNotNull("要提示「先 click 目标输入框再送键」", note);
    assertTrue(note.contains("click"));
  }

  // ==================== 截不出图:熔断,并且自己说出来 ====================

  /**
   * 自动截图连续失败要熔断,并在回执里明说「你现在看不到画面」
   *
   * <p>实测 B 站投稿页上 {@code page.screenshot()} 一张都没成功过,每次都等满 30 秒 —— 于是**每条命令
   * 都白等 30 秒**,而回执里只有一行 {@code screenshot_error},看起来像「这次运气不好」。调用方不知道
   * 自己已经完全没有画面可看,页面上出现整页白屏也判断不出来。这里把「连续失败 → 熔断 → 明说」
   * 整条链固定住:关键是**第三次不能再白等**,而且要给出 {@code capture_degraded} 与原因。
   *
   * <p>用 {@code browser.capture.timeoutMs=1} 把截图逼失败:1 毫秒内不可能完成一次真实的截图往返,
   * 这样不必依赖任何站点就能稳定复现「截不出图」。
   */
  @Test
  public void repeatedCaptureFailuresTripBreakerAndSaySo() {
    open();
    BrowserInstance inst = service.getInstance(id);
    System.setProperty(PlaywrightService.KEY_CAPTURE_TIMEOUT, "1");
    System.setProperty(PlaywrightService.KEY_CAPTURE_FAIL_THRESHOLD, "2");
    System.setProperty(PlaywrightService.KEY_CAPTURE_COOLDOWN, "60000");
    int previousSeq = inst.captureSeq.get();
    try {
      inst.captureFailures.set(0);
      inst.captureCooldownUntil = 0;
      inst.captureFailureReason = null;

      Kv first = service.capture(inst);
      assertNotNull("1 毫秒超时下截图必须失败,否则这条用例就失去意义了", first.get("screenshot_error"));
      assertFalse("第一次失败还不该熔断", Boolean.TRUE.equals(first.get("capture_degraded")));

      Kv second = service.capture(inst);
      assertTrue("到达阈值(2 次)要熔断", Boolean.TRUE.equals(second.get("capture_degraded")));
      assertNotNull("熔断必须带原因,不能只说「停了」", second.getStr("capture_note"));
      assertTrue("要说清这期间没有任何画面留档,不能只凭文本断言页面正常",
          second.getStr("capture_note").contains("没有") && second.getStr("capture_note").contains("request_human_input"));

      long begin = System.currentTimeMillis();
      Kv third = service.capture(inst);
      long elapsed = System.currentTimeMillis() - begin;
      assertTrue("熔断期间应当立刻返回,不再白等一次超时,实际 " + elapsed + "ms", elapsed < 500);
      assertTrue("熔断期间仍要带 capture_degraded", Boolean.TRUE.equals(third.get("capture_degraded")));
      assertNull("熔断期间没有真的去截图,就不该再报 screenshot_error", third.get("screenshot_error"));
      assertEquals("熔断期间序号照常前进(留档位置不跳号)", previousSeq + 3, inst.captureSeq.get());
    } finally {
      System.clearProperty(PlaywrightService.KEY_CAPTURE_TIMEOUT);
      System.clearProperty(PlaywrightService.KEY_CAPTURE_FAIL_THRESHOLD);
      System.clearProperty(PlaywrightService.KEY_CAPTURE_COOLDOWN);
      // 别把熔断状态留给后面的用例(用例之间不该有这种隐形依赖)
      inst.captureFailures.set(0);
      inst.captureCooldownUntil = 0;
      inst.captureFailureReason = null;
    }
  }

  // ==================== 导航伪故障的判据(纯函数) ====================

  /**
   * 「是不是已经跳到目标地址了」的判据
   *
   * <p>实测 {@code go_to_url} 会被 Playwright 事件分发的伪故障砸中而报失败,但地址栏其实**已经是目标**
   * (随后 {@code get_url} 读到的就是目标地址)。判据太松(只看主机)会把同站不同页也判成成功,
   * 太紧(全串比较)又会被站点自动追加的会话参数挡住,所以这里把它固定住。
   */
  @Test
  public void sameLocationIgnoresSessionParamsButNotPaths() {
    assertTrue("末尾斜杠不算差别",
        PlaywrightService.sameLocation("https://a.com/b/", "https://a.com/b"));
    assertTrue("站点自动补的会话参数不算差别",
        PlaywrightService.sameLocation("https://www.bilibili.com/video/BV1EPhy6TEEt/",
            "https://www.bilibili.com/video/BV1EPhy6TEEt/?vd_source=69e3cff470444b21e8c322dddec00def"));
    assertTrue("fragment 也不算",
        PlaywrightService.sameLocation("https://a.com/b", "https://a.com/b#top"));
    assertFalse("同站不同页绝不能算到达",
        PlaywrightService.sameLocation("https://a.com/b", "https://a.com/c"));
    assertFalse("不同主机不算到达",
        PlaywrightService.sameLocation("https://a.com/b", "https://b.com/b"));
    assertFalse("拿不到当前地址时不算到达(null 安全)",
        PlaywrightService.sameLocation("https://a.com/b", null));
  }
}
