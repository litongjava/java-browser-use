package nexus.io.ai.browser.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import nexus.io.ai.browser.actions.registry.CommandTable;

/**
 * 校验技能文档与命令表保持一致,避免文档漂移
 *
 * <p>技能文档现在放在仓库的 {@code skills/<技能名>/SKILL.md}(装进 DSH 时位于
 * {@code .dsh/skills/<技能名>/SKILL.md}),而 Maven 测试的工作目录是 playwright-server,所以用 ..
 * 回到仓库根。历史位置(仓库根的 SKILL.md、{@code .dsh/skills/...})也认,免得换个装法就失效。
 *
 * <p><b>命令表只由主技能文档负责覆盖</b>:{@code skills/} 下还有别的技能(例如某个具体站点的操作手册),
 * 它们讲的是「怎么把命令组合起来用」,不该被迫把 90 多个方法都列一遍。所以:
 * <ul>
 * <li>命令清单覆盖、端点、frontmatter、旧接口名 → 只查主技能({@code SKILL_NAME});</li>
 * <li>「文档里当作命令写的名字必须真实存在」→ 查**每一个**技能文档,免得某份操作手册里写了一个
 * 不存在的命令名,模型照着发请求才发现。</li>
 * </ul>
 *
 * <p>文档不存在时跳过整个类,便于单独拷贝模块构建。
 *
 * <p><b>写站点 skill 时的约定</b>(见 {@code skills/README.md}):文档里**单反引号**包起来的东西被当作
 * 命令名检查,**双反引号**包起来的当作「页面里的名字」不检查。所以 Vue 字段、CSS 类名、HTML id、URL 参数、
 * 接口字段这些 snake_case 标识符请写成 `` 双反引号 `` 的形式。
 */
public class SkillDocConsistencyTest {

  /** 技能名:frontmatter 的 name,也是装进 DSH 时用的目录名 */
  private static final String SKILL_NAME = "deepseek-browser-use";

  /**
   * 依次尝试的位置
   *
   * <p>{@code skills/SKILL.md} 是当前的实际位置;{@code skills/<技能名>/SKILL.md} 与
   * {@code .dsh/skills/<技能名>/SKILL.md} 是「一技能一目录」的装法;仓库根的 {@code SKILL.md}
   * 是历史位置。都认,免得挪一次目录这个守卫就静默失效(它跳过时只会报 Skipped,不会报错)。
   */
  private static final List<Path> SKILL_PATHS = List.of(Paths.get("..", "skills", "SKILL.md"),
      Paths.get("..", "skills", SKILL_NAME, "SKILL.md"), Paths.get("..", "SKILL.md"),
      Paths.get("..", ".dsh", "skills", SKILL_NAME, "SKILL.md"));

  /** 主技能文档的上一级目录:仓库根的 skills/(找不到时退回仓库根) */
  private static final List<Path> SKILL_ROOTS = List.of(Paths.get("..", "skills"), Paths.get(".."));

  /** 文档里形如 `method_name` 的命令名 */
  private static final Pattern DOC_COMMAND = Pattern.compile("`([a-z][a-z0-9_]{2,})`");

  /**
   * 双反引号包住的内容:作者显式声明「这不是命令」
   *
   * <p>
   * <b>为什么要有这个约定</b>:站点操作手册**必然**会大量提到 snake_case 的**非命令标识符** —— Vue 表单
   * 字段({@code business_license_stuff})、CSS 类名({@code mall_invoice_dialog_container})、HTML
   * id({@code dsh_up_0})、URL 查询参数({@code order_id})、接口响应字段({@code has_cname_record})。
   * 而「文档里当作命令写的名字必须真实存在」这条检查靠词形猜,于是每写一个站点 skill 都要把这 20 多处
   * 改成「加点号 / 加 # / 加对象前缀」的写法 —— **让文档迁就测试**,而且这条规则没有任何地方写明,
   * 下一个人还会踩。
   *
   * <p>现在给作者一个显式标记:用**双反引号**包住的标识符一律不当命令检查,视觉上也能区分
   * 「这是命令」(单反引号)与「这是页面里的名字」(双反引号)。
   */
  private static final Pattern DOUBLE_BACKTICK = Pattern.compile("``.+?``", Pattern.DOTALL);

