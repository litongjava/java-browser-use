package nexus.io.ai.browser.actions.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.service.ChromeBrowser;
import nexus.io.model.body.RespBodyVo;

/**
 * 站点配方库的单元测试
 *
 * <p>配方是「某个站点上必须这么点」的经验固化,重点验证三件事:
 * <ul>
 * <li>能按名字读到配方,并且**只能**读配方目录里的文件(挡掉 {@code ../} 这类越权路径);</li>
 * <li>{@code {{var}}} 注入走 JSON 序列化,中文、引号、换行都不用在客户端拼字符串;</li>
 * <li>配方名写错时给出可用清单,而不是一句「没有这个配方」。</li>
 * </ul>
 */
public class RecipeStoreTest {

  private static Path dir;

  @BeforeClass
  public static void setUp() throws IOException {
    dir = Files.createTempDirectory("browser-use-recipes");
    System.setProperty(RecipeStore.KEY_DIR, dir.toString());
    ChromeBrowser.resetForTests();
    write("close-modal.json", """
        {
          "name": "close-modal",
          "description": "关掉 ant-design 的确认框",
          "params": {"title": "弹窗标题,可选"},
          "commands": [
            {"get_modals": {}},
            {"close_modal": {"title": "{{title}}", "button": "{{button}}"}}
          ]
        }
        """);
    write("read-list.json", """
        {"description": "读列表前先等内容稳定", "commands": [{"wait_for_stable": {"quietMs": 800}}]}
        """);
    // 坏文件:不是合法 JSON,列表要跳过它而不是整条报错
    write("broken.json", "{ this is not json");
  }

  @AfterClass
  public static void tearDown() {
    System.clearProperty(RecipeStore.KEY_DIR);
    ChromeBrowser.resetForTests();
  }

  private static void write(String name, String content) throws IOException {
    Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
  }

  /** 列表:坏文件跳过,好文件都在 */
  @Test
  public void listSkipsBrokenFiles() {
    List<Kv> recipes = RecipeStore.list();
    assertTrue("应当列出两个可用配方,实际:" + recipes, recipes.size() == 2);
    StringBuilder names = new StringBuilder();
    for (Kv recipe : recipes) {
      names.append(recipe.getStr("name")).append(",");
    }
    assertTrue("要有 close-modal:" + names, names.toString().contains("close-modal"));
    assertTrue("要有 read-list:" + names, names.toString().contains("read-list"));
    assertFalse("坏文件不该出现在列表里:" + names, names.toString().contains("broken"));
  }

  /** 没有 name 字段时,文件名就是配方名 */
  @Test
  public void fileNameIsTheFallbackName() {
    Kv recipe = RecipeStore.load("read-list");
    assertNotNull(recipe);
    assertEquals("read-list", recipe.getStr("name"));
    assertEquals(1, ((JSONArray) recipe.get("commands")).size());
  }

  /** 越权路径与不存在的配方都读不到 */
  @Test
  public void pathTraversalIsRejected() throws IOException {
    Path secret = dir.getParent().resolve("browser-use-secret.json");
    Files.write(secret, "{\"commands\": []}".getBytes(StandardCharsets.UTF_8));
    try {
      assertNull("不允许用 ../ 读配方目录之外的文件", RecipeStore.load("../browser-use-secret"));
      assertNull("不允许用绝对路径读配方", RecipeStore.load(secret.toAbsolutePath().toString()));
      assertNull("不存在的配方返回 null", RecipeStore.load("no-such-recipe"));
    } finally {
      Files.deleteIfExists(secret);
    }
  }

  /** {{var}} 注入:走 JSON 序列化,中文/引号/换行都不用转义 */
  @Test
  public void varsAreInjectedAsJsonValues() {
    Kv recipe = RecipeStore.load("close-modal");
    JSONObject vars = new JSONObject();
    vars.put("title", "确认提交「直言课堂」?");
    vars.put("button", "取消");
    JSONArray commands = RecipeStore.applyVars((JSONArray) recipe.get("commands"), vars);
    JSONObject close = commands.getJSONObject(1).getJSONObject("close_modal");
    assertEquals("中文与引号应当原样注入", "确认提交「直言课堂」?", close.getString("title"));
    assertEquals("取消", close.getString("button"));
    assertEquals("没传变量的步骤保持原样", 0, commands.getJSONObject(0).getJSONObject("get_modals").size());
  }

  /** 变量值里的双引号不能把 JSON 拼坏 */
  @Test
  public void varsWithQuotesStayValidJson() {
    JSONArray commands = new JSONArray();
    JSONObject step = new JSONObject();
    step.put("input_text_by_selector", new JSONObject());
    step.getJSONObject("input_text_by_selector").put("text", "{{text}}");
    commands.add(step);
    JSONObject vars = new JSONObject();
    vars.put("text", "带\"引号\"和\n换行的值");
    JSONArray replaced = RecipeStore.applyVars(commands, vars);
    assertEquals("带\"引号\"和\n换行的值", replaced.getJSONObject(0).getJSONObject("input_text_by_selector")
        .getString("text"));
  }

