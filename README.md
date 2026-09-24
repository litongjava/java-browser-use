# DeepSeek Browser Use

**给 DeepSeek Harness（以及任何会调 HTTP 的智能体）用的浏览器中间件。**

智能体自己读不了网页、点不了按钮。这个服务把一台真实的浏览器包成一个 HTTP 端点：智能体发一条 `{id, method, params}`，服务就去操作浏览器，然后把页面变成两样它能读懂的东西 ——

- **可交互结构化文本**：整页 DOM 压成 `[index]<a >登录/>` 这样的行，`[index]` 就是可以点的元素编号；
- **截图**：每次页面变化自动留一张，落在 `data/<id>/` 下，可以直接喂给视觉模型。

它不是一个抓取库，也不是无头爬虫框架：它是一个**长驻的、有状态的浏览器服务** —— 一个浏览器进程、一份持久化 profile，多个任务靠页签隔离，登录态一直留着。

默认直接用**本机安装的 Google Chrome**（没有才退回内嵌 Chromium），所以 Google 登录之类的站点不会因为「这是个自动化浏览器」而拒绝；profile 是所有任务共用的，agent 第一次登录之后，后续任务不用再登。

---

## 一、为什么需要它

| 智能体想要的 | 纯 HTTP 抓取 | DeepSeek Browser Use |
| --- | --- | --- |
| 看到 JS 渲染后的页面 | 拿不到 | 真实浏览器，渲染完再读 |
| 点击、填表、翻页 | 做不到 | 按索引点击/输入/勾选/拖拽 |
| 保持登录态 | 要自己维护 Cookie | 一份共享的持久化 profile，登录一次长期有效 |
| 「现在页面长什么样」 | 只有 HTML 字符串 | 结构化文本 + 截图，token 可控 |
| 一次推理跑完一段操作 | 一次请求一个动作 | `commands` 批量，动作和读取混排 |
| 事后回看某一步 | 没有留档 | `data/<id>/<序号>.png` + `.txt` |

典型场景：让智能体去某个后台查一条数据、在电商站点搜一个商品、把表单填完提交、把验证码交给人工再看结果。这些都必须有真实浏览器。

---

## 二、下载即用（发行版）

发行版是**单文件可执行 jar**，内嵌了对应平台的 Chromium，**下载后不需要再下载任何文件**。

> 内嵌的 Chromium 只是兜底：只要这台机器上装了 Google Chrome，服务就用它（见下文「用哪个浏览器、哪份 profile」），内嵌的那份不会被解压。

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
| `method` | 方法名，共 113 个（`list_methods` 或 `GET /playwright/methods` 能随时查全量清单） |
| `params` | 该方法自己的参数 |

响应统一是：

```json
{ "data": {}, "code": 1, "ok": true, "error": null, "msg": null }
```

`code=1` / `ok=true` 成功，失败原因在 `msg`（中文）。**参数问题不会返回 HTTP 500**：缺参数是 `click_element_by_index 失败：缺少参数 index`；方法名写错会顺带给近似建议（`不支持的方法：list_tabs，你是不是想用 get_tabs / new_tab？`），照着重发一次就行。失败时 `data` 里另有 `errorCode`，以及可重试的 `retryable` / `retryAfterMs`（例如站点侧限流是 `RATE_LIMITED`，建议等 35 秒再来）。

另外几个端点：

| 端点 | 用途 |
| --- | --- |
| `GET /playwright/health` | 健康检查 |
| `GET /playwright/tasks` | 当前活着的任务与共享浏览器（URL、标题、页签数、在途请求、profile 目录） |
| `GET /playwright/methods` | 命令清单 |
| `GET /playwright/config` | 服务端**生效**配置（引擎、profile 目录、降级开关、日志与脚本目录） |
| `GET /data/**` | 读截图与结构化文本 |
| `POST /playwright/upload` | **文件暂存**：客户端-服务器模式下，把本地文件送到服务端，再让 `upload_file` 用它 |
| `GET /playwright/upload` | 列出暂存目录里的文件 |
| `DELETE /playwright/upload?name=<文件名>` | 删除一个暂存文件 |

