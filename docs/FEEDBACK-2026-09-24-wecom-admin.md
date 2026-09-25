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

### B11 `SkillDocConsistencyTest` 对站点 skill 有系统性误报

**这是本次写 skill 时踩到的、很具体的一个工程问题。**

`SkillDocConsistencyTest.everySkillDocOnlyMentionsRealCommands()`（`docs/SkillDocConsistencyTest.java:149`）
会遍历 `skills/` 下**每一份** SKILL.md，用这个正则把反引号里的内容当命令名抓出来：

```java
Pattern DOC_COMMAND = Pattern.compile("`([a-z][a-z0-9_]{2,})`");
```

只要抓出来的名字**含下划线**、且不在 `NOT_COMMANDS` 里、又不在命令表中，就判定失败。

**问题**：站点操作手册**必然**会大量提到 snake_case 的**非命令标识符**——
Vue 表单字段、CSS 类名、HTML id、URL 查询参数、接口响应字段。
本次四个新 skill 第一次检查就命中 **22 处**：

| 类别 | 例子 | 数量 |
| --- | --- | --- |
| Vue 表单字段 | `business_license_stuff`、`subject_name`、`socialcredit_code`、`mp_operator_phone`、`verify_result` | 6 |
| CSS 类名 | `mall_invoice_dialog_container`、`qui_dialog`、`ww_dialog`、`form_err` | 4 |
| HTML id | `dsh_up_0`、`dsh_up_1` | 2 |
| URL 查询参数 | `order_id` | 1 |
| 接口响应字段 | `has_cname_record` | 1 |
| **「这些方法不存在」的反例** | `get_frames`、`list_frames`、`switch_frame`、`execute_js_in_frame` | 4 |
| 其它 | `wwmng_authcode` | 1 |

**最讽刺的一类**：文档里**明确写「这些方法不存在」**时（`get_frames()` 全返回「不支持的方法」），
测试反而会因为它「不在命令表里」而报错——**把「记录一个已知缺失的能力」判成了错误**。

**我这次的临时解法**（不改测试）：把这些标识符改成**不像命令名**的写法，各自还更贴合语境：

| 类别 | 改法 | 例 |
| --- | --- | --- |
| CSS 类名 | 加 CSS 点号 | `` `.mall_invoice_dialog_container` `` |
| HTML id | 加 `#` | `` `#dsh_up_0` `` |
| Vue/接口字段 | 加所属对象前缀 | `` `formData.subject_name` `` |
| URL 参数 | 带上 `?` 与 `=` | `` `?order_id=` `` |
| 「不存在的方法」 | 加调用括号 | `` `get_frames()` `` |

改完 22 处全消，`mvn test -Dtest=SkillDocConsistencyTest` **6 项全绿**。

**但这是「让文档迁就测试」，不是好设计。** 每加一个站点 skill 都要重复一遍这个动作，
而且「CSS 类名必须加点号、字段必须加前缀」这条规则**没有任何地方写明**——
下一个人写站点 skill 时还会踩，然后在 CI 上看到一个莫名其妙的失败。

**建议（三选一，推荐第一个）**：

1. **给文档一个显式的「非命令」标记**，让作者自己声明，而不是靠词形猜。例如：
   - 用**双反引号**包住非命令标识符（`` ``subject_name`` ``），测试只检查单反引号；或
   - 支持一个 frontmatter 字段 `nonCommands: [subject_name, dsh_up_0, ...]`，测试把它并入 `NOT_COMMANDS`。
   - 推荐前者：**零配置、词法可判、不需要维护列表**，而且视觉上也能区分「这是命令」与「这是页面里的名字」。
2. **放宽启发式**：只检查「出现在动词/调用语境里」的反引号名字（例如后面紧跟 `(`、或前面有
   `调用`/`方法`/`命令` 字样）。误报会少很多，但规则变复杂。
3. **保留现状，但把规则写进文档**：在 `skills/` 下加一个 `README.md` 或 `CONTRIBUTING`，
   明确写「站点 skill 里非命令的 snake_case 标识符不要用单反引号」，并给出上面那张对照表。
   成本最低，但靠人自觉。

**顺带一个建议**：这个测试目前只覆盖 `skills/`。`recipes/` 里的 JSON 也会写命令名，
建议同样过一遍（`RecipeStore` 已经有加载校验，可以复用）。

