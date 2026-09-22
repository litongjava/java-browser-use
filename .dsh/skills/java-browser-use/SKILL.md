---
name: java-browser-use
description: 通过 HTTP 接口驱动真实浏览器完成网页任务：启动/关闭实例、导航与前进后退、用 get_dom_text 取回 AI 可读的结构化页面文本与元素索引、按索引点击/输入/勾选/悬停/拖拽/双击、下拉框、上传文件、多标签页、等待、鼠标、截图与 PDF、Cookie 与本地存储、浏览器设置、弹窗与控制台、网络拦截、执行任意 JavaScript、批量指令。当任务需要真实浏览器（JS 渲染、登录态、点击交互）而不是纯 HTTP 抓取时使用。
whenToUse: 需要在真实浏览器里打开网页、阅读页面、填表、点击、勾选、滚动、截图、执行 JS 或提取页面内容时；服务默认地址 http://localhost:10049。
---

# Java Browser Use（HTTP 浏览器自动化）

通过 HTTP 接口驱动一个持久化的 Chromium 实例。服务是 tio-boot 应用，所有接口都在 `/api/v1/playwright` 下。

- 默认地址：`http://localhost:10049`（端口来自 `playwright-server/src/main/resources/app.properties` 的 `server.port`）
- 启动服务：在 `playwright-server` 目录执行 `mvn spring-boot:run`
- 下文用 `BASE=http://localhost:10049/api/v1/playwright` 表示接口前缀
- 接口共 94 个，其中 `get_dom_text` 是阅读页面的入口，其余接口负责操作与观测

## 一、通用约定

### 参数怎么传

- **除 `/execute_js` 和 `/commands` 外，所有接口的参数只能放在查询串或表单里**（`GET /x?id=1` 或 `POST /x -d 'id=1'`）。给这些接口发 JSON 请求体没有用：参数全部为 null，随后报 500。
- `/execute_js` 用 `getRequestMap()` 读取参数，查询串、表单、JSON 请求体都支持，同名字段以 JSON 请求体为准。
- `/commands` 是唯一的例外：**只认 POST + JSON 请求体**，`id` 可以放在请求体里，也可以放在查询串里（数组体时必须放查询串）。
- 实用结论：普通接口统一用查询串或表单最省事；`execute_js`（脚本里常带引号）用 JSON 请求体，`commands` 必须用 JSON 请求体，见第七节。
- 带中文的参数必须 URL 编码（`curl --data-urlencode` 或客户端的编码函数）。

### 响应格式

```json
{ "data": {}, "code": 1, "ok": true, "error": null, "msg": null }
```

- `code=1` / `ok=true` 成功；`code=0` / `ok=false` 失败，原因在 `msg`（中文）。
- 实例不存在时统一返回 `没有找到对应的浏览器实例：<id>`。
- **参数缺失或类型不对仍会返回 HTTP 500，没有 JSON 响应体**（例如 `/wait` 少传 `seconds`、`/click_element_by_index` 少传 `index`）。客户端要把 500 当成“请求参数不合法”。
- `id` 是雪花 ID，序列化成字符串，原样回传即可。

### 浏览器实例

- `id` 标识一个实例，由 `start` 返回（响应里是字符串：`{"data":{"id":"3003"}}`），之后每个请求都要带。
- 一个实例 = 一个持久化 Chromium 上下文：cookie、localStorage 保存在 `~/.config/browseruse/profiles/default`，服务重启后仍在。
- 实例本身只在内存里，服务重启后 id 失效。
- 服务没有鉴权，默认只监听本机；对外暴露前必须自行加访问控制。

## 二、快速开始

```shell
BASE=http://localhost:10049/api/v1/playwright

# 1. 启动（headless=true 无头；false 会弹出真实窗口）
curl -s "$BASE/start?headless=true"
# {"data":{"id":"691683163014541312"},"code":1,"ok":true,...}

# 2. 打开页面
curl -s "$BASE/go_to_url?id=691683163014541312&url=https://example.com"
# {"data":{"status":200},"code":1,...}

# 3. 取页面结构化文本（AI 读这一段，元素索引就在里面）
curl -s "$BASE/get_dom_text?id=691683163014541312"
# {"data":{"url":"https://example.com","title":"Example Domain",
#           "text":"\t\tExample Domain\n\t\t[0]<a >More information.../>",
#           "tabs":[{"index":0,"url":"...","title":"...","current":true}],
#           "pixels_above":0,"pixels_below":0,"viewport_height":1080,"page_height":1080},...}

# 4. 按索引操作（索引来自上一步的 [index]）
curl -s "$BASE/click_element_by_index?id=691683163014541312&index=0"

# 5. 关闭
curl -s "$BASE/close?id=691683163014541312"
```

## 三、智能体的交互循环

1. `start` 一次，记住 `id`（整个任务复用同一个实例，登录态会保留）。
2. `go_to_url`（或 `navigate`）打开目标地址。
3. `get_dom_text` 取回 `data.text`：每行的 `[index]` 就是可交互元素索引。
4. 用索引接口执行动作：`click_element_by_index`、`input_text`、`check_element_by_index`、`select_dropdown_option`……
5. **页面只要发生变化（点击、跳转、异步渲染、弹窗）就回到第 3 步重新取一次**；没变化才可以继续用上一轮索引。判断“刚才那一下到底有没有变化”用 `diff_dom_text`，比重新读整页省 token。
6. 需要判断“有没有加载出来”时用 `wait_for_element` / `wait_for_text` / `wait_for_url` / `wait_for_load`，不要用固定 `wait`。
7. 需要看页面长什么样时用 `screenshot`（不传 `path` 会返回 `base64`，可以直接交给视觉模型）；只看某个元素（验证码、二维码、图表）用 `get_element_screenshot`。
8. 需要读接口返回的 JSON 时用 `wait_for_response`（等新响应）或 `get_response_body`（回看最近的响应），不要只靠 `get_requests` 的状态码猜。
9. 想一次拿到页面当前状态（url/标题/页签/弹窗/是否还在加载）用 `get_page_snapshot`，不要连发五六次调用。
10. 遇到验证码、短信码、人工登录这类智能体做不了的环节，用 `request_human_input` 把图和人话一起交出去，见第九节。
11. **能用批量就用批量**：第 3～7 步可以合并成一次 `commands` 请求（动作 + 读取混排，末尾放 `get_dom_text`），一次推理拿到全部结果，别一个动作往返一次。见第七节。
12. 任务完成输出结论，最后 `close`。

