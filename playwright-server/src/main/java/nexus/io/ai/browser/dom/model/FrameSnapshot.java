package nexus.io.ai.browser.dom.model;

import com.microsoft.playwright.Frame;

/**
 * 一次快照里的一个 frame
 *
 * <p>
 * <b>为什么需要它</b>:企业微信后台把「邮件」应用套在 {@code exmail.qq.com} 的**跨域 iframe** 里,微盘 /
 * 文档 / 会议同理。这种形态下顶层 {@code document} 里一个元素都看不见,而 Playwright 本身是能跨 frame
 * 的(它走浏览器协议取内容,不依赖往页面里注入 JS)—— 缺的只是把 {@code page.frames()} 暴露出来。这个类
 * 就是那份暴露:每个 frame 一行元信息 + 它自己的可交互元素树 + 它占用的**全局索引区间**。
 *
 * <p>
 * <b>索引怎么保证全局唯一</b>:每个 frame 单独求值时索引会各自从 0 开始,必然撞车。所以按 frame 顺序依次
 * 求值,把「前面几个 frame 已经用掉多少」当起点传给下一个 frame(见 {@code DomService} 的
 * {@code highlightIndexStart}),于是 {@code firstIndex..lastIndex} 就是该 frame 在快照里的索引区间,
 * 而按索引命令(click_element_by_index / input_text …)靠 {@code indexToFrame} 自动路由到正确的 frame,
 * 调用方**不需要**再传 frame 参数。
 */
public class FrameSnapshot {

  /**
   * frame 序号:0 固定是主 frame,其余按 Playwright 的 frame 树顺序深度优先编号
   *
   * <p>与命令里的 {@code frame} 参数、{@code list_frames} 里的 {@code index} 都是同一个编号 ——
   * 从快照文本里读到 {@code --- frame[2] … ---} 就直接写 {@code {"frame":2}}。
   */
  public final int index;
  /** 该 frame 的 URL(跨域 frame 也能读到,这是识别它的主要依据) */
  public final String url;
  /** {@code <iframe name="...">} 的 name(没有则为空串) */
  public final String name;
  /** 是不是主 frame */
  public final boolean main;
  /** 父 frame 的序号;-1 表示没有父(主 frame) */
  public final int parentIndex;
  /** 嵌套深度,主 frame 是 0 */
  public final int depth;
  /** 这个 frame 占用的全局索引下界;没有可交互元素时与 lastIndex 一起为 -1 */
  public final int firstIndex;
  /** 这个 frame 占用的全局索引上界(含);没有可交互元素时为 -1 */
  public final int lastIndex;
  /** 可交互元素个数 */
  public final int elementCount;
  /** 这个 frame 自己的可交互结构化文本(与 data.text 里那一段完全一致) */
  public final String text;
  /** 该 frame 的元素树根(用于按 xpath 找回元素) */
  public final DOMElementNode tree;
  public final int pixelsAbove;
  public final int pixelsBelow;
  public final int viewportHeight;
  public final int pageHeight;
  /**
   * 快照时用的 frame 句柄
   *
   * <p>
   * frame 是运行期对象,页面一导航(或 iframe 被重建)它就 detach 了。所以动作执行时**不能**无条件拿它去定位:
   * 先按 {@link #url} 在当前 {@code page.frames()} 里重新认一次,认不出来才退回这个句柄(见
   * {@code PlaywrightService.frameForIndex})。
   */
  public final Frame frame;
  /** 这个 frame 读取失败的原因(跨域被拒 / frame 正在销毁 / 还没有文档);成功时为 null */
  public final String readFailure;
  /**
   * 这个 frame 是不是**没被纳入**本次快照
   *
   * <p>默认快照(不带 {@code includeFrames})只纳入同源 frame —— 与以前「顺着同源 iframe 递归」的行为一致;
   * 跨域 frame 被跳过的原因记在 {@link #skipReason}。要纳入全部 frame 就传 {@code includeFrames:true}。
   */
  public final boolean skipped;
  /** 被跳过的原因 */
  public final String skipReason;

  public FrameSnapshot(int index, String url, String name, boolean main, int parentIndex, int depth,
      int firstIndex, int lastIndex, int elementCount, String text, DOMElementNode tree, int pixelsAbove,
      int pixelsBelow, int viewportHeight, int pageHeight, Frame frame, String readFailure) {
    this(index, url, name, main, parentIndex, depth, firstIndex, lastIndex, elementCount, text, tree, pixelsAbove,
        pixelsBelow, viewportHeight, pageHeight, frame, readFailure, false, null);
  }

  public FrameSnapshot(int index, String url, String name, boolean main, int parentIndex, int depth,
      int firstIndex, int lastIndex, int elementCount, String text, DOMElementNode tree, int pixelsAbove,
      int pixelsBelow, int viewportHeight, int pageHeight, Frame frame, String readFailure, boolean skipped,
      String skipReason) {
    this.index = index;
    this.url = url;
    this.name = name;
    this.main = main;
    this.parentIndex = parentIndex;
    this.depth = depth;
    this.firstIndex = firstIndex;
    this.lastIndex = lastIndex;
    this.elementCount = elementCount;
    this.text = text;
    this.tree = tree;
    this.pixelsAbove = pixelsAbove;
    this.pixelsBelow = pixelsBelow;
    this.viewportHeight = viewportHeight;
    this.pageHeight = pageHeight;
    this.frame = frame;
    this.readFailure = readFailure;
    this.skipped = skipped;
    this.skipReason = skipReason;
  }

  /** 这个 frame 有没有可交互元素 */
  public boolean hasElements() {
    return elementCount > 0;
  }

  /** 给模型读的一行元信息(快照文本里的 frame 分隔行用它) */
  public String header() {
    StringBuilder sb = new StringBuilder("--- frame[").append(index).append(']');
    if (main) {
      sb.append(" (main)");
    }
    sb.append(' ').append(url == null ? "" : url);
    if (name != null && !name.isEmpty()) {
      sb.append(" name=\"").append(name).append('"');
    }
    sb.append(" elements=").append(elementCount);
    if (hasElements()) {
      sb.append(" index=").append(firstIndex).append("..").append(lastIndex);
    }
    if (readFailure != null) {
      sb.append(" [读取失败: ").append(readFailure).append(']');
    }
    sb.append(" ---");
    return sb.toString();
  }
}
