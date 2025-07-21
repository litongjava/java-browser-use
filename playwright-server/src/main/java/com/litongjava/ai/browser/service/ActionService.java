package com.litongjava.ai.browser.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.litongjava.ai.browser.actions.model.ClickElementByIndexParams;
import com.litongjava.ai.browser.actions.model.CloseParams;
import com.litongjava.ai.browser.actions.model.CloseTabParams;
import com.litongjava.ai.browser.actions.model.ExtractStructuredDataParams;
import com.litongjava.ai.browser.actions.model.GetDropdownOptionsParams;
import com.litongjava.ai.browser.actions.model.GoBackParams;
import com.litongjava.ai.browser.actions.model.GoToUrlParams;
import com.litongjava.ai.browser.actions.model.InputTextParams;
import com.litongjava.ai.browser.actions.model.NavigateParams;
import com.litongjava.ai.browser.actions.model.ScrollParams;
import com.litongjava.ai.browser.actions.model.ScrollToTextParams;
import com.litongjava.ai.browser.actions.model.SelectDropdownOptionParams;
import com.litongjava.ai.browser.actions.model.SendKeysParams;
import com.litongjava.ai.browser.actions.model.SwitchTabParams;
import com.litongjava.ai.browser.actions.model.UploadFileParams;
import com.litongjava.ai.browser.actions.model.WaitParams;
import com.litongjava.ai.browser.actions.registry.CommandHandler;
import com.litongjava.ai.browser.actions.registry.HandlerRegistry;
import com.litongjava.model.body.RespBodyVo;

public class ActionService {

  private final HandlerRegistry registry = new HandlerRegistry();

  private Class<?> resolveParamClass(String cmdName) {
    switch (cmdName) {
    case "go_to_url":
      return GoToUrlParams.class;
    case "navigate":
      return NavigateParams.class;
    case "go_back":
      return GoBackParams.class;
    case "wait":
      return WaitParams.class;
    case "click_element_by_index":
      return ClickElementByIndexParams.class;
    case "input_text":
      return InputTextParams.class;
    case "upload_file":
      return UploadFileParams.class;
    case "switch_tab":
      return SwitchTabParams.class;
    case "close_tab":
      return CloseTabParams.class;
    case "extract_structured_data":
      return ExtractStructuredDataParams.class;
    case "scroll":
      return ScrollParams.class;
    case "send_keys":
      return SendKeysParams.class;
    case "scroll_to_text":
      return ScrollToTextParams.class;
    case "get_dropdown_options":
      return GetDropdownOptionsParams.class;
    case "select_dropdown_option":
      return SelectDropdownOptionParams.class;
    case "close":
      return CloseParams.class;
    default:
      throw new IllegalArgumentException("未知命令：" + cmdName);
    }
  }

  public RespBodyVo batchExecute(Long id, String bodyJson) {
    try {
      JSONArray list = JSON.parseArray(bodyJson);
      for (int i = 0; i < list.size(); i++) {
        JSONObject entry = list.getJSONObject(i);
        if (entry.size() != 1) {
          return RespBodyVo.fail("每条命令对象只能包含一个键，第 " + i + " 项非法");
        }
        String cmdName = entry.keySet().iterator().next();
        JSONObject argsObj = entry.getJSONObject(cmdName);

        CommandHandler<?> handler = registry.get(cmdName);
        if (handler == null) {
          return RespBodyVo.fail("不支持的命令：" + cmdName);
        }

        Class<?> paramClass = resolveParamClass(cmdName);
        Object params = argsObj.toJavaObject(paramClass);

        @SuppressWarnings("unchecked")
        CommandHandler<Object> h = (CommandHandler<Object>) handler;
        RespBodyVo result = h.handle(id, params);
        if (!result.isOk()) {
          return result;
        }
      }
      return RespBodyVo.ok();
    } catch (Exception e) {
      return RespBodyVo.fail("解析或执行命令出错：" + e.getMessage());
    }
  }

}
