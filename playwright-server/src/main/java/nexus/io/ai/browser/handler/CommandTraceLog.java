package nexus.io.ai.browser.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ChromeBrowser;
import nexus.io.model.body.RespBodyVo;

/**
 * 每一次「请求 → 响应」都落一份到本地,便于事后排查与追踪
 *
 * <p>
 * 服务的响应里 `data.text` 已经是给模型读的页面快照,但排查问题时往往要的是「<b>我当时到底发了什么、
 * 服务到底回了什么</b>」——尤其是批量 {@code commands} 里某一步失败、索引越界、点击没生效这类问题,
 * 只看最后一次响应看不出来。所以这里在**唯一的业务端点**上做审计:请求体与响应体原样留档。
 *
 * <p>
 * 每个自然日一个目录,落在 {@code <启动目录>/logs/trace/<yyyyMMdd>/} 下,一共三种产物:
 *
 * <table border="1">
 * <caption>产物</caption>
 * <tr><th>文件</th><th>内容</th><th>用途</th></tr>
 * <tr><td>{@code steps.log}</td><td>每次调用一行:时间、序号、任务 ID、方法、成功与否、耗时、一句话结果</td><td><b>人看的时间线</b>,一眼看出第几步开始不对</td></tr>
 * <tr><td>{@code calls.jsonl}</td><td>每次调用一行 JSON:调用摘要 + 从 {@code data} 里摘出来的关键字段(url/title/seq/screenshot/state_file/changed…)+ 完整请求体</td><td>机器读、按 id/method 过滤,不占内存</td></tr>
 * <tr><td>{@code 000001-1001-get_browser_state.json}</td><td>这一次调用的**完整**请求与**完整**响应(含整页 {@code data.text})</td><td>要看原始报文、要复现某一步时的唯一凭据</td></tr>
 * </table>
 *
 * <p>
 * 配置项(读法与 {@link ChromeBrowser} 一致,系统属性 / 环境变量 / {@code app.properties} /
 * {@code browser.properties} 优先级从高到低):
 * <ul>
 * <li>{@code browser.trace.enabled}(默认 {@code true}):整个审计开关。关掉就完全不落盘;</li>
 * <li>{@code browser.trace.dir}:留空时是 {@code <启动目录>/logs/trace};</li>
 * <li>{@code browser.trace.maxRecordChars}(默认 8000000):单次调用完整报文的上限,超了截断并打标记
 * (只有 {@code screenshot} 不带 {@code path} 时的 base64 大图可能撞到这个值);</li>
 * <li>{@code browser.trace.redact.enabled}(默认 {@code true}):落盘前脱敏。填表这类任务里请求体带着
 * 姓名、手机号、证件号、详细地址,原文留在磁盘上迟早出问题;</li>
 * <li>{@code browser.trace.redact}:追加的自定义正则(逗号分隔),在内置规则之后生效;</li>
 * <li>{@code browser.trace.redact.mask}(默认 {@code ***}):命中后替换成的文本。</li>
 * </ul>
 *
 * <p>
 * 内置脱敏规则:手机号、18 位身份证号/统一社会信用代码、邮箱、16~19 位长数字。<b>脱敏是「尽力而为」</b>:
 * 它按模式匹配,不认识的个人信息(例如姓名、门牌号)不会被掩掉,所以交付前仍要自己看一眼日志目录。
 *
 * <p>
 * <b>这里绝不影响业务</b>:所有写盘都在 try/catch 里,失败只留一条警告(且同一种失败只警告一次),
 * 磁盘满、目录没权限都不会让浏览器命令失败。
 */
@Slf4j
public final class CommandTraceLog {

