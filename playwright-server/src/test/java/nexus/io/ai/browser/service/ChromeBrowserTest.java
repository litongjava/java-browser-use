package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * Google Chrome 探测与 profile 参数的检查
 *
 * <p>探测结果依赖机器上装没装 Chrome,所以这里不假设结果,只校验「配置项说了算」这件事:
 * 显式配置的路径要用它、关掉之后不许再返回路径、子 profile 要真的进到启动参数里。
 *
 * <p>每个用例结束都要清掉系统属性并重置缓存,否则会污染同一个 JVM 里的其它用例(尤其是真的要
 * 启动浏览器的集成测试)。
 */
public class ChromeBrowserTest {

  @After
  public void cleanup() {
    System.clearProperty(ChromeBrowser.KEY_ENABLED);
    System.clearProperty(ChromeBrowser.KEY_PATH);
    System.clearProperty(ChromeBrowser.KEY_USER_DATA_DIR);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIRECTORY);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_FALLBACK);
    System.clearProperty(ChromeBrowser.KEY_USE_USER_PROFILE);
    System.clearProperty(ChromeBrowser.KEY_EXTRA_ARGS);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
  }

  /** 显式配了可执行文件就用它,不再去猜 */
  @Test
  public void configuredExecutableWins() throws Exception {
    Path fake = Files.createTempFile("fake-chrome", ".exe");
    System.setProperty(ChromeBrowser.KEY_PATH, fake.toString());
    ChromeBrowser.resetForTests();

    assertEquals(fake.toAbsolutePath().normalize(), ChromeBrowser.executablePath());
  }

  /** 配置的路径不存在时不能拿它去启动浏览器,要退回自动探测 */
  @Test
  public void missingConfiguredExecutableFallsBackToDetection() {
    Path missing = Paths.get(System.getProperty("java.io.tmpdir"), "no-such-chrome", "chrome.exe");
    System.setProperty(ChromeBrowser.KEY_PATH, missing.toString());
    ChromeBrowser.resetForTests();

    Path resolved = ChromeBrowser.executablePath();
    assertTrue("配置的路径不存在时不该用它:" + resolved, resolved == null || !resolved.equals(missing));
  }

  /** 关掉之后连用户 profile 也不该再用,调用方会退回内嵌 Chromium + 托管 profile */
  @Test
  public void disabledChromeIsNotUsed() {
    System.setProperty(ChromeBrowser.KEY_ENABLED, "false");
    ChromeBrowser.resetForTests();

    assertNull(ChromeBrowser.executablePath());
    assertNull(ChromeBrowser.userDataDir());
  }

  /** 用户数据目录可以显式指定(Chrome 正在运行、或者想用另一份 profile 时) */
  @Test
  public void configuredUserDataDirIsUsed() throws Exception {
    Path dir = Files.createTempDirectory("chrome-user-data");
    System.setProperty(ChromeBrowser.KEY_ENABLED, "false");
    System.setProperty(ChromeBrowser.KEY_USER_DATA_DIR, dir.toString());
    ChromeBrowser.resetForTests();

    // 关掉 Chrome 时不会解析用户目录,这里只验证配置项本身能被读到
    assertEquals(dir.toString(), ChromeBrowser.config(ChromeBrowser.KEY_USER_DATA_DIR));
  }

  /** 子 profile 要进到启动参数里,否则多 profile 的用户会被带到 Default 上 */
  @Test
  public void profileDirectoryBecomesStartupArgument() {
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIRECTORY, "Profile 3");
    ChromeBrowser.resetForTests();

    assertEquals(List.of("--profile-directory=Profile 3"), ChromeBrowser.profileArgs());
  }

  /** 配成空串表示不传 --profile-directory */
  @Test
  public void emptyProfileDirectoryMeansNoArgument() {
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIRECTORY, "");
    ChromeBrowser.resetForTests();

    assertTrue(ChromeBrowser.profileArgs().isEmpty());
  }

  /** profileFallback 默认开着:用户 profile 用不了时服务还能跑 */
  @Test
  public void profileFallbackDefaultsToOn() {
    assertTrue(ChromeBrowser.profileFallback());
    System.setProperty(ChromeBrowser.KEY_PROFILE_FALLBACK, "false");
    assertFalse(ChromeBrowser.profileFallback());
  }

  /**
   * useUserProfile 默认**关**着
   *
   * <p>
   * Chrome 136 起不允许在默认用户数据目录上开远程调试,默认打开它只会让每次 start 都先失败一次再退回,
   * 所以默认走托管 profile;想用用户自己的 profile 得显式打开(并且这台机器要允许远程调试默认 profile)。
   */
  @Test
  public void userProfileIsOffByDefault() {
    assertFalse(ChromeBrowser.useUserProfile());
    System.setProperty(ChromeBrowser.KEY_USE_USER_PROFILE, "true");
    assertTrue(ChromeBrowser.useUserProfile());
  }

  /** 额外启动参数按逗号切开,空项要丢掉(配置里常留一个尾逗号) */
  @Test
  public void extraArgsAreSplitAndTrimmed() {
    System.setProperty(ChromeBrowser.KEY_EXTRA_ARGS, " --lang=zh-CN , --disable-extensions ,");
    ChromeBrowser.resetForTests();

    assertEquals(List.of("--lang=zh-CN", "--disable-extensions"), ChromeBrowser.extraArgs());
  }

  /** 没配额外参数时返回空列表,不能塞进空字符串 */
  @Test
  public void extraArgsEmptyByDefault() {
    assertTrue(ChromeBrowser.extraArgs().isEmpty());
  }

  /** 托管 profile 默认在 ~/.config/browseruse/profiles/shared,可以用 browser.profileDir 换地方 */
  @Test
  public void managedProfileDirDefaultsUnderUserHome() {
    Path expected = Paths.get(System.getProperty("user.home"), ".config", "browseruse", "profiles", "shared");
    assertEquals(expected, ChromeBrowser.managedProfileDir());

    Path custom = Paths.get(System.getProperty("java.io.tmpdir"), "browseruse-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, custom.toString());
    ChromeBrowser.resetForTests();
    assertEquals(custom.toAbsolutePath().normalize(), ChromeBrowser.managedProfileDir());
  }

  /** 启动参数里绝不能出现 --no-sandbox:那一条由 Playwright 按 chromiumSandbox 自己加 */
  @Test
  public void profileArgsDoNotCarryUnsupportedFlags() {
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIRECTORY, "Default");
    ChromeBrowser.resetForTests();

    for (String arg : ChromeBrowser.profileArgs()) {
      assertFalse("profile 参数里不该有 --no-sandbox", arg.contains("--no-sandbox"));
      assertFalse("profile 参数里不该有 --disable-web-security", arg.contains("--disable-web-security"));
    }
  }

  /**
   * 没人用着的目录不能算「被占用」—— 哪怕这台机器上有 Chrome 正在跑、而且它的命令行读不到
   *
   * <p>
   * 这条是回归保护:以前「命令行读不到」被当成「被占用」,于是只要机器上有一个读不到命令行的 Chrome
   * (实测:Windows 上以管理员身份运行时很常见),{@code browser.chrome.useUserProfile} 就永远用不上,
   * 连指向临时目录的测试都跑不起来。
   */
  @Test
  public void freshDirectoryIsNotInUseEvenWhenCommandLinesAreUnreadable() throws Exception {
    Path dir = Files.createTempDirectory("profile-in-use");

    assertFalse("空目录不该被判成被占用:" + dir, ChromeBrowser.profileInUse(dir));
    assertFalse("null 目录自然也不算被占用", ChromeBrowser.profileInUse(null));
  }

  /** 类 Unix 上的单例锁记着一个活着的 PID:这就是「有人正在用」的实证 */
  @Test
  public void singletonLockWithLivePidMeansInUse() throws Exception {
    Path dir = Files.createTempDirectory("profile-locked");
    Path lock = dir.resolve("SingletonLock");
    try {
      Files.createSymbolicLink(lock, Paths.get("some-host-" + ProcessHandle.current().pid()));
    } catch (IOException | UnsupportedOperationException e) {
      Assume.assumeTrue("这个环境建不了软链(Windows 需要开发者模式或管理员),跳过:" + e.getMessage(), false);
    }

    assertTrue("锁上的 PID 还活着,应当判成被占用", ChromeBrowser.profileInUse(dir));
  }

  /** 锁是崩溃留下的残留(PID 早就没了):不能当成被占用,否则用户永远起不来 */
  @Test
  public void staleSingletonLockIsNotInUse() throws Exception {
    Path dir = Files.createTempDirectory("profile-stale-lock");
    Path lock = dir.resolve("SingletonLock");
    try {
      // Long.MAX_VALUE 这个 PID 一定不存在
      Files.createSymbolicLink(lock, Paths.get("some-host-" + Long.MAX_VALUE));
    } catch (IOException | UnsupportedOperationException e) {
      Assume.assumeTrue("这个环境建不了软链(Windows 需要开发者模式或管理员),跳过:" + e.getMessage(), false);
    }

    assertFalse("锁是残留,不该判成被占用", ChromeBrowser.profileInUse(dir));
  }

  /** Windows 上 Chrome 用的是命名对象,不会落 SingletonLock 文件:一个普通同名文件不该被当成锁 */
  @Test
  public void regularFileNamedSingletonLockIsNotALock() throws Exception {
    Path dir = Files.createTempDirectory("profile-fake-lock");
    Files.writeString(dir.resolve("SingletonLock"), "not a symlink");

    assertFalse("不是软链就不是 Chrome 的单例锁", ChromeBrowser.profileInUse(dir));
  }

  /**
   * 单例锁内容的解读(与能不能建软链无关,所以每个平台都跑得到)
   *
   * <p>
   * 锁里写的是 {@code <主机名>-<PID>}:PID 还活着才算有人用,崩溃留下的残留不算 —— 否则用户会永远
   * 被自己上次的崩溃挡在门外。
   */
  @Test
  public void singletonLockContentIsInterpretedByLiveness() {
    assertTrue("锁上的 PID 是当前进程,还活着",
        ChromeBrowser.singletonLockHeld("my-host-" + ProcessHandle.current().pid()));
    assertFalse("这个 PID 不可能存在,锁是残留",
        ChromeBrowser.singletonLockHeld("my-host-" + Long.MAX_VALUE));
    assertTrue("内容认不出来时宁可当占用", ChromeBrowser.singletonLockHeld("my-host-"));
    assertTrue("内容认不出来时宁可当占用", ChromeBrowser.singletonLockHeld("garbage"));
    assertTrue("null 也当占用", ChromeBrowser.singletonLockHeld(null));
  }
}
