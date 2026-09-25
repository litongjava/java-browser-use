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
- 共 116 个方法（拿不准就先 `list_methods`），`get_browser_state` 是阅读页面的入口，其余方法负责操作与观测

**本文只放「每次都要用的核心」；细节按需再读同目录分册（都在 `.agents/skills/deepseek-browser-use/references/`）：**

| 分册 | 什么时候读它 |
| --- | --- |
| `references/client.md` | 不想手拼 JSON：`dsb` 客户端、批量与异步、退出码、`selftest` |
| `references/protocol.md` | 请求/响应格式、响应精简模式、`mode` 降级、网络证据、截图落盘、`logs/trace/` 追踪日志 |
| `references/reading-pages.md` | `get_browser_state` 全部返回字段、`data.text` 逐行含义、跨域 iframe、主站套第三方控制台 |
| `references/commands.md` | 每个方法的参数与返回字段（全量）、站点配方 |
| `references/batch-and-js.md` | `commands` 批量、`expect` 断言、`execute_js` / `bodyFile` / `vars` |
| `references/human-in-loop.md` | 验证码 / 扫码 / 短信码 / 人工登录、`ocr_image`、多步 `steps` |
| `references/browsers.md` | 选浏览器与引擎、profile 与登录态、实例生命周期、残留进程 |
| `references/pitfalls.md` | 53 条坑与限制（下面「症状表」与「最常踩的坑」里说的「第 N 条」都指它） |

> ## 省 token 铁律：非必要不要读图
>
> `get_browser_state` 以及每个「会改变页面」的方法都会带回 `data.screenshot`（URL）、`data.screenshot_path`（服务器本地路径），`get_browser_state` 还带回 `data.state_file`。**这些字段只是地址，不要顺手把它们读进上下文**：
>
> - **不要**为了「看看页面长什么样」去下载/打开这些图片，也不要交给视觉模型、不要用 图片读取工具读它。图片的 token 消耗比同一次返回的 `data.text` 高几个数量级。
> - 定位与操作所需的全部信息都在文本里：`data.browser_state`（页签）+ `data.text`（每行的 `[index]` 就是元素索引）。**读图不会多给一个索引，只会多烧 token。**
> - 判断「点击到底生效没有」不要靠看图：用点击回执里的 `data.changed`，或用 `diff_dom_text` 比文本差异，都比读图省得多。
> - **只有文本根本表达不了的时候才看图**：验证码 / 二维码 / 扫码登录、图表与曲线、纯图片按钮或图标、以及文本与操作结果明显矛盾、必须肉眼确认的场合。这时优先用 `get_element_screenshot` **只截那一个元素**，而不是把整页大图读进来。
> - 确实需要整页图时再用 `screenshot`（**默认落盘、只回路径**；要内联 base64 得显式传 `inline: true`），并且一次任务里尽量只读一张。
> - **模型读不了图时不要硬撑**：用 `ocr_image` 让服务端用本机 OCR 把图上的文字读出来（见 `references/human-in-loop.md`），或 `request_human_input` 请人看一眼。

### 症状 → 命令（先查这张表，别翻一千行）

| 我遇到的情况 | 用哪个命令 / 参数 |
| --- | --- |
| **快照里找不到明明在页面上的元素** | 先看 `data.pixels_above` / `data.pixels_below` —— 非 0 就说明元素在视口外、**没有索引**。三条解法按优先级：① `get_browser_state` 传 `viewportExpansion`（例如 `1500`）；② 改用 `click_element_by_selector` / `input_text_by_selector`（不依赖索引）；③ 滚动到目标位置后重取快照 |
| **整页读完还是空的、元素像不存在**（主站把第三方控制台套在 iframe 里） | `list_frames` 看有哪些 frame；再 `get_browser_state` 传 `includeFrames: true`。见 `references/reading-pages.md`「跨域 iframe」 |
| 点了没反应，但返回 `ok:true` | 看 `data.changed` / `data.effective`；看 `data.hit`（这次命中的元素）；改用 `click_element_by_text` 复核命中的是不是纯文本容器 |
| 不确定页面到底动没动 | `diff_dom_text`（不落盘、比重读整页省） |
| 表单填了但提交说为空 | 看 `data.mode` / `data.committed`；改用 `input_text_by_selector`（可见字段走真实输入，进框架模型） |
| 上传了但页面没反应 | 看 `upload_file` 回执里的 `data.consumed`（`noListener` 就是这个坑）；用 `get_element_listeners` 复核 |
| 有弹窗挡住点击 | `get_modals` + `close_modal`（每条结果看 `blocking`/`matchedBy`；`buttons` 里连 `<a class="btn92s">确认</a>` 这种自有按钮也认） |
| **`execute_js` 里 `.click()` 点了没反应**（点了 window.open / 带 onclick 的锚） | **不是页面坏了**：JS 派发的点击不是可信事件，弹窗会被浏览器拦掉。改用 `click_element_by_selector`（真实鼠标事件）点它 |
| 想读某个接口的返回，`get_response_body` 却回 `bodyAvailable:false` | 响应体在收到的当下就抄过一份，**跳转/等一会儿也能读**；真读不到说明它不是 xhr/fetch，改用 `wait_for_response` 等一次新响应 |
| 多行 JS 报 `SyntaxError: Unexpected end of input` | 脚本在命令行里被截断了：改用 `js @脚本.js`（或服务端 `bodyFile`），别看语法错误去改脚本 |
| 某个元素到底有没有挂事件 | `get_element_listeners`；`get_interactive_map` 每条也带 `hasListeners` |
| 需要人扫码 / 输验证码 / 支付确认 | `request_human_input`（一串动作用 `steps` 一次交办） |
| 模型读不了图，但要读验证码 / 维护图 | `ocr_image`（Windows 自带 OCR，支持中文） |
| 长批次怕 HTTP 超时 | `commands` 加 `async: true` + `get_job`；或客户端 `batch cmds.json --async --wait` |
| 手拼 JSON 被引号 / 中文 / 编码坑了（Windows 尤其） | 别硬拼，用仓库里的 `dsb` 客户端：Windows 敲 `.\client\dsb.cmd`，参数进文件用 `batch cmds.json` / `js @脚本.js`，见 `references/client.md` |
| 索引老是失效 | 「一次快照只做一个动作」，或全程用选择器；报错里已经带上快照的年龄与元素范围 |
| 换了浏览器之后所有站点都退登录了 | 看 `start` 回执里的 `data.profileSeenBefore` / `data.profileNote`（换引擎等于换一套登录态） |
| 操作一个 id 得到「没有找到对应的浏览器实例」 | 看报错里的服务启动时间：实例只在内存里，**服务重启即失效**，`list_tasks` 确认后重新 `start` 即可（登录态在 profile 里，不会丢） |
| **报错说 `Object doesn't exist: response@…`，可这条命令根本没碰过什么 response**；而且换一条毫不相干的命令还是报同样的对象 | 这是 Playwright 事件泵投递过来的**伪故障**（见 `references/pitfalls.md` 第 47 条），不是页面坏了：只读命令服务端**已自动重发**，回执里会多一个 `data.spuriousRetry`；`execute_js` 读页面时请传 `retryOnSpurious: true`；**点击/提交/支付类命令绝不要自动重发**，先读页面状态 |
| **`get_element_screenshot` / `click_element_by_selector` 用 `[class*=xxx]` 报 `ACTION_TIMEOUT`（等元素可操作超时），可 `get_element_count` 明明说匹配到好几个** | 选择器命中了**隐藏**节点：Playwright 只对可见元素做可操作性检查，隐藏的那个会一直等到超时。先 `get_element_count` 看数量，再用更精确的选择器、或改用索引（快照里只有可见元素才有索引）。实测登录页的 `[class*=qrcode]` 命中 3 个，只有第一个是真二维码 |
| **`get_modals` 回 `count=0`，但页面上确实有一层挡着点不动** | `count=0` 只说明「框架弹窗 / 类名线索 / 几何兜底」这三轮扫描都没命中，**不是「绝对没有遮挡」**：新版站点的「引导层 / 新手蒙层」常常既没有 `role=dialog`、类名也不含 dialog/modal。改用 `get_browser_state` 读文本找「暂不体验 / 我知道了 / 跳过」这类按钮，按索引点掉，元素数会立刻从个位数涨到几十上百 |
| 写了新站点 skill，`SkillDocConsistencyTest` 报「命令表里不存在」 | 页面里的 snake_case 标识符（Vue 字段 / CSS 类名 / id / URL 参数）请用**双反引号**包起来，见 `docs/SKILL-CONVENTIONS.md` |
| **每条命令都慢了几十秒，而且一张图都拿不到** | 看回执里的 ``capture_degraded``：自动截图在这个页面上根本成功不了，已熔断。**别再指望画面**：改用 `diff_dom_text` 等文本取证，关键步骤请人看一眼；调 `browser.capture.timeoutMs` / `browser.capture.failThreshold` / `browser.capture.cooldownMs` |
| **报 `ELEMENT_NOT_FOUND`** | 选择器**一个都没匹配到**（不是超时、不是被遮挡）：先 `get_element_count` 复核数量，再检查父子/兄弟关系写错了没有。别去查监听器、别去查遮挡 |
| **`get_element_count` 说有好几个，但点击/输入就是不可操作** | 匹配到的多半是**隐藏副本**（同名控件在隐藏弹窗里还有一份）：动作类命令现在会优先挑可见的那个，看回执的 `matched` / `chosenIndex` / `visibleMatched` / `hiddenMatchNote` |
| **回执里 `probeTrustworthy:false` / `observationComplete:false`，还附一个 `coveredBy`** | 页面正在导航或整页重建，**这次取证不可信**：`changed` 与 `coveredBy` 都是假象（实测两次点击其实都成功了）。不要重复点击，等几秒重新 `get_browser_state` |
| **报 `because "frame" is null`，可这条命令只是只读查询** | `PAGE_NAVIGATING`：页面正在刷新/重建，**可重试**（约 1 秒后重发），只读命令服务端已自己等过 |
| **`send_keys` 回了 `ok:true` 但页面毫无反应** | 看回执里的 `focused`：焦点可能根本不在输入框上（`isBody:true` 时会给 `focusNote`）。先 `click` 目标输入框，再送键 |
| **`go_to_url` 报失败，可地址栏其实已经跳过去了** | 幂等导航现在会读地址栏核对（忽略 `?vd_source=…` 这类会话参数），到达了就按成功返回并带 `data.warning` |

