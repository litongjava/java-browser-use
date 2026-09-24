---
name: deepseek-browser-use
description: 通过 HTTP 接口驱动真实浏览器完成网页任务：默认使用本机安装的 Google Chrome 与一份共享的持久化 profile（所有任务共用一个浏览器进程，任务之间按页签隔离，登录一次长期有效），用 get_browser_state 取回可交互结构化页面文本与元素索引，按索引点击/输入/勾选/悬停/拖拽/双击，下拉框、上传文件、多标签页、等待、鼠标、截图与 PDF、Cookie 与本地存储、浏览器设置、弹窗与控制台、网络拦截、执行任意 JavaScript、批量指令；每次页面变化自动截图、每次取状态自动落盘截图与结构化文本（截图只留档，非必要不要读图，读文本即可，以节省 token）。当任务需要真实浏览器（JS 渲染、登录态、点击交互）而不是纯 HTTP 抓取时使用。
whenToUse: 需要在真实浏览器里打开网页、阅读页面、填表、点击、勾选、滚动、截图、执行 JS 或提取页面内容时；服务默认地址 http://localhost:10049。读页面只用 get_browser_state 的文本字段，非必要不要读它返回的图片。
---

# DeepSeek Browser Use（HTTP 浏览器自动化）

这是给智能体用的浏览器中间件：一个 tio-boot 服务，用 HTTP 驱动真实的浏览器（默认是**本机安装的 Google Chrome**，配一份**共享的持久化 profile**），把网页变成「可交互结构化文本 + 截图」。

- 默认地址：`http://localhost:10049`（端口来自 `playwright-server/src/main/resources/app.properties` 的 `server.port`）
- 启动服务：`java -jar deepseek-browser-use-<版本>-<平台>.jar`（发行包），或开发态在 `playwright-server` 目录执行 `mvn spring-boot:run`
- **只有一个业务端点**：`POST http://localhost:10049/playwright/command`
- 另有 `GET /playwright/health`（健康检查）与 `GET /data/**`（读取截图与结构化文本）
- 共 93 个方法，`get_browser_state` 是阅读页面的入口，其余方法负责操作与观测

> ## 省 token 铁律：非必要不要读图
>
> `get_browser_state` 以及每个「会改变页面」的方法都会带回 `data.screenshot`（URL）、`data.screenshot_path`（服务器本地路径），`get_browser_state` 还带回 `data.state_file`。**这些字段只是地址，不要顺手把它们读进上下文**：
>
> - **不要**为了「看看页面长什么样」去下载/打开这些图片，也不要交给视觉模型、不要用 图片读取工具读它。图片的 token 消耗比同一次返回的 `data.text` 高几个数量级。
> - 定位与操作所需的全部信息都在文本里：`data.browser_state`（页签）+ `data.text`（每行的 `[index]` 就是元素索引）。**读图不会多给一个索引，只会多烧 token。**
> - 判断「点击到底生效没有」不要靠看图：用点击回执里的 `data.changed`，或用 `diff_dom_text` 比文本差异，都比读图省得多。
> - **只有文本根本表达不了的时候才看图**：验证码 / 二维码 / 扫码登录、图表与曲线、纯图片按钮或图标、以及文本与操作结果明显矛盾、必须肉眼确认的场合。这时优先用 `get_element_screenshot` **只截那一个元素**，而不是把整页大图读进来。
> - 确实需要整页图时再用 `screenshot`（**默认落盘、只回路径**；要内联 base64 得显式传 `inline: true`），并且一次任务里尽量只读一张。

## 一、请求与响应

### 请求格式

所有操作都是同一个端点，请求体是 `{id, method, params}`：

```json
{
  "id": 1001,
  "method": "start",
  "params": { "headless": false }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 见下 | 任务 ID。`start` 时可以传（作为任务标识），不传就自动生成雪花 ID；**其余方法必填** |
| `method` | 是 | 方法名，就是下面接口清单里的名字，也是批量 `commands` 里的键 |
| `params` | 否 | 该方法自己的参数，省略等于空对象 |

- **只支持 POST + JSON 请求体**。参数不再放查询串或表单，也不再需要 URL 编码，中文直接写在 JSON 里即可。
- `id` 是雪花 ID，序列化成字符串返回（`{"data":{"id":"1001"}}`），但请求里写数字或字符串都可以。

```shell
BASE=http://localhost:10049/playwright/command

# 启动（headless=true 无头；false 会弹出真实窗口）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"start","params":{"headless":false}}'
# {"data":{"id":"1001"},"code":1,"ok":true,...}

# 打开页面（注意 data 里带回了自动截图的地址）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"go_to_url","params":{"url":"https://example.com"}}'
# {"data":{"status":200,"seq":1,"screenshot":"/data/1001/1.png","screenshot_path":"..."},...}

# 取浏览器状态（AI 读 browser_state 与 text，元素索引就在 text 里）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"get_browser_state","params":{}}'

# 按索引操作（索引来自上一步的 [index]）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"click_element_by_index","params":{"index":0}}'

