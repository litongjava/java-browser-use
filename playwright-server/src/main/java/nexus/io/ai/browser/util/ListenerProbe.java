package nexus.io.ai.browser.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;

import lombok.extern.slf4j.Slf4j;

/**
 * 「这个元素到底挂了哪些事件监听器」的权威探测
 *
 * <p>
 * <b>为什么需要它</b>:「设了值 / 上传了文件 / 派发了事件,但页面没反应」是 SPA 自动化里最贵的坑之一 ——
 * 实测企业微信后台的营业执照上传,{@code upload_file} 回 {@code ok:true},页面却一直提示「请上传工商营业
 * 执照」,根因是那个 {@code <input class="uploadInput">} **没有挂任何事件监听器**。以前只能靠手写
 * {@code execute_js} 去摸 {@code el._vei} 之类的框架痕迹。
 *
 * <p>
 * <b>为什么不能只靠「框架痕迹」</b>:{@code el._vei}(Vue)、{@code __reactProps$}(React)、内联
 * {@code onchange} 都只是**正面证据** —— 探得到就说明有人监听,探不到**不等于**没人监听:原生
 * {@code addEventListener('change', ...)} 在元素上不留任何可枚举痕迹。把「没探到」说成「没有」是
 * **假阴性**,而假阴性正是这个坑最危险的地方(它会让人去换选择器、换文件、怀疑接口)。
 *
 * <p>
 * 所以这里分两条路:
 * <ol>
 * <li><b>权威路(Chromium 系,推荐)</b>:走 CDP 的 {@code DOMDebugger.getEventListeners} —— 这是浏览器
 * 自己报的事件监听器清单,原生 {@code addEventListener} 也算,结论可信({@code detection: "cdp"});</li>
 * <li><b>启发路(Firefox / CDP 不可用时)</b>:只看框架痕迹。探到痕迹 → {@code hasListeners: true};
 * 什么都没探到 → {@code hasListeners: null}(**未知**,而不是 false)。</li>
 * </ol>
 */
@Slf4j
public final class ListenerProbe {

  /**
   * 框架痕迹探测脚本(传一个元素,返回一个对象)
   *
   * <p>判定依据都是「框架自己在元素上留下的东西」,不是猜:{@code el._vei} 是 Vue 2/3 的事件 invoker 表,
   * 键就是事件名;React 把 props 挂在 {@code __reactProps$xxx} 上;jQuery 的监听器在
   * {@code jQuery._data(el, 'events')} 里。
   */
  public static final String FRAMEWORK_TRACES = """
      (el) => {
        if (!el) return null;
        const clean = (name) => String(name)
          .replace(/^on(?=[A-Z])/, '').replace(/^on/, '')
          .replace(/[!~&]+$/, '').toLowerCase();
        const events = new Set();
        let vue = null;
        let react = null;
        let inline = null;
        let jquery = null;
        try {
          const veiKeys = Object.keys(el).filter(k => k === '_vei' || k.startsWith('_vei'));
          for (const key of veiKeys) {
            const table = el[key];
            if (table && typeof table === 'object') {
              vue = true;
              for (const name of Object.keys(table)) events.add(clean(name));
            } else {
              vue = vue === true;
            }
          }
          if (el.__vue__ || el.__vueParentComponent || el._vnode || el.__vnode) vue = true;
          if (vue === null && veiKeys.length > 0) vue = false;
        } catch (e) { /* 某些元素上访问这些私有属性会抛,忽略 */ }
        try {
          const reactKeys = Object.keys(el).filter(k => k.startsWith('__reactProps$') || k.startsWith('__reactFiber$')
            || k.startsWith('__reactEvents$'));
          if (reactKeys.length > 0) {
            react = true;
            for (const key of reactKeys) {
              if (!key.startsWith('__reactProps$')) continue;
              const props = el[key];
              if (props && typeof props === 'object') {
                for (const name of Object.keys(props)) {
                  if (/^on[A-Z]/.test(name)) events.add(clean(name));
                }
              }
            }
          }
        } catch (e) { /* 同上 */ }
        try {
          const dom0 = Object.keys(el).filter(k => /^on[a-z]+$/.test(k) && typeof el[k] === 'function');
          const attrs = (el.getAttributeNames ? el.getAttributeNames() : []).filter(n => /^on/i.test(n));
          if (dom0.length > 0 || attrs.length > 0) {
            inline = true;
            for (const name of dom0) events.add(clean(name));
            for (const name of attrs) events.add(clean(name));
          } else if (el.tagName) {
            inline = false;
          }
        } catch (e) { /* 同上 */ }
        try {
          const jq = window.jQuery || window.$;
          if (jq && typeof jq._data === 'function') {
            const table = jq._data(el, 'events');
            if (table) {
              jquery = true;
              for (const name of Object.keys(table)) events.add(clean(name));
            } else {
              jquery = false;
            }
          }
        } catch (e) { /* 同上 */ }
        return {
          tag: el.tagName || null,
          id: el.id || null,
          className: typeof el.className === 'string' ? el.className : (el.className && el.className.baseVal) || null,
          type: el.getAttribute ? el.getAttribute('type') : null,
          vue2: vue,
          vue3: vue,
          react: react,
          inline: inline,
          jquery: jquery,
          traces: Array.from(events).filter(Boolean).sort()
        };
      }
      """;

