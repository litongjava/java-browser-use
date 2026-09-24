# 实战反馈：企业微信全流程（2026-09-24）

一次真实任务（上海一看就会信息科技有限公司开通企业微信 → 企业认证 ¥300 → 完善企业信息 → 开通企业邮箱并绑定自有域名 → 开发票 → 申请合同）跑完后，对 deepseek-browser-use 的问题记录与改进建议。

- 服务端：113 个方法的新构建（含 `list_methods` / `get_modals` / `request_human_input` / `run_recipe` / 异步批次）
- 站点：`work.weixin.qq.com`（Vue SPA）、`console.dnspod.cn`（Vue SPA）、`exmail.qq.com`（跨域 iframe 内的第三方控制台）
- 产物：`skills/wecom-register-certify`、`skills/wecom-profile-complete`、`skills/wecom-mail-domain`、`skills/wecom-invoice-contract`

**最该做的三件事**：① 把 frame 能力暴露出来；② 给 `upload_file` 加回读校验；③ 加 `get_element_listeners`（或把监听器信息塞进 `get_interactive_map`）。

---

## 一、遇到的问题

### P1（最严重）跨域 iframe 完全够不着

**现象**：企业微信后台的「邮件」应用是把 `exmail.qq.com` 的邮箱管理控制台套在一个**跨域 iframe** 里。
常规手段全部失效：

| 手段 | 实测结果 |
| --- | --- |
| `get_browser_state` | 只拿到企业微信后台外壳，iframe 内部**一个元素都没有** |
| `execute_js` | 在顶层文档执行，`document.querySelector` 穿不透跨域 iframe |
| `get_modals` / `get_interactive_map` | 只扫顶层 `document`，同样够不着 |
| `list_methods` 找 frame 命令 | **一个都没有**（`get_frames` / `list_frames` / `get_frame_tree` / `switch_frame` / `execute_js_in_frame` 全返回「不支持的方法」） |
| 直接 `go_to_url` 打开 iframe 的 `src` | **`HTTP Error 500`**（token 一次性，已被 iframe 消费） |

**源码证据**（这不是配置问题，是能力缺失）：

| 位置 | 事实 |
| --- | --- |
| `service/BrowserInstance.java:28` | 只有 `public Page page`，**没有任何 Frame 字段或集合** |
| `dom/service/DomService.java:56` | `page.evaluate(expression, args)` —— 顶层文档 |
| `PlaywrightService.java:4980` `MODALS_PROBE` | `document.querySelectorAll(...)` —— 顶层文档 |
| `PlaywrightService.java:5710` `getInteractiveMap` | `document.evaluate(xpath, document, ...)` —— 顶层文档 |
| 全仓 `grep -i "frame\|iframe"` | 11 处命中，**全是堆栈跟踪的 "frame" 字样**，无一处是 frame 处理 |

**关键洞察**：**Playwright 本身是能跨 frame 的**——它走 CDP / juggler 协议在浏览器层面取内容，
不依赖往页面里注入 JS。所以这不是浏览器能力的限制，而是服务端没有把 `page.frames()` 暴露出来。

**影响**：任何「主站把第三方控制台套在 iframe 里」的站点都不可用。企业微信的**邮件 / 微盘 / 文档 / 会议**
四个应用全是这个形态，所以这不是个例。

**我的绕过方式**：找到主站发 token 的接口（`POST /wework_admin/apps/qykit/login/tokenAndOAuthCode`），
用**同源 XHR**（`execute_js` 里发）现取一个**未被消费的新 token**，再用它把控制台**当顶层页面**打开，
之后控制台就完全可读写了。详见 `skills/wecom-mail-domain/SKILL.md` 第 3 节。

> 这个绕过方式虽然有效，但它依赖「主站恰好有一个可同源调用的 token 接口」，**不是通用解**。

---

### P2（最隐蔽）`upload_file` 报成功，页面其实没生效

**现象**：上传营业执照，`upload_file` 返回 `ok:true`，但页面一直停在 `image_uploader form_err`
和「请上传工商营业执照」，表单校验过不去。

**根因**：那个 `ImageUploader` 组件的 `<input class="uploadInput">` **没有挂任何事件监听器**
（Vue 2 的 `el._vei === null`）。`setInputFiles` 把 `input.files` 设好了，但派发的 `change`
事件到不了框架的 handler，所以组件的 `upload()` 从来没被调用。

