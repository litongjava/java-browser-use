package nexus.io.ai.browser.upload;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.handler.CommandTraceLog;
import nexus.io.model.body.RespBodyVo;
import nexus.io.model.upload.UploadFile;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpMethod;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.server.handler.HttpRequestHandler;
import nexus.io.tio.http.server.util.CORSUtils;

/**
 * 文件暂存端点:把客户端的文件送到**服务端**的 upload 目录
 *
 * <p>
 * 为什么需要这个端点:{@code upload_file} 的 {@code path} 是**服务端**的路径,而智能体通常跑在
 * 另一台机器上。客户端先把文件 POST 上来,拿到回执里的 {@code path}(或 {@code relativePath}),
 * 再原样交给 {@code upload_file} 即可。
 *
 * <pre>
 * POST /playwright/upload?filename=图样.jpg          请求体是文件字节(Content-Type 任意,推荐 application/octet-stream)
 * POST /playwright/upload                            multipart/form-data,文件字段默认叫 file(curl -F "file=@图样.jpg")
 * POST /playwright/upload                            application/json:{"filename":"图样.jpg","contentBase64":"..."}
 *                                                       或 {"filename":"a.txt","content":"文本内容"}
 * GET  /playwright/upload                            列出暂存目录里的文件
 * DELETE /playwright/upload?name=图样.jpg            删除暂存目录里的一个文件
 * </pre>
 *
 * <p>
 * 回执统一是 {@code {"data":{...},"code":1,"ok":true}}:{@code path} 是服务端绝对路径(直接喂给
 * {@code upload_file}),{@code relativePath} 是相对暂存目录的名字(也可以直接喂给 {@code upload_file},
 * 服务端会按暂存目录解析),{@code sha256} 让客户端核对收到的就是自己发的那份。
 *
 * <p>
 * 这个端点与浏览器命令无关,但同样会留一条痕迹到 {@code logs/trace/&lt;日期&gt;/uploads.log},
 * 便于「我到底传没传上去」这类排查。
 */
@Slf4j
public class UploadHandler implements HttpRequestHandler {

  /** multipart 表单里文件字段的默认名字 */
  private static final String DEFAULT_FIELD = "file";

  @Override
  public HttpResponse handle(HttpRequest request) throws Exception {
    HttpResponse response = TioRequestContext.getResponse();
    CORSUtils.enableCORS(response);
    HttpMethod method = request.getMethod();
    if (method == HttpMethod.GET) {
      response.body(list());
      return response;
    }
    if (method == HttpMethod.DELETE) {
      response.body(remove(request));
      return response;
    }
    if (method != HttpMethod.POST) {
      response.body(RespBodyVo.fail("上传接口只支持 POST(上传)、GET(列表)、DELETE(删除)"));
      return response;
    }
    response.body(store(request));
    return response;
  }

  /** 上传:multipart / JSON / 裸字节三种写法都认 */
  private RespBodyVo store(HttpRequest request) {
    if (!UploadStore.enabled()) {
      return RespBodyVo.fail("上传接口已关闭(" + UploadStore.KEY_ENABLED + "=false)");
    }
    String contentType = request.getContentType();
    try {
      Kv saved;
      if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
        saved = fromMultipart(request);
      } else if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
        saved = fromJson(request);
      } else {
        saved = fromRawBody(request, contentType);
      }
      CommandTraceLog.recordUpload(saved);
      return RespBodyVo.ok(saved);
    } catch (IllegalArgumentException e) {
      return RespBodyVo.fail("上传失败：" + e.getMessage());
    } catch (IOException e) {
      return RespBodyVo.fail("上传失败：" + e.getMessage());
    } catch (RuntimeException e) {
      log.warn("上传处理异常:{}", e.toString());
      return RespBodyVo.fail("上传失败：" + e.getMessage());
    }
  }

  private Kv fromMultipart(HttpRequest request) throws IOException {
    String field = firstNonBlank(request.getParam("field"), DEFAULT_FIELD);
    UploadFile upload = request.getUploadFile(field);
    if (upload == null) {
      // 字段名不是默认值时,退一步找任何一个上传文件:客户端不一定知道这里默认叫 file
      upload = anyUpload(request);
    }
    if (upload == null) {
      throw new IllegalArgumentException("multipart 表单里没有文件字段(默认字段名 " + DEFAULT_FIELD + ")");
    }
    String name = firstNonBlank(request.getParam("filename"), upload.getName());
    return UploadStore.save(name, upload.getContentType(), upload.getData());
  }

  private static UploadFile anyUpload(HttpRequest request) {
    java.util.Map<String, Object> params = request.getParam();
    if (params == null) {
      return null;
    }
    for (Object value : params.values()) {
      if (value instanceof UploadFile) {
        return (UploadFile) value;
      }
      if (value instanceof Object[]) {
        for (Object item : (Object[]) value) {
          if (item instanceof UploadFile) {
            return (UploadFile) item;
          }
        }
      }
    }
    return null;
  }

  private Kv fromJson(HttpRequest request) throws IOException {
    String body = request.getBodyString();
    JSONObject payload;
    try {
      payload = JSONObject.parseObject(body);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("JSON 请求体不合法,需要 {\"filename\":\"...\",\"contentBase64\":\"...\"}");
    }
    if (payload == null) {
      throw new IllegalArgumentException("JSON 请求体不合法,需要 {\"filename\":\"...\",\"contentBase64\":\"...\"}");
    }
    String name = payload.getString("filename");
    String base64 = payload.getString("contentBase64");
    if (base64 != null) {
      byte[] data;
      try {
        data = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("contentBase64 不是合法的 Base64");
      }
      return UploadStore.save(name, payload.getString("contentType"), data);
    }
    String content = payload.getString("content");
    if (content != null) {
      return UploadStore.save(name == null ? "upload.txt" : name, payload.getString("contentType"),
          content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    throw new IllegalArgumentException("JSON 请求体需要 contentBase64 或 content");
  }

  private Kv fromRawBody(HttpRequest request, String contentType) throws IOException {
    byte[] data = request.getBodyBytes();
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("请求体为空:裸字节上传请把文件内容放在请求体里,并用 ?filename= 指定文件名");
    }
    String name = firstNonBlank(request.getParam("filename"), request.getParam("name"),
        request.getHeader("X-Filename"));
    return UploadStore.save(name, contentType, data);
  }

  private RespBodyVo list() {
    if (!UploadStore.enabled()) {
      return RespBodyVo.fail("上传接口已关闭(" + UploadStore.KEY_ENABLED + "=false)");
    }
    List<Kv> files = UploadStore.list();
    return RespBodyVo.ok(Kv.by("dir", UploadStore.dir().toString()).set("count", files.size()).set("files", files)
        .set("maxBytes", UploadStore.maxBytes()).set("enabled", true).set("overwrite", UploadStore.overwrite()));
  }

  private RespBodyVo remove(HttpRequest request) {
    if (!UploadStore.enabled()) {
      return RespBodyVo.fail("上传接口已关闭(" + UploadStore.KEY_ENABLED + "=false)");
    }
    String name = firstNonBlank(request.getParam("name"), request.getParam("filename"));
    if (name == null) {
      return RespBodyVo.fail("删除需要 ?name= 指定暂存目录里的文件名");
    }
    boolean deleted = UploadStore.delete(name);
    if (!deleted) {
      return RespBodyVo.fail("删除失败:暂存目录里没有这个文件,或名字非法(" + name + ")");
    }
    return RespBodyVo.ok(Kv.by("name", name).set("deleted", true).set("dir", UploadStore.dir().toString()));
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
