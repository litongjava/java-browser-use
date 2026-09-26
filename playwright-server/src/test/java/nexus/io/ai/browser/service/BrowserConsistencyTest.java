package nexus.io.ai.browser.service;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;

import nexus.io.ai.browser.dom.model.DOMState;
import nexus.io.model.body.RespBodyVo;

/** Local fixtures: navigation during capture must not leave actionable mixed indices. */
public class BrowserConsistencyTest {
  private static PlaywrightService service;
  private static Long id;

  @BeforeClass
  public static void start() throws Exception {
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, Files.createTempDirectory("consistency-profile").toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() {
    if (id != null) service.close(id);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
  }

  @Test
  public void stableSnapshotIgnoresItsOwnHighlights() {
    service.getInstance(id).page.setContent("<title>fixture</title><button>Submit</button>");
    RespBodyVo response = service.getBrowserState(id, true, 0);
    assertTrue(response.getMsg(), response.isOk());
    Kv data = (Kv) response.getData();
    assertEquals(data.toString(), true, data.get("snapshotConsistent"));
    assertEquals(true, data.get("indicesUsable"));
    assertEquals(1, data.get("snapshotAttempts"));
  }

  @Test
  public void documentReplacementIsRetriedWithoutReplayingAnAction() {
    AtomicInteger captures = new AtomicInteger();
    PlaywrightService changing = new PlaywrightService() {
      @Override public Kv capture(BrowserInstance inst) {
        if (captures.incrementAndGet() == 1) inst.page.setContent("<button>New document</button>");
        return Kv.by("seq", inst.captureSeq.incrementAndGet());
      }
    };
    service.getInstance(id).page.setContent("<button>Old document</button>");
    Kv data = (Kv) changing.getBrowserState(id, false, 0).getData();
    assertEquals(true, data.get("snapshotConsistent"));
    assertEquals(2, data.get("snapshotAttempts"));
    assertTrue(data.getStr("text"), data.getStr("text").contains("New document"));
    assertFalse(data.getStr("text").contains("Old document"));
  }

  @Test
  public void continuouslyChangingSnapshotInvalidatesIndices() {
    PlaywrightService changing = new PlaywrightService() {
      @Override public Kv capture(BrowserInstance inst) {
        inst.page.evaluate("document.body.append(document.createElement('button'))");
        return Kv.by("seq", inst.captureSeq.incrementAndGet());
      }
    };
    service.getInstance(id).page.setContent("<button>Submit</button>");
    Kv data = (Kv) changing.getBrowserState(id, false, 0).getData();
    assertEquals(false, data.get("snapshotConsistent"));
    assertEquals(false, data.get("indicesUsable"));
    assertNull(service.getInstance(id).domState);
    assertFalse(service.clickElementByIndex(id, 0).isOk());
  }

  @Test
  public void missingElementsAndUrlChangesAreExplicit() {
    DOMState state = new DOMState(null, Map.of(), 0, 0, 0, 0);
    List<String> issues = PlaywrightService.snapshotIssues(Kv.by("url", "old"),
        Kv.by("url", "new"), state, List.of(Kv.by("resolved", false)));
    assertTrue(issues.contains("url_changed"));
    assertTrue(issues.contains("elements_unresolved"));
  }

  @Test
  public void observationFailureCannotEraseACompletedAction() {
    Kv result = PlaywrightService.observeSafely(() -> {
      throw new PlaywrightException("Object doesn't exist: response@fixture");
    });
    assertEquals(false, result.get("observationComplete"));
    assertEquals("unknown", result.get("changeStatus"));
    assertNull(result.get("changed"));
    assertNotNull(result.get("observationError"));
  }

  @Test
  public void uncertainClickReportsUnknownAndDoesNotRetry() {
    BrowserInstance inst = service.getInstance(id);
    inst.page.setContent("<button>Submit</button>");
    Kv before = PlaywrightService.stateProbe(inst, null);
    RespBodyVo response = PlaywrightService.actionErrorOrEffect("click_element_by_role", before,
        inst, null, new PlaywrightException("Object doesn't exist: response@fixture"), "uncertain click");
    assertFalse(response.isOk());
    Kv data = (Kv) response.getData();
    assertEquals("unknown", data.get("actionStatus"));
    assertEquals(false, data.get("retrySafe"));
    assertNull(data.get("effective"));
  }
}