> 现在也可以完全不走 `/playwright/upload`：`upload_file` 支持 `contentBase64` 或 `url`，服务端自己落盘再交给页面，省掉一次往返。`path` 仍然可用（相对路径按服务端暂存目录解析）。

`POST /playwright/upload` 支持三种请求体（任选）：

```bash
curl -F "file=@图样.jpg" http://localhost:10049/playwright/upload
curl --data-binary @图样.jpg "http://localhost:10049/playwright/upload?filename=图样.jpg"
curl -H "Content-Type: application/json" -d '{"filename":"图样.jpg","contentBase64":"/9j/4AAQ..."}' \
     http://localhost:10049/playwright/upload
```

返回 `data.filename` / `data.path` / `data.relativePath` / `data.size` / `data.sha256`，其中 `path`（服务端绝对路径）与 `relativePath` 都可以直接填给 `upload_file` 的 `path`。文件名会被清洗（只留基本名、去掉路径分隔符与控制字符、保留中文），并且只能落在暂存目录里；默认上限 64MB，配置项见 `browser.properties`。

完整的方法清单、参数、返回字段、坑与限制都在技能文档里：

> **[`skills/SKILL.md`](skills/SKILL.md)**（装进 DSH 时对应 `.dsh/skills/deepseek-browser-use/SKILL.md`）
>
> **[`skills/cnipa-trademark-register/SKILL.md`](skills/cnipa-trademark-register/SKILL.md)**（中国商标网注册申请的实操手册，数据已脱敏）

那份文档是给智能体读的，也是给人读的参考手册，**以它为准**。

---

### 可选精简响应

请求信封增加 `"responseMode":"compact"` 可减少 `data` 内的重复页签描述、本机截图路径和点击诊断字段；`"diagnostics":true` 保留点击诊断信息。默认完整格式不变，`ok/code/error/msg` 在所有模式下均保留，包括 null。

表单快照显示实时值、只读和禁用状态，密码值脱敏；普通布局缩进折叠。点击回执会短暂观察异步变化，`changed=false` 仅代表尚未观察到变化。单条和批量动作均自动归档截图。

网络请求及响应通过 requestId 关联，支持按 ID 回查特定响应，并明确报告请求体、响应体的截断状态。业务查询返回空记录时，调用方需核实查询条件，不能直接推断金额为零。

## 四、核心概念

### 一个浏览器，多个任务

浏览器是**全进程共享**的：一个 Chrome 进程、一份 profile，任务之间靠**页签**隔离。

- 第一个 `start` 会把浏览器拉起来，之后每个 `start` 只是认领/新开自己的页签；
- `close` 只关掉该任务自己的页签，最后一个任务关闭时才把浏览器收掉；
- **浏览器类型**、有头/无头、profile 目录、可执行文件是**浏览器级别**的属性：与正在运行的那个不一致时，若已经没有任务在跑就按新配置重建（改了配置、换了浏览器都不用重启服务），若还有任务在跑就明确报错，不会把别人的页签弄没；
- `start` 的返回里带 `data.browser`，说明这次到底用的哪个浏览器、哪份 profile：

```json
{"data":{"id":1001,"browser":{"type":"chrome","chrome":true,"userProfile":false,"engine":"chromium",
  "mode":"managed",
  "profileDir":"C:\\Users\\you\\.config\\browseruse\\profiles\\shared",
  "executable":"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "profileDirectory":"Default","headless":true}}}
```

**为什么不能一个任务一个浏览器？** 用户数据目录天生是单例：同一个 `User Data` 目录同时只允许一个
Chrome 进程，第二个进程会把命令行交给已有实例然后自己退出。所以「共用一份 profile（登录态只养一次）」
和「一个任务一个浏览器」只能二选一，这里选了前者 —— 这也正是原来「一个任务一个 profile」那套的替代。

**隔离的单位是页签，不是浏览器。** 每个任务有自己的 `pages` 集合：`get_browser_state` 只列自己的页签，
`switch_tab` 的索引也只在任务自己的页签里数；弹窗、`new_tab` 开出来的新页签会归属到开它的任务。
代价是上下文级设置（Cookie、地理位置、离线、额外请求头、权限）现在是**所有任务共享**的 —— 它们本来就
挂在同一个 `BrowserContext` 上。

