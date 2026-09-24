package nexus.io.ai.browser.docs;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Windows 脚本的编码卫生:`.ps1` 必须带 UTF-8 BOM,`.cmd` 必须纯 ASCII
 *
 * <p>
 * <b>为什么值得写成一个测试</b>:这两条都是「改了注释就炸」的坑,而且报错信息完全指不到病根。
 *
 * <ul>
 * <li>没有 BOM 的 `.ps1`,Windows PowerShell 5.1 会按 ANSI(中文机器上是 GBK)去读 —— 中文注释
 * 变成乱码,乱码里的字节还会把后面的引号/行尾吃掉,于是报出来的是
 * {@code Unexpected token 'off'}、{@code Unexpected token 'cmd.exe'}、
 * {@code The string is missing the terminator}、{@code '@echo' can be used only as an argument to a command}
 * 这类**看起来像语法写错、实际是编码**的错误。实测踩过两次:一次是 `scripts/run/start-server.ps1`
 * 初次落盘没带 BOM,一次是用编辑器/工具改过之后 BOM 被吃掉。改完 `.ps1` 一定要把 BOM 补回去。</li>
 * <li>`.cmd` 相反:cmd.exe 按 OEM 代码页读批处理,UTF-8 中文注释会被当成语法
 * (仓库里 `client/dsb.cmd` 的注释就写着 keep this file ASCII-only)。</li>
 * </ul>
 *
 * <p>
 * 这个测试和工作目录无关的那部分(比如文件是不是存在)不一样:它只读字节,不启动任何进程,
 * 所以可以放心放在单元测试里。
 */
public class PowerShellScriptHygieneTest {

  /** 仓库根(Maven 的工作目录是 playwright-server) */
  private static final Path ROOT = Paths.get("..");

  /** 这些目录里的脚本不是我们维护的产物,跳过(tmp 是随手放临时脚本的地方) */
  private static final List<String> SKIP_DIRS = List.of("target", "node_modules", ".git", "data", "logs", "upload",
      "dist", "tmp");

  /**
   * 这些路径下的 `.ps1` **故意不带 BOM**
   *
   * <p>
   * `src/main/resources/scripts/*.ps1` 不是给人直接执行的:`WindowsOcr` 会把资源读成字节、**自己补上
   * BOM** 再落到临时文件跑(`WindowsOcr.prepareScript`)。如果资源里已经带了 BOM,就会变成双 BOM,
   * 脚本开头的 {@code \uFEFF} 反而会让 PowerShell 解析失败。所以这里对它们只要求"不要有 BOM",
   * 由 {@code WindowsOcr} 负责编码 —— 那边的兜底逻辑(先剥掉已有 BOM 再补)也在。
   */
  private static final List<String> BOM_MANAGED_BY_CODE = List.of("resources/scripts");

  private static List<Path> scripts;

  @BeforeClass
  public static void collect() throws IOException {
    scripts = new ArrayList<>();
    if (!Files.isDirectory(ROOT)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(ROOT, 6)) {
      stream.filter(Files::isRegularFile)
          .filter(path -> {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return lower.endsWith(".ps1") || lower.endsWith(".cmd") || lower.endsWith(".bat");
          })
          .filter(path -> {
            for (Path part : ROOT.relativize(path)) {
              if (SKIP_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
              }
            }
            return true;
          })
          .forEach(scripts::add);
    }
  }

  /** 这个脚本的 BOM 由代码负责(见 {@link #BOM_MANAGED_BY_CODE}) */
  private static boolean bomManagedByCode(Path path) {
    String normalized = ROOT.relativize(path).toString().replace('\\', '/');
    for (String hint : BOM_MANAGED_BY_CODE) {
      if (normalized.contains(hint)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void everyPowerShellScriptStartsWithUtf8Bom() throws IOException {
    Assume.assumeTrue("没有找到任何 .ps1,跳过", scripts.stream().anyMatch(p -> p.toString().endsWith(".ps1")));
    List<String> offenders = new ArrayList<>();
    for (Path path : scripts) {
      if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".ps1")) {
        continue;
      }
      byte[] bytes = Files.readAllBytes(path);
      boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
          && (bytes[2] & 0xFF) == 0xBF;
      // 纯 ASCII 的脚本没有 BOM 也无所谓(ANSI 与 ASCII 一致);**含非 ASCII 就必须带 BOM**,
      // 否则 5.1 会按 GBK 读,中文注释变乱码,乱码还会吃掉后面的引号 → 报成语法错
      boolean hasNonAscii = false;
      for (byte value : bytes) {
        if ((value & 0xFF) > 0x7F) {
          hasNonAscii = true;
          break;
        }
      }
      if (hasNonAscii && !bom && !bomManagedByCode(path)) {
        offenders.add(ROOT.relativize(path).toString());
      }
    }
    assertTrue("这些 .ps1 含非 ASCII 字符却没有 UTF-8 BOM:Windows PowerShell 5.1 会按 ANSI 读它们,"
        + "中文注释会变成乱码并让脚本报语法错(报错形如 Unexpected token 'off' / "
        + "The string is missing the terminator,与真实病因无关)。补 BOM:"
        + "[System.IO.File]::WriteAllText(<路径>, [System.IO.File]::ReadAllText(<路径>,[Text.Encoding]::UTF8),"
        + " (New-Object Text.UTF8Encoding($true))):" + offenders, offenders.isEmpty());
  }

  @Test
  public void everyBatchFileIsAsciiOnly() throws IOException {
    Assume.assumeTrue("没有找到任何 .cmd/.bat,跳过",
        scripts.stream().anyMatch(p -> !p.toString().toLowerCase(Locale.ROOT).endsWith(".ps1")));
    List<String> offenders = new ArrayList<>();
    for (Path path : scripts) {
      String name = path.toString().toLowerCase(Locale.ROOT);
      if (name.endsWith(".ps1")) {
        continue;
      }
      for (byte value : Files.readAllBytes(path)) {
        if ((value & 0xFF) > 0x7F) {
          offenders.add(ROOT.relativize(path).toString());
          break;
        }
      }
    }
    assertTrue("这些 .cmd/.bat 里有非 ASCII 字节:cmd.exe 按 OEM 代码页读批处理,中文注释会被当成语法"
        + "(注释请写英文,或用 rem 之外的写法):" + offenders, offenders.isEmpty());
  }

  /** 顺带看一眼:脚本里不该出现 UTF-8 BOM 之外的行尾陷阱 —— 这里只做提醒式检查 */
  @Test
  public void scriptsAreReadableAsUtf8() throws IOException {
    Assume.assumeFalse("没有任何脚本,跳过", scripts.isEmpty());
    List<String> offenders = new ArrayList<>();
    for (Path path : scripts) {
      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      if (text.indexOf('\uFFFD') >= 0) {
        offenders.add(ROOT.relativize(path).toString());
      }
    }
    assertTrue("这些脚本不是合法 UTF-8(读出来有替换字符):" + offenders, offenders.isEmpty());
  }
}
