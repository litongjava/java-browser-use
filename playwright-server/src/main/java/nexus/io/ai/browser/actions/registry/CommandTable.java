package nexus.io.ai.browser.actions.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.service.PlaywrightService;
import nexus.io.model.body.RespBodyVo;

/**
 * 命令表:唯一的方法分发处
 *
 * <p>
 * 对外只有一个 HTTP 端点 {@code POST /playwright/command},请求体形如
 * {@code {"id":123,"method":"go_to_url","params":{"url":"https://example.com"}}}。
 * {@code method} 就是这张表的键,{@code params} 里的参数名与下面 Executor 里读的键完全一致。
 *
 * <p>
 * 批量调用 {@code commands} 里的命令名用的是同一套名字,所以模型在「单独调用」和「批量调用」 之间不需要切换心智模型。
 */
public class CommandTable {

  public interface Executor {
    RespBodyVo run(PlaywrightService svc, Long id, JSONObject args);
  }

  private static final Map<String, Executor> TABLE = new LinkedHashMap<>();

  static {
    // ---------- 实例生命周期 ----------
    put("start", (svc, id, a) -> {
      // 不传 headless 时按无头处理,避免在服务器上误弹出窗口
      Boolean headless = a.getBoolean("headless");
      // 不传 browser 时按配置里的默认类型(browser.type / browser.engine)处理,行为与以前一致;
      // 传了不认识的值会在 svc.start 里被拒掉,错误信息里带上可选值(见 BrowserType)
      String requested = a.getString("browser");
      long newId = svc.start(id, headless == null || headless, requested);
      Kv data = Kv.by("id", newId);
      // 这次任务实际用的浏览器与 profile:用的哪个浏览器、是不是本机 Chrome、有没有用上用户自己的 profile
      Kv browser = svc.browserInfo(newId);
      if (browser != null) {
        data.set("browser", browser);
        // 「我要的浏览器」与「实际用的浏览器」都要写清楚:老版本发布包会**静默忽略** browser 参数,
        // 回 ok:true 却用另一个浏览器打开页面,这种问题靠回执一眼看穿,不用再去翻日志
        String effective = browser.getStr("type");
        boolean honored = engineHonored(requested, effective);
        data.set("requestedBrowser", requested == null || requested.isBlank() ? null : requested)
            .set("effectiveBrowser", effective).set("engineHonored", honored);
        // engineHonored:true 只说明「参数被采纳了」,不说明「这份 profile 里有登录态」——这两件事必须分开说,
        // 否则换引擎之后「所有站点都退登录了」会完全没有线索(见技能手册里「换引擎等于换一套登录态」)
        if (browser.get("profileSeenBefore") != null) {
          data.set("profileSeenBefore", browser.get("profileSeenBefore"));
        }
        if (browser.getStr("profileNote") != null) {
          data.set("profileNote", browser.getStr("profileNote"));
        }
        if (!honored) {
          data.set("engineWarning", "请求的浏览器是 " + requested + ",实际用的是 " + effective
              + ":说明这个服务实例没有按 browser 参数切换浏览器(常见于旧版本发布包,它只认 headless)。"
              + "要按需切换浏览器请升级服务端,或先用 get_config 确认 engine/type");
        }
      }
      return RespBodyVo.ok(data);
    });
    put("close", (svc, id, a) -> svc.close(id));
    // 关掉所有任务与共享浏览器:比直接杀进程干净(不留占着 profile 的孤儿浏览器)
    put("shutdown", (svc, id, a) -> svc.shutdown());

    // ---------- 导航与页面信息 ----------
    put("navigate", (svc, id, a) -> svc.navigate(id, reqStr(a, "url")));
    put("go_to_url", (svc, id, a) -> svc.goToUrl(id, reqStr(a, "url")));
    put("go_back", (svc, id, a) -> svc.goBack(id));
    put("go_forward", (svc, id, a) -> svc.goForward(id));
    put("reload", (svc, id, a) -> svc.reload(id));
    put("get_url", (svc, id, a) -> svc.getUrl(id));
    put("get_title", (svc, id, a) -> svc.getTitle(id));
    put("get_browser_state",
        (svc, id, a) -> svc.getBrowserState(id, a.getBoolean("highlight"), a.getInteger("viewportExpansion"),
            a.getBoolean("includeElements"), a.getInteger("maxElements"), a.getBoolean("includeFrames")));
    // 页面上的 frame 清单(跨域 iframe 也能读到 URL):主站把第三方控制台套在 iframe 里时先看它
    put("list_frames", (svc, id, a) -> svc.listFrames(id, a.getBoolean("refresh")));
    put("wait", (svc, id, a) -> svc.waitSeconds(id, reqInt(a, "seconds")));

    // ---------- 元素交互(按索引) ----------
    // mode:auto(默认,原生失败先改真实鼠标、再降级 JS 派发)/ native / mouse / js;timeoutMs 按次覆盖超时
    put("click_element_by_index",
        (svc, id, a) -> svc.clickElementByIndex(id, reqInt(a, "index"), optStr(a, "mode"), a.getInteger("timeoutMs")));
    put("double_click_element_by_index", (svc, id, a) -> svc.doubleClickElementByIndex(id, reqInt(a, "index"),
        optStr(a, "mode")));
    put("hover_element_by_index", (svc, id, a) -> svc.hoverElementByIndex(id, reqInt(a, "index")));
    put("focus_element_by_index", (svc, id, a) -> svc.focusElementByIndex(id, reqInt(a, "index")));
    put("check_element_by_index",
        (svc, id, a) -> svc.checkElementByIndex(id, reqInt(a, "index"), optStr(a, "mode")));
    put("uncheck_element_by_index",
        (svc, id, a) -> svc.uncheckElementByIndex(id, reqInt(a, "index"), optStr(a, "mode")));
    // mode:auto(默认:看得见就用真实输入,看不见退回 JS 设值)/ native / type / js
    put("input_text",
        (svc, id, a) -> svc.inputTextByIndex(id, reqInt(a, "index"), reqStr(a, "text"), optStr(a, "mode")));
    put("type_text", (svc, id, a) -> svc.typeText(id, reqInt(a, "index"), reqStr(a, "text")));
    // index 与 selector 传一个即可:隐藏的 file input 没有索引,只能用 selector;
    // path 可以是服务端绝对路径,也可以是 /playwright/upload 返回的 relativePath(按服务端 upload 目录解析);
    // 也可以完全不走 upload 接口:直接给 contentBase64 或 url,服务端自己落盘再交给页面
    put("upload_file", (svc, id, a) -> {
      if (optStr(a, "contentBase64") != null || optStr(a, "url") != null) {
        return svc.uploadFileInline(id, a.getInteger("index"), optStr(a, "selector"), optStr(a, "filename"),
            optStr(a, "contentType"), optStr(a, "contentBase64"), optStr(a, "url"), a.getInteger("timeoutMs"),
            optStr(a, "frame"));
      }
      return svc.uploadFile(id, a.getInteger("index"), optStr(a, "selector"), reqStr(a, "path"),
          a.getInteger("timeoutMs"), optStr(a, "frame"));
    });
    put("drag_element_by_index",
        (svc, id, a) -> svc.dragElementByIndex(id, reqInt(a, "index"), reqInt(a, "targetIndex")));
    put("send_keys", (svc, id, a) -> svc.sendKeys(id, reqStr(a, "keys")));
    put("key_down", (svc, id, a) -> svc.keyDown(id, reqStr(a, "keys")));
    put("key_up", (svc, id, a) -> svc.keyUp(id, reqStr(a, "keys")));
    put("get_dropdown_options", (svc, id, a) -> svc.getDropdownOptions(id, reqInt(a, "index")));
    put("select_dropdown_option", (svc, id, a) -> svc.selectDropdownOption(id, reqInt(a, "index"), reqStr(a, "text")));
    put("scroll", (svc, id, a) -> svc.scroll(id, optBool(a, "down"), reqInt(a, "numPages"), a.getInteger("index")));
    put("scroll_to_text", (svc, id, a) -> svc.scrollToText(id, reqStr(a, "text")));

    // ---------- 读取元素信息与状态 ----------
    put("get_element_text", (svc, id, a) -> svc.getElementText(id, reqInt(a, "index")));
    put("get_element_html", (svc, id, a) -> svc.getElementHtml(id, reqInt(a, "index")));
    put("get_element_value", (svc, id, a) -> svc.getElementValue(id, reqInt(a, "index")));
    put("get_element_attribute", (svc, id, a) -> svc.getElementAttribute(id, reqInt(a, "index"), reqStr(a, "name")));
    // 这个元素到底挂了哪些事件监听器:SPA 自动化的基础诊断信息(全为 false 就是「这个 input 没人监听」)
    put("get_element_listeners", (svc, id, a) -> svc.getElementListeners(id, a.getInteger("index"),
        optStr(a, "selector"), optStr(a, "frame")));
    put("get_element_count", (svc, id, a) -> svc.getElementCount(id, reqStr(a, "selector"), optStr(a, "frame")));
    put("get_element_box", (svc, id, a) -> svc.getElementBox(id, reqInt(a, "index")));
    put("is_visible", (svc, id, a) -> svc.isVisible(id, reqInt(a, "index")));
    put("is_enabled", (svc, id, a) -> svc.isEnabled(id, reqInt(a, "index")));
    put("is_checked", (svc, id, a) -> svc.isChecked(id, reqInt(a, "index")));

    // ---------- 选择器与语义定位 ----------
    // frame:目标在跨域 iframe 里时必传(序号见 list_frames,或写 URL/name 子串);
    // 按索引的命令不需要它 —— 索引里已经带了 frame 信息,服务端自动路由
    put("click_element_by_selector",
        (svc, id, a) -> svc.clickElementBySelector(id, reqStr(a, "selector"), optStr(a, "mode"),
            a.getInteger("timeoutMs"), optStr(a, "frame")));
    put("input_text_by_selector", (svc, id, a) -> svc.inputTextBySelector(id, reqStr(a, "selector"),
        reqStr(a, "text"), optStr(a, "mode"), optStr(a, "frame")));
    put("click_element_by_text", (svc, id, a) -> svc.clickElementByText(id, reqStr(a, "text"), optStr(a, "mode")));
    put("click_element_by_role",
        (svc, id, a) -> svc.clickElementByRole(id, reqStr(a, "role"), optStr(a, "name"), optStr(a, "mode")));
    put("input_text_by_label",
        (svc, id, a) -> svc.inputTextByLabel(id, reqStr(a, "label"), reqStr(a, "text"), optStr(a, "mode")));
    put("clear_text", (svc, id, a) -> svc.clearText(id, a.getInteger("index"), optStr(a, "selector")));
    put("hover_and_click", (svc, id, a) -> svc.hoverAndClick(id, a.getInteger("index"), optStr(a, "selector"),
        a.getInteger("hoverDelayMs"), optStr(a, "mode")));

    // ---------- 标签页 ----------
    put("get_tabs", (svc, id, a) -> svc.getTabs(id));
    put("new_tab", (svc, id, a) -> svc.newTab(id, optStr(a, "url")));
    put("switch_tab", (svc, id, a) -> svc.switchTab(id, reqInt(a, "pageIndex")));
    put("switch_tab_by_url", (svc, id, a) -> svc.switchTabByUrl(id, reqStr(a, "url")));
    put("close_tab", (svc, id, a) -> svc.closeTab(id, reqInt(a, "pageIndex")));
    put("close_other_tabs", (svc, id, a) -> svc.closeOtherTabs(id, a.getInteger("pageIndex")));
    put("bring_to_front", (svc, id, a) -> svc.bringToFront(id, a.getInteger("pageIndex")));

    // ---------- 等待 ----------
    put("wait_for_element",
        (svc, id, a) -> svc.waitForElement(id, reqStr(a, "selector"), a.getDouble("timeoutSeconds"),
            optStr(a, "frame")));
    put("wait_for_text", (svc, id, a) -> svc.waitForText(id, reqStr(a, "text"), a.getDouble("timeoutSeconds")));
    put("wait_for_url", (svc, id, a) -> svc.waitForUrl(id, reqStr(a, "url"), a.getDouble("timeoutSeconds")));
    put("wait_for_load", (svc, id, a) -> svc.waitForLoad(id, optStr(a, "state"), a.getDouble("timeoutSeconds")));
    put("wait_for_function",
        (svc, id, a) -> svc.waitForFunction(id, reqStr(a, "expression"), a.getDouble("timeoutSeconds")));
    // 等页面安静下来:连续 quietMs 毫秒既没有 DOM 变更、也没有在途请求(比固定 wait 可靠,比手写
    // wait_for_function 省事);selector/text 是额外条件
    put("wait_for_idle", (svc, id, a) -> svc.waitForIdle(id, a.getInteger("quietMs"), a.getDouble("timeoutSeconds"),
        optStr(a, "selector"), optStr(a, "text")));
    // 等内容稳定:正文/表单值/表格内容连续 quietMs 毫秒不再变化。查/搜索结果是异步刷新的,
    // 点完「查询」立刻读会读到上一次的结果,先 wait_for_stable 再读就不会错位
    put("wait_for_stable", (svc, id, a) -> svc.waitForStable(id, optStr(a, "selector"), a.getInteger("quietMs"),
        a.getDouble("timeoutSeconds")));
    // 等选择器的命中数量达标:等弹窗全部消失用 max:0,等结果行出现用 min:1
    put("wait_for_count", (svc, id, a) -> svc.waitForCount(id, reqStr(a, "selector"), a.getInteger("min"),
        a.getInteger("max"), a.getInteger("equals"), a.getDouble("timeoutSeconds")));

    // ---------- 鼠标 ----------
    put("mouse_move", (svc, id, a) -> svc.mouseMove(id, reqDouble(a, "x"), reqDouble(a, "y")));
    put("mouse_down", (svc, id, a) -> svc.mouseDown(id, optStr(a, "button")));
    put("mouse_up", (svc, id, a) -> svc.mouseUp(id, optStr(a, "button")));
    put("mouse_wheel", (svc, id, a) -> svc.mouseWheel(id, reqDouble(a, "deltaY")));
    // 真实鼠标点击(一条顶原来的 move+down+up 三条):JS 派发无效的按钮、被遮挡但要点到的元素都用它
    put("mouse_click",
        (svc, id, a) -> svc.mouseClick(id, reqDouble(a, "x"), reqDouble(a, "y"), optStr(a, "button"),
            a.getInteger("clickCount")));
    put("mouse_click_by_selector",
        (svc, id, a) -> svc.mouseClickBySelector(id, reqStr(a, "selector"), optStr(a, "button"),
            a.getInteger("clickCount"), a.getInteger("timeoutMs")));

    // ---------- 截图与 PDF ----------
    // 默认落盘并只回路径/URL;要内联图片(会把整张图塞进上下文)才传 inline:true
    put("screenshot",
        (svc, id, a) -> svc.screenshot(id, optStr(a, "path"), a.getBoolean("fullPage"), a.getInteger("index"),
            optStr(a, "selector"), a.getDouble("clipX"), a.getDouble("clipY"), a.getDouble("clipWidth"),
            a.getDouble("clipHeight"), a.getBoolean("inline")));
    put("get_element_screenshot", (svc, id, a) -> svc.getElementScreenshot(id, a.getInteger("index"),
        optStr(a, "selector"), optStr(a, "path"), a.getBoolean("inline")));
    put("pdf", (svc, id, a) -> svc.pdf(id, optStr(a, "path")));

    // ---------- Cookie 与本地存储 ----------
    put("get_cookies", (svc, id, a) -> svc.getCookies(id, optStr(a, "url")));
    put("set_cookie", (svc, id, a) -> svc.setCookie(id, reqStr(a, "name"), reqStr(a, "value"), optStr(a, "url")));
    put("clear_cookies", (svc, id, a) -> svc.clearCookies(id));
    put("get_local_storage", (svc, id, a) -> svc.getLocalStorage(id, optStr(a, "key")));
    put("set_local_storage", (svc, id, a) -> svc.setLocalStorage(id, reqStr(a, "key"), optStr(a, "value")));
    put("clear_local_storage", (svc, id, a) -> svc.clearLocalStorage(id));

    // ---------- 浏览器设置 ----------
    put("set_viewport", (svc, id, a) -> svc.setViewport(id, reqInt(a, "width"), reqInt(a, "height")));
    put("set_geolocation", (svc, id, a) -> svc.setGeolocation(id, reqDouble(a, "latitude"), reqDouble(a, "longitude")));
    put("set_offline", (svc, id, a) -> svc.setOffline(id, optBool(a, "offline")));
    put("set_headers", (svc, id, a) -> svc.setHeaders(id, reqStr(a, "headersJson")));
    put("set_credentials", (svc, id, a) -> svc.setCredentials(id, reqStr(a, "username"), reqStr(a, "password")));
    put("set_media", (svc, id, a) -> svc.setMedia(id, reqStr(a, "colorScheme")));

    // ---------- 弹窗与控制台 ----------
    // 注意:下面两个是浏览器**原生**对话框(window.alert/confirm/prompt);页面里的 DOM 弹窗
    // (ant-design Modal、用户服务协议层)是 get_modals / close_modal,两者完全不同
    put("get_dialog", (svc, id, a) -> svc.getDialog(id, a.getBoolean("consume")));
    put("clear_dialog", (svc, id, a) -> svc.clearDialog(id));
    put("get_js_dialog", (svc, id, a) -> svc.getDialog(id, a.getBoolean("consume")));
    put("clear_js_dialog", (svc, id, a) -> svc.clearDialog(id));
    put("set_dialog_behavior", (svc, id, a) -> svc.setDialogBehavior(id, optBool(a, "dismiss")));
    // DOM 弹窗栈:标题、按钮、右上角 × 的坐标。被遮挡/点了没反应时先看它
    put("get_modals", (svc, id, a) -> svc.getModals(id));
    // 关掉弹窗:which=top(默认)/first/all,title 按标题匹配,button 指定点哪个按钮。
    // 一律用真实鼠标点,并且点完校验弹窗数量是否真的减少
    put("close_modal", (svc, id, a) -> svc.closeModal(id, optStr(a, "which"), optStr(a, "title"),
        optStr(a, "button")));
    put("get_console_logs", (svc, id, a) -> svc.getConsoleLogs(id));
    put("clear_console_logs", (svc, id, a) -> svc.clearConsoleLogs(id));

    // ---------- 网络 ----------
    put("route", (svc, id, a) -> svc.route(id, reqStr(a, "urlPattern"), optStr(a, "action"), optStr(a, "body"),
        a.getInteger("status"), optStr(a, "contentType")));
    put("unroute", (svc, id, a) -> svc.unroute(id, optStr(a, "urlPattern")));
    put("get_requests", (svc, id, a) -> svc.getRequests(id, optStr(a, "filter"), optStr(a, "resourceType"),
        a.getInteger("limit"), a.getLong("since")));
    put("wait_for_response", (svc, id, a) -> svc.waitForResponse(id, reqStr(a, "urlPattern"),
        a.getDouble("timeoutSeconds"), a.getInteger("maxChars"), a.getInteger("lookBackSeconds")));
    put("get_response_body", (svc, id, a) -> svc.getResponseBody(id, optStr(a, "filter"), a.getInteger("index"),
        a.getInteger("maxChars"), optStr(a, "requestId")));

    // ---------- 页面状态汇总与快照差异 ----------
    put("get_page_snapshot", (svc, id, a) -> svc.getPageSnapshot(id, a.getBoolean("includeConsole"),
        a.getBoolean("includeRequests"), optStr(a, "requestFilter")));
    put("diff_dom_text",
        (svc, id, a) -> svc.diffDomText(id, a.getBoolean("highlight"), a.getInteger("viewportExpansion")));
    put("get_interactive_map", (svc, id, a) -> svc.getInteractiveMap(id));
    // 一次读回整张表单:每个控件的标签/值/可见性/禁用/只读/校验错误 + 错误清单(密码字段不返回值)
    put("get_form_state", (svc, id, a) -> svc.getFormState(id, optStr(a, "selector"), a.getBoolean("includeHidden"),
        a.getInteger("max")));

    // ---------- 其它 ----------
    put("extract_structured_data",
        (svc, id, a) -> svc.extractStructuredData(id, optStr(a, "query"), optBool(a, "extractLinks")));
    // body 直接写脚本;或用 bodyFile 从服务端脚本目录读(见 get_config 的 jsDir),
    // 再用 vars 注入 {{变量}} —— 中文/引号/换行都不用在客户端拼字符串
    put("execute_js", (svc, id, a) -> {
      if (optStr(a, "body") == null && optStr(a, "bodyFile") == null) {
        // 两个都没给:报「缺少参数 body」,与老版本的行为一致(老版本只认 body)
        throw new IllegalArgumentException("缺少参数 body");
      }
      return svc.executeJs(id, a.getString("body"), optStr(a, "bodyFile"), a.getJSONObject("vars"),
          optStr(a, "frame"));
    });

    // ---------- 服务自省与配方 ----------
    // 命令清单:不知道方法名时先问它,别靠猜(猜错只会拿到一句「不支持的方法」)
    put("list_methods", (svc, id, a) -> {
      String filter = optStr(a, "filter");
      List<String> names = new java.util.ArrayList<>();
      for (String name : names()) {
        if (filter == null || name.contains(filter.toLowerCase(Locale.ROOT))) {
          names.add(name);
        }
      }
      Kv data = Kv.by("count", names.size()).set("methods", names).set("total", names().size());
      if (filter != null) {
        data.set("filter", filter);
      }
      return RespBodyVo.ok(data);
    });
    // 生效配置:引擎/类型、解析后的 profile 目录、降级开关、脚本与日志目录、任务数
    put("get_config", (svc, id, a) -> svc.getConfig(id));
    // 当前活着的任务一览(URL/标题/页签数/在途请求)+ 共享浏览器信息
    put("list_tasks", (svc, id, a) -> svc.listTasks());
    // 清理落盘产物:截图/结构化文本/追踪日志;默认只做预演(dryRun),不会真删
    put("cleanup", (svc, id, a) -> svc.cleanup(optStr(a, "scope"), a.getInteger("olderThanHours"),
        a.getInteger("keepLatest"), a.getBoolean("dryRun")));
    // 站点配方:list 看有哪些,run 跑一个(必须显式点名,不做任何隐式推断)
    put("list_recipes", (svc, id, a) -> {
      List<Kv> recipes = RecipeStore.list();
      return RespBodyVo.ok(Kv.by("count", recipes.size()).set("recipes", recipes)
          .set("dir", RecipeStore.dir().toString()));
    });
    put("run_recipe", (svc, id, a) -> runRecipe(id, reqStr(a, "name"), a.getJSONObject("vars"),
        a.getBoolean("stopOnError")));
    // 长批次(commands + async:true)的结果查询与取消
    put("get_job", (svc, id, a) -> {
      String jobId = reqStr(a, "jobId");
      nexus.io.ai.browser.service.JobRegistry.Job job = nexus.io.ai.browser.service.JobRegistry.get(jobId);
      if (job == null) {
        return RespBodyVo.fail("没有这个任务：" + jobId + "（任务只保留最近 50 个,且服务重启即丢;"
            + "用 list_jobs 看现存任务）");
      }
      boolean withResult = a.getBoolean("includeResult") == null || a.getBoolean("includeResult");
      Kv data = job.describe(withResult);
      if ("running".equals(job.status)) {
        data.set("hint", "还在跑:等一会儿再查,或用 waitForSeconds 做长轮询");
      }
      return RespBodyVo.ok(data);
    });
    put("cancel_job", (svc, id, a) -> {
      String jobId = reqStr(a, "jobId");
      nexus.io.ai.browser.service.JobRegistry.Job job = nexus.io.ai.browser.service.JobRegistry.get(jobId);
      if (job == null) {
        return RespBodyVo.fail("没有这个任务：" + jobId);
      }
      boolean accepted = nexus.io.ai.browser.service.JobRegistry.cancel(jobId);
      return RespBodyVo.ok(Kv.by("jobId", jobId).set("cancelRequested", accepted).set("status", job.status)
          .set("note", "取消是协作式的:批次会在下一步之前停下来,当前这一步不会被中断"));
    });
    put("list_jobs", (svc, id, a) -> {
      Integer limit = a.getInteger("limit");
      List<Kv> jobs = new java.util.ArrayList<>();
      for (nexus.io.ai.browser.service.JobRegistry.Job job : nexus.io.ai.browser.service.JobRegistry
          .recent(limit == null ? 20 : limit)) {
        jobs.add(job.describe(false));
      }
      return RespBodyVo.ok(Kv.by("count", jobs.size()).set("jobs", jobs));
    });

    // ---------- 人机协同 ----------
    // 一次请求可以带多个待办(steps):扫码 + 输码 + 支付确认是**一串**动作,人一次做完比来回多次往返省事
    put("request_human_input", (svc, id, a) -> {
      // prompt 与 steps 二选一:两个都没给时在**碰浏览器对象之前**就说清楚是缺哪个参数
      if (a.getJSONArray("steps") == null && optStrRaw(a, "prompt") == null) {
        throw new IllegalArgumentException("缺少参数 prompt");
      }
      return svc.requestHumanInput(id, optStrRaw(a, "prompt"), a.getInteger("index"), optStr(a, "selector"),
          a.getInteger("timeoutSeconds"), stepListOf(a.getJSONArray("steps")), a.getLong("expiresAt"),
          a.getBoolean("ocr"), optStr(a, "ocrLanguage"), a.getBoolean("inline"));
    });
    put("submit_human_input", (svc, id, a) -> svc.submitHumanInput(id, reqStr(a, "requestId"),
        optStrRaw(a, "answer"), optStr(a, "stepId"), a.getJSONObject("answers")));
    put("get_human_input",
        (svc, id, a) -> svc.getHumanInput(id, reqStr(a, "requestId"), a.getInteger("timeoutSeconds")));
    // 读图上的文字(Windows 自带 OCR):读不了图的模型也能答「验证码是什么」
    put("ocr_image", (svc, id, a) -> svc.ocrImage(id, optStr(a, "path"), a.getInteger("index"),
        optStr(a, "selector"), optStr(a, "frame"), optStr(a, "language")));
  }

