import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;

public class CnipaBrowserProbe {
  static PrintWriter log;
  static void record(String s) {log.println(Instant.now()+" "+s); log.flush();}
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !java.util.Set.of("chromium", "chrome", "edge", "firefox", "webkit").contains(args[0])) {
      throw new IllegalArgumentException("Choose chromium, chrome, edge, firefox, or webkit");
    }
    String engine=args[0];
    Path out=Paths.get("playwright-server/target/cnipa-diagnosis/playwright-1.53-"+engine); Files.createDirectories(out);
    log=new PrintWriter(Files.newBufferedWriter(out.resolve("result.log")));
    try(Playwright pw=Playwright.create()) {
      BrowserType type=engine.equals("firefox")?pw.firefox():engine.equals("webkit")?pw.webkit():pw.chromium();
      BrowserType.LaunchOptions opts=new BrowserType.LaunchOptions().setHeadless(false);
      if(engine.equals("edge")) opts.setChannel("msedge");
      if(engine.equals("chrome")) opts.setChannel("chrome");
      try(Browser browser=type.launch(opts)) {
        record("BROWSER "+engine+" VERSION "+browser.version());
        BrowserContext ctx=browser.newContext(); ctx.setDefaultTimeout(20000);
        ctx.onPage(p->{
          p.onFrameNavigated(f->{if(f==p.mainFrame())record("NAV "+f.url());});
          p.onConsoleMessage(m->{if(m.type().equals("warning")||m.type().equals("error"))record("CONSOLE "+m.text());});
          p.onPageError(e->record("PAGE_ERROR "+e));
          p.onCrash(v->record("CRASH"));
          p.onResponse(r->{if(r.request().isNavigationRequest())record("HTTP "+r.status()+" "+r.url());});
          p.onRequestFailed(r->{if(r.isNavigationRequest())record("REQUEST_FAILED "+r.url()+" "+r.failure());});
        });
        Page home=ctx.newPage(); home.navigate("https://sbj.cnipa.gov.cn/index.html");
        Page apply=ctx.waitForPage(()->home.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("商标网上申请").setExact(true)).click());
        apply.waitForLoadState();
        Page login=ctx.waitForPage(()->apply.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("商标新网上申请系统").setExact(true)).click());
        for(int i=0;i<27;i++) {
          home.waitForTimeout(5000);
          try {record("SAMPLE "+((i+1)*5)+"s "+login.evaluate("() => ({url:location.href,title:document.title,text:document.body?.innerText?.slice(0,600),inputs:document.querySelectorAll('input').length})"));}
          catch(PlaywrightException e){record("SAMPLE_ERROR "+login.url()+" "+e.getMessage());}
          if(i==1||i==26) {try{login.screenshot(new Page.ScreenshotOptions().setPath(out.resolve("after-"+((i+1)*5)+"s.png")).setTimeout(5000));}catch(Exception e){record("SCREENSHOT_ERROR "+e.getMessage());}}
        }
        record("COMPLETE");
      }
    }catch(Exception e){record("FAILED "+e); throw e;}
    finally{log.close();}
  }
}
