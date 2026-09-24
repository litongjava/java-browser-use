package nexus.io.ai.browser.handler;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ActionService;
import nexus.io.ai.browser.service.PlaywrightService;
import nexus.io.jfinal.aop.Aop;
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

  /**
   * 当前活着的任务与共享浏览器
   *
   * <p>
   * 与 {@code POST /playwright/command} 里的 {@code list_tasks} 同一份数据,单独开一个 GET 是为了
   * 让运维侧(脚本、监控、浏览器直接打开)不用构造 POST 请求体就能看到「现在有哪些任务、浏览器还活着没」。
   */
  public HttpResponse tasks(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();
    response.body(Aop.get(PlaywrightService.class).listTasks());
    return response;
  }

  /** 命令清单:直接 GET 就能看到服务端支持哪些方法,不必先猜一个方法名再被拒 */
  public HttpResponse methods(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();
    java.util.List<String> names = new java.util.ArrayList<>(nexus.io.ai.browser.actions.registry.CommandTable.names());
    response.body(RespBodyVo.ok(Kv.by("count", names.size()).set("methods", names)));
    return response;
  }

  /** 服务端生效配置(引擎、profile 目录、降级开关、日志目录等) */
  public HttpResponse config(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();
    response.body(Aop.get(PlaywrightService.class).getConfig(null));
    return response;
  }

  /** 仅供测试与排障:取 ActionService(避免 handler 里散落 Aop 调用) */
  static ActionService service() {
    return Aop.get(ActionService.class);
  }
}