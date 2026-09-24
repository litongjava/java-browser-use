package nexus.io.ai.browser.dom.service;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import nexus.io.ai.browser.dom.model.DOMBaseNode;
import nexus.io.ai.browser.dom.model.DOMElementNode;
import nexus.io.ai.browser.dom.model.DOMState;
import nexus.io.ai.browser.dom.model.DOMTextNode;
import nexus.io.ai.browser.dom.model.FrameSnapshot;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;

import lombok.extern.slf4j.Slf4j;
import nexus.io.tio.utils.hutool.FileUtil;
import nexus.io.tio.utils.hutool.ResourceUtil;

@Slf4j
public class DomService {

  /**
   * 拼好的脚本缓存
   *
   * <p>
   * 脚本有 50KB 上下,每帧都要读一次文件再 {@code String.format} 一遍:单 frame 时看不出来,带 frame 的
   * 快照要对每个 frame 各来一次,白白多花几十毫秒。脚本在进程生命周期里不会变,缓存住即可。
   */
  private static volatile String cachedExpression;

  public static String buildExpression() {
    String cached = cachedExpression;
    if (cached != null) {
      return cached;
    }
    // 从 resources 加载 index.js
    String js_path = "dom/dom_tree/index.js";
    URL url = ResourceUtil.getResource(js_path);
    if (url == null) {
      throw new RuntimeException("not found index.js");
    }
    String stringBuilder = FileUtil.readString(url);
    String buildScript = stringBuilder.toString();

    String run_js = "dom/dom_tree/run.js";
    URL run_js_url = ResourceUtil.getResource(run_js);
    if (run_js_url == null) {
      throw new RuntimeException("not found run_js");
    }

    stringBuilder = FileUtil.readString(run_js_url);
    String expression = String.format(stringBuilder.toString(), buildScript);
    cachedExpression = expression;
    return expression;
  }

  public static Map<String, Object> evaluate(Page page, boolean highlightElements, int focusElement, int viewportExpansion) {
    return evaluate(page.mainFrame(), highlightElements, focusElement, viewportExpansion, 0);
  }

  /**
   * 在指定的 frame 里求值
   *
   * <p>
   * <b>为什么按 {@link Frame} 而不是 {@link Page} 参数化</b>:跨域 iframe 里 {@code page.evaluate} 是够不着的
   * (它在顶层文档执行,{@code document} 就是顶层 document)。而 Playwright 的 {@code frame.evaluate} 走的是
   * 浏览器协议,注入到该 frame 自己的文档里,{@code document} / {@code window} 都是该 frame 的 —— 所以
   * 「在 frame 里跑同一段脚本」不需要改脚本,只需要换求值目标。顶层文档就是 {@code page.mainFrame()}。
   *
   * @param highlightIndexStart 本 frame 的元素索引从哪里开始编号。多 frame 快照时,每个 frame 单独求值会各自
   *                            从 0 开始编号,索引就会撞车;调用方按 frame 顺序依次求值、把前几个 frame 用掉的
   *                            数量当起点传进来,索引才是全局唯一的(见 {@code PlaywrightService.buildFrameState})
   */
  public static Map<String, Object> evaluate(Frame frame, boolean highlightElements, int focusElement,
      int viewportExpansion, int highlightIndexStart) {
    return evaluate(frame, buildExpression(), highlightElements, focusElement, viewportExpansion,
        highlightIndexStart);
  }

  public static Map<String, Object> evaluate(Page page, String expression, boolean highlightElements, int focusElement, int viewportExpansion) {
    return evaluate(page.mainFrame(), expression, highlightElements, focusElement, viewportExpansion, 0);
  }

  public static Map<String, Object> evaluate(Frame frame, String expression, boolean highlightElements, int focusElement,
      int viewportExpansion, int highlightIndexStart) {
    return evaluate(frame, expression, highlightElements, focusElement, viewportExpansion, highlightIndexStart, true);
  }

