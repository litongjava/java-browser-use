package nexus.io.ai.browser.actions.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import nexus.io.ai.browser.controller.PlaywrightController;
import nexus.io.annotation.RequestPath;

/**
 * 批量接口(/commands)的覆盖度检查
 *
 * <p>PTC 用法要求「单独能调的接口,批量里也能调」,否则模型必须为少数接口回退到多次推理。
 * 所以这里强制:除 commands 自身之外,控制器上的每个接口都必须是批量命令。
 *
 * <p>同时反向检查:批量表里不能有控制器上不存在的命令名(手写命令表最容易出的错就是拼错名字)。
 */
public class CommandTableTest {

  private static Set<String> controllerEndpoints() {
    Set<String> endpoints = new TreeSet<>();
    for (Method method : PlaywrightController.class.getDeclaredMethods()) {
      RequestPath path = method.getAnnotation(RequestPath.class);
      if (path != null) {
        endpoints.add(path.value().substring(1));
      }
    }
    return endpoints;
  }

  private static Set<String> batchCommands() {
    Set<String> commands = new TreeSet<>();
    commands.addAll(new HandlerRegistry().names());
    commands.addAll(CommandTable.names());
    return commands;
  }

  @Test
  public void batchCoversEveryEndpointExceptItself() {
    Set<String> endpoints = controllerEndpoints();
    endpoints.remove("commands");
    Set<String> commands = batchCommands();

    List<String> missing = new ArrayList<>();
    for (String endpoint : endpoints) {
      if (!commands.contains(endpoint)) {
        missing.add(endpoint);
      }
    }
    assertEquals("这些接口还不能在批量接口里调用:" + missing, 0, missing.size());
  }

  @Test
  public void batchHasNoUnknownCommand() {
    Set<String> endpoints = controllerEndpoints();
    List<String> unknown = new ArrayList<>();
    for (String command : batchCommands()) {
      if (!endpoints.contains(command)) {
        unknown.add(command);
      }
    }
    assertTrue("批量表里有控制器上不存在的命令:" + unknown, unknown.isEmpty());
  }

  @Test
  public void everyCommandHasAnExecutor() {
    for (String command : batchCommands()) {
      boolean known = CommandTable.get(command) != null || new HandlerRegistry().get(command) != null;
      assertTrue("命令没有执行体:" + command, known);
    }
  }
}
