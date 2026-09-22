package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import org.junit.Test;

public class ActionErrorTest {
  @Test public void timeoutCallLogPreservesActualCause() {
    String prefix = "Timeout 5000ms exceeded.\nCall log:\n";
    assertEquals("ELEMENT_NOT_EDITABLE", ActionError.code(prefix + "element is not editable"));
    assertEquals("ELEMENT_OBSCURED", ActionError.code(prefix + "<div class=overlay> intercepts pointer events"));
    assertEquals("ELEMENT_HIDDEN", ActionError.code(prefix + "element is not visible"));
    assertEquals("ELEMENT_DISABLED", ActionError.code(prefix + "element is not enabled"));
    assertEquals("STALE_ELEMENT", ActionError.code(prefix + "element is not attached to the DOM"));
    assertEquals("ACTION_TIMEOUT", ActionError.code(prefix + "waiting for locator('#missing')"));
    assertFalse(ActionError.describe("click", prefix).contains("请重新"));
  }
}