#### 已解决（2026-09-25 复核）

上面建议的第 1 条**已经落地**：非命令标识符改用双反引号（` ``subject_name`` `），
`SkillDocConsistencyTest` 也相应扩展（6 项 → **9 项，全绿**）。

> **给后来的自己提个醒**：我事后用一段临时脚本复现这个检查时**报了 22 处假阳性**——
> 因为朴素的 `` `([a-z][a-z0-9_]{2,})` `` 正则会命中双反引号 span **内部**那一段
> （`` ``abc_def`` `` 里就含一个 `` `abc_def` `` 子串）。
> **别用一个自己手写的正则去近似这个守卫**：要么跑真正的 `mvn test -Dtest=SkillDocConsistencyTest`，
> 要么照着测试的实现一起处理双反引号。文档改完自测一次，比事后 debug 便宜得多。

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

---

## 五、追加：新构建（116 方法）实测反馈（2026-09-25）

上面 B 段提的建议大部分**已经实现**了，本节是拿真实任务跑一遍之后的实测反馈。新命令都用上了，整体很好用；
下面是几个**误报 / 摩擦点**，都是「功能是对的、但回执把人带偏」这一类，修起来都不大。

### F1 `upload_file` 的 `consumed` 诊断会误报 `unknown`（B2 的后续）

实测上传盖章版授权书（664 KB PDF）到 `input[type=file][accept=".pdf"]`：

```json
{"consumed":"unknown","readbackError":"Timeout 30000ms exceeded.","mode":"native",
 "changeStatus":"observed","changed":true,"filename":"…盖章版.pdf","size":"664486"}
```

**但上传其实是成功的**——页面上 `.file_text` 里立刻出现了文件名，无任何报错。

问题在回读：那个 file input 是 `0×0`、`opacity:0` 的隐藏元素（这是 SPA 上传框的常态），
**回读很可能在做可操作性等待，于是吃满 30 秒超时**，最后只能降级成 `consumed: "unknown"`。
更关键的是：**`changeStatus: "observed"` 已经说明页面变了**，但 `consumed` 没把它当证据。

**建议**：

1. **回读不要走可操作性检查**：读 `input.files.length` 应该直接 `page.evaluate` 取属性，
   不要用 locator 的 actionability 路径。零成本，且不会超时。
2. **超时要短**：回读是「顺手确认」，2 秒足够；30 秒会把一次成功操作拖成半分钟的等待。
3. **让 `consumed` 用上已有证据**：`changeStatus == "observed"` + 页面出现文件名 → 至少
   报 `listened`，而不是 `unknown`。可以让 `consumed` 的取值带上依据，例如
   `{"consumed":"listened","because":"changeStatus=observed"}`。
4. **降级时别只丢一个 `readbackError`**：读者看到 `Timeout 30000ms exceeded` 的第一反应是
   「上传失败了」，会去重试——**重试一次就可能传两份**。建议明确写一句
   「回读失败不代表上传失败，请以页面为准（见 `data.changed`）」。

> 顺带：`get_element_listeners` 在这条路径上**帮了大忙**——它报 `detection: "cdp"`、
> `listeners: ["change"]`、`hasListeners: true`，让我**上传前**就确认「这个框有人监听，值得传」。
> 对比上次认证页那个 `uploadInput`（一个监听器都没有），这条命令的价值非常直观。

### F2 `mouse_click` 回了失败，但点击其实生效了

实测在 Chrome 内置 PDF 查看器工具栏上点「下载」：

```json
{"ok":false,"errorCode":"ACTION_FAILED",
 "msg":"mouse_click 失败：Object doesn't exist: artifact@07568ad4944aebe5374e63114d781972"}
```

**而下载文件确实落到了下载目录**（436 KB，秒级）。

**同一个毛病后来又出现一次，而且换了命令**——在合同签署完成页点「下载合同」：

```json
{"ok":false,
 "msg":"click_element_by_selector 失败：Object doesn't exist: response@639ac11cda57bf619171632012ba13e1"}
```

**下载同样成功**（613 KB 已签署合同落盘）。