**源码证据**：`PlaywrightService.java:2866-2871`

```java
locator.setInputFiles(file, new Locator.SetInputFilesOptions().setTimeout(actionTimeoutMs(timeoutMs)));
...
return RespBodyVo.ok(Kv.by("filename", ...).set("path", ...).set("size", ...)
    .set("target", target).set("mode", "native"));
```

**只调了 `setInputFiles` 就返回成功，没有任何回读校验**。而 `mode:"native"` 这个字段名会让人
误以为「原生方式一定进了框架」——实际上 `setInputFiles` 的语义只是「把文件放进 input」，
**页面有没有消费完全是另一回事**。

**对比**：`input_text` 这类命令已经有 `mode` / `committed` 的概念（`PlaywrightService.java:2438`），
说明团队已经意识到了「DOM 设值 ≠ 进框架 model」这个问题，但 `upload_file` 这条路径漏了。

**影响**：这是最耗时的坑——报错信息指向「请上传营业执照」，会让人以为是选择器错了、文件错了、
或者上传接口挂了，而真正的原因在**组件没监听器**上。我在这上面绕了很久（试过换输入框、
试过把隐藏 input 强制显示出来、试过手写 `change` 事件），最后靠 `el._vei` 才定位到。

**我的绕过方式**：直接调组件方法并复刻它的状态写回：

```js
vm.upload({files: fileList}, onSuccess, onError);   // 真发 POST /wework_admin/wwAuth/upload_img
vm.list.push({key: filekey, loadingIndex: null});
vm.emitChange();                                    // = $emit("change", list.map(x => x.key))
```

---

### P3 索引失效与「`ok=true` 但没点中」

**现象**：
- 反复遇到「元素不存在或页面已变化，请重新调用 `get_browser_state`」和「索引越界」
- 点「开通」返回 `ok=true` / `changed=false`，**页面毫无变化**（该按钮只触发了一次埋点和一个接口）

**现状**：主技能第 3 条与第 21 条**已经覆盖**了这两件事，文档没问题。
问题在于**排查成本**：拿到 `changed=false` 之后没有任何进一步线索，只能靠人再取快照、再回读文本。

**建议**：见 B5（错误信息带下一步）。

---

### P4 弹窗检测覆盖不全

**现象**：发票弹窗（类名 `.mall_invoice_dialog_container`）在 `get_modals` 的候选里**很可能命中不了**。

**源码证据**：`PlaywrightService.java:5027-5030` 的选择器是框架专用的：

```js
document.querySelectorAll('.ant-modal-wrap, .ant-drawer-open')          // ant-design
document.querySelectorAll('.agreement-container, [class*=agreement]')   // 协议层
document.querySelectorAll('[role=dialog], .el-dialog, .vxe-modal--wrapper, .layui-layer')
```

企业微信自己的弹窗用的是 `qui_dialog` / `ww_dialog` / `mall_invoice_dialog_container` 这类自有类名。
**`[role=dialog]` 是唯一的通用兜底，但不保证企业微信会加这个属性。**

**影响**：`get_modals` 返回 `count:0` 会被理解成「没有弹窗」，于是继续点后面的元素——而弹窗
其实还在，把点击全挡住了。这个「假阴性」比假阳性危险。

---

### P5 服务重启后任务静默消失

**现象**：服务重启后（进程从 18820 换成新的一对 PID），操作原任务得到：

```json
{"ok":false,"msg":"没有找到对应的浏览器实例：2002",
 "data":{"retryable":false,"errorCode":"ACTION_FAILED"}}
```

**问题**：`retryable:false` 让人以为「这个 id 就是不存在」，但真实原因是**服务重启过、实例只在内存里**。
错误信息里没有任何指向这个原因的线索。我一开始怀疑是自己 id 用错了。

**已有缓解**：`list_tasks` 能看出来（里面只有别的任务）。但这是「事后自己想到去查」，不是错误信息直接告诉我的。

---

### P6 换引擎会静默换掉登录态

**现象**：服务端把默认引擎配成了 `firefox`（`get_config` → `engine: "firefox"`），而我的企业微信 /
DNSPod / 腾讯云登录态都在 **Chromium profile** 里。如果不知情直接 `start`（不传 `browser`），
会起 Firefox，然后「所有站点都退登录了」。

