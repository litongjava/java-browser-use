package nexus.io.ai.browser.config;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.actions.registry.CommandTable;
import nexus.io.ai.browser.actions.registry.RecipeStore;
import nexus.io.ai.browser.handler.PlaywrightHandler;
import nexus.io.ai.browser.handler.PlaywrightHealthHandler;
import nexus.io.ai.browser.service.ChromeBrowser;
import nexus.io.ai.browser.upload.UploadHandler;
import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.context.BootConfiguration;
import nexus.io.tio.boot.http.handler.common.HttpFileDataHandler;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.http.server.router.HttpRequestRouter;

@Slf4j
public class PlaywrightAppConfig implements BootConfiguration {

  @Override
  public void config() {
    TioBootServer me = TioBootServer.me();
    HttpRequestRouter r = me.getRequestRouter();
    if (r != null) {
      // 截图与可交互结构化文本:data/<id>/<seq>.png|.txt
      r.add("/data/**", new HttpFileDataHandler(false));
      PlaywrightHealthHandler health = new PlaywrightHealthHandler();
      r.add("/playwright/health", health::ping);
      // 运维自省:活着的任务、命令清单、生效配置 —— 都是 GET,方便脚本与浏览器直接打开
      r.add("/playwright/tasks", health::tasks);
      r.add("/playwright/methods", health::methods);
      r.add("/playwright/config", health::config);
      // 唯一的浏览器控制端点:POST {id, method, params}
      r.add("/playwright/command", new PlaywrightHandler());
      // 文件暂存:客户端-服务器模式下,客户端把文件传到服务端的 upload 目录,
      // 再把返回的 path/relativePath 交给 upload_file(见 UploadStore)
      r.add("/playwright/upload", new UploadHandler());
      logStartupSummary();
    }
  }

  /**
   * 启动时把「这次跑起来用的是什么」打进日志
   *
   * <p>
   * 这几行是排障时最先要看的:引擎、解析后的 profile 目录(多实例时最容易在这里踩坑)、上传与日志目录、
   * 命令数与配方数。以前这些只能靠翻配置文件加猜,尤其「profile 到底落在哪」全靠日志反推。
   */
  private static void logStartupSummary() {
    try {
      log.info("playwright-server 启动配置: 引擎={} 类型={} profileDir={} (按端口派生={}) 上传目录={} 追踪目录={} 命令数={} 配方数={}",
          nexus.io.ai.browser.service.BrowserEngine.current().id(),
          nexus.io.ai.browser.service.BrowserChoice.configured().id(), ChromeBrowser.managedProfileDir(),
          ChromeBrowser.perPortProfileDir(), UploadStore.dir(),
          nexus.io.ai.browser.handler.CommandTraceLog.currentDir(), CommandTable.names().size(),
          RecipeStore.list().size());
    } catch (RuntimeException e) {
      // 启动日志打不出来不该拦住服务
      log.warn("打印启动配置失败:{}", e.getMessage());
    }
  }
}