### 用哪个浏览器、哪份 profile

`start` 时可以按站点挑浏览器（`browser` 参数，也接受配置项 `browser.type` 作为默认值）：

```shell
# 用本机安装的 Microsoft Edge 跑这一次任务
curl -X POST http://127.0.0.1:10049/playwright/command \
  -H 'Content-Type: application/json' \
  -d '{"method":"start","params":{"browser":"edge"}}'
```

| `browser` | 浏览器 | 引擎 | profile | 什么时候 |
| --- | --- | --- | --- | --- |
| 不传 / `auto` | 本机 Google Chrome，没装则内置 Chromium | Chromium | `browser.profileDir`（默认 `~/.config/browseruse/profiles/shared`） | 默认（推荐） |
| `chrome` | **只**用本机 Google Chrome | Chromium | 同上；打开 `browser.chrome.useUserProfile` 时是用户自己的 `User Data` | 需要 Google 登录之类的站点，或者要确认「本机 Chrome 下的表现」 |
| `edge` | **只**用本机 Microsoft Edge | Chromium | `browser.edge.profileDir`（默认 `~/.config/browseruse/profiles/edge`），走 CDP 那条路 | 站点在 Chrome 下用不了、或者要对照两个浏览器的差异 |
| `chromium` | **只**用内置的那份（发行版内嵌 / 开发态是 Playwright 自带的），完全不碰本机 Chrome | Chromium | `browser.profileDir` | 怀疑本机 Chrome 的扩展或登录态干扰时做对照 |
| `firefox` | Playwright 自带的那份 Firefox（本机装的普通 Firefox 接不上 juggler 协议） | Firefox | `browser.profileDir` | 站点在 Chromium 下用不了，见下一节 |

别名也认（`msedge` / `google-chrome` / `bundled` / `ff` …）；写了不认识的值会直接失败并列出可选值：
`start 失败：无法识别的浏览器类型：safari，可选值：auto / chromium / chrome / edge / firefox`。

**`auto` 与显式取值的区别是「能不能悄悄换一个」**：不传 `browser` 时服务按老规矩退让（本机没有 Chrome
就用内置 Chromium），并把原因写进 `data.browser.note`；显式写了 `chrome` / `edge` 就是「我就要这个」，
没装会直接报错并说清怎么改 —— 否则「明明要了 Edge，结果用 Chrome 跑出另一种页面」这种问题很难查。

**一次只能有一个浏览器。** 类型可以在任务之间切换（把在跑的任务 `close` 掉再 `start` 即可，不用重启
服务），但不能在任务运行中切换：那会连累别的任务的页签，服务会明确报错。想同时用两个浏览器（例如一边
Chrome 一边 Edge），再起一个服务进程，给它们配不同的端口与 profile 目录。

**profile 目录是跟着「浏览器产品」走的**：内置 Chromium 与本机 Chrome 共用 `browser.profileDir`
（同一 Chromium 家族，也是原来的行为，不动已有登录态）；Edge 单独一份 —— Edge 打开 Chrome 的 `User Data`
会把它当外来 profile 处理，而且两家 Cookie 的 App-Bound 加密密钥不同，混用只会得到一份读不出登录态的
目录；Firefox 沿用 `browser.profileDir`（保持原行为，免得把已经养起来的登录态挪走）。所以**换浏览器等于
换一套登录态**，需要登录的站点要重新登一次，登录态随后同样长期留在那份 profile 里。

Edge 那条路**不做 UA 伪装**：内置 Chromium 会把自己伪装成 Chrome（版本与真实 Chrome 不一致），而本机
Chrome 与 Edge 都用各自的 UA —— 所以 `navigator.userAgent` 里出现 `Edg/` 才说明这次真的用上了 Edge。
也不支持「用你自己日常那份 Edge profile」：原因与 Chrome 那边一样（136 起不允许在默认用户数据目录上开
远程调试）。