**现状**：主技能第二节与第 29 条**已经写清楚了**「换引擎等于换一套登录态」。
而且新构建的 `start` 回执已经给了 `requestedBrowser` / `effectiveBrowser` / `engineHonored`，
比老版本好很多。

**残留问题**：`engineHonored:true` 只说明「参数被采纳了」，**不说明「这份 profile 里有登录态」**。
「这份 profile 之前用过吗」这个信息，调用方拿不到。

---

### P7 读不了图的模型没有兜底

**现象**：当前模型（`deepseek-v4.1-flash`）不支持图片输入，`read_image` 直接报错：

> cannot read "..." as an image: model "deepseek-v4.1-flash" does not declare image input

**影响**：凡是「必须看图」的环节（二维码、验证码、图表、纯图片元素、整站维护图）都断了。
我这次的做法是 `get_element_screenshot` 存盘 → 起一个 HTTP 可访问的路径 → 在聊天里把 URL 贴给用户，
让用户自己看。**能用，但很粗糙**，而且二维码有短时效，来回沟通容易超时。

**已有缓解**：`request_human_input` 是这次更新最有价值的补充之一，正好解决这个场景。
但它返回的是 `imageBase64`，**对读不了图的模型仍然没用**。

**另有一条已知的兜底**：`skills/cnipa-trademark-register/SKILL.md` 1.5 节记录了用
**Windows 自带 OCR**（`Windows.Media.Ocr`，支持 `zh-Hans-CN`）读维护图的做法，实测可读。
这个能力应该被服务端吸收，而不是让每个任务 skill 各写一遍。

---

### P8 `execute_js` 的异步语义不明确

**现象**：我需要用 `execute_js` 调一个接口拿 token。不确定 `execute_js` 会不会 await Promise，
最后用了**同步 XHR**（`x.open(..., false)`）来规避——能用，但同步 XHR 已废弃，而且会阻塞渲染线程。

**建议**：明确文档化「`execute_js` 是否 await 返回值里的 Promise」，或直接支持 await。

---

## 二、工程改进建议（按性价比排序）

### B1 暴露 frame 能力 ⭐️ 最高价值

**为什么**：P1 是本次唯一「完全走不通、只能靠 hack 绕过」的问题，而且它影响的是一整类站点
（主站套第三方控制台）。Playwright 本身支持，改动成本不高。

**最小可用改动（一天量级）**：

1. 新增 `list_frames`：

   ```json
   {"index":0,"url":"https://work.weixin.qq.com/...","name":"","isMain":true}
   {"index":1,"url":"https://exmail.qq.com/mail/mngpage?...","name":"","isMain":false}
   ```

2. 给 `get_browser_state` 加 `includeFrames`（默认 `false` 保持兼容）：遍历 `page.frames()`，
   把每个 frame 的元素也纳入快照，**索引统一编号，每条带 `frameIndex` 字段**；
   快照文本里用分隔行标出 frame 边界（例如 `--- frame[1] https://exmail.qq.com/... ---`）。

3. 给索引类命令（`click_element_by_index` / `input_text` / `get_element_text` / …）加可选
   `frame` 参数；不传时按索引所在的 frame 自动路由（因为索引里已经带了 frame 信息）。

**关键实现注意**：`PlaywrightService.java:5710` 的 `getInteractiveMap` 用的是
`document.evaluate(xpath, document, ...)`。**在 frame 里必须改成相对该 frame 的 document 求值**
（`frame.evaluate` 里的 `document` 就是该 frame 的文档，所以只要把 evaluate 的目标换成 frame 即可）。
xpath 本身也要按 frame 各自生成。

**验收**：企业微信 → 协作 → 邮件，`get_browser_state` 能读到控制台的「概况 / 邮箱域名 / 邮箱账号」导航项。

---

### B2 给 `upload_file` 加回读校验 ⭐️ 高价值

**为什么**：P2 是最隐蔽、最耗时的坑，而且**「报成功但没生效」比报错危险得多**——
它会让人去错的方向排查。

**改法**（`PlaywrightService.java:2866` 之后）：