# 关闭
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"close"}'
```

### 也可以不手拼 JSON：用现成客户端

手工拼 `-d '...'` 在参数带中文、引号、换行时很容易出错（PowerShell 尤其爱吃掉引号）。仓库里有两个客户端，
都用**子命令/参数文件**的方式传参，并且都会把「请求 + 响应」留档到 `logs/agent/<会话>/`（默认脱敏）：

| 客户端 | 适合 | 例子 |
| --- | --- | --- |
| `scripts/client/dsb.py`（Python 3，只用标准库，跨平台，也可当库 import） | 写进脚本、批量、异步、跨平台 | `python scripts/client/dsb.py --port 10049 start --browser firefox` |
| `scripts/trace/browse.ps1` | 已有的 PowerShell 排查习惯 | `browse.ps1 -PayloadFile req.json -Session t1` |

`dsb.py` 的要点（完整用法见 `scripts/client/README.md`）：

```shell
# 通用选项放子命令前后都行；退出码 0 成功 / 1 传输错 / 2 业务失败 / 3 用法错
python scripts/client/dsb.py --port 10049 health
python scripts/client/dsb.py --port 10049 --id 1001 start --browser firefox --headless
python scripts/client/dsb.py --port 10049 --id 1001 run go_to_url -p url=https://example.com
python scripts/client/dsb.py --port 10049 --id 1001 state --full          # 标题/URL/元素/结构化文本
python scripts/client/dsb.py --port 10049 --id 1001 js @脚本.js --var who=dsb   # 支持 {{变量}} 注入
python scripts/client/dsb.py --port 10049 --id 1001 batch cmds.json --async --wait   # 长批次不受 HTTP 超时限制
python scripts/client/dsb.py --port 10049 --id 1001 recipes --run close-all-modals
python scripts/client/dsb.py --port 10049 upload 图样.jpg                 # 送文件到服务端暂存区
python scripts/client/dsb.py --port 10049 last                            # 重放最近一次响应
```

不确定服务端现在是什么状态（引擎、profile 目录、命令数、配方数）时，先跑一次自检：

```shell
python scripts/client/dsb.py --port 10049 selftest --browser firefox
```

### 响应格式

```json
{ "data": {}, "code": 1, "ok": true, "error": null, "msg": null }
```

- `code=1` / `ok=true` 成功；`code=0` / `ok=false` 失败，原因在 `msg`（中文）。
- **任何参数问题都返回 JSON 错误，不再有 HTTP 500**：缺必填参数得到 `click_element_by_index 失败：缺少参数 index`，方法名不存在得到 `不支持的方法：xxx`，请求体不是合法 JSON 得到 `请求体不是合法 JSON：...`。
- 实例不存在时统一返回 `没有找到对应的浏览器实例：<id>`。

### 响应精简模式（兼容原协议）

请求信封可选 `responseMode: "compact"`，默认保持完整响应。`ok`、`code`、`error`、`msg` 一律保留，包括成功时的 null；精简仅发生在 `data` 内。

```json
{"id":"1001","method":"get_browser_state","responseMode":"compact","params":{"highlight":false}}
```

- 页面状态保留 `tabs`（index 为 0 基、current 标明当前页），省略重复的 `browser_state` 和顶层 URL/标题。
- 保留截图 URL 与文本文件链接，省略本机截图绝对路径。不会省略截图错误。
- 点击回执省略前后 URL、页签数、正文长度及 outerHtml 等诊断字段；请求信封加 `diagnostics: true` 可保留这些字段。
- 批量结果逐条采用相同规则；网站返回的 body、脚本 result 及请求参数不会被递归删字段。
- `count` 为已执行条数；批量中每个成功的页面动作恰好归档一次，末尾取状态仍独立归档。单条与批量经过同一执行收尾。

### 表单状态和动作结果

页面文本中的 value、checked、selected 来自实时 DOM 属性，不只是初始 HTML。只读、禁用状态及 editable 也会输出；select 提供 selected-text。密码值在快照和文本留档中为 `[redacted]`。可见的禁用表单控件可能没有操作索引，但仍输出状态。

属性中的 name、value、状态值不再按 15 字符截断；title、placeholder、alt、aria-label、selected-text 的展示上限为 160 字符。普通布局容器不增加缩进，表单、菜单、表格等语义结构保留缩进，最多 6 层。

动作错误的 `data.errorCode` 区分 ELEMENT_READ_ONLY、ELEMENT_DISABLED、ELEMENT_HIDDEN、ELEMENT_OBSCURED、ELEMENT_NOT_EDITABLE、STALE_ELEMENT、ACTION_TIMEOUT 和 ACTION_FAILED。错误原因来自完整调用日志；没有充分证据的超时只报 ACTION_TIMEOUT。

点击回执会短暂等待异步变化（观察循环上限约 500ms，具体浏览器调用耗时另计）。`data.changeStatus` 为 observed 或 not_observed，`data.observationComplete` 指示探针是否成功，`data.observationWindowMs` 为观察窗口配置。`changed=false` 不表示点击失败，`changed=true` 也不表示查询、缴款等业务成功；应使用目标元素、文本或网络响应确认，禁止仅据此重复提交。

#### 动作的三种执行方式（`mode`）与降级

点击类命令（`click_element_by_index`、`click_element_by_selector`、`click_element_by_text`、`click_element_by_role`、`check_element_by_index`、`uncheck_element_by_index`、`double_click_element_by_index`、`hover_and_click`）和输入类命令（`input_text`、`input_text_by_selector`、`input_text_by_label`）都接受 `mode`，另可传 `timeoutMs` 按次覆盖超时。

| `mode` | 行为 |
| --- | --- |
| 不传 / `auto` | 先按原生方式做，超时或不可操作时**自动改用 JS 派发事件**，成功则回执里写明 `data.mode=js` 与 `data.fallbackReason` |
| `native` | 只用原生方式；失败就是失败，不换方式 |
| `js` | 直接在页面里派发事件（点击派发 mousedown/mouseup/click），**跳过可操作性检查** |
| 输入类的 `fill` / `type` | 只用真实输入（`fill` 覆盖式、`type` 逐键） |

为什么需要降级：有些站点（实测 ant-design 的 Vue SPA，例如商标网上申请系统）在 Playwright 的可操作性检查下会等满超时返回 `[ACTION_TIMEOUT] 等待元素可操作超时`，而元素明明在那里、点上去也有反应——常见于被浮层遮挡、有过渡动画、或在 `pointer-events` 上做了手脚的元素。这时唯一稳的办法就是在页面里直接派发事件。

- **降级不会被伪装成原生成功**：回执 `data.mode` 说明这次实际用了哪种方式，`data.fallbackReason` 给出原生失败的原因。看到 `mode=js` 就要知道「这次没走真实交互」，关键步骤（提交、缴费）建议再确认一次页面状态。
- **JS 设值不等于进了框架模型**：`input_text` 走 JS 设值时会回 `data.committed=false` 与 `data.note`。DOM 上能看到值、但 Vue/React 的 model 里可能是空的——预览页或提交校验会因此报「不能为空」。这类字段要用 `input_text_by_selector`（可见字段默认走真实输入，`committed=true`）重新填一遍。
- 超时默认 5 秒，可用 `browser.action.timeoutMs` 调大；`browser.action.jsFallback=false` 可整体关掉自动降级。

### 网络证据关联

`get_requests` 中每次请求有独立 requestId（雪花 ID 字符串）、requestedAt、method、url、resourceType；有请求体时附 postData、postDataLength、postDataTruncated。响应到达后回填 status 和 respondedAt，网络失败则记录 failure 和 finishedAt。

`get_response_body` 和 `wait_for_response` 返回相同 requestId、对应 request 元数据、respondedAt 和 ageMs。bodyLength 是完整响应字符数，truncated 明示是否截断，bodyAvailable 指示响应体是否可用。重复 URL 应优先通过 requestId 回查，避免把上次查询结果当成本次结果。

接口只报告网站返回的记录和查询条件：空列表或 Total=0 表示该条件下没有记录，不能自动解释为税额、余额等业务金额为零。

## 二、任务与浏览器实例

**一个浏览器，多个任务。** 服务用的是**本机安装的 Google Chrome**（没装才退回内嵌 Chromium）和**一份共享的持久化 profile**（默认在 `~/.config/browseruse/profiles/shared`）：所有任务共用同一个 Chrome 进程与同一份 profile，任务之间靠**页签**隔离。登录一次就留在 profile 里，后续任务不用再登。

> 为什么不再一个任务一份 profile：用户数据目录天生是单例 —— 同一个目录同时只允许一个 Chrome 进程（第二个进程会把命令行交给已有实例然后自己退出），所以「共用一份 profile」和「一个任务一个浏览器」只能二选一。现在的取舍是：**共用浏览器与 profile，页签按任务隔离**。
>
> 想直接用用户日常那份 profile（现成的 Cookie 与登录态）？Chrome 136 起**不允许在默认用户数据目录上开启远程调试**（Playwright / Puppeteer / Selenium 一视同仁），所以默认不走这条路。只有在服务端把 `browser.chrome.useUserProfile` 打开、并且这台机器允许远程调试默认 profile（企业策略 `RemoteDebuggingAllowed=1`，或 Chrome 低于 136）时才会用上，这时 `data.browser.mode` 是 `cdp`。

### 用哪个浏览器：`start` 时选（`browser` 参数）

**一个站点在这个浏览器下用不了，就换一个再试** —— 这是最容易见效的一招，不用改服务端配置。`start` 时传 `browser` 即可：

```json
{"method":"start","params":{"headless":true,"browser":"edge"}}
```

| `browser` 取值 | 用的是什么 | 说明 |
| --- | --- | --- |
| 不传（或 `auto`） | 本机安装的 Google Chrome，没装则内置 Chromium | 默认，行为与以前完全一致 |
| `chrome` | 本机安装的 Google Chrome | 没装会**直接失败**（不会偷偷换成内置的），失败信息里会说怎么改 |
| `edge` | 本机安装的 Microsoft Edge | 同上；Edge 用**它自己一份 profile**，登录态与 Chrome 那份不通用 |
| `chromium` | 内置的那份 Chromium（发行包内嵌；开发态是 Playwright 自带的） | 完全不碰本机 Chrome，适合「怀疑是本机 Chrome 的扩展／登录态干扰」时对照 |
| `firefox` | Playwright 自带的 Firefox | 等价于服务端把 `browser.engine` 配成 `firefox`，见下 |

也可以简写成 `msedge` / `google-chrome` / `ff` 这些别名；写了不认识的值会返回 `start 失败：无法识别的浏览器类型：xxx，可选值：auto / chromium / chrome / edge / firefox`。

**先看返回再干活**：`data.browser.type` 就是这次实际用的浏览器（`auto` 不会出现在这里，已经落成确定值），配合 `data.browser.chrome` / `userProfile` / `mode` / `profileDir` 一起看。`data.browser.note` 非空时说明服务替你做了退让（例如「没有找到本机安装的 Google Chrome，改用内嵌/Playwright 自带的 Chromium」）。

**什么时候换**：站点明确报「浏览器不支持」、页面白屏但换引擎就好、或者需要对照「同一页面在两个浏览器下的差异」时。换了浏览器**不保证**一定有救 —— 但比反复重试同一个浏览器划算。

**注意三件事**：

- **一次只能有一个浏览器**：浏览器与 profile 全进程共用。任务还在跑时 `start` 传一个不同的 `browser` 会返回 `start 失败：浏览器已经在运行（browser=chrome，headless=true，profile=…）：所有任务共用同一个浏览器，不能中途切换浏览器类型…`。要换就先 `close` 掉在跑的任务，再 `start`；**不用重启服务**。
- **换浏览器等于换一套登录态**：`chrome`／`chromium` 共用 `browser.profileDir` 那一份；`edge` 用它自己那份（`~/.config/browseruse/profiles/edge`）；`firefox` 的 profile 格式与 Chromium 系不通用。所以换过去之后，需要登录的站点要重新登一次（登录态会留在那份 profile 里，后续任务不用再登）。
- **`edge` 走的是「自己拉进程 + CDP」这条路**（`data.browser.mode` 是 `cdp`，和用用户自己的 Chrome profile 一样）：这样才能带着沙箱跑（实测 Edge 配上沙箱时，Playwright 的管道启动会让它启动即退出）。随之而来的差别有两条：`set_credentials` 在这条路上不可用；页面触发的下载落到浏览器自己的下载目录（不是 `~/Downloads/broswer`，`pdf` 命令不受影响，它自己算路径）。
- **`edge` 这条路用的是浏览器自己的 UA**（含 `Edg/`）。内置 Chromium 会伪装成 Chrome 的 UA，本机 Chrome 与 Edge 都用各自的 UA —— 所以 UA 里出现 `Edg/` 才说明这次真的用上了 Edge。

### 用哪个引擎：Chrome 还是 Firefox（`browser.engine`）

默认 `chromium`：本机安装的 Google Chrome，没装才退回内嵌/Playwright 自带的 Chromium —— 与以前完全一致。服务端把 `browser.engine` 配成 `firefox` 时改走 `playwright().firefox().launchPersistentContext(...)`：Playwright 自带的那份 Firefox（版本由 Playwright 依赖决定），配同一份托管 profile。**`start` 时传 `browser=firefox` 是同一件事**（不用改服务端配置），引擎与浏览器类型的关系见上一节。

**什么时候需要切**：有的站点在 Chromium 下根本用不了。中国商标网统一身份认证（`sso.cnipa.gov.cn/am/`）的 SPA 会做开发者工具检测（disable-devtool 的 Performance 检测器），Chromium（本机 Chrome 与内嵌 Chromium 都一样）会走到空白页或 HTTP 400，而 Firefox 139 下同一流程能正常渲染出登录表单。切过去之后 `start` 的返回里 `data.browser.engine` 是 `firefox`、`data.browser.chrome` 与 `data.browser.userProfile` 都是 `false`、`mode` 是 `managed`。

**注意这几处与 Chromium 不同**（是引擎能力差异，不是配置错了）：

| 差别 | 说明 |
| --- | --- |
| `pdf` 命令 | 只有 Chromium 支持，Firefox 下会直接返回失败原因（提示换成 Chromium 系的浏览器：`browser=chrome` / `chromium` / `edge`），不要以为是自己参数写错了 |
| 用户自己的 Chrome profile | `browser.chrome.useUserProfile` 与 Firefox 无关，打开也不会被 Firefox 用上 |
| 启动参数 | 没有 `--no-sandbox`／`chromiumSandbox`／`--profile-directory` 这些 Chromium 概念，Firefox 下不传 |
| 本机装的 Firefox | 用不上：Playwright 的 Firefox 是打过补丁的构建（走 juggler 协议），`browser.firefox.path` 一般不需要配 |
| UA | **不覆写成 Chrome**：Firefox 的价值就在于它是 Firefox，UA 里会是 `Firefox/<版本>` |

`browser.engine` 是**浏览器级**配置：改了它之后，如果还有任务在跑，`start` 会明确报错而不是把别人的页签弄没；没有任务在跑时，下一次 `start` 会自动把旧浏览器收掉、按新引擎重建。登录态跟着 profile 走，**换引擎会换一套登录态**（Chromium 与 Firefox 的 profile 格式不通用），所以切过去之后需要重新登录一次。

- `id` 就是任务标识。`start` 时自己指定（例如用业务里的任务号），不传则自动生成雪花 ID。
- 每个任务有**自己的一组页签**：`get_tabs` / `data.tabs` / `switch_tab` 的索引只在这个任务的页签里数，别的任务的页签看不见也点不到。弹窗与 `new_tab` 开出来的新页签归开它的任务。
- **同一个 `id` 不能重复 `start`**：会返回 `start 失败：该 id 已经有正在运行的浏览器实例：1001，请先调用 close，或换一个 id`。要重来就先 `close` 再 `start`。
- `close` 只关掉这个任务的页签；**最后一个任务关闭时**浏览器才会一起退出（profile 的占用也随之释放）。
- 实例本身只在内存里，服务重启后 id 失效（登录态在 profile 里，仍在）。
- `start` 的返回里有 `data.browser`：`type`（这次实际用的浏览器：`chrome`／`edge`／`chromium`／`firefox`）、`chrome`（是否用上了本机 Chrome）、`userProfile`（是否用上了用户自己的 profile）、`engine`、`mode`（`managed` = Playwright 的托管 profile，`cdp` = 服务自己拉进程再接上：用户自己的 Chrome profile 与 `edge` 都是这条）、`executable`、`profileDir`、`profileDirectory`、`headless`，以及服务替你做了退让时的 `note`。**看到 `userProfile=false` 就说明这次不是用户日常那份登录态**，需要登录的站点要重新走登录流程或请人协助；**看到 `type` 不是你要的那个，先看 `note`**（见上节「用哪个浏览器」）。
- 服务没有鉴权，默认只监听本机；对外暴露前必须自行加访问控制。

> 登录态不跟着任务 ID 走，而是跟着共享 profile 走：换任务、换 id 都不影响。`userProfile=false` 时用的是托管 profile（`~/.config/browseruse/profiles/shared`），那份 profile 里的登录态是 agent 自己养起来的 —— 第一次登录之后同样会长期保留。

## 三、get_browser_state：页签信息 + 元素索引

`get_browser_state` 是阅读页面的唯一入口。它在页面里执行 buildDomTree，把 DOM 转成 AI 可读的结构化文本，缓存本次快照，同时**截一张图**并把文本落盘（见第四节）。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 任务 ID |
| `highlight` | 否 | 是否在页面上画高亮框，默认 `true`（便于人工观察，不影响返回文本） |
| `viewportExpansion` | 否 | 视口外扩像素，默认 `0`；想一次拿到首屏之外的更多元素就调大（例如 `1000`） |

返回字段：

| 字段 | 说明 |
| --- | --- |
| `data.url` / `data.title` | 当前页的 URL 与标题 |
| `data.browser_state` | 页签信息文本块，格式见下 |
| `data.text` | 给模型读的可交互结构化文本，每行形如 `[index]<tag attr='value'>文本/>` |
| `data.tabs` | 页签数组：`index`（**0 基**）、`url`、`title`、`current` |
| `data.pixels_above` / `data.pixels_below` | 视口上方/下方还有多少像素没进快照 |
| `data.viewport_height` / `data.page_height` | 视口高度与整页高度 |
| `data.seq` / `data.screenshot` / `data.screenshot_path` / `data.state_file` | 本次落盘的截图与结构化文本，见第四节。**前三个只是截图地址：非必要不要读图**，见开头的省 token 铁律；真正要读的是 `data.text` |

### 页签信息文本块

`data.browser_state` 固定是这种格式，编号**从 1 开始**：

```
Browser tab: 1, Title: "哔哩哔哩 (゜-゜)つロ 干杯~-bilibili", URL: "https://www.bilibili.com/".
Browser tab: 2, 
current tab is: 1
```

- 标题和 URL 都取不到的页签（新建但还没加载完的空白页）只输出 `Browser tab: N, `。
- 最后一行 `current tab is: N` 指出当前正在操作的页签。
- **注意两套编号**：这里的 `N` 是 **1 基**，而 `data.tabs[].index` 与 `switch_tab`/`close_tab` 的 `pageIndex` 仍然是 **0 基**。看到 `current tab is: 2` 要切过去，用 `pageIndex: 1`。

### data.text 怎么读

`data.text` 是给模型读的页面文本，每行形如 `[index]<tag attr='value'>文本/>`。真实样例（百度首页；首尾两行是 demo 代码打印的边界标记，接口不输出）：

```
[Start of page]
		[0]<a >新闻/>
		[8]<a />
			[9]<a name='tj_briicon'>更多/>
		[12]<a name='tj_login'>登录/>
							[14]<input name='wd' placeholder='肖战连续两天请剧组喝冰饮'/>
							[15]<input type='submit' value='百度一下'/>
							[16]<a >AI搜索已支持「DeepSeek-R1」最新版 立即体验/>
								[17]<div > />
						[19]<li />
							[20]<a > 0 以历史的纵深感做好今天的工作/>
						[21]<li >新/>
							[22]<a > 5 亲叔叔炮轰宗馥莉：她从不与宗家往来/>
			[39]<div >辅助模式/>
				[40]<div />
