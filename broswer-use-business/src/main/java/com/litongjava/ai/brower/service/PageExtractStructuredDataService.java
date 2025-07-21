package com.litongjava.ai.brower.service;

import java.util.ArrayList;
import java.util.List;

import com.litongjava.chat.UniChatClient;
import com.litongjava.chat.UniChatMessage;
import com.litongjava.chat.UniChatRequest;
import com.litongjava.chat.UniChatResponse;
import com.litongjava.tio.utils.hutool.FileUtil;
import com.litongjava.tio.utils.hutool.ResourceUtil;

public class PageExtractStructuredDataService {
  public String extract(String platform, String model, String content, String query) {
    String systemPrompt = FileUtil.readString(ResourceUtil.getResource("prompts/extract_structured_data.md"));

    List<UniChatMessage> messages = new ArrayList<>();
    messages.add(UniChatMessage.buildUser("Extraction goal: " + query));
    messages.add(UniChatMessage.buildUser("Page:" + content));

    UniChatRequest uniChatRequest = new UniChatRequest(platform, model, systemPrompt.toString(), messages, 0.0f);
    UniChatResponse uniChatResponse = UniChatClient.generate(uniChatRequest);
    return uniChatResponse.getMessage().getContent();
  }
}
