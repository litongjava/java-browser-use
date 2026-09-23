package nexus.io.ai.browser.service;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/** 临时实测:商标网上申请 → 商标网上申请系统 → 登录页,看还会不会白屏。结果写到 target/cnipa-report.txt */
public class TempCnipaTest {

  private static final String HOME = "https://sbj.cnipa.gov.cn/index.html";
  private static final Path OUT_DIR = Paths.get("target", "cnipa");

  private static PrintStream out;

  @Test
  public void cnipaLoginPageIsNotBlank() throws Exception {
    Files.createDirectories(OUT_DIR);
    out = new PrintStream(new FileOutputStream("target/cnipa-report.txt", false), true, "UTF-8");
    try {
      for (boolean headless : List.of(true, false)) {
        out.println("\n===== headless=" + headless + " =====");
        flow(headless);
      }
    } finally {
      out.close();
    }
  }

  private void flow(boolean headless) throws Exception {
    PlaywrightService service = new PlaywrightService();
    ActionService actions = new ActionService(service);
    long id = service.start(null, headless);
    out.println("[BROWSER] " + service.browserInfo(id));
    try {
      actions.execute(id, "go_to_url", json("url", HOME));
      wait(5);
      state(actions, id, "首页");

      out.println("[CLICK1] " + actions.execute(id, "click_element_by_selector",
          json("selector", "a:has-text('商标网上申请')")).getMsg());
      wait(6);
      tabs(actions, id);
      lastTab(actions, id);
      state(actions, id, "网上申请页");

      out.println("[CLICK2] " + actions.execute(id, "click_element_by_selector",
          json("selector", "text=\"商标新网上申请系统\"")).getMsg());
      wait(10);
      tabs(actions, id);
      lastTab(actions, id);
      for (int i = 1; i <= 2; i++) {
        wait(5);
        state(actions, id, "登录页等 " + (i * 5) + "s");
      }
      for (String shot : List.of("login-full", "login-viewport")) {
        RespBodyVo response = actions.execute(id, "screenshot", json("path",
            OUT_DIR.resolve("h" + headless + "-" + shot + ".png").toAbsolutePath().toString(),
            "fullPage", shot.endsWith("full")));
        out.println("[SHOT " + shot + "] ok=" + response.isOk() + " msg=" + response.getMsg() + " data=" + response.getData());
      }
    } finally {
      service.close(id);
    }
  }

  private static void state(ActionService actions, long id, String label) {
    out.println("[STATE " + label + "] " + js(actions, id,
        "() => JSON.stringify({url: location.href, title: document.title, ready: document.readyState,"
            + " textLen: document.body ? document.body.innerText.trim().length : -1,"
            + " htmlLen: document.body ? document.body.innerHTML.length : -1,"
            + " canvases: document.querySelectorAll('canvas').length,"
            + " iframes: document.querySelectorAll('iframe').length,"
            + " fonts: document.fonts ? document.fonts.status : 'n/a',"
            + " text: document.body ? document.body.innerText.trim().slice(0, 150) : ''})"));
  }

  private static void tabs(ActionService actions, long id) {
    RespBodyVo response = actions.execute(id, "get_tabs", new JSONObject());
    out.println("[TABS] " + (response.isOk() ? ((Kv) response.getData()).get("tabs") : response.getMsg()));
  }

  private static void lastTab(ActionService actions, long id) {
    RespBodyVo response = actions.execute(id, "get_tabs", new JSONObject());
    List<?> list = response.isOk() ? (List<?>) ((Kv) response.getData()).get("tabs") : null;
    if (list != null && list.size() > 1) {
      actions.execute(id, "switch_tab", json("pageIndex", list.size() - 1));
      wait(2);
    }
  }

  private static String js(ActionService actions, long id, String body) {
    RespBodyVo response = actions.execute(id, "execute_js", json("body", body));
    if (!response.isOk()) {
      return "ERR:" + response.getMsg();
    }
    return String.valueOf(((Kv) response.getData()).get("result"));
  }

  private static JSONObject json(Object... pairs) {
    JSONObject object = new JSONObject();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      object.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return object;
  }

  private static void wait(int seconds) {
    try {
      Thread.sleep(seconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