  /** @param descendIframes 见 {@link #getClickableElements(Frame, String, boolean, int, int, int, boolean)} */
  public static Map<String, Object> evaluate(Frame frame, String expression, boolean highlightElements, int focusElement,
      int viewportExpansion, int highlightIndexStart, boolean descendIframes) {
    // 构造 JS 参数
    Map<String, Object> args = new HashMap<>();
    args.put("doHighlightElements", highlightElements);
    args.put("focusHighlightIndex", focusElement);
    args.put("viewportExpansion", viewportExpansion);
    args.put("debugMode", log.isDebugEnabled());
    args.put("highlightIndexStart", highlightIndexStart);
    args.put("descendIframes", descendIframes);

    @SuppressWarnings("unchecked")
    Map<String, Object> evalPage = (Map<String, Object>) frame.evaluate(expression, args);
    return evalPage;
  }

  public static DOMState getClickableElements(Page page, boolean highlightElements, int focusElement, int viewportExpansion) {
    return getClickableElements(page, buildExpression(), highlightElements, focusElement, viewportExpansion);
  }

  public static DOMState getClickableElements(Page page, String expression, boolean highlightElements, int focusElement, int viewportExpansion) {
    return getClickableElements(page.mainFrame(), expression, highlightElements, focusElement, viewportExpansion, 0);
  }

  /** 在指定 frame 里建一棵可交互元素树(索引从 highlightIndexStart 开始编号) */
  public static DOMState getClickableElements(Frame frame, String expression, boolean highlightElements, int focusElement,
      int viewportExpansion, int highlightIndexStart) {
    return getClickableElements(frame, expression, highlightElements, focusElement, viewportExpansion,
        highlightIndexStart, true);
  }

  /**
   * @param descendIframes 要不要顺着同源 iframe 递归下去。默认 true(与历史行为一致);
   *                       多 frame 快照传 false —— 同源 iframe 的元素由调用方按 frame 逐个求值,
   *                       否则会被数两遍、索引撞车
   */
  public static DOMState getClickableElements(Frame frame, String expression, boolean highlightElements, int focusElement,
      int viewportExpansion, int highlightIndexStart, boolean descendIframes) {
    Map<String, Object> evalPage = evaluate(frame, expression, highlightElements, focusElement, viewportExpansion,
        highlightIndexStart, descendIframes);

    // 拿到 map 和 rootId
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> jsNodeMap = (Map<String, Map<String, Object>>) evalPage.get("map");
    Object object = evalPage.get("rootId");
    String rootId = object.toString();

    // 第一次遍历：构建所有节点（不设 parent/children）
    Map<String, DOMBaseNode> nodeMap = new HashMap<>();
    Map<Integer, DOMElementNode> selectorMap = new HashMap<>();

    Set<Entry<String, Map<String, Object>>> entrySet = jsNodeMap.entrySet();
    for (Entry<String, Map<String, Object>> entry : entrySet) {
      String id = entry.getKey();
      Map<String, Object> nd = entry.getValue();
      String type = (String) nd.get("type");

      if ("TEXT_NODE".equals(type)) {
        // 文本节点
        String text = (String) nd.get("text");
        boolean isVis = Boolean.TRUE.equals(nd.get("isVisible"));
        nodeMap.put(id, new DOMTextNode(text, isVis));
        continue;
      }

      // 元素节点
      String tag = (String) nd.get("tagName");
      String xpath = (String) nd.get("xpath");
      @SuppressWarnings("unchecked")
      Map<String, String> attrs = (Map<String, String>) nd.get("attributes");
      boolean isVis = Boolean.TRUE.equals(nd.get("isVisible"));
      boolean isInt = Boolean.TRUE.equals(nd.get("isInteractive"));
      boolean isTop = Boolean.TRUE.equals(nd.get("isTopElement"));
      boolean inVP = Boolean.TRUE.equals(nd.get("isInViewport"));
      boolean sr = Boolean.TRUE.equals(nd.get("shadowRoot"));
      Integer hi = nd.get("highlightIndex") != null ? ((Number) nd.get("highlightIndex")).intValue() : null;
      Boolean isNew = (Boolean) nd.get("isNew");

      DOMElementNode el = new DOMElementNode(tag, xpath, attrs, isVis, isInt, isTop, inVP, sr, hi, isNew);
      nodeMap.put(id, el);
      if (hi != null) {
        selectorMap.put(hi, el);
      }

    }

    // 第二次遍历：建立 parent–children 关系
    for (Entry<String, Map<String, Object>> entry : entrySet) {
      String id = entry.getKey();
      Map<String, Object> raw = entry.getValue();
      if (!(nodeMap.get(id) instanceof DOMElementNode)) {
        continue;
      }

      DOMElementNode el = (DOMElementNode) nodeMap.get(id);
      @SuppressWarnings("unchecked")
      List<Object> childList = (List<Object>) raw.get("children");
      if (childList == null) {
        continue;
      }
      for (Object o : childList) {
        String cid = o.toString();
        DOMBaseNode child = nodeMap.get(cid);
        if (child != null) {
          el.addChild(child);
        }

      }
    }

    // 拿到根节点
    DOMElementNode root = (DOMElementNode) nodeMap.get(rootId);
    int pixelsAbove = ((Number) evalPage.get("pixels_above")).intValue();
    int pixelsBelow = ((Number) evalPage.get("pixels_below")).intValue();
    int viewportHeight = ((Number) evalPage.get("viewport_height")).intValue();
    int pageHeight = ((Number) evalPage.get("page_height")).intValue();

    return new DOMState(root, selectorMap, pixelsAbove, pixelsBelow, viewportHeight, pageHeight);
  }

