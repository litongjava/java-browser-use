package com.litongjava.ai.browser.dom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.litongjava.tio.utils.collect.Lists;
import com.litongjava.tio.utils.hutool.StrUtil;

public class DOMElementNode extends DOMBaseNode {
  private final String tagName;
  private final String xpath;
  private final Map<String, String> attributes;
  private final List<DOMBaseNode> children = new ArrayList<>();

  private final boolean isInteractive;
  private final boolean isTopElement;
  private final boolean isInViewport;
  private final boolean shadowRoot;
  private final Integer highlightIndex;
  private final Boolean isNew;

  // （viewportInfo / pageCoordinates 按需补）
  public static final List<String> DEFAULT_INCLUDE_ATTRIBUTES = Lists.of("title", "type", "checked", "name", "role", "value",
      //
      "placeholder", "data-date-format", "alt", "aria-label", "aria-expanded", "data-state", "aria-checked");

  public DOMElementNode(String tagName, String xpath, Map<String, String> attributes, boolean isVisible, boolean
  //
  isInteractive, boolean isTopElement, boolean isInViewport, boolean shadowRoot, Integer highlightIndex, Boolean isNew) {
    super(isVisible);
    this.tagName = tagName;
    this.xpath = xpath;
    this.attributes = attributes;
    this.isInteractive = isInteractive;
    this.isTopElement = isTopElement;
    this.isInViewport = isInViewport;
    this.shadowRoot = shadowRoot;
    this.highlightIndex = highlightIndex;
    this.isNew = isNew;
  }

  public String getTagName() {
    return tagName;
  }

  public Integer getHighlightIndex() {
    return highlightIndex;
  }

  public boolean isInteractive() {
    return isInteractive;
  }

  public boolean isTopElement() {
    return isTopElement;
  }

  public boolean isInViewport() {
    return isInViewport;
  }

  public Boolean isNew() {
    return isNew;
  }

  public List<DOMBaseNode> getChildren() {
    return children;
  }

  public void addChild(DOMBaseNode child) {
    children.add(child);
    if (child instanceof DOMElementNode) {
      ((DOMElementNode) child).setParent(this);
    } else if (child instanceof DOMTextNode) {
      ((DOMTextNode) child).setParent(this);
    }
  }

  /**
   * 收集从 this 节点开始，直到下一个可点击元素前的所有文本
   */
  public String getAllTextTillNextClickableElement(int maxDepth) {
    StringBuilder sb = new StringBuilder();
    collectText(this, 0, maxDepth, sb);
    return sb.toString().trim();
  }

  private void collectText(DOMBaseNode node, int depth, int maxDepth, StringBuilder sb) {
    if (maxDepth != -1 && depth > maxDepth) {
      return;
    }
    if (node instanceof DOMElementNode) {
      DOMElementNode el = (DOMElementNode) node;
      if (el != this && el.getHighlightIndex() != null) {
        return;
      }
      for (DOMBaseNode c : el.getChildren()) {
        collectText(c, depth + 1, maxDepth, sb);
      }
    } else if (node instanceof DOMTextNode) {
      //sb.append(((DOMTextNode) node).getText()).append("\n");
      String text = ((DOMTextNode) node).getText();
      sb.append(text.trim()).append(" ");
    }
  }

  /**
   * 核心：把可点击元素按照 [index]<tag 属性拼接>文本/> 的格式输出
   */
  public String clickableElementsToString(List<String> includeAttributes) {
    if (includeAttributes == null) {
      includeAttributes = DEFAULT_INCLUDE_ATTRIBUTES;
    }

    List<String> out = new ArrayList<>();
    processNode(this, "", includeAttributes, out);
    return out.stream().collect(Collectors.joining("\n"));
  }

  private void processNode(DOMBaseNode node, String indent, List<String> includeAttributes, List<String> out) {
    if (node instanceof DOMElementNode) {
      DOMElementNode el = (DOMElementNode) node;
      if (el.getHighlightIndex() != null) {
        String text = el.getAllTextTillNextClickableElement(-1);
        String attrStr = buildAttributesHtml(el.attributes, includeAttributes, text);
        String indicator = Boolean.TRUE.equals(el.isNew()) ? "*[" + el.getHighlightIndex() + "]" : "[" + el.getHighlightIndex() + "]";
        String line = indent + indicator + "<" + el.tagName + (attrStr.isEmpty() ? "" : " " + attrStr)
            + (text.isEmpty() ? (attrStr.isEmpty() ? " " : "") + "/>" : (attrStr.isEmpty() ? " " : "") + ">" + text + "/>");
        out.add(line);
      }
      // 继续递归
      for (DOMBaseNode c : el.getChildren()) {
        processNode(c, indent + "\t", includeAttributes, out);
      }
    } else if (node instanceof DOMTextNode) {
      DOMTextNode txt = (DOMTextNode) node;
      if (txt.hasParentWithHighlightIndex())
        return;
      DOMElementNode p = txt.getParent();
      if (p != null && p.isVisible() && p.isTopElement()) {
        out.add(indent + txt.getText());
      }
    }
  }

  private String buildAttributesHtml(Map<String, String> attrs, List<String> includeAttributes, String text) {
    Map<String, String> keep = new LinkedHashMap<>();
    for (String k : includeAttributes) {
      if (attrs.containsKey(k) && StrUtil.isNotBlank(attrs.get(k))) {
        keep.put(k, cap(attrs.get(k), 15));
      }
    }
    // “去重”“剔除与文本相同的属性” 等…可按需补
    return keep.entrySet().stream().map(e -> e.getKey() + "='" + e.getValue() + "'").collect(Collectors.joining(" "));
  }

  private static String cap(String s, int max) {
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }
}