**Edge 走的是「自己拉进程 + CDP」这条路**（`data.browser.mode` 是 `cdp`，与「用用户自己的 Chrome profile」
是同一条路），不是 Playwright 的 `launchPersistentContext`。原因在沙箱：实测 Edge 配上沙箱时，Playwright
那条路用的 `--remote-debugging-pipe` 会让 Edge 启动即退出，换成 `--remote-debugging-port` 就正常（详见
下面「关于 Chromium 沙箱」）。这条路的代价有两条，用之前先知道：

- `set_credentials`（HTTP 基本认证）用不了：凭据只能在 Playwright 创建上下文时设置。失败信息里会提示改用
  `browser=chrome` / `chromium`。
- 页面触发的下载落到浏览器自己的下载目录，不是 `~/Downloads/broswer`。`pdf` 命令不受影响（路径由服务自己算）。

用本机 Chrome 的好处是它就是用户日常在用的那个浏览器，Google 登录之类的站点不会因为「这是个自动化浏览器」
而拒绝；profile 是所有任务共用的，**agent 第一次登录某个站点后，登录态就留在 profile 里，后续任务不用再登**。

托管 profile 的位置可以改（`browser.profileDir`，默认 `~/.config/browseruse/profiles/shared`）；换目录等于换一套登录态。

> **为什么不能直接用用户自己的 Chrome profile？** Chrome 136 起不允许在**默认用户数据目录**上开启远程调试
> （`DevTools remote debugging requires a non-default data directory`），`--remote-debugging-pipe` 与
> `--remote-debugging-port` 都被拒绝，所以 Playwright / Puppeteer / Selenium 都接不上你日常那份 profile；
> 把 profile 复制到别处也不行 —— Chrome 的 App-Bound 加密会让 Cookie 解不开（实测原 profile 有 1421 个
> Cookie，复制后读到 0 个）。要真的用上用户 profile，需要这台机器允许远程调试默认 profile：给
> `HKLM\SOFTWARE\Policies\Google\Chrome` 加 `DWORD RemoteDebuggingAllowed=1`（需管理员，机器级生效），
> 然后把 `browser.chrome.useUserProfile` 打开 —— 这时服务会自己拉 Chrome、从它的输出里读调试端口，再用
> `connectOverCDP` 接上（`data.browser.mode` 会是 `cdp`，且要求 Chrome 当前没有在运行）。

用户 profile 用不上时（Chrome 正在运行、启动失败等），`start` 会**退回托管 profile**，并把原因放进
`data.browser.note`；`browser.chrome.profileFallback=false` 可以让它直接报错而不是悄悄换一份。

### 换成 Firefox：`browser.engine=firefox`

默认 `chromium`（本机 Google Chrome，没装才退回内嵌 Chromium），行为与以前完全一致；配成 `firefox` 时
改走 `playwright().firefox().launchPersistentContext(...)`，用 Playwright 自带的那份 Firefox 配同一份托管 profile：

```shell
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dbrowser.engine=firefox"
```

**为什么需要这个开关**：有的站点在 Chromium 下用不了。中国商标网统一身份认证
（`sso.cnipa.gov.cn/am/`）的 SPA 会做开发者工具检测（disable-devtool 的 Performance 检测器），
Chromium 会走到空白页或 HTTP 400；同一流程在 Firefox 139 下能正常渲染出登录表单。对照记录见
`playwright-server/target/cnipa-diagnosis/`。

几处差异是引擎能力差异，不是配置错了：

| 差异 | 说明 |
| --- | --- |
| `pdf` 命令 | 只有 Chromium 支持，Firefox 下返回明确的中文失败原因 |
| 用户自己的 Chrome profile | `browser.chrome.useUserProfile` 对 Firefox 无效（那条路是 CDP，Firefox 没有 CDP） |
| 启动参数 | 没有 `--no-sandbox` / `chromiumSandbox` / `--profile-directory`，Firefox 下不传 |
| 本机装的 Firefox | 用不上：Playwright 的 Firefox 是打过补丁的构建（juggler 协议），一般不用配 `browser.firefox.path` |
| UA | 不覆写成 Chrome，UA 就是 Firefox 自己的 |

