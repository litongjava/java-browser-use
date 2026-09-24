package nexus.io.ai.browser.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;

/**
 * 用 Windows 自带的 OCR({@code Windows.Media.Ocr})把图上的文字读出来
 *
 * <p>
 * <b>为什么服务端要吸收这个能力</b>:验证码、二维码、公告维护图这类环节「必须看图」,而不少模型不支持
 * 图片输入({@code read_image} 直接报「does not declare image input」)。以前只能在每个任务 skill 里各写
 * 一遍「用 Windows 自带 OCR 读维护图」的做法(见 {@code skills/cnipa-trademark-register} 1.5 节),
 * 换个人写 skill 就要重踩一遍。这里把它固化成一个命令({@code ocr_image}),读不了图的模型也能自己答出
 * 「验证码是什么」这类问题的一部分。
 *
 * <p>
 * <b>为什么走 PowerShell</b>:{@code Windows.Media.Ocr} 是 WinRT API,Java 侧调它要过 JNI/COM;而系统自带的
 * Windows PowerShell 5.1 能直接以 {@code ContentType=WindowsRuntime} 的方式加载它,零依赖。脚本随包发布
 * (见 {@code resources/scripts/windows-ocr.ps1}),不往用户机器上装任何东西。
 *
 * <p>
 * <b>识别中文需要语言包</b>:默认用 {@code zh-Hans-CN};系统没装中文 OCR 包时会退回「用户配置语言」,
 * 读中文会退化成拉丁字母片段 —— 这种情况回执里会明确说明,而不是给一段看不懂的乱码让调用方猜。
 */
@Slf4j
public final class WindowsOcr {

  /** 默认识别语言:中文简体。Windows 的 OCR 语言包按语言分,装了哪个才能识别哪个 */
  public static final String DEFAULT_LANGUAGE = "zh-Hans-CN";

  /** 单次识别的超时(秒):OCR 是本地计算,几秒内没结果基本就是卡住了 */
  private static final int TIMEOUT_SECONDS = 60;

  private WindowsOcr() {
  }

  /** 这台机器上能不能用(非 Windows 直接不行) */
  public static boolean available() {
    return powershell() != null;
  }

  /**
   * 读一张图上的文字
   *
   * @param image    图片路径(本地文件)
   * @param language 识别语言,空则用 {@link #DEFAULT_LANGUAGE}
   * @return {@code {ok, text, language, engineLanguage, ms}};失败时 {@code ok=false} 且带 {@code error}
   */
  public static Kv read(Path image, String language) {
    Kv result = new Kv();
    String script = powershell();
    if (script == null) {
      result.set("ok", false)
          .set("error", "本机没有可用的 Windows PowerShell,读图能力不可用(OCR 走 Windows.Media.Ocr,只在 Windows 上有)")
          .set("hint", "非 Windows 或精简版系统上请改用 request_human_input 请人看图,或把 data.imageUrl 贴给用户");
      return result;
    }
    if (image == null || !Files.isRegularFile(image)) {
      result.set("ok", false).set("error", "找不到要识别的图片:" + image);
      return result;
    }
    Path scriptFile;
    Path outFile;
    try {
      // 脚本每次落一份临时文件:随包发布的是资源,不能直接拿资源 URL 当 -File 参数(打包进 jar 之后没有真实路径)
      scriptFile = Files.createTempFile("dsh-ocr-", ".ps1");
      byte[] body = readResource();
      // PowerShell 5.1 按 BOM 判断脚本编码;不写 BOM 时脚本里的中文会被按本地代码页(GBK)解读,
      // 于是中文注释变成乱码、乱码还会吃掉后面的引号,报出来却是 "Missing closing '}'" 这类语法错
      // (实测复现过)。这里统一补 BOM;资源里若已经带了 BOM 就先剥掉,免得变成双 BOM ——
      // 双 BOM 会让脚本开头多出一个 \uFEFF 字符,一样解析失败。
      if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) {
        byte[] stripped = new byte[body.length - 3];
        System.arraycopy(body, 3, stripped, 0, stripped.length);
        body = stripped;
      }
      byte[] withBom = new byte[body.length + 3];
      withBom[0] = (byte) 0xEF;
      withBom[1] = (byte) 0xBB;
      withBom[2] = (byte) 0xBF;
      System.arraycopy(body, 0, withBom, 3, body.length);
      Files.write(scriptFile, withBom);
      outFile = Files.createTempFile("dsh-ocr-", ".txt");
    } catch (IOException e) {
      result.set("ok", false).set("error", "准备 OCR 脚本失败:" + e.getMessage());
      return result;
    }

