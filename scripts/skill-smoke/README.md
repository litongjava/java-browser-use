# skill-smoke：java-browser-use 技能冒烟测试

用 HTTP 接口把技能文档（`.dsh/skills/java-browser-use/SKILL.md`）里承诺的行为跑一遍。
文档与源码的**接口名/参数名**一致性由 `playwright-server` 里的 `SkillDocConsistencyTest` 拦截，
批量接口的覆盖度由 `CommandTableTest` 拦截；这个脚本负责**文档说的行为是不是真的**：141 项断言，
覆盖导航、快照与索引、元素交互、读取与状态、定位方式、标签页、等待、鼠标、截图与 PDF、
Cookie 与本地存储、浏览器设置、弹窗与控制台、网络拦截、**批量指令（PTC，POST-only）**、
快照过期、错误处理。

## 前置

1. 服务已启动（默认端口 10049，来自 `playwright-server/src/main/resources/app.properties`）：

   ```powershell
   cd playwright-server
   mvn spring-boot:run
   ```

2. `node` 在 PATH 上（脚本用它起本地测试页，默认端口 10054）。
3. 本机已安装 Playwright 浏览器（首次运行 `mvn test` 会自动下载到 `%LOCALAPPDATA%\ms-playwright`）。

## 运行

```powershell
cd scripts\skill-smoke
powershell -ExecutionPolicy Bypass -File smoke.ps1
powershell -ExecutionPolicy Bypass -File smoke.ps1 -Base http://localhost:10050/api/v1/playwright -PagePort 10055
```

执行策略被拦、又不方便改策略时：

```powershell
cd scripts\skill-smoke
& ([scriptblock]::Create((Get-Content -Raw -Encoding UTF8 .\smoke.ps1)))
```

退出码：`0` 全部通过，`1` 有断言失败，`2` 环境不可用（服务/测试页/node 有问题）。
截图与 PDF 落在 `out/`，可直接人工核对。

## 文件

| 文件 | 作用 |
| --- | --- |
| `smoke.ps1` | 测试主体，每条断言对应文档里的一句话 |
| `test.html` | 测试页：按钮、输入框、复选框、下拉框、拖拽区、弹窗、可隐藏元素、属性元素 |
| `other.html` | 用于多标签页与前进后退 |
| `server.js` | 本地静态服务，另提供 `/api/ping` 供网络拦截用例 mock/abort |
| `out/` | 运行产物（截图、PDF），可随时删除 |

## 注意

- 脚本会 `start` 一个 headless 实例并在结束时 `close`，用的是服务默认的持久化 profile。
- 脚本只调用 HTTP 接口，不改动 `playwright-server` 的任何状态。
- `set_credentials` 会重建浏览器上下文（当前页面丢失），不适合放进冒烟脚本，需单独人工验证。
- 断言依赖 `test.html` 的结构（例如按钮数量 9、下拉框 3 个选项），改页面时要同步改断言。
