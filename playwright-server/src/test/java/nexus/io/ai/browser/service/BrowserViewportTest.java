package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;

import org.junit.After;
import org.junit.Test;

import com.microsoft.playwright.BrowserType;

/**
 * 窗口尺寸与页面视口策略({@code browser.viewport})的语义
 *
 * <p>
 * 盯住四件事,它们都会「改错了也编译得过」:
 * <ol>
 * <li><b>高度取可用工作区、不是整块屏幕</b>:这是任务栏那条 bug(窗口底边压到任务栏底下,压住的正是
 * 页面底部)。用 {@code getScreenSize()} 会算出一个比工作区高 48px 的窗口,而代码看起来毫无问题;</li>
 * <li><b>有头默认跟随窗口</b>:以前视口是钉死的,于是页面按钉死的尺寸排版、窗口按自己的尺寸显示,
 * 每页底部约 13% 渲染在窗口外面而截图照样拍进去 —— 「截图」与「所见」不是一回事;</li>
 * <li><b>无头不能跟随窗口</b>:无头没有真实窗口,配了 window 必须退回固定视口,并且把原因说出来
 * (静默退回会让人以为配置生效了);</li>
 * <li><b>配置写错要直接报错并列出可选值</b>:与 {@code browser.type} 的做派一致 —— 悄悄用一个默认值
 * 会让「我明明配了 1440x900」变成查不出来的问题。</li>
 * </ol>
 */
public class BrowserViewportTest {

  /** 一个与真实屏幕无关的稳定尺寸,用来验证「固定视口就等于这个尺寸」 */
  private static final Dimension WINDOW = new Dimension(1484, 1019);

  @After
  public void tearDown() {
    System.clearProperty(BrowserViewport.KEY_VIEWPORT);
    ChromeBrowser.resetForTests();
  }

  // ==================== 窗口尺寸:任务栏那条 bug ====================

  /**
   * 高度必须是**可用工作区**,不是整块屏幕
   *
   * <p>Windows 上任务栏占掉的高度就落在这两个值之间(实测这台机器 1067 vs 1019)。这条断言在
   * 没有显示设备的机器上会被跳过 —— 那种机器上根本量不到工作区。
   */
  @Test
  public void heightComesFromTheWorkingAreaNotTheWholeScreen() {
    int screenHeight = screenHeightOrSkip();
    if (screenHeight < 0) {
      return;
    }
    int windowHeight = BrowserViewport.windowSize().height;
    assertTrue("窗口高度不该超过屏幕高度", windowHeight <= screenHeight);

    Rectangle working = workingAreaOrSkip();
    if (working == null) {
      return;
    }
    assertEquals("高度必须等于可用工作区高度(任务栏不算在内)", working.height, windowHeight);
    if (working.height < screenHeight) {
      assertTrue("这台机器有任务栏,窗口高度就该严格小于屏幕高度(老算法在这里会失败)",
          windowHeight < screenHeight);
    }
  }

  /** 宽度按可用工作区的 28/32 算,与引擎无关 */
  @Test
  public void widthIsTwentyEightThirtySecondsOfTheUsableArea() {
    Rectangle usable = BrowserViewport.usableBounds();
    assertEquals((usable.width / 32) * 28, BrowserViewport.windowSize().width);
  }

  /** 量不到屏幕时给一个能打开的窗口,而不是抛异常把 start 弄挂 */
  @Test
  public void usableBoundsNeverReturnsSomethingUnopenable() {
    Rectangle usable = BrowserViewport.usableBounds();
    assertTrue("宽度必须为正", usable.width > 0);
    assertTrue("高度必须为正", usable.height > 0);
    Dimension size = BrowserViewport.windowSize();
    assertTrue("窗口宽度不该是 0 或负数", size.width >= 320);
    assertTrue("窗口高度不该是 0 或负数", size.height >= 240);
  }

  // ==================== 视口策略 ====================

