package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Test;

import nexus.io.tio.utils.environment.EnvUtils;

/**
 * 「用哪个浏览器」({@code start} 的 {@code browser} 参数 / {@code browser.type} 配置)的语义
 *
 * <p>
 * 盯住几件「改错了也编译得过」的事:
 * <ol>
 * <li><b>不传时行为不变</b>:{@code auto} 仍然落成「本机 Chrome 优先,没装退回内置 Chromium」,与以前的
 * 版本完全一致;</li>
 * <li><b>显式类型不退让</b>:{@code chrome} / {@code edge} 落成的就是它们自己,不会因为本机没装就变成
 * 内置 Chromium —— 这正是 {@code auto} 与它们的区别;</li>
 * <li><b>profile 目录</b>:Chromium 系沿用 {@code browser.profileDir}(不动已有登录态),Edge 单独一份;</li>
 * <li>{@code browser.engine=firefox} 这个老开关仍然有效。</li>
 * </ol>
 */
public class BrowserChoiceTest {

  private static final String HOME = EnvUtils.get("user.home", ".");

  @After
  public void tearDown() {
    System.clearProperty(BrowserChoice.KEY_TYPE);
    System.clearProperty(BrowserEngine.KEY_ENGINE);
    // 这几个开关一旦漏清,后面的测试类会跟着用错浏览器(surefire 默认同一个 JVM 跑完所有类),
    // 而症状是「另一个类里的断言莫名失败」,很难往这里想 —— 所以清干净是本测试的责任
    System.clearProperty(ChromeBrowser.KEY_ENABLED);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    System.clearProperty(EdgeBrowser.KEY_PROFILE_DIR);
    System.clearProperty(EdgeBrowser.KEY_ENABLED);
    ChromeBrowser.resetForTests();
    EdgeBrowser.resetForTests();
  }

  @Test
  public void parseAcceptsTheDocumentedValues() {
    assertEquals(BrowserChoice.AUTO, BrowserChoice.parse("auto"));
    assertEquals(BrowserChoice.CHROMIUM, BrowserChoice.parse("chromium"));
    assertEquals(BrowserChoice.CHROME, BrowserChoice.parse("chrome"));
    assertEquals(BrowserChoice.EDGE, BrowserChoice.parse("edge"));
    assertEquals(BrowserChoice.FIREFOX, BrowserChoice.parse("firefox"));
  }

  /** 大小写、空格与常见别名都要认:调用方是模型,不该因为写了 msedge 就失败 */
  @Test
  public void parseAcceptsAliasesAndTrims() {
    assertEquals(BrowserChoice.EDGE, BrowserChoice.parse("  msedge "));
    assertEquals(BrowserChoice.EDGE, BrowserChoice.parse("Microsoft-Edge"));
    assertEquals(BrowserChoice.CHROME, BrowserChoice.parse("google-chrome"));
    assertEquals(BrowserChoice.CHROMIUM, BrowserChoice.parse("bundled"));
    assertEquals(BrowserChoice.FIREFOX, BrowserChoice.parse("FF"));
    assertEquals(BrowserChoice.AUTO, BrowserChoice.parse("default"));
  }

  /** 认不出来返回 null(由 start 报错并列出可选值),不要悄悄当成默认值 */
  @Test
  public void parseReturnsNullForUnknownValues() {
    assertNull(BrowserChoice.parse("safari"));
    assertNull(BrowserChoice.parse("ie"));
    assertNull(BrowserChoice.parse(null));
    assertNull(BrowserChoice.parse("   "));
  }

  @Test
  public void choicesListEveryValue() {
    assertEquals("auto / chromium / chrome / edge / firefox", BrowserChoice.choices());
  }

  /** 不配任何东西时默认是 auto:本机装了 Chrome 就用它,与以前的版本一致 */
  @Test
  public void defaultIsAuto() {
    assertEquals(BrowserChoice.AUTO, BrowserChoice.configured());
    assertEquals(BrowserChoice.AUTO.id(), "auto");
  }

  /** 老的引擎开关仍然管用:browser.engine=firefox 等价于 browser.type=firefox */
  @Test
  public void engineSwitchStillWorks() {
    System.setProperty(BrowserEngine.KEY_ENGINE, "firefox");
    assertEquals(BrowserChoice.FIREFOX, BrowserChoice.configured());
    assertEquals(BrowserChoice.FIREFOX, BrowserChoice.resolve(null));
  }

  @Test
  public void typeConfigBeatsEngineConfig() {
    System.setProperty(BrowserEngine.KEY_ENGINE, "firefox");
    System.setProperty(BrowserChoice.KEY_TYPE, "edge");
    assertEquals(BrowserChoice.EDGE, BrowserChoice.configured());
  }

  /** 不认识的值退回 browser.engine 那套判断,而不是让服务起不来 */
  @Test
  public void unknownTypeFallsBackToEngineConfig() {
    System.setProperty(BrowserChoice.KEY_TYPE, "safari");
    assertEquals(BrowserChoice.AUTO, BrowserChoice.configured());
    System.setProperty(BrowserEngine.KEY_ENGINE, "firefox");
    assertEquals(BrowserChoice.FIREFOX, BrowserChoice.configured());
  }