  /** 总开关 */
  public static final String KEY_ENABLED = "browser.trace.enabled";
  /** 落盘目录,默认 {@code <启动目录>/logs/trace} */
  public static final String KEY_DIR = "browser.trace.dir";
  /** 单次调用完整报文的上限(字符数) */
  public static final String KEY_MAX_RECORD_CHARS = "browser.trace.maxRecordChars";
  /** 脱敏开关,默认开 */
  public static final String KEY_REDACT_ENABLED = "browser.trace.redact.enabled";
  /** 自定义脱敏正则(逗号分隔),会**追加**在内置规则之后 */
  public static final String KEY_REDACT = "browser.trace.redact";
  /** 命中后替换成的文本 */
  public static final String KEY_REDACT_MASK = "browser.trace.redact.mask";

  /**
   * 内置脱敏规则:手机号、18 位身份证/统一社会信用代码、邮箱、银行卡式长数字
   *
   * <p>
   * 每条都带「前后不能还是数字/字母」的边界(而不是 {@code \b}),原因是有过一次实测教训:手机号规则
   * {@code 1[3-9]\d{9}} 把 13 位的毫秒时间戳当成了手机号 —— 2026 年的 epoch 毫秒是 {@code 1790…},
   * 开头正好是 {@code 17},于是日志里的 {@code recordedSince} 被掩成了 {@code ***87},排查时反而
   * 看不懂。加上边界后,「一长串数字里的一截」不再算命中,独立的 11 位号码照旧命中。
   */
  private static final String[] BUILT_IN_PATTERNS = { "(?<!\\d)1[3-9]\\d{9}(?!\\d)",
      "(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])",
      "(?<![0-9A-Za-z])[0-9A-HJ-NPQRTUWXY]{18}(?![0-9A-Za-z])",
      "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "(?<!\\d)\\d{16,19}(?!\\d)" };

  private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter HUMAN_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  /** 全局递增序号,和当天的目录一起定位一次调用 */
  private static final AtomicLong SEQ = new AtomicLong();
  /** 出过一次写盘错误就不再刷屏 */
  private static final AtomicBoolean WARNED = new AtomicBoolean();

  private CommandTraceLog() {
  }

  /**
   * 记一次调用
   *
   * @param requestBody     客户端发来的原始 JSON 字符串(可能为 null / 不是合法 JSON)
   * @param response        即将写回客户端的响应,已经过 {@link ResponseFormatter} 投影
   * @param startedAtMillis 收到请求的时间
   */
  public static void record(String requestBody, RespBodyVo response, long startedAtMillis) {
    if (!enabled()) {
      return;
    }
    try {
      long seq = SEQ.incrementAndGet();
      long now = System.currentTimeMillis();
      JSONObject request = parse(requestBody);
      String method = request == null ? null : request.getString("method");
      String taskId = request == null ? null : request.getString("id");
      JSONObject responseJson = toJson(response);
      // 脱敏在**落盘之前**做:日志里有证件号、手机号、地址这类个人信息,原文留在磁盘上迟早出问题
      String redactedBody = redact(requestBody);
      redactJson(request);
      redactJson(responseJson);

      Path dir = dayDir();
      Files.createDirectories(dir);
      String base = String.format("%06d-%s-%s", seq, taskId == null ? "na" : taskId, safeName(method));
      writeFile(dir.resolve(base + ".json"),
          fullRecord(redactedBody, responseJson, startedAtMillis, now, seq, method, taskId));
      append(dir.resolve("calls.jsonl"), summaryLine(request, responseJson, startedAtMillis, now, seq, method, taskId,
          base));
      append(dir.resolve("steps.log"), humanLine(responseJson, startedAtMillis, now, seq, method, taskId));
    } catch (RuntimeException | IOException | OutOfMemoryError e) {
      if (WARNED.compareAndSet(false, true)) {
        log.warn("写调用追踪日志失败(后续同类错误不再重复提示):{}", e.toString());
      }
    }
  }

  /**
   * 记一次文件暂存({@code POST /playwright/upload})
   *
   * <p>上传不走命令表,但「我到底传没传上去、传成了哪个文件」同样是排查时最先要看的,所以单独在
   * 同一天的目录里追加一行 {@code uploads.log}。文件名与大小会留档,<b>文件内容不会</b>。
   */
  public static void recordUpload(com.jfinal.kit.Kv saved) {
    if (!enabled() || saved == null) {
      return;
    }
    try {
      Path dir = dayDir();
      Files.createDirectories(dir);
      JSONObject line = new JSONObject();
      line.put("ts", iso(System.currentTimeMillis()));
      for (String key : new String[] { "filename", "relativePath", "path", "size", "existed", "sha256", "contentType" }) {
        Object value = saved.get(key);
        if (value != null) {
          line.put(key, redact(String.valueOf(value)));
        }
      }
      append(dir.resolve("uploads.log"), JSON.toJSONString(line));
    } catch (RuntimeException | IOException e) {
      if (WARNED.compareAndSet(false, true)) {
        log.warn("写上传追踪日志失败(后续同类错误不再重复提示):{}", e.toString());
      }
    }
  }

  /** 追踪日志目录(当天的),给 {@code start} 的回执用,方便交付时告诉用户去哪清理 */
  public static Path currentDir() {
    return dayDir();
  }

  // ==================== 脱敏 ====================

  /** 脱敏总开关,默认开;关掉后日志是原文(排查「值到底传没传对」时可能需要) */
  public static boolean redactEnabled() {
    String configured = ChromeBrowser.config(KEY_REDACT_ENABLED);
    return configured == null || Boolean.parseBoolean(configured);
  }

  /**
   * 把一段文本里的个人信息替换成掩码
   *
   * <p>内置规则覆盖手机号、18 位身份证/统一社会信用代码、邮箱、16~19 位长数字,可以用
   * {@code browser.trace.redact} 追加自定义正则(逗号分隔)。规则写坏时只跳过那一条,不影响其它规则。
   */
  public static String redact(String text) {
    if (text == null || text.isEmpty() || !redactEnabled()) {
      return text;
    }
    String mask = mask();
    String result = text;
    for (String pattern : patterns()) {
      try {
        result = result.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(mask));
      } catch (RuntimeException e) {
        // 单条规则非法不该让整条日志丢掉
      }
    }
    return result;
  }