所以这不只是 `mouse_click` 的问题，也不只是 `artifact@`：**只要动作触发了下载，后续的取证环节
（截图 `artifact@…` 或响应 `response@…`）就会拿不到对象，并把整个命令判成失败**。
触发下载的按钮在企业微信这类后台里很常见（下载合同、下载发票、导出报表），所以踩中的概率不低。

看起来是「点击后的取证环节（截图/artifact）拿不到对象」被当成了整个命令失败。
这和 B5 是同一类问题：**动作成功了，但回执说失败**——调用方会重试，而重试的代价可能是重复下载/重复提交。

**建议**：把「动作」与「取证」分开报告。动作成功、只是截图失败时，应该
`ok: true` + `data.captureError: "…"`，而不是整体失败。至少也要在 `msg` 里写明
「点击可能已生效，请以页面状态为准」。

### F3 `switch_tab` 的参数名是 `pageIndex`，不是 `index`

`{"method":"switch_tab","params":{"index":1}}` →
`switch_tab 失败：缺少参数 pageIndex`。

而 `get_tabs` 回执里每个页签的字段就叫 `index`。**同一份回执里的字段名不能直接拿来当参数名**，
这一条很容易踩（我踩了）。主技能里的「方法名写错会给近似建议」很好用，
**参数名建议同样给建议**：`缺少参数 pageIndex（页面状态里的字段名是 index，注意别混）`。

### F4 `request_human_input` 不方便指向 iframe 里的元素

企业微信登录页的二维码在 iframe 里（`img.js_qrcode_img`）。实测：

```json
{"method":"request_human_input","params":{"prompt":"…","selector":"img.js_qrcode_img"}}
```

回执 `ok: true`，但 `imageUrl` / `imagePath` / `imageBase64` **全为空**——因为
`request_human_input` 没有 `frame` 参数，selector 只在顶层文档找。

同期的 `ocr_image` **已经有 `frame` 参数**了，`upload_file` 也有（文档第 619 行），
所以这里像是**漏了一个**。建议给 `request_human_input`（以及 `steps[]` 里的每一项）
补上 `frame`，让「把人叫来看 iframe 里的验证码/二维码」这件事能一次做完。

> 这次的绕法：因为登录页与二维码 iframe **同源**，我用 `execute_js` 穿过
> `iframe.contentDocument` 拿到二维码 URL；但跨域 iframe 就没这条路了，
> 而跨域恰恰是最需要人看图的场景。

### F5 一处很好用的设计（正面反馈）

`get_browser_state` 的 `includeFrames: true` + `data.frameHint` 组合得很到位：
顶层元素为空时，回执直接提示「该用 includeFrames / list_frames」——
**这正是 B10「症状 → 命令」思路落到回执里**，比只写在文档里更有效。
`list_frames` 的 `note` 里也直接写了下一步该用什么，省掉一次翻文档。

另外 `screenshot` 默认不返回 base64（`base64Omitted: true` + 明确的提示语）也是对的，
省 token 这件事在回执层面被贯彻了。

### F6 一条能力边界：Chrome 内置 PDF 查看器里的内容，DOM 与 JS 都够不着

企业微信的合同预览页 `<body>` 里只有一个
`<embed type="application/pdf" src="about:blank">`（整页 HTML 仅 344 字节），
合同正文由**浏览器内置 PDF 插件**渲染 —— `document.querySelectorAll` 什么都读不到，
`list_frames` 也是 0 个 frame。

**能用的两条路**（都实测有效）：

1. **`ocr_image` 截图后 OCR**：能读出正文（实测读出了协议标题、双方主体、条款文字），
   但**准确率有限**（「履行」被读成「戔行」、「通过」被读成「i 甬 过」），
   **不能当作「读原文」的手段**，只适合「确认页面上有没有内容 / 大概是什么」。
2. **点查看器工具栏的下载按钮**：需要 `mouse_move`/`mouse_click` 走真实坐标
   （工具栏是插件渲染的，不在 DOM 里，也没有 selector 可用）。下载落盘后拿到的是
   **带文字层的原始 PDF**（实测 17 页、`/Font` 存在、无 `/Image`），再配合本机
   `pdftotext`（MiKTeX 自带）就能拿到**完全准确的原文**。

