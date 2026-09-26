package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

import nexus.io.tio.utils.environment.EnvUtils;

/**
 * Microsoft Edge 的探测与参数
 *
 * <p>
 * 与 {@link ChromeBrowserTest} 同一个思路:显式配置的路径要用它、指向不存在的文件时不能把它交给
 * Playwright(那会得到一句英文的启动失败)、profile 目录要独立、参数要能配。
 *
 * <p>
 * 这些断言都不要求这台机器上真的装了 Edge —— 真起一次 Edge 的是
 * {@link EdgeBrowserSmokeTest}(默认不跑)。
 */
public class EdgeBrowserTest {

  private static final String HOME = EnvUtils.get("user.home", ".");

  @After
  public void tearDown() {
    System.clearProperty(EdgeBrowser.KEY_ENABLED);
    System.clearProperty(EdgeBrowser.KEY_PATH);
    System.clearProperty(EdgeBrowser.KEY_PROFILE_DIR);
    System.clearProperty(EdgeBrowser.KEY_EXTRA_ARGS);
    EdgeBrowser.resetForTests();
  }

  /** 默认探测开着:本机装了 Edge 就该找到它 */
  @Test
  public void enabledByDefault() {
    assertTrue("Edge 探测默认应当是开的", ChromeBrowser.booleanConfig(EdgeBrowser.KEY_ENABLED, true));
  }

  /** 关掉之后一律返回 null,调用方会给出「browser=edge 找不到」的明确失败 */
  @Test
  public void disabledMeansNoExecutable() {
    System.setProperty(EdgeBrowser.KEY_ENABLED, "false");
    EdgeBrowser.resetForTests();
    assertNull(EdgeBrowser.executablePath());
  }

  /** 显式配置的可执行文件要真的用上(装了 Edge 的机器上可以直接指到 msedge.exe) */
  @Test
  public void configuredExecutableIsUsed() throws IOException {
    Path fake = Files.createTempFile("msedge-", ".exe");
    try {
      System.setProperty(EdgeBrowser.KEY_PATH, fake.toAbsolutePath().toString());
      EdgeBrowser.resetForTests();
      assertEquals(fake.toAbsolutePath().normalize(), EdgeBrowser.executablePath());
    } finally {
      Files.deleteIfExists(fake);
    }
  }

  /**
   * 探测到的可执行文件「看起来是 Edge」吗
   *
   * <p>
   * 不能只认 `msedge`：Windows 上可执行文件叫 `msedge.exe`，而 **macOS 上它就叫
   * `Microsoft Edge`**（在 `Microsoft Edge.app/Contents/MacOS/` 里）。实测在装了 Edge 的 macOS 上，
   * 只认 `msedge` 会让下面两条断言必挂 —— 而它们真正要守的是「别把 Chrome 当 Edge」。
   */
  private static boolean looksLikeEdge(Path found) {
    String text = found.toAbsolutePath().toString().toLowerCase().replace('\\', '/');
    return !text.contains("chrome") && (text.contains("msedge") || text.contains("microsoft edge"));
  }

  /** 配了一个不存在的路径时不能把它交给 Playwright:那只会得到一句英文的启动失败 */
  @Test
  public void missingConfiguredExecutableIsIgnored() {
    System.setProperty(EdgeBrowser.KEY_PATH, Paths.get("definitely", "not", "msedge.exe").toString());
    EdgeBrowser.resetForTests();
    Path found = EdgeBrowser.executablePath();
    assertTrue("要么找不到(null),要么退回自动探测,但绝不能是那个不存在的路径",
        found == null || Files.isRegularFile(found));
    if (found != null) {
      assertTrue("自动探测的结果应当是 Edge(msedge / Microsoft Edge),实际:" + found, looksLikeEdge(found));
    }
  }

  /** 自动探测的结果必须是 Edge(不能把 chrome.exe 当 Edge 用) */
  @Test
  public void detectedExecutableIsEdgeNotChrome() {
    Path found = EdgeBrowser.executablePath();
    if (found == null) {
      return;
    }
    assertTrue("探测到的应当是 Edge(msedge / Microsoft Edge),实际:" + found, looksLikeEdge(found));
  }

  /** Edge 的托管 profile 与 Chrome 那份分开:默认在 profiles/edge 下 */
  @Test
  public void managedProfileDirIsSeparateFromChrome() {
    Path edge = EdgeBrowser.managedProfileDir();
    assertEquals(Paths.get(HOME, ".config", "browseruse", "profiles", "edge").toAbsolutePath().normalize(), edge);
    Path chrome = ChromeBrowser.managedProfileDir();
    assertTrue("Edge 的 profile 目录不能和 Chrome 那份是同一个:" + edge, !edge.equals(chrome));
  }

  @Test
  public void managedProfileDirCanBeConfigured() {
    Path custom = Paths.get(System.getProperty("java.io.tmpdir"), "edge-browser-test-profile");
    System.setProperty(EdgeBrowser.KEY_PROFILE_DIR, custom.toString());
    assertEquals(custom.toAbsolutePath().normalize(), EdgeBrowser.managedProfileDir());
  }

  /** 额外参数按逗号拆,空项丢掉 */
  @Test
  public void extraArgsAreParsed() {
    assertTrue("没配时应当是空列表", EdgeBrowser.extraArgs().isEmpty());
    System.setProperty(EdgeBrowser.KEY_EXTRA_ARGS, " --lang=zh-CN , ,--disable-sync ");
    assertEquals(List.of("--lang=zh-CN", "--disable-sync"), EdgeBrowser.extraArgs());
  }

  /**
   * 首启哨兵:全新 profile 目录会让 Edge 走引导向导,启动前要补一个空文件
   *
   * <p>已经有这个文件时不动它(里面本来也没有内容,但「不覆盖」这个语义要固定下来)。
   */
  @Test
  public void firstRunSentinelIsCreatedOnce() throws IOException {
    Path dir = Files.createTempDirectory("edge-first-run");
    try {
      EdgeBrowser.prepareProfileDir(dir);
      Path sentinel = dir.resolve("First Run");
      assertTrue("应当补上 First Run 哨兵:" + sentinel, Files.isRegularFile(sentinel));
      Files.write(sentinel, "keep-me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      EdgeBrowser.prepareProfileDir(dir);
      assertEquals("已经有哨兵时不该覆盖它", "keep-me",
          new String(Files.readAllBytes(sentinel), java.nio.charset.StandardCharsets.UTF_8));
    } finally {
      try (Stream<Path> paths = Files.walk(dir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  /** 传 null 不该炸:调用方在配置不全时也可能走到这里 */
  @Test
  public void prepareProfileDirToleratesNull() {
    EdgeBrowser.prepareProfileDir(null);
  }
}