  /**
   * auto 要看这台机器上有什么:装了 Chrome 用它,关掉 Chrome 探测就用内置 Chromium
   *
   * <p>
   * 这两条断言合起来才是「auto 的行为没变」:以前是 {@code ChromeBrowser.executablePath() != null} 决定
   * 用不用本机 Chrome,现在仍然由它决定。
   */
  @Test
  public void autoResolvesToWhatIsInstalled() {
    System.setProperty(ChromeBrowser.KEY_ENABLED, "false");
    ChromeBrowser.resetForTests();
    assertEquals("关掉本机 Chrome 探测时,auto 应当落到内置 Chromium", BrowserChoice.CHROMIUM,
        BrowserChoice.resolve(BrowserChoice.AUTO));
    System.clearProperty(ChromeBrowser.KEY_ENABLED);
    ChromeBrowser.resetForTests();
    if (ChromeBrowser.executablePath() != null) {
      // 这台机器上装了 Chrome(开发机就是这种情况):auto 必须用它,否则「默认行为没变」这句话不成立
      assertEquals("装了本机 Chrome 时,auto 应当落到 chrome", BrowserChoice.CHROME,
          BrowserChoice.resolve(BrowserChoice.AUTO));
    }
  }

  /** 显式类型不退让:这台机器上有没有 Chrome,都不该影响 chrome / edge 的落点 */
  @Test
  public void explicitTypesDoNotFallBack() {
    System.setProperty(ChromeBrowser.KEY_ENABLED, "false");
    ChromeBrowser.resetForTests();
    assertEquals(BrowserChoice.CHROME, BrowserChoice.resolve(BrowserChoice.CHROME));
    assertEquals(BrowserChoice.EDGE, BrowserChoice.resolve(BrowserChoice.EDGE));
    assertEquals(BrowserChoice.CHROMIUM, BrowserChoice.resolve(BrowserChoice.CHROMIUM));
  }

  /** 引擎由类型推出来:Chromium 系都是 chromium,firefox 才是 firefox */
  @Test
  public void engineFollowsTheChoice() {
    assertEquals(BrowserEngine.CHROMIUM, BrowserChoice.CHROME.engine());
    assertEquals(BrowserEngine.CHROMIUM, BrowserChoice.EDGE.engine());
    assertEquals(BrowserEngine.CHROMIUM, BrowserChoice.CHROMIUM.engine());
    assertEquals(BrowserEngine.FIREFOX, BrowserChoice.FIREFOX.engine());
    assertTrue(BrowserChoice.FIREFOX.isFirefox());
    assertTrue(BrowserChoice.EDGE.isEdge());
    assertTrue(BrowserChoice.CHROME.isGoogleChrome());
    assertFalse("Edge 不是 Google Chrome,返回里的 chrome 字段必须为 false", BrowserChoice.EDGE.isGoogleChrome());
  }

  /** Chromium 系(含 Firefox)沿用 browser.profileDir:不动已有登录态 */
  @Test
  public void chromiumFamilyKeepsTheConfiguredProfileDir() {
    Path custom = Paths.get(System.getProperty("java.io.tmpdir"), "browser-choice-test-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, custom.toString());
    ChromeBrowser.resetForTests();
    for (BrowserChoice type : new BrowserChoice[] { BrowserChoice.AUTO, BrowserChoice.CHROMIUM, BrowserChoice.CHROME,
        BrowserChoice.FIREFOX }) {
      assertEquals(type + " 应当沿用 browser.profileDir", custom.toAbsolutePath().normalize(), type.profileDir());
    }
  }

  /** Edge 单独一份托管 profile:与 Chrome 那份不能混用 */
  @Test
  public void edgeHasItsOwnProfileDir() {
    assertEquals(Paths.get(HOME, ".config", "browseruse", "profiles", "edge").toAbsolutePath().normalize(),
        BrowserChoice.EDGE.profileDir());
  }

  @Test
  public void edgeProfileDirCanBeConfigured() {
    Path custom = Paths.get(System.getProperty("java.io.tmpdir"), "edge-profile-choice-test");
    System.setProperty(EdgeBrowser.KEY_PROFILE_DIR, custom.toString());
    assertEquals(custom.toAbsolutePath().normalize(), BrowserChoice.EDGE.profileDir());
  }

  /** 没装 Edge 时的失败原因要能指导下一步:提安装位置与配置项,而不是只说一句「找不到」 */
  @Test
  public void edgeNotFoundMessageTellsHowToFixIt() {
    String message = BrowserChoice.EDGE.notFoundMessage();
    assertTrue("要提到 browser=edge,实际:" + message, message.contains("browser=edge"));
    assertTrue("要提到怎么显式指定可执行文件,实际:" + message, message.contains("browser.edge.path"));
    assertTrue("要提到可以改用别的浏览器,实际:" + message, message.contains("browser=chromium"));
  }
}