**建议**：把「浏览器内置 PDF 查看器 / 插件渲染内容」当成一类已知盲区写进「坑与限制」，
并给出上面这条「OCR 定位 → 点下载 → pdftotext 提文本」的套路。
同时值得说明：**这类内容不能靠 DOM 读，`ok:true` 的 JS 探测会给出「页面是空的」这种误导结论**
（我一开始就差点据此判断「预览页没内容」）。

---

### F7 修复记录（2026-09-25）

上面 F1–F4 已经改完并补了回归测试，`mvn test` 全绿。改动落点：

| 问题 | 改了什么 | 落在哪 |
| --- | --- | --- |
| **F1** 上传回读等满 30 秒 + 误报 `unknown` | 回读不再走 `Locator`（它在节点脱离文档后会一直等到默认超时），改成 `frame.evaluate` **现场重新解析 DOM**；顺序改成**先取回执、再回读 input**，让 `changed` 成为回读结论的证据；`elementGone` / `filesLength:0` 各自给出 `readbackNote` 说明；`ListenerProbe` 同样改成现场解析 | `PlaywrightService.readInputState` / `readInputStateViaLocator` / `appendUploadReadback`；`util/ListenerProbe.evaluateTraces` |
| **F2** 动作成功但取证失败被判成失败 | 新增 `actionErrorOrEffect`：动作抛异常时**先取回执**，只对 **`Object doesn't exist`（句柄已失效）这一族**放宽——确实变了或确实发生了下载就回 `ok:true` + `data.warning` + `data.actionError` + `data.downloadsStarted`；同时给 `observeAfter` / `stateProbe` / `attachCapture` 加保护，事后取证再也不会把已成功的动作翻成失败；`ActionService` 的兜底 catch 加了 `ACTION_UNCERTAIN`（不可重试）+「先读状态再决定」的 note，并把参数校验错单独分开 | `PlaywrightService.actionErrorOrEffect` / `observeAfter` / `stateProbe` / `capture` 调用点；`ActionService.execute`/`attachCapture`；`ActionError.ACTION_UNCERTAIN` |
| **F2 的判据** | **下载要能被认出来**：下载不一定改 DOM，所以它单独进状态探针与 `changed` 判定。实现上**刻意不用 `page.onDownload`**（见 F8），改成纯服务端数下载目录里的文件数 | `PlaywrightService.countDownloadFiles` / `downloadsDir`；`stateProbe` / `probeChanged` |
| **F3** `switch_tab` 参数名混淆 | `reqInt`/`reqStr`/`reqDouble` 缺参数时，若调用方传的是一张已知混淆名表里的名字，直接把对应关系写进报错：`缺少参数 pageIndex（你传的是 index：…）`。什么都没传时保持原来的简洁措辞 | `CommandTable.missingParamMessage` / `CONFUSABLE_PARAMS` |
| **F4** `request_human_input` 缺 `frame` | 补上 `frame`（`steps[]` 里每项也各自可带），透传到元素截图；顺带把同类缺口 `get_element_screenshot` 也补上；回执新增 `data.imageTarget`（截图取自哪个 frame / 选择器），跨域 iframe 里截图时这是判断「到底截到没有」的唯一线索 | `CommandTable` 两个 handler；`PlaywrightService.requestHumanInput` / `getElementScreenshot` |

**回归测试**（`BrowserFrictionUpgradeTest` 3 项 + `BrowserFrameUpgradeTest` 1 项）：

- `uploadReadbackSurvivesInputReplacement`：fixture 里让 file input 在 `change` 后**换掉自己**（复刻企业微信
  上传组件的行为），断言回执里**没有** `readbackError`、耗时不是几十秒、页面确实收到了文件；
- `actionErrorIsNotFailureWhenPageChanged`：直接对「抛异常 + 页面已变」这个输入组合断言契约，
  并固定住两个**反面**边界——页面没变必须失败、**超时族异常即使页面变了也必须失败**；
- `switchTabExplainsParamNameConfusion`：传 `index` 要报出对应关系，什么都不传时保持原措辞；
- `humanInputCanTargetElementInsideFrame`：给 `frame` 必须截出图，不给时如实报 `imageError`。