[End of page]
```

**每一条都容易误判**：

| 现象 | 含义 |
| --- | --- |
| `[Start of page]` / `[End of page]` | 只是首尾标记，不是页面元素。**接口本身不输出这两行**（`data.text` 的第一行就是第一个元素），样例里出现它们是因为样例来自 demo/测试代码自己 `println` 的边界标记 |
| 行首缩进（制表符） | 语义层级，普通布局容器折叠，最多 6 层；不是原始 DOM 深度：`[20]` 缩进在 `[19]<li />` 里，说明这条新闻链接属于那个 `li`；`[17]<div>` 缩进在 `[16]<a>` 里 |
| `[index]` | 元素索引，所有按索引操作的方法都用它。**编号不连续**（`[12]` 后面直接是 `[14]`），因为只有 buildDomTree 判定为可交互的节点才有索引，不要把它当行号或「第 n 个元素」 |
| 行尾 `/>` | 只是格式化后缀，**不代表自闭合**：`[0]<a >新闻/>` 里的 `新闻` 就是链接文字 |
| `<a />`、`<span />`、`<div />`（没有文字） | 没有可见文字的节点：图标、装饰、空容器。它**有索引**就说明可以点 |
| `[17]<div > />`（尖括号里只有一个空格） | 该节点没有任何被保留的语义属性，文本也只有一个空格 |
| 属性集合 | 只保留语义信息，并补充表单实时属性：有 `type`、`placeholder`、`aria-label`、`title`、`name`、`value`（按钮类）。**没有 `id`、`class`、`href`、`style`**，所以从快照里看不出链接地址、也认不出 CSS 类名 |
| `value='百度一下'` | 按钮类 input 上的按钮文字；输入框上出现的 `value` 是**当前值**（实测必应搜索框输入后快照里出现 `value='Mac Mini M4'`），包含脚本修改后的实时属性；密码统一显示 `[redacted]`。也可用 `get_element_value` 单独读取 |
| 文本顺序 | DOM 顺序；`[21]<li >新/>` 里的 `新` 是角标文字，真正的链接文字在它内部的 `[22]` 里 |
| 元素在快照里找不到 | **不可见的元素不进快照**。实测百度首页的真实搜索框是 `INPUT#kw name=wd`，但它 `offsetParent === null`（被新的 AI 输入框取代而隐藏），快照里就没有它，只剩 `[16]<button >百度一下/>`。这种元素用选择器类方法也会失败（隐藏元素不满足可操作性），只能用 `execute_js` 设值，见第十节第 16 条 |

其它要点：

- 有索引的是 buildDomTree 判定为可交互的节点（含带 `onclick`、`cursor:pointer` 的 `div`/`span`）；普通纯文本容器的文字会直接出现在文本里，但没有索引。
- 要按 `id`/`class`/`href` 定位，用 `click_element_by_selector`、`input_text_by_selector`，或用 `execute_js` 取（例如 `document.querySelectorAll('a')[0].href`）。**只想看这些属性就先用 `get_interactive_map`**：它按当前快照的 xpath 一次回查全部元素，直接给出 `index → tag/id/className/href/name/text` 的映射，省掉一堆 `execute_js`。
- `index` 直接用于：`click_element_by_index`、`input_text`、`upload_file`、`get_dropdown_options`、`select_dropdown_option`、`double_click_element_by_index`、`hover_element_by_index`、`focus_element_by_index`、`check_element_by_index`、`uncheck_element_by_index`、`type_text`、`drag_element_by_index`、`get_element_text`、`get_element_html`、`get_element_value`、`get_element_attribute`、`get_element_box`、`is_visible`、`is_enabled`、`is_checked`、`clear_text`、`hover_and_click`、`get_element_screenshot`、`screenshot`。
- **没有快照时的差别**：`click_element_by_index`、`input_text`、`upload_file`、`get_dropdown_options`、`select_dropdown_option` 会退化成 CSS 选择器顺序索引；读取/状态类方法则直接报错 `索引越界: N,当前没有页面快照,请先调用 get_browser_state 获取元素索引`。
- 索引越界返回 `xxx 索引越界: N`；每次元素操作等待上限默认 **5 秒**（`browser.action.timeoutMs` 可调），部分按索引点击有恢复重试，总耗时可能更长。只读输入立即报错；超时本身不证明快照过期，需看 `data.errorCode`。
- **索引失效会自动补救两级**：先等 300 毫秒用同一个 xpath 重试（挡住动画/异步渲染的抖动），再重取一次临时快照，按「同 tag + 同文本且全页唯一」把元素找回来。都失败才返回对应错误，**不会乱点别的元素**。补救用的临时快照不会覆盖当前快照，所以索引不会悄悄漂移。
- 快照是**快照**：点击、跳转、异步渲染之后索引会重算，必须重新调用 `get_browser_state`；沿用旧索引可能得到越界或操作超时。

## 四、截图与可交互结构化文本（data/<id>/）

每次浏览的页面发生变化，服务都会自动留档，落在进程工作目录下的 `data/<id>/` 里：

| 时机 | 产物 |
| --- | --- |
| 「会改变页面」的方法执行成功 | `data/<id>/<seq>.png` 一张截图 |
| 每次 `get_browser_state` | `data/<id>/<seq>.png` 截图 **+** `data/<id>/<seq>.txt` 同名的可交互结构化文本 |

- `seq` 是**每个任务独立的自增序号，从 1 开始**：1.png、2.png、3.png、4.png…… 一对 `.png` / `.txt` 的序号相同，表示是同一时刻的页面。
- 这两个文件都可以直接 GET：`GET http://localhost:10049/data/<id>/<seq>.png`、`GET .../<seq>.txt`。视觉模型可以按 URL 取图，**但非必要不要取**（见开头的省 token 铁律）：截图只是留档，智能体读页面请读 `data.text` 或同序号的 `.txt`。
- 响应里的字段：`data.seq`、`data.screenshot`（URL，如 `/data/1001/3.png`）、`data.screenshot_path`（服务器本地绝对路径）、`data.state_file`（URL，只有 `get_browser_state` 有）。截图失败不会让方法失败，原因在 `data.screenshot_error`。
- `<seq>.txt` 的内容是「页签文本块 + 空行 + 可交互结构化文本」，也就是 `data.browser_state` 加 `data.text`，方便事后离线复看某一步的页面。
- 哪些方法算「会改变页面」：导航类（`navigate`、`go_to_url`、`go_back`、`go_forward`、`reload`）、点击与交互类、滚动与鼠标类、页签类、等待类、`execute_js` 与部分设置类。纯读取类（`get_url`、`get_cookies`、`is_visible`……）不截图，否则每读一个值就多一张一模一样的图。
- 截图前会尽力等页面进入 DOMCONTENTLOADED（最多 1.5 秒），等不到也照常截图，不会因为等待失败丢掉这一张。
- 批量调用时**每一步的结果里都有它自己那一步的截图**，所以一次批量请求就能拿到整段操作的页面变化历史。
- 这些文件不会自动清理，`data/` 已经加进 `.gitignore`；不需要时直接删目录即可。

### 调用追踪日志（`logs/trace/`）

除了页面留档，**每一次调用本身**也会被服务记一份到本地：请求体与响应体原样落盘，便于事后排查「我到底发了什么、服务回了什么」。每个自然日一个目录 `logs/trace/<日期>/`：

| 文件 | 内容 | 用途 |
| --- | --- | --- |
| `steps.log` | 每次调用一行：时间、序号、任务 ID、方法、成功与否、耗时、关键字段 | **人看的时间线**，一眼看出第几步开始不对 |
| `calls.jsonl` | 每次调用一行 JSON：摘要 + 完整请求体；批量 `commands` 还会把每一步的成败单列出来 | 程序过滤（按 id/method 找某几次调用） |
| `000001-1001-get_browser_state.json` | 这一次调用的**完整**请求与**完整**响应（含整页 `data.text`） | 要看原始报文、要复现某一步时的唯一凭据 |

要点：

- 排查顺序建议：先看 `steps.log` 定位出问题的那一步 → 再用同名序号的 `.json` 看完整报文 → 最后按里面的 `data/<id>/<seq>.txt` 与 `.png` 看当时页面。三者序号可以互相印证。
- 写盘失败（磁盘满、目录没权限）只留一条警告，**不会让浏览器命令失败**，所以不能把它当成「命令成功」的证据；反过来，命令失败也不代表日志写失败。
- 开关与服务端配置：`browser.trace.enabled`（默认开）、`browser.trace.dir`（默认 `<启动目录>/logs/trace`）、`browser.trace.maxRecordChars`（单条完整报文上限，默认 800 万字符）。
- **默认脱敏**（`browser.trace.redact.enabled`，默认开）：手机号、18 位身份证号/统一社会信用代码、邮箱、16～19 位长数字在落盘前会被替换成 `***`，掩码可用 `browser.trace.redact.mask` 改。`browser.trace.redact` 可追加自定义正则（逗号分隔），例如把公司名、商标名也掩掉。
- 脱敏是**尽力而为**：按模式匹配，不认识的个人信息（姓名、门牌号、账号）不会被掩掉，日志也**不会自动清理**。交付或共享 `logs/trace/` 前自己过一眼。
- `POST /playwright/upload` 的落盘记录另写在同一天的 `uploads.log`（文件名、大小、SHA-256、落盘路径），只记元数据、不记文件内容。
- `start` 的返回里带 `data.browser.trace`（日志目录、是否开启、是否脱敏）与 `data.browser.upload`（暂存目录、开关、单文件上限），客户端-服务器模式下照着它就知道该去哪清理。

## 五、智能体的交互循环

1. `start` 一次，自己指定或用返回的 `id`（整个任务复用同一个实例）。
2. `go_to_url`（或 `navigate`）打开目标地址。
3. `get_browser_state` 取回 `data.browser_state`（页签）与 `data.text`（每行的 `[index]` 就是可交互元素索引）。
4. 用索引方法执行动作：`click_element_by_index`、`input_text`、`check_element_by_index`、`select_dropdown_option`……
5. **页面只要发生变化（点击、跳转、异步渲染、弹窗）就回到第 3 步重新取一次**；没变化才可以继续用上一轮索引。判断「刚才那一下到底有没有变化」用 `diff_dom_text`，比重新读整页省 token。
6. 需要判断「有没有加载出来」时用 `wait_for_element` / `wait_for_text` / `wait_for_url` / `wait_for_load`，不要用固定 `wait`；不确定要等多久、只知道「等它忙完」时用 `wait_for_idle`。
7. **默认不看图**：`data.screenshot` 只是截图地址，非必要不要读进上下文（见开头的省 token 铁律）。判断页面变化用 `data.changed` 或 `diff_dom_text`；只有验证码、二维码、图表、纯图片元素这类「文本表达不了」的场景才按需取图，优先 `get_element_screenshot`（只截那一个元素），要整页图才用 `screenshot`。两个命令**默认都落盘只回路径**，确实要内联 base64 才传 `inline: true`。
8. 需要读接口返回的 JSON 时用 `wait_for_response`（等新响应）或 `get_response_body`（回看最近的响应），不要只靠 `get_requests` 的状态码猜。
9. 想一次拿到页面当前状态（url/标题/页签/弹窗/是否还在加载）用 `get_page_snapshot`，不要连发五六次调用。
10. **填完一屏表单后先用 `get_form_state` 对一遍**：一次读回每个控件的标签/当前值/是否可见/是否禁用/校验错误，比逐个 `get_element_value` 省调用，也更容易发现「值填了但没进模型」的字段。
11. 遇到登录、验证码、短信码、扫码、滑块验证、点击验证等无法自行处理的环节，必须主动请求人类帮助，暂停依赖该环节的操作并保留浏览器现场；确认人工处理完成后再继续，见第九节。
12. **能用批量就用批量**：第 3～7 步可以合并成一次 `commands` 请求（动作 + 读取混排，末尾放 `get_browser_state`），一次推理拿到全部结果，别一个动作往返一次。见第七节。
13. 任务完成输出结论，最后 `close`；等待人类协助时任务尚未完成，不要关闭浏览器。

## 六、方法清单

下面的名字就是 `method` 的取值，也是 `params` 里的参数名。

### 实例生命周期

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `start` | `id`(可选), `headless`(bool，默认 `true`), `browser`(可选，见下) | 开始一个任务；返回 `data.id`，以及 `data.browser`（这次用的浏览器与 profile，见第二节）。传 `id` 就把它当任务 ID。第一个任务会把共享的浏览器拉起来，之后的任务只是各领自己的页签 |
| `close` | `id` | 关掉**这个任务自己的页签**，别的任务不受影响；**最后一个任务关闭时**浏览器才一起退出（登录态留在 profile 里，下次还在） |

### 导航与页面信息

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `navigate` | `id`, `url` | 返回 `data.status` |
| `go_to_url` | `id`, `url` | 与 `navigate` 等价 |
| `get_browser_state` | `id`, `highlight`, `viewportExpansion` | 见第三节 |
| `get_page_snapshot` | `id`, `includeConsole`(bool), `includeRequests`(bool), `requestFilter` | 一次拿到页面状态：`data.url`、`data.title`、`data.tabs`、`data.dialog`、`data.loading`；`includeConsole=true` 再带 `data.logs`/`data.errors`，`includeRequests=true` 再带 `data.requests`（可用 `requestFilter` 按 URL 子串过滤）。替代六次单独调用，**不含 DOM 快照文本** |
| `diff_dom_text` | `id`, `highlight`, `viewportExpansion` | 重新执行一次 buildDomTree，与上一次快照按行做差集：`data.changed`、`data.added`、`data.removed`（各最多 200 行）、`data.first`。判断「页面到底动没动」比重读整页省 token。**不产生新的截图/文本文件** |
| `get_interactive_map` | `id` | 按当前快照的 xpath 回查全部元素，返回 `data.elements`：`index`、`tag`、`xpath`、`id`、`className`、`href`、`name`、`text`。补上快照里没有的 `id`/`class`/`href`；需要先有快照 |
| `get_form_state` | `id`, `selector`(可选，默认整页), `includeHidden`(bool，默认 false), `max`(可选，默认 200) | 一次读回整张表单：`data.fields`（每项含 `label`/`id`/`name`/`type`/`value`/`checked`/`disabled`/`readOnly`/`required`/`visible`/`invalid`/`error`/`placeholder`）、`data.count`、`data.errorCount`、`data.errors`（`label`+`error` 清单）。**密码字段的值一律回 `[redacted]`** |