## 一、最小可用：请求、客户端、自省

所有操作都是同一个端点，请求体是 `{id, method, params}`：

```json
{ "id": 1001, "method": "start", "params": { "headless": false } }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 见下 | 任务 ID。`start` 时可以传（作为任务标识），不传就自动生成雪花 ID；**其余方法必填** |
| `method` | 是 | 方法名，就是下面「命令速查」里的名字，也是批量 `commands` 里的键 |
| `params` | 否 | 该方法自己的参数，省略等于空对象 |

- **只支持 POST + JSON 请求体**。参数不再放查询串或表单，也不需要 URL 编码，中文直接写在 JSON 里即可。
- 响应信封：`{"data":{},"code":1,"ok":true,"error":null,"msg":null}`。`code=1`/`ok=true` 成功；`code=0`/`ok=false` 失败，原因在 `msg`（中文）。
- **任何参数问题都返回 JSON 错误，不再有 HTTP 500**：缺必填参数得到 `click_element_by_index 失败：缺少参数 index`，方法名不存在得到 `不支持的方法：xxx`（还会按编辑距离给近似建议），请求体不是合法 JSON 得到 `请求体不是合法 JSON：...`。实例不存在时统一返回 `没有找到对应的浏览器实例：<id>`。

```shell
BASE=http://localhost:10049/playwright/command

# 启动（headless=true 无头；false 会弹出真实窗口）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"start","params":{"headless":false}}'
# {"data":{"id":"1001"},"code":1,"ok":true,...}

# 打开页面（data 里带回自动截图的地址）→ 取状态（AI 读 browser_state 与 text）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"go_to_url","params":{"url":"https://example.com"}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"get_browser_state","params":{}}'

# 按索引操作（索引来自上一步的 [index]）→ 关闭
curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"id":1001,"method":"click_element_by_index","params":{"index":0}}'
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{"id":1001,"method":"close"}'
```

### 也可以不手拼 JSON：用现成客户端（**首选**）

手工拼 `-d '...'` 在参数带中文、引号、换行时很容易出错（PowerShell 尤其爱吃掉引号），返回体还得自己解析。仓库里的 `dsb` 客户端把这几件事都替你办了：**子命令式传参**、**批量与异步**、**每一步的请求与响应都留档**。凡是「发请求 → 读页面 → 再发请求」的任务，用它比手拼 JSON 少一大类无谓的失败。

```shell
# Windows 上直接敲 .\client\dsb.cmd（cmd.exe 里写 client\dsb.cmd）；
# 其它平台用 python client/dsb.py，参数完全一致。
.\client\dsb.cmd --port 10049 health
.\client\dsb.cmd --port 10049 --id 1001 start --browser chrome --headful
.\client\dsb.cmd --port 10049 --id 1001 run go_to_url -p url=https://example.com
.\client\dsb.cmd --port 10049 --id 1001 state --full          # 标题/URL/元素/结构化文本
.\client\dsb.cmd --port 10049 --id 1001 js @脚本.js --var who=dsb
.\client\dsb.cmd --port 10049 --id 1001 batch cmds.json --async --wait   # 长批次不受 HTTP 超时限制
.\client\dsb.cmd --port 10049 selftest --browser chrome       # 不确定服务端状态时先自检
```

**退出码 0 成功 / 1 传输错 / 2 业务失败 / 3 用法错** —— 把「服务没起」与「业务失败」分开了，写脚本时不用去解析 `msg` 猜。还有两个直接好处：`steps.log` 一行一次调用（时间、序号、任务 ID、方法、成败、耗时、摘要），第几步开始不对一眼就能看出来；每一步的请求与响应都留档。**多行脚本不要写在命令行里**（经 cmd/PowerShell 传参会只剩第一行），用 `js @脚本.js`、`--params @文件.json` 或 `batch cmds.json`。完整用法（含 `--summary` 与 `responseMode` 的区别、脱敏规则）见 `references/client.md`。

不确定有什么能力时先问服务自己：`list_methods`（方法名拿不准别猜）、`get_config`（生效的引擎 / profile 目录 / 超时与降级开关 / 脚本与日志目录）、`list_tasks`（活着的任务与共享浏览器）。三者也有 GET 版本：`GET /playwright/methods`、`GET /playwright/config`、`GET /playwright/tasks`。

## 二、读页面：`get_browser_state`

`get_browser_state` 是阅读页面的唯一入口。它在页面里执行 buildDomTree，把 DOM 转成 AI 可读的结构化文本，缓存本次快照，同时**截一张图**并把文本落盘。

| 参数 | 说明 |
| --- | --- |
| `highlight` | 是否在页面上画高亮框，默认 `true`（便于人工观察，不影响返回文本） |
| `viewportExpansion` | 视口外扩像素，默认 `0`；想一次拿到首屏之外的更多元素就调大（例如 `1000`） |
| `includeElements` / `maxElements` | 是否在 `data.elements` 里内联元素清单（默认 `true`）与内联条数上限（默认 200） |
| `includeFrames` | 是否把**跨域 iframe** 里的元素也纳入快照，默认 `false` |

最常读的返回字段（完整清单见 `references/reading-pages.md`）：

| 字段 | 说明 |
| --- | --- |
| `data.browser_state` | 页签信息文本块，最后一行 `current tab is: N` 指出当前页签（**1 基**） |
| `data.text` | 给模型读的可交互结构化文本，每行形如 `[index]<tag attr='value'>文本/>`，`[index]` 就是元素索引 |
| `data.tabs` | 页签数组：`index`（**0 基**）、`url`、`title`、`current`。**与上面的 1 基编号是两套** |
| `data.pixels_above` / `data.pixels_below` | 视口上方/下方还有多少像素没进快照。**非 0 就说明元素在视口外、没有索引** |
| `data.frames` / `data.frameCount` / `data.frameHint` | 只在 `includeFrames: true`（或页面里确实有没纳入的跨域 frame）时出现 |
| `data.viewport_height` / `data.page_height` | 视口高度与整页高度 |
| `data.seq` / `data.screenshot` / `data.screenshot_path` / `data.state_file` | 本次落盘的截图与结构化文本。**前三个只是截图地址：非必要不要读图**（见开头铁律）；真正要读的是 `data.text` |

**`data.text` 的五条读法**（误判高发区，逐行解释与真实样例见 `references/reading-pages.md`）：

- **索引不连续**（`[12]` 后面直接是 `[14]`）：只有 buildDomTree 判定为可交互的节点才有索引，不要把它当行号或「第 n 个元素」。
- **行尾 `/>` 只是格式化后缀，不代表自闭合**：`[0]<a >新闻/>` 里的 `新闻` 就是链接文字。
- **没有 `id`、`class`、`href`、`style`**：所以从快照里看不出链接地址、也认不出 CSS 类名。要这些属性就用选择器类命令或 `execute_js`；**只想看属性就先用 `get_interactive_map`**，它一次回查全部元素的 `index → tag/id/className/href/name/text`。
- **不可见的元素既不进快照，也不能用「原生方式」操作**（隐藏元素不满足可操作性检查）；实测百度首页的真实搜索框 `INPUT#kw` 因为 `offsetParent === null` 就不在 `data.text` 里。
- **快照是快照**：点击、跳转、异步渲染之后索引会重算，必须重新 `get_browser_state`；沿用旧索引可能得到越界或操作超时。