> **一个由回归测试抓出来的坑，值得单独记一笔**：我第一版的放宽条件是「抛异常 + 页面变了 ⇒ 成功」，
> 结果 `BrowserInspectionUpgradeTest#movingElementFallsBackToRealMouse` 立刻失败了——
> 那个用例点的是一个**一直在动**的元素，它会持续改变 DOM 指纹，于是「原生点击超时」被我的新逻辑
> 洗成了「点击成功」。**「页面变了」不等于「我的动作造成的」**，所以最后把放宽收窄到
> `Object doesn't exist` 这一族，并把下载计数作为独立的正向证据。
> 这正是 F2 建议里「把动作与取证分开报告」的边界，写在这里提醒以后别再踩。

### F8 一个既有问题：`BrowserResponseIntegrationTest` 与「上一个浏览器测试类」跑在同一个 JVM 里必挂

追 F2 的过程中撞上了这个，**它不是我这次改出来的，而是一直在那儿**——顺手把它查清楚了，因为它和
F2 是**同一个根因家族**（Playwright 对象已释放 + 事件分发重抛）。

**现象**：全量 `mvn test` 时，

```
repeatedUrlResponsesAreCorrelatedAndTruncationIsExplicit(BrowserResponseIntegrationTest)  Time elapsed: 0.03 sec  <<< ERROR!
com.microsoft.playwright.PlaywrightException: Object doesn't exist: response@41ec633d…
	at com.microsoft.playwright.impl.Connection.getExistingObject(Connection.java:195)
	at com.microsoft.playwright.impl.BrowserContextImpl.handleEvent(BrowserContextImpl.java:777)
	...
	at com.microsoft.playwright.impl.FrameImpl.evaluate(FrameImpl.java:277)
	at BrowserResponseIntegrationTest.repeatedUrlResponsesAreCorrelatedAndTruncationIsExplicit(:275)
```

注意堆栈：异常**不是在哪个 handler 里抛的**，而是在 Playwright 自己的事件分发里抛的，
然后在**下一句毫不相干的 `page.evaluate`** 上重新抛出。所以：

1. **`safely(...)` 这类「把 handler 包起来」的兜底挡不住它** —— 它发生在 handler 之前。
   `attachRequestRecorder` 的每个监听器都套了 `safely`，但对这一族无效。
2. **失败点与真因离得很远**，而且只在「消息泵正在跑的间隙」出现 —— 看起来像随机 flake。

**实测结论（二分过）**：

| 跑法 | 结果 |
| --- | --- |
| `-Dtest=BrowserResponseIntegrationTest` 单独跑 | ✅ 通过 |
| `-Dtest=<任意一个浏览器测试类>,BrowserResponseIntegrationTest` | ❌ **稳定失败** |
| **把本次改动全部 `git stash` 后用原始代码跑同样的组合** | ❌ **照样稳定失败** |

也就是说：**只要它前面还有一个浏览器测试类跑在同一个 JVM 里，两两组合下就必挂**，与本次改动无关。
全量跑则是**时好时坏**（同一份代码我跑过 4 次：2 次挂、2 次过），符合「消息泵竞态」的形态。
同一组合下 `BrowserActionUpgradeTest` 自己的两个用例也会报 `Object doesn't exist: request@…`
（它同样不是我改的文件）。

**建议**（没在这次动它，属于测试基建，改动面比业务代码大）：

- 让每个浏览器测试类在自己的 fork 里跑（surefire `forkCount` + `reuseForks=false`，
  或给这些类单独配一个 execution），**别让多个 `PlaywrightService` 生命周期串在同一个 JVM 里**；
- 或者给 `PlaywrightService` 加一个「测试用重置」把静态 driver / 连接清干净，
  在 `@AfterClass` 里调用；
- 短期最省事的做法：把 `BrowserResponseIntegrationTest` 里那句「靠 `page.evaluate` 去发请求、
  顺便等两次 fetch」改成**不依赖消息泵时机**的写法（例如 `wait_for_response` 或直接 `get_requests` 轮询）。

> **顺带**：`attachListeners` 里每个页签都会注册一批 Page 级监听器。Playwright Java 的 Page 级事件在
> 客户端是挂在**上下文**上再按页过滤的，所以这些监听器会随页签数不断累积。本次给 F2 加下载判定时
> 我原本用了 `page.onDownload`，后来改成**纯服务端数下载目录**（`countDownloadFiles`）——
> 虽然它不是上面这个失败的诱因（已二分排除），但**能少一份上下文级分发面就少一份**，值这个取舍。