  private ListenerProbe() {
  }

  /**
   * 探一个元素的事件监听器
   *
   * @param locator       已经定位好的元素(第一个匹配)
   * @param frame         {@code locator} 所在的 frame;null 表示主 frame(CDP 路要用它开会话)
   * @param elementScript 解析该元素的 JS 表达式,给 CDP 的 {@code Runtime.evaluate} 用(如
   *                      {@code document.querySelector("#x")});为 null 时跳过 CDP 路
   * @return {@code {found, tag, className, type, vue2, vue3, react, inline, jquery, listeners,
   *         hasListeners, detection}};定位不到时 {@code found:false}
   */
  public static Kv probe(Locator locator, Frame frame, String elementScript) {
    Kv result = new Kv();
    Kv traces;
    try {
      Object raw = evaluateTraces(locator, frame, elementScript);
      if (!(raw instanceof Map)) {
        result.set("found", false);
        return result;
      }
      traces = new Kv();
      traces.putAll((Map<?, ?>) raw);
    } catch (PlaywrightException e) {
      result.set("found", false).set("error", e.getMessage());
      return result;
    }
    result.set("found", true).set(traces);

    // 权威路:CDP 报的监听器清单。原生 addEventListener 只有它看得到
    List<String> cdpTypes = elementScript == null ? null : cdpListeners(frame, elementScript);
    List<String> merged = new ArrayList<>();
    for (String name : stringList(traces.get("traces"))) {
      if (!merged.contains(name)) {
        merged.add(name);
      }
    }
    if (cdpTypes != null) {
      // 按字母序输出事件名,顺序稳定,便于肉眼比对
      java.util.Collections.sort(cdpTypes);
      for (String name : cdpTypes) {
        if (!merged.contains(name)) {
          merged.add(name);
        }
      }
      java.util.Collections.sort(merged);
      boolean has = !cdpTypes.isEmpty();
      // 有框架痕迹但 CDP 说是空的:以 CDP 为准,但把这件事写出来(可能是内联 HTML 属性那种不走 CDP 的)
      boolean tracesPresent = !stringList(traces.get("traces")).isEmpty();
      result.set("listeners", merged).set("hasListeners", has).set("detection", "cdp");
      if (tracesPresent && !has) {
        result.set("note", "CDP 报的监听器清单是空的,但元素上有框架痕迹(可能只是数据绑定,不是事件监听)");
      }
      return result;
    }
    // 启发路:只有正面证据才敢下结论,探不到就是「未知」
    result.set("listeners", merged).set("detection", "heuristic");
    result.set("hasListeners", merged.isEmpty() ? null : Boolean.TRUE);
    return result;
  }

  /**
   * 取元素的框架痕迹
   *
   * <p>
   * <b>优先「现场重新解析元素」,不要用 Locator。</b>实测 {@code setInputFiles} 之后框架会把原来的
   * input 换成新的(企业微信的上传组件就是这样),此时 Locator 已指向脱离文档的节点,
   * {@code Locator.evaluate} 会一直等到**默认 30 秒超时**才抛,于是「上传成功」被写成
   * {@code readbackError: Timeout 30000ms exceeded.} + {@code consumed: unknown}。
   * 用 {@code frame.evaluate} 现场重新查询既读得到新节点,也**不会等**。
   *
   * @param elementScript 解析元素的 JS 表达式;为 null 时才退回 Locator(可能等到超时)
   */
  private static Object evaluateTraces(Locator locator, Frame frame, String elementScript) {
    if (frame != null && elementScript != null && !elementScript.isBlank()) {
      try {
        return frame.evaluate(
            "(() => { const el = (" + elementScript + "); return (" + FRAMEWORK_TRACES + ")(el); })()");
      } catch (PlaywrightException e) {
        // 现场解析失败(元素真的没了 / frame 没了)再退回 Locator 试一次
      }
    }
    if (locator == null || locator.count() == 0) {
      return null;
    }
    Object raw = locator.first().evaluate(FRAMEWORK_TRACES);
    if (!(raw instanceof Map)) {
      throw new PlaywrightException("页面没有返回探测结果");
    }
    return raw;
  }