### 整页读不到元素 → 内容在跨域 iframe 里

企业微信后台把「邮件」应用套在 `exmail.qq.com` 的跨域 iframe 里（微盘 / 文档 / 会议同理），顶层 `document` 里**一个元素都没有**，`execute_js` 与 `get_modals` 同样只扫顶层文档——**但这不是浏览器能力的限制**，Playwright 本身能跨 frame。服务端把 `page.frames()` 暴露出来之后，同一页就能完整读到：

```json
{"id":"1001","method":"list_frames","params":{}}
{"id":"1001","method":"get_browser_state","params":{"includeFrames":true,"highlight":false}}
{"id":"1001","method":"click_element_by_selector","params":{"selector":"a[href*=domain]","frame":"exmail.qq.com"}}
```

- `includeFrames: true` 时每个 frame 的元素都进**同一个索引空间**，`data.text` 用 `--- frame[i] <url> elements=N index=a..b ---` 标出边界；只有一个 frame 的普通页面**不会**出现这行。
- **按索引的命令不需要传 `frame`**：索引里已经带了 frame 信息，服务端会把动作发到索引所属的那个 frame 里（iframe 重新加载过也会按 URL 重新认一次）。点击回执的探针也取在目标所在的 frame 里（回执带 `data.probedFrameUrl`）。
- **按选择器 / 执行 JS / 人工介入要显式传 `frame`**：`click_element_by_selector`、`input_text_by_selector`、`get_element_count`、`wait_for_element`、`upload_file`、`get_element_screenshot`、`ocr_image`、`request_human_input`、`execute_js` 都接受 `frame`（frame 序号，0 是主 frame；或 URL / name 子串）。**`request_human_input` 的 `frame` 尤其容易漏**：验证码/二维码经常就嵌在 iframe 里。
- 直接打开 iframe 的 `src` 往往打不开（URL 里带的是**一次性 token**，已被 iframe 消费掉），要绕过得从主站的发 token 接口现取一个，见 `references/reading-pages.md` 的 `wework-qykit-open-console` 配方。

## 三、读页面的其它入口与浏览器选择

### 状态汇总与快照差异

| 方法 | 什么时候用 |
| --- | --- |
| `get_page_snapshot` | 想一次拿到 url / 标题 / 页签 / 弹窗 / 是否还在加载（可选带控制台与请求），替代五六次单独调用；**不含 DOM 快照文本** |
| `diff_dom_text` | 判断「页面到底动没动」：重新执行一次 buildDomTree 与上次快照按行做差，返回 `data.changed`/`added`/`removed`。**不产生新的截图/文本文件** |
| `get_form_state` | **填完一屏表单后对一遍**：一次读回每个控件的标签/当前值/是否可见/是否禁用/校验错误，比逐个 `get_element_value` 省调用，也更容易发现「值填了但没进模型」的字段（`data.errors` 直接给「哪个字段、错在哪」） |
| `get_interactive_map` | 补上快照里没有的 `id`/`class`/`href`，以及每个元素的 `hasListeners`（这个元素有没有挂事件监听器） |

### 一个浏览器，多个任务

服务用的是**本机安装的 Google Chrome**（没装才退回内嵌 Chromium）和**一份共享的持久化 profile**（默认在 `~/.config/browseruse/profiles/shared`）：所有任务共用同一个 Chrome 进程与同一份 profile，任务之间靠**页签**隔离。登录一次就留在 profile 里，后续任务不用再登。

> 为什么不再一个任务一份 profile：用户数据目录天生是单例（同一个目录同时只允许一个 Chrome 进程），所以「共用一份 profile」和「一个任务一个浏览器」只能二选一。想直接用用户日常那份 profile 的话：Chrome 136 起**不允许在默认用户数据目录上开启远程调试**，所以默认不走这条路。

`start` 时传 `browser` 换一个再试 —— 这是「一个站点在这个浏览器下用不了」时最容易见效的一招：

| `browser` 取值 | 用的是什么 | 说明 |
| --- | --- | --- |
| 不传（或 `auto`） | 本机安装的 Google Chrome，没装则内置 Chromium | 默认，行为与以前完全一致 |
| `chrome` | 本机安装的 Google Chrome | 没装会**直接失败**（不会偷偷换成内置的） |
| `chromium` | 内置的那份 Chromium | 完全不碰本机 Chrome，适合对照「是不是本机扩展/登录态干扰」 |
| `edge` | 本机安装的 Microsoft Edge | 用**它自己一份 profile**；走「自己拉进程 + CDP」，`set_credentials` 不可用，UA 里会出现 `Edg/` |
| `firefox` | Playwright 自带的 Firefox | 等价于服务端把 `browser.engine` 配成 `firefox`；**`pdf` 命令不支持** |

也可以简写成 `msedge` / `google-chrome` / `ff` 这些别名。**先看返回再干活**：`data.browser.type` 就是这次实际用的浏览器，`data.browser.note` 非空说明服务替你做了退让。

