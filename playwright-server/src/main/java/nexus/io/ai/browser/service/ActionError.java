package nexus.io.ai.browser.service;

import java.util.Locale;

/**
 * Classify the complete Playwright call log, not only the first timeout line.
 */
public final class ActionError {
  private ActionError() {
  }

  /**
   * 执行器抛了未预期异常:动作**可能**已经生效
   *
   * <p>
   * 实测点「下载」类按钮时底层会抛 {@code Object doesn't exist: artifact@… / response@…},
   * 而文件其实已经落盘。这类异常**不能当成普通失败**——调用方看到失败就会重试,而重试的代价是
   * 重复下载 / 重复提交。所以单独给一个码,并固定为不可重试。
   */
  public static final String ACTION_UNCERTAIN = "ACTION_UNCERTAIN";

  /**
   * Playwright 事件泵投递过来的「伪故障」:异常不是本次命令产生的,却砸在本次命令上
   *
   * <p>
   * <b>机制</b>:Playwright Java 把 Page 级事件挂在**上下文**上,服务器按上下文通道下发
   * {@code response} / {@code request} / {@code requestFailed} 等事件;客户端在
   * {@code BrowserContextImpl.handleEvent} 里按 guid 查对象。一旦那个对象已经被释放
   * (驱动自己发的 {@code __dispose__}),{@code Connection.getExistingObject} 就抛
   * {@code Object doesn't exist: response@…};而这一抛发生在**消息泵**里 —— 它会顺着
   * {@code Connection.processOneMessage → runUntil} 逃出来,砸在**当时正在等待回复的那次 API 调用**上。
   * 于是「execute_js 报 response@ 不存在」「截图报 request@ 不存在」「go_to_url 报 response@ 不存在」
   * 这类**互相矛盾**的报错会成串出现,而页面其实好好的。
   *
   * <p>
   * <b>不是我们的 bug,也不能靠升级修</b>:1.53.0 与 1.63.0 的 {@code BrowserContextImpl.handleEvent}
   * 里,只有 {@code dialog} 与 {@code pageError} 两个分支包了 try/catch,{@code response}/{@code request}
   * 分支的 {@code getExistingObject} 至今没有兜(字节码的 Exception table 里只有那两段)。
   * 上游 issue:playwright-java#1197 / #624。所以我们自己兜:
   * 只读命令由服务端**自动重试**(见 {@code ActionService} 的 {@code SPURIOUS_RETRY_SAFE}),
   * 动作类命令不重试,只把「可能已生效」如实说清。
   */
  public static final String SPURIOUS_DISPATCH = "SPURIOUS_DISPATCH";

  /**
   * 选择器在页面里**一个都没匹配到**
   *
   * <p>
   * <b>为什么要单独一个码</b>:以前这种情况会走到 {@code locator.click()} 上,等满可操作性超时之后
   * 报 {@code ACTION_TIMEOUT}「等待元素可操作超时,不能据此确定元素不存在」。这句话在两种情况下都成立,
   * 于是调用方**分不清**「选择器写错了」和「元素在、只是暂时不可点」,只能两条路都试一遍。
   * 实测踩到过:把 {@code .tag-pre-wrp input.input-val} 当成「标签输入框在 .tag-pre-wrp 里面」——
   * 实际上那个 input 是 {@code .tag-pre-wrp} 的**兄弟**节点,选择器匹配 0 个,却报成超时,
   * 于是先去查监听器、又去查遮挡,白花了两轮。
   *
   * <p>
   * 现在动作类命令在动手之前先 {@code count()} 一次:为 0 就立刻失败,并把「匹配到 0 个」写在提示里。
   * 这类错误**不可重试** —— 页面没变,重试多少次都是 0。
   */
  public static final String ELEMENT_NOT_FOUND = "ELEMENT_NOT_FOUND";

