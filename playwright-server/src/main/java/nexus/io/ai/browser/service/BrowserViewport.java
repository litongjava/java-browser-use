package nexus.io.ai.browser.service;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 窗口尺寸与页面视口的策略(配置项 {@code browser.viewport})
 *
 * <p>
 * <b>这两件事以前被当成一件事,于是有了「页面比窗口还大」的毛病。</b>它们必须分开看:
 *
 * <ul>
 * <li><b>窗口尺寸</b>({@link #windowSize()}):按屏幕的<b>可用工作区</b>算 —— 宽取 28/32、高取满。
 * 用 {@link GraphicsEnvironment#getMaximumWindowBounds()} 而不是 {@code getScreenSize()}:后者返回整块
 * 屏幕、**不扣任务栏**,窗口会顶到屏幕底边被任务栏压住。实测这台机器(逻辑分辨率 1707x1067、任务栏占
 * 48px)按老算法算出 1067 高,而实际可用只有 1019。</li>
 * <li><b>页面视口</b>({@link #policy(boolean)}):可以钉死一个尺寸,也可以交给真实窗口
 * ({@code browser.viewport=window},有头时的默认值)。</li>
 * </ul>
 *
 * <p>
 * <b>为什么要分开看。</b>视口钉死时,页面按钉死的尺寸排版,而窗口按自己的尺寸显示,「视口高度」与「可见
 * 高度」从此对不上。实测(1484x1067 的视口 + 1484x1019 的窗口外框):页面自称
 * {@code window.innerHeight == 1067},而窗口外框只有 1019,再减掉标题栏/标签栏/地址栏约 88px,
 * 真实可见的页面区只有约 931px —— <b>每页底部约 13% 渲染在窗口外面</b>,人看不见,而 {@code screenshot}
 * 却是按 1484x1067 拍的,也就是说<b>喂给视觉模型的画面和你眼睛看到的不是一回事</b>。
 *
 * <p>
 * 交给真实窗口之后({@code browser.viewport=window}),页面按窗口的实际内容区排版、随窗口缩放实时回流,
 * 截图尺寸也随之变成真实可见尺寸 —— 所见即所得。代价是截图小了一点(该被裁的本来也看不见)。
 *
 * <p>
 * 无头模式没有真实窗口可跟随,所以配置成 {@code window} 时也会退回固定视口 —— 否则视口会落到
 * {@code --window-size} 上,尺寸不可预期。这一点写在 {@link Policy#note} 里,随 {@code start} 回执返回。
 */
public final class BrowserViewport {

  /** 配置项:页面视口策略。取值见 {@link #MODE_WINDOW} / {@link #MODE_FIXED} / {@code 宽x高} */
  public static final String KEY_VIEWPORT = "browser.viewport";

  /** 视口交给真实窗口(有头时的默认值):拖动窗口页面跟着回流 */
  public static final String MODE_WINDOW = "window";

  /** 视口钉死在窗口尺寸上(无头时的默认值,也是以前的唯一行为) */
  public static final String MODE_FIXED = "fixed";

  /** 窗口宽占可用工作区宽的 28/32 */
  private static final int WIDTH_NUMERATOR = 28;
  private static final int WIDTH_DENOMINATOR = 32;

  /** 可用工作区小到离谱时(远程桌面、异常 DPI)也别给出一个打不开的窗口 */
  private static final int MIN_WIDTH = 320;
  private static final int MIN_HEIGHT = 240;

  /** 完全问不到屏幕尺寸时的兜底(容器/无 DISPLAY:下面两条探测都会抛异常) */
  private static final Dimension FALLBACK = new Dimension(1280, 800);

  /** 显式写死的尺寸,例如 {@code 1440x900}(x 也认 * 与 ×) */
  private static final Pattern EXPLICIT_SIZE = Pattern.compile("^(\\d{3,5})\\s*[x*×]\\s*(\\d{3,5})$");

  private BrowserViewport() {
  }

  /**
   * 这次启动该用哪种视口策略
   *
   * @param headless 这次是不是无头;无头下 {@code window} 会退回固定视口
   * @throws IllegalArgumentException 配置值不认识时直接失败并列出可选值(与 {@code browser.type} 的做派一致)
   */
  public static Policy policy(boolean headless) {
    return policy(ChromeBrowser.config(KEY_VIEWPORT), headless, windowSize());
  }

  /**
   * 解析配置值(与读配置解耦,便于测试)
   *
   * @param configured 配置原文;null / 空 / {@code window} / {@code follow} / {@code auto} 都表示跟随窗口
   * @param window     固定视口与窗口尺寸用的那个尺寸
   */
  static Policy policy(String configured, boolean headless, Dimension window) {
    String value = configured == null ? null : configured.trim().toLowerCase(Locale.ROOT);
    boolean followRequested = value == null || value.isEmpty()
        || MODE_WINDOW.equals(value) || "follow".equals(value) || "auto".equals(value);

    if (followRequested) {
      if (headless) {
        // 无头没有真实窗口可跟随;静默退回固定视口,但把原因说出来,免得有人配了 window 却以为生效了
        return new Policy(MODE_FIXED, window.width, window.height,
            "无头模式没有真实窗口可跟随,browser.viewport=window 退回固定视口 "
                + window.width + "x" + window.height + "（有头启动时才会跟随窗口）");
      }
      return new Policy(MODE_WINDOW, null, null, null);
    }

    if (MODE_FIXED.equals(value)) {
      return new Policy(MODE_FIXED, window.width, window.height, null);
    }

    Matcher explicit = EXPLICIT_SIZE.matcher(value);
    if (explicit.matches()) {
      return new Policy(MODE_FIXED, Integer.parseInt(explicit.group(1)), Integer.parseInt(explicit.group(2)), null);
    }

    throw new IllegalArgumentException("无法识别的 " + KEY_VIEWPORT + "：" + configured
        + "，可选值：window（跟随真实窗口）/ fixed（用按屏幕算出的固定尺寸）/ 宽x高（例如 1440x900）");
  }

  /**
   * 浏览器窗口的外框尺寸:可用工作区宽的 28/32,高度取满
   *
   * <p>
   * Chromium 与 Firefox 共用同一套算法,免得两个引擎的窗口大小不一致(排查时看到的页面布局也就不一致)。
   * 它也用来给 {@code --window-size}(CDP 那条路必须自己给一个尺寸)。
   *
   * <p>
   * <b>高度是「可用工作区」而不是「整块屏幕」</b>:{@code getScreenSize()} 把任务栏也算进去,按它开的
   * 窗口底边会被任务栏压住 —— 实测差 48px,而 Chrome 的窗口高度是外框,压住的就是页面底部。
   */
  public static Dimension windowSize() {
    Rectangle usable = usableBounds();
    int width = Math.max(MIN_WIDTH, (usable.width / WIDTH_DENOMINATOR) * WIDTH_NUMERATOR);
    int height = Math.max(MIN_HEIGHT, usable.height);
    return new Dimension(width, height);
  }

  /**
   * 屏幕的可用工作区(已扣掉任务栏 / Dock)
   *
   * <p>
   * 三级退让,任何一级失败都不该让 {@code start} 直接挂掉:
   * <ol>
   * <li>{@code getMaximumWindowBounds()}:最准,Windows 上是工作区(屏幕减去任务栏);</li>
   * <li>{@code getScreenSize()}:拿不到工作区时的老算法(含任务栏);</li>
   * <li>{@link #FALLBACK}:连屏幕都问不到(容器里没有 DISPLAY、AWT 处于 headless)。</li>
   * </ol>
   * 前两级在没有显示设备的机器上会抛 {@code HeadlessException} 或 {@code AWTError},所以这里必须接住 ——
   * 老代码直接调 {@code getScreenSize()},在容器里会让整个 {@code start} 失败在算窗口尺寸这一步。
   */
  static Rectangle usableBounds() {
    try {
      Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
      if (bounds != null && bounds.width > 0 && bounds.height > 0) {
        return bounds;
      }
    } catch (Throwable ignored) {
      // 没有显示设备(headless / 无 DISPLAY)时 AWT 会抛 HeadlessException 或 AWTError,退到下一级
    }
    try {
      Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
      if (screen != null && screen.width > 0 && screen.height > 0) {
        return new Rectangle(screen);
      }
    } catch (Throwable ignored) {
      // 同上
    }
    return new Rectangle(FALLBACK);
  }

  /**
   * 一次启动定下来的视口策略
   *
   * <p>
   * {@code width / height} 为 null 表示「跟随窗口」,其余情况都是钉死的尺寸。这个区分用
   * {@link #followsWindow()} 读,别自己判 null。
   */
  public static final class Policy {
    /** {@link #MODE_WINDOW} 或 {@link #MODE_FIXED} */
    public final String mode;
    /** 钉死的视口宽;跟随窗口时为 null */
    public final Integer width;
    /** 钉死的视口高;跟随窗口时为 null */
    public final Integer height;
    /** 需要说给调用方听的说明(例如「无头下退回了固定视口」);没有为 null */
    public final String note;

    Policy(String mode, Integer width, Integer height, String note) {
      this.mode = mode;
      this.width = width;
      this.height = height;
      this.note = note;
    }

    /** 视口是不是交给真实窗口(即启动参数用 {@code noDefaultViewport},不套 emulation) */
    public boolean followsWindow() {
      return MODE_WINDOW.equals(mode);
    }

    /** 回执里用的紧凑描述,例如 {@code window} 或 {@code fixed 1484x1019} */
    public String describe() {
      return followsWindow() ? MODE_WINDOW : mode + " " + width + "x" + height;
    }
  }
}