**注意三件事**：

- **一次只能有一个浏览器**：任务还在跑时 `start` 一个不同的 `browser` 会失败。要换就先 `close` 掉在跑的任务再 `start`，**不用重启服务**。
- **换浏览器等于换一套登录态**：`chrome`／`chromium` 共用 `browser.profileDir` 那一份；`edge` 与 `firefox` 各用各的（Firefox 的 profile 格式与 Chromium 系不通用）。
- **`engineHonored:true` 只说明「浏览器参数被采纳了」，不说明「这份 profile 里有登录态」**：要看 `data.profileSeenBefore`（这份 profile 之前用过吗）与 `data.profileNote`（例如「本次首次创建，任何站点都要重新登录」/「引擎从 X 切到 Y，登录态不通用」）。实测踩过：服务端默认引擎被配成 `firefox`，而企业微信 / DNSPod / 腾讯云的登录态都在 Chromium profile 里。

其余要点：同一个 `id` 不能重复 `start`；每个任务有自己的一组页签（别的任务的页签看不见也点不到）；`close` 只关这个任务的页签，**最后一个任务关闭时**浏览器才退出；实例只在内存里，服务重启后 id 失效（登录态在 profile 里，仍在）；服务没有鉴权，默认只监听本机。各引擎的差异表、profile 目录按端口分开、强杀服务留下孤儿浏览器怎么处理，见 `references/browsers.md`。

## 四、命令速查（全部 116 个方法）

下面的名字就是 `method` 的取值，也是 `params` 里的参数名。**参数与返回字段的全量表**见 `references/commands.md`；下列各表里省略了每个方法都要带的 `id`（任务 ID）。

### 实例生命周期

| 方法 | 说明 |
| --- | --- |
| `start` | 开始一个任务（`id` 可选、`headless` 默认 `true`、`browser` 见上）；返回 `data.id` 与 `data.browser`（这次用的浏览器与 profile） |
| `close` | 关掉**这个任务自己的页签**，别的任务不受影响；最后一个任务关闭时浏览器才退出 |
| `shutdown` | 关掉所有任务与共享浏览器（服务进程不退出）。比直接杀进程干净：不留占着 profile 目录的孤儿浏览器 |

### 导航与页面信息

| 方法 | 说明 |
| --- | --- |
| `navigate` / `go_to_url` | 打开地址（两者等价）；`go_to_url` 是幂等导航，会读地址栏核对是否真的到了 |
| `go_back` / `go_forward` / `reload` | 后退 / 前进 / 刷新 |
| `get_url` / `get_title` | 读当前 URL / 标题 |
| `get_browser_state` | 页签信息 + 元素索引 + 结构化文本（见第二节） |
| `list_frames` | 列出页面上的**全部 frame**（含跨域 iframe），`index 0` 固定是主 frame。**顶层读不到元素时先看它** |
| `wait` | 固定等待 `seconds`（必填）；要等条件就用下面的 `wait_for_*` |

### 元素交互（按索引）

| 方法 | 说明 |
| --- | --- |
| `click_element_by_index` | 单击；返回点击回执（见下） |
| `double_click_element_by_index` | 双击 |
| `hover_element_by_index` / `focus_element_by_index` | 悬停 / 聚焦 |
| `check_element_by_index` / `uncheck_element_by_index` | 勾选 / 取消勾选复选框、单选框 |
| `input_text` | 覆盖式填充（会清空），`text` 必填 |
| `type_text` | 逐字输入，**不清空**原有内容 |
| `send_keys` / `key_down` / `key_up` | 按键（`Enter`、`Tab`、`Control+A`、`ArrowDown`）/ 按住 / 松开 |
| `upload_file` | 上传文件：`path` 是**服务器本地路径**，`index` 与 `selector` 二选一（**优先 `selector`**：隐藏的 file input 没有索引） |
| `drag_element_by_index` | 把第 `index` 个元素拖到第 `targetIndex` 个元素 |
| `get_dropdown_options` / `select_dropdown_option` | 读下拉选项 / 按**选项文本**（label）选择 |
| `scroll` / `scroll_to_text` | 翻页（`down` + `numPages`）或对某元素滚动；`scroll_to_text` 找不到文本会等满 30 秒，别用它探测元素是否存在 |
| `clear_text` | 清空输入框（`index` 与 `selector` 二选一）。`input_text` 的 `text` 必填，所以清空走这里 |

点击类与输入类命令都接受 `mode`，另可传 `timeoutMs` 按次覆盖超时（默认 5 秒）：

| `mode` | 行为 |
| --- | --- |
| 不传 / `auto` | 原生方式 → 失败改**真实鼠标** → 再失败改 JS 派发；目标被遮挡时跳过鼠标档直接走 JS |
| `native` | 只用原生方式；失败就是失败 |
| `mouse` | 只用真实鼠标点击（不做可操作性检查） |
| `js` | 直接在页面里派发事件，**跳过可操作性检查** |
| 输入类的 `fill` / `type` | 只用真实输入（`fill` 覆盖式、`type` 逐键） |

- 为什么要降级：有些站点（实测 ant-design 的 Vue SPA）在 Playwright 的可操作性检查下会等满超时返回 `[ACTION_TIMEOUT] 等待元素可操作超时`，而元素明明在那里、点上去也有反应。回执里 `data.mode` 是**这次实际用上的**方式，`data.fallbackReason` 给出原生失败的原因；`data.effective` 表示这次点击有没有真的改变页面。**只看 `ok:true` 会误判。**
- **JS 设值不等于进了框架模型**：`input_text` 走 JS 时会回 `data.committed=false`，DOM 上能看到值、但 Vue/React 的 model 里可能是空的——预览或提交校验会因此报「不能为空」。这类字段要用 `input_text_by_selector`（可见字段默认走真实输入）重填一遍。

**点击回执**里除了 `data.changed`（URL、页签数或页面指纹变化）/ `data.changeStatus` / `data.hit` / `data.coveredBy` / `data.probeTrustworthy`，还有 `data.urlBefore`/`urlAfter`、`data.tabCountBefore`/`After`、`data.textLengthBefore`/`After`。**`ok=true` 只代表动作没抛异常，不代表点中了东西**；`changed=false` 也不代表点击失败（观察窗口约 500ms），**禁止仅据此重复提交**，要用目标元素、文本或网络响应确认。

### 读取元素信息与状态

| 方法 | 返回 |
| --- | --- |
| `get_element_text` / `get_element_html` | `data.text`（innerText）/ `data.html`（innerHTML） |
| `get_element_value` | `data.value`（输入框的 value） |
| `get_element_attribute` | `data.value`（`name` 指定的属性值，可为 null） |
| `get_element_listeners` | 这个元素挂了哪些事件监听器：`data.vue2`/`vue3`/`react`/`inline`/`jquery`、`data.listeners`、`data.hasListeners`、`data.detection`。**「有没有挂事件」是 SPA 自动化的基础诊断信息**，凡「设了值 / 上传了文件但页面没反应」先查它 |
| `get_element_count` | `data.count`（CSS 选择器匹配数量，不需要索引） |
| `get_element_box` | `data.x/y/width/height`；元素不可见时失败 |
| `is_visible` / `is_enabled` / `is_checked` | `data.visible` / `data.enabled` / `data.checked` |

