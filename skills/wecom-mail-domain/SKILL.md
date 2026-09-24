---
name: wecom-mail-domain
description: 用 deepseek-browser-use 给企业微信开通「免费版企业邮箱」并把企业自有域名绑上去的实操手册：先在 DNS 服务商（DNSPod / 腾讯云解析）加两条 MX 记录（mxbiz1.qq.com 优先级 5、mxbiz2.qq.com 优先级 10，主机记录 @），再去企业微信后台【协作 → 邮件】开通邮箱服务并把域名绑上，最后回到【我的企业 → 企业信息 → 企业域名】完成绑定。本文的核心是解决**邮箱管理控制台藏在跨域 iframe 里、常规手段完全够不着**这件事：企业微信的邮件应用把 exmail.qq.com 的控制台套在一个跨域 iframe 中，服务端的 get_browser_state / execute_js / get_modals / get_interactive_map 全都只作用于顶层文档，快照里一个 iframe 内部元素都没有；而直接打开 iframe 的 URL 会 500，因为里面的 token 是一次性的、已被 iframe 消费。破解办法是**从企业微信后台自己的接口现取一个未使用的新 token**（同源 XHR 调 apps/qykit/login/tokenAndOAuthCode），再用它把控制台当顶层页面打开，之后控制台的路由、按钮、表单全部可读写。同时给出该流程的其它坑：控制台是 hash 路由（可以直接改 location.hash 跳页，比找按钮点更可靠）、开通入口点不动时走路由表直连、DNSPod 改解析要过微信 MFA 扫码、企业域名绑定以 MX 校验为前置（所以顺序不能颠倒）。文内数据全部脱敏，替换占位符即可复用。
whenToUse: 需要给企业微信 / 腾讯企业邮箱开通邮箱服务、绑定企业自有域名、给成员分配企业邮箱，或需要驱动「主站把第三方控制台套在跨域 iframe 里」这类站点（企业微信的邮件/微盘/文档/会议应用都是这个形态），或需要处理「嵌入式应用用一次性 token 换登录态、token 被 iframe 消费后直连报错」的场景时。
---

# 企业微信：开通免费企业邮箱并绑定自有域名（实操手册）

一句话流程：**DNS 加两条 MX → 破解 iframe 进 exmail 控制台 → 绑定域名 → 回企业微信绑企业域名 → 成员邮箱自动开通**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这条链路上「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：MX 记录加好、域名在企业微信邮箱里显示「使用中」、企业微信「企业域名」绑定成功、成员邮箱分配到位，即完成。
- **人来做的部分**：企业微信后台登录（扫码）、**DNSPod 改解析要过的微信 MFA 扫码**、以及任何需要验证码/人脸的动作。
- **不做的事**：不动与本次无关的解析记录（尤其别碰 A / CNAME，会直接影响网站）、不改已有邮箱账号、不代签任何协议。
- **要提醒用户的事**：
  - **MX 记录只影响收信，不影响网站**。但**如果这个域名本来就在别处收信（比如已有其它邮箱服务商），加腾讯的 MX 会把收信切到腾讯**，先问清楚。
  - 企业微信邮箱**基础功能免费**（官方口径：全员免费使用邮箱基础功能）。要「无限容量、更多公共邮箱、邮件自动备份、IP 限制」这类高级功能才需要付费，别默认去买。
  - 域名绑定成功后，**该域名下所有成员的邮箱会按成员名自动开通**（实测 2 个成员各得一个），这一步不需要手工建。

## 1. 开工前

### 1.1 服务与浏览器

```json
{"id": 2001, "method": "start", "params": {"headless": false, "browser": "chrome"}}
```

- **`headless: false` 是必须的**：企业微信后台登录要人扫码，DNSPod 改解析要人扫 MFA，都要真实窗口。
- **`browser` 的选择要看你有没有现成的登录态**：企业微信、DNSPod、腾讯云这几家的登录态都在**共享 profile** 里（默认
  `~/.config/browseruse/profiles/shared`）。**换引擎等于换一套 profile**（Chromium 与 Firefox 的 profile 格式不通用），
  所以一旦你上次是用 `chrome` 登进去的，这次就必须还是 `chrome`，否则登录态全丢、要重扫一遍。
