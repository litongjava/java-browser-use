package com.litongjava.ai.browser.actions.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class ScrollParams {
  private Boolean down;
  private Integer numPages;
  private Integer index;
}