`get_form_state` 是用来「填完一屏后对一遍」的：

- 一次调用代替十几个 `get_element_value`/`is_visible`/`get_element_attribute`，尤其适合提交前的自检。
- `data.errors` 直接给「哪个字段、错在哪」，比去猜 `.ant-form-item-explain-error` 之类的站点类名稳。
- `invalid` 来自 CSS 伪类与 `aria-invalid`，`error` 取的是该字段所在的表单行里的错误文本。
- **注意「值在 DOM 里但不在框架模型里」的坑**：`value` 读的是实时 DOM 属性，值写进去了就会显示出来；如果预览/提交仍然说「不能为空」，说明写值的方式没进框架的 model（见前面 `mode` 一节），要用 `input_text_by_selector` 重填，而不是继续加值。
- 隐藏控件（`type=hidden`、`display:none`）默认不返回，`includeHidden: true` 才带上——真实站点上的 file input 常常就是这类。
| `go_back` | `id` | 返回 `data.status`、`data.url`；无历史时 `msg=无法后退：没有可用历史记录` |
| `go_forward` | `id` | 同上；`about:blank` 这类没有 HTTP 响应的页面 `status` 为 0 |
| `reload` | `id` | 返回 `data.status` |
| `get_url` | `id` | 返回 `data.url` |
| `get_title` | `id` | 返回 `data.title` |
| `wait` | `id`, `seconds`（必填，秒） | 固定等待；缺 `seconds` 得到 `wait 失败：缺少参数 seconds` |

### 元素交互（按索引）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `click_element_by_index` | `id`, `index`, `mode`(可选), `timeoutMs`(可选) | 单击；返回点击回执（见下） |
| `double_click_element_by_index` | `id`, `index`, `mode`(可选) | 双击；返回点击回执 |
| `hover_element_by_index` | `id`, `index` | 悬停 |
| `focus_element_by_index` | `id`, `index` | 聚焦 |
| `check_element_by_index` | `id`, `index`, `mode`(可选) | 勾选复选框/单选框 |
| `uncheck_element_by_index` | `id`, `index`, `mode`(可选) | 取消勾选 |
| `type_text` | `id`, `index`, `text` | 逐字输入，**不清空**原有内容 |
| `input_text` | `id`, `index`, `text`, `mode`(可选) | 覆盖式填充（等价 `fill`，会清空）；`text` 必填，**清空请用 `clear_text`** |
| `drag_element_by_index` | `id`, `index`, `targetIndex` | 把第 index 个元素拖到第 targetIndex 个元素 |
| `upload_file` | `id`, `path`, `index` 或 `selector`(二选一), `timeoutMs`(可选) | `path` 是**服务器本地路径**：绝对路径直接用，相对路径按服务端暂存目录解析（见下面的「上传文件」） |
| `send_keys` | `id`, `keys` | 键盘按键：`Enter`、`Tab`、`Control+A`、`ArrowDown` |
| `key_down` | `id`, `keys` | 按住不放（配合 `key_up`） |
| `key_up` | `id`, `keys` | 松开按键 |
| `get_dropdown_options` | `id`, `index` | 返回 `data.options`，选项文本数组 |
| `select_dropdown_option` | `id`, `index`, `text` | 按**选项文本**（label）匹配 |
| `scroll` | `id`, `down`(bool), `numPages`(int), `index`(可选) | 不传 `index` 时按 `PageDown`/`PageUp` 翻页；传 `index` 时对第 index 个元素 `scrollBy` |
| `scroll_to_text` | `id`, `text` | 按 `text=` 定位并滚动到可见；找不到会等满 30 秒后失败 |

**点击回执**：`click_element_by_index`、`double_click_element_by_index`、`click_element_by_selector`、`click_element_by_text`、`click_element_by_role`、`hover_and_click` 都会在 `data` 里返回点击前后的状态对比：

| 字段 | 说明 |
| --- | --- |
| `data.urlBefore` / `data.urlAfter` | 点击前后的 URL |
| `data.tabCountBefore` / `data.tabCountAfter` | 点击前后的页签数（变多说明弹出了新页签） |
| `data.textLengthBefore` / `data.textLengthAfter` | 点击前后 `document.body.innerText` 的长度（粗略反映内容变化） |
| `data.changed` | URL、页签数或页面指纹发生变化则为 `true`；指纹涵盖正文、表单实时值和 DOM 结构状态 |
| `data.hint` | `changed=false` 时出现：动作已执行，但观察窗口内尚未发现变化；不代表点击失败 |
| `data.tag` / `data.text` / `data.outerHtml` | **只有** `click_element_by_text`、`click_element_by_role`、`hover_and_click` 返回：真正命中的元素是什么 |
| `data.seq` / `data.screenshot` / `data.screenshot_path` | 这一步的自动截图，见第四节 |

`ok=true` 只代表动作没抛异常，**不代表点中了东西**：实测点悬浮菜单时文本命中的是纯文本容器，方法返回成功但页面毫无变化。`data.changed` 仅表示观察到变化，不证明业务操作成功；为 false 时应等待目标条件。

#### 上传文件（`upload_file` 与 `POST /playwright/upload`）

`upload_file` 的 `path` 是**服务端**能打开的路径，不是 URL。`index` 与 `selector` 传一个即可，**优先用 `selector`**：真实站点上的 file input 基本都是隐藏的（`display:none` 或 `visibility:hidden`），既没有操作索引，也要先想办法把它显出来才能用索引定位——用选择器就不需要这一套。

```bash
# 把 file input 的选择器喂给它（不需要元素可见，也不需要索引）
{"id":"1001","method":"upload_file","params":{"selector":"#form_item_imageAttJson","path":"图样.jpg"}}
```

**客户端-服务器模式**（智能体在客户端、浏览器在服务端）下，客户端本地文件服务端读不到，先把文件 POST 到暂存接口，再用回执里的路径：

```bash
# 1. 上传（三种写法等价，任选）
curl -F "file=@图样.jpg"            http://<服务端>:10049/playwright/upload
curl --data-binary @图样.jpg "http://<服务端>:10049/playwright/upload?filename=图样.jpg"
curl -H "Content-Type: application/json" \
     -d '{"filename":"图样.jpg","contentBase64":"/9j/4AAQ..."}' http://<服务端>:10049/playwright/upload

# 回执：{"ok":true,"data":{"filename":"图样.jpg","path":"<服务端暂存目录>/图样.jpg",
#                        "relativePath":"图样.jpg","size":18363,"sha256":"...","existed":false}}

# 2. 把 path（或 relativePath）回填给 upload_file
{"id":"1001","method":"upload_file","params":{"selector":"#form_item_imageAttJson","path":"图样.jpg"}}

# 辅助接口：GET /playwright/upload 列出暂存文件；DELETE /playwright/upload?name=图样.jpg 删掉一个
```

- 文件字段名默认 `file`，可用 `?field=xxx` 改；multipart 之外的两种写法见上。文件名会被清洗（只留基本名、去掉路径分隔符与控制字符、保留中文），并且**只能落在暂存目录里**，`../` 这类路径会被拒绝。
- 回执字段：`data.filename`、`data.path`、`data.relativePath`、`data.size`、`data.sha256`（客户端可据此核对是否传对了文件）、`data.target`（`index=3` 或 `selector=...`）。
- 单文件上限默认 64MB（`browser.upload.maxBytes`），同名默认覆盖（`browser.upload.overwrite=false` 则自动改名 `a-1.jpg`）。暂存目录默认 `<启动目录>/upload`，`start` 的返回里能看到实际路径。
- 文件不存在时错误信息会直接告诉你去 `POST /playwright/upload`，不要再去猜路径。
- 上传动作也会写进追踪日志的 `uploads.log`（只记元数据，不记内容）。

### 读取元素信息与状态（按索引）

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `get_element_text` | `id`, `index` | `data.text`（innerText） |
| `get_element_html` | `id`, `index` | `data.html`（innerHTML） |
| `get_element_value` | `id`, `index` | `data.value`（输入框的 value） |
| `get_element_attribute` | `id`, `index`, `name` | `data.value`（属性值，可为 null） |
| `get_element_count` | `id`, `selector` | `data.count`（CSS 选择器匹配数量，不需要索引） |
| `get_element_box` | `id`, `index` | `data.x/y/width/height`；元素不可见时失败 |
| `is_visible` | `id`, `index` | `data.visible` |
| `is_enabled` | `id`, `index` | `data.enabled` |
| `is_checked` | `id`, `index` | `data.checked` |

### 按选择器 / 文本 / 语义定位（快照过期时的兜底）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `click_element_by_selector` | `id`, `selector`, `mode`(可选), `timeoutMs`(可选) | CSS 选择器取第一个匹配并点击；**点完如果弹出新页签会自动切过去并带到最前**；返回点击回执 |
| `input_text_by_selector` | `id`, `selector`, `text`, `mode`(可选) | 覆盖式填充。**可见字段默认走真实输入**（进框架模型），这是「值填了但预览/校验说为空」时的正解 |
| `click_element_by_text` | `id`, `text`, `mode`(可选) | 按可见文本定位。**先向上找最近的可点击祖先**（`a`/`button`/`[role=button]`/`[onclick]`），找不到才点文本节点本身；返回真正命中的 `data.tag`/`data.outerHtml` 与点击回执 |
| `click_element_by_role` | `id`, `role`, `name`(可选), `mode`(可选) | 无障碍角色，`role` 如 `button`、`link`、`textbox`、`checkbox`；返回命中的 `data.tag`/`data.outerHtml` 与点击回执 |
| `input_text_by_label` | `id`, `label`, `text`, `mode`(可选) | 按表单标签 / `aria-label` 定位输入框 |
| `clear_text` | `id`, `index`(可选), `selector`(可选) | 清空输入框，返回 `data.value`（清空后的值）。`input_text` 的 `text` 必填，所以清空走这里；`index` 与 `selector` 传一个即可 |
| `hover_and_click` | `id`, `index`(可选), `selector`(可选), `hoverDelayMs`(可选), `mode`(可选) | 悬停后**立刻**点同一个元素，`hoverDelayMs` 默认 300 毫秒。悬浮菜单专用：分两步调用中间隔着一次推理往返，菜单早收起来了。返回命中信息与点击回执 |

### 标签页

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `get_tabs` | `id` | 返回 `data.tabs`（`index`/`url`/`title`/`current`，`index` 是 0 基） |
| `new_tab` | `id`, `url`(可选) | 新建标签页并切换过去，返回 `data.pageIndex`、`data.url`；新页签会被带到最前 |
| `switch_tab` | `id`, `pageIndex` | 切换当前操作的标签页，并把该页签带到最前 |
| `switch_tab_by_url` | `id`, `url` | 按 URL 匹配切换当前页签，返回 `data.pageIndex`/`data.url`/`data.title`。匹配规则见下面的「URL 匹配」；匹配不到时 `msg` 里列出当前全部页签 |
| `close_tab` | `id`, `pageIndex` | 关闭标签页；关掉当前页时自动切到第一个（同样带到最前） |
| `close_other_tabs` | `id`, `pageIndex`(可选) | 关掉除指定页签外的全部页签，返回 `data.closed`/`data.remaining`/`data.pageIndex`。不传 `pageIndex` 时保留当前页签。**关重复页签用这个**：一个个 `close_tab` 会因为索引整体前移而关错 |
| `bring_to_front` | `id`, `pageIndex`(可选) | 把指定页签（默认当前页签）带到窗口最前，返回 `data.pageIndex`/`data.url`。只切窗口、**不改当前操作页**，适合「让人看一眼这一页」 |

`new_tab` / `switch_tab` / `switch_tab_by_url` / `close_tab` / `close_other_tabs` / `bring_to_front` 以及 `click_element_by_selector` 命中弹窗时都会调用 `bringToFront()`：**有头模式下浏览器窗口会跟着切到智能体正在操作的那个页签**，人工可以直接看到当前进度；无头模式没有副作用。

页签多了以后 `pageIndex` 不稳定（实测点一次菜单会弹出两个同 URL 的重复页签），优先用 `switch_tab_by_url` 定位。

### 等待