  /** 递归脱敏一个 JSON 结构里的所有字符串值(键名不动,免得看不出请求长什么样) */
  private static void redactJson(Object node) {
    if (!redactEnabled() || node == null) {
      return;
    }
    if (node instanceof JSONObject) {
      JSONObject object = (JSONObject) node;
      for (String key : new java.util.ArrayList<>(object.keySet())) {
        Object value = object.get(key);
        if (value instanceof String) {
          object.put(key, redact((String) value));
        } else {
          redactJson(value);
        }
      }
    } else if (node instanceof JSONArray) {
      JSONArray array = (JSONArray) node;
      for (int i = 0; i < array.size(); i++) {
        Object value = array.get(i);
        if (value instanceof String) {
          array.set(i, redact((String) value));
        } else {
          redactJson(value);
        }
      }
    }
  }

  private static List<String> patterns() {
    List<String> all = new java.util.ArrayList<>(java.util.Arrays.asList(BUILT_IN_PATTERNS));
    String configured = ChromeBrowser.config(KEY_REDACT);
    if (configured != null) {
      for (String piece : configured.split(",")) {
        String trimmed = piece.trim();
        if (!trimmed.isEmpty()) {
          all.add(trimmed);
        }
      }
    }
    return all;
  }

  private static String mask() {
    String configured = ChromeBrowser.config(KEY_REDACT_MASK);
    return configured == null ? "***" : configured;
  }

  /** 今天的目录:换天后自动落到新目录,不需要重启 */
  private static Path dayDir() {
    String configured = ChromeBrowser.config(KEY_DIR);
    Path root = configured != null ? Paths.get(configured) : Paths.get("").toAbsolutePath().resolve("logs").resolve("trace");
    return root.resolve(LocalDate.now().format(FILE_STAMP));
  }

  private static boolean enabled() {
    String configured = ChromeBrowser.config(KEY_ENABLED);
    return configured == null || Boolean.parseBoolean(configured);
  }

