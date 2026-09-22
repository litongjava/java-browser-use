package nexus.io.ai.browser.util;

import java.io.File;

import org.junit.Assume;
import org.junit.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import nexus.io.tio.utils.hutool.FileUtil;
import nexus.io.tio.utils.json.FastJson2Utils;

public class Request2ParseTest {

  @Test
  public void test() {
    File file = new File("data/02_request.json");
    // 样例数据不在仓库里,缺失时跳过,避免整个构建失败
    Assume.assumeTrue("缺少样例文件 " + file.getPath() + ",跳过该测试", file.exists());
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
