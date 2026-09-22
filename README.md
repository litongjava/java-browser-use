# DeepSeek Browser Use

**给 DeepSeek Harness（以及任何会调 HTTP 的智能体）用的浏览器中间件。**

智能体自己读不了网页、点不了按钮。这个服务把一台真实的 Chromium 包成一个 HTTP 端点：智能体发一条 `{id, method, params}`，服务就去操作浏览器，然后把页面变成两样它能读懂的东西 ——

- **可交互结构化文本**：整页 DOM 压成 `[index]<a >登录/>` 这样的行，`[index]` 就是可以点的元素编号；
- **截图**：每次页面变化自动留一张，落在 `data/<id>/` 下，可以直接喂给视觉模型。

它不是一个抓取库，也不是无头爬虫框架：它是一个**长驻的、有状态的浏览器服务**，一个任务一个独立实例，登录态、页签、Cookie 都跟着任务走。

---

## 一、为什么需要它

| 智能体想要的 | 纯 HTTP 抓取 | DeepSeek Browser Use |
| --- | --- | --- |
| 看到 JS 渲染后的页面 | 拿不到 | 真实 Chromium，渲染完再读 |
| 点击、填表、翻页 | 做不到 | 按索引点击/输入/勾选/拖拽 |
| 保持登录态 | 要自己维护 Cookie | 每个任务一个持久化 profile |
| 「现在页面长什么样」 | 只有 HTML 字符串 | 结构化文本 + 截图，token 可控 |
| 一次推理跑完一段操作 | 一次请求一个动作 | `commands` 批量，动作和读取混排 |
| 事后回看某一步 | 没有留档 | `data/<id>/<序号>.png` + `.txt` |

典型场景：让智能体去某个后台查一条数据、在电商站点搜一个商品、把表单填完提交、把验证码交给人工再看结果。这些都必须有真实浏览器。

---

## 二、下载即用（发行版）

发行版是**单文件可执行 jar**，内嵌了对应平台的 Chromium，**下载后不需要再下载任何文件**。

| 平台 | 文件 |
| --- | --- |
| Windows x64 | `deepseek-browser-use-<版本>-windows-x64.jar` |
| Linux x64 | `deepseek-browser-use-<版本>-linux-x64.jar` |
| Linux arm64 | `deepseek-browser-use-<版本>-linux-arm64.jar` |
| macOS Intel | `deepseek-browser-use-<版本>-macos-x64.jar` |
| macOS Apple 芯片 | `deepseek-browser-use-<版本>-macos-arm64.jar` |

```shell
# 需要 Java 21 或更高版本
java -jar deepseek-browser-use-1.0.0-windows-x64.jar
```

启动后监听 `http://localhost:10049`：

```shell
# 健康检查
curl -s http://localhost:10049/playwright/health
# {"code":1,"data":{"name":"playwright-server"},"ok":true,...}
```

首次启动会把内嵌的 Chromium 解压到用户缓存目录（`~/.cache/deepseek-browser-use/`），之后每次启动都直接用缓存，几秒内可用。

> 解压目录按平台区分，同一台机器上多个平台的包不会互相覆盖。

### 一个最小的使用示例

```shell
BASE=http://localhost:10049/playwright/command

# 1. 起一个任务（headless=false 会弹出真实窗口，可以看着它操作）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"start","params":{"headless":false}}'

# 2. 打开页面（响应里带回这一页的自动截图）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"go_to_url","params":{"url":"https://example.com"}}'
# {"data":{"status":200,"seq":1,"screenshot":"/data/1001/1.png",...}}

# 3. 取浏览器状态：browser_state 是页签，text 是可交互结构化文本
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"get_browser_state"}'

# 4. 按索引点一下
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"click_element_by_index","params":{"index":0}}'

# 5. 看这一步的截图
#    http://localhost:10049/data/1001/2.png

# 6. 收工
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"close"}'
```

---

## 三、接口长什么样

**只有一个业务端点。**

```
POST http://localhost:10049/playwright/command
Content-Type: application/json

{ "id": 1001, "method": "go_to_url", "params": { "url": "https://example.com" } }
```