- 服务端**默认引擎可能是 firefox**（`browser.engine` 配置项），这时 `start` 不传 `browser` 会起 Firefox。
  **开工前先 `get_config` 看 `engine` / `configuredType`，或 `start` 后看回执的 `engineHonored` / `effectiveBrowser`**，
  别等到发现「怎么退登录了」才回头查。
- 换浏览器/引擎**只能在当前没有其它任务时做**：有任务在跑会直接报「浏览器已经在运行…不能中途切换浏览器类型」。
  要换就先 `close` 掉在跑的任务，**不用重启服务**。
- 开工前 `GET /playwright/health` 确认服务活着；`list_tasks` 看现在有哪些任务与共享浏览器的 `profileDir`。

### 1.2 开工前必须问清的字段

一次问全，避免做到一半停下来：

| 组 | 字段 |
| --- | --- |
| 域名 | 要绑的域名（如 `<域名>`）、这个域名**现在有没有在别处收信** |
| DNS | 域名在哪个解析服务商（DNSPod / 腾讯云 / 阿里云 / Cloudflare…）、**是否由你操作**、改解析要不要过 MFA |
| 邮箱 | 是否要给全员开通、是否有成员要单独分配、是否需要公共邮箱 |
| 顺序 | 确认**先加 MX 再绑域名**（反了会卡在校验） |

### 1.3 留档

- 服务端：`logs/trace/<yyyyMMdd>/`（`steps.log` 时间线、`calls.jsonl` 逐条 JSON、`NNNNNN-<任务id>-<方法>.json` 完整请求响应）。
- 客户端：`scripts/client/dsb.py`（跨平台、退出码区分传输错/业务失败/用法错、默认脱敏）或 `scripts/trace/browse.ps1`。
- **DNS 变更证据要自己留**：把「加记录前」「加记录后」的 `get_requests` / 页面文本各存一份，别只靠口头说加好了。

## 2. 第一步：加 MX 记录（在 DNS 服务商那边）

### 2.1 要加什么

企业微信/腾讯企业邮箱要求的记录固定是这两条（**主机记录留空，也就是 `@`**）：

| 记录类型 | 主机记录 | 记录值 | 优先级 | TTL |
| --- | --- | --- | --- | --- |
| MX | `@`（不填） | `mxbiz1.qq.com` | 5 | 600 |
| MX | `@`（不填） | `mxbiz2.qq.com` | 10 | 600 |

> 企业微信后台会把这两条**原样列给你**（见 3.3），以页面显示的为准。有的页面给的值带结尾的点
> （`mxbiz1.qq.com.`），那是 DNS 的绝对域名写法，**填不填点都行**。

### 2.2 DNSPod / 腾讯云解析：加记录

**入口**：`https://console.dnspod.cn/dns/list` → 点域名那一行的「解析」→ 落到
`https://console.dnspod.cn/dns/list/detail/<域名>/records`。

> **别用直连 URL 打开解析页**：实测直接 `go_to_url` 到 `console.dnspod.cn/dns/list/detail/<域名>/records`
> 只会渲染出导航壳（`#console-root` 长度 138），列表是空的。**必须从列表页点「解析」进去**，SPA 才会把数据拉起来。

**加一条记录的步骤（实测）**：点「添加记录」→ 表格里**内联展开一行**（不是弹窗）→ 依次填。

**这一行的字段定位（用 `name` 属性，最稳）**：

| 字段 | 选择器 | 说明 |
| --- | --- | --- |
| 主机记录 | `input[name=Name]` | 填 `@`（或留空，等于 `@`） |
| 记录类型 | 点显示当前类型的 `div`（如显示 `A` 的那个） | 弹出 `li` 列表，选 `MX` |
| 记录值 | `input[name=Value]` | `mxbiz1.qq.com` |
| 权重 | `input[name=Weight]` | 留空 |
| 优先级 | `input[name=MX]` | **只有选了 MX 才会出现这个输入框**，填 5 |
| TTL | `input[name=TTL]` | 默认 600，不用改 |
| 确认 | 该行内的「确认」按钮 | 提交 |