`timeoutSeconds` 不传时默认 30 秒；超时返回 `xxx 超时：等待时间内条件一直没有满足`。

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `wait_for_element` | `id`, `selector`, `timeoutSeconds` | 等元素出现 |
| `wait_for_text` | `id`, `text`, `timeoutSeconds` | 等文本出现 |
| `wait_for_url` | `id`, `url`, `timeoutSeconds` | 等 URL 匹配（支持 `**/path` 这类通配），返回 `data.url` |
| `wait_for_load` | `id`, `state`, `timeoutSeconds` | `state` 取 `load` / `domcontentloaded` / `networkidle` |
| `wait_for_function` | `id`, `expression`, `timeoutSeconds` | 等 JS 表达式为真，如 `() => document.readyState === "complete"` |
| `wait_for_idle` | `id`, `quietMs`(可选，默认 500), `timeoutSeconds`(可选，默认 30), `selector`(可选), `text`(可选) | 等页面「忙完」：连续 `quietMs` 毫秒既没有 DOM 变更、也没有在途请求。**不知道要等多久时用它**，比固定 `wait` 稳、比手写 `wait_for_function` 省事 |
| `wait_for_stable` | `id`, `selector`(可选，默认整个 body), `quietMs`(可选，默认 800), `timeoutSeconds`(可选) | 等**内容**稳定：正文 / 表单值 / 表格内容连续 `quietMs` 毫秒不再变化，返回 `data.stable`、`data.changes`、`data.length`、`data.fingerprint` 与**稳定后的文本 `data.text`** |
| `wait_for_count` | `id`, `selector`, `min`/`max`/`equals`(至少给一个), `timeoutSeconds`(可选) | 等命中数量达标。等弹窗/遮罩**全部消失**用 `max: 0`，等结果行**出现**用 `min: 1`。返回 `data.matched`、`data.count`、`data.waitedMs` |

`wait_for_idle` 的返回：`data.idle`（成功时 true）、`data.waitedMs`、`data.mutations`（观察到的 DOM 变更次数）、`data.inflight`（结束时仍在途的请求数）。超时失败时同样带这几个计数，便于判断是「接口一直不回来」还是「页面有定时器一直在改 DOM」。传了 `selector` / `text` 时会同时要求该条件成立。

**`wait_for_idle` 看「忙不忙」，`wait_for_stable` 看「内容变没变」**，两者不能互相替代：查询/搜索结果是异步刷新的，页面可能一直在动（动画、轮询计时器）但真正要读的内容已经定下来了，这时用 `wait_for_stable`；点一下等它保存完、不知道要等多久，用 `wait_for_idle`。

> **查完立刻读结果是错的**：实测点「查询」后马上读表格，读到的是**上一次**的搜索结果，表现成「明明有这一行却找不到」。正确顺序是 `click` → `wait_for_stable`（或 `wait_for_count`）→ 再读。`wait_for_stable` 已经把稳定后的文本一并返回，省掉一次 `get_element_text`。

### 鼠标

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `mouse_move` | `id`, `x`, `y` | 移动鼠标到坐标 |
| `mouse_down` | `id`, `button` | `left` / `right` / `middle` |
| `mouse_up` | `id`, `button` | 松开 |
| `mouse_wheel` | `id`, `deltaY` | 滚轮，正数向下 |
| `mouse_click` | `id`, `x`, `y`, `button`(可选), `clickCount`(可选) | **真实鼠标点击一个坐标**（一条顶原来的 `mouse_move`+`mouse_down`+`mouse_up` 三条） |
| `mouse_click_by_selector` | `id`, `selector`, `button`(可选), `clickCount`(可选), `timeoutMs`(可选) | 真实鼠标点击某个元素**中心**（自己算坐标，不用先 `get_element_box`） |

**真实鼠标事件是唯一能让某些控件生效的方式**：实测 ant-design 的 `Modal.confirm`「确定/取消」、对话框右上角的 ×，用 JS 派发 `click`（甚至元素原生 `el.click()`）**完全无效**——点了没反应、弹窗不关，而接口照样回成功。这类控件必须走真实鼠标事件。

三个点击方法（`click_element_by_index`、`click_element_by_selector`、`click_element_by_text` 等）都支持 `mode`：

| `mode` | 行为 |
| --- | --- |
| `auto`（默认） | 原生点击 → 失败则改**真实鼠标** → 再失败改 JS 派发；目标**被遮挡**时跳过鼠标档（鼠标点的是坐标，会落在遮挡物上）直接走 JS 派发 |
| `native` | 只做原生点击（有完整的可操作性检查） |
| `mouse` | 只做真实鼠标点击（不做可操作性检查，元素一直在动时也能点到） |
| `js` | 只做 JS 派发（不要求元素可见、不被遮挡影响） |

回执里 `data.mode` 是**实际用上的**那一种，`data.fallbackReason` 是降级原因（没降级就没有），`data.effective` 表示这次点击有没有真的改变页面。**只看 `ok:true` 会误判**：JS 派发的点击在框架里可能被忽略，所以要连 `data.effective` 一起看。

`data.coveredBy` 表示目标中心点上实际命中的是别的元素（常见：用户服务协议层、弹窗遮罩、叠起来的确认框），这时先 `close_modal` 关掉遮挡物再点，而不是反复点。

### 截图与 PDF

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `screenshot` | `id`, `path`(可选), `fullPage`(bool), `index`(可选), `selector`(可选), `clipX`/`clipY`/`clipWidth`/`clipHeight`(可选), `inline`(bool，默认 false) | **默认落盘**：不传 `path` 时写到 `data/<id>/shot-N.png`，返回 `data.path`/`data.url`/`data.size` 与 `data.base64Omitted=true`；要内联 base64（直接喂视觉模型）才传 `inline: true`。传 `index` 或 `selector` 时**只截该元素**；否则截整页，`clipX/clipY/clipWidth/clipHeight` 四个都传才按区域裁剪 |
| `get_element_screenshot` | `id`, `index`(可选), `selector`(可选), `path`(可选), `inline`(bool，默认 false) | 只截一个元素，返回 `data.path`+`data.url`+`data.size`、`data.target`；`inline: true` 时另给 `data.base64`。`index` 与 `selector` 传一个即可 |
| `pdf` | `id`, `path`(可选) | 存 PDF，返回 `data.path`；不传 `path` 落到 `~/Downloads/broswer/` |

> 日常「看页面长什么样」**先别看图**：`data.screenshot` 是每个改变页面的方法自动留下的截图地址，但把图读进上下文很贵，非必要不要读（见开头的省 token 铁律），读 `data.text` 就够了。`get_element_screenshot` 是验证码、二维码、图表这类「必须看图」的元素的标准做法：走 Playwright 自己的元素截图，**不受 canvas 跨域污染限制**（用 `execute_js` + canvas 手抠图，跨域图片会直接失败），而且只截一个元素、比整页图省得多。

### Cookie 与本地存储

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `get_cookies` | `id`, `url`(可选) | 返回 `data.cookies`（`name/value/domain/path/expires/httpOnly/secure/sameSite`）与 `data.count` |
| `set_cookie` | `id`, `name`, `value`, `url`(可选) | 建议同时传 `url`，否则要自己保证 domain 合法 |
| `clear_cookies` | `id` | 清空 |
| `get_local_storage` | `id`, `key`(可选) | 传 `key` 返回该键的值；不传返回整个 localStorage 的 JSON 字符串（`data.value`） |
| `set_local_storage` | `id`, `key`, `value` | 写入当前站点 |
| `clear_local_storage` | `id` | 清空当前站点 |

### 浏览器设置

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `set_viewport` | `id`, `width`, `height` | 改视口尺寸 |
| `set_geolocation` | `id`, `latitude`, `longitude` | 会同时授予 `geolocation` 权限 |
| `set_offline` | `id`, `offline`(bool) | 断网 / 恢复 |
| `set_headers` | `id`, `headersJson` | 形如 `{"X-Key":"v"}` |
| `set_credentials` | `id`, `username`, `password` | HTTP 基本认证。凭据只能在创建上下文时设置，所以**会重建整个浏览器**：当前页面丢失，**且会失败** —— 浏览器是所有任务共用的，只允许在「当前只有这一个任务」时调用（否则提示先 `close` 掉其它任务）；走 CDP 那条路时（`data.browser.mode=cdp`：用户自己的 Chrome profile 或 `browser=edge`）不支持，会直接返回失败原因，需要基本认证就改用 `browser=chrome`／`chromium` |
| `set_media` | `id`, `colorScheme` | `light` / `dark` / `no-preference` |

### 弹窗与控制台

这里有两类**完全不同**的弹窗，别混：

| 类型 | 是什么 | 用哪个方法 |
| --- | --- | --- |
| 浏览器原生对话框 | `window.alert` / `confirm` / `prompt`，会阻塞页面 | `get_dialog` / `clear_dialog` / `set_dialog_behavior` |
| 页面里的 DOM 弹窗 | ant-design 的 `Modal`/`Modal.confirm`、用户服务协议层、抽屉 | `get_modals` / `close_modal` |

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `get_dialog` | `id`, `consume`(bool) | 返回 `data.dialog`（`type/message/defaultValue/seq/timestamp`）或 null。**记录不会自动清除**，可能是很早以前的弹窗；`consume=true` 读后即清。`get_js_dialog` 是它的同义名 |
| `clear_dialog` | `id` | 清空弹窗记录，返回 `data.cleared`。`clear_js_dialog` 是它的同义名 |
| `set_dialog_behavior` | `id`, `dismiss`(bool) | 弹窗**默认自动确认**；`dismiss=true` 改成自动取消 |
| `get_modals` | `id` | 列出当前可见的 DOM 弹窗：`data.count`、`data.modals[]`（`kind`/`title`/`text`/`buttons`/`buttonPoints`/`hasClose`/`closePoint`/`rect`/`zIndex`）、`data.top`（最后弹出来的那个） |
| `close_modal` | `id`, `which`(可选，默认 `top`), `title`(可选), `button`(可选) | 关掉 DOM 弹窗。**一律用真实鼠标点**，并且点完**校验数量是否真的减少**，返回 `data.closed`、`data.countBefore`、`data.countAfter`、`data.clicked` |
| `get_console_logs` | `id` | 返回 `data.logs` 与 `data.errors`，各最多 200 条 |
| `clear_console_logs` | `id` | 清空 |

**`get_dialog` 是「最近一次弹窗」而不是「当前这一步的结果」**：实测提交验证码失败过一次之后，后面查询明明成功了，`get_dialog` 仍然返回上一轮的「验证码输入错误」，很容易误判成这次也失败了。判断弹窗是不是新的看 `data.dialog.seq`/`timestamp`；稳妥做法是**每次提交动作前先 `get_dialog` 加 `consume: true` 清一次**，动作后再读。

**DOM 弹窗用 `get_modals` / `close_modal`，不要自己写 JS 点它**：`close_modal` 的 `which` 取 `top`（默认）/ `first` / `all`，`title` 按标题或文本子串匹配（给了就以它为准），`button` 指定点哪个按钮（不给则优先右上角 ×，其次「取消/关闭/知道了/我接受」这类非提交按钮）。反复点一个关不掉的弹窗会把确认框**一层层叠起来**（实测叠到 16 个），之后所有「取第一个可见弹窗」的逻辑都在操作最老的那个——所以要看 `data.closed`：为 `false` 说明点了但数量没减少，这时用 `get_modals` 拿 `closePoint`/`buttonPoints`，再 `mouse_click` 那个坐标。

### 网络

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `route` | `id`, `urlPattern`, `action`, `body`, `status`, `contentType` | `action` 取 `abort`（拦截）或 `mock`（返回自定义响应）；`urlPattern` 用 Playwright 通配，如 `**/api/ping` |
| `unroute` | `id`, `urlPattern`(可选) | 不传则移除全部路由 |
| `get_requests` | `id`, `filter`(可选), `resourceType`(可选), `limit`(可选), `since`(可选) | 返回 `data.requests`（`method/url/resourceType/status`，**带请求体的请求另有 `postData`，最多 4000 字符**），最多 200 条。`filter` 按 URL 子串过滤，`resourceType` 按 `xhr`/`fetch`/`document`/`script` 等过滤，`limit` 限制条数，`since` 只返回该毫秒时间戳之后的。返回里另有 `data.count`/`data.total`/`data.recordedSince`/`data.inflight`/`data.note`。**只有元数据，没有响应体** |
| `wait_for_response` | `id`, `urlPattern`, `timeoutSeconds`(可选), `maxChars`(可选), `lookBackSeconds`(可选) | 等 `urlPattern` 匹配的响应并返回它的响应体：`data.url`、`data.status`、`data.body`（默认最多 20000 字符）、`data.bodyLength`、`data.ageMs`、`data.fromLookBack`。匹配规则见下面的「URL 匹配」 |
| `get_response_body` | `id`, `filter`(可选), `index`(可选), `maxChars`(可选), `requestId`(可选) | 回看**已经发生过**的响应体（保留最近 100 个响应）。`filter` 按 URL 子串过滤，不传取最近一个；`index` 在多个匹配里选第几个（默认最后一个）。可用 `requestId` 精确关联重复 URL 的某次请求。响应体已被释放时返回 `data.bodyError`，且 `data.bodyAvailable=false` |

