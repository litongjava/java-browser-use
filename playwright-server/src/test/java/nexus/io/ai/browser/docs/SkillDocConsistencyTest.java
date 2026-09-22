package nexus.io.ai.browser.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import nexus.io.ai.browser.controller.PlaywrightController;
import nexus.io.annotation.RequestPath;

/**
 * 校验技能文档与控制器接口保持一致,避免文档漂移
 *
 * <p>技能文档在仓库根的 .dsh/skills/java-browser-use/SKILL.md,而 Maven 测试的工作目录是
 * playwright-server,所以用 .. 回到仓库根。文档不存在时跳过整个类,便于单独拷贝模块构建。
 *
 * <p>覆盖四件事:接口清单双向一致、参数名都在文档里出现、frontmatter 合法、没有旧包名与旧接口名。
 */
public class SkillDocConsistencyTest {

  private static final Path SKILL_PATH = Paths.get("..", ".dsh", "skills", "java-browser-use", "SKILL.md");

  private static final Path CONTROLLER_PATH = Paths
      .get("src", "main", "java", "nexus", "io", "ai", "browser", "controller", "PlaywrightController.java");

  /** 文档里形如 `/endpoint` 的接口名 */
  private static final Pattern DOC_ENDPOINT = Pattern.compile("`/([a-z_]+)`");

  /** 控制器里的方法签名,用来取参数名 */
  private static final Pattern METHOD_SIGNATURE = Pattern
      .compile("@RequestPath\\(\"/([a-z_]+)\"\\)\\s+public\\s+[\\w<>\\[\\].]+\\s+\\w+\\(([^)]*)\\)");

  /** 参数类型里出现这些字样就不检查参数名(框架注入的对象,不对外暴露) */
  private static final String[] IGNORED_PARAM_TYPES = { "HttpRequest", "HttpResponse" };

  private static String doc;

  @BeforeClass
  public static void loadDoc() throws IOException {
    Assume.assumeTrue("技能文档不存在:" + SKILL_PATH.toAbsolutePath().normalize(), Files.exists(SKILL_PATH));
    doc = new String(Files.readAllBytes(SKILL_PATH), StandardCharsets.UTF_8);
  }

  /** 控制器上真实存在的接口(含类级前缀) */
  private static Set<String> controllerEndpoints() {
    RequestPath prefix = PlaywrightController.class.getAnnotation(RequestPath.class);
    String base = prefix == null ? "" : prefix.value();
    Set<String> endpoints = new TreeSet<>();
    for (Method method : PlaywrightController.class.getDeclaredMethods()) {
      RequestPath path = method.getAnnotation(RequestPath.class);
      if (path != null) {
        endpoints.add(base + path.value());
      }
    }
    return endpoints;
  }

  /** 文档里写到的接口(只取末段,便于和控制器比较) */
  private static Set<String> documentedEndpoints() {
    Set<String> endpoints = new TreeSet<>();
    Matcher matcher = DOC_ENDPOINT.matcher(doc);
    while (matcher.find()) {
      endpoints.add(matcher.group(1));
    }
    return endpoints;
  }

  private static String shortName(String endpoint) {
    int slash = endpoint.lastIndexOf('/');
    return slash < 0 ? endpoint : endpoint.substring(slash + 1);
  }

  @Test
  public void documentedEndpointsMatchController() {
    Set<String> inCode = new TreeSet<>();
    for (String endpoint : controllerEndpoints()) {
      inCode.add(shortName(endpoint));
    }
    Set<String> inDoc = documentedEndpoints();

    List<String> missingInDoc = new ArrayList<>();
    for (String endpoint : inCode) {
      if (!inDoc.contains(endpoint)) {
        missingInDoc.add(endpoint);
      }
    }
    List<String> missingInCode = new ArrayList<>();
    for (String endpoint : inDoc) {
      if (!inCode.contains(endpoint)) {
        missingInCode.add(endpoint);
      }
    }

    assertEquals("控制器里有接口没写进技能文档:" + missingInDoc, 0, missingInDoc.size());
    assertEquals("技能文档写了控制器里不存在的接口:" + missingInCode, 0, missingInCode.size());
    assertEquals("接口数量不一致", inCode.size(), inDoc.size());
  }

  /** 控制器每个参数名都应该在文档里出现过,否则调用方只能猜参数名 */
  @Test
  public void everyControllerParameterIsDocumented() throws IOException {
    Assume.assumeTrue("控制器源码不存在:" + CONTROLLER_PATH.toAbsolutePath().normalize(),
        Files.exists(CONTROLLER_PATH));
    String source = new String(Files.readAllBytes(CONTROLLER_PATH), StandardCharsets.UTF_8);
    List<String> undocumented = new ArrayList<>();
    Matcher matcher = METHOD_SIGNATURE.matcher(source);
    while (matcher.find()) {
      String endpoint = matcher.group(1);
      String params = matcher.group(2).trim();
      if (params.isEmpty()) {
        continue;
      }
      for (String param : params.split(",")) {
        String text = param.trim();
        boolean ignored = false;
        for (String type : IGNORED_PARAM_TYPES) {
          if (text.contains(type)) {
            ignored = true;
          }
        }
        if (ignored) {
          continue;
        }
        int space = text.lastIndexOf(' ');
        String name = space < 0 ? text : text.substring(space + 1);
        if (!doc.contains(name)) {
          undocumented.add(endpoint + "." + name);
        }
      }
    }
    assertTrue("这些参数名没有出现在技能文档里:" + undocumented, undocumented.isEmpty());
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
    assertEquals("name 必须与所在目录同名", SKILL_PATH.getParent().getFileName().toString(), name);
  }

  @Test
  public void docHasNoLegacyReferences() {
    assertFalse("文档里还有旧接口名 get_page_state", doc.contains("get_page_state") || doc.contains("getPageState"));
    assertFalse("文档里还有旧包名 com.litongjava", doc.contains("com.litongjava"));
    assertFalse("文档里出现了 project-nexus 字样", doc.contains("project-nexus"));
    assertFalse("文档里不要写本机绝对路径", doc.contains("E:\\") || doc.contains("D:\\"));
  }

  private static String frontmatterValue(String frontmatter, String key) {
    Matcher matcher = Pattern.compile("(?m)^" + key + ":\\s*(.+)$").matcher(frontmatter);
    return matcher.find() ? matcher.group(1).trim() : null;
  }
}