  public static String getSimpleText(Page page) {
    String expression = buildExpression();
    DOMState state = DomService.getClickableElements(page, expression, true, -1, 0);
    return state.getElementTree().clickableElementsToString(null);
  }

  // ==================== 多 frame 快照 ====================

  /** 一个 frame 连同它的父 frame 与深度,深度优先展开(主 frame 在最前) */
  public static final class FrameNode {
    public final Frame frame;
    public final int parentIndex;
    public final int depth;

    FrameNode(Frame frame, int parentIndex, int depth) {
      this.frame = frame;
      this.parentIndex = parentIndex;
      this.depth = depth;
    }
  }

  /**
   * 把页面上的所有 frame 深度优先展开(主 frame 排第 0 个)
   *
   * <p>
   * 刻意不用 {@code page.frames()}:它只保证「返回所有 frame」,顺序没有写进契约,而索引编号必须**稳定**
   * (同一份快照里 frame[i] 的元素就应该是 index=…)。自己从 {@code mainFrame()} 递归 {@code childFrames()}
   * 走一遍,顺序与父子关系都是确定的。
   */
  public static List<FrameNode> frameTree(Page page) {
    List<FrameNode> out = new java.util.ArrayList<>();
    Frame main = page.mainFrame();
    collect(main, -1, 0, out);
    return out;
  }

  private static void collect(Frame frame, int parentIndex, int depth, List<FrameNode> out) {
    int index = out.size();
    out.add(new FrameNode(frame, parentIndex, depth));
    for (Frame child : frame.childFrames()) {
      collect(child, index, depth + 1, out);
    }
  }

  /**
   * 建一份「带 frame」的快照:每个 frame 的元素都进同一个索引空间
   *
   * <p>
   * 每个 frame 单独求值(并且**不让脚本顺着 iframe 递归**),索引起点接着上一个 frame 往下排,所以
   * {@code selectorMap} 里的 index 是**全局唯一**的;{@code indexToFrame} 记着每个索引属于哪个 frame,
   * 按索引命令据此自动路由。
   *
   * <p>
   * 单个 frame 求值失败(跨域被拒、frame 正在销毁、空白 frame 还没有文档)不会让整次快照失败:那一条记进
   * {@link FrameSnapshot#elementCount} 为 0 并在文本里注明,其余 frame 照常可用 —— 主站外壳能读到总比整页
   * 读不到好。
   *
   * @param viewportExpansion  视口外扩像素
   * @param onlySameOriginFrames 只纳入与主 frame 同源的 frame(默认快照用这个:与以前「顺着同源 iframe
   *                             递归」的行为一致);false 表示连跨域 iframe 一起纳入
   */
  public static DOMState getFrameState(Page page, boolean highlightElements, int viewportExpansion) {
    return getFrameState(page, highlightElements, viewportExpansion, false);
  }

