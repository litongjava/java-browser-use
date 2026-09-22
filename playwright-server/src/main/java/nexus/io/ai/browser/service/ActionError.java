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
    if (text.contains("timeout") || text.contains("超时"))
      return "ACTION_TIMEOUT";
    return "ACTION_FAILED";
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
    case "ACTION_TIMEOUT":
      reason = "等待元素可操作超时；不能据此确定元素不存在或快照过期";
      break;
    default:
      reason = PlaywrightService.briefMessage(message);
    }
    return action + " 失败：[" + code + "] " + reason;
  }
}
