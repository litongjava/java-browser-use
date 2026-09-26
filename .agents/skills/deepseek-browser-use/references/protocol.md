# 协议：请求、响应与留档

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

## 请求格式

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
| `method` | 是 | 方法名，就是 `commands.md` 命令全表里的名字，也是批量 `commands` 里的键 |
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

> 不想手拼 JSON 时用现成客户端 `dsb`，见 `client.md`。

## 响应格式

```json
{ "data": {}, "code": 1, "ok": true, "error": null, "msg": null }
```

- `code=1` / `ok=true` 成功；`code=0` / `ok=false` 失败，原因在 `msg`（中文）。
- **任何参数问题都返回 JSON 错误，不再有 HTTP 500**：缺必填参数得到 `click_element_by_index 失败：缺少参数 index`，方法名不存在得到 `不支持的方法：xxx`，请求体不是合法 JSON 得到 `请求体不是合法 JSON：...`。
- 实例不存在时统一返回 `没有找到对应的浏览器实例：<id>`。

## 响应精简模式（兼容原协议）

请求信封可选 `responseMode: "compact"`，默认保持完整响应。`ok`、`code`、`error`、`msg` 一律保留，包括成功时的 null；精简仅发生在 `data` 内。

```json
{"id":"1001","method":"get_browser_state","responseMode":"compact","params":{"highlight":false}}
```

- 页面状态保留 `tabs`（index 为 0 基、current 标明当前页），省略重复的 `browser_state` 和顶层 URL/标题。
- 保留截图 URL 与文本文件链接，省略本机截图绝对路径。不会省略截图错误。
- 点击回执省略前后 URL、页签数、正文长度及 outerHtml 等诊断字段；请求信封加 `diagnostics: true` 可保留这些字段。
- 批量结果逐条采用相同规则；网站返回的 body、脚本 result 及请求参数不会被递归删字段。
- `count` 为已执行条数；批量中每个成功的页面动作恰好归档一次，末尾取状态仍独立归档。单条与批量经过同一执行收尾。

## 表单状态和动作结果

页面文本中的 value、checked、selected 来自实时 DOM 属性，不只是初始 HTML。只读、禁用状态及 editable 也会输出；select 提供 selected-text。密码值在快照和文本留档中为 `[redacted]`。可见的禁用表单控件可能没有操作索引，但仍输出状态。

属性中的 name、value、状态值不再按 15 字符截断；title、placeholder、alt、aria-label、selected-text 的展示上限为 160 字符。普通布局容器不增加缩进，表单、菜单、表格等语义结构保留缩进，最多 6 层。

动作错误的 `data.errorCode` 区分 ELEMENT_READ_ONLY、ELEMENT_DISABLED、ELEMENT_HIDDEN、ELEMENT_OBSCURED、ELEMENT_NOT_EDITABLE、STALE_ELEMENT、ACTION_TIMEOUT 和 ACTION_FAILED；另有一个 SPURIOUS_DISPATCH 专门标「异常来自 Playwright 的事件分发、与本次命令无关」（见 `pitfalls.md` 第 47 条）。错误原因来自完整调用日志；没有充分证据的超时只报 ACTION_TIMEOUT。

点击回执会短暂等待异步变化（观察循环上限约 500ms，具体浏览器调用耗时另计）。`data.changeStatus` 为 observed 或 not_observed，`data.observationComplete` 指示探针是否成功，`data.observationWindowMs` 为观察窗口配置。`changed=false` 不表示点击失败，`changed=true` 也不表示查询、缴款等业务成功；应使用目标元素、文本或网络响应确认，禁止仅据此重复提交。

### 动作的三种执行方式（`mode`）与降级

点击类命令（`click_element_by_index`、`click_element_by_selector`、`click_element_by_text`、`click_element_by_role`、`check_element_by_index`、`uncheck_element_by_index`、`double_click_element_by_index`、`hover_and_click`）和输入类命令（`input_text`、`input_text_by_selector`、`input_text_by_label`）都接受 `mode`，另可传 `timeoutMs` 按次覆盖超时。

