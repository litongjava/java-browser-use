# skill-smoke：deepseek-browser-use 技能冒烟测试

用 HTTP 接口把技能文档（`.dsh/skills/deepseek-browser-use/SKILL.md`）里承诺的**行为**跑一遍。

职责分工很明确：

| 谁 | 挡什么 |
| --- | --- |
| `CommandTableTest` | 分发表本身：方法名齐全、参数缺失报错、哪些方法算「会改变页面」 |
| `SkillDocConsistencyTest` | 文档与源码漂移：每个命令都在文档里、文档里不留旧方法名 |
| `BundledBrowserRevisionTest` | 内嵌 Chromium 的修订号与当前 Playwright 依赖一致 |
| **`smoke.ps1`（本目录）** | **文档说的行为是不是真的**：真起浏览器、真发 HTTP、真读磁盘上的截图 |

覆盖：端点形态、`start` 的「一个任务一个实例」、`get_browser_state` 的页签块与结构化文本、
自动截图与同名 `.txt` 落盘、`/data/**` 静态读取、批量指令、页签 1 基/0 基的区别、
以及各类错误响应都是 JSON 而不是 500。

## 前置

1. 服务已启动，二选一：

   ```powershell
   # 发行版（推荐，顺便验证内嵌 Chromium）
   cd dist
   java -jar deepseek-browser-use-1.0.0-windows-x64.jar

   # 或开发态
   cd playwright-server
   mvn spring-boot:run
   ```

2. 能访问外网（脚本要开 `https://example.com` 与 `https://example.org` 来验证页签）。

## 运行

```powershell
pwsh -File scripts/skill-smoke/smoke.ps1

# 换地址 / 换任务 ID
pwsh -File scripts/skill-smoke/smoke.ps1 -Base http://localhost:10050 -Id 900002

# 跑完不关实例，方便人工打开浏览器看
pwsh -File scripts/skill-smoke/smoke.ps1 -Keep
```

执行策略被拦、又不方便改策略时：

```powershell
cd scripts\skill-smoke
& ([scriptblock]::Create((Get-Content -Raw -Encoding UTF8 .\smoke.ps1)))
```

退出码：`0` 全部通过，`1` 有断言失败，`2` 环境不可用（服务连不上）。

## 文件

| 文件 | 作用 |
| --- | --- |
| `smoke.ps1` | 测试主体，每条断言对应技能文档里的一句话 |
| `test.html` / `other.html` / `server.js` | 早期的本地测试页与静态服务，供扩展用例时使用；当前 `smoke.ps1` 只依赖公网页面，不启动它们 |

## 注意

- 脚本会 `start` 一个 headless 实例并在结束时 `close`；`-Keep` 时不会 `close`。
- 截图与结构化文本落在**服务进程工作目录**下的 `data/<id>/`，也就是你启动服务时所在的那个目录。
- 脚本只调用 HTTP 接口，不改动服务端代码或配置。
- 断言依赖 `get_browser_state` 的输出格式（`Browser tab: N, Title: "...", URL: "...".` 与 `current tab is: N`），
  改这个格式时要同步改断言与 `PlaywrightServiceTest`。