```java
locator.setInputFiles(file, opts);
// 1. 回读 input 的实际状态
Kv probe = (Kv) inst.page.evaluate(
    "(sel) => { const el = document.querySelector(sel); if (!el) return null;"
  + " return { filesLength: el.files ? el.files.length : null,"
  + "          value: el.value || '',"
  + "          vue2: !!(el._vei && Object.keys(el._vei).length),"
  + "          react: !!(el.__reactProps$ || el.__reactFiber$),"
  + "          inline: !!el.onchange,"
  + "          disabled: !!el.disabled }; }", selector);
```

回执里加：

| 字段 | 含义 |
| --- | --- |
| `data.filesLength` | input 里现在有几个文件（应为 1） |
| `data.listeners` | `{vue2, react, inline}` —— **全为 false 就是「这个 input 没人监听」** |
| `data.consumed` | 启发式结论：`unknown` / `listened` / `noListener` |
| `data.hint` | `noListener` 时给一句可操作的提示 |

**提示语建议**（直接可操作，别只说「可能有问题」）：

> 这个 file input 没有任何事件监听器（Vue2/React/内联都为 false），文件已放进 input，
> 但页面很可能不会处理它。请改用组件方法直调，或参考技能手册里「Vue 组件直调」一节。

**可选增强**：传 `verify: true` 时，上传前后各取一次 `get_form_state` 对比，把 diff 一起返回。

---

### B3 新增 `get_element_listeners`（或把监听器信息塞进 `get_interactive_map`）⭐️ 高价值

**为什么**：如果这次有这条命令，P2 的排查时间能从「很久」压到「一眼」。
「这个元素到底有没有挂事件」是 SPA 自动化的**基础诊断信息**，而现在只能靠手写 `execute_js` 摸。

**接口**：

```json
{"method":"get_element_listeners","params":{"selector":"input.uploadInput"}}
```

返回：

```json
{"found": true, "tag": "INPUT", "className": "uploadInput",
 "vue2": false, "vue3": null, "react": null, "inline": null,
 "listeners": [],
 "note": "没有任何事件监听器：JS 派发的 change/click 到不了框架的 handler，需要直调组件方法"}
```

**更省事的做法**：直接在 `get_interactive_map` 的每条元素上加一个 `hasListeners` 布尔值
（成本很低：一次 `evaluate` 里对每个元素顺手取一下 `el._vei` / `el.onchange`）。
这样「哪些元素是死的」一眼可见。

**实现要点**：Vue 2 用 `el._vei`；Vue 3 用 `el._vei` 不适用（invoker 存在 WeakMap 里），
所以 Vue 3 只能靠「有 `__vueParentComponent` 之类的挂载痕迹」或干脆报 `null`（未知）。
React 用 `el.__reactProps$` / `el.__reactFiber$` 的键名探测。**未知就报 `null`，不要瞎猜成 false。**

---

### B4 `get_modals` 加通用浮层兜底

**为什么**：P4 的假阴性会让调用方以为「没弹窗」，然后继续点被挡住的元素。

**改法**：在现有框架选择器之后，追加一轮**启发式扫描**：

```js
// 可见 + 面积够大 + (position:fixed 或 z-index 高于阈值)
const heuristic = [...document.querySelectorAll('div,section,aside')].filter(el => {
  const s = getComputedStyle(el);
  if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
  const r = el.getBoundingClientRect();
  if (r.width < 150 || r.height < 60) return false;
  const z = parseInt(s.zIndex);
  return s.position === 'fixed' || (!isNaN(z) && z > 50);
});
```

要点：

1. **每条 modal 标注命中来源** `matchedBy: "selector:.ant-modal-wrap"` 或 `"heuristic:fixed-overlay"`，
   让调用方知道这个结果的置信度。
2. 去重逻辑要复用现有的 `seen` 数组（`PlaywrightService.java:5018`）——启发式扫描会大量命中
   已被框架选择器收过的元素及其祖先。
3. `close_modal` 支持按 **class 子串**匹配（`which: "class:.mall_invoice_dialog_container"`），
   因为很多站点的弹窗没有标题、也没有 `role=dialog`。

---

### B5 错误信息带「下一步」

**为什么**：P3 / P5 的共性是「错误信息描述现象，但不指向原因，也不给动作」。
主技能第 2 条已经证明团队会做这件事（方法名拼错给近似建议），把这个思路推广到运行期错误即可。