`start` 的返回里会多一个 `data.browser.engine`，用它确认这次到底跑的是哪个引擎。引擎是浏览器级配置：
改了之后若还有任务在跑，`start` 会报错而不是把别人的页签弄没；空闲时下一次 `start` 会自动重建。
**换引擎 = 换一套 profile 格式，所以要重新登录一次**。

`start` 的回执里还会明确给出「我要的」与「实际用的」：

```json
{ "data": { "id": 1001,
    "requestedBrowser": "firefox", "effectiveBrowser": "firefox", "engineHonored": true,
    "browser": { "type": "firefox", "engine": "firefox", "profileDir": "...", "headless": false } } }
```

**`engineHonored:false` 表示这个服务实例没有按 `browser` 参数切浏览器**（老版本发布包只认 `headless`，会静默忽略 `browser`，回执照样 `ok:true`），此时 `data.engineWarning` 会说明原因。要不要用 `get_config` 看服务端认的 `engine`/`configuredType` 也行。

### profile 目录：默认按端口分开

托管 profile 默认在 `~/.config/browseruse/profiles/` 下。**默认按服务端口派生目录名**（`shared-<端口>`，例如 `shared-10049`），这样同一台机器上跑多个服务实例时各自一份 profile，不会互相抢锁、也不会出现「第二个实例启动卡到超时」：

| 配置 | 效果 |
| --- | --- |
| 不配（默认） | `shared-<端口>`，实例之间互不干扰 |
| `browser.profileDirPerPort=false` | 回到所有实例共用 `shared` 的老行为 |
| `browser.profileDir=<路径>` | 显式指定，优先级最高（要用同一份已登录的会话就指到同一个目录） |

当前解析到哪个目录用 `get_config` 或 `list_tasks` 看。**注意：换端口等于换一套登录态**，想复用旧会话就显式配 `browser.profileDir`。

### 调用追踪日志：每次请求与响应都留档

每一次调用的请求体与响应体都会落到 `<启动目录>/logs/trace/<日期>/` 下，便于排查与追踪：

| 文件 | 内容 |
| --- | --- |
| `steps.log` | 每次调用一行：时间、序号、任务 ID、方法、成败、耗时、关键字段 —— 人看的时间线 |
| `calls.jsonl` | 每次调用一行 JSON：摘要 + 完整请求体 + 批量每一步的成败 —— 给程序过滤 |
| `000001-1001-get_browser_state.json` | 这一次调用的完整请求与完整响应（含整页 `data.text`） |

配置项：`browser.trace.enabled`（默认开）、`browser.trace.dir`、`browser.trace.maxRecordChars`。
写盘失败只留警告，不会影响浏览器命令；日志**不做脱敏、不会自动清理**，里面有敏感值时请自行清理。

客户端这一侧还有两个把请求也留档的客户端：PowerShell 的 `scripts/trace/browse.ps1` 与 Python 的
`client/dsb.py`（Windows 上还有一层薄包装 `client/dsb.cmd`，直接敲 `client\dsb.cmd ...` 即可，不必写
`python` 前缀）。它们都按序号把发出去的请求（`NNN.req.json`）与收回来的响应（`NNN.res.json`）
成对存进 `logs/agent/<会话>/`，并维护一份 `steps.log`。好处是**请求在发送前就落盘**，连服务没起来、
请求根本没发出去这种情况也能看出来。

| | `scripts/trace/browse.ps1` | `client/dsb.py` / `client/dsb.cmd` |
| --- | --- | --- |
| 运行环境 | Windows PowerShell | 任意平台的 Python 3（只用标准库）；`dsb.cmd` 是 Windows 包装 |
| 形态 | 传 `-PayloadFile` 发一次请求 | 子命令式 CLI（`start`/`run`/`batch`/`state`/`upload`/`selftest`…）+ 可 import 的库 |
| 退出码 | 0 业务结果、1 传输失败 | 0 成功 / 1 传输错 / 2 业务失败 / 3 用法错（分得更细，便于写脚本） |
| 适合 | 已有的 PowerShell 排查习惯、一次性排障 | 跨平台、写进 Python 流程、批量与异步任务 |