**关键点**：

- **`input[name=MX]` 是「优先级」**（属性名容易误解成记录类型）。选 A 类型时它不存在，选 MX 后才渲染。
- 主机记录旁边有快捷按钮 `www` / `@` / `mail` / `*`，**点 `@` 那个按钮比手打更省事**（按钮文本就是 `@`）。
- 记录类型下拉的选项是 `li` 元素，文本为 `A` / `CNAME` / `MX` / `TXT` / `AAAA` / `NS` / `CAA` / `SRV`；
  用 JS 找**文本完全等于 `MX`** 的最内层元素点它。
- 一行填完**必须点「确认」**，否则记录不落库。提交成功会有「添加成功」提示，并给出「预计最晚生效时间」。

**可直接复用的写法**（把每一步拆开、每步之间等一拍）：

```json
{"id":2001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{const n=document.querySelector('input[name=Name]');if(n)n.id='dsh_name';const v=document.querySelector('input[name=Value]');if(v)v.id='dsh_value';const t=document.querySelector('input[name=TTL]');if(t)t.id='dsh_ttl';const d=[...document.querySelectorAll('div')].filter(e=>e.offsetParent!==null&&e.innerText&&e.innerText.trim()==='A'&&e.children.length===0).pop();if(d){d.id='dsh_type';d.click()}return {name:!!n,value:!!v,type:!!d}})()"}},
  {"input_text_by_selector":{"selector":"#dsh_name","text":"@"}},
  {"wait":{"seconds":3}},
  {"execute_js":{"body":"(()=>{const o=[...document.querySelectorAll('li,div,span')].filter(e=>e.offsetParent!==null&&e.innerText&&e.innerText.trim()==='MX').pop();if(!o)return {noOpt:true};o.click();return {ok:true}})()"}},
  {"wait":{"seconds":3}},
  {"execute_js":{"body":"(()=>{const row=[...document.querySelectorAll('tr')].filter(e=>e.offsetParent!==null&&e.querySelector('input[name=Name]'))[0];if(!row)return {noRow:true};const v=row.querySelector('input[name=Value]');const m=row.querySelector('input[name=MX]');if(v)v.id='dsh_value';if(m)m.id='dsh_mx';return {v:!!v,m:!!m}})()"}},
  {"input_text_by_selector":{"selector":"#dsh_value","text":"mxbiz1.qq.com"}},
  {"input_text_by_selector":{"selector":"#dsh_mx","text":"5"}},
  {"execute_js":{"body":"(()=>{const row=[...document.querySelectorAll('tr')].filter(e=>e.offsetParent!==null&&e.querySelector('input[name=Name]'))[0];const b=[...row.querySelectorAll('button')].filter(e=>e.innerText.trim()==='确认')[0];if(!b)return {noBtn:true};b.click();return {ok:true}})()"}},
  {"wait":{"seconds":8}}
]}}
```

第二条（`mxbiz2.qq.com` / 优先级 10）重复一遍即可。

### 2.3 坑：DNSPod 改解析要过微信 MFA 扫码

**实测**：点「确认」提交时，DNSPod 会弹「身份验证」浮层：

> 身份验证 —— 为了您的账号安全，请用微信扫码验证身份。 MFA 是什么？
> 切换成普通二维码
> 需要（微信：`<脱敏后的微信号>`）扫码验证身份

- 这是**真实鼠标操作才能过的**，智能体过不去，**必须交给人**。
- 用 `request_human_input`（主技能第六/九节）把二维码交给用户，比自己在聊天里贴截图路径更规范：

```json
{"id":2001,"method":"request_human_input","params":{
  "selector":"img[src*='genQrImg']",
  "prompt":"DNSPod 要求微信 MFA 扫码才能改解析，请用微信 <微信号> 扫码，完成后告诉我",
  "timeoutSeconds":300}}
```