| 字段 | 说明 |
| --- | --- |
| `id` | 任务 ID。`start` 时可自己指定，不传就自动生成；其余方法必填 |
| `method` | 方法名，共 93 个 |
| `params` | 该方法自己的参数 |

响应统一是：

```json
{ "data": {}, "code": 1, "ok": true, "error": null, "msg": null }
```

`code=1` / `ok=true` 成功，失败原因在 `msg`（中文）。**参数问题不会返回 HTTP 500**：缺参数是 `click_element_by_index 失败：缺少参数 index`，方法不存在是 `不支持的方法：xxx`。

另外两个端点：`GET /playwright/health`（健康检查）、`GET /data/**`（读截图与结构化文本）。

完整的方法清单、参数、返回字段、坑与限制都在技能文档里：

> **[`.dsh/skills/deepseek-browser-use/SKILL.md`](.dsh/skills/deepseek-browser-use/SKILL.md)**

那份文档是给智能体读的，也是给人读的参考手册，**以它为准**。

---

### 可选精简响应

请求信封增加 `"responseMode":"compact"` 可减少 `data` 内的重复页签描述、本机截图路径和点击诊断字段；`"diagnostics":true` 保留点击诊断信息。默认完整格式不变，`ok/code/error/msg` 在所有模式下均保留，包括 null。

表单快照显示实时值、只读和禁用状态，密码值脱敏；普通布局缩进折叠。点击回执会短暂观察异步变化，`changed=false` 仅代表尚未观察到变化。单条和批量动作均自动归档截图。

网络请求及响应通过 requestId 关联，支持按 ID 回查特定响应，并明确报告请求体、响应体的截断状态。业务查询返回空记录时，调用方需核实查询条件，不能直接推断金额为零。

## 四、核心概念

### 一个任务一个实例

每次 `start` 都新建一套独立的 Chromium + 持久化 profile，任务之间完全隔离：

- profile 在 `~/.config/browseruse/profiles/<id>`，**同一个 id 重新 `start` 时登录态还在**；
- 同一个 id 不能重复 `start`（会明确报错，提示先 `close` 或换 id）；
- 不同任务用不同 id，可以并发跑，互不干扰。

**隔离的单位是浏览器上下文，不是 Playwright 的 driver。** `Playwright.create()` 会拉起一个 node 子进程并握手，每次约 350～400ms，还常驻一份内存；但「一个任务一个实例」并不需要各自一个 driver —— 真正需要隔离的是 `BrowserContext`（独立 profile、独立浏览器进程）。所以服务全进程共用一个 driver，`start` 直接从 `launchPersistentContext()` 开始：

| | 每个任务一个 driver | 共享一个 driver（现在） |
| --- | --- | --- |
| 第 2 个任务起的 `start` 耗时 | ~850ms | ~480ms |
| 空闲时的 node 进程 | 每个任务一个 | 1 个 |

代价是 driver 成了单点：它一旦崩，所有任务一起断。所以 `start` 里带了自愈 —— 第一次失败就把共享实例判死、重建一个再试一次（实测 driver 被杀后，下一次 `start` 会在 ~340ms 内重建并成功）。

`close` 只关掉该任务自己的浏览器上下文，**不会**动共享的 driver，因此关一个任务不会连累别的任务。driver 本身由 JVM 退出时的 shutdown hook 收尾；即使进程被硬杀（`taskkill /F`），driver 也会因为管道关闭自己退出，不会留下孤儿进程。

### get_browser_state：智能体的「眼睛」

```
data.browser_state:            data.text:
Browser tab: 1, Title: "...",  		[0]<a >新闻/>
URL: "https://...".             		[8]<a />
current tab is: 1               		[12]<a name='tj_login'>登录/>
```

- `browser_state` 告诉你**有几个页签、当前在哪一个**；
- `text` 告诉你**页面上有什么、哪些能点**，每行的 `[index]` 就是操作时要传的索引。

### 页面变化自动留档

| 时机 | 产物 |
| --- | --- |
| 「会改变页面」的方法执行成功 | `data/<id>/<序号>.png` |
| 每次 `get_browser_state` | `data/<id>/<序号>.png` **+** 同名的 `.txt`（页签 + 结构化文本） |