**URL 匹配**（`wait_for_response` 的 `urlPattern`、`switch_tab_by_url` 的 `url`）：先按**子串**匹配，再按**通配**匹配，通配里的 `*` 和 `**` 都表示任意字符、**可以跨 `/`**。模式没写尾部通配时，URL 后面还可以再跟内容（`#fragment`、`?query` 都算），所以：

| 模式 | 能匹配 |
| --- | --- |
| `**/api/query*` | `https://x.com/api/query?y=1` |
| `**/example.com*` | `https://example.com/`（末尾斜杠不影响） |
| `**/smoke.html` | `file:///…/smoke.html#`（尾部 `#fragment` 不影响） |

> 注意这一条和 `route`、`unroute` 的 `urlPattern` **不一样**：那两个用的是 Playwright 原生通配，`*` 不含 `/`，`https://example.com/` 末尾的斜杠会让 `**/example.com*` 匹配不上。写 `route` 时如果 URL 末尾有斜杠，把模式改成 `**/example.com/**` 这类形式。

**读接口数据用 `wait_for_response`，不要靠 `get_requests` 猜**：SPA 的数据都在 XHR 里，而 `get_requests` 只有 url 和状态码。典型用法是「点一下 → 等接口 → 读 JSON」，放在一个批次里：

```shell
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id": 1001, "method": "commands",
  "params": {"stopOnError": false, "commands": [
    {"click_element_by_index":{"index":12}},
    {"wait_for_response":{"urlPattern":"**/api/query*","timeoutSeconds":20}}]}}'
```

**为什么顺序写就行**：`wait_for_response` **先回看再等** —— `lookBackSeconds`（默认 10 秒）内已经收到过的匹配响应会直接返回，`data.ageMs` 是它距今的毫秒数、`data.fromLookBack` 为 `true`。响应通常在你拿到点击结果之前就到了，所以顺序调用照样命中。`lookBackSeconds=0` 表示只等新响应（这时必须并发触发，而**同一个实例不要并发发请求**，见第十节第 13 条，所以一般不需要）。

已经发过的请求想看返回内容就用 `get_response_body`。

**`get_requests` 返回空不等于「没有请求」**：记录是从页签挂上监听那一刻开始记的，页面在这之前（或另一个页签里）发生的请求不会出现在这里。所以空结果时服务会一并返回 `data.note` 说明这一点、`data.recordedSince` 告诉你从什么时候开始记、`data.inflight` 告诉你此刻还有几个在途。判断顺序：先看 `recordedSince` 是不是晚于你要找的那次请求 → 再用 `filter`/`resourceType`/`since` 缩小范围 → 仍然没有就改用 `get_response_body`（它保留最近 100 个响应，跨页签）。

### 人机协同（验证码 / 短信码 / 人工登录）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `request_human_input` | `id`, `prompt`, `index`(可选), `selector`(可选), `timeoutSeconds`(可选) | 发起一个人工介入请求。传 `index` 或 `selector` 时把该元素（通常是验证码图）截成 `data.imageBase64` 一起返回，并把当前页签带到最前。返回 `data.requestId`、`data.prompt`、`data.expiresAt`（默认 300 秒）、`data.url` |
| `submit_human_input` | `id`, `requestId`, `answer` | 提交人工答复，返回 `data.status` 与 `data.answer` |
| `get_human_input` | `id`, `requestId`, `timeoutSeconds`(可选) | 取人工答复，返回 `data.status`（`pending`/`answered`/`expired`）、`data.answer`、`data.prompt`。传 `timeoutSeconds` 时长轮询等待，到时间还没答复就返回当前状态（**不算失败**） |

完整流程见第九节。

### 其它

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `extract_structured_data` | `id`, `query`, `extractLinks`(bool) | 返回 `data.text`（正文，最多 20000 字符，读取时会临时隐藏高亮层）与 `data.links` |
| `execute_js` | `id`, `body` 或 `bodyFile`, `vars`(可选) | 返回 `data.result`，见第八节 |
| `commands` | `id`, `params.stopOnError`, `params.commands`, `params.async`(可选) | 批量指令，是 `method` 的一个取值，见第七节 |

### 服务自省（不知道有什么能力时先问它）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `list_methods` | `filter`(可选) | 返回 `data.methods`（全部方法名）与 `data.count`。**方法名拿不准就先查**，别靠猜——猜错只会拿到一句「不支持的方法」 |
| `get_config` | `id`(可选) | 服务端**生效**配置：`engine`/`configuredType`、`profileDir`（解析后的真实目录）、`action`（超时与两个降级开关）、`jsDir`、`trace`、`upload`、`tasks`、`commands`。传 `id` 时另给该任务的 `browser`。**「为什么这次不是我要的浏览器」「脚本放哪」这类问题先看它** |
| `list_tasks` | 无 | 当前活着的任务：`data.tasks[]`（`id`/`url`/`title`/`tabCount`/`captureSeq`/`inflight`/`profileDir`）与 `data.browser`（类型、profile 目录、是否走 CDP） |
| `list_recipes` | 无 | 服务端有哪些站点配方，返回 `data.recipes[]`（`name`/`description`/`params`/`stepCount`）与 `data.dir` |
| `run_recipe` | `id`, `name`, `vars`(可选), `stopOnError`(可选) | 跑一个站点配方，见第十二节 |
| `get_job` | `jobId`, `includeResult`(可选，默认 true) | 查异步批次的结果：`data.status`（`running`/`done`/`failed`/`cancelled`）、`data.steps`、`data.data` |
| `cancel_job` | `jobId` | 取消异步批次。**取消是协作式的**：批次会在下一步之前停下来，当前这一步不会被打断 |
| `list_jobs` | `limit`(可选，默认 20) | 最近的异步任务（只保留 50 个，服务重启即丢） |
| `cleanup` | `scope`(可选，默认 `all`), `olderThanHours`(可选，默认 24), `keepLatest`(可选), `dryRun`(可选，**默认 true**) | 清理落盘产物（截图、结构化文本、追踪日志）。**默认只预演不删**，要真删必须显式传 `dryRun: false`。`scope` 取 `all`/`data`/`trace`/`upload`；`all` 不会动 `upload`（暂存文件可能正在被任务使用） |
| `shutdown` | 无 | 关掉所有任务与共享浏览器（服务进程不退出）。比直接杀进程干净：不留占着 profile 目录的孤儿浏览器 |

这些能力也有 GET 版本，脚本与浏览器可以直接打开：`GET /playwright/methods`、`GET /playwright/config`、`GET /playwright/tasks`（与 `list_methods`/`get_config`/`list_tasks` 同一份数据）。

## 七、批量指令（PTC：一次请求跑完一整个计划）

`commands` 是**降低推理步数**的关键：把「动作 + 读取」打包成一次请求，一次模型推理就能拿到全部观察结果。

```shell
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id": 1001,
  "method": "commands",
  "params": {
    "stopOnError": false,
    "commands": [ {"go_to_url":{"url":"https://example.com"}}, {"get_browser_state":{}} ]
  }}'
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `params.stopOnError` | `true` | `true`＝遇到第一个失败就停止；`false`＝继续跑完并把每一步结果都返回，**批量里推荐 `false`** |
| `params.commands` | 必填 | 命令数组，每项**只能有一个键**（外加可选的 `expect`），键是方法名、值是参数对象 |
| `params.async` | `false` | `true`＝立刻返回 `data.jobId`，批次在后台跑，用 `get_job` 取结果、`cancel_job` 取消。**长批次（几十秒以上）用它**，否则客户端容易超时——而超时**不代表批次停了**，它还在服务端继续跑 |
| `params.stopOnExpectFailure` | `false` | `true`＝某一步的 `expect` 断言没过就停下 |
| `params.maxDurationMs` | 无 | 整批的总时长上限，超了就停在当前这一步，`data.stopReason` 说明原因 |

返回值里**每一步的结果都在**：

```jsonc
{"data":{
  "count":3,"succeeded":2,"failed":1,"stopped":true,
  "results":[
    {"index":0,"command":"go_to_url","ok":true,"data":{"status":200,"seq":1,"screenshot":"/data/1001/1.png"},"msg":null},
    {"index":1,"command":"get_browser_state","ok":true,"data":{"text":"[0]<a >新闻/> ...","title":"...","seq":2,"state_file":"/data/1001/2.txt"},"msg":null},
    {"index":2,"command":"click_element_by_index","ok":false,"data":null,"msg":"click_element_by_index 索引越界: 999"}
  ]}, "code":0,"ok":false,"msg":"第 2 条命令 click_element_by_index 失败：click_element_by_index 索引越界: 999"}
```

- **覆盖范围**：第六节的方法里，除 `commands` 自身（不允许嵌套）之外**全部都能批量调用**，命令名与单独调用完全一致。
- 每一步的 `data` 原样返回，所以 `get_browser_state` 的 `data.text`、`execute_js` 的 `data.result`、`is_checked` 的 `data.checked` 在批量里都能直接读到；**动作类命令的 `data.screenshot` 也在**，一个批次就是一段页面变化历史。
- 有失败时整体响应是 `code:0`，但 `data.results` 仍然完整返回，`msg` 指出第一条失败的命令。
- 参数缺失会得到 `第 N 条命令 xxx 失败：缺少参数 index`，不会 500。
- 数组最多 200 条；空数组、非对象项、嵌套 `commands` 都会得到中文提示。
- 布尔参数不传按 `false` 处理（和单独调用一致），所以批量里 **`scroll` 要写 `"down":true` 才向下**；`start` 例外，不传 `headless` 按无头处理，避免误弹窗口。

**PTC 推荐节奏**：一个批次 = 一个计划段。批次里先做动作，末尾放一个 `get_browser_state`，这样下一次推理直接基于最新快照决策。

```shell
# 搜索全过程：输入 → 回车 → 等结果 → 取新快照，一次请求完成
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id": 1001, "method": "commands",
  "params": {"stopOnError": false, "commands": [
    {"input_text": {"index": 14, "text": "Mac Mini M4"}},
    {"send_keys": {"keys": "Enter"}},
    {"wait_for_text": {"text": "Mac Mini", "timeoutSeconds": 10}},
    {"get_browser_state": {}}
  ]}}'
```

```shell
# 一批独立读取：一条失败不影响其它条
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id": 1001, "method": "commands",
  "params": {"stopOnError": false, "commands": [
    {"get_title":{}},{"get_url":{}},{"get_element_text":{"index":0}},{"get_console_logs":{}}]}}'
```

- 需要循环、条件判断这类逻辑，就在批次里用 `execute_js` 一步做完，不要拆成几十条命令。
- 未知命令返回 `第 N 条命令 xxx 失败：不支持的方法：xxx`。

### 用 `expect` 断言「动作真的生效了」

每一步都可以带一个 `expect`，在**命令执行之后**求值。这是本工具最值钱的一个习惯：**回执说 `ok:true` 不代表页面真的变了** —— JS 派发的点击在某些框架控件上完全无效，接口照样回成功。加了断言，「动作发了、状态没变」会被当场标出来。

```shell
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id": 1001, "method": "commands",
  "params": {"stopOnError": false, "stopOnExpectFailure": true, "commands": [
    {"click_element_by_selector": {"selector": ".ant-modal-confirm .ant-btn-primary", "mode": "mouse"},
     "expect": {"js": "document.querySelectorAll(\".ant-modal-confirm\").length", "equals": 0}},
    {"wait_for_count": {"selector": ".ant-modal-confirm", "max": 0, "timeoutSeconds": 5}}
  ]}}'