  /**
   * 文档里出现这些词会被当成命令名来比对,但它们不是命令
   *
   * <p>名单越短越好:能用双反引号声明的,就不该往这里塞。
   */
  private static final List<String> NOT_COMMANDS = List.of("true", "false", "null", "json", "get", "post", "put",
      "headless", "params", "method", "base64", "png", "txt", "http", "https", "localhost", "curl", "java", "jar",
      "data", "id", "url", "title", "text", "index", "selector", "path", "name", "value", "state", "tabs", "dom",
      "ok", "msg", "code", "error", "seq", "current", "browser_state", "pageindex", "fullpage", "timeoutseconds",
      "urlpattern", "maxchars", "lookbackseconds", "viewportexpansion", "highlight", "stoponerror", "commands",
      "requestid", "answer", "prompt", "keys", "button", "deltay", "numpages", "extractlinks",
      "headersjson", "colorscheme", "latitude", "longitude", "width", "height", "offline", "consume", "dismiss",
      "requestfilter", "includerequests", "includeconsole", "filter", "hoverdelayms", "targetindex", "clippath",
      "clipx", "clipy", "clipwidth", "clipheight", "role", "label", "expression", "seconds", "on",
      // get_browser_state 返回的字段名,不是命令
      "pixels_above", "pixels_below", "viewport_height", "page_height", "state_file", "screenshot_path",
      "screenshot_error", "outerhtml",
      // 后来补上的参数名与返回字段:词形像命令,但都是「参数/字段」,文档里必须用单反引号才读得通
      "includeframes", "includeelements", "maxelements", "frame", "frameindex", "frameurl", "framecount",
      "stepid", "steps", "expiresat", "ocrtext", "ocrlanguage", "imagebase64", "imagepath", "imageurl",
      "imagesize", "listeners", "haslisteners", "fileslength", "matchedby", "classname", "previousengine",
      "previousbrowser", "profileseenbefore", "profilenote", "consumed", "screenshotted");

  /** 逐个技能文档里,作者用 frontmatter 的 nonCommands 声明的非命令名(并入 NOT_COMMANDS) */
  private static final Pattern NON_COMMANDS_FRONTMATTER = Pattern.compile("(?m)^nonCommands:\\s*\\[(.*?)\\]\\s*$");

  private static String doc;

  @BeforeClass
  public static void loadDoc() throws IOException {
    Path found = null;
    for (Path candidate : SKILL_PATHS) {
      if (Files.exists(candidate)) {
        found = candidate;
        break;
      }
    }
    Assume.assumeTrue("技能文档不存在:" + SKILL_PATHS.get(0).toAbsolutePath().normalize(), found != null);
    doc = new String(Files.readAllBytes(found), StandardCharsets.UTF_8);
  }

