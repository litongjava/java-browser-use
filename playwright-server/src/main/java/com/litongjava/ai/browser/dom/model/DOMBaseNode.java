package com.litongjava.ai.browser.dom.model;

public abstract class DOMBaseNode {
  protected boolean isVisible;
  protected DOMElementNode parent;

  public DOMBaseNode(boolean isVisible) {
    this.isVisible = isVisible;
  }

  public boolean isVisible() {
    return isVisible;
  }

  public DOMElementNode getParent() {
    return parent;
  }

  public void setParent(DOMElementNode parent) {
    this.parent = parent;
  }
}
