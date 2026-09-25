package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;

import nexus.io.model.body.RespBodyVo;

/**
 * 「事件泵伪故障」的识别与重发策略
 *
 * <p>
 * 起因是一次真实任务:阿里云控制台上 {@code execute_js} / {@code get_form_state} / {@code get_element_box} /
 * {@code go_to_url} / 自动截图会**成串**报 {@code Object doesn't exist: response@…},而页面完全正常。
 * 根因在 Playwright Java 的上下文事件分发(详见 {@link ActionError#SPURIOUS_DISPATCH}),服务端能做的是
 * 「对重发无害的命令自己重发,其余如实报告」。
 *
 * <p>
 * 这里全部用假的 supplier,不碰浏览器与 Playwright 驱动,所以既快又稳:钉住的是**策略**,
 * 而不是某次网络时序。
 */
public class SpuriousDispatchRetryTest {

  /** 实测原文(execute_js 失败时的 msg 形态) */
  private static final String SPURIOUS_JS =
      "执行 JavaScript 失败：Object doesn't exist: response@537bc1345bba374ca3c570f9e55ec00e";

  private static final String SPURIOUS_REQUEST = "wait_for_idle 失败：Object doesn't exist: request@1eea1f7bac703f569f5300f64b5172b1";

  @Test
  public void spuriousFamilyIsRecognized() {
    assertTrue(ActionError.isSpuriousDispatch(SPURIOUS_JS));
    assertTrue(ActionError.isSpuriousDispatch(SPURIOUS_REQUEST));
    assertTrue(ActionError.isSpuriousDispatch("Cannot find object to call requestFinished: response@1"));
    assertFalse("普通元素错误不是伪故障", ActionError.isSpuriousDispatch("element is not visible"));
    assertFalse(ActionError.isSpuriousDispatch(null));
  }

  @Test
  public void spuriousFamilyGetsItsOwnErrorCode() {
    assertEquals(ActionError.SPURIOUS_DISPATCH, ActionError.code(SPURIOUS_JS));
    // 其余分类不受影响
    assertEquals("ELEMENT_HIDDEN", ActionError.code("element is not visible"));
    assertEquals("ELEMENT_DISABLED", ActionError.code("element is not enabled"));
  }

  /** 只读命令:伪故障会被自动吃掉,回执里留下 spuriousRetry 便于统计噪声 */
  @Test
  public void readOnlyCommandIsRetriedUntilItSucceeds() {
    AtomicInteger calls = new AtomicInteger();
    RespBodyVo result = ActionService.dispatchWithSpuriousRetry("get_form_state", () -> {
      if (calls.incrementAndGet() < 3) {
        return RespBodyVo.fail(SPURIOUS_JS);
      }
      return RespBodyVo.ok(Kv.by("count", 7));
    });
    assertEquals("前两次是伪故障,第三次应当被采用", 3, calls.get());
    assertTrue(result.getMsg(), result.isOk());
    Kv data = (Kv) result.getData();
    assertEquals(7, data.getInt("count").intValue());
    Kv retry = (Kv) data.get("spuriousRetry");
    assertEquals("回执要标出这次是重发之后的", 3, retry.getInt("attempts").intValue());
    assertEquals(ActionError.SPURIOUS_DISPATCH, retry.getStr("errorCode"));
  }

  /** 一直失败就如实报:重发次数有上限,不能变成死循环 */
  @Test
  public void readOnlyCommandStopsAfterMaxAttempts() {
    AtomicInteger calls = new AtomicInteger();
    RespBodyVo result = ActionService.dispatchWithSpuriousRetry("get_element_box", () -> {
      calls.incrementAndGet();
      return RespBodyVo.fail(SPURIOUS_JS);
    });
    assertEquals("重发有上限:含首次最多 3 次", 3, calls.get());
    assertFalse("三次都失败就照旧返回失败", result.isOk());
  }

  /** 抛异常的那条路也一样:只读命令重发到上限后把异常抛给上层兜底 */
  @Test
  public void thrownSpuriousExceptionIsRetriedForReadOnlyCommand() {
    AtomicInteger calls = new AtomicInteger();
    try {
      ActionService.dispatchWithSpuriousRetry("get_page_snapshot", () -> {
        calls.incrementAndGet();
        throw new PlaywrightException(SPURIOUS_REQUEST);
      });
      fail("三次都抛异常时应当把异常抛出去");
    } catch (PlaywrightException expected) {
      assertEquals(3, calls.get());
    }
  }