  /** skills/ 下的全部技能文档(主技能 + 各站点操作手册) */
  private static List<Path> allSkillDocs() throws IOException {
    List<Path> docs = new ArrayList<>();
    for (Path root : SKILL_ROOTS) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (java.util.stream.Stream<Path> stream = Files.walk(root, 3)) {
        stream.filter(path -> path.getFileName().toString().equals("SKILL.md")).filter(Files::isRegularFile)
            .forEach(docs::add);
      }
      if (!docs.isEmpty()) {
        break;
      }
    }
    return docs;
  }

  /** 命令表里的每个方法都要在文档里出现,否则模型根本不知道有这个能力 */
  @Test
  public void everyCommandIsDocumented() {
    List<String> missing = new ArrayList<>();
    for (String command : CommandTable.names()) {
      if (!doc.contains("`" + command + "`")) {
        missing.add(command);
      }
    }
    assertEquals("命令表里有方法没写进技能文档:" + missing, 0, missing.size());
  }

  /** 反向检查:文档里当作命令写的名字必须在命令表里存在 */
  @Test
  public void documentedCommandsExist() {
    List<String> unknown = new ArrayList<>();
    Matcher matcher = DOC_COMMAND.matcher(stripNonCommands(doc, null));
    while (matcher.find()) {
      String name = matcher.group(1);
      if (NOT_COMMANDS.contains(name.toLowerCase())) {
        continue;
      }
      // 只检查带下划线的名字:文档里的普通英文词不会带下划线
      if (!name.contains("_")) {
        continue;
      }
      if (CommandTable.get(name) == null) {
        unknown.add(name);
      }
    }
    assertTrue("文档里写了命令表里不存在的命令:" + unknown, unknown.isEmpty());
  }

  /**
   * 把「作者显式声明为非命令」的内容从待检查文本里去掉
   *
   * <p>两种声明方式(见 {@code DOC_CONVENTIONS}):
   * <ul>
   * <li>**双反引号**:``subject_name`` —— 零配置、词法可判,推荐;</li>
   * <li>frontmatter 的 {@code nonCommands: [a, b]} —— 一份文档里要声明很多个时更整齐。</li>
   * </ul>
   */
  private static String stripNonCommands(String text, Path path) {
    String stripped = DOUBLE_BACKTICK.matcher(text).replaceAll(" ");
    Matcher frontmatter = NON_COMMANDS_FRONTMATTER.matcher(stripped);
    while (frontmatter.find()) {
      for (String name : frontmatter.group(1).split(",")) {
        String trimmed = name.trim().replace("`", "");
        if (!trimmed.isEmpty()) {
          stripped = stripped.replace("`" + trimmed + "`", " ");
        }
      }
    }
    return stripped;
  }

  /**
   * 每一份技能文档里当作命令写的名字都必须真实存在
   *
   * <p>站点操作手册会大量提到命令名,写错一个(例如把 {@code get_tabs} 写成 {@code list_tabs}),
   * 模型照着发请求就会拿到「不支持的方法」。这一条把 skills/ 下的所有文档都过一遍。
   */
  @Test
  public void everySkillDocOnlyMentionsRealCommands() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path path : allSkillDocs()) {
      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      Matcher matcher = DOC_COMMAND.matcher(stripNonCommands(text, path));
      while (matcher.find()) {
        String name = matcher.group(1);
        if (NOT_COMMANDS.contains(name.toLowerCase()) || !name.contains("_")) {
          continue;
        }
        if (CommandTable.get(name) == null) {
          offenders.add(path.getFileName() + "@" + path.getParent().getFileName() + " → " + name);
        }
      }
    }
    assertTrue("技能文档里写了命令表里不存在的命令:" + offenders
        + "（页面里的 snake_case 标识符请用**双反引号**包起来,见 skills/README.md）", offenders.isEmpty());
  }

  /**
   * 配方里的命令名也必须真实存在
   *
   * <p>{@code recipes/*.json} 里同样写着命令名,写错一个的话 {@code run_recipe} 要到运行时才报
   * 「不支持的方法」。这里在构建阶段就过一遍 —— 命令表是唯一事实来源,文档与配方都不该各说各话。
   */
  @Test
  public void everyRecipeOnlyMentionsRealCommands() throws IOException {
    List<Path> recipes = new ArrayList<>();
    for (Path root : SKILL_ROOTS) {
      Path dir = root.resolve("recipes");
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
        stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
            .filter(Files::isRegularFile).forEach(recipes::add);
      }
      if (!recipes.isEmpty()) {
        break;
      }
    }
    Assume.assumeTrue("没有找到 recipes/ 目录,跳过", !recipes.isEmpty());
    List<String> offenders = new ArrayList<>();
    for (Path path : recipes) {
      String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      com.alibaba.fastjson2.JSONObject parsed;
      try {
        parsed = com.alibaba.fastjson2.JSON.parseObject(text);
      } catch (RuntimeException e) {
        offenders.add(path.getFileName() + " → 不是合法 JSON:" + e.getMessage());
        continue;
      }
      if (parsed == null) {
        offenders.add(path.getFileName() + " → 不是合法 JSON 对象");
        continue;
      }
      com.alibaba.fastjson2.JSONArray commands = parsed.getJSONArray("commands");
      if (commands == null) {
        offenders.add(path.getFileName() + " → 缺少 commands 数组");
        continue;
      }
      for (int i = 0; i < commands.size(); i++) {
        com.alibaba.fastjson2.JSONObject entry = commands.getJSONObject(i);
        if (entry == null) {
          offenders.add(path.getFileName() + " 第 " + i + " 步不是对象");
          continue;
        }
        for (String name : entry.keySet()) {
          // 允许「一条命令 + 一个 expect 断言」的两键写法
          if ("expect".equals(name)) {
            continue;
          }
          if (CommandTable.get(name) == null) {
            offenders.add(path.getFileName() + " 第 " + i + " 步 → " + name);
          }
        }
      }
    }
    assertTrue("配方里写了命令表里不存在的命令:" + offenders, offenders.isEmpty());
  }

  /** 唯一的端点必须写清楚 */
  @Test
  public void endpointIsDocumented() {
    assertTrue("文档没有写 POST /playwright/command", doc.contains("/playwright/command"));
    assertTrue("文档没有写请求信封的 method 字段", doc.contains("\"method\""));
    assertTrue("文档没有写请求信封的 params 字段", doc.contains("\"params\""));
    assertTrue("文档没有写页签块格式", doc.contains("current tab is:"));
    assertTrue("文档没有写截图落盘目录", doc.contains("data/<id>/"));
  }

  @Test
  public void frontmatterIsValid() {
    assertTrue("技能文档缺少 frontmatter", doc.startsWith("---"));
    int end = doc.indexOf("\n---", 3);
    assertTrue("技能文档 frontmatter 没有闭合", end > 0);
    String frontmatter = doc.substring(3, end);

    String name = frontmatterValue(frontmatter, "name");
    String description = frontmatterValue(frontmatter, "description");
    assertTrue("frontmatter 缺少 name", name != null && !name.isEmpty());
    assertTrue("frontmatter 缺少 description", description != null && description.length() >= 10);
    assertTrue("name 必须是 kebab-case:" + name, name.matches("[a-z0-9]+(-[a-z0-9]+)*"));
    assertEquals("name 必须与技能名一致", SKILL_NAME, name);
  }

  /**
   * 「非命令标识符」的声明方式必须真的生效
   *
   * <p>这是一种**词法约定**,不是靠维护名单:双反引号里的东西一律不当命令检查。所以这里用一个反例固定
   * 住它 —— 把「一个并不存在的命令名」放进双反引号里,它就**不该**再被报错。约定一旦失效,下一个人写站点
   * skill 时又会莫名其妙地失败。
   */
  @Test
  public void doubleBacktickEscapesNonCommandIdentifiers() {
    String sample = "单反引号是命令:`no_such_command_here`;双反引号是页面里的名字:``no_such_command_here``";
    List<String> checked = new ArrayList<>();
    Matcher matcher = DOC_COMMAND.matcher(stripNonCommands(sample, null));
    while (matcher.find()) {
      checked.add(matcher.group(1));
    }
    assertTrue("单反引号里的名字应当被检查,实际:" + checked, checked.contains("no_such_command_here"));
    List<String> doubleBackticked = new ArrayList<>();
    Matcher onlyDouble = DOC_COMMAND.matcher(stripNonCommands("``no_such_command_here``", null));
    while (onlyDouble.find()) {
      doubleBackticked.add(onlyDouble.group(1));
    }
    assertEquals("双反引号里的名字不该被当成命令,实际:" + doubleBackticked, 0, doubleBackticked.size());
  }

  /** 站点 skill 写作约定必须写下来,否则「双反引号」这条规则没人知道 */
  @Test
  public void skillAuthoringConventionsAreDocumented() {
    Path readme = null;
    for (Path root : SKILL_ROOTS) {
      Path candidate = root.resolve("README.md");
      if (Files.isRegularFile(candidate)) {
        readme = candidate;
        break;
      }
    }
    Assume.assumeTrue("没有找到 skills/README.md,跳过", readme != null);
    String text;
    try {
      text = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("读不到 " + readme + ":" + e.getMessage(), e);
    }
    assertTrue("skills/README.md 没有写明「双反引号声明非命令」这条约定", text.contains("双反引号"));
    assertTrue("skills/README.md 没有给出非命令标识符的例子", text.contains("非命令"));
  }

  @Test
  public void docHasNoLegacyReferences() {
    assertFalse("文档里还有旧方法名 get_dom_text", doc.contains("get_dom_text"));
    assertFalse("文档里还有旧端点前缀 /api/v1/playwright", doc.contains("/api/v1/playwright"));
    assertFalse("文档里还有旧类名 PlaywrightController", doc.contains("PlaywrightController"));
    assertFalse("文档里还有旧包名 com.litongjava", doc.contains("com.litongjava"));
    assertFalse("文档里出现了 project-nexus 字样", doc.contains("project-nexus"));
    assertFalse("文档里不要写本机绝对路径", doc.contains("E:\\") || doc.contains("D:\\"));
  }

  private static String frontmatterValue(String frontmatter, String key) {
    Matcher matcher = Pattern.compile("(?m)^" + key + ":\\s*(.+)$").matcher(frontmatter);
    return matcher.find() ? matcher.group(1).trim() : null;
  }
}
