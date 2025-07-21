package com.litongjava.ai.browser.dom.model;

public class DOMTextNode extends DOMBaseNode {
  private final String text;
  private final String type = "TEXT_NODE";

  public DOMTextNode(String text, boolean isVisible) {
    super(isVisible);
    this.text = text;
  }

  public String getText() {
    return text;
  }

  public boolean hasParentWithHighlightIndex() {
    DOMElementNode cur = parent;
    while (cur != null) {
      if (cur.getHighlightIndex() != null)
        return true;
      cur = cur.getParent();
    }
    return false;
  }

  public boolean isParentInViewport() {
    return parent != null && parent.isInViewport();
  }

  public boolean isParentTopElement() {
    return parent != null && parent.isTopElement();
  }
}
