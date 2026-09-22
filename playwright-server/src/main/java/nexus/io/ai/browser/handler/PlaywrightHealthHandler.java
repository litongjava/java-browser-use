package nexus.io.ai.browser.handler;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.common.utils.HttpIpUtils;

@Slf4j
public class PlaywrightHealthHandler {
  public HttpResponse ping(HttpRequest request) {
    log.info("ping from :{}", HttpIpUtils.getRealIp(request));
    HttpResponse response = TioRequestContext.getResponse();
    Kv kv = Kv.by("name", "playwright-server");
    response.body(RespBodyVo.ok(kv));
    return response;
  }
}