- **二维码有时效**（几分钟）。超时了要重新点一次「确认」生成新的，别拿旧码反复催用户。
- 扫码期间那个浮层里有「登录失效，请重新登录」这类提示时会**一闪而过**（是 toast，不是真失效）——
  别被它误导去重登。判断是否真失效：看页面列表还在不在、能不能读到记录，而不是看有没有出现过那句提示。
- **MFA 通过后**，之前填好的表单内容会保留，直接继续提交即可。

### 2.4 验证 MX 真的生效

**别只看 DNSPod 页面说「添加成功」**，要独立验证。多个解析器都查一遍（有些解析器缓存旧结果）：

```powershell
foreach($s in @('8.8.8.8','1.1.1.1','119.29.29.29','223.5.5.5')){
  "=== $s ==="
  Resolve-DnsName <域名> -Type MX -Server $s -ErrorAction Stop |
    Where-Object { $_.NameExchange } |
    ForEach-Object { "  $($_.NameExchange)  pref=$($_.Preference)" }
}
```

- **`119.29.29.29` 是腾讯自己的 DNS**，它能看到就说明腾讯侧没问题，最贴近企业微信校验时的视角。
- 再查一次**权威 NS**（DNSPod 的 `booking.dnspod.net` / `rum.dnspod.net`），权威有就说明记录真的落库了。
- 实测：记录加完**几分钟内**四个公共解析器就都能查到；但**企业微信页面上的「立即验证」可能要再等一会**才过。

## 3. 第二步：破解跨域 iframe，进邮箱控制台

**这一节是本文的核心。** 企业微信后台的「邮件」应用**不是企业微信自己的页面**，而是把
`exmail.qq.com` 的邮箱管理控制台套在一个**跨域 iframe** 里。

### 3.1 为什么常规手段全都够不着

实测结论（2026-09，服务端 113 个方法的新构建）：

| 手段 | 结果 |
| --- | --- |
| `get_browser_state` | 只拿到企业微信后台外壳，**iframe 内部一个元素都没有** |
| `execute_js` | 在**顶层文档**执行，`document.querySelector` 穿不透跨域 iframe |
| `get_modals` / `get_interactive_map` | 都只扫顶层 `document`，同上 |
| `list_methods` 里找 frame 相关命令 | **一个都没有**（`get_frames` / `switch_frame` / `execute_js_in_frame` 全返回「不支持的方法」） |
| 直接 `go_to_url` 打开 iframe 的 `src` | **`HTTP Error 500 内部服务器错误`** |

**最后一条的原因**：iframe 的 URL 里带 `token` + `wwmng_authcode`，**是一次性的**。页面加载时 iframe
已经把 token 消费掉了，你再拿同一个 URL 去开就是无效 token → 500。

> 顺带说明：Playwright 本身**是能跨 frame 的**（它走 CDP / juggler 协议，不依赖往页面里注入 JS），
> 所以这不是浏览器能力的限制，而是服务端没有把 frame 能力暴露出来。等主技能补上 frame 支持后，
> 本节的做法可以退化成「直接指定 frame」；在那之前，下面的 token 换法是最可靠的。

### 3.2 破解：从企业微信后台现取一个新 token

企业微信后台自己有个接口在给各个嵌入式应用发 token：

```
POST https://work.weixin.qq.com/wework_admin/apps/qykit/login/tokenAndOAuthCode?lang=zh_CN&f=json&ajax=1
Content-Type: application/x-www-form-urlencoded

type=exmail&srcUrl=exmail.qq.com%2Fmail%2Fmngpage&noopentoken=1
```

响应：

```json
{"data":{"token":"<64 位左右的 token>","auth_code":"<auth_code>"}}
```

**关键点**：这个接口与企业微信后台**同源**，所以可以在后台页面上用 `execute_js` 直接调它，
**拿一个全新的、还没被任何 iframe 消费过的 token**，然后立刻把顶层页签导航到控制台 URL。

用**同步 XHR**（`execute_js` 不保证 await Promise，同步 XHR 最稳）：