> **`hasListeners` 有三态，别把 `null` 当成 `false`**：`true` 确认有（CDP 清单非空或探到框架痕迹）、`false` **确认没有**（只有 CDP，即 `data.detection: "cdp"` 才给得出这个结论）、`null` **未知**（既没走 CDP 也没探到框架痕迹；原生 `addEventListener` 在元素上不留可枚举痕迹）。元素清单（`get_interactive_map`）里的 `hasListeners` 是**启发式的**，只会是 `true` 或 `null`，永远不会是 `false`。

### 按选择器 / 文本 / 语义定位（快照过期时的兜底）

| 方法 | 说明 |
| --- | --- |
| `click_element_by_selector` | CSS 选择器取第一个匹配并点击；**点完如果弹出新页签会自动切过去并带到最前**；目标在跨域 iframe 里时传 `frame` |
| `input_text_by_selector` | 覆盖式填充。**可见字段默认走真实输入**（进框架模型），这是「值填了但预览/校验说为空」时的正解 |
| `click_element_by_text` | 按可见文本定位，**先向上找最近的可点击祖先**（`a`/`button`/`[role=button]`/`[onclick]`），找不到才点文本节点本身；返回真正命中的 `data.tag`/`data.outerHtml` |
| `click_element_by_role` | 无障碍角色：`role` 如 `button`、`link`、`textbox`、`checkbox`，可配 `name` |
| `input_text_by_label` | 按表单标签 / `aria-label` 定位输入框 |
| `hover_and_click` | 悬停后**立刻**点同一个元素（`hoverDelayMs` 默认 300 毫秒）。悬浮菜单专用：分两步调用中间隔着一次推理往返，菜单早收起来了 |

### 标签页

| 方法 | 说明 |
| --- | --- |
| `get_tabs` | 列出页签（`index`/`url`/`title`/`current`，`index` 是 0 基） |
| `new_tab` | 新建标签页并切换过去；新页签会被带到最前 |
| `switch_tab` | 按 `pageIndex` 切换当前操作的标签页。**参数叫 `pageIndex`，而回执里每个页签的字段叫 `index`**（传错了服务端会直接把对应关系写出来） |
| `switch_tab_by_url` | 按 URL 匹配切换。**页签多了 `pageIndex` 不稳定（实测点一次菜单会弹出两个同 URL 的重复页签），优先用它** |
| `close_tab` / `close_other_tabs` | 关闭标签页 / 关掉除指定页签外的全部。**清重复页签用后者**：一个个 `close_tab` 会因为索引整体前移而关错 |
| `bring_to_front` | 把指定页签带到窗口最前，只切窗口、**不改当前操作页**，适合「让人看一眼这一页」 |

以上页签方法（以及 `click_element_by_selector` 命中弹窗时）都会调用 `bringToFront()`：**有头模式下浏览器窗口会跟着切到智能体正在操作的那个页签**，人工可以直接看到当前进度。

### 等待（`timeoutSeconds` 不传默认 30 秒；超时返回 `xxx 超时：等待时间内条件一直没有满足`）

| 方法 | 说明 |
| --- | --- |
| `wait_for_element` / `wait_for_text` / `wait_for_url` | 等元素出现 / 等文本出现 / 等 URL 匹配（支持 `**/path` 这类通配） |
| `wait_for_load` | 等加载状态：`state` 取 `load` / `domcontentloaded` / `networkidle` |
| `wait_for_function` | 等 JS 表达式为真，如 `() => document.readyState === "complete"` |
| `wait_for_idle` | 等页面「忙完」：连续 `quietMs`（默认 500）毫秒既没有 DOM 变更、也没有在途请求。**不知道要等多久时用它**，比固定 `wait` 稳 |
| `wait_for_stable` | 等**内容**稳定（正文 / 表单值 / 表格内容），返回 `data.stable`、`data.changes` 与**稳定后的文本 `data.text`** |
| `wait_for_count` | 等选择器命中数量达标：等弹窗/遮罩**全部消失**用 `max: 0`，等结果行**出现**用 `min: 1` |

**`wait_for_idle` 看「忙不忙」，`wait_for_stable` 看「内容变没变」，两者不能互相替代**：查询结果是异步刷新的，页面可能一直在动（动画、轮询计时器）但真正要读的内容已经定下来了，这时用 `wait_for_stable`。

> **查完立刻读结果是错的**：实测点「查询」后马上读表格，读到的是**上一次**的搜索结果。正确顺序是 `click` → `wait_for_stable`（或 `wait_for_count`）→ 再读。

### 鼠标

| 方法 | 说明 |
| --- | --- |
| `mouse_move` / `mouse_down` / `mouse_up` / `mouse_wheel` | 移动鼠标到坐标 / 按下 / 松开 / 滚轮（`deltaY` 正数向下） |
| `mouse_click` | **真实鼠标点击一个坐标**（一条顶原来的 `mouse_move`+`mouse_down`+`mouse_up` 三条） |
| `mouse_click_by_selector` | 真实鼠标点击某个元素**中心**（自己算坐标，不用先 `get_element_box`） |

**真实鼠标事件是唯一能让某些控件生效的方式**：实测 ant-design 的 `Modal.confirm`「确定/取消」、对话框右上角的 ×，用 JS 派发 `click`（甚至元素原生 `el.click()`）**完全无效**——点了没反应、弹窗不关，而接口照样回成功。

### 截图与 PDF

| 方法 | 说明 |
| --- | --- |
| `screenshot` | **默认落盘**（不传 `path` 时写到 `data/<id>/shot-N.png`），只回 `data.path`/`data.url`/`data.size`；传 `index` 或 `selector` 时**只截该元素**，否则截整页；要内联 base64 才传 `inline: true` |
| `get_element_screenshot` | 只截一个元素（验证码、二维码、图表的标准做法）：走 Playwright 自己的元素截图，**不受 canvas 跨域污染限制**，而且只截一个元素、比整页图省得多 |
| `pdf` | 存 PDF，不传 `path` 落到 `~/Downloads/broswer/`。**只有 Chromium 支持**，Firefox 下会返回失败原因 |

### Cookie 与本地存储

| 方法 | 说明 |
| --- | --- |
| `get_cookies` / `set_cookie` / `clear_cookies` | 读（可按 `url` 过滤）/ 写（建议同时传 `url`，否则要自己保证 domain 合法）/ 清空 |
| `get_local_storage` / `set_local_storage` / `clear_local_storage` | 读（传 `key` 取该键，不传回整个 localStorage 的 JSON）/ 写 / 清空当前站点 |

### 浏览器设置

| 方法 | 说明 |
| --- | --- |
| `set_viewport` | 改视口尺寸 |
| `set_geolocation` | 设置地理位置（同时授予 `geolocation` 权限） |
| `set_offline` | 断网 / 恢复 |
| `set_headers` | 附加请求头（`headersJson`） |
| `set_credentials` | HTTP 基本认证。凭据只能在创建上下文时设置，所以**会重建整个浏览器**：只允许在「当前只有这一个任务」时调用，走 CDP 那条路时（用户自己的 Chrome profile 或 `browser=edge`）不支持 |
| `set_media` | `colorScheme` 取 `light` / `dark` / `no-preference` |

### 弹窗与控制台（两类**完全不同**的弹窗，别混）

| 类型 | 是什么 | 用哪个方法 |
| --- | --- | --- |
| 浏览器原生对话框 | `window.alert` / `confirm` / `prompt`，会阻塞页面 | `get_dialog` / `clear_dialog` / `set_dialog_behavior` |
| 页面里的 DOM 弹窗 | ant-design 的 `Modal`/`Modal.confirm`、用户服务协议层、抽屉 | `get_modals` / `close_modal` |