  private static int maxRecordChars() {
    String configured = ChromeBrowser.config(KEY_MAX_RECORD_CHARS);
    if (configured == null) {
      return 8_000_000;
    }
    try {
      int parsed = Integer.parseInt(configured.trim());
      return parsed > 0 ? parsed : 8_000_000;
    } catch (NumberFormatException e) {
      return 8_000_000;
    }
  }

  // ==================== 三种产物 ====================

  /** 完整报文:请求 + 响应原样 */
  private static String fullRecord(String requestBody, JSONObject response, long startedAt, long finishedAt, long seq,
      String method, String taskId) {
    JSONObject record = new JSONObject();
    record.put("seq", seq);
    record.put("startedAt", iso(startedAt));
    record.put("finishedAt", iso(finishedAt));
    record.put("durationMs", finishedAt - startedAt);
    record.put("taskId", taskId);
    record.put("method", method);
    record.put("request", requestBody);
    record.put("response", response);
    return truncate(JSON.toJSONString(record, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
  }

  /** 摘要行:人/机器都能用,去掉了整页文本这类大块内容,但保留完整请求体 */
  private static String summaryLine(JSONObject request, JSONObject response, long startedAt, long finishedAt, long seq,
      String method, String taskId, String base) {
    JSONObject line = new JSONObject();
    line.put("seq", seq);
    line.put("ts", iso(startedAt));
    line.put("durationMs", finishedAt - startedAt);
    line.put("taskId", taskId);
    line.put("method", method);
    line.put("ok", response == null ? null : response.get("ok"));
    line.put("code", response == null ? null : response.get("code"));
    line.put("msg", response == null ? null : response.get("msg"));
    line.put("record", base + ".json");
    line.put("request", request);
    JSONObject highlights = highlights(response);
    if (!highlights.isEmpty()) {
      line.put("result", highlights);
    }
    return JSON.toJSONString(line);
  }

  /**
   * 从 {@code data} 里摘出排查时最常看的那几个字段
   *
   * <p>整页 {@code text}/{@code html}/{@code base64} 一律不进摘要(它们在完整报文里),这里只留「这一步
   * 去了哪、动没动、留了哪张图」。
   */
  static JSONObject highlights(JSONObject response) {
    JSONObject out = new JSONObject();
    if (response == null) {
      return out;
    }
    Object dataObject = response.get("data");
    if (!(dataObject instanceof JSONObject)) {
      return out;
    }
    JSONObject data = (JSONObject) dataObject;
    for (String key : new String[] { "url", "title", "seq", "screenshot", "screenshot_path", "state_file", "changed",
        "changeStatus", "status", "errorCode", "id", "engine", "browser", "pageIndex", "count", "succeeded", "failed",
        "closed", "remaining", "visible", "enabled", "checked", "value", "path", "requestId", "urlAfter" }) {
      Object value = data.get(key);
      if (value != null) {
        out.put(key, value);
      }
    }
    if (data.get("text") instanceof String) {
      out.put("textLength", ((String) data.get("text")).length());
    }
    // 批量:每一步的成败单独列出来,哪一条开始失败一目了然
    if (data.get("results") instanceof JSONArray) {
      JSONArray results = data.getJSONArray("results");
      JSONArray steps = new JSONArray();
      for (int i = 0; i < results.size(); i++) {
        JSONObject step = results.getJSONObject(i);
        if (step == null) {
          continue;
        }
        JSONObject item = new JSONObject();
        item.put("index", step.get("index"));
        item.put("command", step.get("command"));
        item.put("ok", step.get("ok"));
        if (step.get("msg") != null) {
          item.put("msg", step.get("msg"));
        }
        JSONObject stepData = step.getJSONObject("data");
        if (stepData != null) {
          for (String key : new String[] { "url", "title", "seq", "screenshot", "state_file", "changed", "result" }) {
            if (stepData.get(key) != null) {
              item.put(key, stepData.get(key));
            }
          }
        }
        steps.add(item);
      }
      out.put("results", steps);
    }
    return out;
  }

  /** 人看的一行:时间 序号 任务 方法 结果 耗时 */
  private static String humanLine(JSONObject response, long startedAt, long finishedAt, long seq, String method,
      String taskId) {
    JSONObject highlights = highlights(response);
    StringBuilder line = new StringBuilder();
    line.append(LocalDateTime.ofInstant(Instant.ofEpochMilli(startedAt), ZoneId.systemDefault()).format(HUMAN_STAMP));
    line.append(" #").append(seq);
    line.append(" id=").append(taskId == null ? "-" : taskId);
    line.append(" ").append(method == null ? "-" : method);
    boolean ok = response != null && Boolean.TRUE.equals(response.get("ok"));
    line.append(ok ? " OK" : " FAIL");
    line.append(" ").append(finishedAt - startedAt).append("ms");
    Object msg = response == null ? null : response.get("msg");
    if (msg != null && !String.valueOf(msg).isBlank()) {
      line.append(" msg=").append(oneLine(String.valueOf(msg)));
    }
    for (Map.Entry<String, Object> entry : orderedHighlights(highlights).entrySet()) {
      String value = String.valueOf(entry.getValue());
      if ("results".equals(entry.getKey())) {
        continue;
      }
      if (value.isBlank() || "null".equals(value)) {
        continue;
      }
      line.append(" ").append(entry.getKey()).append("=").append(oneLine(value));
    }
    if (highlights.get("results") instanceof JSONArray) {
      for (Object stepObject : highlights.getJSONArray("results")) {
        if (stepObject instanceof JSONObject) {
          JSONObject step = (JSONObject) stepObject;
          line.append(" | ").append(step.get("index")).append(":").append(step.get("command"))
              .append(Boolean.TRUE.equals(step.get("ok")) ? "=ok" : "=FAIL");
          if (step.get("msg") != null) {
            line.append("(").append(oneLine(String.valueOf(step.get("msg")))).append(")");
          }
        }
      }
    }
    return line.toString();
  }

  private static Map<String, Object> orderedHighlights(JSONObject highlights) {
    Map<String, Object> ordered = new LinkedHashMap<>();
    for (String key : highlights.keySet()) {
      ordered.put(key, highlights.get(key));
    }
    return ordered;
  }

  // ==================== 工具 ====================

  private static JSONObject parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return JSONObject.parseObject(body);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static JSONObject toJson(RespBodyVo response) {
    if (response == null) {
      return null;
    }
    try {
      return JSON.parseObject(JSON.toJSONString(response));
    } catch (RuntimeException e) {
      JSONObject fallback = new JSONObject();
      fallback.put("ok", response.isOk());
      fallback.put("code", response.getCode());
      fallback.put("msg", response.getMsg());
      fallback.put("serializeError", e.toString());
      return fallback;
    }
  }

  private static void writeFile(Path file, String content) throws IOException {
    Files.write(file, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  /** 追加一行;同一时刻可能有多条命令在写,所以串行化。跨进程不保证(服务只有这一个端点,够用) */
  private synchronized static void append(Path file, String line) throws IOException {
    Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
        StandardOpenOption.APPEND, StandardOpenOption.WRITE);
  }

  private static String truncate(String value) {
    int max = maxRecordChars();
    if (value.length() <= max) {
      return value;
    }
    return value.substring(0, max) + "\n... [追踪日志按 " + KEY_MAX_RECORD_CHARS + "=" + max + " 截断,原始长度 "
        + value.length() + " 字符]";
  }

  private static String safeName(String method) {
    if (method == null || method.isBlank()) {
      return "unknown";
    }
    return method.replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  private static String oneLine(String value) {
    String flat = value.replaceAll("\\s+", " ").trim();
    return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
  }

  private static String iso(long millis) {
    return Instant.ofEpochMilli(millis).toString();
  }
}