```

| `expect` 字段 | 说明 |
| --- | --- |
| `js` | 必填，要断言的 JS 表达式（写成 `() => …` 函数也行） |
| `equals` / `notEquals` | 相等 / 不相等（数字按数字比） |
| `min` / `max` | 数值区间 |
| `contains` | 实际值里包含某段文本 |
| `truthy` | 真值判断；**只写 `js` 不写匹配方式时按真值判断** |

- 每一步的结果里多一个 `expectResult`：`{passed, actual, js, matcher, expected}`。
- 断言没过时：批次整体 `code:0`，`data.expectFailed` 计数，`msg` 指出是哪一步的断言没过；**命令本身不算失败**（`data.failed` 仍是 0），`data.note` 会说明「命令都执行成功，是断言没过」。
- 断言脚本自己报错（写错选择器等）也记成 `passed:false`，并把错误放在 `expectResult.error`。

## 八、执行 JavaScript

```json
{ "id": 1001, "method": "execute_js", "params": { "body": "document.title" } }
```

`body` 支持三种写法，都按提交的形式直接求值：

| 写法 | 示例 | 说明 |
| --- | --- | --- |
| 表达式 | `document.title` | 返回表达式的值 |
| 函数 | `() => document.title`、`() => { return document.title; }`、`(async () => await fetch(location.href))()` | 求值结果是函数时会被自动调用，Promise 会被等待 |
| 语句片段 | `const a = 40; return a + 2;` | 含 `return` 的片段会被包装成函数体执行 |

- 返回值必须是 JSON 可序列化的；DOM 元素不报错，但只会得到 `ref: <Node>`，请先转成 `textContent`、`outerHTML`、`value`。
- 脚本报错时返回 `code:0`，`msg` 形如 `execute_js 失败：执行 JavaScript 失败：TypeError: Cannot read properties of null (reading 'click')`（只有异常首行，没有堆栈）。
- `body` 长度上限 100000 字符；脚本**没有超时**。
- 与 `get_browser_state` 的分工：**读页面优先用 `get_browser_state`**（结构化、带索引、token 可控）；`execute_js` 用于取快照里没有的东西（`id`/`class`/`href`、滚动位置、localStorage 原始值）或做特殊交互。

### 用 `bodyFile` + `vars` 传长脚本（客户端-服务器模式下必看）

脚本里带中文、引号、换行时，在客户端拼 JSON 很容易出错（PowerShell 尤其容易吃掉引号）。两种做法：

| 做法 | 写法 | 适用 |
| --- | --- | --- |
| 服务端脚本文件 | `"bodyFile": "read-table.js"` | 脚本较长、要反复用：文件放在服务端的脚本目录（见 `get_config` 的 `jsDir`，默认 `<启动目录>/scripts/js`），只能用这个目录里的文件名 |
| 变量注入 | `"body": "() => document.querySelector('{{sel}}').innerText", "vars": {"sel": "#表格"}` | 脚本短但要传参数 |

`vars` 的替换走 **JSON 编码**：字符串自动带引号并转义，中文、引号、换行都不用转义；数字、布尔、数组、对象直接塞进脚本。两种占位符都支持，且**带引号的写法会连引号一起替换**，所以 `querySelector("{{sel}}")` 与 `var n = {{n}};` 都是对的。

常见用法：

```js
document.body.innerText                                       // 取正文
Array.from(document.querySelectorAll('a')).map(a => a.href)   // 取链接
document.querySelector('#submit').click()                     // 点没有索引的元素
document.querySelector('#kw').value = 'x'                     // 直接赋值
```

## 九、人机协同（验证码 / 登录 / 人工介入）

**遇到无法自行处理的环节，必须主动请求人类帮助，不要反复尝试或直接放弃任务。** 常见情况包括需要人工登录、输入图片验证码或短信码、扫码、拖动滑块、按顺序点击图片/文字验证、设备确认，以及其他需要用户亲自完成的操作。

人工接力流程：

1. 告诉用户当前停在哪个网站、遇到了什么问题、需要完成哪一步。例如：“当前停在登录页，需要你在浏览器中完成登录和滑块验证。完成后请告诉我，我会继续查询。”
2. 优先让用户直接操作有头浏览器（`headless=false`），用 `bring_to_front` 或 `request_human_input` 将当前页签带到最前。保留当前任务 ID、页面和浏览器，不要在等待期间刷新、关闭或继续点击验证控件。无头实例无法直接显示时，先说明需要切换到有头模式，保存当前 URL，再按同一 ID 先 `close` 后 `start`（`headless=false`）并重新打开页面；重启可能丢失未提交的表单和当前验证进度。
3. 调用 `request_human_input` 记录人工请求，并通过宿主工具的提问能力或对话消息明确通知用户；不能假定接口返回成功就代表用户已收到通知。登录、滑块或点击验证优先由用户在浏览器内完成，不要求用户在对话中提供密码。
4. 等待用户答复或观察到明确的完成状态。等待期间暂停依赖登录或验证的后续操作，不反复提交、不猜答案；请求超时只表示尚未收到答复，不代表验证已通过。
5. 用户处理后重新调用 `get_browser_state`，确认已登录或验证已通过，并取得新的元素索引，再继续原任务。若仍受阻，说明当前状态并继续请求协助。

服务提供以下三个方法记录请求与答复；用户也可以直接在浏览器中完成操作：

| 步骤 | 调用 | 做什么 |
| --- | --- | --- |
| 1 | `request_human_input`，`prompt=请输入图片验证码`，`index=7`，`timeoutSeconds=300` | 建一个待办；`index`/`selector` 指向验证码图时把图截成 `data.imageBase64` 返回，同时把页签带到窗口最前 |
| 2 | 人看图 → 把答案回填 | 通过 `submit_human_input`（`requestId` + `answer`）提交；**或者**直接在有头浏览器里自己把这一步操作完 |
| 3 | `get_human_input`，`requestId=hr-1-xxx`，`timeoutSeconds=60` | 取答复。`data.status` 为 `pending` / `answered` / `expired` |

要点：

- **`get_element_screenshot` 是这套流程的地基**：没有它，`request_human_input` 也没东西可以给人看。要单独把图拿出来（不发起人工请求）就直接调它。
- `data.imageBase64` 是 PNG 的 base64，需要向用户展示验证图片时可以使用；取到图片不代表验证已完成。
- 用户直接在浏览器里操作不会自动更新人工请求记录，`get_human_input` 可能仍为 `pending`。不要只等该字段，也不能直接跳过验证：重新读取页面，确认登录或验证已成功后才继续后续步骤；一般页面变化本身不足以证明验证成功。
- **验证码有时效**：实测税务系统的图片验证码约 **120 秒**过期，而且**一次性**（用过的码再提交必然失败）。所以拿到答复后要**立刻**提交，不要攒着；提交失败先换一张新图再让人看，别拿旧码重试。
- **登录态跟着共享 profile 走，不跟任务 ID 走**：所有任务用的是同一份 profile（`data.browser.profileDir`），换任务、换 id 都不影响；是否仍有效由网站决定，登录过期时再次请求用户协助。若 `data.browser.userProfile=false`（退回托管 profile，例如 Chrome 正在运行）或 `chrome=false`（没装 Chrome），说明这次不是用户日常那份登录态，需要重新走登录流程。
- 有头模式（`headless=false`）下配合 `bring_to_front` / `request_human_input`，人工能直接看到智能体停在哪一页，接力最顺。

```shell
# 1. 请人看验证码（把图和问题一起拿出来）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"request_human_input",
  "params":{"prompt":"请输入图片验证码","index":7}}'
# {"data":{"requestId":"hr-1001-3001","prompt":"请输入图片验证码","imageBase64":"iVBORw0...","expiresAt":1750000000000},...}

# 2. 人给出答案后立刻回填
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"submit_human_input",
  "params":{"requestId":"hr-1001-3001","answer":"8f3k"}}'

# 3. 取答复并立刻提交表单
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"get_human_input","params":{"requestId":"hr-1001-3001"}}'
```

## 十、典型任务

**打开页面取正文并让模型阅读**

```shell
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"go_to_url","params":{"url":"https://example.com"}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"get_browser_state"}'   # 把 data.text 交给模型
```

**搜索**

```shell
# 先 get_browser_state，假设搜索框是 [3]
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"input_text","params":{"index":3,"text":"Mac Mini M4"}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"send_keys","params":{"keys":"Enter"}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"wait_for_text","params":{"text":"Mac Mini","timeoutSeconds":10}}'
```

**勾选并提交表单**

```shell
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"check_element_by_index","params":{"index":2}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"is_checked","params":{"index":2}}'   # {"data":{"checked":true},...}
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"click_element_by_role","params":{"role":"button","name":"提交"}}'
```

**下拉框**

```shell
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"get_dropdown_options","params":{"index":4}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"select_dropdown_option","params":{"index":4,"text":"上海"}}'
```

**页面变化后元素找不到**：重新 `get_browser_state` 取新索引；如果元素没有索引，改用 `click_element_by_selector` 或 `execute_js` 的 `.click()`。

**多标签页**：点击 `target=_blank` 链接或 `execute_js` 里 `window.open(url)` 后，用 `get_tabs` 看列表，`switch_tab` 加 `pageIndex: 1` 切过去，`close_tab` 关掉；也可以直接 `new_tab`。切过去的页签会同时被带到窗口最前，`headless=false` 时人工能看到智能体当前操作的是哪一页。先看 `data.browser_state` 里的 `current tab is: N` 最直观（记得减 1）。

**抓接口 / 造数据**：`route` 加 `action=mock` 让接口返回假数据；`action=abort` 让请求直接失败；`get_requests` 看请求记录（含请求体）；`get_response_body` 看已经发生过的响应体；`wait_for_response` 拿接口返回的 JSON（先回看最近 10 秒，再等新的）；`unroute` 撤销。

**悬浮菜单**：`hover_and_click` 一步完成悬停 + 点击（分两步菜单会收起来）。菜单项如果只有文本没有索引，用 `hover_and_click` 加 `selector`，注意选择器里的引号在 JSON 里要转义。

**判断点击到底生效没有**：看点击回执里的 `data.changed`。`false` 只表示最多约 500ms 的观察窗口内尚未发现变化，不能据此重复提交；配合 `diff_dom_text` 能确认页面快照有没有变。**不要为了这个去读 `data.screenshot`**，图很贵（见开头的省 token 铁律）。

**读某个接口返回的 JSON**：`wait_for_response` 加 `urlPattern` 与 `timeoutSeconds` 直接拿 `data.body`（默认先回看最近 10 秒）；已经发生过的用 `get_response_body`。

**事后复看某一步的页面**：`data/<id>/<seq>.png` 是截图、`data/<id>/<seq>.txt` 是同一时刻的页签 + 可交互结构化文本，直接 GET `http://localhost:10049/data/<id>/<seq>.png` 或 `.txt`。

## 十一、坑与限制

1. **只有一个端点，只支持 POST + JSON 请求体**：`{"id":...,"method":...,"params":{...}}`。参数不放查询串、不放表单，也不需要 URL 编码。
2. **参数问题不再返回 HTTP 500**：缺参数得到 `xxx 失败：缺少参数 name`，实例不存在得到 `没有找到对应的浏览器实例：<id>`。**方法名写错会顺带给近似建议**：`不支持的方法：list_tabs，你是不是想用 get_tabs / new_tab / get_tabs？`（按编辑距离与分词近似挑候选），照着改一次就能过，不用再猜。运行期错误也是 `code:0`，例如 `go_to_url 失败：net::ERR_CONNECTION_REFUSED at ...`、`send_keys 失败：Unknown key: "NotAKey"`。
3. **页面变化后索引全部重算**：点击、跳转、异步渲染之后必须重新 `get_browser_state`；沿用旧索引会得到 `索引越界` 或 5 秒超时后提示重新取快照。
4. **快照里没有 `id`/`class`/`href`**：按 id/class 定位用 `click_element_by_selector`，取 href 用 `execute_js`，批量看属性用 `get_interactive_map`。
5. **纯文本容器（`div`/`span`/`li`）没有索引**：这类元素用 `click_element_by_selector` 或 `execute_js` 调 `.click()`；但带 `onclick`/`cursor:pointer` 的 `div`/`span` 会有索引，别一概而论。
6. `wait` 的 `seconds` 必填；要等页面就绪请用 `wait_for_load` / `wait_for_element`。
7. `upload_file` 的 `path` 是**服务器**能打开的路径（绝对路径直接用，相对路径按服务端暂存目录解析），不是 URL；文件不存在时错误信息会告诉你去 `POST /playwright/upload` 把文件送上来。传 `selector` 可以操作隐藏的 file input（比 `index` 更好用，见第四节的「上传文件」）。
8. `scroll_to_text` 找不到文本会等满 30 秒，别用它探测元素是否存在，用 `wait_for_element`。
9. **`execute_js` 没有超时**：脚本里不要写死循环或长时间轮询，否则请求一直挂着；页面上弹模态框时弹窗会被自动确认，也可以用 `set_dialog_behavior` 改成自动取消。
10. `execute_js` 的 `body` 上限 100000 字符；返回 DOM 元素只会得到 `ref: <Node>`。
11. `get_browser_state` 的 `highlight=true` 会在页面上加一层高亮框，它是页面里真实存在的 DOM（`extract_structured_data` 读取正文时会临时隐藏它）；人工观察时很有用，纯自动跑可以传 `highlight=false`。
12. **不要用同一个 `id` 重复 `start`**：会直接返回失败，提示先 `close` 或换一个 id。不同任务用不同 id（浏览器与 profile 是共用的，隔离靠各自的页签）。
13. 一个任务只对应一个当前 Page，**不要并发对同一个 `id` 发请求**；并发任务请各自 `start` 一个任务（共用浏览器，各有各的页签）。
14. `set_credentials` 会重建整个浏览器（所有任务共用），只能在「当前只有一个任务」时用，走 CDP 那条路时（用户自己的 Chrome profile 或 `browser=edge`）不可用；`headless=false` 会弹出真实窗口，只适合本机调试。
15. 服务无鉴权且 `execute_js` 能执行任意脚本，对外部署前必须加访问控制。
16. **不可见元素既不进快照，也不能用「原生方式」操作**：实测百度首页的真实搜索框 `INPUT#kw`（`offsetParent === null`，被新的 AI 输入框取代而隐藏）不在 `data.text` 里，只剩提交按钮；`input_text_by_selector` 按默认方式作用在它上面会等满超时后返回 `input_text_by_selector 失败：[ELEMENT_HIDDEN] 元素当前不可见: 选择器 #kw`。这时有三条路，**优先第一条**：

    1. 传 `mode: "js"`（跳过可操作性检查，直接在页面里设值并派发 `input`/`change`），回执会给出 `data.committed`：

       ```json
       {"id":1001,"method":"input_text_by_selector",
        "params":{"selector":"#kw","text":"Mac Mini M4","mode":"js"}}
       ```

       注意：JS 设值**不保证进框架的 model**（`committed=false`），关键字段仍要用可见的等价输入框重填一遍。
    2. 用 `execute_js` 自己设值并派发事件（要完全控制事件细节时）：
    3. 先把元素显示出来（去掉 `display:none` / 改 `visibility`）再按常规方式操作——**只在确实没有别的办法时用**，改动页面样式可能让站点行为与真实用户不一致。

    ```js
    (function(){var e=document.querySelector("#kw");e.focus();e.value="Mac Mini M4";
      e.dispatchEvent(new Event("input",{bubbles:true}));return e.value;})()
    ```

    判断元素是否可见：快照里有它就是可见的；怀疑隐藏时用 `execute_js` 看 `e.offsetParent !== null`，或直接用 `get_form_state` 加 `includeHidden: true` 看 `visible` 字段。