  /** 有头 + 不配 → 跟随真实窗口(这次的默认行为变化) */
  @Test
  public void headedDefaultsToFollowingTheWindow() {
    BrowserViewport.Policy policy = BrowserViewport.policy(null, false, WINDOW);
    assertTrue("有头默认应当跟随窗口", policy.followsWindow());
    assertEquals(BrowserViewport.MODE_WINDOW, policy.mode);
    assertNull("跟随窗口时没有固定尺寸", policy.width);
    assertNull(policy.height);
    assertNull("默认路径不该带说明", policy.note);
    assertEquals("window", policy.describe());
  }

  /** 无头 + 跟随窗口 → 退回固定视口,而且必须说明原因 */
  @Test
  public void headlessFallsBackToAFixedViewportAndSaysSo() {
    BrowserViewport.Policy policy = BrowserViewport.policy(null, true, WINDOW);
    assertFalse("无头没有真实窗口可跟随", policy.followsWindow());
    assertEquals(BrowserViewport.MODE_FIXED, policy.mode);
    assertEquals(Integer.valueOf(WINDOW.width), policy.width);
    assertEquals(Integer.valueOf(WINDOW.height), policy.height);
    assertNotNull("静默退回会让人以为配置生效了,必须有说明", policy.note);
    assertTrue("说明里要提到无头", policy.note.contains("无头"));
    assertEquals("fixed 1484x1019", policy.describe());
  }

  /** fixed:一直用按屏幕算出来的尺寸(以前的行为) */
  @Test
  public void fixedAlwaysUsesTheDerivedSize() {
    BrowserViewport.Policy headed = BrowserViewport.policy(BrowserViewport.MODE_FIXED, false, WINDOW);
    assertFalse(headed.followsWindow());
    assertEquals(Integer.valueOf(WINDOW.width), headed.width);
    assertEquals(Integer.valueOf(WINDOW.height), headed.height);
    assertNull("fixed 是有意为之,不需要说明", headed.note);

    BrowserViewport.Policy headless = BrowserViewport.policy(BrowserViewport.MODE_FIXED, true, WINDOW);
    assertFalse(headless.followsWindow());
    assertEquals(Integer.valueOf(WINDOW.height), headless.height);
  }

  /** 显式尺寸:直接在配置里钉死,不用改代码 */
  @Test
  public void explicitSizePinsTheViewport() {
    BrowserViewport.Policy policy = BrowserViewport.policy("1440x900", false, WINDOW);
    assertFalse("写了尺寸就不该跟随窗口", policy.followsWindow());
    assertEquals(Integer.valueOf(1440), policy.width);
    assertEquals(Integer.valueOf(900), policy.height);
    assertEquals("fixed 1440x900", policy.describe());
  }

  /** 分隔符与大小写都宽容:`1440X900` / `1440*900` / `1440 × 900` */
  @Test
  public void explicitSizeAcceptsCommonSeparators() {
    for (String value : new String[] { "1440X900", "1440*900", "1440 × 900", " 1440x900 " }) {
      BrowserViewport.Policy policy = BrowserViewport.policy(value, false, WINDOW);
      assertEquals("没认出来:" + value, Integer.valueOf(1440), policy.width);
      assertEquals("没认出来:" + value, Integer.valueOf(900), policy.height);
    }
  }

  /** window / follow / auto / 空串 都表示跟随窗口(有头时) */
  @Test
  public void followAliasesAllMeanFollowTheWindow() {
    for (String value : new String[] { "window", "WINDOW", "follow", "auto", "", "   " }) {
      assertTrue("`" + value + "` 应当表示跟随窗口",
          BrowserViewport.policy(value, false, WINDOW).followsWindow());
    }
  }

  /** 不认识的取值:直接失败,并且把可选值列出来 */
  @Test
  public void unknownValueFailsWithTheListOfValidOnes() {
    // 这个项目用的是 JUnit 4.12,没有 assertThrows,只能自己接住
    IllegalArgumentException failure = null;
    try {
      BrowserViewport.policy("1920", false, WINDOW);
    } catch (IllegalArgumentException thrown) {
      failure = thrown;
    }
    assertNotNull("`1920` 既不是模式也不是「宽x高」,必须直接失败而不是悄悄用默认值", failure);
    assertTrue("要回显配错的那个值:" + failure.getMessage(), failure.getMessage().contains("1920"));
    assertTrue("要列出可选值:" + failure.getMessage(), failure.getMessage().contains("window"));
    assertTrue("要列出可选值:" + failure.getMessage(), failure.getMessage().contains("fixed"));
    assertTrue("要给出显式尺寸的写法:" + failure.getMessage(), failure.getMessage().contains("1440x900"));
  }

