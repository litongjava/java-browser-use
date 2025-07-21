package com.litongjava.ai.browser.dom.model;

import java.util.Map;

public class DOMState {
  private final DOMElementNode elementTree;
  private final Map<Integer, DOMElementNode> selectorMap;
  public final int pixelsAbove;
  public final int pixelsBelow;
  public final int viewportHeight;
  public final int pageHeight;

  public DOMState(DOMElementNode tree, Map<Integer, DOMElementNode> sel,
      //
      int pixelsAbove, int pixelsBelow, int viewportHeight, int pageHeight) {
    this.elementTree = tree;
    this.selectorMap = sel;
    this.pixelsAbove = pixelsAbove;
    this.pixelsBelow = pixelsBelow;
    this.viewportHeight = viewportHeight;
    this.pageHeight = pageHeight;
  }

  public DOMElementNode getElementTree() {
    return elementTree;
  }

  public Map<Integer, DOMElementNode> getSelectorMap() {
    return selectorMap;
  }

  public int getPixelsAbove() {
    return pixelsAbove;
  }

  public int getPixelsBelow() {
    return pixelsBelow;
  }

  public int getViewportHeight() {
    return viewportHeight;
  }

  public int getPageHeight() {
    return pageHeight;
  }
  
  
}
