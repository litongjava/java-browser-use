package com.litongjava.bowser.use;

import java.io.File;

import org.junit.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.litongjava.tio.utils.hutool.FileUtil;
import com.litongjava.tio.utils.json.FastJson2Utils;

public class Request2ParseTest {

  @Test
  public void test() {
    File file = new File("data/02_request.json");
    String jsonString = FileUtil.readString(file);
    JSONObject jsonObject = FastJson2Utils.parseObject(jsonString);
//    JSONArray jsonArray = jsonObject.getJSONArray("messages");
//    for (int i = 0; i < jsonArray.size(); i++) {
//      JSONObject message = jsonArray.getJSONObject(i);
//      String role = message.getString("role");
//      String content = message.getString("content");
//      System.out.println(role + "\t" + content);
//    }
    
    JSONArray tools = jsonObject.getJSONArray("tools");
  for (int i = 0; i < tools.size(); i++) {
    JSONObject tool = tools.getJSONObject(i);
    System.out.println(tool.toJSONString());
  }
    

  }

}
