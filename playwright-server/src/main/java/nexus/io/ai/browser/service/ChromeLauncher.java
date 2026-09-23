package nexus.io.ai.browser.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * 自己拉起本机安装的 Google Chrome,再把调试地址交出去(给 {@code connectOverCDP} 用)
 *
 * <p>
 * 为什么不能继续用 Playwright 的 {@code launchPersistentContext}:它走的是
 * {@code --remote-debugging-pipe},而 Chrome 136 之后**拒绝在默认用户数据目录上用 pipe 调试**,
 * 启动时会直接打印:
 *
 * <pre>
 * DevTools remote debugging requires a non-default data directory. Specify this using --user-data-dir.
 * </pre>
 *
 * <p>
 * 结果就是 Playwright 一直等不到浏览器握手,180 秒后超时。而
 * {@code --remote-debugging-port} 这条路径在同一个目录上是允许的(Chrome 153 实测可用),
 * 所以「用用户自己那份 profile(登录态现成)」只能自己拉进程:先带
 * {@code --remote-debugging-port=0} 启动 Chrome,从它的 stderr 里读
 * {@code DevTools listening on ws://127.0.0.1:<port>/...},再用 Playwright 接上去。
 *
 * <p>
 * 为什么读 stderr 而不是 {@code DevToolsActivePort} 文件:那个文件只在**非默认**用户数据目录
 * 下才会被写出来(实测默认目录不写),所以只能认 stderr 这一行。
 *
 * <p>
 * 托管 profile(没装 Chrome、Chrome 正在运行等)不走这里,仍旧用
 * {@code launchPersistentContext},那条路径上 pipe 是允许的。
 */
@Slf4j
public final class ChromeLauncher {

  /** Chrome 启动成功后打在 stderr 上的那一行 */
  private static final Pattern DEVTOOLS_LISTENING = Pattern.compile("DevTools listening on (ws://\\S+)");

  /** Chrome 明确拒绝时的输出:默认用户数据目录不允许远程调试 */
  private static final Pattern REFUSED = Pattern
      .compile("DevTools remote debugging requires a non-default data directory");

  /** 被拒绝时给调用方看的话,顺便说清楚怎么办 */
  static final String REFUSED_MESSAGE = "Chrome 拒绝在默认用户数据目录上开启远程调试"
      + "（Chrome 136 起的安全限制，Playwright/Selenium/Puppeteer 都一样接不上）。"
      + "要么改用托管 profile（browser.chrome.useUserProfile=false），"
      + "要么给这台机器加上企业策略 RemoteDebuggingAllowed=1 后再用用户 profile";

  /** 出错时回显多少行 Chrome 输出 */
  private static final int TAIL_LINES = 30;

  private ChromeLauncher() {
  }

  /**
   * 浏览器在打出调试地址之前就自己退出了
   *
   * <p>
   * 单独做成一个类型,是因为这个失败**有明确含义**,调用方要据此说清原因:最常见的情况是这个
   * profile 上已经有一个浏览器在运行 —— 新的进程会把命令行交给已有实例,然后自己退出(退出码 0)。
   * 也可能是 profile 被锁着、或者启动参数让浏览器直接不干了。有这个名字,调用方就不用去匹配中文
   * 消息了。
   */
  public static final class ExitedEarlyException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    ExitedEarlyException(String message) {
      super(message);
    }
  }

  /** 已经拉起来的 Chrome:进程 + 调试端点 */
  public static final class Launched {
    private final Process process;
    private final String endpoint;

    Launched(Process process, String endpoint) {
      this.process = process;
      this.endpoint = endpoint;
    }

    public Process process() {
      return process;
    }

    /** 形如 {@code http://127.0.0.1:6573},直接喂给 connectOverCDP */
    public String endpoint() {
      return endpoint;
    }
  }

  /**
   * 启动 Chrome 并等它把调试地址打出来
   *
   * @param executable    chrome 可执行文件
   * @param userDataDir   用户数据目录(User Data)
   * @param args          除 {@code --user-data-dir} 之外的启动参数
   * @param timeoutMs     等调试地址的上限
   * @return 已经可以连接的 Chrome
   * @throws IllegalStateException 超时或 Chrome 提前退出,消息里带 Chrome 自己的输出
   */
  public static Launched launch(Path executable, Path userDataDir, List<String> args, long timeoutMs) {
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.add("--user-data-dir=" + userDataDir);
    command.addAll(args);
    log.info("启动 Chrome:{}", String.join(" ", command));

    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new IllegalStateException("启动 Chrome 失败：" + e.getMessage());
    }

    Deque<String> tail = new ArrayDeque<>();
    String[] endpoint = new String[1];
    boolean[] refused = new boolean[1];
    Thread reader = new Thread(() -> readOutput(process, tail, endpoint, refused), "chrome-output-reader");
    reader.setDaemon(true);
    reader.start();

    long deadline = System.currentTimeMillis() + timeoutMs;
    while (endpoint[0] == null && !refused[0] && System.currentTimeMillis() < deadline && process.isAlive()) {
      sleepQuietly(100);
    }

    if (endpoint[0] != null) {
      return new Launched(process, endpoint[0]);
    }
    // 先记下状态再收尾:stop 之后进程一定不活着,会得出「立即退出」这种误导性结论
    boolean alive = process.isAlive();
    int exitCode = alive ? -1 : process.exitValue();
    stop(process);
    if (refused[0]) {
      // Chrome 拒绝之后不会自己退出(它会变成一个普通浏览器继续跑),所以必须我们自己收掉
      throw new IllegalStateException(REFUSED_MESSAGE);
    }
    if (!alive) {
      // 「进程已退出」这条路径不用等超时:实测 profile 被占用时新进程几百毫秒就退出了,
      // 所以调用方可以放心地「先试 CDP,失败了再退回托管 profile」
      throw new ExitedEarlyException("浏览器启动后立即退出(退出码 " + exitCode + ")：" + tailText(tail));
    }
    throw new IllegalStateException("等待调试端口超时(" + timeoutMs + "ms)：" + tailText(tail));
  }

  /** 把 Chrome 的 stderr 读干净(不读会把它堵死),顺便找出调试地址 */
  private static void readOutput(Process process, Deque<String> tail, String[] endpoint, boolean[] refused) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (log.isDebugEnabled()) {
          log.debug("chrome: {}", line);
        }
        synchronized (tail) {
          tail.addLast(line);
          while (tail.size() > TAIL_LINES) {
            tail.removeFirst();
          }
        }
        if (REFUSED.matcher(line).find()) {
          refused[0] = true;
        }
        if (endpoint[0] == null) {
          Matcher matcher = DEVTOOLS_LISTENING.matcher(line);
          if (matcher.find()) {
            String http = httpEndpoint(matcher.group(1));
            if (http != null) {
              endpoint[0] = http;
            }
          }
        }
      }
    } catch (IOException e) {
      log.debug("读取 Chrome 输出结束:{}", e.getMessage());
    }
  }

  /** ws://127.0.0.1:6573/devtools/browser/xxx -> http://127.0.0.1:6573 */
  private static String httpEndpoint(String websocketUrl) {
    try {
      java.net.URI uri = java.net.URI.create(websocketUrl);
      if (uri.getHost() == null || uri.getPort() < 0) {
        return null;
      }
      return "http://" + uri.getHost() + ":" + uri.getPort();
    } catch (RuntimeException e) {
      log.warn("解析 Chrome 调试地址失败:{}", websocketUrl);
      return null;
    }
  }

  /** 兜底收尾:进程还在就杀掉(正常路径上 Playwright 的 browser.close() 已经让 Chrome 退出了) */
  public static void stop(Process process) {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    for (int i = 0; i < 30 && process.isAlive(); i++) {
      sleepQuietly(100);
    }
    if (process.isAlive()) {
      log.warn("Chrome 没有在 3 秒内退出,强制结束进程 {}", process.pid());
      process.destroyForcibly();
    }
  }

  private static String tailText(Deque<String> tail) {
    synchronized (tail) {
      if (tail.isEmpty()) {
        return "(Chrome 没有输出)";
      }
      return String.join(" | ", tail);
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