  /** 配置项真的读得到(走 ChromeBrowser 的配置通道,含系统属性覆盖) */
  @Test
  public void policyReadsTheConfigKey() {
    System.setProperty(BrowserViewport.KEY_VIEWPORT, "1600x1000");
    ChromeBrowser.resetForTests();
    BrowserViewport.Policy policy = BrowserViewport.policy(false);
    assertEquals(Integer.valueOf(1600), policy.width);
    assertEquals(Integer.valueOf(1000), policy.height);
  }

  /** 不配时,有头走 window、无头走固定 —— 两者必须不同,否则这次改动没生效 */
  @Test
  public void defaultPolicyDiffersBetweenHeadedAndHeadless() {
    ChromeBrowser.resetForTests();
    assertTrue("有头默认跟随窗口", BrowserViewport.policy(false).followsWindow());
    assertFalse("无头默认固定", BrowserViewport.policy(true).followsWindow());
  }

  // ==================== 落到启动参数上 ====================

  /**
   * 跟随窗口时,**不能**再给 deviceScaleFactor / isMobile / hasTouch
   *
   * <p>
   * Playwright 校验上下文参数时会直接拒绝这个组合并让启动失败:
   * {@code "deviceScaleFactor" option is not supported with null "viewport"}。
   *
   * <p>
   * 这条是实测踩出来的:纯单元测试一个都不报错,只有真正拉起浏览器的用例才挂 —— 也就是说
   * 「browser.viewport=window 下 start 直接失败」这种事故,只有这条断言能在几毫秒内拦住。
   */
  @Test
  public void followingTheWindowDoesNotSetDeviceEmulation() {
    BrowserType.LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions();
    PlaywrightService.applyViewport(opts, BrowserViewport.policy(null, false, WINDOW));

    assertNotNull("viewportSize 必须被设过(empty 表示 noDefaultViewport)", opts.viewportSize);
    assertFalse("empty 才是「视口交给窗口」", opts.viewportSize.isPresent());
    assertNull("null viewport 下给 deviceScaleFactor,Playwright 会拒绝启动", opts.deviceScaleFactor);
    assertNull("null viewport 下给 isMobile,Playwright 会拒绝启动", opts.isMobile);
    assertNull("null viewport 下给 hasTouch,Playwright 会拒绝启动", opts.hasTouch);
  }

  /** 钉死视口时三项设备仿真必须给齐(以前的行为,别在重构里丢掉) */
  @Test
  public void pinnedViewportStillSetsDeviceEmulation() {
    BrowserType.LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions();
    PlaywrightService.applyViewport(opts, BrowserViewport.policy("1440x900", false, WINDOW));

    assertTrue("钉死视口时 viewportSize 必须带值", opts.viewportSize.isPresent());
    assertEquals(1440, opts.viewportSize.get().width);
    assertEquals(900, opts.viewportSize.get().height);
    assertEquals(Double.valueOf(1.0), opts.deviceScaleFactor);
    assertEquals(Boolean.FALSE, opts.isMobile);
    assertEquals(Boolean.FALSE, opts.hasTouch);
  }

  // ==================== 探测助手 ====================

  /** @return 屏幕高度;没有显示设备(CI 容器)时 -1 */
  private static int screenHeightOrSkip() {
    try {
      return Toolkit.getDefaultToolkit().getScreenSize().height;
    } catch (Throwable headless) {
      return -1;
    }
  }

  /** @return 可用工作区;没有显示设备时 null */
  private static Rectangle workingAreaOrSkip() {
    try {
      return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    } catch (Throwable headless) {
      return null;
    }
  }
}
