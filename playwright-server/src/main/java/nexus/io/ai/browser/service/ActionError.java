package nexus.io.ai.browser.service;

import java.util.Locale;

/**
 * Classify the complete Playwright call log, not only the first timeout line.
 */
public final class ActionError {
  private ActionError() {
  }

  public static String code(String message) {
    String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
    if (text.contains("element_read_only") || text.contains("read-only") || text.contains("readonly"))
      return "ELEMENT_READ_ONLY";
    if (text.contains("element_disabled") || text.contains("not enabled") || text.contains("element is disabled"))
      return "ELEMENT_DISABLED";
    if (text.contains("intercepts pointer events"))
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
   */
  public static boolean retryable(String code) {
    switch (code) {
    case "RATE_LIMITED":
    case "ACTION_TIMEOUT":
    case "STALE_ELEMENT":
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
    default:
      reason = PlaywrightService.briefMessage(message);
    }
    return action + " 失败：[" + code + "] " + reason;
  }
}