## 四、get_dom_text 与元素索引

`get_dom_text` 在页面里执行 buildDomTree，把 DOM 转成 AI 可读的文本，并缓存本次快照。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 实例 ID |
| `highlight` | 否 | 是否在页面上画高亮框，默认 `true`（便于人工观察，不影响返回文本） |
| `viewportExpansion` | 否 | 视口外扩像素，默认 `0`；想一次拿到首屏之外的更多元素就调大（例如 `1000`） |

返回字段：`data.url`、`data.title`、`data.text`、`data.tabs`（`index/url/title/current`）、`data.pixels_above`、`data.pixels_below`、`data.viewport_height`、`data.page_height`。

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

**这段文本怎么读**（每一条都容易误判）：

| 现象 | 含义 |
| --- | --- |
| `[Start of page]` / `[End of page]` | 只是首尾标记，不是页面元素。**接口本身不输出这两行**（`data.text` 的第一行就是第一个元素），样例里出现它们是因为样例来自 demo/测试代码（`DomServiceTest` 等）自己 `println` 的边界标记 |
| 行首缩进（制表符） | DOM 嵌套层级：`[20]` 缩进在 `[19]<li />` 里，说明这条新闻链接属于那个 `li`；`[17]<div>` 缩进在 `[16]<a>` 里 |
| `[index]` | 元素索引，所有按索引操作的接口都用它。**编号不连续**（`[12]` 后面直接是 `[14]`），因为只有 buildDomTree 判定为可交互的节点才有索引，不要把它当行号或「第 n 个元素」 |
| 行尾 `/>` | 只是格式化后缀，**不代表自闭合**：`[0]<a >新闻/>` 里的 `新闻` 就是链接文字 |
| `<a />`、`<span />`、`<div />`（没有文字） | 没有可见文字的节点：图标、装饰、空容器。它**有索引**就说明可以点 |
| `[17]<div > />`（尖括号里只有一个空格） | 该节点没有任何被保留的语义属性，文本也只有一个空格 |
| 属性集合 | 只保留语义信息：实测有 `type`、`placeholder`、`aria-label`、`title`、`name`、`value`（按钮类）。**没有 `id`、`class`、`href`、`style`**，所以从快照里看不出链接地址、也认不出 CSS 类名 |
| `value='百度一下'` | 按钮类 input 上的按钮文字；输入框上出现的 `value` 是**当前值**（实测必应搜索框输入后快照里出现 `value='Mac Mini M4'`），但要确定地读值还是用 `get_element_value` |
| 文本顺序 | DOM 顺序；`[21]<li >新/>` 里的 `新` 是角标文字，真正的链接文字在它内部的 `[22]` 里 |
| 元素在快照里找不到 | **不可见的元素不进快照**。实测百度首页的真实搜索框是 `INPUT#kw name=wd`，但它 `offsetParent === null`（被新的 AI 输入框取代而隐藏），快照里就没有它，只剩 `[16]<button >百度一下/>`。这种元素用选择器类接口也会失败（隐藏元素不满足可操作性），只能用 `execute_js` 设值，见第九节第 16 条 |

其它要点：

- 有索引的是 buildDomTree 判定为可交互的节点（含带 `onclick`、`cursor:pointer` 的 `div`/`span`）；普通纯文本容器的文字会直接出现在文本里，但没有索引。
- 要按 `id`/`class`/`href` 定位，用 `click_element_by_selector`、`input_text_by_selector`，或用 `execute_js` 取（例如 `document.querySelectorAll('a')[0].href`）。**只想看这些属性就先用 `get_interactive_map`**：它按当前快照的 xpath 一次回查全部元素，直接给出 `index → tag/id/className/href/name/text` 的映射，省掉一堆 `execute_js`。
- `index` 是 DOM 树快照里的索引，直接用于：`click_element_by_index`、`input_text`、`upload_file`、`get_dropdown_options`、`select_dropdown_option`、`double_click_element_by_index`、`hover_element_by_index`、`focus_element_by_index`、`check_element_by_index`、`uncheck_element_by_index`、`type_text`、`drag_element_by_index`、`get_element_text`、`get_element_html`、`get_element_value`、`get_element_attribute`、`get_element_box`、`is_visible`、`is_enabled`、`is_checked`、`clear_text`、`hover_and_click`、`get_element_screenshot`、`screenshot`。
- **没有快照时的差别**：老的几个接口（`click_element_by_index`、`input_text`、`upload_file`、`get_dropdown_options`、`select_dropdown_option`）会退化成 CSS 选择器顺序索引；新增的读取/状态类接口则直接报错 `索引越界: N,当前没有页面快照,请先调用 get_dom_text 获取元素索引`。
- 索引越界返回 `xxx 索引越界: N`；元素已失效（快照过期）时按索引操作的等待上限是 **5 秒**，超时返回 `xxx 失败：元素不存在或页面已变化,请重新调用 get_dom_text 获取元素索引`。
- **索引失效会自动补救两级**：先等 300 毫秒用同一个 xpath 重试（挡住动画/异步渲染的抖动），再重取一次临时快照，按「同 tag + 同文本且全页唯一」把元素找回来。都失败才报上面那句错误，**不会乱点别的元素**。补救用的临时快照不会覆盖当前快照，所以索引不会悄悄漂移。
- 快照是**快照**：点击、跳转、异步渲染之后索引会重算，必须重新调用 `get_dom_text`；沿用旧索引只会得到越界或 5 秒超时。

## 五、接口清单