```json
{"id":2001,"method":"execute_js","params":{"body":"(()=>{const x=new XMLHttpRequest();x.open('POST','/wework_admin/apps/qykit/login/tokenAndOAuthCode?lang=zh_CN&f=json&ajax=1',false);x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');x.send('type=exmail&srcUrl=exmail.qq.com%2Fmail%2Fmngpage&noopentoken=1');return JSON.parse(x.responseText).data})()"}}
```

拿到 token 后**立刻**顶层打开（不要先做别的事，token 有短时效）：

```
https://exmail.qq.com/mail/mngpage?qykit_goto=&locale=zh&token=<token>&wwmng_authcode=<auth_code>&style=top_bar#toolbox/index
```

**这一步成功的话**，你会看到页面变成腾讯企业邮箱的管理后台，`document.body.innerText` 里出现
「概况 / 邮箱域名 / 邮箱账号 / 邮箱管理 / 安全管理 / 数据统计 / 操作日志 / 配置」这些导航项。

**完整的两步骨架**：

```json
{"id":2001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#apps/qykit/proxy/exmail"}},
  {"wait":{"seconds":8}},
  {"execute_js":{"body":"(()=>{const x=new XMLHttpRequest();x.open('POST','/wework_admin/apps/qykit/login/tokenAndOAuthCode?lang=zh_CN&f=json&ajax=1',false);x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');x.send('type=exmail&srcUrl=exmail.qq.com%2Fmail%2Fmngpage&noopentoken=1');return JSON.parse(x.responseText).data})()"}}
]}}
```

然后**把上一步返回的 token 与 auth_code 拼进 URL**，再发一次 `go_to_url`。**必须分两次请求**
（第二次要用第一次的结果），不要试图塞进同一个批次。

> **这个套路可以推广**：企业微信后台的其它嵌入式应用（微盘 `drive.weixin.qq.com`、文档
> `doc.weixin.qq.com`、会议 `wwmeeting.weixin.qq.com`）走的是**同一个接口**，只是
> `type` / `srcUrl` 不同——它们都出现在同一个页面的 iframe 列表里，可以从 `iframe.src` 反查
> 各自的 `type` 与 `srcUrl`（`srcUrl` 就是 iframe src 里 `mngpage` 那段路径去掉参数）。
> 更一般地：**遇到「主站把第三方控制台套在 iframe 里」时，先去找主站发 token / 换登录态的接口，
> 用同源 XHR 现取一份，再顶层直连第三方**——这比在 iframe 里盲点坐标可靠得多。

### 3.3 控制台是 hash 路由：直接改 hash 跳页

进到控制台后，**别急着找按钮点**——它是标准的 hash 路由，`location.hash` 直接改就能跳页，
比找按钮点稳得多（按钮可能是纯 JS 事件、点了没反应）。

从页面上的 `<a title="..." href="#...">` 反查出路由表（实测）：

| 导航项 | hash 路由 |
| --- | --- |
| 概况 | `#toolbox/index` |
| **邮箱域名** | `#qyww/domain/list` |
| 邮箱账号 | `#toolbox/weworkemail/list` |
| 邮箱管理 | `#toolbox/open` |
| 安全管理 | `#toolbox/receive_send` |
| 数据统计 | `#toolbox/statistics` |
| 操作日志 | `#qyww/operateLog/mail` |
| 配置 | `#toolbox/user_login` |

```json
{"id":2001,"method":"execute_js","params":{"body":"(()=>{location.hash='#qyww/domain/list';return {h:location.hash}})()"}}
```

**注意**：`location.hash` 跳转是**同文档导航**，不会重新请求那个一次性 token，所以**可以反复用**。
（而改 `iframe.src` 或重新 `go_to_url` 到带 token 的 URL 会重新请求 → 500。这个区别很关键。）

**回读路由表的写法**（以后页面改版了可以自己重新查）：

```js
(()=>[...document.querySelectorAll('a')].filter(e=>e.getAttribute('title'))
  .map(e=>({title:e.getAttribute('title'),href:e.getAttribute('href')})))()
```

### 3.4 开通邮箱服务并绑定域名

进到 `#qyww/domain/list` 后：

**未开通时**页面是：