| 错误 | 现在 | 建议追加 |
| --- | --- | --- |
| 索引失效 | `元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引` | 附上快照的 `seq` 与年龄：「当前索引来自快照 seq=42（12.3 秒前），页面已发生 3 次 DOM 变更」 |
| 索引越界 | `索引越界: 44` | 附当前快照的元素总数与范围：「当前快照共 38 个可交互元素（0–37）」 |
| 实例不存在 | `没有找到对应的浏览器实例：2002` | 附服务启动时间：「服务于 2026-09-24 12:09 启动，此前创建的任务在重启后失效，请重新 start」 |
| 点击无变化 | `changed:false` | 附「本次点击命中的元素」`tag`/`class`/`text`（`click_element_by_text` 已经这么做了，把它推广到按索引点击） |

---

### B6 `start` 回执提示 profile 的登录态状况

**为什么**：P6 —— `engineHonored:true` 不说明「这份 profile 里有登录态」。

**改法**：`start` 回执加：

| 字段 | 含义 |
| --- | --- |
| `data.profileNote` | 例如「该 profile 目录本次是首次创建，任何站点都需要重新登录」/「引擎从 chrome 切到 firefox，登录态不通用，需要重新登录」 |
| `data.profileSeenBefore` | 布尔值（profile 目录里是否已有非空的引擎数据目录） |

实现上只要看 profile 目录里有没有对应引擎的数据目录、以及它的创建时间即可，成本很低。

---

### B7 人机协同的补强

`request_human_input` 解决了「谁来做」的问题，但还有三个缺口：

1. **读不了图的模型仍然用不上**：现在只回 `imageBase64`。建议同时回
   `data.imagePath` + `data.url`（HTTP 可取），并新增 `mode: "ocr"` ——
   用本地 OCR 直接把图上的文字返回（Windows `Windows.Media.Ocr` 支持 `zh-Hans-CN`，
   已在 cnipa skill 里被证明可用于读中文公告图）。这样「验证码是什么」这类问题，
   读不了图的模型也能自己答一部分。
2. **多步人机协同要来回多次**：扫码 + 输码 + 支付确认是**一串**动作，现在每次都要单独
   `request_human_input` + `get_human_input`。建议支持**一次请求带多个待办**
   （`steps: [{prompt, selector}, ...]`），人一次性做完，减少往返与超时。
3. **超时语义**：现在默认 300 秒。二维码这类**有短时效**的场景，建议允许调用方指定
   `expiresAt` 并把「二维码已过期」这件事显式回给调用方，而不是让人干等。

---

### B8 把「一次性 token / 跨域控制台」沉淀成官方配方

**为什么**：P1 的绕过方式虽然是 workaround，但**它是这一类站点的通用解法**，
而且企业微信的邮件 / 微盘 / 文档 / 会议四个应用全是这个形态。放进 `recipes/` 比留在某一个任务 skill 里好。

建议：

1. `recipes/` 里加一个 `wework-qykit-open-console.json` 形态的配方（或写进文档），
   把「同源 XHR 取 token → 顶层直连」固化成可复用步骤；
2. 文档里加一节「主站套第三方控制台」的通用套路：
   ① 从 `iframe.src` 反查第三方 URL 与参数；
   ② 找主站发 token / 换登录态的接口（在 `get_requests` 里按 `token` / `oauth` / `qykit` 过滤）；
   ③ 用 `execute_js` 同源调它；
   ④ 用新 token 顶层打开，之后当普通页面处理。
3. **同时说明「token 是一次性的」这件事**：直接打开 iframe 的 `src` 会 500，
   而 `location.hash` 同文档跳转不会重新请求，所以**进了控制台之后可以用改 hash 的方式跳页**。
   这个区别很容易踩。

---

### B9 `viewportExpansion` 的可用性问题（文档问题，不是代码问题）

源码里 `get_browser_state` **已经支持 `viewportExpansion`**（`PlaywrightService.java:1530`，
文档在主技能第 239 行），`data.pixels_above` / `data.pixels_below` 也会告诉你还差多少没进快照。

**但我这次完全没用上它**，而是退回去手写 JS 扫 DOM、用选择器硬找元素。原因有两个：

1. 它埋在「`get_browser_state` 参数表」的一行里，**症状导向的检索找不到它**——
   我当时的搜索词是「视口」「滚动」「元素不在快照里」，而不是「get_browser_state 参数」。
