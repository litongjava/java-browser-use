package nexus.io.ai.browser.actions.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class ExecuteJsParams {
  /** 需要执行的 JavaScript,支持表达式、函数和包含 return 的语句片段 */
  private String body;
}