### 导航与页面信息

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/start` | `id`(可选), `headless`(bool) | 返回 `data.id`；传 `id` 可指定便于识别的实例号 |
| `/navigate` | `id`, `url` | 返回 `data.status` |
| `/go_to_url` | `id`, `url` | 与 `/navigate` 等价 |
| `/get_dom_text` | `id`, `highlight`, `viewportExpansion` | 见第四节 |
| `/get_page_snapshot` | `id`, `includeConsole`(bool), `includeRequests`(bool), `requestFilter` | 一次拿到页面状态：`data.url`、`data.title`、`data.tabs`、`data.dialog`、`data.loading`；`includeConsole=true` 再带 `data.logs`/`data.errors`，`includeRequests=true` 再带 `data.requests`（可用 `requestFilter` 按 URL 子串过滤）。替代六次单独调用，**不含 DOM 快照文本** |
| `/diff_dom_text` | `id`, `highlight`, `viewportExpansion` | 重新执行一次 buildDomTree，与上一次快照按行做差集：`data.changed`、`data.added`、`data.removed`（各最多 200 行）、`data.first`。判断“页面到底动没动”比重读整页省 token |
| `/get_interactive_map` | `id` | 按当前快照的 xpath 回查全部元素，返回 `data.elements`：`index`、`tag`、`xpath`、`id`、`className`、`href`、`name`、`text`。补上快照里没有的 `id`/`class`/`href`；需要先有快照 |
| `/go_back` | `id` | 返回 `data.status`、`data.url`；无历史时 `msg=无法后退：没有可用历史记录` |
| `/go_forward` | `id` | 同上；`about:blank` 这类没有 HTTP 响应的页面 `status` 为 0 |
| `/reload` | `id` | 返回 `data.status` |
| `/get_url` | `id` | 返回 `data.url` |
| `/get_title` | `id` | 返回 `data.title` |
| `/wait` | `id`, `seconds`（必填，秒） | 固定等待；缺 `seconds` 直接 500 |
| `/close` | `id` | 关闭上下文与 Playwright，释放实例 |

### 元素交互（按索引）

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/click_element_by_index` | `id`, `index` | 单击；返回点击回执（见下） |
| `/double_click_element_by_index` | `id`, `index` | 双击；返回点击回执 |
| `/hover_element_by_index` | `id`, `index` | 悬停 |
| `/focus_element_by_index` | `id`, `index` | 聚焦 |
| `/check_element_by_index` | `id`, `index` | 勾选复选框/单选框 |
| `/uncheck_element_by_index` | `id`, `index` | 取消勾选 |
| `/type_text` | `id`, `index`, `text` | 逐字输入，**不清空**原有内容 |
| `/input_text` | `id`, `index`, `text` | 覆盖式填充（等价 `fill`，会清空）；`text` 必填，**清空请用 `clear_text`** |
| `/drag_element_by_index` | `id`, `index`, `targetIndex` | 把第 index 个元素拖到第 targetIndex 个元素 |
| `/upload_file` | `id`, `index`, `path` | `path` 是**服务器本地绝对路径**，不是 URL |
| `/send_keys` | `id`, `keys` | 键盘按键：`Enter`、`Tab`、`Control+A`、`ArrowDown` |
| `/key_down` | `id`, `keys` | 按住不放（配合 `/key_up`） |
| `/key_up` | `id`, `keys` | 松开按键 |
| `/get_dropdown_options` | `id`, `index` | 返回 `data.options`，选项文本数组 |
| `/select_dropdown_option` | `id`, `index`, `text` | 按**选项文本**（label）匹配 |
| `/scroll` | `id`, `down`(bool), `numPages`(int), `index`(可选) | 不传 `index` 时按 `PageDown`/`PageUp` 翻页；传 `index` 时对第 index 个元素 `scrollBy` |
| `/scroll_to_text` | `id`, `text` | 按 `text=` 定位并滚动到可见；找不到会等满 30 秒后失败 |

**点击回执**：`click_element_by_index`、`double_click_element_by_index`、`click_element_by_selector`、`click_element_by_text`、`click_element_by_role`、`hover_and_click` 都会在 `data` 里返回点击前后的状态对比：

| 字段 | 说明 |
| --- | --- |
| `data.urlBefore` / `data.urlAfter` | 点击前后的 URL |
| `data.tabCountBefore` / `data.tabCountAfter` | 点击前后的页签数（变多说明弹出了新页签） |
| `data.textLengthBefore` / `data.textLengthAfter` | 点击前后 `document.body.innerText` 的长度（粗略反映内容变化） |
| `data.changed` | 上面三项有任意一项变化就是 `true` |
| `data.hint` | `changed=false` 时出现：`xxx 已执行,但 url、页签数、正文长度都没有变化,请确认是否点中了目标元素` |
| `data.tag` / `data.text` / `data.outerHtml` | **只有** `click_element_by_text`、`click_element_by_role`、`hover_and_click` 返回：真正命中的元素是什么 |

`ok=true` 只代表动作没抛异常，**不代表点中了东西**：实测点悬浮菜单时文本命中的是纯文本容器，接口返回成功但页面毫无变化。判断是否生效要看 `data.changed`。

### 读取元素信息与状态（按索引）

| 接口 | 参数 | 返回 |
| --- | --- | --- |
| `/get_element_text` | `id`, `index` | `data.text`（innerText） |
| `/get_element_html` | `id`, `index` | `data.html`（innerHTML） |
| `/get_element_value` | `id`, `index` | `data.value`（输入框的 value） |
| `/get_element_attribute` | `id`, `index`, `name` | `data.value`（属性值，可为 null） |
| `/get_element_count` | `id`, `selector` | `data.count`（CSS 选择器匹配数量，不需要索引） |
| `/get_element_box` | `id`, `index` | `data.x/y/width/height`；元素不可见时失败 |
| `/is_visible` | `id`, `index` | `data.visible` |
| `/is_enabled` | `id`, `index` | `data.enabled` |
| `/is_checked` | `id`, `index` | `data.checked` |