> 开通企业微信邮箱服务，使用统一的邮箱域名
> 免费为全员开通统一的邮箱域名，更正式更专业
> **开通** / 了解更多

**坑**：实测点这个「开通」（`<a class="domain_open">开通</a>`）**只触发一次埋点与一个
`activeSuggestDomain` 接口，页面不变、也不弹窗**。埋点名叫
`AdminConsole_SetDomainPage_NewChooseDialog`，说明本该弹出一个「选择开通方式」的弹窗，
但实测没渲染出来。

**别在按钮上耗时间，直接走路由**（这才是稳的路子）：

```
#qyww/domain/add            → 「配置域名」页
#qyww/domain/choose/self    → 「配置已有域名」页（输入框）
#domain/config/<域名>        → 「配置已有域名」的解析指引页
```

- `#qyww/domain/add` 页面内容（实测）：

  > 配置域名
  > 你的企业正在使用默认域名 @`<企业默认域名>`.wecom.work
  > 默认域名每日全员最多可发 50 封，建议配置企业专属域名
  > **免费领取域名** ｜ **已有正在使用的域名**

  **选「已有正在使用的域名」**（我们要绑自己的域名）。它是一个 `<span class="qywx_item_txt">`，
  点它之后 URL 会变成 `#qyww/domain/choose/self`。

- `#qyww/domain/choose/self`：一个 `input[placeholder="请输入域名，如：work.com"]`，填域名 → 点「下一步」。

- `#domain/config/<域名>`：给出两条 MX 记录（与第 2 节一致）和「**已完成设置，立即验证**」按钮。
  这时点「立即验证」。

  **坑**：实测第一次点，按钮文本会变成「正在验证中」然后又变回「已完成设置，立即验证」，
  而**没有发出任何校验请求**（`get_requests` 里只有一个埋点 `domain|own|verify|now`）。
  这种情况**先确认 MX 是否真的全球生效**（第 2.4 节），确认无误后**再点一次**；
  也可以直接 `location.hash='#qyww/domain/list'` 去看域名列表的实际状态——
  **列表显示「使用中」才是真的成功了**，比盯按钮可靠。

- `#qyww/domain/list` 成功后的样子：

  > 添加域名 / 管理记录 / 购买记录
  > **`<域名>`** — **使用中** — 域名指向： 企业微信 — 查看详情

### 3.5 成员邮箱会自动开通

域名绑定成功后，回 `#toolbox/index`（概况）能看到：

> 使用情况
> 总使用成员数 **N** 人
> 使用邮箱服务
> 企业域名 **`<域名>`**

再看 `#toolbox/weworkemail/list`（邮箱账号）：

> 成员邮箱账号 · N
> 成员邮箱账号是为企业通讯录成员提供的免费邮箱账号，可以在企业微信客户端使用邮件
> 姓名 | 企业邮箱 | 部门 | 操作
> `<成员姓名>` | **`<成员名>@<域名>`** | `<部门>` | 编辑
> …

**这一步不需要手工建**——企业微信会按通讯录成员自动分配 `<成员名>@<域名>`。
（默认域名 `@<企业默认域名>.wecom.work` 会被替换成你的域名。）

> 如果确实要建一个**不属于任何成员**的地址（例如给某个验证流程用的随机地址），
> 用「其他邮箱账号」那一栏的「添加邮箱」/「导入」入口，而不是去建假成员。

## 4. 第三步：回企业微信绑定「企业域名」

**顺序很重要**：**企业域名绑定以「域名能收信」为前置**，所以必须**先**在企业微信邮箱里把域名绑好
（第 3 节），**再**回企业微信企业信息里绑企业域名。反了会卡在验证上。

- 入口：`https://work.weixin.qq.com/wework_admin/frame#profile/domain`
  （企业信息页 `#profile` 里「企业域名」那一行的链接是 `<a href="#profile/domain">添加</a>`）
- 页面：**绑定企业域名** / 企业域名: `input[placeholder="abc.com"]` / **绑定域名**
- 填域名 → 点「绑定域名」→ 成功显示：

  > **已绑定 `<域名>` 企业域名**

