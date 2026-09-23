package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

/**
 * 引擎开关({@code browser.engine})的配置语义
 *
 * <p>
 * 盯住三件事,它们都会「改错了也编译得过」:
 * <ol>
 * <li><b>默认仍然是 chromium</b>:不配这一项时服务的行为必须和以前一模一样,谁把默认值改掉都会在这里失败;</li>
 * <li>Firefox 下不能混进 Chromium 特有的东西:剪贴板权限、{@code --no-sandbox} 这类参数传给 Firefox 会直接让
 * 浏览器起不来(而且报的是很难懂的英文选项错误);</li>
 * <li>{@code browser.firefox.path} 不存在时要退回 Playwright 自己那份,而不是把不存在的路径交给 Playwright。</li>
 * </ol>
 */
public class BrowserEngineTest {

  @After
  public void tearDown() {
    System.clearProperty(BrowserEngine.KEY_ENGINE);
    System.clearProperty(BrowserEngine.KEY_FIREFOX_PATH);
    System.clearProperty(BrowserEngine.KEY_FIREFOX_EXTRA_ARGS);
    ChromeBrowser.resetForTests();
  }

  /** 不配 browser.engine 时保持原来的行为:本机 Chrome / 内嵌 Chromium */
  @Test
  public void defaultEngineIsChromium() {
    assertEquals(BrowserEngine.CHROMIUM, BrowserEngine.current());
    assertTrue(BrowserEngine.current().isChromium());
    assertFalse(BrowserEngine.current().isFirefox());
    assertEquals("chromium", BrowserEngine.current().id());
  }

  @Test
  public void firefoxIsRecognizedInSeveralSpellings() {
    for (String value : List.of("firefox", "Firefox", " firefox ", "FF", "gecko")) {
      System.setProperty(BrowserEngine.KEY_ENGINE, value);
      assertEquals("browser.engine=" + value + " 应当解析成 Firefox", BrowserEngine.FIREFOX,
          BrowserEngine.current());
    }
  }

  @Test
  public void chromiumIsRecognizedInSeveralSpellings() {
    for (String value : List.of("chromium", "Chromium", "chrome", "chromium-based")) {
      System.setProperty(BrowserEngine.KEY_ENGINE, value);
      assertEquals("browser.engine=" + value + " 应当解析成 Chromium", BrowserEngine.CHROMIUM,
          BrowserEngine.current());
    }
  }

  /** 写错的值不能让服务起不来:按默认处理,只留一条警告 */
  @Test
  public void unknownEngineFallsBackToChromium() {
    System.setProperty(BrowserEngine.KEY_ENGINE, "safari");
    assertEquals(BrowserEngine.CHROMIUM, BrowserEngine.current());
  }

  /**
   * Firefox 的权限里不能有剪贴板:那是 Chromium 特有的权限名,传进 Firefox 的上下文创建会直接启动失败
   */
  @Test
  public void firefoxPermissionsAreFirefoxCompatible() {
    List<String> permissions = BrowserEngine.firefoxPermissions();
    assertFalse("Firefox 不支持 clipboard-read,传进去会让浏览器起不来", permissions.contains("clipboard-read"));
    assertFalse("Firefox 不支持 clipboard-write,传进去会让浏览器起不来", permissions.contains("clipboard-write"));
    assertTrue("至少保留 notifications", permissions.contains("notifications"));
  }

  /** 自动化特征与指纹保护:这两项是 Firefox 下能正常渲染政务站点的前提之一 */
  @Test
  public void firefoxPrefsHideAutomationAndDisableFingerprintSpoofing() {
    Map<String, Object> prefs = BrowserEngine.firefoxUserPrefs();
    assertEquals(Boolean.FALSE, prefs.get("dom.webdriver.enabled"));
    assertEquals("指纹保护会改写 screen.availWidth/availHeight,站点读到的屏幕尺寸会和窗口对不上",
        Boolean.FALSE, prefs.get("privacy.fingerprintingProtection"));
  }

  /** 配了不存在的可执行文件时退回 Playwright 自己那份,而不是把死路径交给 Playwright */
  @Test
  public void missingFirefoxExecutableFallsBackToPlaywrightBuild() {
    System.setProperty(BrowserEngine.KEY_FIREFOX_PATH, Paths.get("definitely", "not", "firefox.exe").toString());
    assertNull(BrowserEngine.firefoxExecutablePath());
  }

  @Test
  public void firefoxExtraArgsAreSplitAndTrimmed() {
    System.setProperty(BrowserEngine.KEY_FIREFOX_EXTRA_ARGS, " -private , ,--new-instance ");
    assertEquals(List.of("-private", "--new-instance"), BrowserEngine.firefoxExtraArgs());
    System.clearProperty(BrowserEngine.KEY_FIREFOX_EXTRA_ARGS);
    assertTrue("没配时应当是空列表", BrowserEngine.firefoxExtraArgs().isEmpty());
  }

  /**
   * Firefox 与 Chromium 共用同一套窗口尺寸算法:两个引擎看到的页面布局不该不一样
   */
  @Test
  public void windowSizeIsSharedByBothEngines() {
    java.awt.Dimension size = PlaywrightService.windowSize();
    java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
    assertEquals((screen.width / 32) * 28, size.width);
    assertEquals(screen.height, size.height);
  }
}