| `mode` | 行为 |
| --- | --- |
| 不传 / `auto` | 先用原生方式；普通失败且未发现遮挡时尝试真实鼠标、JS 降级；被遮挡时返回 `ELEMENT_OBSCURED`，对象释放异常不补点 |
| `native` | 只用原生方式；失败就是失败，不换方式 |
| `js` | 直接在页面里派发事件（点击派发 mousedown/mouseup/click），**跳过可操作性检查** |
| 输入类的 `fill` / `type` | 只用真实输入（`fill` 覆盖式、`type` 逐键） |

降级适用于部分框架的可操作性检查与实际交互不一致的场景。遮挡层可能承载短信验证或确认操作，不能自动用 JS 穿透点击背景按钮。应先读取当前弹窗，再精确定位控件。多个可见匹配会优先选择中心点能接收事件的控件；这不等于业务语义唯一，重要按钮仍需检查命中文本和所属弹窗。

- **降级不会被伪装成原生成功**：回执 `data.mode` 说明这次实际用了哪种方式，`data.fallbackReason` 给出原生失败的原因。看到 `mode=js` 就要知道「这次没走真实交互」，关键步骤（提交、缴费）建议再确认一次页面状态。
- **JS 设值不等于进了框架模型**：`input_text` 走 JS 设值时会回 `data.committed=false` 与 `data.note`。DOM 上能看到值、但 Vue/React 的 model 里可能是空的——预览页或提交校验会因此报「不能为空」。这类字段要用 `input_text_by_selector`（可见字段默认走真实输入，`committed=true`）重新填一遍。
- 超时默认 5 秒，可用 `browser.action.timeoutMs` 调大；`browser.action.jsFallback=false` 可整体关掉自动降级。

## 网络证据关联

`get_requests` 中每次请求有独立 requestId（雪花 ID 字符串）、requestedAt、method、url、resourceType；有请求体时附 postData、postDataLength、postDataTruncated。响应到达后回填 status 和 respondedAt，网络失败则记录 failure 和 finishedAt。

`get_response_body` 和 `wait_for_response` 返回相同 requestId、对应 request 元数据、respondedAt 和 ageMs。bodyLength 是完整响应字符数，truncated 明示是否截断，bodyAvailable 指示响应体是否可用。重复 URL 应优先通过 requestId 回查，避免把上次查询结果当成本次结果。

**响应体是「当场抄下来」的，不是「要的时候再去取」。** 浏览器只短暂保留响应体：实测在 12306 这种每秒轮询的页面上，一条 **7 秒前**的 XHR 再取 body 就是 `Protocol error (Network.getResponseBody): No resource with given identifier found`，一导航更是彻底没了——于是「保留最近 100 个响应」在真实站点上等于「一个都读不到」，而调用方最想知道的是「我这一步提交到底成功了没有」（当时只能靠后面又冒出了 `checkOrderInfo`/`getQueueCount` 反推）。现在收到响应时就把 **xhr/fetch** 的 body 抄一份存起来：单条最多 10 万字符（超出标 `bodyTruncated`、`bodyCachedChars`），同时抄的有名额上限（满了会说明「用 `wait_for_response` 重新等一次」）。非 xhr/fetch（文档、脚本、图片）不抄——它们又大又不是「接口返回」。

接口只报告网站返回的记录和查询条件：空列表或 Total=0 表示该条件下没有记录，不能自动解释为税额、余额等业务金额为零。

## 截图与可交互结构化文本（data/<id>/）

每次浏览的页面发生变化，服务都会自动留档，落在进程工作目录下的 `data/<id>/` 里：

| 时机 | 产物 |
| --- | --- |
| 「会改变页面」的方法执行成功 | `data/<id>/<seq>.png` 一张截图 |
| 每次 `get_browser_state` | `data/<id>/<seq>.png` 截图 **+** `data/<id>/<seq>.txt` 同名的可交互结构化文本 |