  /** steps: [{prompt, index?, selector?}, ...] → List&lt;Kv&gt; */
  private static java.util.List<Kv> stepListOf(com.alibaba.fastjson2.JSONArray steps) {
    java.util.List<Kv> list = new java.util.ArrayList<>();
    if (steps == null) {
      return list;
    }
    for (int i = 0; i < steps.size(); i++) {
      JSONObject item = steps.getJSONObject(i);
      if (item == null) {
        continue;
      }
      Kv step = new Kv();
      step.putAll(item);
      list.add(step);
    }
    return list;
  }

  private CommandTable() {
  }

  private static void put(String command, Executor executor) {
    TABLE.put(command, executor);
  }

  /**
   * 跑一个站点配方
   *
   * <p>
   * 配方就是一段预先写好的命令序列(见 {@link RecipeStore}),这里把它当成一次 {@code commands} 批量
   * 执行:配方里的每一步都会走同一套命令分发,所以单步手跑和整段跑配方行为完全一致,排障时可以把
   * 配方里的命令一条条贴出来单独跑。
   *
   * <p>
   * 用 {@code Aop.get} 延迟取 {@code ActionService} 是为了避免与它的静态依赖成环(它反过来依赖本表)。
   */
  private static RespBodyVo runRecipe(Long id, String name, JSONObject vars, Boolean stopOnError) {
    Kv recipe = RecipeStore.load(name);
    if (recipe == null) {
      List<Kv> available = RecipeStore.list();
      StringBuilder hint = new StringBuilder();
      for (Kv item : available) {
        if (hint.length() > 0) {
          hint.append(" / ");
        }
        hint.append(item.getStr("name"));
      }
      return RespBodyVo.fail("没有这个配方：" + name + (hint.length() == 0
          ? "（配方目录里没有可用配方,见 get_config 的 recipesDir / list_recipes）"
          : "，可用配方：" + hint));
    }
    JSONArray commands = RecipeStore.applyVars((JSONArray) recipe.get("commands"),
        RecipeStore.mergeVars((JSONObject) recipe.get("defaults"), vars));
    JSONObject params = new JSONObject();
    params.put("commands", commands);
    params.put("stopOnError", stopOnError == null || stopOnError);
    RespBodyVo result = nexus.io.jfinal.aop.Aop.get(nexus.io.ai.browser.service.ActionService.class)
        .batchExecute(id, params);
    Object data = result.getData();
    Kv merged = data instanceof Kv ? (Kv) data : Kv.by("result", data);
    merged.set("recipe", recipe.getStr("name"));
    if (recipe.get("description") != null) {
      merged.set("recipeDescription", recipe.get("description"));
    }
    result.setData(merged);
    return result;
  }

