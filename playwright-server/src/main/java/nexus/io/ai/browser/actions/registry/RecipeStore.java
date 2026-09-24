package nexus.io.ai.browser.actions.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ChromeBrowser;

/**
 * 站点配方库:把「某个站点上必须这么点」的经验固化成可复用的命令序列
 *
 * <p>
 * <b>为什么要有它</b>:同一个站点上总会攒下一批绕不开的固定动作 —— 比如「ant-design 的确认框必须用
 * 真实鼠标点」「下拉要先打字过滤再选」「查询结果要等内容稳定再读」。这些经验如果只写在提示词/技能文档里,
 * 每写一次载荷就要重抄一遍,而且容易漏。配方把它们落成服务端的 JSON 文件,调用方用
 * {@code run_recipe} 一条命令跑完,参数用 {@code {{var}}} 注入。
 *
 * <p>
 * <b>与「站点级引擎记忆」的区别</b>:配方**不自动生效**、不做任何隐式推断 —— 必须由调用方显式点名
 * {@code run_recipe} 才会执行,与「让智能体自己决定用哪个引擎」的原则不冲突。引擎选择仍然完全由
 * 调用方在 {@code start} 时决定。
 *
 * <p>
 * 文件放在「配方目录」(默认 {@code <工作目录>/recipes},可用配置项 {@code browser.recipes.dir} 改),
 * 每个配方一个 {@code .json} 文件,文件名(不含扩展名)就是配方名:
 *
 * <pre>
 * {
 *   "name": "close-antd-modal",
 *   "description": "关掉 ant-design 的确认框:JS 派发无效,必须真实鼠标点",
 *   "params": {"title": "要关的弹窗标题,可选"},
 *   "commands": [
 *     {"get_modals": {}},
 *     {"close_modal": {"title": "{{title}}"}}
 *   ]
 * }
 * </pre>
 *
 * <p>
 * {@code commands} 与 {@code commands} 批量接口里的格式完全一致(每项一个键),所以配方里的每一步
 * 都能单独拿出来手跑,便于排障。
 */
@Slf4j
public final class RecipeStore {

  /** 配方目录(默认 {@code <工作目录>/recipes}) */
  public static final String KEY_DIR = "browser.recipes.dir";

  private RecipeStore() {
  }

  /** 配方目录的绝对路径 */
  public static Path dir() {
    String configured = ChromeBrowser.config(KEY_DIR);
    Path dir = configured == null || configured.isBlank() ? Paths.get("recipes") : Paths.get(configured.trim());
    return dir.toAbsolutePath().normalize();
  }

  /** 列出所有配方(读不出 commands 的坏文件会被跳过,只记日志,不影响列表) */
  public static List<Kv> list() {
    List<Kv> recipes = new ArrayList<>();
    Path base = dir();
    if (!Files.isDirectory(base)) {
      return recipes;
    }
    try (Stream<Path> files = Files.list(base)) {
      files.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(path -> {
            JSONObject parsed = read(path);
            if (parsed == null) {
              return;
            }
            recipes.add(summary(nameOf(path), parsed));
          });
    } catch (IOException e) {
      log.warn("读取配方目录失败 {}:{}", base, e.getMessage());
    }
    return recipes;
  }

  /**
   * 读一个配方
   *
   * @param name 配方名(文件名,不含 .json)
   * @return {@code {name, description, params, commands}};不存在或格式不对时返回 null
   */
  public static Kv load(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    // 只允许按名字取配方目录下的文件:挡掉 ../../ 这类越权读取
    String fileName = name.trim();
    if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
      return null;
    }
    Path file = dir().resolve(fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
        ? fileName : fileName + ".json");
    if (!file.normalize().startsWith(dir()) || !Files.isRegularFile(file)) {
      return null;
    }
    JSONObject parsed = read(file);
    if (parsed == null) {
      return null;
    }
    Kv recipe = summary(nameOf(file), parsed);
    if (recipe.get("commands") == null) {
      return null;
    }
    return recipe;
  }

  private static String nameOf(Path path) {
    String fileName = path.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static JSONObject read(Path path) {
    try {
      JSONObject parsed = JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
      if (parsed == null) {
        log.warn("配方文件不是合法 JSON 对象:{}", path);
      }
      return parsed;
    } catch (IOException | RuntimeException e) {
      log.warn("读取配方失败 {}:{}", path, e.getMessage());
      return null;
    }
  }

  private static Kv summary(String name, JSONObject parsed) {
    Kv kv = Kv.by("name", parsed.getString("name") == null ? name : parsed.getString("name"));
    kv.set("file", name + ".json");
    if (parsed.getString("description") != null) {
      kv.set("description", parsed.getString("description"));
    }
    if (parsed.getJSONObject("params") != null) {
      kv.set("params", parsed.getJSONObject("params"));
    }
    // defaults:调用方没给这个变量时用这里的值,配方因此可以「不传任何参数直接跑」
    if (parsed.getJSONObject("defaults") != null) {
      kv.set("defaults", parsed.getJSONObject("defaults"));
    }
    JSONArray commands = parsed.getJSONArray("commands");
    if (commands != null) {
      kv.set("commands", commands).set("stepCount", commands.size());
    }
    return kv;
  }

  /**
   * 合并变量:配方自带的 {@code defaults} 打底,调用方给的 {@code vars} 覆盖
   *
   * <p>有了它,配方里就能写「默认按这个选择器找表格」这类合理缺省,调用方不传参也能直接跑。
   */
  public static JSONObject mergeVars(JSONObject defaults, JSONObject vars) {
    JSONObject merged = new JSONObject();
    if (defaults != null) {
      merged.putAll(defaults);
    }
    if (vars != null) {
      merged.putAll(vars);
    }
    return merged;
  }

  /**
   * 把配方里的 {@code {{var}}} 替换成调用方给的值
   *
   * <p>
   * 在**序列化后的 JSON 文本**上做替换,所以字符串会自动带引号并转义:中文、引号、换行都不用在客户端
   * 拼字符串。这也是配方能在客户端-服务器模式下好用的前提。
   *
   * <p>
   * 两种写法都支持,顺序很关键:
   * <ol>
   * <li>{@code "{{key}}"} —— 连引号一起替换。写配方时最自然的写法({@code "title": "{{title}}"})就是这种,
   * 替换进去的是 JSON 字面量(字符串带引号、数字不带),所以不会出现 {@code ""值""} 这种坏 JSON;</li>
   * <li>{@code {{key}}} —— 裸占位符,直接替换成 JSON 字面量,适合 {@code "seconds": {{seconds}}} 这种位置。</li>
   * </ol>
   *
   * @param commands 配方里的命令数组(会被复制,不改原对象)
   * @param vars     变量;null 或空表示不替换
   * @return 替换后的命令数组
   */
  public static JSONArray applyVars(JSONArray commands, JSONObject vars) {
    if (commands == null) {
      return null;
    }
    if (vars == null || vars.isEmpty()) {
      return commands;
    }
    String text = commands.toJSONString();
    for (String key : vars.keySet()) {
      Object value = vars.get(key);
      String literal = value == null ? "null" : JSON.toJSONString(value);
      // 先处理「带引号的占位符」,再处理裸占位符:反过来的话裸替换会把引号留在原地
      text = text.replace("\"{{" + key + "}}\"", literal).replace("{{" + key + "}}", literal);
    }
    JSONArray replaced = JSON.parseArray(text);
    return replaced == null ? commands : replaced;
  }
}