### 按选择器 / 文本 / 语义定位（快照过期时的兜底）

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/click_element_by_selector` | `id`, `selector` | CSS 选择器取第一个匹配并点击；**点完如果弹出新页签会自动切过去并带到最前**；返回点击回执 |
| `/input_text_by_selector` | `id`, `selector`, `text` | 覆盖式填充 |
| `/click_element_by_text` | `id`, `text` | 按可见文本定位。**先向上找最近的可点击祖先**（`a`/`button`/`[role=button]`/`[onclick]`），找不到才点文本节点本身；返回真正命中的 `data.tag`/`data.outerHtml` 与点击回执 |
| `/click_element_by_role` | `id`, `role`, `name` | 无障碍角色，`role` 如 `button`、`link`、`textbox`、`checkbox`；返回命中的 `data.tag`/`data.outerHtml` 与点击回执 |
| `/input_text_by_label` | `id`, `label`, `text` | 按表单标签 / `aria-label` 定位输入框 |
| `/clear_text` | `id`, `index`(可选), `selector`(可选) | 清空输入框，返回 `data.value`（清空后的值）。`input_text` 的 `text` 必填、传空串会被参数校验挡掉（HTTP 500），所以清空走这里；`index` 与 `selector` 传一个即可 |
| `/hover_and_click` | `id`, `index`(可选), `selector`(可选), `hoverDelayMs`(可选) | 悬停后**立刻**点同一个元素，`hoverDelayMs` 默认 300 毫秒。悬浮菜单专用：分两步调用中间隔着一次推理往返，菜单早收起来了。返回命中信息与点击回执 |

### 标签页

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/get_tabs` | `id` | 返回 `data.tabs`（`index/url/title/current`） |
| `/new_tab` | `id`, `url`(可选) | 新建标签页并切换过去，返回 `data.pageIndex`、`data.url`；新页签会被带到最前 |
| `/switch_tab` | `id`, `pageIndex` | 切换当前操作的标签页，并把该页签带到最前 |
| `/switch_tab_by_url` | `id`, `url` | 按 URL 匹配切换当前页签，返回 `data.pageIndex`/`data.url`/`data.title`。匹配规则见下面的「URL 匹配」；匹配不到时 `msg` 里列出当前全部页签 |
| `/close_tab` | `id`, `pageIndex` | 关闭标签页；关掉当前页时自动切到第一个（同样带到最前） |
| `/close_other_tabs` | `id`, `pageIndex`(可选) | 关掉除指定页签外的全部页签，返回 `data.closed`/`data.remaining`/`data.pageIndex`。不传 `pageIndex` 时保留当前页签。**关重复页签用这个**：一个个 `close_tab` 会因为索引整体前移而关错 |
| `/bring_to_front` | `id`, `pageIndex`(可选) | 把指定页签（默认当前页签）带到窗口最前，返回 `data.pageIndex`/`data.url`。只切窗口、**不改当前操作页**，适合「让人看一眼这一页」 |

`new_tab` / `switch_tab` / `switch_tab_by_url` / `close_tab` / `close_other_tabs` / `bring_to_front` 以及 `click_element_by_selector` 命中弹窗时都会调用 `bringToFront()`：**有头模式下浏览器窗口会跟着切到智能体正在操作的那个页签**，人工可以直接看到当前进度；无头模式没有副作用。

页签多了以后 `pageIndex` 不稳定（实测点一次菜单会弹出两个同 URL 的重复页签），优先用 `/switch_tab_by_url` 定位。

### 等待

`timeoutSeconds` 不传时默认 30 秒；超时返回 `xxx 超时：等待时间内条件一直没有满足`。

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/wait_for_element` | `id`, `selector`, `timeoutSeconds` | 等元素出现 |
| `/wait_for_text` | `id`, `text`, `timeoutSeconds` | 等文本出现 |
| `/wait_for_url` | `id`, `url`, `timeoutSeconds` | 等 URL 匹配（支持 `**/path` 这类通配），返回 `data.url` |
| `/wait_for_load` | `id`, `state`, `timeoutSeconds` | `state` 取 `load` / `domcontentloaded` / `networkidle` |
| `/wait_for_function` | `id`, `expression`, `timeoutSeconds` | 等 JS 表达式为真，如 `() => document.readyState === "complete"` |

### 鼠标

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/mouse_move` | `id`, `x`, `y` | 移动鼠标到坐标 |
| `/mouse_down` | `id`, `button` | `left` / `right` / `middle` |
| `/mouse_up` | `id`, `button` | 松开 |
| `/mouse_wheel` | `id`, `deltaY` | 滚轮，正数向下 |

### 截图与 PDF

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/screenshot` | `id`, `path`(可选), `fullPage`(bool), `index`(可选), `selector`(可选), `clipX`/`clipY`/`clipWidth`/`clipHeight`(可选) | 传 `path` 存服务器文件并返回 `data.path`/`data.size`；不传返回 `data.base64`，可直接给视觉模型。传 `index` 或 `selector` 时**只截该元素**；否则截整页，`clipX/clipY/clipWidth/clipHeight` 四个都传才按区域裁剪 |
| `/get_element_screenshot` | `id`, `index`(可选), `selector`(可选), `path`(可选) | 只截一个元素，返回 `data.path`+`data.size` 或 `data.base64`、`data.target`。`index` 与 `selector` 传一个即可 |
| `/pdf` | `id`, `path`(可选) | 存 PDF，返回 `data.path`；不传 `path` 落到 `~/Downloads/broswer/` |

`get_element_screenshot` 是验证码、二维码、图表这类「必须看图」的元素的标准做法：走 Playwright 自己的元素截图，**不受 canvas 跨域污染限制**（用 `execute_js` + canvas 手抠图，跨域图片会直接失败）。

### Cookie 与本地存储

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/get_cookies` | `id`, `url`(可选) | 返回 `data.cookies`（`name/value/domain/path/expires/httpOnly/secure/sameSite`）与 `data.count` |
| `/set_cookie` | `id`, `name`, `value`, `url`(可选) | 建议同时传 `url`，否则要自己保证 domain 合法 |
| `/clear_cookies` | `id` | 清空 |
| `/get_local_storage` | `id`, `key`(可选) | 传 `key` 返回该键的值；不传返回整个 localStorage 的 JSON 字符串（`data.value`） |
| `/set_local_storage` | `id`, `key`, `value` | 写入当前站点 |
| `/clear_local_storage` | `id` | 清空当前站点 |

### 浏览器设置

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/set_viewport` | `id`, `width`, `height` | 改视口尺寸 |
| `/set_geolocation` | `id`, `latitude`, `longitude` | 会同时授予 `geolocation` 权限 |
| `/set_offline` | `id`, `offline`(bool) | 断网 / 恢复 |
| `/set_headers` | `id`, `headersJson` | 形如 `{"X-Key":"v"}`，会 URL 编码后放在查询串 |
| `/set_credentials` | `id`, `username`, `password` | HTTP 基本认证；**会重建上下文，当前页面丢失**（登录态仍在 profile 里） |
| `/set_media` | `id`, `colorScheme` | `light` / `dark` / `no-preference` |

### 弹窗与控制台

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/get_dialog` | `id`, `consume`(bool) | 返回 `data.dialog`（`type/message/defaultValue/seq/timestamp`）或 null。**记录不会自动清除**，可能是很早以前的弹窗；`consume=true` 读后即清 |
| `/clear_dialog` | `id` | 清空弹窗记录，返回 `data.cleared` |
| `/set_dialog_behavior` | `id`, `dismiss`(bool) | 弹窗**默认自动确认**；`dismiss=true` 改成自动取消 |
| `/get_console_logs` | `id` | 返回 `data.logs` 与 `data.errors`，各最多 200 条 |
| `/clear_console_logs` | `id` | 清空 |

