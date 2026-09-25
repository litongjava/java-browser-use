# 读页面：get_browser_state 与跨域 iframe

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

`get_browser_state` 是阅读页面的唯一入口。它在页面里执行 buildDomTree，把 DOM 转成 AI 可读的结构化文本，缓存本次快照，同时**截一张图**并把文本落盘（见 `protocol.md`）。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 任务 ID |
| `highlight` | 否 | 是否在页面上画高亮框，默认 `true`（便于人工观察，不影响返回文本） |
| `viewportExpansion` | 否 | 视口外扩像素，默认 `0`；想一次拿到首屏之外的更多元素就调大（例如 `1000`） |
| `includeElements` | 否 | 是否在 `data.elements` 里内联元素清单，默认 `true` |
| `maxElements` | 否 | 内联条数上限，默认 200 |
| `includeFrames` | 否 | 是否把**跨域 iframe** 里的元素也纳入快照，默认 `false`。见下面「跨域 iframe」 |

返回字段：

| 字段 | 说明 |
| --- | --- |
| `data.url` / `data.title` | 当前页的 URL 与标题 |
| `data.browser_state` | 页签信息文本块，格式见下 |
| `data.text` | 给模型读的可交互结构化文本，每行形如 `[index]<tag attr='value'>文本/>` |
| `data.tabs` | 页签数组：`index`（**0 基**）、`url`、`title`、`current` |
| `data.pixels_above` / `data.pixels_below` | 视口上方/下方还有多少像素没进快照 |
| `data.viewport_height` / `data.page_height` | 视口高度与整页高度 |
| `data.frames` / `data.frameCount` | 只有 `includeFrames: true` 时有：每个 frame 的 `index`/`url`/`name`/`isMain`/`depth`/`elementCount`/`indexRange` |
| `data.frameHint` | 顶层一个可交互元素都没读到、而页面上确实有 iframe 时出现：提示下一步该用 `includeFrames` / `list_frames` |
| `data.seq` / `data.screenshot` / `data.screenshot_path` / `data.state_file` | 本次落盘的截图与结构化文本，见 `protocol.md`。**前三个只是截图地址：非必要不要读图**，见 `SKILL.md` 开头的省 token 铁律；真正要读的是 `data.text` |

## 跨域 iframe（主站把第三方控制台套在 iframe 里）

**这是最容易被误判成「页面没加载完」的一类站点。** 企业微信后台把「邮件」应用套在 `exmail.qq.com` 的跨域
iframe 里（微盘 / 文档 / 会议同理），顶层 `document` 里**一个元素都没有**：

| 手段 | 只看顶层文档时的结果 |
| --- | --- |
| `get_browser_state` | 只拿到主站外壳，iframe 内部一个元素都没有（`data.text` 几乎是空的） |
| `execute_js` | 在顶层文档执行，`document.querySelector` 穿不透跨域 iframe |
| `get_modals` / `get_interactive_map` | 同样只扫顶层 `document` |

**但这不是浏览器能力的限制**：Playwright 本身能跨 frame（它走浏览器协议取内容，不依赖往页面里注入 JS）。
服务端把 `page.frames()` 暴露出来之后，同一页就能完整读到：

```json
{"id":"1001","method":"list_frames","params":{}}
```

```json
{"ok":true,"data":{"count":2,"frames":[
  {"index":0,"url":"https://work.weixin.qq.com/wework_admin/frame","name":"","isMain":true,"depth":0,"elementCount":63,"indexRange":"0-62"},
  {"index":1,"url":"https://exmail.qq.com/mail/mngpage?token=...","name":"","isMain":false,"depth":1,"elementCount":18,"indexRange":"63-80"}]}}
```

拿到 frame 之后有两种用法：

1. **一次取全（推荐）**：`get_browser_state` 传 `includeFrames: true`。每个 frame 的元素都进**同一个索引空间**，
   `data.text` 里用 `--- frame[i] <url> elements=N index=a..b ---` 标出边界，`data.elements[]` 每项带 `frameIndex`。

   ```json
   {"id":"1001","method":"get_browser_state","params":{"includeFrames":true,"highlight":false}}
   ```

   ```
   --- frame[0] (main) https://work.weixin.qq.com/wework_admin/frame elements=63 index=0..62 ---
   [0]<a >首页/>
   ...
   --- frame[1] https://exmail.qq.com/mail/mngpage?token=... elements=18 index=63..80 ---
   [63]<a >概况/>
   [64]<a >邮箱域名/>
   [65]<a >邮箱账号/>
   ```

   > **不加 `includeFrames` 会怎样**：默认快照只纳入**同源** frame（与历史行为一致：以前的服务端脚本本来就会顺着同源 `iframe` 递归）。同源 iframe 里的元素照样有索引、也能被按索引命令点到（服务端会自动路由），只是**跨域** iframe 一个元素都读不到 —— 需要它就传 `includeFrames: true`。只有当页面里确实还有没纳入的跨域 frame 时，回执才会带 `data.frameHint` 指出这一点。只有一个 frame 的普通页面**不会**出现 `--- frame[0] ---` 这行，文本与以前完全一样。