  /** 动作类命令**绝不**自动重发:可能已经生效,重发等于重复提交 */
  @Test
  public void actionCommandIsNeverRetriedAutomatically() {
    for (String method : new String[] {"click_element_by_index", "click_element_by_selector", "input_text",
        "execute_js", "upload_file", "send_keys", "select_dropdown_option", "start", "close", "new_tab"}) {
      AtomicInteger calls = new AtomicInteger();
      RespBodyVo result = ActionService.dispatchWithSpuriousRetry(method, () -> {
        calls.incrementAndGet();
        return RespBodyVo.fail(SPURIOUS_JS);
      });
      assertEquals(method + " 不该被自动重发", 1, calls.get());
      assertFalse(result.isOk());
    }
  }

  /** 与伪故障无关的失败不重发:重发解决不了「元素就是不可见」 */
  @Test
  public void ordinaryFailureIsNotRetried() {
    AtomicInteger calls = new AtomicInteger();
    RespBodyVo result = ActionService.dispatchWithSpuriousRetry("get_element_text", () -> {
      calls.incrementAndGet();
      return RespBodyVo.fail("get_element_text 失败：element is not visible");
    });
    assertEquals(1, calls.get());
    assertFalse(result.isOk());
  }

  /**
   * `execute_js` 默认不重发,调用方显式声明「脚本重发无害」时才重发
   *
   * <p>
   * 实测里 execute_js 是伪故障的第一个受害者(读页面用的脚本成串报 response@ 不存在),但脚本可能有副作用
   * —— 悄悄重发一次等于再点一次提交。所以这条界线交给调用方:读页面就带 {@code retryOnSpurious},写操作别带。
   */
  @Test
  public void executeJsIsRetriedOnlyWhenCallerDeclaresItSafe() {
    AtomicInteger withoutFlag = new AtomicInteger();
    ActionService.dispatchWithSpuriousRetry("execute_js", new JSONObject(), () -> {
      withoutFlag.incrementAndGet();
      return RespBodyVo.fail(SPURIOUS_JS);
    });
    assertEquals("没声明就不能重发", 1, withoutFlag.get());
    assertFalse(ActionService.retrySafeFor("execute_js", null));
    assertFalse(ActionService.retrySafeFor("execute_js", new JSONObject()));

    JSONObject declared = new JSONObject();
    declared.put("retryOnSpurious", true);
    assertTrue(ActionService.retrySafeFor("execute_js", declared));

    AtomicInteger withFlag = new AtomicInteger();
    RespBodyVo result = ActionService.dispatchWithSpuriousRetry("execute_js", declared, () -> {
      if (withFlag.incrementAndGet() < 2) {
        return RespBodyVo.fail(SPURIOUS_JS);
      }
      return RespBodyVo.ok(Kv.by("result", "轻量应用服务器控制台"));
    });
    assertEquals(2, withFlag.get());
    assertTrue(result.getMsg(), result.isOk());
    assertEquals("轻量应用服务器控制台", ((Kv) result.getData()).getStr("result"));
  }

  /** 名单本身:只读/幂等/覆盖式落盘在里面,可能改状态的一律不在 */
  @Test
  public void retrySafeListOnlyContainsHarmlessCommands() {
    for (String method : new String[] {"get_browser_state", "get_form_state", "get_element_box", "diff_dom_text",
        "get_response_body", "screenshot", "get_element_screenshot", "go_to_url", "reload", "wait_for_idle",
        "wait_for_response", "set_viewport", "list_frames"}) {
      assertTrue(method + " 应当可以安全重发", ActionService.SPURIOUS_RETRY_SAFE.contains(method));
    }
    for (String method : new String[] {"click_element_by_index", "input_text", "execute_js", "upload_file",
        "send_keys", "select_dropdown_option", "start", "close", "new_tab", "close_tab", "set_cookie",
        "check_element_by_index", "scroll"}) {
      assertFalse(method + " 有副作用,不该进重发名单", ActionService.SPURIOUS_RETRY_SAFE.contains(method));
    }
  }
}