  /**
   * 用 CDP 取一个元素的事件监听器类型
   *
   * @return 事件名列表;CDP 不可用(非 Chromium 系、会话开不起来、脚本解析不到元素)时返回 null,
   *         调用方据此退回启发路 —— **不能把「问不到」当成「没有」**
   */
  private static List<String> cdpListeners(Frame frame, String elementScript) {
    if (frame == null) {
      return null;
    }
    CDPSession session = null;
    String objectId = null;
    try {
      session = frame.page().context().newCDPSession(frame);
      JsonObject evaluate = new JsonObject();
      evaluate.addProperty("expression", elementScript);
      evaluate.addProperty("objectGroup", "dsh-listener-probe");
      evaluate.addProperty("includeCommandLineAPI", false);
      JsonObject evaluated = session.send("Runtime.evaluate", evaluate);
      JsonElement remote = evaluated.get("result");
      if (remote == null || !remote.isJsonObject()) {
        return null;
      }
      JsonObject remoteObject = remote.getAsJsonObject();
      if (remoteObject.has("subtype") && "null".equals(remoteObject.get("subtype").getAsString())) {
        return java.util.Collections.emptyList();
      }
      if (!remoteObject.has("objectId")) {
        return null;
      }
      objectId = remoteObject.get("objectId").getAsString();
      JsonObject params = new JsonObject();
      params.addProperty("objectId", objectId);
      params.addProperty("depth", 1);
      params.addProperty("pierce", true);
      JsonObject listeners = session.send("DOMDebugger.getEventListeners", params);
      List<String> types = new ArrayList<>();
      JsonElement raw = listeners.get("listeners");
      if (raw != null && raw.isJsonArray()) {
        JsonArray array = raw.getAsJsonArray();
        for (JsonElement item : array) {
          if (item.isJsonObject() && item.getAsJsonObject().has("type")) {
            types.add(item.getAsJsonObject().get("type").getAsString());
          }
        }
      }
      return types;
    } catch (PlaywrightException e) {
      log.debug("CDP 读事件监听器失败,退回启发式:{}", e.getMessage());
      return null;
    } catch (RuntimeException e) {
      log.debug("CDP 读事件监听器出错,退回启发式:{}", e.getMessage());
      return null;
    } finally {
      if (session != null) {
        try {
          if (objectId != null) {
            JsonObject release = new JsonObject();
            release.addProperty("objectId", objectId);
            session.send("Runtime.releaseObject", release);
          }
          session.detach();
        } catch (RuntimeException ignored) {
          // 会话收尾失败无所谓:它只影响这一次诊断
        }
      }
    }
  }

  /** 把 Kv 里的字符串数组读出来 */
  private static List<String> stringList(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof List) {
      for (Object item : (List<?>) value) {
        if (item != null) {
          out.add(String.valueOf(item));
        }
      }
    }
    return out;
  }

  /**
   * 按探测结果给一句可操作的话
   *
   * <p>刻意**不只说「可能有问题」**:这类坑的排查成本全在「知道该往哪查」上,所以直接给下一步。
   *
   * @return 无需提醒时返回 null
   */
  public static String note(Kv probe) {
    if (probe == null) {
      return null;
    }
    Object has = probe.get("hasListeners");
    if (Boolean.TRUE.equals(has)) {
      return null;
    }
    if (has == null) {
      return "没能确认这个元素有没有事件监听器(本机不走 CDP,框架痕迹也没探到):"
          + "原生 addEventListener 不在元素上留痕迹,所以「没探到」不等于「没有」。"
          + "派发事件前建议先用 execute_js 在页面上打点确认,或直接改用组件方法直调";
    }
    return "这个元素**没有任何事件监听器**(浏览器自己报的监听器清单是空的):JS 派发的 click/change 到不了"
        + "任何 handler,设上去的值很可能不会被页面处理。改用组件方法直调(见技能手册「Vue 组件直调」一节),"
        + "或先点击它的可见父元素 / 触发框架自己的入口";
  }

  /** 供批量探测(元素清单)复用的容器:把启发路的结果整理成和 {@link #probe} 一致的字段 */
  public static Kv fromTraces(Map<?, ?> raw) {
    Kv kv = new Kv();
    if (raw == null) {
      return kv;
    }
    kv.putAll(raw);
    List<String> traces = stringList(kv.get("traces"));
    kv.set("listeners", traces);
    // 批量路径上不能对每个元素都开一次 CDP 会话,所以只能说「有痕迹」或「未知」——
    // 这里绝不把「没探到」写成 false(那是假阴性,正是这个坑最危险的地方)
    kv.set("hasListeners", traces.isEmpty() ? null : Boolean.TRUE);
    kv.set("detection", "heuristic");
    return kv;
  }

  /** 给 {@code listeners} 字段用的空表(避免各调用点自己 new) */
  public static Map<String, Object> emptyListenerMap() {
    return new LinkedHashMap<>();
  }
}