序号从 1 开始递增，`data/<id>/1.png`、`2.png`、`3.png`…… 一对 `.png`/`.txt` 序号相同就代表是同一时刻的页面。两个文件都能直接 GET，视觉模型按 URL 取图即可。一次 `commands` 批量请求里，每一步的截图都在它自己那一步的 `data.screenshot` 里 —— 一个批次就是一段页面变化历史。

### 批量指令（PTC）

把「动作 + 读取」打包成一次请求，一次模型推理拿到全部观察结果：

```json
{ "id": 1001, "method": "commands",
  "params": { "stopOnError": false, "commands": [
    { "input_text": { "index": 14, "text": "Mac Mini M4" } },
    { "send_keys": { "keys": "Enter" } },
    { "wait_for_text": { "text": "Mac Mini", "timeoutSeconds": 10 } },
    { "get_browser_state": {} }
  ] } }
```

---

## 五、从源码构建

### 环境

- JDK 21+
- Maven 3.8+
- Node.js 18+（只用于打包发行版）

### 开发态运行

```shell
cd playwright-server
mvn spring-boot:run
```

开发态下 jar 里没有内嵌浏览器（`browsers/index.txt` 不存在），`BundledBrowser` 会返回 null，Playwright 就用它自己管理的浏览器（缓存目录是 `~/.cache/ms-playwright`，Windows 上是 `%USERPROFILE%\AppData\Local\ms-playwright`），本地调试不需要重新打包。首次用到时会自动下载，需要能访问外网。

### 跑测试

```shell
mvn test
```

测试里有几条「防漂移」的检查：技能文档必须覆盖命令表里的每个方法、文档里不能出现旧方法名、`browser.properties` 里的 Chromium 修订号必须和当前 Playwright 依赖要求的一致。

### 打发行版

```shell
# 当前构建机对应的平台
node scripts/package/build-release.mjs

# 单个平台
node scripts/package/build-release.mjs --platform=linux-x64

# 家族别名:macos / linux 会展开成两个架构
node scripts/package/build-release.mjs --platform=macos
node scripts/package/build-release.mjs --platform=linux

# 五个平台全打
node scripts/package/build-release.mjs --platform=all

# 看帮助
node scripts/package/build-release.mjs --help
```

平台名与别名：

| 平台名 | 说明 |
| --- | --- |
| `windows-x64` | Windows 64 位 |
| `linux-x64` / `linux-arm64` | Linux x86_64 / arm64 |
| `macos-x64` / `macos-arm64` | macOS Intel / Apple 芯片 |
| `all` | 上面五个 |
| `macos`、`mac`、`darwin` | 等价于 `macos-x64` + `macos-arm64` |
| `linux` | 等价于 `linux-x64` + `linux-arm64` |
| `windows`、`win` | 等价于 `windows-x64` |

**打包不受构建机限制**：在 Windows 上一样能打出 Linux 与 macOS 的包（macOS 的符号链接问题已在打包流程里处理，见下）。构建机需要 JDK 21+、Maven、Node.js 18+，以及能访问 Playwright 的 CDN。

脚本会：

1. 从 Playwright 官方 CDN 下载目标平台的 Chromium（缓存在 `playwright-server/build/browser-cache/`，重复构建不重复下载，网络中断会自动重试）；
2. 解压、校验每个文件都落地，并生成内嵌清单 `index.txt` / `meta.properties` / `symlinks.txt`；
3. 执行 `mvn -Pproduction package`，把 Chromium 和**目标平台自己的** Playwright driver 打进一个 fat jar；
4. 校验成品 jar 里该有的条目都在（驱动类、目标平台 node、内嵌浏览器可执行文件），产物放到 `dist/deepseek-browser-use-<版本>-<平台>.jar`。

两处细节值得单独说，因为它们都是「构建能过、用户一跑就崩」的坑：