  /**
   * 页面正在导航 / 重载,DOM 这一刻拿不到(主 frame 为 null)
   *
   * <p>
   * 症状是一句与命令毫不相干的 {@code Cannot invoke "com.microsoft.playwright.Frame.childFrames()"
   * because "frame" is null}:用户手动刷新、SPA 整页重建、micro-app 重新挂载时都会出现。
   * 以前它被归成 {@link #ACTION_UNCERTAIN}(「无法判断动作是否生效,不要重试」)—— 对一次已经成功的
   * 只读命令来说这是最糟的答复:明明**重发一次就好**,却告诉调用方别动。
   * 现在单独给码并标成可重试,只读命令由服务端自己等页面回来。
   */
  public static final String PAGE_NAVIGATING = "PAGE_NAVIGATING";

  /**
   * 这是「事件泵投递过来的伪故障」吗
   *
   * <p>
   * 只看驱动那两个只可能来自「对象已被释放」的措辞:{@code Object doesn't exist}(拿 guid 查不到对象)
   * 与 {@code Cannot find object to call}(拿 guid 查不到被调用的通道)。
   *
   * <p>
   * <b>注意这一族有两种读法</b>:①驱动事件泵的伪故障(与本次命令无关,重发即可);②本次命令的目标句柄
   * 真的失效了(例如元素已被页面换掉)。文本上分不开,所以只拿它做**重发**的依据(只读命令重发无害),
   * 不用它下「动作成功了」的结论。
   */
  public static boolean isSpuriousDispatch(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT);
    return text.contains("object doesn't exist") || text.contains("cannot find object to call");
  }

  /**
   * 这条异常是不是「页面正在导航/重载,这一刻拿不到 DOM」
   *
   * <p>
   * 实测原文是 {@code Cannot invoke "com.microsoft.playwright.Frame.childFrames()" because "frame" is null},
   * 出现在用户手动刷新 B 站投稿页之后(SPA 整页重建 + micro-app 重新挂载)。它既不是伪故障
   * (不是事件泵投递的),也不是页面坏了 —— 就是**这一刻**还没有 frame。
   *
   * <p>
   * 只认这一族措辞,不认 {@code Target closed}:后者往往意味着浏览器/上下文真的没了,等多久都不会回来,
   * 把它当「等一会儿就好」会掩盖真实故障。
   */
  public static boolean isPageNavigating(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT);
    return text.contains("because \"frame\" is null")
        || text.contains("because \"page\" is null")
        || text.contains("execution context was destroyed")
        || text.contains("frame was detached")
        || text.contains("navigating and changing the document");
  }

  public static String code(String message) {
    String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
    // 放在最前面:这一族措辞很独特,而且「是不是伪故障」比「元素怎么了」更该先告诉调用方
    if (isSpuriousDispatch(text)) {
      return SPURIOUS_DISPATCH;
    }
    // 这两个是服务端自己掷出来的哨兵(见上面各常量的注释)。必须排在通用措辞之前,
    // 否则 PAGE_NAVIGATING 会被后面的 timeout 分支当成普通失败。
    if (text.contains("element_not_found")) {
      return ELEMENT_NOT_FOUND;
    }
    if (isPageNavigating(text)) {
      return PAGE_NAVIGATING;
    }
    if (text.contains("element_read_only") || text.contains("read-only") || text.contains("readonly"))
      return "ELEMENT_READ_ONLY";
    if (text.contains("element_disabled") || text.contains("not enabled") || text.contains("element is disabled"))
      return "ELEMENT_DISABLED";
    if (text.contains("element_obscured") || text.contains("intercepts pointer events"))
      return "ELEMENT_OBSCURED";
    if (text.contains("not visible") || text.contains("element_hidden"))
      return "ELEMENT_HIDDEN";
    if (text.contains("not editable") || text.contains("element_not_editable"))
      return "ELEMENT_NOT_EDITABLE";
    if (text.contains("not attached") || text.contains("detached") || text.contains("索引越界"))
      return "STALE_ELEMENT";
    if (text.contains("不允许重复提交") || text.contains("请稍候") || text.contains("too many requests")
        || text.contains("rate limit"))
      return "RATE_LIMITED";
    if (text.contains("timeout") || text.contains("超时"))
      return "ACTION_TIMEOUT";
    return "ACTION_FAILED";
  }

  /**
   * 这类错误「等一会儿重试」是否可能成功
   *
   * <p>
   * 站点侧限流是最典型的可重试错误:实测政务站点的「不允许重复提交,请稍候再试」是服务端节流,
   * 等 30~60 秒再来一次就能过。以前这类判断只能靠调用方读中文提示去猜,现在回执里直接给出
   * {@code retryable} 与 {@code retryAfterMs},调用方可以据此自动退避重试。
   *
   * <p>
   * 反过来,**元素类错误不该无脑重试**:{@code ELEMENT_READ_ONLY}/{@code ELEMENT_DISABLED} 是
   * 页面语义决定的,重试多少次都一样;{@code STALE_ELEMENT} 可以重试,但必须先重取快照
   * (所以这里也算可重试,只是提示里会说明)。
   *
   * <p>
   * {@code SPURIOUS_DISPATCH} 也**刻意不标成可重试**:只读命令服务端已经自己重发过了
   * (见 {@code ActionService} 的 {@code SPURIOUS_RETRY_SAFE}),而动作类命令可能已经生效 ——
   * 在这里回一句「可以重试」会诱导调用方重复提交。
   */
  public static boolean retryable(String code) {
    switch (code) {
    case "RATE_LIMITED":
    case "ACTION_TIMEOUT":
    case "STALE_ELEMENT":
    case PAGE_NAVIGATING:
      return true;
    default:
      return false;
    }
  }

  /**
   * 建议的退避时间(毫秒)
   *
   * @return 不可重试时返回 0
   */
  public static long retryAfterMs(String code) {
    switch (code) {
    case "RATE_LIMITED":
      // 站点节流窗口实测在几十秒量级,给一个偏保守的建议值
      return 35_000;
    case "ACTION_TIMEOUT":
      return 1_500;
    case "STALE_ELEMENT":
      return 300;
    case PAGE_NAVIGATING:
      // SPA 整页重建通常几百毫秒就回来了;给 1 秒,足够而不用让调用方干等
      return 1_000;
    default:
      return 0;
    }
  }

  public static String describe(String action, String message) {
    String code = code(message);
    String reason;
    switch (code) {
    case "ELEMENT_READ_ONLY":
      reason = "元素只读，请使用日期选择器或页面提供的控件";
      break;
    case "ELEMENT_DISABLED":
      reason = "元素已禁用";
      break;
    case "ELEMENT_OBSCURED":
      reason = "元素被遮挡，无法接收点击";
      break;
    case "ELEMENT_HIDDEN":
      reason = "元素当前不可见";
      break;
    case "ELEMENT_NOT_EDITABLE":
      reason = "元素不可编辑";
      break;
    case "STALE_ELEMENT":
      reason = "元素已脱离页面或索引失效，请重新获取页面状态";
      break;
    case "RATE_LIMITED":
      reason = "站点侧限流（例如「不允许重复提交，请稍候再试」）：等一会儿再试即可，不要连续重发";
      break;
    case "ACTION_TIMEOUT":
      reason = "等待元素可操作超时；不能据此确定元素不存在或快照过期";
      break;
    case ELEMENT_NOT_FOUND:
      reason = "选择器在页面里**一个都没匹配到**（不是超时、也不是被遮挡）："
          + "先 get_element_count 复核数量，再检查选择器里的祖先/兄弟关系是不是写错了 —— "
          + "input 常常不是那个包装元素的子节点，而是它的兄弟";
      break;
    case PAGE_NAVIGATING:
      reason = "页面正在导航/重载，这一刻拿不到 DOM（主 frame 还是 null）："
          + "等它稳定下来再重发即可；只读命令服务端已经自己等过，动作类命令请先读页面状态确认没有生效";
      break;
    case ACTION_UNCERTAIN:
      reason = "执行器抛了未预期异常，**无法判断动作是否已经生效**；"
          + "直接重试可能造成重复下载 / 重复提交，请先用只读命令确认页面状态";
      break;
    case SPURIOUS_DISPATCH:
      reason = "底层对象已被释放，而这个异常是 Playwright 的事件分发投递过来的"
          + "（`response@` / `request@` 与本次命令往往毫不相干）：只读命令服务端已自动重试，"
          + "动作类命令则无法判断是否已经生效，请先用只读命令确认页面状态";
      break;
    default:
      reason = PlaywrightService.briefMessage(message);
    }
    return action + " 失败：[" + code + "] " + reason;
  }
}