**`get_dialog` 是「最近一次弹窗」而不是「当前这一步的结果」**：实测提交验证码失败过一次之后，后面查询明明成功了，`get_dialog` 仍然返回上一轮的「验证码输入错误」，很容易误判成这次也失败了。判断弹窗是不是新的看 `data.dialog.seq`/`timestamp`；稳妥做法是**每次提交动作前先 `get_dialog?consume=true` 清一次**，动作后再读。

### 网络

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/route` | `id`, `urlPattern`, `action`, `body`, `status`, `contentType` | `action` 取 `abort`（拦截）或 `mock`（返回自定义响应）；`urlPattern` 用 Playwright 通配，如 `**/api/ping` |
| `/unroute` | `id`, `urlPattern`(可选) | 不传则移除全部路由 |
| `/get_requests` | `id`, `filter`(可选) | 返回 `data.requests`（`method/url/resourceType/status`，**带请求体的请求另有 `postData`，最多 4000 字符**），最多 200 条，`filter` 按 URL 子串过滤。**只有元数据，没有响应体** |
| `/wait_for_response` | `id`, `urlPattern`, `timeoutSeconds`(可选), `maxChars`(可选), `lookBackSeconds`(可选) | 等 `urlPattern` 匹配的响应并返回它的响应体：`data.url`、`data.status`、`data.body`（默认最多 20000 字符）、`data.bodyLength`、`data.ageMs`、`data.fromLookBack`。匹配规则见下面的「URL 匹配」 |
| `/get_response_body` | `id`, `filter`(可选), `index`(可选), `maxChars`(可选) | 回看**已经发生过**的响应体（保留最近 100 个响应）。`filter` 按 URL 子串过滤，不传取最近一个；`index` 在多个匹配里选第几个（默认最后一个）。响应体已被释放时返回 `data.bodyError` |

**URL 匹配**（`wait_for_response` 的 `urlPattern`、`switch_tab_by_url` 的 `url`）：先按**子串**匹配，再按**通配**匹配，通配里的 `*` 和 `**` 都表示任意字符、**可以跨 `/`**。模式没写尾部通配时，URL 后面还可以再跟内容（`#fragment`、`?query` 都算），所以：

| 模式 | 能匹配 |
| --- | --- |
| `**/api/query*` | `https://x.com/api/query?y=1` |
| `**/example.com*` | `https://example.com/`（末尾斜杠不影响） |
| `**/smoke.html` | `file:///…/smoke.html#`（尾部 `#fragment` 不影响） |

> 注意这一条和 `/route`、`/unroute` 的 `urlPattern` **不一样**：那两个用的是 Playwright 原生通配，`*` 不含 `/`，`https://example.com/` 末尾的斜杠会让 `**/example.com*` 匹配不上。写 `route` 时如果 URL 末尾有斜杠，把模式改成 `**/example.com/**` 这类形式。

**读接口数据用 `wait_for_response`，不要靠 `get_requests` 猜**：SPA 的数据都在 XHR 里，而 `get_requests` 只有 url 和状态码。典型用法是「点一下 → 等接口 → 读 JSON」：
```shell
# 一个批次里完成：点击 + 等响应（顺序写法就够，不用并发）
curl -s -X POST "$BASE/commands" -H 'Content-Type: application/json' -d '{
  "id": 1, "stopOnError": false,
  "commands": [{"click_element_by_index":{"index":12}},
               {"wait_for_response":{"urlPattern":"**/api/query*","timeoutSeconds":20}}]}'
```

**为什么顺序写就行**：`wait_for_response` **先回看再等** —— `lookBackSeconds`（默认 10 秒）内已经收到过的匹配响应会直接返回，`data.ageMs` 是它距今的毫秒数、`data.fromLookBack` 为 `true`。响应通常在你拿到点击结果之前就到了，所以顺序调用照样命中。`lookBackSeconds=0` 表示只等新响应（这时必须并发触发，而**同一个实例不要并发发请求**，见第十节第 13 条，所以一般不需要）。

已经发过的请求想看返回内容就用 `get_response_body`：

```shell
curl -s -G "$BASE/get_response_body" --data-urlencode 'id=1' --data-urlencode 'filter=/api/query'
```

### 人机协同（验证码 / 短信码 / 人工登录）

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/request_human_input` | `id`, `prompt`, `index`(可选), `selector`(可选), `timeoutSeconds`(可选) | 发起一个人工介入请求。传 `index` 或 `selector` 时把该元素（通常是验证码图）截成 `data.imageBase64` 一起返回，并把当前页签带到最前。返回 `data.requestId`、`data.prompt`、`data.expiresAt`（默认 300 秒）、`data.url` |
| `/submit_human_input` | `id`, `requestId`, `answer` | 提交人工答复，返回 `data.status` 与 `data.answer` |
| `/get_human_input` | `id`, `requestId`, `timeoutSeconds`(可选) | 取人工答复，返回 `data.status`（`pending`/`answered`/`expired`）、`data.answer`、`data.prompt`。传 `timeoutSeconds` 时长轮询等待，到时间还没答复就返回当前状态（**不算失败**） |

完整流程见第九节。

### 其它

| 接口 | 参数 | 说明 |
| --- | --- | --- |
| `/extract_structured_data` | `id`, `query`, `extractLinks`(bool) | 返回 `data.text`（正文，最多 20000 字符，读取时会临时隐藏高亮层）与 `data.links` |
| `/execute_js` | `id`, `body` | 返回 `data.result`，见第六节 |
| `/commands` | POST 的 JSON 请求体（`id` 可选放查询串） | 批量指令，只支持 POST，见第七节 |

## 六、执行 JavaScript

```
POST /api/v1/playwright/execute_js
{ "id": 691683163014541312, "body": "document.title" }
```

`body` 支持三种写法，都按提交的形式直接求值：

| 写法 | 示例 | 说明 |
| --- | --- | --- |
| 表达式 | `document.title` | 返回表达式的值 |
| 函数 | `() => document.title`、`() => { return document.title; }`、`(async () => await fetch(location.href))()` | 求值结果是函数时会被自动调用，Promise 会被等待 |
| 语句片段 | `const a = 40; return a + 2;` | 含 `return` 的片段会被包装成函数体执行 |

- 返回值必须是 JSON 可序列化的；DOM 元素不报错，但只会得到 `ref: <Node>`，请先转成 `textContent`、`outerHTML`、`value`。
- 脚本报错时返回 `code:0`，`msg` 形如 `执行 JavaScript 失败：TypeError: Cannot read properties of null (reading 'click')`（只有异常首行，没有堆栈）。
- `body` 长度上限 100000 字符；脚本**没有超时**。
- 与 `get_dom_text` 的分工：**读页面优先用 `get_dom_text`**（结构化、带索引、token 可控）；`execute_js` 用于取快照里没有的东西（`id`/`class`/`href`、滚动位置、localStorage 原始值）或做特殊交互。

常见用法：

```js
document.body.innerText                                   // 取正文
Array.from(document.querySelectorAll('a')).map(a => a.href) // 取链接
document.querySelector('#submit').click()                 // 点没有索引的元素
document.querySelector('#kw').value = 'x'                 // 直接赋值
```

## 七、批量指令（PTC：一次请求跑完一整个计划）

`commands` 是**降低推理步数**的关键接口：把「动作 + 读取」打包成一次请求，一次模型推理就能拿到全部观察结果，而不是每个动作都往返一次。

**就照这个写**：POST + JSON 请求体，`id`、`stopOnError`、`commands` 一起放在体里。

```shell
curl -s -X POST "$BASE/commands" -H 'Content-Type: application/json' -d '{
  "id": 1,
  "stopOnError": false,
  "commands": [ {"go_to_url":{"url":"https://example.com"}}, {"get_dom_text":{}} ]
}'
```

> **只支持 POST + JSON 请求体**：不支持 GET、不支持表单、也没有 `bodyJson` 字段。原因有二——批量指令会改浏览器状态（点击、跳转），不是只读接口；而且 JSON 塞进 URL 要做百分号编码、还有长度上限。用 GET 调会得到 `批量指令只支持 POST + JSON 请求体,请用 POST 并把命令放在请求体里`。

```jsonc
// 数组体，每项只能有一个键
[{"click_element_by_index":{"index":14}},{"get_dom_text":{}}]