    List<String> command = new ArrayList<>();
    command.add(script);
    command.add("-NoProfile");
    command.add("-NonInteractive");
    command.add("-ExecutionPolicy");
    command.add("Bypass");
    command.add("-File");
    command.add(scriptFile.toString());
    command.add("-Path");
    command.add(image.toAbsolutePath().toString());
    command.add("-Out");
    command.add(outFile.toString());
    command.add("-Language");
    command.add(language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim());

    long startedAt = System.currentTimeMillis();
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      Process process = builder.start();
      // 脚本自己把结果写文件,stdout 只用来兜底诊断;不读干净的话缓冲区满了会把子进程卡住
      String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        result.set("ok", false).set("error", "OCR 超时(" + TIMEOUT_SECONDS + " 秒)");
        return result;
      }
      String text = Files.isRegularFile(outFile)
          ? new String(Files.readAllBytes(outFile), StandardCharsets.UTF_8).trim() : "";
      result.set("ms", System.currentTimeMillis() - startedAt);
      if (process.exitValue() == 2 || text.startsWith("NO_ENGINE")) {
        result.set("ok", false).set("engineMissing", true)
            .set("error", "这台机器上没有装可用的 Windows OCR 语言包")
            .set("hint", "在「设置 → 时间和语言 → 语言和区域」里给中文(或需要的语言)添加「光学字符识别」可选功能;"
                + "已安装的语言包:" + text.replace("NO_ENGINE:", ""));
        return result;
      }
      if (process.exitValue() != 0 || text.startsWith("ERROR:")) {
        result.set("ok", false)
            .set("error", text.startsWith("ERROR:") ? text.substring("ERROR:".length()) : truncate(console));
        return result;
      }
      result.set("ok", true).set("text", text)
          .set("language", language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim())
          .set("lineCount", text.isEmpty() ? 0 : text.split("\\r?\\n").length);
      return result;
    } catch (IOException e) {
      result.set("ok", false).set("error", "调用 OCR 失败:" + e.getMessage());
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result.set("ok", false).set("error", "OCR 被中断");
      return result;
    } finally {
      deleteQuietly(scriptFile);
      deleteQuietly(outFile);
    }
  }

  /** Windows PowerShell 的绝对路径;非 Windows 或找不到时返回 null */
  private static String powershell() {
    String os = System.getProperty("os.name", "");
    if (!os.toLowerCase(java.util.Locale.ROOT).contains("win")) {
      return null;
    }
    String systemRoot = System.getenv("SystemRoot");
    if (systemRoot == null || systemRoot.isBlank()) {
      systemRoot = System.getenv("WINDIR");
    }
    if (systemRoot == null || systemRoot.isBlank()) {
      return null;
    }
    // 刻意用 Windows PowerShell 5.1 而不是 pwsh 7:WinRT 互操作在 5.1 上是现成的,pwsh 7 需要额外模块
    Path legacy = java.nio.file.Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    if (Files.isRegularFile(legacy)) {
      return legacy.toString();
    }
    return null;
  }

  private static byte[] readResource() throws IOException {
    try (InputStream in = WindowsOcr.class.getResourceAsStream("/scripts/windows-ocr.ps1")) {
      if (in == null) {
        throw new IOException("找不到内置脚本 scripts/windows-ocr.ps1");
      }
      return in.readAllBytes();
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.debug("清理 OCR 临时文件失败:{}", e.getMessage());
    }
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > 400 ? value.substring(0, 400) + "..." : value;
  }
}