2. **按索引操作会自动路由**：`click_element_by_index` / `input_text` / `get_element_text` … **不需要**传 frame
   参数——索引里已经带了 frame 信息，服务端会把动作发到索引所属的那个 frame 里。iframe 重新加载过时，服务
   会按 URL 在当前 frame 树里把 frame 重新认一次，所以同一个快照里的索引不会因为 iframe 刷新就全废。

   点 iframe 里的元素时，点击回执的探针（`data.changed` / `data.effective`）也取在**目标所在的 frame** 里
   （回执里带 `data.probedFrameUrl`）—— 否则顶层文档一点都不会变，回执永远是 `changed:false`，把「生效了」
   误报成「没生效」。

3. **按选择器 / 执行 JS 要显式指 frame**：这类命令用的是 `page.locator(...)` / 顶层 `document`，够不着 iframe
   内部。`click_element_by_selector`、`input_text_by_selector`、`get_element_count`、`wait_for_element`、
   `upload_file`、`get_element_screenshot`、`ocr_image`、`request_human_input`、`execute_js` 都接受一个可选的
   `frame` 参数，取值是 frame 序号（0 是主 frame，见 `list_frames`）或 URL / name 子串：

   ```json
   {"id":"1001","method":"click_element_by_selector","params":{"selector":"a[href*=domain]","frame":"exmail.qq.com"}}
   {"id":"1001","method":"execute_js","params":{"frame":1,"body":"() => location.href"}}
   ```

   > **`request_human_input` 的 `frame` 尤其容易漏**：验证码 / 二维码经常就嵌在 iframe 里
   > （实测企业微信登录页的二维码就是）。不传 `frame` 时 `selector` 只在顶层文档找，
   > 回执里 `imageUrl` 会是空的——而「把人叫来看 iframe 里的验证码」恰恰是最需要它的场景。
   > `steps[]` 里的每一项也各自可以带 `frame`。

**要点与坑**：

- `list_frames` 默认会顺手重建一次带全部 frame 的快照（`refresh: false` 关掉），所以它返回的 `elementCount` /
  `indexRange` 就是当前页面的真实情况；`data.frames[]` 里带 `skipped: true` 的那些表示「这次快照没纳入它」，
  `skipReason` 说明原因。
- 某个 frame 读不出来（跨域被拒、正在销毁、还没有文档）**不会让整次快照失败**：那个 frame 的
  `elementCount` 是 0、`readError` 说明原因，其余 frame 照常可用（主站外壳能读到总比整页读不到好）。
- **`execute_js` 会 await Promise**：脚本返回 Promise（或返回函数）时 Playwright 会等它 settle，所以
  `async () => { const r = await fetch(...); return r.json(); }` 直接就能拿到结果，**不需要**用已废弃的同步 XHR
  绕（回执里的 `data.awaited` 恒为 true，说明这次确实等了）。
- **直接打开 iframe 的 `src` 往往打不开**：很多第三方控制台的 URL 里带的是**一次性 token**，已经被 iframe
  消费掉了，再 `go_to_url` 打开会 500 或回到登录页。要绕过的话得从主站的发 token 接口现取一个，见本文「套路：主站套第三方控制台」与 `commands.md` 的 `wework-qykit-open-console` 配方。

## 页签信息文本块

`data.browser_state` 固定是这种格式，编号**从 1 开始**：

```
Browser tab: 1, Title: "哔哩哔哩 (゜-゜)つロ 干杯~-bilibili", URL: "https://www.bilibili.com/".
Browser tab: 2, 
current tab is: 1
```

- 标题和 URL 都取不到的页签（新建但还没加载完的空白页）只输出 `Browser tab: N, `。
- 最后一行 `current tab is: N` 指出当前正在操作的页签。
- **注意两套编号**：这里的 `N` 是 **1 基**，而 `data.tabs[].index` 与 `switch_tab`/`close_tab` 的 `pageIndex` 仍然是 **0 基**。看到 `current tab is: 2` 要切过去，用 `pageIndex: 1`。

