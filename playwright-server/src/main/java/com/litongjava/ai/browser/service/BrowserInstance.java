package com.litongjava.ai.browser.service;

import java.nio.file.Path;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

public class BrowserInstance {
  public final Playwright playwright;
  public BrowserContext context;
  public Page page;
  public Path profileDir;
  public LaunchPersistentContextOptions opts;

  public BrowserInstance(Playwright playwright, BrowserContext ctx, Page pg) {
    this.playwright = playwright;
    this.context = ctx;
    this.page = pg;
  }

  public BrowserInstance(Playwright playwright, BrowserContext ctx, Page pg, Path profileDir, LaunchPersistentContextOptions opts) {
    this.playwright = playwright;
    this.context = ctx;
    this.page = pg;
    this.profileDir = profileDir;
    this.opts = opts;
  }
}
