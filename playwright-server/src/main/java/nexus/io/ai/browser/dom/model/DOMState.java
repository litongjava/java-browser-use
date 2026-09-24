package nexus.io.ai.browser.dom.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DOMState {
  private final DOMElementNode elementTree;
  private final Map<Integer, DOMElementNode> selectorMap;
  public final int pixelsAbove;
  public final int pixelsBelow;
  public final int viewportHeight;
  public final int pageHeight;

  /**
   * 全局索引 -&gt; frame 序号(0 是主 frame)
   *
   * <p>
   * 单 frame 快照时是空表(所有索引都在主 frame);带 frame 的快照(见 {@code get_browser_state} 的
   * {@code includeFrames})里每个索引都有一条。按索引命令靠它把动作路由到正确的 frame —— 索引里已经带了
   * frame 信息,所以调用方不必再传 frame 参数。
   */
  private final Map<Integer, Integer> indexToFrame;

  /**
   * 这次快照涉及的 frame,下标即 frame 序号(0 是主 frame)
   *
   * <p>单 frame 快照时是空表,调用方按「所有索引都在主 frame」处理。
   */
  private final List<FrameSnapshot> frames;

  public DOMState(DOMElementNode tree, Map<Integer, DOMElementNode> sel,
      //
      int pixelsAbove, int pixelsBelow, int viewportHeight, int pageHeight) {
    this(tree, sel, pixelsAbove, pixelsBelow, viewportHeight, pageHeight, Collections.emptyMap(),
        Collections.emptyList());
  }

  public DOMState(DOMElementNode tree, Map<Integer, DOMElementNode> sel, int pixelsAbove, int pixelsBelow,
      int viewportHeight, int pageHeight, Map<Integer, Integer> indexToFrame, List<FrameSnapshot> frames) {
    this.elementTree = tree;
    this.selectorMap = sel;
    this.pixelsAbove = pixelsAbove;
    this.pixelsBelow = pixelsBelow;
    this.viewportHeight = viewportHeight;
    this.pageHeight = pageHeight;
    this.indexToFrame = indexToFrame == null ? Collections.emptyMap() : indexToFrame;
    this.frames = frames == null ? Collections.emptyList() : frames;
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

  public Map<Integer, Integer> getIndexToFrame() {
    return indexToFrame;
  }

  public List<FrameSnapshot> getFrames() {
    return frames;
  }

  /** 这次快照是不是带 frame 的(带了才有 {@link FrameSnapshot} 与索引路由) */
  public boolean hasFrames() {
    return !frames.isEmpty();
  }

  /** 取某个索引所属的 frame;没有 frame 信息时返回 null(表示主 frame) */
  public FrameSnapshot frameOf(int index) {
    Integer ordinal = indexToFrame.get(index);
    if (ordinal == null || ordinal < 0 || ordinal >= frames.size()) {
      return null;
    }
    return frames.get(ordinal);
  }
}
