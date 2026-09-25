# 批量指令与 execute_js

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

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

- **覆盖范围**：`commands.md` 里的方法，除 `commands` 自身（不允许嵌套）之外**全部都能批量调用**，命令名与单独调用完全一致。
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
- **脚本重发无害时传 `retryOnSpurious: true`**：这个站点/这个页面上 `execute_js` 报 `Object doesn't exist: response@…`（对象与脚本毫不相干）时，服务端会替你重发（最多 3 次），成功后在 `data.spuriousRetry` 里说明。**只给读页面/取值的脚本传**；会点按钮、提交表单的脚本不要传 —— 那种脚本重发等于再执行一次。机制见 `pitfalls.md` 第 47 条。
- `body` 长度上限 100000 字符；脚本**没有超时**。
- **`execute_js` 一定会 await Promise**（回执里的 `data.awaited` 恒为 `true`）：脚本返回 Promise 时会等它 settle，返回函数时会先调用再等。所以需要现取一个接口值时直接写 `async () => { const r = await fetch(url, {credentials:'same-origin'}); return r.json(); }`，**不要**再用已废弃的同步 XHR（`x.open(..., false)`）去绕——它会阻塞渲染线程，而且没有理由。
- **在跨域 iframe 里执行要传 `frame`**：不传时脚本跑在顶层文档，`document.querySelector` 穿不透 iframe。取值是 frame 序号（0 是主 frame，见 `list_frames`）或 URL / name 子串：

  ```json
  {"id":1001,"method":"execute_js","params":{"frame":"exmail.qq.com","body":"async () => (await fetch('/cgi-bin/x',{credentials:'same-origin'})).json()"}}
  ```

  回执里会带上 `data.frame` 与 `data.frameUrl`，便于确认这次到底在哪个文档里跑的。
- 与 `get_browser_state` 的分工：**读页面优先用 `get_browser_state`**（结构化、带索引、token 可控）；`execute_js` 用于取快照里没有的东西（`id`/`class`/`href`、滚动位置、localStorage 原始值）或做特殊交互。

### 用 `bodyFile` + `vars` 传长脚本（客户端-服务器模式下必看）

> **多行脚本走命令行会被 shell 吃掉，这是 Windows 上最常见的一类假故障。** 把多行 JS 直接塞进
> `dsb.cmd`/PowerShell 的参数里时，可能只有第一行到达服务端，报错却是语法级的
> `SyntaxError: Unexpected end of input` —— 只说语法，人根本想不到是传输层把脚本切了。所以服务端遇到这类
> 语法错误会额外回一句 `data.hint`（「脚本像是被截断了…改用 bodyFile 或 `js @脚本.js`」）并给出
> `data.scriptLength`，客户端侧也用文件传（`js @脚本.js` / `--params @文件.json`），别在命令行里拼多行脚本。

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