- 回到 `#profile` 能看到「企业域名 `<域名>` 修改 删除」。

> **实测要点**：域名已经通过企业微信邮箱验证过之后，这里**只需要填域名点绑定，不会再要求你去建
> 一个验证邮箱**。（企业微信的域名验证有两条路：DNS/MX 校验，或「在域名下创建指定地址的邮箱收验证信」。
> 走通了邮箱这条路之后，邮箱那条就自动满足了。）

## 5. 该流程的通用坑

### 5.1 后台内容区是内层滚动容器，不是 window

企业微信后台的滚动发生在**内层容器**上：

- `window.scrollTo(0, 0)` **无效**（实测 `window.scrollY` 恒为 0，而目标元素在 `y=-192`）。
- **快照默认只覆盖当前视口**，视口外的元素**不在 `data.text` 里、也没有索引**。所以「找不到元素」
  先怀疑它不在视口内，而不是选择器写错了。

**三条解法，按优先级**：

1. **`get_browser_state` 传 `viewportExpansion`**（**最省事，很多人不知道它存在**）：

   ```json
   {"id":2001,"method":"get_browser_state","params":{"highlight":false,"viewportExpansion":1500}}
   ```

   视口外扩的像素数，默认 `0`。企业微信后台这种「首屏放不下」的页面，直接调大到 `1000~2000`
   就能一次拿到首屏之外的元素索引，**不用滚动**。回执里 `data.pixels_above` / `data.pixels_below`
   会告诉你上下还差多少没进快照——**如果它们不为 0，就说明扩得还不够**。

2. **改用选择器定位**：`click_element_by_selector` / `input_text_by_selector` /
   `click_element_by_text`（按 id/class/文本定位，**不依赖快照索引**）。这是最稳的路子，
   不受视口和索引失效影响。

3. **滚动**：先找到内层滚动容器再 `scroll`（`scroll` 命令默认操作页面/元素，必要时用
   `execute_js` 直接改容器的 `scrollTop`）。

### 5.2 索引失效极快

任何点击、异步渲染之后**索引全部重算**，沿用旧索引会得到「元素不存在或页面已变化」或「索引越界」。
稳妥节奏是「**一次 `get_browser_state` → 只做一个动作 → 再取快照**」，或者全程用选择器。
主技能第七节的「动作段 + 末尾回读」也是这个意思。

### 5.3 弹窗要主动关，但别只信 `get_modals`

- 浮层会挡住后面的点击。优先 `get_modals` + `close_modal`（**它一律用真实鼠标点**，
  因为 ant-design 这类框架的弹窗只认真实鼠标事件，JS 派发 `click` 完全无效）。
- **但要注意**：`get_modals` 的选择器是 ant-design / Element-UI / vxe / layui 专用的
  （`.ant-modal-wrap`、`.el-dialog`、`.layui-layer`、`[role=dialog]`…），**企业微信自己的弹窗类名
  不一定命中**。实测发票弹窗的类名是 `.mall_invoice_dialog_container`。
- **所以 `get_modals` 返回 0 不等于没有弹窗。** 这时用 `execute_js` 自己扫可见浮层：

```js
(()=>{const all=[...document.querySelectorAll('div')].filter(e=>{
    const s=getComputedStyle(e); if(s.display==='none'||s.visibility==='hidden') return false;
    const z=parseInt(s.zIndex); const r=e.getBoundingClientRect();
    return (s.position==='fixed'||(!isNaN(z)&&z>50)) && r.width>150 && r.height>60;
  });
  return all.map(e=>({cls:String(e.className).slice(0,50),
    pos:getComputedStyle(e).position, z:getComputedStyle(e).zIndex,
    txt:(e.innerText||'').slice(0,120)}));})()
```

### 5.4 `ok=true` 不代表点中了东西

点击类方法只保证动作没抛异常。**实测点「开通」返回 `ok=true`、`changed=false`，页面毫无变化。**
所以：**关键动作后一定要回读页面**（`document.body.innerText` 的片段、或 `expect` 断言），
不要只看 `ok`。

