package nexus.io.ai.browser.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.service.ChromeBrowser;
import nexus.io.model.body.RespBodyVo;

/**
 * 调用追踪日志的脱敏
 *
 * <p>填表类任务里,请求体带着姓名、手机号、证件号、详细地址,而这些日志「不做脱敏、不会自动清理」——
 * 原文留在磁盘上迟早出问题。这里验证:内置规则确实命中、自定义规则生效、关掉开关后是原文,
 * 以及**落盘的文件里真的没有敏感值**(不只是内存里的函数返回对了)。
 */
public class CommandTraceLogRedactTest {

  private static Path tempDir;

  @BeforeClass
  public static void setUp() throws IOException {
    tempDir = Files.createTempDirectory("browser-use-trace-test");
    System.setProperty(CommandTraceLog.KEY_DIR, tempDir.toString());
    ChromeBrowser.resetForTests();
  }

  @AfterClass
  public static void tearDown() throws IOException {
    for (String key : List.of(CommandTraceLog.KEY_DIR, CommandTraceLog.KEY_REDACT,
        CommandTraceLog.KEY_REDACT_ENABLED, CommandTraceLog.KEY_REDACT_MASK)) {
      System.clearProperty(key);
    }
    ChromeBrowser.resetForTests();
    if (tempDir != null) {
      try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void phoneNumbersAreMasked() {
    String masked = CommandTraceLog.redact("联系人手机号 13800000000");
    assertFalse("手机号不该出现在日志里:" + masked, masked.contains("13800000000"));
    assertTrue(masked.contains("***"));
  }

  @Test
  public void creditCodeAndIdCardAreMasked() {
    String masked = CommandTraceLog.redact("统一社会信用代码 91310118MAK7DA2R14");
    assertFalse("18 位证件号不该出现在日志里:" + masked, masked.contains("91310118MAK7DA2R14"));

    String idCard = CommandTraceLog.redact("身份证 11010119900307123X");
    assertFalse("身份证号不该出现在日志里:" + idCard, idCard.contains("11010119900307123X"));
  }

  @Test
  public void emailsAreMasked() {
    String masked = CommandTraceLog.redact("邮箱 someone@example.com 请回信");
    assertFalse("邮箱不该出现在日志里:" + masked, masked.contains("someone@example.com"));
  }

  /**
   * 毫秒时间戳不能被当成手机号
   *
   * <p>实测踩过:2026 年的 epoch 毫秒是 {@code 1790…},开头正好是 {@code 17},{@code 1[3-9]\d{9}}
   * 于是命中,日志里的 {@code recordedSince} 被掩成 {@code ***87} —— 脱敏把排查要用的信息也抹掉了。
   */
  @Test
  public void epochMillisecondsAreNotMistakenForPhoneNumbers() {
    assertEquals("1790161171387", CommandTraceLog.redact("1790161171387"));
    String line = CommandTraceLog.redact("{\"recordedSince\":1790161171387,\"inflight\":0}");
    assertTrue("时间戳要原样保留:" + line, line.contains("1790161171387"));
    // 但独立的 11 位手机号照样要被掩掉
    assertFalse(CommandTraceLog.redact("13800000000").contains("13800000000"));
  }

  /** 长数字/长字母串里的一截也不算命中,避免把 base64、摘要、订单号打碎 */
  @Test
  public void partialMatchesInsideLongerTokensAreIgnored() {
    String hash = "fd577b4ba4081312b9aa4f594b00f5f6da8e67bbca8d36fb55de8ae3a3194870";
    assertEquals("sha256 摘要不该被改动", hash, CommandTraceLog.redact(hash));
    String orderNo = "202609231799999999999999";
    assertEquals("一长串数字里的一截不算手机号", orderNo, CommandTraceLog.redact(orderNo));
  }

  /** 自定义规则用来自定义「这个项目里还有什么算敏感」(公司名、商标名…) */
  @Test
  public void customPatternsAreApplied() {
    System.setProperty(CommandTraceLog.KEY_REDACT, "示例科技有限公司,示例课堂");
    ChromeBrowser.resetForTests();
    try {
      String masked = CommandTraceLog.redact("申请人 示例科技有限公司 商标 示例课堂");
      assertFalse("自定义规则没生效:" + masked, masked.contains("示例科技有限公司"));
      assertFalse("自定义规则没生效:" + masked, masked.contains("示例课堂"));
    } finally {
      System.clearProperty(CommandTraceLog.KEY_REDACT);
      ChromeBrowser.resetForTests();
    }
  }

  /** 规则写坏时只跳过那一条,不能让整条日志丢掉 */
  @Test
  public void brokenPatternDoesNotBreakRedaction() {
    System.setProperty(CommandTraceLog.KEY_REDACT, "[这不是合法正则");
    ChromeBrowser.resetForTests();
    try {
      String masked = CommandTraceLog.redact("手机 13800000000");
      assertFalse("坏规则不该影响内置规则:" + masked, masked.contains("13800000000"));
    } finally {
      System.clearProperty(CommandTraceLog.KEY_REDACT);
      ChromeBrowser.resetForTests();
    }
  }

  @Test
  public void maskIsConfigurable() {
    System.setProperty(CommandTraceLog.KEY_REDACT_MASK, "[已脱敏]");
    ChromeBrowser.resetForTests();
    try {
      assertEquals("手机 [已脱敏]", CommandTraceLog.redact("手机 13800000000"));
    } finally {
      System.clearProperty(CommandTraceLog.KEY_REDACT_MASK);
      ChromeBrowser.resetForTests();
    }
  }

  /** 关掉开关后是原文:排查「值到底传没传对」时可能需要,所以这个开关要真的管用 */
  @Test
  public void canBeTurnedOff() {
    System.setProperty(CommandTraceLog.KEY_REDACT_ENABLED, "false");
    ChromeBrowser.resetForTests();
    try {
      assertEquals("手机 13800000000", CommandTraceLog.redact("手机 13800000000"));
    } finally {
      System.clearProperty(CommandTraceLog.KEY_REDACT_ENABLED);
      ChromeBrowser.resetForTests();
    }
  }

  @Test
  public void nullAndEmptyArePassedThrough() {
    assertEquals(null, CommandTraceLog.redact(null));
    assertEquals("", CommandTraceLog.redact(""));
  }

  /** 端到端:落盘的三个文件里都不能有手机号 */
  @Test
  public void writtenFilesDoNotContainPersonalData() throws IOException {
    String body = "{\"id\":1001,\"method\":\"input_text\",\"params\":{\"index\":3,\"text\":\"联系人 13800000000\"}}";
    RespBodyVo response = RespBodyVo.ok(Kv.by("value", "13800000000").set("url", "https://example.com/13800000000"));
    CommandTraceLog.record(body, response, System.currentTimeMillis() - 12);

    Path dayDir = CommandTraceLog.currentDir();
    assertTrue("当天目录要建出来:" + dayDir, Files.isDirectory(dayDir));
    List<Path> files;
    try (java.util.stream.Stream<Path> paths = Files.list(dayDir)) {
      files = paths.toList();
    }
    assertFalse("至少要写出 steps.log 与 calls.jsonl", files.isEmpty());
    for (Path file : files) {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      assertFalse("文件里还有手机号:" + file.getFileName() + " → " + content, content.contains("13800000000"));
    }
  }

  /** 上传也留痕(文件名/大小/摘要),但不落文件内容 */
  @Test
  public void uploadIsTracedWithoutContent() throws IOException {
    Kv saved = Kv.by("filename", "图样.jpg").set("relativePath", "图样.jpg").set("path", "/tmp/图样.jpg")
        .set("size", 1234).set("sha256", "abc").set("contentType", "image/jpeg");
    CommandTraceLog.recordUpload(saved);
    Path log = CommandTraceLog.currentDir().resolve("uploads.log");
    assertTrue("uploads.log 要写出来", Files.exists(log));
    String content = Files.readString(log, StandardCharsets.UTF_8);
    assertTrue("要记下文件名:" + content, content.contains("图样.jpg"));
    assertTrue("要记下大小:" + content, content.contains("1234"));
    assertFalse("不该记录文件内容", content.contains("base64"));
  }

  /** highlights 是给摘要用的:大块内容(text/base64)不该进摘要 */
  @Test
  public void summaryKeepsSmallFieldsOnly() {
    JSONObject response = JSONObject.parseObject(
        "{\"ok\":true,\"data\":{\"url\":\"https://example.com\",\"text\":\"很长的页面文本\",\"base64\":\"AAAA\"}}");
    JSONObject highlights = CommandTraceLog.highlights(response);
    assertEquals("https://example.com", highlights.get("url"));
    assertFalse("整页文本不进摘要", highlights.containsKey("text"));
    assertTrue("但要记下长度", highlights.containsKey("textLength"));
    assertFalse("base64 不进摘要", highlights.containsKey("base64"));
  }
}