17. **批量接口的约定**：每项只能有一个键；`stopOnError` 默认 `true`（遇到第一个失败就停），批量里推荐显式传 `false`；布尔参数不传按 `false` 处理（`scroll` 要写 `"down":true`）；`commands` 不能嵌套；覆盖范围是除 `commands` 外的全部方法；数组最多 200 条。
18. **验证 `route` mock 时别等页面自己的回调**：实测页面加载时自己发起的 `fetch` 被 mock 后，渲染进程里的 `.then` 可能迟迟不执行（没有真实网络 IO 去唤醒它），而用 `execute_js` 主动发一次同样的请求就能立刻拿到 mock 数据。要验证拦截效果就用 `execute_js` 主动发请求，或看 `get_requests` 里的状态码。
19. **一个批次里前面的失败会影响后面**：批次是顺序执行的，索引类命令依赖同一次快照，前面的点击跳转会改变 DOM；PTC 的稳妥做法是「动作段 + 末尾 `get_browser_state`」，用下一次推理基于新快照决定后续，而不是在一个批次里塞几十步。
20. **`get_dialog` 是「最近一次弹窗」，不会自动清除**：它可能来自很早以前的一次操作，别把内容当成当前这一步的结果。实测提交验证码失败过之后，后续查询明明成功了，`get_dialog` 仍返回上一轮的「验证码输入错误」，据此误判会白跑一轮。用 `data.dialog.seq`/`timestamp` 判断新旧，或在每次提交动作前先 `get_dialog` 加 `consume: true`。
21. **`ok=true` 不代表点中了东西**：点击类方法只保证动作没抛异常。实测点悬浮菜单时文本命中的是纯文本容器，方法返回成功但页面毫无变化。是否观察到变化看回执里的 `data.changed`；`click_element_by_text` / `click_element_by_role` / `hover_and_click` 还会返回真正命中的 `data.tag`/`data.outerHtml`。
22. **`input_text` 清空不了**：`text` 是必填参数。要清空用 `clear_text`。
23. **`get_requests` 只有元数据**：`method/url/resourceType/status`，加带请求体请求的 `postData`（最多 4000 字符），**没有响应体**。要读接口返回的内容用 `wait_for_response`（先回看最近 10 秒，再等新响应）或 `get_response_body`（回看最近 100 个响应）。另外 `wait_for_response` 遇到页面**自己**发起的 fetch 时，回调可能迟迟不执行（见第 18 条），这时用 `execute_js` 主动发一次同样的请求，或改用 `get_response_body` 回看。
24. **页签索引不稳定**：实测点一次菜单会弹出两个同 URL 的重复页签，这时 `pageIndex` 很容易指错。按 URL 用 `switch_tab_by_url` 切换，用 `close_other_tabs` 清理，别一个个 `close_tab`（索引会整体前移）。
25. **图片类元素只能靠截图接口拿**：`get_browser_state` 只有文本，`execute_js` + canvas 抠图遇到跨域图片会被污染直接失败。用 `get_element_screenshot`（走 Playwright 元素截图，不受同源限制）；智能体本身读不了图时，按第九节请人来看。**但只在确实必须看图时才调它**，非必要不要读图（见开头的省 token 铁律）。
26. **截图序号是任务级的，不是调用级的**：`seq` 只增不减，`close` 再 `start` 同一个 id 也会接着往上加（文件不删就继续累加）。想要干净的一轮就从空的 `data/<id>/` 目录开始。
27. **`data/<id>/` 里的文件不会自动清理**，长期跑要自己定期清理；服务只监听本机，`/data/**` 也没有鉴权，别把它暴露到公网。
28. **非必要不要读 `get_browser_state`（以及任何自动截图）返回的图片**：`data.screenshot` / `data.screenshot_path` 只是地址，把图读进上下文非常贵，而定位和操作要的信息全在 `data.browser_state` + `data.text` 里。默认只读文本字段；确认页面变化用 `data.changed` / `diff_dom_text`；只有验证码、二维码、图表、纯图片元素这类文本表达不了的场景才取图，并且优先 `get_element_screenshot` 只截那一个元素。详见开头的省 token 铁律。
29. **登录页白屏时可以换引擎试试 Firefox**：先记录最终 URL、HTTP 状态和控制台错误，区分 `/login` 返回 HTTP 400 的空白页与跳转到 `about:blank`，不要直接归因于沙盒或 DevTools。2026-09-22 国家知识产权局登录页实测中，**Playwright 1.53.0 + Firefox 139.0** 两次正常显示登录表单；**Playwright 1.63.0 + Firefox 155.0** 两次出现 HTTP 412 → 400 后白屏；Chromium 系（本机 Chrome、内置 Chromium、Edge）在该站点一律白屏。遇到类似现象，换引擎 + 全新会话从官网入口重试，并观察至少一分钟。

    **怎么换引擎**：`start` 时传 `browser`，取值 `auto`（默认，本机 Chrome，没装退回内置 Chromium）/ `chromium` / `chrome` / `edge` / `firefox`：

    ```bash
    {"id":"1001","method":"start","params":{"browser":"firefox","headless":false}}
    ```

    也可以给服务配默认值 `browser.engine=firefox`（等价 `browser.type=firefox`），命令行覆盖示例：`mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dbrowser.engine=firefox"`。要点：

    - Firefox 用的是 **Playwright 自带的那份**（打过补丁、走 juggler 协议），本机安装的普通 Firefox 接不上，所以**不要**配 `browser.firefox.path` 指到本机 Firefox。
    - **profile 与 Chromium 那份是分开的**：换引擎等于换一套登录态，Chrome 里登录过的站点在 Firefox 下要重新登一次（反之亦然）。
    - `pdf` 命令只支持 Chromium，Firefox 下会返回明确失败原因；`set_credentials`、`--profile-directory` 这类 Chromium 概念在 Firefox 下同样不适用。
    - 换引擎会重建浏览器，所以只能在「当前没有其它任务」时换（把在跑的任务 `close` 掉再 `start`，不用重启服务）。
    - 这仍然是**排障候选**，不保证适用于所有网站，也不代表已成功登录。详见[问题记录与复测证据](scripts/diagnostics/RESULTS-2026-09-22.md)。

30. **`data.mode=js` 意味着「这次没走真实交互」**：被遮挡、带动画、或 `pointer-events` 有问题的元素上，原生点击会等满超时，服务会自动降级成 JS 派发事件（回执里 `data.mode=js` + `data.fallbackReason`），点击本身通常是有效的。但 JS 设值**不保证进框架的 model**：`input_text` 走 JS 时会回 `data.committed=false`，DOM 上明明有值、预览或提交校验却说「不能为空」就是这种情况——用 `input_text_by_selector` 重填一遍（可见字段默认走真实输入）。**关键步骤（提交、缴费）看到 `mode=js` 要额外确认页面状态**，不要只看 `ok=true`。

31. **`POST /playwright/upload` 与 `/data/**` 一样没有鉴权**：能访问端口的人就能往服务端磁盘写文件（只能写进暂存目录、文件名会被清洗，但文件内容不限）。服务只监听本机时问题不大，**对外部署前必须一起加访问控制**；`browser.upload.enabled=false` 可以整体关掉这个接口。追踪日志默认脱敏（手机号、证件号、邮箱、长数字）但只是尽力而为，日志与暂存文件也都不会自动清理。

32. **强杀服务会留下孤儿浏览器，下一次 `start` 可能卡在启动超时**：浏览器是**独立进程**，杀掉服务（或它的 mvn 进程）**不会**关掉它启动的浏览器。残留的浏览器占着 profile 目录，下一次启动时它既不连上管道也不退出，`start` 就一直等到启动超时（默认 60 秒，见 `browser.launch.timeoutMs`），而错误信息里只有一句 `Timeout ... exceeded`。

    - 服务在 Windows 上会**自动清掉残留痕迹**（profile 里的 `parent.lock` 与 `.startup-incomplete`：前者删得掉就证明没有活着的持有者，后者是「上次启动没走完」的标记），并且**第一次失败后会自动重建驱动再试一次** —— 实测这个重试往往就能成功（表现为第一次等 60 秒、第二次 2 秒起来）。
    - 手工处理顺序：先结束残留浏览器进程（`Get-Process firefox | Stop-Process -Force`），再重启服务。
    - **规范做法是「先 `close` 任务，再停服务」**：关掉最后一个任务时浏览器会一起退出，就不会留下孤儿。关掉全部任务与共享浏览器也可以直接用 `shutdown`（服务进程不退出，HTTP 还能应答）。
    - 有头模式（`headless:false`）下这个现象更容易出现：有头启动比有头慢，且窗口/桌面状态会影响启动。排查时可以先确认「无头能不能起来」（`headless:true`），能起来就说明是环境问题而不是引擎问题。
    - **profile 目录默认按端口分开**（`shared-<端口>`）：同一台机器上跑多个服务实例时，各自用各自的 profile，不会互相抢锁。要用同一份 profile（例如复用已登录的会话）就显式配 `browser.profileDir`。当前解析到哪个目录用 `get_config` 看。
    - **强杀服务前先确认没有活着的任务**：`list_tasks` 能直接看到活着的任务与共享浏览器，不用翻日志猜。

33. **`browser` 参数在老版本发布包上会被静默忽略**：老版本只认 `headless`，传了 `browser:"firefox"` 照样用内置 Chromium 打开页面，回执还是 `ok:true`——实测在一个需要 Firefox 的站点上白排查了很久。现在 `start` 的回执里明确给出 `requestedBrowser`、`effectiveBrowser`、`engineHonored`，不匹配时还会带 `engineWarning`。**看到 `engineHonored:false` 就说明这个服务实例没有按参数切浏览器**，要升级服务端；也可以用 `get_config` 确认服务端认的 `engine`/`configuredType`。

34. **截图与日志不会自动清理**：一次完整的站点操作能攒下上百个追踪文件与几十张截图。定期用 `cleanup` 清理（**默认只预演**，`dryRun:false` 才真删），或用 `browser.capture.enabled=false` 关掉「每次页面变化都自动截图」（显式调 `screenshot` 不受影响）。

## 十二、站点配方（`run_recipe`）

配方是把「某个站点上必须这么点」的经验固化成服务端的 JSON 命令序列：文件放在配方目录（默认 `<启动目录>/recipes`，可用 `browser.recipes.dir` 改），文件名就是配方名，内容形如：

```json
{
  "name": "close-antd-modal",
  "description": "关掉 ant-design 的确认框：JS 派发无效，必须真实鼠标点",
  "params": {"title": "要关的弹窗标题，可选"},
  "commands": [
    {"get_modals": {}},
    {"close_modal": {"title": "{{title}}", "button": "取消"}}
  ]
}
```

调用：

```bash
{"id":1001,"method":"run_recipe","params":{"name":"close-antd-modal","vars":{"title":"确认提交"},"stopOnError":true}}
```

- `list_recipes` 看有哪些配方；`get_config` 的 `jsDir`/`recipesDir` 告诉你目录在哪。
- `vars` 用 `{{变量}}` 注入，走 JSON 编码（中文、引号、换行都不用转义）。
- 配方里的每一步都走同一套命令分发，所以**单步手跑与整段跑行为完全一致**，排障时可以把配方里的命令一条条贴出来单独执行。
- 回执里带 `data.recipe` 与 `data.recipeDescription`，其余字段与 `commands` 批量完全一致（`count`/`succeeded`/`failed`/`results`）。
- **配方不会自动生效**：必须显式点名 `run_recipe` 才执行，不做任何「看到这个域名就自动套用」的隐式推断。引擎选择同理，始终由调用方在 `start` 时决定。