  /**
   * 请求的浏览器与实际用的是不是同一个
   *
   * <p>
   * {@code auto}/{@code default}(以及没传)表示「服务自己挑」,拿哪个都算数;其余按
   * {@link BrowserChoice#parse} 归一化后比较({@code ff}/{@code gecko} 与 {@code firefox} 等价)。
   */
  static boolean engineHonored(String requested, String effective) {
    if (requested == null || requested.isBlank()) {
      return true;
    }
    nexus.io.ai.browser.service.BrowserChoice wanted = nexus.io.ai.browser.service.BrowserChoice.parse(requested);
    if (wanted == null || wanted == nexus.io.ai.browser.service.BrowserChoice.AUTO) {
      return true;
    }
    nexus.io.ai.browser.service.BrowserChoice actual = nexus.io.ai.browser.service.BrowserChoice.parse(effective);
    return actual == wanted;
  }

  public static Executor get(String command) {
    return TABLE.get(command);
  }

  public static Set<String> names() {
    return Collections.unmodifiableSet(TABLE.keySet());
  }

  /**
   * 按编辑距离给出最接近的方法名
   *
   * <p>
   * 给「方法名打错」用:名字对了才有活干,而凭直觉猜名字是最常见的错法。除了整体编辑距离,还有两条
   * 近义规则,因为命令名是「动作_对象_方式」拼出来的,错法往往只错在其中一段:
   * <ul>
   * <li>词根相同({@code list_tabs} 与 {@code get_tabs} 的词根都是 tabs);</li>
   * <li>下划线分词后是子集({@code click_by_index} ⊂ {@code click_element_by_index},
   * 少写了一个 {@code element} 仍然该被认出来)。</li>
   * </ul>
   *
   * @param unknown 用户写错的名字
   * @param limit   最多返回几个候选
   */
  public static List<String> suggest(String unknown, int limit) {
    if (unknown == null || unknown.isBlank()) {
      return Collections.emptyList();
    }
    String target = unknown.trim().toLowerCase(Locale.ROOT);
    Set<String> targetTokens = tokens(target);
    Map<String, Integer> scored = new java.util.LinkedHashMap<>();
    for (String candidate : TABLE.keySet()) {
      int best = distance(target, candidate);
      if (root(target).equals(root(candidate))) {
        // 词根(第一个下划线之后)相同:动作前缀不同不算错,例如 list_tabs → get_tabs
        best = Math.min(best, Math.max(1, best - 3));
      }
      Set<String> candidateTokens = tokens(candidate);
      if (!targetTokens.isEmpty() && (candidateTokens.containsAll(targetTokens)
          || targetTokens.containsAll(candidateTokens))) {
        // 分词后是子集:少写或多写了一段(click_by_index → click_element_by_index)
        best = Math.min(best, 1);
      }
      scored.put(candidate, best);
    }
    return scored.entrySet().stream().filter(e -> e.getValue() <= Math.max(2, target.length() / 2))
        .sorted(java.util.Map.Entry.comparingByValue()).limit(limit).map(java.util.Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toList());
  }