## data.text 怎么读

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
| 元素在快照里找不到 | **不可见的元素不进快照**。实测百度首页的真实搜索框是 `INPUT#kw name=wd`，但它 `offsetParent === null`（被新的 AI 输入框取代而隐藏），快照里就没有它，只剩 `[16]<button >百度一下/>`。这种元素用选择器类方法也会失败（隐藏元素不满足可操作性），只能用 `execute_js` 设值，见 `pitfalls.md` 第 16 条 |

其它要点：

- 有索引的是 buildDomTree 判定为可交互的节点（含带 `onclick`、`cursor:pointer` 的 `div`/`span`）；普通纯文本容器的文字会直接出现在文本里，但没有索引。
- 要按 `id`/`class`/`href` 定位，用 `click_element_by_selector`、`input_text_by_selector`，或用 `execute_js` 取（例如 `document.querySelectorAll('a')[0].href`）。**只想看这些属性就先用 `get_interactive_map`**：它按当前快照的 xpath 一次回查全部元素，直接给出 `index → tag/id/className/href/name/text` 的映射，省掉一堆 `execute_js`。
- `index` 直接用于：`click_element_by_index`、`input_text`、`upload_file`、`get_dropdown_options`、`select_dropdown_option`、`double_click_element_by_index`、`hover_element_by_index`、`focus_element_by_index`、`check_element_by_index`、`uncheck_element_by_index`、`type_text`、`drag_element_by_index`、`get_element_text`、`get_element_html`、`get_element_value`、`get_element_attribute`、`get_element_box`、`is_visible`、`is_enabled`、`is_checked`、`clear_text`、`hover_and_click`、`get_element_screenshot`、`screenshot`。
- **没有快照时的差别**：`click_element_by_index`、`input_text`、`upload_file`、`get_dropdown_options`、`select_dropdown_option` 会退化成 CSS 选择器顺序索引；读取/状态类方法则直接报错 `索引越界: N,当前没有页面快照,请先调用 get_browser_state 获取元素索引`。
- 索引越界返回 `xxx 索引越界: N`；每次元素操作等待上限默认 **5 秒**（`browser.action.timeoutMs` 可调），部分按索引点击有恢复重试，总耗时可能更长。只读输入立即报错；超时本身不证明快照过期，需看 `data.errorCode`。
- **索引失效会自动补救两级**：先等 300 毫秒用同一个 xpath 重试（挡住动画/异步渲染的抖动），再重取一次临时快照，按「同 tag + 同文本且全页唯一」把元素找回来。都失败才返回对应错误，**不会乱点别的元素**。补救用的临时快照不会覆盖当前快照，所以索引不会悄悄漂移。
- 快照是**快照**：点击、跳转、异步渲染之后索引会重算，必须重新调用 `get_browser_state`；沿用旧索引可能得到越界或操作超时。

## 套路：主站套第三方控制台

企业微信后台把「邮件」应用套在 `exmail.qq.com` 的跨域 iframe 里（微盘 / 文档 / 会议同理）——**这是一整类
站点**，不是个例。

**先用正规解法**：`get_browser_state` 传 `includeFrames: true`，或先 `list_frames`（见本文「跨域 iframe」）。

**什么时候还要用这个套路**：iframe 里的控制台受主站外壳影响（iframe 太小把控件裁掉、主站轮询重置状态）时，
把控制台当**顶层页面**打开更稳：

1. 从 `iframe.src` 反查第三方 URL 与参数（`list_frames` 或 `execute_js` 读 `document.querySelectorAll('iframe')`）；
2. 找主站发 token / 换登录态的接口：在 `get_requests` 里按 `token` / `oauth` / `sso` / `qykit` 过滤
   （企业微信是 `POST /wework_admin/apps/qykit/login/tokenAndOAuthCode`）；
3. 用 `execute_js` **同源**调它（`credentials:'same-origin'` 自动带登录 Cookie），拿一个**还没被消费的**新 token；
4. 用新 token 顶层 `go_to_url` 打开，之后当普通页面处理。

**两个容易踩的点**：

- **token 是一次性的**：直接 `go_to_url` 打开 iframe 的 `src` 会 **HTTP 500**（那个 token 已经被 iframe 消费了）。
  必须先现取一个新的。这个绕过方式依赖「主站恰好有一个可同源调用的 token 接口」，**不是通用解** —— 通用解是
  第三步里的 `includeFrames`。
- **进了控制台之后用改 hash 的方式跳页**：`location.hash = '#/domain'` 是**同文档跳转**，不会重新请求，也就
  不会让 token 失效；而 `go_to_url` 到控制台里的另一个 URL 会重新走鉴权，多半失败。控制台内部导航优先用
  `execute_js` 改 hash，或点页面上的导航项。