2. 主技能第 3 条说「页面变化后索引全部重算」，第 16 条说「不可见元素不进快照」，
   但**没有一条把「元素在视口外所以没索引」和「用 `viewportExpansion` 解决」连起来**。

**建议**：在「坑与限制」里补一条症状导向的条目：

> **快照里找不到明明在页面上的元素**：先看 `data.pixels_above` / `data.pixels_below` 是不是非 0
> —— 非 0 就说明元素在视口外，**没有索引**。三条解法按优先级：
> ① `get_browser_state` 传 `viewportExpansion`（例如 `1500`）一次拿到首屏之外的元素；
> ② 改用 `click_element_by_selector` / `input_text_by_selector`（不依赖索引）；
> ③ 滚动到目标位置后重新取快照。

---

### B10 文档可发现性：加一张「症状 → 命令」索引表

**为什么**：本次最尴尬的一件事是——**P9 的答案文档里本来就有，我没找到**。
主技能 1022 行、信息密度很高，但有大量「参数表里的一行」承载着关键能力
（`viewportExpansion`、`get_interactive_map`、`diff_dom_text`、`get_form_state`、
`get_element_count`、`hover_and_click`、`clear_text`…）。

**建议**：在开头（「省 token 铁律」之后）加一张**症状导向**的索引表：

| 我遇到的情况 | 用哪个命令 / 参数 |
| --- | --- |
| 快照里找不到页面上的元素 | `viewportExpansion`，或改用选择器定位 |
| 点了没反应，但返回 ok | 看 `data.changed`；用 `click_element_by_text` 看命中的元素 |
| 不确定页面到底动没动 | `diff_dom_text` |
| 表单填了但提交说为空 | 看 `data.mode` / `data.committed`，改用 `input_text_by_selector` |
| 有弹窗挡住点击 | `get_modals` + `close_modal`（注意它可能漏检自定义类名） |
| 上传了但页面没反应 | 检查该 input 有没有事件监听器（`el._vei`） |
| 需要人扫码 / 输验证码 | `request_human_input` |
| 长批次怕 HTTP 超时 | `commands` 加 `async` + `get_job` |
| 索引老是失效 | 「一次快照只做一个动作」，或全程用选择器 |
| 元素在 iframe 里 | 目前不支持（见 P1）——先看 `list_methods` 有没有新的 frame 命令 |

这张表的信息量不大，但**能把「翻 1000 行找答案」变成「查一行表」**。

---

## 三、我自己的操作失误（诚实记录，供文档参考）

1. **没用 `viewportExpansion`**：见 B9。文档里有，我没找到。
2. **没用 `request_human_input`**：这次是旧构建，没有这个命令；但我在知道有它之后，
   在 skill 里明确写了应该用它，而不是自己在聊天里贴截图路径。
3. **一开始 `curl.exe` 拼 JSON 出错**（Windows 下引号被吃掉）：
   后来改用 `Invoke-RestMethod` + UTF8 字节。**这正说明 `scripts/client/dsb.py` 这类客户端值得优先推荐**——
   我在文档里已改为优先推荐 `dsb.py`。
4. **在按钮上耗时间**：DNSPod 的「添加记录」、邮箱控制台的「开通」，都是「点了返回 ok 但页面不动」。
   正确做法是**早点去看路由 / 接口**，而不是反复换选择器点。这一条我写进了两个 skill。
5. **任务 2002 随服务重启消失后，我第一反应是怀疑 id 用错了**，而不是查 `list_tasks`。见 B5。

---

## 四、附：本次新增的四个任务 skill

| skill | 覆盖 |
| --- | --- |
| `skills/wecom-register-certify` | 注册企业微信 → 企业认证表单 → 营业执照上传（Vue 组件直调）→ 短信/扫脸 → 支付 ¥300 → 查审核 |
| `skills/wecom-profile-complete` | 企业 logo / 地址 / 简称 / 企业域名绑定 / 联系电话 / 企业名片 |
| `skills/wecom-mail-domain` | DNSPod 加 MX（含微信 MFA）→ **破解跨域 iframe 进 exmail 控制台** → 绑定域名 → 成员邮箱自动开通 → 回企业微信绑企业域名 |
| `skills/wecom-invoice-contract` | 订单发票（含普票 vs 专票的纳税人判断）→ 合同申请 → 电子签章四步（授权书盖章是交接点） |