| 方法 | 说明 |
| --- | --- |
| `get_dialog` / `get_js_dialog` | 返回最近一次原生弹窗（`data.dialog`，可为 null）。**记录不会自动清除**，可能是很早以前的弹窗；`consume: true` 读后即清 |
| `clear_dialog` / `clear_js_dialog` | 清空弹窗记录 |
| `set_dialog_behavior` | 弹窗**默认自动确认**；`dismiss: true` 改成自动取消 |
| `get_modals` | 列出当前可见的 DOM 弹窗：`data.count`/`countBlocking`、`data.modals[]`（`kind`/`matchedBy`/`blocking`/`hasMask`/`className`/`title`/`text`/`buttons`/`closePoint`/`rect`/`zIndex`/`frameIndex`）、`data.top`（优先取 blocking 的那条）、`data.scannedBy`。**主 frame 与每个 iframe 各扫一遍**，iframe 里的坐标已换算成主页面视口坐标 |
| `close_modal` | 关掉 DOM 弹窗。`which` 取 `top`（默认）/ `first` / `all` / `class:<子串>`；**一律用真实鼠标点**，并且点完**校验数量是否真的减少**（看 `data.closed`/`countBefore`/`countAfter`） |
| `get_console_logs` / `clear_console_logs` | 读（`data.logs` 与 `data.errors`，各最多 200 条）/ 清空 |

- **`get_dialog` 是「最近一次弹窗」而不是「当前这一步的结果」**：实测提交验证码失败过一次之后，后面查询明明成功了，`get_dialog` 仍然返回上一轮的「验证码输入错误」。稳妥做法是**每次提交动作前先 `get_dialog` 加 `consume: true` 清一次**，动作后再读；判断新旧看 `data.dialog.seq`/`timestamp`。
- `get_modals` 按三轮扫描（框架专用选择器 / 类名线索 / 几何兜底），每条结果的 `matchedBy` 说明命中来源与置信度；整页遮罩与 sticky 页头**不算弹窗**（它们没有按钮、没有标题，只会把 `count` 抬高）。企业微信那种自有类名的弹窗（``qui_dialog``、``mall_invoice_dialog_container``）靠类名线索命中，关它用 `which: "class:<子串>"`。
- **反复点一个关不掉的弹窗会把确认框一层层叠起来**（实测叠到 16 个），之后所有「取第一个可见弹窗」的逻辑都在操作最老的那个。`data.closed` 为 `false` 说明点了但数量没减少，这时用 `get_modals` 拿 `closePoint`/`buttonPoints`，再 `mouse_click` 那个坐标。

### 网络

| 方法 | 说明 |
| --- | --- |
| `route` / `unroute` | 拦截（`action: abort`）或 mock（`action: mock`，可配 `body`/`status`/`contentType`）匹配 `urlPattern` 的请求 / 撤销（不传则移除全部路由） |
| `get_requests` | 请求记录：`method`/`url`/`resourceType`/`status`，带请求体的请求另有 `postData`（最多 4000 字符）。**只有元数据，没有响应体**；可用 `filter`/`resourceType`/`limit`/`since` 缩小范围 |
| `wait_for_response` | 等匹配 `urlPattern` 的响应并返回响应体。**先回看再等**：`lookBackSeconds`（默认 10 秒）内已收到过的匹配响应直接返回（`data.fromLookBack`），所以「点一下 → 等接口」顺序写就行 |
| `get_response_body` | 回看**已经发生过**的响应体（保留最近 100 个）。**响应体在收到的当下就抄了一份**，所以跳转/等一会儿照样读得到；抄不到（非 xhr/fetch、或超过缓存名额）才给 `data.bodyAvailable=false` 与提示 |

- **读接口数据用 `wait_for_response`，不要靠 `get_requests` 猜**：SPA 的数据都在 XHR 里，而 `get_requests` 只有 url 和状态码。典型用法（点一下 → 等接口 → 读 JSON）可以放在一个批次里。
- **`get_requests` 返回空不等于「没有请求」**：记录是从页签挂上监听那一刻开始记的，页面在这之前（或另一个页签里）发生的请求不会出现。这时看 `data.recordedSince` 与 `data.note`，再改用 `get_response_body`（它保留最近 100 个响应，跨页签）。
- `wait_for_response` 与 `switch_tab_by_url` 的**匹配规则**（先子串、再通配，`*`/`**` 都可以跨 `/`）与 `route`/`unroute` 的 `urlPattern`（Playwright 原生通配，`*` 不含 `/`）**不一样**，写 `route` 时注意末尾斜杠。

### 其它与服务自省

| 方法 | 说明 |
| --- | --- |
| `extract_structured_data` | 取正文（最多 20000 字符，读取时会临时隐藏高亮层）与链接 |
| `execute_js` | 执行任意 JavaScript，返回 `data.result`（见第六节）；在跨域 iframe 里执行要传 `frame` |
| `list_methods` | 全部方法名（**拿不准先查，别靠猜**） |
| `get_config` | 服务端**生效**配置：`engine`/`configuredType`、`profileDir`、`action`（超时与降级开关）、`jsDir`、`trace`、`upload`、`tasks`、`commands` |
| `list_tasks` | 当前活着的任务（含每个任务的页签数、在途请求、profileDir）与共享浏览器信息 |
| `cleanup` | 清理落盘产物（截图 / 结构化文本 / 追踪日志）。**默认只预演不删**，要真删必须显式传 `dryRun: false`；`scope` 取 `all`/`data`/`trace`/`upload` |
| `list_recipes` / `run_recipe` | 站点配方列表 / 跑一个配方（`vars` 注入 `{{变量}}`）。**配方不会自动生效**，必须显式点名；见 `references/commands.md` |
| `get_job` / `cancel_job` / `list_jobs` | 异步批次的结果查询（`includeResult` 默认 true）/ 取消（**协作式**：在下一步之前停下）/ 最近任务（只保留 50 个，服务重启即丢） |

### 人机协同

| 方法 | 说明 |
| --- | --- |
| `request_human_input` | 发起人工介入请求（验证码 / 短信码 / 扫码 / 支付）。传 `index`/`selector` 时把该元素截下来，**同时**回 `data.imageBase64`、`data.imagePath`、`data.imageUrl`（可直接 GET、能贴给用户）；一串动作用 `steps` 一次交办 |
| `submit_human_input` | 提交人工答复：单步给 `answer`；多步用 `stepId` 逐条回填，或 `answers: {"s1":"…"}` 一次回填多步 |
| `get_human_input` | 取答复：`data.status` 取 `pending`/`partial`/`answered`/`expired`。传 `timeoutSeconds` 时长轮询等待，到时间还没答复返回当前状态（**不算失败**） |
| `ocr_image` | 用**本机 OCR**（Windows 自带 `Windows.Media.Ocr`）把图上的文字读出来：给 `path` 读服务端已有的一张图，给 `index`/`selector` 则先截这个元素再读。**模型读不了图时的兜底** |

批量入口 `commands` 也是一次 `method` 取值（一次请求跑完一整个计划），见第六节。

## 五、智能体的交互循环