### F8 后续（2026-09-25）：定位到具体分支，并在服务端兜住

F8 当时只查清了「对象已释放 + 事件分发重抛」，没查「是哪一条事件」。一次阿里云轻量控制台的真实任务
（XHR 极多）把这一族又踩了一遍，这次有生产现场，于是顺手把它钉死并兜住了。

**现场**：`execute_js` 连续 8 次失败，连 `() => 1` 都失败；`get_form_state`、`get_element_box`、`go_to_url`、
`wait_for_idle`、自动截图跟着一起报，而同一页面上 `get_browser_state` / `get_element_count` 一切正常。
报错对象每次都不一样（`response@…` / `request@…`），**与当次命令毫无关系**。

**定位**：服务端日志（`logs/log.<日期>.log`）里的栈与 F8 一致，只是这次拿到了行号：

```
at Connection.getExistingObject(Connection.java:195)
at BrowserContextImpl.handleEvent(BrowserContextImpl.java:777)   ← 只有 "response" 分支
at Connection.dispatch(Connection.java:295)
at Connection.processOneMessage(Connection.java:214)
at ChannelOwner.runUntil(ChannelOwner.java:136)
at FrameImpl.evaluate(FrameImpl.java:277)
```

- 从 `playwright-1.53.0-sources.jar` 里取出 `BrowserContextImpl.java`，第 777 行正是
  `else if ("response".equals(event))` 里的 `connection.getExistingObject(guid)`；
- 用 `javap -p -c -l` 对比 **1.63.0** 的字节码：`handleEvent` 的 Exception table 只有两段，
  分别护着 `dialog`（偏移 129-134）与 `pageError`（1127-1153）—— **`response` / `request` 分支至今没有兜**。
  结论：**升级 Playwright 修不了这一族**，只能在服务端自己兜（升级还会牵动 Chromium 修订号与 Firefox 的
  已知回归，见第 29 条，代价更大）。

**服务端改动**（不再只是「少一份分发面」）：

| 改动 | 位置 |
| --- | --- |
| 新增错误码 `SPURIOUS_DISPATCH` + `ActionError.isSpuriousDispatch`（认 `Object doesn't exist` / `Cannot find object to call`；`code()` 里最先判它） | `ActionError` |
| 只读 / 幂等 / 覆盖式落盘命令**自动重发**（含首次最多 3 次、间隔 120ms），回执带 `data.spuriousRetry`；动作类**绝不**自动重发，只标码 | `ActionService.SPURIOUS_RETRY_SAFE` / `dispatchWithSpuriousRetry` |
| `execute_js` 默认不重发，调用方用 `retryOnSpurious: true` **显式声明**「这个脚本重发无害」才重发 | `ActionService.retrySafeFor` |
| 自动截图 / `screenshot` / `get_element_screenshot` 内部各重发一次（伪故障下不再丢图） | `PlaywrightService.spuriousRetry` |
| 抛异常那条路也分开报告：只读命令给「可以再发」，动作类仍是 ACTION_UNCERTAIN + `data.spuriousDispatch: true` | `ActionService.execute` 的 catch |

**测试**：`SpuriousDispatchRetryTest`（9 个用例，全部用假 supplier，不碰浏览器，因此不 flake）钉住
「哪些命令会重发、哪些绝不重发」与「非伪故障不重发」。主技能文档同步写了这一条（第十一节第 47 条）。

**实测对比**（同一个阿里云控制台页面、同一批 12 条命令）：

| | 修复前 | 修复后 |
| --- | --- | --- |
| `execute_js` | 连续 8 次全失败（含 `() => 1`） | 3 轮共 12 次全成功，其中 1 次是靠 `retryOnSpurious` 自动重发拿到结果（`retried=2`） |
| `get_form_state` / `get_element_box` / `go_to_url` / 自动截图 | 同样中招，成串失败 | 全部正常；剩余失败只有真实原因（快照过期导致索引越界、`wait_for_idle` 在 71 个在途请求下超时） |

**仍然遗留**：上游那个竞态本身还在（事件还在下发、对象已被释放），服务端做的是「把投递错的异常认出来并重发」，
不是消除竞态；F8 里给测试基建的建议（`forkCount` + `reuseForks=false` 或加测试用重置）也仍未做。