- `seq` 是**每个任务独立的自增序号，从 1 开始**：1.png、2.png、3.png、4.png…… 一对 `.png` / `.txt` 的序号相同，表示是同一时刻的页面。
- 这两个文件都可以直接 GET：`GET http://localhost:10049/data/<id>/<seq>.png`、`GET .../<seq>.txt`。视觉模型可以按 URL 取图，**但非必要不要取**（见 `SKILL.md` 开头的省 token 铁律）：截图只是留档，智能体读页面请读 `data.text` 或同序号的 `.txt`。
- 响应里的字段：`data.seq`、`data.screenshot`（URL，如 `/data/1001/3.png`）、`data.screenshot_path`（服务器本地绝对路径）、`data.state_file`（URL，只有 `get_browser_state` 有）。截图失败不会让方法失败，原因在 `data.screenshot_error`。
- `<seq>.txt` 的内容是「页签文本块 + 空行 + 可交互结构化文本」，也就是 `data.browser_state` 加 `data.text`，方便事后离线复看某一步的页面。
- 哪些方法算「会改变页面」：导航类（`navigate`、`go_to_url`、`go_back`、`go_forward`、`reload`）、点击与交互类、滚动与鼠标类、页签类、等待类、`execute_js` 与部分设置类。纯读取类（`get_url`、`get_cookies`、`is_visible`……）不截图，否则每读一个值就多一张一模一样的图。
- 截图前会尽力等页面进入 DOMCONTENTLOADED（最多 1.5 秒），等不到也照常截图，不会因为等待失败丢掉这一张。
- **出图的像素尺寸取决于视口策略**（服务端配置 `browser.viewport`，默认 `window` = 视口跟随真实窗口）。`window` 下 `devicePixelRatio` 是**真实系统 DPI 比**，出图 = CSS 视口 × 该比值 —— 实测 150% 缩放的屏幕上，1470×925 的视口出的是 **2205×1388** 的图；`fixed` / 显式尺寸下固定为 1.0，出图就是 CSS 视口那么大。所以**要把图上的像素位置换算成页面坐标**（`data.text` 里的坐标、`clip` 参数、`set_viewport` 用的都是 CSS 像素）**就除以 `devicePixelRatio`**。`start` 回执里的 `data.browser.viewport` 说明这次用的是哪一种。
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

## 动作与快照可靠性补充

点击成功返回后，回执的 `actionStatus:completed` 只说明动作调用完成，不保证业务成功。
后续探针失败时仍保留成功响应，并给出 `observationComplete:false`、`effective:null`；
如果整个观测过程异常，还带 `observationError` 和 `changeStatus:unknown`。
底层对象释放异常使动作本身是否完成无法判断时，回执包含 `actionStatus:unknown`、
`retrySafe:false` 和 `actionError`。未观察到变化时保持失败信封，但不能自动重复提交。
观察到变化也只是业务可能已执行的证据，仍应回读结果。

`get_browser_state` 在读取前后检查文档标识、URL、DOM 变动、frame 读取错误及元素回查。
排除服务自身高亮层的变动，以及截图引起的光标颜色样式变动；检测到不一致后最多重建一次，只重读，不重放操作。
返回 `snapshotConsistent`、`snapshotAttempts`、`indicesUsable` 和 `pageAppearsBlank`。
两次都不可靠时返回 `snapshotIssues`，并清除服务端索引快照。元素回查失败显示
`resolved:false` 与原标签，不再静默显示无标签元素。
主 frame 读取失败按读取错误返回，由只读重试策略处理，不伪装成正常空页面。

一致性检查不是业务完成条件：持续动画可能被标为不一致，SPA 在新 URL 下暂时保留旧内容也可能
在读取窗口内完全不变。应等待具体的表单、列表或响应，不以 URL 或 `snapshotConsistent:true` 代替业务确认。