1. `start` 一次，自己指定或用返回的 `id`（整个任务复用同一个实例）。
2. `go_to_url`（或 `navigate`）打开目标地址。
3. `get_browser_state` 取回 `data.browser_state`（页签）与 `data.text`（每行的 `[index]` 就是可交互元素索引）。
4. 用索引方法执行动作：`click_element_by_index`、`input_text`、`check_element_by_index`、`select_dropdown_option`……
5. **页面只要发生变化（点击、跳转、异步渲染、弹窗）就回到第 3 步重新取一次**；没变化才可以继续用上一轮索引。判断「刚才那一下到底有没有变化」用 `diff_dom_text`，比重新读整页省 token。
6. 需要判断「有没有加载出来」时用 `wait_for_element` / `wait_for_text` / `wait_for_url` / `wait_for_load`，不要用固定 `wait`；不确定要等多久、只知道「等它忙完」时用 `wait_for_idle`；结果是异步刷新的表格用 `wait_for_stable`。
7. **默认不看图**：`data.screenshot` 只是截图地址（见开头的省 token 铁律）。只有验证码、二维码、图表、纯图片元素这类「文本表达不了」的场景才按需取图，优先 `get_element_screenshot`。
8. 需要读接口返回的 JSON 时用 `wait_for_response`（等新响应）或 `get_response_body`（回看最近的响应），不要只靠 `get_requests` 的状态码猜。
9. 想一次拿到页面当前状态（url/标题/页签/弹窗/是否还在加载）用 `get_page_snapshot`，不要连发五六次调用。
10. **填完一屏表单后先用 `get_form_state` 对一遍**。
11. 遇到登录、验证码、短信码、扫码、滑块验证、点击验证等无法自行处理的环节，**必须主动请求人类帮助**，暂停依赖该环节的操作并保留浏览器现场；确认人工处理完成后再继续，见第七节。
12. **能用批量就用批量**：第 3～7 步可以合并成一次 `commands` 请求（动作 + 读取混排，末尾放 `get_browser_state`），一次推理拿到全部结果，别一个动作往返一次。
13. 任务完成输出结论，最后 `close`；等待人类协助时任务尚未完成，不要关闭浏览器。

## 六、批量指令、断言与执行 JavaScript

`commands` 是**降低推理步数**的关键：把「动作 + 读取」打包成一次请求，一次模型推理就能拿到全部观察结果；一个批次 = 一个计划段（先做动作，末尾放一个 `get_browser_state`）。

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"input_text":{"index":14,"text":"Mac Mini M4"}},
  {"send_keys":{"keys":"Enter"}},
  {"wait_for_text":{"text":"Mac Mini","timeoutSeconds":10}},
  {"get_browser_state":{}}]}}
```

- `stopOnError` 默认 `true`（遇到第一个失败就停止），**批量里推荐显式传 `false`**：跑完并把每一步结果都返回，`data.results` 里每步的 `data` 原样带回来（`get_browser_state` 的 `data.text`、`execute_js` 的 `data.result`、动作类的 `data.screenshot` 都在）。
- 有失败时整体响应是 `code:0`，但 `data.results` 仍然完整返回，`msg` 指出第一条失败的命令。
- **长批次（几十秒以上）加 `async: true`**：立刻返回 `data.jobId`，批次在后台跑，用 `get_job` 取结果、`cancel_job` 取消。客户端超时**不代表批次停了**，它还在服务端继续跑。
- 除 `commands` 自身（不允许嵌套）之外**全部都能批量调用**，命令名与单独调用完全一致；数组最多 200 条。
- 布尔参数不传按 `false` 处理（和单独调用一致），所以批量里 **`scroll` 要写 `"down":true` 才向下**。
- **一个批次里前面的失败会影响后面**：批次是顺序执行的，索引类命令依赖同一次快照。

**每一步都可以带 `expect` 断言，在命令执行之后求值**——这是本工具最值钱的一个习惯，因为回执说 `ok:true` 不代表页面真的变了：

```json
{"click_element_by_selector": {"selector": ".ant-modal-confirm .ant-btn-primary", "mode": "mouse"},
 "expect": {"js": "document.querySelectorAll(\".ant-modal-confirm\").length", "equals": 0}}
```

`expect` 的固定写法是 `js`（必填）+ 一个匹配方式：`equals`/`notEquals`/`min`/`max`/`contains`/`truthy`（**只写 `js` 不写匹配方式时按真值判断**）。每步结果里多一个 `expectResult`（`passed`/`actual`/`matcher`/`expected`）；断言没过时批次整体 `code:0` 且 `data.expectFailed` 计数，但**命令本身不算失败**。完整字段见 `references/batch-and-js.md`。

**`execute_js` 要点**：`body` 支持三种写法（表达式、函数、含 `return` 的语句片段），**一定会 await Promise**——要现取接口值就直接写 `async () => (await fetch(url, {credentials:'same-origin'})).json()`，不要再用已废弃的同步 XHR 去绕。返回值必须 JSON 可序列化（DOM 元素只会得到 `ref: <Node>`，请先转成 `textContent`/`outerHTML`/`value`）。`body` 上限 100000 字符，脚本**没有超时**。跨域 iframe 要传 `frame`。

- **多行脚本不要写在命令行里**（会被 shell 截断，报的却是 `SyntaxError: Unexpected end of input`）：用服务端的 `bodyFile`（文件放在 `get_config` 的 `jsDir` 里）、客户端的 `js @脚本.js`，或 `--params @文件.json`。带参数时用 `vars` 注入 `{{变量}}`（走 JSON 编码，中文/引号/换行都不用转义）。
- **脚本重发无害时传 `retryOnSpurious: true`**：页面上报 `Object doesn't exist: response@…`（对象与脚本毫不相干）时服务端替你重发。**只给读页面/取值的脚本传**；会点按钮、提交表单的脚本不要传——那种脚本重发等于再执行一次。

## 七、人机协同（验证码 / 登录 / 人工介入）

**遇到无法自行处理的环节，必须主动请求人类帮助，不要反复尝试或直接放弃任务。** 常见情况包括需要人工登录、输入图片验证码或短信码、扫码、拖动滑块、按顺序点击图片/文字验证、设备确认。

1. 告诉用户当前停在哪个网站、遇到了什么问题、需要完成哪一步（例如「当前停在登录页，需要你完成登录和滑块验证，完成后请告诉我，我会继续」）。
2. 优先让用户直接操作有头浏览器（`headless=false`），用 `bring_to_front` 或 `request_human_input` 把当前页签带到最前。保留当前任务 ID、页面和浏览器；**等待期间不要刷新、关闭或继续点击验证控件**。无头实例无法显示时，先说明需要切到有头模式，保存当前 URL，再按同一 ID 先 `close` 后 `start`（可能丢失未提交的表单）。
3. 调 `request_human_input` 记录人工请求，并通过宿主工具的提问能力或对话消息明确通知用户；**不能假定接口返回成功就代表用户已收到通知**。登录、滑块、点击验证优先由用户在浏览器内完成，不要求用户在对话中提供密码。
4. 等待用户答复或观察到明确的完成状态；**请求超时只表示尚未收到答复，不代表验证已通过**，不要反复提交、不要猜答案。
5. 用户处理后重新 `get_browser_state`，确认已登录或验证已通过并取得新的元素索引，再继续原任务。

**模型读不了图怎么办**（``read_image`` 报 `model ... does not declare image input` 时），三条路按顺序试：

1. **先让服务端自己读**：`ocr_image`（或 `request_human_input` 加 `ocr: true`）用本机 OCR 直接把图上的文字返回；验证码这类印刷体识别率不错，能自己答就省掉一次人工往返。`data.ok:false` 且带 `data.engineMissing:true` 说明这台机器没装 OCR 语言包，才走下一步。
2. **把 `data.imageUrl` 贴给用户**（比本地路径好用：用户自己就能打开），让用户在对话里告诉你内容。
3. **请人直接在有头浏览器里操作**（扫码、滑块这类本来也只能人做）。

**要点**：