`dsb.py` 的完整用法见 `client/README.md`，装完先跑一次端到端自检：

```bash
python client/dsb.py --port 10049 selftest --browser firefox
```

### driver 与自愈

**共享的还有 Playwright 的 driver。** `Playwright.create()` 会拉起一个 node 子进程并握手，每次约
350～400ms，还常驻一份内存；但共享浏览器并不需要每个任务一个 driver。所以服务全进程共用一个 driver，
`start` 直接从启动浏览器开始：

| | 每个任务一个 driver | 共享一个 driver（现在） |
| --- | --- | --- |
| 第 2 个任务起的 `start` 耗时 | ~850ms | ~480ms |
| 空闲时的 node 进程 | 每个任务一个 | 1 个 |

代价是 driver 成了单点：它一旦崩，所有任务一起断。所以 `start` 里带了自愈 —— 第一次失败就把共享实例判死、重建一个再试一次（实测 driver 被杀后，下一次 `start` 会在 ~340ms 内重建并成功）。

`close` 只关掉该任务自己的页签，**不会**动共享的 driver，也不会连累别的任务。driver 与（用用户 profile 时）
我们自己拉起来的 Chrome 都由 JVM 退出时的 shutdown hook 收尾；即使进程被硬杀（`taskkill /F`），driver 也会
因为管道关闭自己退出，不会留下孤儿进程。

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

每一步都可以再带一个 `expect` 断言，用来抓住「接口说成功、页面其实没变」：

```json
{ "id": 1001, "method": "commands",
  "params": { "stopOnError": false, "stopOnExpectFailure": true, "commands": [
    { "click_element_by_selector": { "selector": ".ant-modal-confirm .ant-btn-primary", "mode": "mouse" },
      "expect": { "js": "document.querySelectorAll('.ant-modal-confirm').length", "equals": 0 } }
  ] } }
```

断言没过时批次整体失败，但 `data.failed` 仍是 0、`data.expectFailed` 是 1，`msg` 指出是哪一步的断言没过 —— **命令本身执行成功了，是页面状态没变成期望的样子**。

批次跑得久（几十秒以上）就加 `"async": true`：接口立刻返回 `data.jobId`，批次在后台跑，用 `get_job` 取结果、`cancel_job` 取消（取消会在下一步之前生效）。客户端超时不再等于「任务失败」——超时之后批次其实还在服务端继续跑。

### 站点配方（recipes）

把「某个站点上必须这么点」的经验固化成服务端的 JSON 命令序列，`run_recipe` 显式点名执行（**不做任何隐式推断**）：

```json
{ "id": 1001, "method": "run_recipe", "params": { "name": "close-all-modals", "vars": {} } }
```

配方放在 `<启动目录>/recipes/`（`browser.recipes.dir` 可改），格式与写配方的纪律见 [`playwright-server/recipes/README.md`](playwright-server/recipes/README.md)。仓库自带 `close-all-modals`、`query-and-read-table`、`cnipa-list-drafts` 三个。

### 观测与自省

| 想知道什么 | 用什么 |
| --- | --- |
| 有哪些方法 | `list_methods` / `GET /playwright/methods` |
| 现在有哪些任务、浏览器活着没 | `list_tasks` / `GET /playwright/tasks` |
| 服务端生效配置（引擎、profile 目录、降级开关） | `get_config` / `GET /playwright/config` |
| 页面上的 DOM 弹窗是谁、按钮在哪 | `get_modals`（标题、按钮文本、× 与各按钮的坐标） |
| 关掉弹窗 | `close_modal`（真实鼠标点，并校验数量真的减少了） |
| 等异步内容稳定 / 等元素数量达标 | `wait_for_stable` / `wait_for_count` |
| 磁盘越用越多 | `cleanup`（**默认只预演**，`dryRun:false` 才真删） |
| 浏览器没关干净 | `shutdown`（关掉全部任务与共享浏览器，服务进程不退出） |

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

