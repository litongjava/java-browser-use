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

  /** 文档里出现这些词会被当成命令名来比对,但它们不是命令 */
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
      "screenshot_error", "outerhtml");

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
    Matcher matcher = DOC_COMMAND.matcher(doc);
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
      Matcher matcher = DOC_COMMAND.matcher(text);
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
    assertTrue("技能文档里写了命令表里不存在的命令:" + offenders, offenders.isEmpty());
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