// 对象载荷（推荐：id、开关、命令一起带）
{"id":"691683163014541312","stopOnError":false,"commands":[{"click_element_by_index":{"index":14}},{"get_dom_text":{}}]}

// 只写一条命令时也可以省掉 commands 数组
{"id":"691683163014541312","get_title":{}}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `id` | 查询参数里的 id | 也可以放在 JSON 体或表单里，PTC 时更省事 |
| `stopOnError` | `true` | `true`＝遇到第一个失败就停止；`false`＝继续跑完并把每一步结果都返回，**批量里推荐 `false`** |
| `commands` | 必填 | 命令数组 |

返回值里**每一步的结果都在**（这是和老版本最大的区别）：

```jsonc
{"data":{
  "count":3,"succeeded":2,"failed":1,"stopped":true,
  "results":[
    {"index":0,"command":"go_to_url","ok":true,"data":{"status":200},"msg":null},
    {"index":1,"command":"get_dom_text","ok":true,"data":{"text":"[0]<a >新闻/> ...","title":"..."},"msg":null},
    {"index":2,"command":"click_element_by_index","ok":false,"data":null,"msg":"click_element_by_index 索引越界: 999"}
  ]}, "code":0,"ok":false,"msg":"第 2 条命令 click_element_by_index 失败：click_element_by_index 索引越界: 999"}
```

- **覆盖范围**：第五节的接口里，除 `commands` 自身（不允许嵌套）之外**全部 93 个都能批量调用**，命令名就是接口路径去掉斜杠，参数名与单独调用完全一致。
- 每一步的 `data` 原样返回，所以 `get_dom_text` 的 `data.text`、`execute_js` 的 `data.result`、`is_checked` 的 `data.checked` 在批量里都能直接读到。
- 有失败时整体响应是 `code:0`，但 `data.results` 仍然完整返回，`msg` 指出第一条失败的命令。
- 参数缺失会得到 `第 N 条命令 xxx 失败：缺少参数 index`，不会 500。
- 请求体是 JSON：数组体（`[{"命令名":{参数}}]`，每项只能有一个键）或对象载荷（`id`/`stopOnError`/`commands`）；数组体带不了 `id`，所以数组体时 `id` 要放在查询串里（`POST /commands?id=1 -d '[...]'`，POST 带查询串是允许的）。请求体为空、不是合法 JSON 都会得到中文提示。
- 布尔参数不传按 `false` 处理（和单独调用一致），所以批量里 **`scroll` 要写 `"down":true` 才向下**；`start` 例外，不传 `headless` 按无头处理，避免误弹窗口。

**PTC 推荐节奏**：一个批次 = 一个计划段。批次里先做动作，末尾放一个 `get_dom_text`，这样下一次推理直接基于最新快照决策，省掉「每个动作一次推理 + 一次单独取快照」。

```shell
# 搜索全过程：输入 → 回车 → 等结果 → 取新快照，一次请求完成
curl -s -X POST "$BASE/commands" -H 'Content-Type: application/json' -d '{
  "id": 1, "stopOnError": false,
  "commands": [
    {"input_text": {"index": 14, "text": "Mac Mini M4"}},
    {"send_keys": {"keys": "Enter"}},
    {"wait_for_text": {"text": "Mac Mini", "timeoutSeconds": 10}},
    {"get_dom_text": {}}
  ]}'
```

```shell
# 一批独立读取：一条失败不影响其它条
curl -s -X POST "$BASE/commands" -H 'Content-Type: application/json' -d '{
  "id": 1, "stopOnError": false,
  "commands": [{"get_title":{}},{"get_url":{}},{"get_element_text":{"index":0}},{"get_console_logs":{}}]}'
```

```shell
# 勾选 + 校验 + 提交，三次动作一次推理
curl -s -X POST "$BASE/commands" -H 'Content-Type: application/json' -d '{
  "id": 1,
  "commands": [{"check_element_by_index":{"index":2}},{"is_checked":{"index":2}},
               {"click_element_by_role":{"role":"button","name":"提交"}}]}'
```

- 需要循环、条件判断这类逻辑，就在批次里用 `execute_js` 一步做完，不要拆成几十条命令。
- 未知命令返回 `不支持的命令：xxx(批量接口支持除 commands 之外的全部接口,命令名就是接口路径去掉斜杠)`。


## 八、典型任务

**打开页面取正文并让模型阅读**

```shell
curl -s "$BASE/go_to_url?id=1&url=https://example.com"
curl -s "$BASE/get_dom_text?id=1"          # 把 data.text 交给模型
```

