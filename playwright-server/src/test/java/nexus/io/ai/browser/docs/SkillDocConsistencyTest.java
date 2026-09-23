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
 * <p>技能文档是仓库根的 {@code SKILL.md}(装进 DSH 时位于
 * {@code .dsh/skills/deepseek-browser-use/SKILL.md},两个位置都认),而 Maven 测试的工作目录是
 * playwright-server,所以用 .. 回到仓库根。文档不存在时跳过整个类,便于单独拷贝模块构建。
 *
 * <p>覆盖三件事:命令表里的每个方法都写进了文档、文档里没有旧接口名与旧包名、frontmatter 合法。
 */
public class SkillDocConsistencyTest {

  /** 技能名:frontmatter 的 name,也是装进 DSH 时用的目录名 */
  private static final String SKILL_NAME = "deepseek-browser-use";

  /** 依次尝试的位置:仓库根的 SKILL.md、装成技能时的 .dsh 目录 */
  private static final List<Path> SKILL_PATHS = List.of(Paths.get("..", "SKILL.md"),
      Paths.get("..", ".dsh", "skills", SKILL_NAME, "SKILL.md"));

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
