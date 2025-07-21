package com.litongjava.ai.browser.dom.service;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.litongjava.ai.browser.dom.model.DOMBaseNode;
import com.litongjava.ai.browser.dom.model.DOMElementNode;
import com.litongjava.ai.browser.dom.model.DOMState;
import com.litongjava.ai.browser.dom.model.DOMTextNode;
import com.litongjava.tio.utils.hutool.FileUtil;
import com.litongjava.tio.utils.hutool.ResourceUtil;
import com.microsoft.playwright.Page;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DomService {

  public static String buildExpression() {
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
    return String.format(stringBuilder.toString(), buildScript);
  }

  public static Map<String, Object> evaluate(Page page, boolean highlightElements, int focusElement, int viewportExpansion) {
    return evaluate(page, buildExpression(), highlightElements, focusElement, viewportExpansion);
  }

  public static Map<String, Object> evaluate(Page page, String expression, boolean highlightElements, int focusElement, int viewportExpansion) {
    // 构造 JS 参数
    Map<String, Object> args = new HashMap<>();
    args.put("doHighlightElements", highlightElements);
    args.put("focusHighlightIndex", focusElement);
    args.put("viewportExpansion", viewportExpansion);
    args.put("debugMode", log.isDebugEnabled());

    @SuppressWarnings("unchecked")
    Map<String, Object> evalPage = (Map<String, Object>) page.evaluate(expression, args);
    return evalPage;
  }

  public static DOMState getClickableElements(Page page, boolean highlightElements, int focusElement, int viewportExpansion) {
    return getClickableElements(page, buildExpression(), highlightElements, focusElement, viewportExpansion);
  }

  public static DOMState getClickableElements(Page page, String expression, boolean highlightElements, int focusElement, int viewportExpansion) {
    Map<String, Object> evalPage = evaluate(page, expression, highlightElements, focusElement, viewportExpansion);

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

}