> **只打目标平台的 driver。** Playwright 的 driver 包里带着 5 个平台的 node，合计约 194MB，而一个发行版只需要自己那个平台的，打包时会把另外 4 个剔掉（jar 从 360MB 降到约 200MB）。注意 `DriverJar.class` 也在同一个包里、而且不在 `driver/` 目录下，必须一起保留，否则启动时会报 `ClassNotFoundException: ...impl.driver.jar.DriverJar`。打包脚本的 `verifyJar` 会挡住这类改坏。
>
> **macOS 的符号链接。** `Chromium.app` 是标准 framework 结构，靠 `Versions/Current -> 138.0.7204.23` 这类符号链接才能启动。但打包机不一定是 macOS（Windows 上创建符号链接需要管理员特权），所以打包时把链接记进 `browsers/symlinks.txt`，由 `BundledBrowser` 在目标机器上重建。

### 首次运行时会做什么

发行版启动后第一次 `start` 浏览器时，`BundledBrowser` 会把 jar 里的 Chromium 解压到 `~/.cache/deepseek-browser-use/<构建标识>/`，在 macOS 上重建 framework 需要的符号链接，最后写下 `.complete` 标记；之后每次都直接用这个目录，不再解压。解压失败时（例如磁盘满）会退回到 Playwright 自己管理的浏览器并打印警告，不会让服务起不来。

`<构建标识>` 形如 `chromium-1179-win64`，含平台名，所以同一台机器上放多个平台的包不会互相覆盖。

---

## 六、目录结构

```
deepseek-browser-use/
├── playwright-server/                     服务本体
│   ├── src/main/java/nexus/io/ai/browser/
│   │   ├── PlaywrightApp.java             入口(main)
│   │   ├── config/                        路由注册
│   │   ├── handler/PlaywrightHandler.java 唯一的控制端点 POST /playwright/command
│   │   ├── actions/registry/CommandTable.java  方法名 → 服务调用 的分发表
│   │   ├── service/
│   │   │   ├── ActionService.java         方法分发与 commands 批量执行
│   │   │   ├── PlaywrightService.java     所有浏览器操作
│   │   │   ├── BrowserInstance.java       一个任务的全部运行时状态
│   │   │   └── BundledBrowser.java        内嵌 Chromium 的解压与定位
│   │   └── dom/                            buildDomTree 与结构化文本
│   └── src/main/resources/
│       ├── app.properties                 端口
│       ├── browser.properties             内嵌 Chromium 的修订号
│       └── dom/dom_tree/                  DOM 转结构化文本的 JS
├── scripts/package/build-release.mjs      发行版打包脚本
├── .dsh/skills/deepseek-browser-use/      给智能体读的技能文档
└── dist/                                  发行版产物(构建后生成)
```

运行期产物：

```
<启动目录>/data/<任务ID>/<序号>.png   截图
<启动目录>/data/<任务ID>/<序号>.txt   同一时刻的页签 + 可交互结构化文本
```

---

## 七、安全提示

- 服务**没有鉴权**，`execute_js` 能执行任意脚本，`/data/**` 能读到所有截图与页面文本。
- 默认只监听本机。**对外暴露前必须自己加访问控制**，并且不要把 `/data/**` 直接放到公网。
- `data/` 里的文件不会自动清理，长期跑要自己定期清理。

### 关于 Chromium 沙箱

Windows / macOS 上服务会显式**开启** Chromium 沙箱；Linux 上关闭（容器里通常以 root 运行，不带 `--no-sandbox` 时 Chrome 会直接拒绝启动）。

这么做顺带消掉了那条很常见的警告：

```
You are using an unsupported command-line flag: --no-sandbox. Stability and security will suffer.
```

值得说清楚的是：**这个标志不是本服务加的**。Playwright 的 `chromiumSandbox` 默认就是 `false`，它自己会往命令行里塞 `--no-sandbox`（见驱动的 `_innerDefaultArgs`：`if (options.chromiumSandbox !== true) chromeArguments.push("--no-sandbox")`）。所以只把自己传的参数删掉是没用的，必须显式开启沙箱。服务传的启动参数里只有 `--disable-blink-features=AutomationControlled`（以及 Linux 上的 `--disable-dev-shm-usage`），这两个都不在 Chrome 的「不受支持标志」名单里。

这条改动有对应的单元测试（`PlaywrightServiceTest`）盯着：只要有人把 `--no-sandbox`、`--disable-web-security` 之类加回启动参数，或者把非 Linux 上的沙箱关掉，构建就会失败。