  public static DOMState getFrameState(Page page, boolean highlightElements, int viewportExpansion,
      boolean onlySameOriginFrames) {
    String expression = buildExpression();
    List<FrameNode> nodes = frameTree(page);
    String mainOrigin = nodes.isEmpty() ? null : frameOrigin(nodes.get(0).frame);
    List<FrameSnapshot> snapshots = new java.util.ArrayList<>(nodes.size());
    Map<Integer, DOMElementNode> merged = new java.util.LinkedHashMap<>();
    Map<Integer, Integer> indexToFrame = new java.util.LinkedHashMap<>();
    DOMElementNode mainTree = null;
    int cursor = 0;

    // 每个 frame 都占一条记录(被跳过的也在内),而且**按下标就是 frame 序号**:
    // 这样 frames.get(ordinal) 永远拿得到那个 frame,快照里显示的 frame 序号也与 frame 参数一致
    for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
      FrameNode node = nodes.get(ordinal);
      String url = safeFrameUrl(node.frame);
      String name = safeFrameName(node.frame);
      boolean main = ordinal == 0;
      String origin = main ? mainOrigin : frameOrigin(node.frame);
      boolean crossOrigin = mainOrigin != null && origin != null && !mainOrigin.equals(origin);
      if (onlySameOriginFrames && crossOrigin) {
        // 跨域 iframe:默认快照不纳入(要看它请传 includeFrames:true,或直接用 list_frames)
        snapshots.add(new FrameSnapshot(ordinal, url, name, false, node.parentIndex, node.depth, -1, -1, 0, "",
            null, 0, 0, 0, 0, node.frame, null, true,
            "跨域 frame(" + origin + "):默认快照不纳入,传 includeFrames:true 可以一起读"));
        continue;
      }
      DOMState state = null;
      String failure = null;
      try {
        // viewportExpansion=-1 表示「不管在不在视口都给索引」,与单 frame 路径的取法一致
        state = getClickableElements(node.frame, expression, highlightElements, -1, viewportExpansion, cursor, false);
      } catch (RuntimeException e) {
        failure = e.getMessage();
      }
      int first = -1;
      int last = -1;
      int count = 0;
      String text = "";
      if (state != null) {
        List<Integer> indices = new java.util.ArrayList<>(state.getSelectorMap().keySet());
        java.util.Collections.sort(indices);
        for (Integer index : indices) {
          merged.put(index, state.getSelectorMap().get(index));
          indexToFrame.put(index, ordinal);
        }
        count = indices.size();
        if (count > 0) {
          first = indices.get(0);
          last = indices.get(count - 1);
          cursor = last + 1;
        }
        text = state.getElementTree() == null ? "" : state.getElementTree().clickableElementsToString(null);
        if (main) {
          mainTree = state.getElementTree();
        }
      }
      snapshots.add(new FrameSnapshot(ordinal, url, name, main, node.parentIndex, node.depth, first, last, count,
          text, state == null ? null : state.getElementTree(),
          state == null ? 0 : state.getPixelsAbove(), state == null ? 0 : state.getPixelsBelow(),
          state == null ? 0 : state.getViewportHeight(), state == null ? 0 : state.getPageHeight(), node.frame,
          failure, false, null));
    }

    FrameSnapshot mainState = snapshots.isEmpty() ? null : snapshots.get(0);
    return new DOMState(mainTree, merged, mainState == null ? 0 : mainState.pixelsAbove,
        mainState == null ? 0 : mainState.pixelsBelow, mainState == null ? 0 : mainState.viewportHeight,
        mainState == null ? 0 : mainState.pageHeight, indexToFrame, snapshots);
  }

  /** frame 的 origin(在 frame 自己里问,about:blank 这类继承来的 origin 也拿得准);取不到时返回 null */
  private static String frameOrigin(Frame frame) {
    try {
      Object origin = frame.evaluate("() => location.origin");
      return origin == null ? null : String.valueOf(origin);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String safeFrameUrl(Frame frame) {
    try {
      String url = frame.url();
      return url == null ? "" : url;
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static String safeFrameName(Frame frame) {
    try {
      String name = frame.name();
      return name == null ? "" : name;
    } catch (RuntimeException e) {
      return "";
    }
  }

}
