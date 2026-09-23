package nexus.io.ai.browser.handler;

import java.util.Set;

import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ActionService;
import nexus.io.ai.browser.service.PlaywrightService;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpMethod;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.server.handler.HttpRequestHandler;
import nexus.io.tio.http.server.util.CORSUtils;

/**
 * 唯一的浏览器控制端点
 *
 * <p>所有操作都走一个 POST,请求体形如:
 *
 * <pre>
 * { "id": 1234567890,
 *   "method": "start",
 *   "params": { "headless": false } }
 * </pre>
 *
 * <ul>
 * <li>{@code id}:任务 ID。{@code start} 时可以传(作为任务标识)也可以不传(自动生成雪花 ID);
 * 其余方法必须传,用来定位这个任务的浏览器实例。</li>
 * <li>{@code method}:命令名,与 {@code commands} 批量里的键、与响应里的错误提示完全一致。</li>
 * <li>{@code params}:该命令的参数,可以省略。</li>
 * </ul>
 *
 * <p>响应统一是 {@code {"data":{},"code":1,"ok":true,"error":null,"msg":null}}。
 *
 * <p>「会改变页面」的命令执行成功后,共享执行层会自动给当前页面截一张图,落到
 * {@code data/<id>/<seq>.png}(序号从 1 开始递增),并在响应的 data 里回填
 * {@code seq} / {@code screenshot}(可直接 GET 的 URL)/ {@code screenshot_path}。
 * 截图列表就是这次任务的页面变化历史,视觉模型可以直接按 URL 取图。
 *
 * <p>每一次调用(请求体 + 响应体)都会另外留一份审计日志到 {@code logs/trace/<日期>/} 下,见
 * {@link CommandTraceLog}:{@code steps.log} 是给人看的时间线,{@code calls.jsonl} 供程序过滤,
 * 每次调用还有一个完整报文的 {@code .json}。排查「第几步开始不对」时先看这份日志。
 */
@Slf4j
public class PlaywrightHandler implements HttpRequestHandler {

  /**
   * 会改变页面的命令:执行成功后自动截图
   *
   * <p>这里列的是「动作类」命令;纯读取类(get_url、get_cookies、is_visible……)不截图,否则
   * 每读一个值就多一张一模一样的图。{@code get_browser_state} 不在集合里 —— 它自己会截图并
   * 额外落一份同名的可交互结构化文本。
   */
  public static final Set<String> PAGE_CHANGING = ActionService.PAGE_CHANGING;

  @Override
  public HttpResponse handle(HttpRequest request) throws Exception {
    long startedAt = System.currentTimeMillis();
    HttpResponse response = TioRequestContext.getResponse();
    CORSUtils.enableCORS(response);
    String body = request.getBodyString();
    RespBodyVo result = ResponseFormatter.format(dispatch(request), body);
    // 每一次「请求 → 响应」都落一份到 logs/trace/<日期>/ 下(见 CommandTraceLog)。
    // 它自己吞掉所有异常:磁盘满、目录没权限都不会让浏览器命令失败。
    CommandTraceLog.record(body, result, startedAt);
    response.body(result);
    return response;
  }

  private RespBodyVo dispatch(HttpRequest request) {
    if (request.getMethod() != HttpMethod.POST) {
      return RespBodyVo.fail("浏览器控制接口只支持 POST,请把 {id, method, params} 放在 JSON 请求体里");
    }
    String body = request.getBodyString();
    if (body == null || body.isBlank()) {
      return RespBodyVo.fail("请求体不能为空,需要 {\"id\":123,\"method\":\"start\",\"params\":{}}");
    }
    JSONObject payload;
    try {
      payload = JSONObject.parseObject(body);
    } catch (Exception e) {
      return RespBodyVo.fail("请求体不是合法 JSON：" + PlaywrightService.briefMessage(e.getMessage()));
    }
    if (payload == null) {
      return RespBodyVo.fail("请求体不是合法 JSON 对象");
    }
    String method = payload.getString("method");
    if (method == null || method.isBlank()) {
      return RespBodyVo.fail("缺少参数 method");
    }
    Long id;
    try {
      id = payload.getLong("id");
    } catch (Exception e) {
      return RespBodyVo.fail("id 必须是数字或数字字符串");
    }
    JSONObject params = payload.getJSONObject("params");

    long startedAt = System.currentTimeMillis();
    RespBodyVo result = service().execute(id, method, params);
    if (log.isDebugEnabled()) {
      log.debug("id:{} method:{} ok:{} cost:{}ms", id, method, result.isOk(), System.currentTimeMillis() - startedAt);
    }
    return result;
  }

  /** Aop 容器在配置阶段可能还没初始化,所以延迟到请求时再取 */
  private static ActionService service() {
    return Aop.get(ActionService.class);
  }

}