**搜索**

```shell
# 先 get_dom_text，假设搜索框是 [3]
curl -s -G "$BASE/input_text" --data-urlencode 'id=1' --data-urlencode 'index=3' --data-urlencode 'text=Mac Mini M4'
curl -s "$BASE/send_keys?id=1&keys=Enter"
curl -s "$BASE/wait_for_text?id=1&timeoutSeconds=10" --data-urlencode 'text=Mac Mini'
```

**勾选并提交表单**

```shell
curl -s "$BASE/check_element_by_index?id=1&index=2"
curl -s "$BASE/is_checked?id=1&index=2"                  # {"data":{"checked":true},...}
curl -s "$BASE/click_element_by_role?id=1&role=button&name=%E6%8F%90%E4%BA%A4"
```

**下拉框**

```shell
curl -s "$BASE/get_dropdown_options?id=1&index=4"        # {"data":{"options":["请选择","北京","上海"]},...}
curl -s -G "$BASE/select_dropdown_option" --data-urlencode 'id=1' --data-urlencode 'index=4' --data-urlencode 'text=上海'
```

**页面变化后元素找不到**：重新 `get_dom_text` 取新索引；如果元素没有索引，改用 `click_element_by_selector` 或 `execute_js` 的 `.click()`。

**多标签页**：点击 `target=_blank` 链接或 `execute_js` 里 `window.open(url)` 后，用 `/get_tabs` 看列表，`/switch_tab?id=1&pageIndex=1` 切过去，`/close_tab` 关掉；也可以直接 `/new_tab?id=1&url=...`。切过去的页签会同时被带到窗口最前，`headless=false` 时人工能看到智能体当前操作的是哪一页。

**抓接口 / 造数据**：`/route?id=1&urlPattern=**/api/ping&action=mock&body={"pong":false}` 让接口返回假数据；`/route?...&action=abort` 让请求直接失败；`/get_requests?id=1&filter=/api` 看请求记录（含请求体）；`/get_response_body?id=1&filter=/api` 看已经发生过的响应体；`/wait_for_response?id=1&urlPattern=**/api/ping` 拿接口返回的 JSON（先回看最近 10 秒，再等新的）；`/unroute?id=1` 撤销。

**悬浮菜单**：`/hover_and_click?id=1&index=5` 一步完成悬停 + 点击（分两步菜单会收起来）。菜单项如果只有文本没有索引，用 `/hover_and_click?id=1&selector=a[title='纳税人信息查询']`，注意选择器里的引号要 URL 编码。

**判断点击到底生效没有**：看点击回执里的 `data.changed`。`false` 说明 url、页签数、正文长度都没变，多半没点中；配合 `/diff_dom_text` 能确认页面快照有没有变。

**读某个接口返回的 JSON**：`/wait_for_response?id=1&urlPattern=**/api/query*&timeoutSeconds=20` 直接拿 `data.body`（默认先回看最近 10 秒）；已经发生过的用 `/get_response_body?id=1&filter=/api/query`。

**复用登录态**：同一实例的 cookie 会留在持久化 profile 里，重新 `go_to_url` 即可；换任务不必重新登录。

## 九、人机协同（验证码 / 登录 / 人工介入）

验证码、短信码、扫码登录这类环节，智能体既读不了图也拿不到凭证，只能请人来做。三个接口把这件事固定下来：

| 步骤 | 调用 | 做什么 |
| --- | --- | --- |
| 1 | `/request_human_input?id=1&prompt=请输入图片验证码&index=7&timeoutSeconds=300` | 建一个待办；`index`/`selector` 指向验证码图时把图截成 `data.imageBase64` 返回，同时把页签带到窗口最前 |
| 2 | 人看图 → 把答案回填 | 通过 `/submit_human_input?id=1&requestId=hr-1-xxx&answer=8f3k` 提交；**或者**直接在有头浏览器里自己把这一步操作完 |
| 3 | `/get_human_input?id=1&requestId=hr-1-xxx&timeoutSeconds=60` | 取答复。`data.status` 为 `pending` / `answered` / `expired` |

要点：

- **`get_element_screenshot` 是这套流程的地基**：没有它，`request_human_input` 也没东西可以给人看。要单独把图拿出来（不发起人工请求）就直接调它。
- `data.imageBase64` 直接就是 PNG 的 base64，交给视觉模型或存成文件都可以。
- 步骤 2 的「人直接在浏览器里操作」是允许的：这时 `get_human_input` 会一直是 `pending` 直到 `expiresAt` 过期，**智能体不要死等**——可以直接继续后续步骤，或者用 `get_page_snapshot` / `diff_dom_text` 看页面有没有变化。
- **验证码有时效**：实测税务系统的图片验证码约 **120 秒**过期，而且**一次性**（用过的码再提交必然失败）。所以拿到答复后要**立刻**提交，不要攒着；提交失败先换一张新图再让人看，别拿旧码重试。
- **登录态本身是可以复用的**：同一个实例的 cookie 留在持久化 profile 里，人工登录一次之后，后续 `go_to_url` 不用再登。所以「请人登录」这一步一个任务里通常只需要做一次。
- 有头模式（`headless=false`）下配合 `bring_to_front` / `request_human_input`，人工能直接看到智能体停在哪一页，接力最顺。

```shell
# 1. 请人看验证码（把图和问题一起拿出来）
curl -s -G "$BASE/request_human_input" --data-urlencode 'id=1' \
  --data-urlencode 'prompt=请输入图片验证码' --data-urlencode 'index=7'
# {"data":{"requestId":"hr-1-3001","prompt":"请输入图片验证码","imageBase64":"iVBORw0...","expiresAt":1750000000000},...}

# 2. 人给出答案后立刻回填
curl -s -G "$BASE/submit_human_input" --data-urlencode 'id=1' \
  --data-urlencode 'requestId=hr-1-3001' --data-urlencode 'answer=8f3k'

# 3. 取答复并立刻提交表单
curl -s -G "$BASE/get_human_input" --data-urlencode 'id=1' --data-urlencode 'requestId=hr-1-3001'
```

## 十、坑与限制

