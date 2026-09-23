package nexus.io.ai.browser.service;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/** 临时实测:登录域 400 白屏,试 locale / 重试 / 先访问主站拿 cookie 三种做法 */
public class TempCnipaSsoTest {

  private static final String LOGIN = "https://sso.cnipa.gov.cn/login";
  private static final String HOME = "https://sbj.cnipa.gov.cn/index.html";
  private static final Path OUT_DIR = Paths.get("target", "cnipa");

  private static PrintStream out;

  @Test
  public void ssoChallengeDiagnosis() throws Exception {
    out = new PrintStream(new FileOutputStream("target/cnipa-sso.txt", false), true, "UTF-8");
    try {
      scenario("zh-CN locale", "--lang=zh-CN", 1, false);
      scenario("reload x3", null, 3, false);
      scenario("via-home-then-login", null, 1, true);
    } finally {
      System.clearProperty(ChromeBrowser.KEY_EXTRA_ARGS);
      ChromeBrowser.resetForTests();
      out.close();
    }
  }

  private void scenario(String label, String extraArgs, int reloads, boolean viaHome) {
    out.println("\n===== " + label + " =====");
    if (extraArgs == null) {
      System.clearProperty(ChromeBrowser.KEY_EXTRA_ARGS);
    } else {
      System.setProperty(ChromeBrowser.KEY_EXTRA_ARGS, extraArgs);
    }
    ChromeBrowser.resetForTests();

    PlaywrightService service = new PlaywrightService();
    ActionService actions = new ActionService(service);
    long id = service.start(null, false);
    try {
      if (viaHome) {
        actions.execute(id, "go_to_url", json("url", HOME));
        wait(4);
      }
      for (int i = 1; i <= reloads; i++) {
        out.println("[NAV " + i + "] " + actions.execute(id, "go_to_url", json("url", LOGIN)).isOk());
        wait(6);
      }
      wait(6);
      out.println("[RESULT] " + js(actions, id,
          "() => JSON.stringify({url: location.href, title: document.title, nav: (performance.getEntriesByType('navigation')[0]||{}).responseStatus,"
              + " docLen: document.documentElement.outerHTML.length, text: document.body ? document.body.innerText.trim().slice(0,120) : '',"
              + " lang: navigator.language, langs: navigator.languages, cookieLen: document.cookie.length,"
              + " acceptLang: (performance.getEntriesByType('navigation')[0]||{}).name})"));
      out.println("[HEADERS] " + js(actions, id,
          "() => fetch(location.href, {method: 'GET'}).then(r => r.status + ' len=' + r.headers.get('content-length')"
              + " + ' type=' + r.headers.get('content-type')).catch(e => 'ERR ' + e.message)"));
      wait(3);
      RespBodyVo shot = actions.execute(id, "screenshot", json("path",
          OUT_DIR.resolve("sso-" + label.replaceAll("[^a-zA-Z0-9]+", "-") + ".png").toAbsolutePath().toString()));
      out.println("[SHOT] ok=" + shot.isOk() + " data=" + shot.getData());
    } finally {
      service.close(id);
    }
  }

  private static String js(ActionService actions, long id, String body) {
    RespBodyVo response = actions.execute(id, "execute_js", json("body", body));
    if (!response.isOk()) {
      return "ERR:" + response.getMsg();
    }
    Object result = ((Kv) response.getData()).get("result");
    if (result == null) {
      wait(2);
      return "async:" + String.valueOf(((Kv) response.getData()));
    }
    return String.valueOf(result);
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

  @SuppressWarnings("unused")
  private static List<String> unused() {
    return List.of();
  }
}