测试里有几条「防漂移」的检查：技能文档必须覆盖命令表里的每个方法、文档里不能出现旧方法名、`browser.properties` 里的 Chromium 修订号必须和当前 Playwright 依赖要求的一致、页签列表必须走任务自己的 `pages`（不能再直接读 `context.pages()`）。

集成测试用的是**临时用户数据目录**（`browser.chrome.userDataDir` 指向临时目录），不会碰你日常那份 Chrome profile。想在这台机器上验证「用用户自己的 profile」这条路，用带开关的冒烟测试（会往真实 profile 里写历史记录，所以默认不跑）：

```shell
mvn test -Dtest=ChromeUserProfileSmokeTest -Dsmoke.chrome.userProfile=true
```

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
│   │   │   ├── ChromeBrowser.java         本机 Google Chrome 与用户 profile 的探测
│   │   │   ├── ChromeLauncher.java        用用户 profile 时自己拉 Chrome 并读出调试端口
│   │   │   └── BundledBrowser.java        内嵌 Chromium 的解压与定位
│   │   └── dom/                            buildDomTree 与结构化文本
│   └── src/main/resources/
│       ├── app.properties                 端口
│       ├── browser.properties             内嵌 Chromium 修订号 + 浏览器/profile/日志/上传配置
│       └── dom/dom_tree/                  DOM 转结构化文本的 JS
├── scripts/package/build-release.mjs      发行版打包脚本
├── scripts/trace/browse.ps1               客户端侧调用留档脚本(与服务端同一套脱敏规则)
├── client/dsb.py                           Python 客户端(CLI + 可 import,只用标准库)
├── client/dsb.cmd                          Windows 薄包装(能直接敲 dsb,不必写 python 前缀)
├── client/README.md                        Python 客户端的用法与退出码约定
├── recipes/*.json                          显式 opt-in 的站点配方(run_recipe 用)
├── skills/SKILL.md                        给智能体读的技能文档(装进 DSH 时放到 .dsh/skills/deepseek-browser-use/)
├── skills/<站点名>/SKILL.md                具体站点的实操手册(例如 cnipa-trademark-register)
└── dist/                                  发行版产物(构建后生成)
```

运行期产物：

```
<启动目录>/data/<任务ID>/<序号>.png      自动截图
<启动目录>/data/<任务ID>/<序号>.txt      同一时刻的页签 + 可交互结构化文本
<启动目录>/data/<任务ID>/shot-N.png      screenshot / get_element_screenshot 手动截图
<启动目录>/logs/trace/<日期>/            每次调用的完整请求与响应(默认脱敏)+ uploads.log
<启动目录>/upload/                       POST /playwright/upload 的暂存文件
```

---

## 七、安全提示

- 服务**没有鉴权**，`execute_js` 能执行任意脚本，`/data/**` 能读到所有截图与页面文本，`POST /playwright/upload` 能往服务端磁盘写文件（只能写进暂存目录、文件名会被清洗，但文件内容不限；`browser.upload.enabled=false` 可关掉这个接口）。
- 默认只监听本机。**对外暴露前必须自己加访问控制**，并且不要把 `/data/**` 直接放到公网。
- `data/`、`logs/trace/` 与 `upload/` 里的文件**都不会自动清理**，长期跑要自己定期清理。
- 追踪日志**默认脱敏**（手机号、18 位身份证号/统一社会信用代码、邮箱、16～19 位长数字 → `***`，可用 `browser.trace.redact` 追加公司名/商标名等自定义规则），但这是**尽力而为**：姓名、门牌号这类按模式认不出来的个人信息不会被掩掉，交付或共享日志前自己过一眼。需要原文时把 `browser.trace.redact.enabled` 设为 `false`。

### 停服务前先关任务（否则会留下孤儿浏览器）

浏览器是**独立进程**：强杀服务（或它的 mvn 进程）不会关掉它启动的浏览器，残留的浏览器会一直占着 profile 目录，下一次 `start` 可能卡到启动超时（默认 60 秒，`browser.launch.timeoutMs`）。

- **规范做法**：先 `close` 掉任务再停服务 —— 关掉最后一个任务时浏览器会跟着退出。
- 已经留下了孤儿：先结束残留的浏览器进程，再重启服务。
- 服务侧也有兜底：Windows 上会自动清掉 profile 里的 `parent.lock` 与 `.startup-incomplete` 这两种残留标记（`parent.lock` 删得掉就说明没有活着的持有者），并且第一次启动失败后会自动重建驱动重试一次 —— 实测这个重试通常就能起来（表现为第一次等 60 秒、第二次 2 秒）。

### 关于 Chromium 沙箱

**默认开启**（`PlaywrightService.chromiumSandbox(BrowserChoice, boolean)`）：在普通桌面环境（Windows / macOS）
上，本机 Chrome、内置 Chromium 与 Edge 的进程沙箱都能正常工作，关掉它换不来任何东西，只换来更差的隔离和
窗口上那条 `You are using an unsupported command-line flag: --no-sandbox. Stability and security will suffer.`
提示条。

| 平台 / 浏览器 | 默认 | 原因 |
| --- | --- | --- |
| Windows / macOS 上的 `chrome`、`chromium`、`auto` | **开启** | 普通桌面环境，沙箱可用；开着之后那条提示条也不会再出现 |
| Windows / macOS 上的 `edge` | **开启** | Edge 走的是 CDP（端口）那条路，沙箱不影响启动。实测：开沙箱 + Playwright 的 `--remote-debugging-pipe` 会让 Edge 启动即退出（`Target page, context or browser has been closed`），换成 `--remote-debugging-port` 就一切正常 —— 问题在「沙箱 + 管道」这个组合，所以解决办法是换启动方式，而不是关沙箱 |
| Linux 上的任何浏览器 | 关闭（与以前一致） | 服务多数跑在容器里、以 root 运行，而 Chrome 以 root 启动时会直接报 `Running as root without --no-sandbox is not supported` 并退出 |

要改就用 `browser.chromium.sandbox`：`true` 始终开启（Linux 桌面上非 root 跑时可以用），`false` 始终关闭
（回到以前那种「带提示条、没有沙箱」的跑法）。代码里还留了一道保险：万一 Edge 被改回 Playwright 的管道
启动，沙箱会自动关掉并打一条警告，而不是让浏览器起不来。

```shell
# 容器里以 root 跑:必须关
java -Dbrowser.chromium.sandbox=false -jar deepseek-browser-use.jar
```

值得说清楚的是：**`--no-sandbox` 是 Playwright 自己加的**，不是本服务传的。Playwright 的
`chromiumSandbox` 默认就是 `false`，它会往命令行里塞 `--no-sandbox`（见驱动的 `_innerDefaultArgs`：
`if (options.chromiumSandbox !== true) chromeArguments.push("--no-sandbox")`），所以只要不显式开启沙箱，
这条标志就一直在。本服务把 `chromiumSandbox` 显式设为上面那张表的结论，两条启动路径（Playwright 的
`launchPersistentContext` 与 CDP 那条自己拉 Chrome 的路）都看同一个开关，不会一半开一半关 —— CDP 那条路
不经过 Playwright，关沙箱时由服务自己补 `--no-sandbox`。服务仅在 Linux 上额外传入
`--disable-dev-shm-usage`（容器里 `/dev/shm` 往往只有 64MB）。

如果提示中出现 `--disable-blink-features=AutomationControlled`，说明正在运行的浏览器仍使用旧版启动参数。项目已移除该参数；更新后端并重新启动浏览器后生效。移除后不再通过此参数隐藏浏览器的自动化特征，部分网站的自动化检测表现可能变化。

这条行为有对应的单元测试（`PlaywrightServiceTest.sandboxFollowsPlatformByDefault` 与
`sandboxCanBeConfiguredBothWays`）盯着：平台默认值被改掉、或者配置项失效，构建就会失败。
真实浏览器上还有两个冒烟测试兜着：`BrowserChoiceSmokeTest`（`-Dsmoke.chromium=true`，内置 Chromium +
沙箱开启）与 `EdgeBrowserSmokeTest`（`-Dsmoke.edge=true`，Edge + CDP + 沙箱开启）。
