package com.litongjava.ai.brower.service;

import java.io.File;

import org.junit.Test;

import com.litongjava.claude.ClaudeModels;
import com.litongjava.consts.ModelPlatformName;
import com.litongjava.exception.GenerateException;
import com.litongjava.jfinal.aop.Aop;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.tio.utils.hutool.FileUtil;
import com.litongjava.tio.utils.json.JsonUtils;

public class PageExtractStructuredDataServiceTest {

  @Test
  public void test() {
    EnvUtils.load();
    String md = FileUtil.readString(new File("data/tabo_index.md"));
    String query = "Extraction goal: Extract the details and link of the first product results";

    try {
      //Input length 188799 exceeds the maximum length 131072
      //String data = Aop.get(PageExtractStructuredDataService.class).extract(ModelPlatformName.VOLC_ENGINE, VolcEngineModels.DEEPSEEK_V3_250324, md, query);

      String data = Aop.get(PageExtractStructuredDataService.class).extract(
          //
          ModelPlatformName.ANTHROPIC, ClaudeModels.CLAUDE_SONNET_4_20250514, md, query);
      System.out.println(data);
    } catch (GenerateException e) {
      e.printStackTrace();
      String json = JsonUtils.toJson(e);
      FileUtil.writeString(json, "error.json");
    }

  }
}