  /** 没传变量时原样返回,不做任何替换 */
  @Test
  public void noVarsKeepsPlaceholders() {
    JSONArray commands = new JSONArray();
    JSONObject step = new JSONObject();
    step.put("wait", new JSONObject());
    step.getJSONObject("wait").put("seconds", "{{seconds}}");
    commands.add(step);
    assertNotNull(RecipeStore.applyVars(commands, null));
    assertEquals("{{seconds}}", RecipeStore.applyVars(commands, new JSONObject()).getJSONObject(0)
        .getJSONObject("wait").getString("seconds"));
  }

  /** 变量合并:配方自带的 defaults 打底,调用方给的覆盖 */
  @Test
  public void varsMergeOverDefaults() {
    JSONObject defaults = new JSONObject();
    defaults.put("table", ".vxe-table--body-wrapper");
    defaults.put("quietMs", 900);
    JSONObject vars = new JSONObject();
    vars.put("quietMs", 300);
    JSONObject merged = RecipeStore.mergeVars(defaults, vars);
    assertEquals("没被覆盖的用默认值", ".vxe-table--body-wrapper", merged.getString("table"));
    assertEquals("被覆盖的用调用方的值", 300, merged.getIntValue("quietMs"));
    assertEquals("两边都为空时返回空对象", 0, RecipeStore.mergeVars(null, null).size());
  }

  /** 配方名写错时,回执里要带可用清单 */
  @Test
  public void unknownRecipeNameListsAvailableOnes() {
    JSONObject params = new JSONObject();
    params.put("name", "no-such-recipe");
    RespBodyVo response = CommandTable.get("run_recipe").run(null, 1L, params);
    assertFalse("没有这个配方时应当失败", response.isOk());
    assertTrue("要给出可用配方清单,实际:" + response.getMsg(),
        response.getMsg().contains("close-modal") && response.getMsg().contains("read-list"));
  }

  /** 目录不存在时列表为空,不抛异常 */
  @Test
  public void missingDirectoryIsNotAnError() {
    System.setProperty(RecipeStore.KEY_DIR, dir.resolve("not-created-yet").toString());
    ChromeBrowser.resetForTests();
    try {
      assertTrue("目录不存在时应当返回空清单", RecipeStore.list().isEmpty());
      assertNull(RecipeStore.load("close-modal"));
    } finally {
      System.setProperty(RecipeStore.KEY_DIR, dir.toString());
      ChromeBrowser.resetForTests();
    }
  }

  /**
   * 仓库里自带的配方文件必须是合法 JSON 且真的有命令
   *
   * <p>写坏一个配方文件的后果很隐蔽:{@link RecipeStore#list()} 会**跳过**读不出来的文件,配方于是
   * 静默消失,调用方只会看到「没有这个配方」。所以这里把随仓库发布的配方整体过一遍。
   *
   * <p>配方目录默认是「服务启动目录」下的 {@code recipes/},而服务通常从仓库根启动(测试的工作目录是
   * {@code playwright-server}),所以这里显式检查仓库根的 {@code recipes/}。
   */
  @Test
  public void shippedRecipesAreValid() {
    System.clearProperty(RecipeStore.KEY_DIR);
    ChromeBrowser.resetForTests();
    java.nio.file.Path shipped = Paths.get("..", "recipes").toAbsolutePath().normalize();
    org.junit.Assume.assumeTrue("仓库里没有 recipes 目录,跳过", Files.isDirectory(shipped));
    System.setProperty(RecipeStore.KEY_DIR, shipped.toString());
    ChromeBrowser.resetForTests();
    try {
      List<Kv> recipes = RecipeStore.list();
      assertTrue("自带的配方应当能全部读出来(坏文件会被静默跳过),实际读到 " + recipes.size() + " 个", recipes.size() >= 3);
      for (Kv recipe : recipes) {
        String name = recipe.getStr("name");
        JSONArray commands = (JSONArray) recipe.get("commands");
        assertNotNull("配方 " + name + " 缺少 commands", commands);
        assertTrue("配方 " + name + " 的 commands 是空的", commands.size() > 0);
        // 每一步的命令名都必须是真实存在的命令,否则跑起来才发现
        for (int i = 0; i < commands.size(); i++) {
          JSONObject step = commands.getJSONObject(i);
          for (String key : step.keySet()) {
            if ("expect".equals(key)) {
              continue;
            }
            assertNotNull("配方 " + name + " 第 " + i + " 步用了不存在的命令:" + key, CommandTable.get(key));
          }
        }
      }
    } finally {
      System.setProperty(RecipeStore.KEY_DIR, dir.toString());
      ChromeBrowser.resetForTests();
    }
  }
}