- **`get_element_screenshot` 是这套流程的地基**：没有它，`request_human_input` 也没东西可以给人看。取到图片**不代表验证已完成**。
- **验证码有时效且一次性**：实测税务系统的图片验证码约 **120 秒**过期，用过的码再提交必然失败。拿到答复后要**立刻**提交，不要攒着；提交失败先换一张新图再让人看。短时效凭证用 `expiresAt` 传绝对过期时刻，过期后 `get_human_input` 直接回 `data.status:"expired"`，而不是让人干等。
- **多步人机协同用 `steps` 一次交办**：扫码 + 输码 + 支付确认是一串动作，每次单独「请求 + 等待 + 取答复」要来回好几趟。`steps[]` 里每项可以各带 `prompt`/`selector`/`frame`；人做完一步回填一步（也可用 `answers` 一次回填多步），还有步骤没回填时 `data.status` 是 `partial`（并给出 `data.pendingSteps`）。
- **`request_human_input` 的 `frame` 尤其容易漏**：验证码 / 二维码经常就嵌在 iframe 里（实测企业微信登录页的二维码就是）。不传 `frame` 时 `selector` 只在顶层文档找，回执里的 `imageUrl` 会是空的——而「把人叫来看 iframe 里的验证码」恰恰是最需要它的场景。
- **登录态跟着共享 profile 走，不跟任务 ID 走**：换任务、换 id 都不影响；是否仍有效由网站决定。若 `data.browser.userProfile=false` 或 `chrome=false`，说明这次不是用户日常那份登录态。
- **用户直接在浏览器里操作不会自动更新人工请求记录**，`get_human_input` 可能仍为 `pending`。不要只等该字段，也不能直接跳过验证：重新读页面确认成功后才继续。

完整流程（含 `steps`、`expiresAt`、OCR 回执字段与 curl 示例）见 `references/human-in-loop.md`。

## 八、典型任务

| 任务 | 怎么做 |
| --- | --- |
| 打开页面取正文 | `go_to_url` → `get_browser_state`，把 `data.text` 交给模型 |
| 搜索 | `input_text`（搜索框索引）→ `send_keys` 加 `keys: "Enter"` → `wait_for_text` → 重取快照 |
| 勾选并提交表单 | `check_element_by_index` → `is_checked` 复核 → 提交前 `get_form_state` 对一遍 → 提交用 `click_element_by_role` 加 `name` |
| 下拉框 | `get_dropdown_options` 看选项 → `select_dropdown_option` 按选项文本选 |
| 页面变化后元素找不到 | 重新 `get_browser_state` 取新索引；元素没有索引就改用 `click_element_by_selector` 或 `execute_js` |
| 多标签页 | `get_tabs` 看列表 → `switch_tab_by_url` 切过去 / `close_other_tabs` 清理。注意 `data.browser_state` 里 `current tab is: N` 是 **1 基**，`pageIndex` 是 **0 基** |
| 抓接口 / 造数据 | `route` 加 `action=mock` 让接口返回假数据、`action=abort` 让请求直接失败；`get_requests` 看请求记录；`wait_for_response` 拿接口返回的 JSON；`unroute` 撤销 |
| 悬浮菜单 | `hover_and_click` 一步完成悬停 + 点击（分两步菜单会收起来）；菜单项只有文本没有索引时用 `hover_and_click` 加 `selector` |
| 判断点击到底生效没有 | 看点击回执里的 `data.changed`，或 `diff_dom_text` 确认快照有没有变。**不要为了这个去读 `data.screenshot`** |
| 事后复看某一步的页面 | `GET http://localhost:10049/data/<id>/<seq>.png`（截图）与同序号的 `.txt`（页签 + 可交互结构化文本，两者序号相同表示同一时刻） |

## 九、最常踩的 12 条坑（完整 53 条见 `references/pitfalls.md`）

1. **页面变化后索引全部重算**：点击、跳转、异步渲染之后必须重新 `get_browser_state`；沿用旧索引会得到 `索引越界` 或超时（报错里带快照年龄与元素范围，照它判断就行）。
2. **快照里没有 `id`/`class`/`href`**：按 id/class 定位用选择器类命令，取 href 用 `execute_js`，批量看属性用 `get_interactive_map`。
3. **快照里找不到明明在页面上的元素**：先看 `data.pixels_above`/`pixels_below` 是不是非 0（非 0 = 在视口外、没有索引）→ ① 传 `viewportExpansion` ② 改用选择器类命令 ③ 滚动后重取。
4. **`ok=true` 不代表点中了东西**：是否观察到变化看回执里的 `data.changed`/`data.hit`；`click_element_by_text` / `click_element_by_role` / `hover_and_click` 还会返回真正命中的 `data.tag`/`data.outerHtml`。
5. **`execute_js` 里的 `.click()` 触发不了「真点击才有的东西」**：JS 派发的 click 不是可信事件，`window.open` 会被浏览器拦掉，部分框架的提交按钮也不认它，**而接口照样回 `ok:true`**。要真点击用 `click_element_by_selector` / `click_element_by_index`。判断有没有生效看 `data.mode`（`js` 就是没走真实交互）与 `data.changed`。
6. **「设了值 / 上传了文件 / 派发了事件，但页面没反应」先查监听器**：`get_element_listeners` 或在 `get_interactive_map` 里看 `hasListeners`。`upload_file` 的回执会直接给 `data.consumed`（`listened`/`noListener`/`unknown`）与 `data.hint`；**看到 `noListener` 就别再换选择器了**，改用组件方法直调（`execute_js` 里拿到页面上的实例调它的 `upload()`），或先点它的可见父元素。
7. **`ELEMENT_NOT_FOUND` = 选择器一个都没匹配到**（不是超时、不是被遮挡）：去改选择器，先 `get_element_count` 复核数量，再检查父子/兄弟关系写错没有。
8. **`get_element_count` 说有好几个、点击/输入却不可操作**：多半命中了**隐藏副本**（同名控件在隐藏弹窗里还有一份，是 0×0）。动作类命令会**优先挑可见的那个**，把解析结果写进回执（`matched`/`chosenIndex`/`visibleMatched`/`hiddenMatchNote`）。
9. **回执里 `probeTrustworthy:false` / `observationComplete:false` 时这次取证什么都不能说明**：页面正在导航或 SPA 整页重建，`changed` 与 `coveredBy` 都是假象。**既不要重复点击**（可能重复提交），也不要急着重新取快照，等几秒再说。
10. **`frame` 为 null 是 `PAGE_NAVIGATING`**（用户刷新、SPA 重建），**可重试**（建议 1 秒后重发）；只读命令服务端会自己等页面回来。
11. **`send_keys` 回了 `ok:true` 但页面毫无反应**：看回执里的 `focused` —— 焦点可能根本不在输入框上（落在 `<body>` 上时会给 `focusNote`）。先 `click` 目标输入框再送键。
12. **`go_to_url` 报失败、可地址栏其实已经跳过去了**：幂等导航会读一次地址栏核对（比较主机+路径，忽略 `?vd_source=…` 这类会话参数），到达了就按成功返回并带 `data.warning`。

其余 41 条里最值得先翻的几类：跨域 iframe（第 37 条）、弹窗与遮挡（第 20 / 39 条）、网络响应体缓存（第 23 条）、`data/<id>/` 与日志不会自动清理（第 27 / 34 条）、强杀服务留下孤儿浏览器（第 32 条）、`get_dialog` 是「最近一次弹窗」（第 20 条）、`Object doesn't exist` 伪故障的机制（第 47 条）、整页截不出图的 ``capture_degraded``（第 48 条）、回读看不到 input 不等于上传失败（第 45 条）。