  /** 取方法名里第一个下划线之后的部分,没有下划线时取全名 */
  private static String root(String method) {
    int underscore = method.indexOf('_');
    return underscore < 0 ? method : method.substring(underscore + 1);
  }

  /** 按下划线分词,用于「少写了一段」的近似判断 */
  private static Set<String> tokens(String method) {
    return new java.util.LinkedHashSet<>(java.util.Arrays.asList(method.split("_")));
  }

  /** 标准 Levenshtein 距离 */
  private static int distance(String left, String right) {
    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];
    for (int j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= left.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[right.length()];
  }

  // ==================== 参数读取,缺失必填参数时给出中文原因 ====================

  private static int reqInt(JSONObject args, String key) {
    Integer value = args.getInteger(key);
    if (value == null) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static double reqDouble(JSONObject args, String key) {
    Double value = args.getDouble(key);
    if (value == null) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static String reqStr(JSONObject args, String key) {
    String value = args.getString(key);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("缺少参数 " + key);
    }
    return value;
  }

  private static String optStr(JSONObject args, String key) {
    String value = args.getString(key);
    return value == null || value.isEmpty() ? null : value;
  }

  /**
   * 取值但不把空串当成缺失
   *
   * <p>
   * 人工答复与 prompt 都可能合法地是空串(「我什么都没填」也是一种答复),用 {@link #optStr} 会把它们
   * 悄悄变成 null,让调用方以为参数没传。
   */
  private static String optStrRaw(JSONObject args, String key) {
    return args.getString(key);
  }

  private static boolean optBool(JSONObject args, String key) {
    Boolean value = args.getBoolean(key);
    return value != null && value;
  }
}