1. **除 `/execute_js`、`/commands` 外，参数只能放查询串或表单**，发 JSON 请求体等于没传参数；`execute_js` 可以用 JSON 请求体，`commands` **必须**用 POST + JSON 请求体（GET 会被拒绝）。
2. **参数缺失/类型错误返回 HTTP 500**（没有 JSON 体）；实例不存在则是 `code:0` 加 `没有找到对应的浏览器实例：<id>`。地址打不通、按键名非法这类运行期错误也会返回 `code:0`，例如 `go_to_url 失败：net::ERR_CONNECTION_REFUSED at ...`、`send_keys 失败：Unknown key: "NotAKey"`。
3. **页面变化后索引全部重算**：点击、跳转、异步渲染之后必须重新 `get_dom_text`；沿用旧索引会得到 `索引越界` 或 5 秒超时后提示重新取快照。
4. **快照里没有 `id`/`class`/`href`**：按 id/class 定位用 `click_element_by_selector`，取 href 用 `execute_js`。
5. **纯文本容器（`div`/`span`/`li`）没有索引**：这类元素用 `click_element_by_selector` 或 `execute_js` 调 `.click()`；但带 `onclick`/`cursor:pointer` 的 `div`/`span` 会有索引，别一概而论。
6. `/wait` 的 `seconds` 必填；要等页面就绪请用 `wait_for_load` / `wait_for_element`。
7. `/upload_file` 的 `path` 是服务器本地绝对路径；文件不存在会失败。
8. `/scroll_to_text` 找不到文本会等满 30 秒，别用它探测元素是否存在，用 `wait_for_element`。
9. **`execute_js` 没有超时**：脚本里不要写死循环或长时间轮询，否则请求一直挂着；页面上弹模态框时弹窗会被自动确认，也可以用 `set_dialog_behavior` 改成自动取消。
10. `execute_js` 的 `body` 上限 100000 字符；返回 DOM 元素只会得到 `ref: <Node>`。
11. `get_dom_text` 的 `highlight=true` 会在页面上加一层高亮框，它是页面里真实存在的 DOM（`extract_structured_data` 读取正文时会临时隐藏它）；人工观察时很有用，纯自动跑可以传 `highlight=false`。
12. 不要用同一个 `id` 重复 `start`：后一个实例会覆盖前一个，旧实例泄漏（不会自动关闭）。
13. 一个实例只对应一个 Page，**不要并发对同一个 `id` 发请求**；并发任务请各自 `start` 一个实例。
14. `set_credentials` 会重建上下文，当前页面会丢；`headless=false` 会弹出真实窗口，只适合本机调试。
15. 服务无鉴权且 `execute_js` 能执行任意脚本，对外部署前必须加访问控制。
16. **不可见元素既不进快照，也不能用选择器操作**：实测百度首页的真实搜索框 `INPUT#kw`（`offsetParent === null`，被新的 AI 输入框取代而隐藏）不在 `data.text` 里，只剩提交按钮；`input_text_by_selector` 作用在它上面会等满 5 秒后返回 `input_text_by_selector 失败：没匹配到可操作的元素(不存在或不可见): 选择器 #kw`。这种元素只能用 `execute_js` 直接设值并派发事件：

    ```shell
    curl -s -G "$BASE/execute_js" --data-urlencode 'id=1' --data-urlencode 'body=(function(){var e=document.querySelector("#kw");e.focus();e.value="Mac Mini M4";e.dispatchEvent(new Event("input",{bubbles:true}));return e.value;})()'
    ```

    判断元素是否可见：快照里有它就是可见的；怀疑隐藏时用 `execute_js` 看 `e.offsetParent !== null`。
17. **批量接口的约定**：每项只能有一个键；`stopOnError` 默认 `true`（遇到第一个失败就停），批量里推荐显式传 `false`；布尔参数不传按 `false` 处理（`scroll` 要写 `"down":true`）；`commands` 不能嵌套；覆盖范围是除 `commands` 外的全部接口。
18. **验证 `route` mock 时别等页面自己的回调**：实测页面加载时自己发起的 `fetch` 被 mock 后，渲染进程里的 `.then` 可能迟迟不执行（没有真实网络 IO 去唤醒它），而用 `execute_js` 主动发一次同样的请求就能立刻拿到 mock 数据。要验证拦截效果就用 `execute_js` 主动发请求，或看 `get_requests` 里的状态码。
19. **一个批次里前面的失败会影响后面**：批次是顺序执行的，索引类命令依赖同一次快照，前面的点击跳转会改变 DOM；PTC 的稳妥做法是「动作段 + 末尾 `get_dom_text`」，用下一次推理基于新快照决定后续，而不是在一个批次里塞几十步。
20. **`get_dialog` 是「最近一次弹窗」，不会自动清除**：它可能来自很早以前的一次操作，别把内容当成当前这一步的结果。实测提交验证码失败过之后，后续查询明明成功了，`get_dialog` 仍返回上一轮的「验证码输入错误」，据此误判会白跑一轮。用 `data.dialog.seq`/`timestamp` 判断新旧，或在每次提交动作前先 `get_dialog?consume=true`。
21. **`ok=true` 不代表点中了东西**：点击类接口只保证动作没抛异常。实测点悬浮菜单时文本命中的是纯文本容器，接口返回成功但页面毫无变化。判断是否生效看回执里的 `data.changed`；`click_element_by_text` / `click_element_by_role` / `hover_and_click` 还会返回真正命中的 `data.tag`/`data.outerHtml`。
22. **`input_text` 清空不了**：`text` 是必填参数，传空串会被参数校验挡掉并返回 HTTP 500「缺少参数 text」。要清空用 `clear_text`。
23. **`get_requests` 只有元数据**：`method/url/resourceType/status`，加带请求体请求的 `postData`（最多 4000 字符），**没有响应体**。要读接口返回的内容用 `wait_for_response`（先回看最近 10 秒，再等新响应）或 `get_response_body`（回看最近 100 个响应）。另外 `wait_for_response` 遇到页面**自己**发起的 fetch 时，回调可能迟迟不执行（见第 18 条），这时用 `execute_js` 主动发一次同样的请求，或改用 `get_response_body` 回看。
24. **页签索引不稳定**：实测点一次菜单会弹出两个同 URL 的重复页签，这时 `pageIndex` 很容易指错。按 URL 用 `switch_tab_by_url` 切换，用 `close_other_tabs` 清理，别一个个 `close_tab`（索引会整体前移）。
25. **图片类元素只能靠截图接口拿**：`get_dom_text` 只有文本，`execute_js` + canvas 抠图遇到跨域图片会被污染直接失败。用 `get_element_screenshot`（走 Playwright 元素截图，不受同源限制）；智能体本身读不了图时，按第九节请人来看。