### 5.5 任务会随服务重启消失

服务重启后**任务 id 全部失效**（实例只在内存里），再操作会得到
`没有找到对应的浏览器实例：<id>`。**登录态还在 profile 里**，重新 `start` 同一个 id 即可继续。
`list_tasks` 能随时看到活着的任务。

### 5.6 企业微信侧的域名校验以 MX 为准

`getdomainDNS` 这类接口会回 `nameserver` / `ns` / `has_cname_record` 等信息；企业微信校验 MX 时
看的是**该域名权威 NS 上的 MX 记录**。所以第 2.4 节的多解析器验证不是可选项——**先证明 DNS 对了，
再去点验证按钮**，否则你会在按钮上反复点而不知道问题在 DNS 侧。

## 6. 一次典型任务的骨架

```json
{"id": 2001, "method": "start", "params": {"headless": false, "browser": "chrome"}}
```

```json
{"id":2001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://console.dnspod.cn/dns/list"}},
  {"wait":{"seconds":10}},
  {"execute_js":{"body":"(()=>({url:location.href,txt:document.body.innerText.slice(0,600)}))()"}}
]}}
```

> 加 MX 记录（第 2.2 节，两条）→ 交人过 MFA（第 2.3 节）→ 多解析器验证（第 2.4 节）
> → 取 token 进控制台（第 3.2 节）→ hash 跳 `#qyww/domain/list` 绑域名（第 3.4 节）
> → 回 `#profile/domain` 绑企业域名（第 4 节）→ 回读 `#profile` 与邮箱账号页确认（第 3.5 节）。

**节奏**：不要在一个批次里塞几十步。推荐「**动作段 + 末尾回读**」——连做几个动作，最后用一次
`execute_js` 回读 URL / 关键文本 / 错误，下一次推理基于新结果决定后续。

## 7. 交付话术（收尾）

1. 给一张**记录表**，逐条写清加在哪、值是什么：

   | 主机记录 | 类型 | 记录值 | 优先级 | 状态 |
   | --- | --- | --- | --- | --- |
   | `@` | MX | `mxbiz1.qq.com` | 5 | ✅ 已生效 |
   | `@` | MX | `mxbiz2.qq.com` | 10 | ✅ 已生效 |

2. 说明**DNS 生效情况**：给出验证过的解析器列表（含腾讯的 `119.29.29.29`）与权威 NS 的结果；
   说明「运营商缓存可能需要几十分钟到几十小时，但主流公共解析器已经能查到」。
3. 说明**邮箱服务状态**：企业域名 `<域名>` **使用中**、域名指向 企业微信；**基础功能免费**，
   没有产生费用。
4. 说明**成员邮箱**：列出 `<成员>@<域名>`（按脱敏约定只留必要部分），并说明是**自动开通**的，
   不需要手工建。
5. 说明**企业域名绑定**：企业微信「企业信息 → 企业域名」已显示 `<域名>`。
6. **提醒用户**：
   - MX 只影响收信；如果这个域名原来在别处收信，收信已经切到腾讯了。
   - A / CNAME 记录**没有动**，网站不受影响。
   - 建议去企业微信客户端或邮箱网页版**实发一封测试邮件**验证收发。
   - 清理留档：`logs/trace/**`、`logs/agent/**`（可能含账号、域名等信息），
     或用 `cleanup`（`{"scope":"trace","olderThanHours":24}`，**默认只预演**，要真删加 `"dryRun":false`）。

## 8. 脱敏约定

本文所有示例都是占位符：`<域名>`、`<企业默认域名>`、`<企业全称>`、`<企业ID>`、`<成员姓名>`、
`<微信号>`、`<订单号>`。

- **不要把真实值写回技能文档**（真实域名、企业名、企业 ID、成员姓名、微信号、token、本机绝对路径）。
- **token 与 auth_code 绝对不要写进文档或日志**：它们能直接换到邮箱管理控制台的管理权限，
  且是一次性的。给用户看过程记录时也要打码。
- 给用户看的过程记录里，域名可以保留（是公开信息），但企业 ID、成员姓名、微信号**要打码**。
