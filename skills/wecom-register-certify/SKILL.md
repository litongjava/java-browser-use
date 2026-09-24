---
name: wecom-register-certify
description: 用 deepseek-browser-use 把「注册企业微信 + 提交企业认证 + 支付 ¥300 + 查审核结果」这条线走完的实操手册：有头启动浏览器（认证要法人扫脸、短信码、微信支付，必须 headless:false），登录交给人，然后从「我的企业 → 企业信息 → 前往认证」进入 Vue 单页表单，按「主体信息 → 营业执照 → 法人扫脸 → 确认名称」逐项填写，随时读 Vue 实例 MainValidate.formData 核对字段，最后停在提交前由人完成扫脸、短信验证码与微信支付。重点记录本站点两个最深的坑：一是营业执照用的 ImageUploader 组件其 file input 没有挂 Vue 事件监听器（el._vei 为 null），upload_file 把 files 设好、change 也派发了，页面却永远停在 image_uploader form_err + 「请上传工商营业执照」，必须直接调组件方法 vm.upload({files}) 再复刻 vm.list.push + vm.emitChange 把 filekey 写回表单；二是页面同时挂着两个上传输入框，选错会把营业执照传进「补充证明材料」。另附该后台的通用坑（内容区是内层滚动容器、window.scrollTo 无效、索引易失效要改用选择器、企业微信自有弹窗类名不被 get_modals 命中、hash 路由可直接跳）与提交后的审核结果查法、发票与合同入口。文内数据全部脱敏，替换占位符即可复用。
whenToUse: 需要在企业微信管理后台（work.weixin.qq.com/wework_admin）注册或开通企业、提交或续办企业微信认证（¥300）、上传营业执照、走法人扫脸与短信验证、完成微信支付，或需要查认证审核结果、申请发票与合同时；也适用于「Vue 组件自带上传逻辑、原生 file input 被架空」这类站点的上传排障。
---

# 企业微信：注册 + 企业认证 + 支付 ¥300 + 查审核（实操手册）

一句话流程：**有头开浏览器 → 人登录 → 进认证表单 → 逐步填写 → 上传营业执照（组件直调）→ 交人扫脸/短信/支付 → 查审核结果**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录，不是企业微信的产品说明书。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这个后台里「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：把认证表单填到「确认并提交」之前即停。**提交、扫脸、短信码、支付由人做**，智能体不要代点支付。
- **必须由人做的四件事**（智能体一律做不到，别硬试）：
  1. **法人扫脸**：弹出二维码，必须由**法定代表人本人**用微信扫码并完成人脸识别；
  2. **短信验证码**：发到**管理员手机号**上，智能体读不到那条短信；
  3. **支付 ¥300**：微信支付扫码/确认，涉及资金，只能由人操作；
  4. **最终确认**：提交前的确认对话框，属于法律性质的申报确认。
- **不做的事**：不绕过人脸识别、不伪造或美化营业执照、不用不实的工商信息、不代付、不代替法人签署任何承诺。
- **要提醒用户的事**：
  - 认证提交的是**工商登记信息 + 法人身份信息**，必须与营业执照完全一致（企业全称、18 位统一社会信用代码一个字都不能错）；
  - 认证费 ¥300 **生效一年**，到期要续；
  - 认证审核由**第三方审核机构**执行（具体是哪一家因订单而异，订单详情页的「审核机构」一栏会写明，
    并附咨询电话 / 邮箱 / 在线客服微信号 / 咨询时间），企业微信只是通道；审核中可能被退回补充材料。

## 1. 开工前

### 1.1 服务与浏览器

```json
POST http://localhost:10049/playwright/command
{"id": 1001, "method": "start", "params": {"headless": false, "browser": "chrome"}}
```

- **`headless: false` 是硬要求**：登录要人扫码、认证要人扫脸、短信码要人念、支付要人扫码——无头实例没法让人操作。
  主技能第 14 条也提到有头只适合本机调试，但**这个站点恰恰就是那个例外**。
- `browser` 不用特殊指定，`chrome` / 默认 `auto` 都可以（这个后台不挑引擎，没有商标网那种开发者工具检测）。
  但记住主技能第二节的两条：**换浏览器/换引擎等于换一套 profile 与登录态**（`edge` 用它自己那份，`firefox` 格式不通用），
  所以**别在中途换**，不然要重新登录一次。
- 开工前先 `GET /playwright/health` 确认服务在，再 `start`。
- `start` 的回执里重点看 `data.browser.profileDir`（登录态就养在这份 profile 里，长期有效）与
  `data.browser.userProfile`（`false` 说明用的是托管 profile，不是用户日常那份）。

### 1.2 开工前必须问清的字段（一次问全，别填到一半停下来）

| 组 | 字段 | 说明 |
| --- | --- | --- |
| 主体 | `<企业全称>`、`<18 位统一社会信用代码>` | 必须与营业执照逐字一致；信用代码 18 位 |
| 法人 | `<法人姓名>`、`<法人身份证号>` | 扫脸必须是**这个人本人** |
| 管理员 | `<管理员手机号>`、`<管理员邮箱>` | 手机号用来收短信码；邮箱用于接收通知 |
| 材料 | **营业执照扫描件/照片** | 见第 4 节，上传是整个流程最容易翻车的一步 |
| 可选 | 企业简称（如 `<企业简称>`）、企业 logo、企业地址、联系电话 | 简称有命名规则，见 3.4 |

> **先确认「企业是否已经存在」再动手**：企业微信一个手机号/微信号可能已经开通过企业。实测踩过——以为要注册，
> 结果企业早就建好了，白走一遍注册流程。判据：打开后台如果直接进到管理首页（左侧有「我的企业 / 通讯录 / 协作」），
> 说明**已登录且有企业**，直接从第 3 节开始，不要重新注册。

### 1.3 留档（排查用）

- 服务端：`logs/trace/<yyyyMMdd>/`（`steps.log` 时间线、`calls.jsonl` 逐条 JSON、
  `NNNNNN-<任务id>-<方法>.json` 完整请求响应、`uploads.log` 上传记录）。
- 页面留档：`data/<id>/<seq>.png`（截图）与 `data/<id>/<seq>.txt`（同刻的页签 + 可交互结构化文本）。
  **事后复看某一步**直接 GET `http://localhost:10049/data/<id>/<seq>.txt`，比重新跑一遍便宜得多。
- 客户端：`python client/dsb.py`（跨平台、退出码区分传输错/业务失败/用法错、默认脱敏），
  写进脚本时用它，不要手拼 `-d '...'`（带中文和引号的 JSON 在 PowerShell 里很容易被吃掉引号，实测 `curl.exe` 会
  返回 `请求体不是合法 JSON：not allow unquoted fieldName`）。
- **两份日志都默认脱敏但不会自动清理**：手机号、18 位身份证号/统一社会信用代码、邮箱、长数字会被掩成 `***`，
  但**姓名、地址、门牌号掩不掉**。任务结束用 `cleanup`（默认只预演，`dryRun:false` 才真删）或手动清。
- 上传的营业执照落在服务端暂存目录（`start` 回执里的 `data.browser.upload.dir`），**同样不会自动清理**；
  不需要了用 `DELETE /playwright/upload?name=<文件名>` 删掉——**营业执照是敏感材料，交付前务必提醒用户清理**。

### 1.4 这个后台的结构特点（先记住，后面全靠它）

- 后台是**单页应用，hash 路由**：`https://work.weixin.qq.com/wework_admin/frame#<路由>`。
  **能直接 `go_to_url` 到已知路由**，不用一层层点菜单（比点菜单稳得多，见 8.5）。
- 常用路由（实测可用）：

| 页面 | hash 路由 |
| --- | --- |
| 企业信息 | `#profile` |
| 认证中心 / 前往认证 | `#/authCenter/?from=cert_vbutton` |
| 订单详情 | `#/authCenter/orderDetail?order_id=<订单号>` |
| 企业域名 | `#profile/domain` |

- **内容区是内层滚动容器，不是 `window`**：见 8.1，这条不记住会在滚动上白花很多时间。

## 2. 登录与认证入口

### 2.1 登录：交给人

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame"}},
  {"wait":{"seconds":4}}
]}}
```

然后**请人在弹出的 Chrome 窗口里扫码/登录**。正式做法是用人机协同 API，不要像我第一次那样自己截图再在聊天里贴图片地址：

```json
{"id":1001,"method":"request_human_input",
 "params":{"prompt":"请在弹出的浏览器窗口里扫码登录企业微信管理后台","timeoutSeconds":300}}
```

- `request_human_input` 会把当前页签**带到窗口最前**，人一眼就能看到该操作哪一页；传 `selector`/`index`
  还会把该元素截成 `data.imageBase64` 一并返回（二维码场景用它，见 5.2）。
- 登录成功的判据：页面左侧出现「我的企业 / 通讯录 / 协作 / 应用管理 / 安全与管理」这套菜单，
  并且 `#profile` 能读到企业全称。**不要靠 `document.title` 判断**（标题一直是「企业微信」）。
- 登录态留在共享 profile 里，**后续任务不用再登**；换 id、换任务都不影响（主技能第九节最后一条）。

### 2.2 进认证入口

两条等价的路，优先第二条：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/?from=cert_vbutton"}},
  {"wait_for_stable":{"quietMs":800,"timeoutSeconds":20}},
  {"execute_js":{"body":"return {url:location.href, head:document.body.innerText.slice(0,400)}"}}
]}}
```

- 或者走界面：**我的企业 → 企业信息 → 右上角「前往认证」**。
- 企业信息页上「前往认证」按钮旁边有一行小字是**当前认证状态**：
  - 未认证：只有「前往认证」；
  - 已认证：`当前认证有效期至 <日期>`（实测认证通过后这里显示到次年同日）。

### 2.3 入口分叉：先判断「现在是哪种状态」，别猜

打开后台会落到下面三种状态之一，**动作完全不同**，先花一次调用判定：

```js
(() => {
  const t = document.body.innerText;
  return {
    url: location.href,
    hasAdminNav: /我的企业/.test(t) && /通讯录/.test(t),   // 已登录且有企业
    hasCertValid: /当前认证有效期至/.test(t),               // 已认证且在有效期内
    needCert: /前往认证/.test(t),
    looksLikeLogin: /扫码登录|微信登录|手机号登录/.test(t),
    looksLikeSignup: /注册|开通|创建企业/.test(t) && !/我的企业/.test(t),
    head: t.slice(0, 300)
  };
})()
```

| 判据 | 状态 | 接下来做什么 |
| --- | --- | --- |
| `looksLikeLogin` | 未登录 | 交人登录（2.1），登录后再判一次 |
| `hasAdminNav && needCert` | **已有企业、未认证** | 直接进 2.2 走认证（**最常见的情况**） |
| `hasAdminNav && hasCertValid` | 已有企业、已认证 | 看有效期；要续办走「重新认证」，否则**任务已无必要**，先问用户 |
| `looksLikeSignup` | 还没有企业 | 先注册/开通企业（见下） |

**「已有企业」这条最容易误判**：实测踩过一次——按「注册」的思路准备了一整套流程，
结果打开后台发现企业**早就建好了**（左侧菜单齐全、企业信息页能读到全称），
白白多绕一圈。所以**判断状态这一步不要省**。

> **关于注册本身**：企业微信的「注册」= 用微信/手机号开通一个企业，填写企业名称、行业类型、员工规模等，
> 之后可以邀请成员。这一步同样需要**人扫码授权**，属于第 0 节「由人做」的范畴。
> 如果判定结果确实落在「还没有企业」，**先把需要人做的扫码授权交出去**，拿到企业上下文之后
> 再按本文第 3 节往下走——认证部分的写法与「已有企业」完全一致，不用改脚本。

## 3. 认证表单填写

### 3.1 步骤总览

| 步 | 名称 | 关键点 |
| --- | --- | --- |
| 1 | 主体信息 | 主体类型、企业全称、统一社会信用代码、管理员姓名/手机/邮箱 |
| 2 | 营业执照 + 法人验证 | 上传营业执照（**最坑，见第 4 节**）、法人扫脸（**人做**） |
| 3 | 确认名称 | 企业简称 + 简称命名依据，最后「确认并提交」 |
| — | 提交后 | 跳转支付（¥300，**人做**） |

### 3.2 先找到 Vue 实例——本站点最好用的诊断手段

认证表单是一个 Vue 组件，实例上挂着完整的 `formData`。**回读字段、判断「值到底进没进模型」，读它最准**：

```js
// 定位认证表单的 Vue 实例（组件名/根节点可能随版本变，按 formData 特征找最稳）
(() => {
  const roots = [document.querySelector('#app'), document.body].filter(Boolean);
  const seen = new Set(); const hits = [];
  const walk = (el) => {
    if (!el || seen.has(el)) return; seen.add(el);
    const vm = el.__vue__;
    if (vm && vm.formData && ('socialcredit_code' in vm.formData || 'subject_name' in vm.formData)) hits.push(vm);
    for (const c of el.children) walk(c);
  };
  roots.forEach(walk);
  const vm = hits[0];
  if (!vm) return { found: false };
  return { found: true, component: (vm.$options && vm.$options.name) || '-', formData: vm.formData };
})()
```

返回的 `formData` 实测长这样（**字段名就是契约，写脚本时照抄**）：

```jsonc
{
  "customer_type": "1",                       // 1 = 企业
  "subject_name": "<企业全称>",
  "socialcredit_code": "<18 位统一社会信用代码>",
  "subject_name_verify_type": "1",            // 1 = 法人扫脸
  "legalperson_face_info": {
    "legalperson_name": "<法人姓名>",
    "legalperson_idcard": "<法人身份证号>",
    "verify_result": 1,                       // 1 = 扫脸通过
    "isAuthLegalShare": 1
  },
  "mp_operator_name": "<管理员姓名>",
  "mp_operator_phone": "<管理员手机号>",
  "mp_operator_email": "<管理员邮箱>",
  "business_license_stuff": ["wwverify_v2_..."]   // 营业执照上传后的 filekey 数组
}
```

- **这是「填完对不对」的权威答案**：DOM 上有值、`formData` 里没有，就是没进模型（主技能第 30 条）。
- `formData.business_license_stuff` 是**数组**，空数组 = 营业执照没上传成功，哪怕页面上看着有图。

### 3.3 主体信息

- 字段一律用 `input_text_by_selector`（可见字段默认走真实输入，`committed=true`）；
  **不要用 `execute_js` 直接改 `input.value`**，那样 Vue 的 model 里是空的（主技能第 30 条）。
- 中文一律不要用 `send_keys`（只认键名，塞中文得到 `Unknown key`）。
- 填完先用 `get_form_state` 对一遍，再回读一次 3.2 的 `formData`：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_form_state":{"includeHidden":true}},
  {"execute_js":{"body":"return {url:location.href, txt:document.body.innerText.slice(0,600)}"}}
]}}
```

- **信用代码最容易错**：18 位，字母数字混排，读一遍确认位数（`get_element_value` 回读后 `.length === 18`）。
- 企业全称必须是**营业执照上的全称**（含「有限公司」这类后缀），简称另填（见 3.4）。

### 3.4 确认名称（第 3 步）

- **企业简称**：填 `<企业简称>`。规则是**基于企业全称里的工商字号**命名，不是随便起。
- **简称命名依据**：是个下拉/选项，实测 `801` = **基于全称中的工商字号命名**。选错会被驳回。
- 简称**一年只能改一次**（改完就消耗掉了），所以确认页要停下来问用户，别自己拍。
- 最后一步按钮是「确认并提交」，**点它之前先做完第 6 节的自检**。

### 3.5 非文本控件的处理顺序（单选 / 下拉 / 勾选）

认证表单里除了输入框，还有单选（主体类型、验证方式）、选项（简称命名依据）、勾选框（协议）。
**按这个顺序试，不要一上来就写 JS**：

1. **先判它是不是原生控件**——是 `<select>` 还是自绘下拉，写法完全不同：

```js
(() => { const out = [];
  for (const e of document.querySelectorAll('select,input[type=radio],input[type=checkbox]')) {
    const r = e.getBoundingClientRect();
    out.push({ tag: e.tagName, type: e.type || '-', id: e.id || '-', name: e.name || '-',
      checked: e.checked === undefined ? null : e.checked, selectedText: e.selectedOptions && e.selectedOptions[0] ? e.selectedOptions[0].text : null,
      y: Math.round(r.top), visible: e.offsetParent !== null });
  }
  return out.slice(0, 20); })()
```

2. **原生 `<select>`** → 用 `get_dropdown_options` 拿选项文本，再 `select_dropdown_option` 按文本选
   （主技能第六节）。**但别假设它一定是原生 select**：本后台大量控件是自绘的，
   `get_dropdown_options` 会拿不到选项，这时走第 3 步。
3. **自绘下拉**（`div`/`span` 当触发器，选项是一堆 `li`/`div`）→ **真实鼠标点开，再点选项文本**，
   分两次调用、中间不要插别的动作（菜单会收起来）：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"click_element_by_selector":{"selector":"<下拉触发器选择器>","mode":"mouse"}},
  {"wait_for_stable":{"quietMs":500,"timeoutSeconds":10}},
  {"click_element_by_text":{"text":"<选项文本>","mode":"mouse"}},
  {"execute_js":{"body":"return {txt:document.body.innerText.slice(-300)}"}}
]}}
```

4. **单选 / 勾选** → 用 `check_element_by_index` 或直接点它的 `<label>`；
   **点完等 1–2 秒再回读**（框架状态更新是异步的，主技能第 25 条），
   用 `is_checked` 或回读 3.2 的 `formData` 交叉验证。
5. **协议类勾选框必须真的勾上**再提交（第 8.7 节）。漏勾的表现是提交按钮点了没反应、
   或者报一句含糊的「请先阅读并同意」。

**判据统一回落到 3.2 的 `formData`**：控件点了、页面上看着选中了，但 `formData` 里没变，
就是没进模型——换成真实鼠标点（`mode:"mouse"`）再试，别继续在 JS 派发上打转。

## 4. 营业执照上传（重头戏）

**这一节是本文最值钱的部分。** 实测在这里卡了很久：`upload_file` 明明返回 `ok:true`、
`input.files` 也设进去了、`change` 事件也派发了，页面却**一直停在 `image_uploader form_err` + 「请上传工商营业执照」**。

### 4.1 根因：组件的 file input 没有挂 Vue 事件监听器

这个上传用的是自研的 `ImageUploader` 组件，它的 `<input class="uploadInput">` **根本没有监听器**，
所以任何 JS 派发的 `change` 事件都不会走到组件的 `upload()` 方法里去——文件"进"了 input，但没人读它。

> **现在不用自己摸 `_vei` 了**：服务端已经有 `get_element_listeners`，而且走的是 CDP 的
> `DOMDebugger.getEventListeners`（浏览器自己报的清单，原生 `addEventListener` 也算），结论比手写
> `execute_js` 探 `_vei` 可信：
>
> ```json
> {"id":2001,"method":"get_element_listeners","params":{"selector":".uploadInput"}}
> ```
> ```json
> {"ok":true,"data":{"found":true,"tag":"INPUT","hasListeners":false,"detection":"cdp",
>   "listeners":[],"note":"这个元素**没有任何事件监听器**(浏览器自己报的监听器清单是空的)…"}}
> ```
>
> `upload_file` 的回执里也会带 `data.consumed`（`listened`/`noListener`/`unknown`）与 `data.hint` ——
> 看到 `noListener` 就**别再重试上传了**，直接跳到 4.2。
>
> 下面这段手写 `_vei` 探针保留下来，是因为它能一次把页面上**所有** file input 挨个探一遍（很实用），
> 而 `get_element_listeners` 一次只探一个元素。

**诊断手法（值得单列，通用）**：Vue 2 把事件回调挂在元素的 `` _vei ``（invoker 表）上。

```js
// 判断这个元素到底有没有监听器：_vei 为 null 就说明「JS 派发事件没用」
(() => {
  const inp = document.querySelector('.uploadInput');
  if (!inp) return { none: true };
  return {
    hasVei: inp._vei !== null,                 // false/null = 没挂监听器
    veiKeys: inp._vei ? Object.keys(inp._vei) : null,
    hasVue: !!inp.__vue__,                      // 元素本身通常也没有 __vue__
    parentVue: inp.parentElement ? !!inp.parentElement.__vue__ : false
  };
})()
```

- **`_vei === null` 是判决书**：这种情况下 `upload_file`（内部就是 `setInputFiles` + 派发事件）
  **永远不可能成功**，再重试一百次也一样。
- 反例：同一页面上 logo 的上传框是个普通 `ww_fileInput`（原生 input + 正常监听），
  用 `upload_file` **一次就成**——**所以「能不能用 upload_file」要按控件分别判断，不要一概而论**。

### 4.2 正解：直接调组件方法，再复刻它的状态写回

两步，**两步都不能少**：

```js
(async () => {
  // ---------- 准备：拿到组件实例 ----------
  const inp = document.querySelector('.uploadInput');
  if (!inp) return { err: 'no uploadInput' };

  // 组件实例一般挂在 input 的某个祖先上，沿父链找带 upload 方法的那个
  let vm = inp.__vue__;
  for (let p = inp.parentElement; !vm && p; p = p.parentElement) {
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  }
  if (!vm) return { err: 'no vue instance with upload()' };

  // ---------- 1) 直接把 files 喂给组件的 upload 方法 ----------
  // 注意：upload_file 已经把文件塞进 input.files 了；这里直接复用
  const f = inp.files;
  if (!f || !f.length) return { err: 'input.files empty — 先用 upload_file 把文件放进去' };

  const filekey = await new Promise((resolve, reject) => {
    vm.upload({ files: f },
      (res) => resolve(res),                 // 成功回调：组件会拿到 filekey
      (err) => reject(err));
  });
  // 组件内部真发 POST /wework_admin/wwAuth/upload_img
  // → 200 {"filekey":"wwverify_v2_...","filename":"<营业执照文件名>"}

  // ---------- 2) 复刻组件自己的状态写回（不做这步，formData 不会更新）----------
  vm.list.push({ key: filekey, loadingIndex: null });
  vm.emitChange();   // 等价于 this.$emit("change", list.filter(x => !x.loadingIndex).map(x => x.key))

  return { filekey, list: vm.list };
})()
```

要点：

1. **`vm.upload({files}, onSuccess, onError)` 的入参形状是 `{files: FileList}`**，不是裸 FileList；
   回调是**两个函数**（成功/失败），不是 Promise——上面用 `await new Promise(...)` 把它包成同步等待。
2. **上传接口**：`POST https://work.weixin.qq.com/wework_admin/wwAuth/upload_img`
   → 200 `{"filekey":"wwverify_v2_<...>","filename":"<文件名>"}`。**`filekey` 才是要进表单的东西**，不是文件名。
3. **`vm.list.push({key, loadingIndex: null})` 里的 `loadingIndex: null` 不能省**：
   `emitChange` 会 `filter(x => !x.loadingIndex)`，留着非 null 的 loading 标记会被过滤掉。
4. **`vm.emitChange()` 是让数据进表单的关键**：它 `$emit("change", [...keys])`，
   父组件（`MainValidate`）收到后才会把值写进 `formData.business_license_stuff`。
   **不做这一步，上传成功了但表单仍是空的**——这是第二个容易漏的点。
5. 有些版本 `vm.list` 可能是别的名字（`fileList` / `items`）。**先回读一次确认**：

```js
(() => { const i = document.querySelector('.uploadInput');
  let vm = i && i.__vue__;
  for (let p = i && i.parentElement; !vm && p; p = p.parentElement)
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  return vm ? { methods: Object.keys(vm.$options.methods || {}),
                dataKeys: Object.keys(vm.$data || {}) } : { err: 'no vm' };
})()
```

### 4.3 怎么验证「真的上传成功了」

三个判据，**按可靠性排序**：

```js
(() => {
  // ① 最可靠：Vue 的 formData 里有非空 filekey
  const i = document.querySelector('.uploadInput');
  let vm = i && i.__vue__;
  for (let p = i && i.parentElement; !vm && p; p = p.parentElement)
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  const keys = vm ? (vm.list || []).map(x => x.key).filter(Boolean) : [];

  // ② 次可靠：组件的根节点类名里不该再有 form_err
  const root = document.querySelector('.image_uploader');
  const cls = root ? String(root.className) : null;

  // ③ 最不可靠：页面上有没有出现文件名
  const pageHasFile = /<营业执照文件名片段>/.test(document.body.innerText);

  return { keys, rootClass: cls, stillErr: /form_err/.test(cls || ''), pageHasFile };
})()
```

- **`stillErr: true` 或 `keys: []` 就是没成**，别因为「页面上看着有图」就往下走。
- 页面出现「请上传工商营业执照」这句红字，等价于 `.form_err` 仍在。

### 4.4 坑：选错上传输入框

页面上**同时挂着多个 `input[type=file]`**，实测的 id 选择器是 `#dsh_up_0` / `#dsh_up_1`：

| id | 用途 |
| --- | --- |
| `#dsh_up_0` | **营业执照**（要的就是这个） |
| `#dsh_up_1` | 补充证明材料（传这里等于白传） |

- 快照里它们都显示成 `<input type=file />`，**看不出区别**。用属性确认：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_browser_state":{}},
  {"get_element_attribute":{"index":30,"name":"id"}}
]}}
```

  实测 `get_element_attribute(index, "id")` 回 `#dsh_up_1` —— 这就是传错了。
- **更稳的做法：根本不要按索引传，直接用选择器**（主技能第七节第 7 条）：
  用 `document.querySelectorAll('input[type=file]')` 结合它在 DOM 里的位置/祖先类名挑出营业执照那个，
  给它一个临时 id 再 `upload_file` 传 `selector`。
- **绝对不要靠「第几个 input」这种顺序假设**：布局一变顺序就变。

### 4.5 坑：目标输入框不在视口里、快照里没有它

- 实测 `#dsh_up_0` 一度在**视口上方**（`y = -192`），于是**它不在快照里、没有索引**，
  按索引怎么都传不进去；直到页面布局变化后才出现索引 `[44]`。
- **原因**：快照只覆盖当前视口，视口外的元素不进快照（主技能第三节的「元素在快照里找不到」）。
- **应对顺序**：
  1. **首选：用 `selector` 而不是 `index`** —— `upload_file` 传 `selector` 不需要元素可见、也不需要索引；
  2. 需要索引时，用 `viewportExpansion` 扩大快照范围，或先把元素滚进视口（滚动见 8.1）；
  3. 确认元素位置用 `execute_js` 读 `getBoundingClientRect()`，比猜快。

```js
(() => { const es = [...document.querySelectorAll('input[type=file]')];
  return es.map((e, i) => { const r = e.getBoundingClientRect();
    return { i, id: e.id, accept: e.accept, cls: String(e.className).slice(0, 30),
             y: Math.round(r.top), visible: e.offsetParent !== null }; }); })()
```

### 4.6 一个完整的「上传营业执照」批次

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"upload_file":{"selector":"#dsh_up_0","path":"<营业执照文件名>"}},
  {"wait":{"seconds":1}},
  {"execute_js":{"body":"(async()=>{const inp=document.querySelector('.uploadInput')||document.querySelector('input[type=file]');let vm=inp&&inp.__vue__;for(let p=inp&&inp.parentElement;!vm&&p;p=p.parentElement){if(p.__vue__&&typeof p.__vue__.upload==='function')vm=p.__vue__;}if(!vm)return{err:'no vm'};const f=inp.files;if(!f||!f.length)return{err:'no files'};const key=await new Promise((res,rej)=>vm.upload({files:f},res,rej));vm.list.push({key:key,loadingIndex:null});vm.emitChange();return{filekey:key};})()"}}
]}}
```

> `upload_file` 的 `path` 是**服务端**能打开的路径。客户端-服务器模式下先把文件 `POST /playwright/upload` 送上去，
> 再用回执里的 `path`/`relativePath`（主技能第四节的「上传文件」）。

### 4.7 上传失败排查决策树（照这个顺序走，别乱试）

一条命令拿到全部判据：

```js
(() => {
  const inputs = [...document.querySelectorAll('input[type=file]')].map((e, i) => {
    const r = e.getBoundingClientRect();
    return { i, id: e.id || '-', accept: e.accept || '-', cls: String(e.className).slice(0, 24),
             fileCount: e.files ? e.files.length : -1, fileName: e.files && e.files[0] ? e.files[0].name : null,
             y: Math.round(r.top), inViewport: r.top >= 0 && r.bottom <= innerHeight,
             hasVei: e._vei !== null, hasVue: !!e.__vue__ };
  });
  // 找带 upload() 的 Vue 实例
  const anchor = document.querySelector('.uploadInput') || document.querySelector('input[type=file]');
  let vm = anchor && anchor.__vue__;
  for (let p = anchor && anchor.parentElement; !vm && p; p = p.parentElement)
    if (p.__vue__ && typeof p.__vue__.upload === 'function') vm = p.__vue__;
  const root = document.querySelector('.image_uploader');
  return {
    inputs,
    vmFound: !!vm,
    vmMethods: vm ? Object.keys(vm.$options.methods || {}).slice(0, 20) : null,
    listKeys: vm ? (vm.list || []).map(x => x.key).filter(Boolean) : null,
    rootClass: root ? String(root.className) : null,
    errText: /请上传工商营业执照/.test(document.body.innerText)
  };
})()
```

| 现象 | 判据 | 动作 |
| --- | --- | --- |
| 页面红字「请上传工商营业执照」不放 | `errText: true` | 继续往下看，别重复点提交 |
| `inputs[].fileCount` 为 0 | 文件根本没进去 | 检查 `upload_file` 的 `selector` 是否指向了**存在且可写**的 file input；先 `POST /playwright/upload` 确认服务端能读到该文件 |
| `fileCount > 0` 但 `listKeys` 为空 | **文件进了 input，组件没读** | 就是 4.1 的根因：`hasVei` 大概率是 `false` → 走 4.2 直调 `vm.upload()` + `emitChange()` |
| `vmFound: false` | 找不到组件实例 | 换找法：`$children` 递归找带 `upload` 的组件；或确认选择器 `.uploadInput` 是否改版（回读 `vmMethods` 看方法名） |
| `listKeys` 非空但表单仍空 | 状态没写回 | 补 `vm.emitChange()`（4.2 第 4 点），或直接回读 `formData.business_license_stuff` 确认 |
| `inputs[]` 里 `inViewport: false`（`y < 0`） | 元素在视口外、**快照里没有索引** | 改用 `selector` 定位（4.5），或 `viewportExpansion` 扩大快照 |
| `fileCount > 0` 但传的是 `#dsh_up_1` | **传错输入框** | 换到营业执照那个（4.4），用 `get_element_attribute(index,"id")` 确认 |

**纪律**：同一个动作失败两次就换判据、换手段，**不要第三次重试同一个调用**。
这个站点上「`upload_file` 反复传同一张图」是最典型的无效重试——因为根因在组件监听器上，重试永远不成功。

## 5. 短信验证码与法人扫脸（都由人做，但流程要写对）

### 5.1 用正式的人机协同 API，别自己截图

主技能第九节那套 `request_human_input` / `submit_human_input` / `get_human_input` 就是为这种场景设计的，
**比我这次的做法（自己 `get_element_screenshot` 存盘 + 在聊天里贴图片 URL）规范得多**：

```json
{"id":1001,"method":"request_human_input",
 "params":{"prompt":"请输入收到的短信验证码","selector":"#sms_code","timeoutSeconds":300}}
```

- 传 `selector`/`index` 指向二维码/验证码图时，服务会把该元素截成 **`data.imageBase64`** 一并返回，
  同时把页签带到窗口最前——**人既能看到浏览器，也能拿到图**。
- 人答复后 `submit_human_input`（`requestId` + `answer`）回填，或直接让他在浏览器里自己填。
- 取答复用 `get_human_input`（可传 `timeoutSeconds` 长轮询）。`data.status` 为 `pending` / `answered` / `expired`。

### 5.2 法人扫脸（二维码）

```json
{"id":1001,"method":"request_human_input",
 "params":{"prompt":"请法定代表人用微信扫描页面上的二维码完成人脸识别","selector":"<二维码元素选择器>","timeoutSeconds":600}}
```

- 二维码元素用 `get_element_screenshot` 单独取也行（走 Playwright 元素截图，不受 canvas 跨域污染限制），
  但**优先用 `request_human_input` 一次把图和提示都给出去**。
- **必须是法定代表人本人**扫脸。实测这一步完成后 `formData.legalperson_face_info.verify_result` 变成 `1`——
  **用这个字段判断扫脸过没过**，不要看页面文案。
- 扫脸超时/失败会重置，重新发起即可；不要让智能体去"重试人脸识别"。

### 5.3 短信验证码会过期——拿到就立刻提交

**实测踩过**：第一次拿到的验证码在提交时报 **「短信验证码过期」**，
重新点「重新发送验证码」拿到新码后**立刻**提交才成功。两次验证码之间的间隔只有几分钟，
所以**不要以为「码还在手上」就慢慢来**。

- 验证码**有时效**（主技能第九节提到税务系统的图验约 120 秒；短信码同样是分钟级），
  所以流程是：`request_human_input` → 人给码 → **`submit_human_input` 之后立刻 `input_text` + 点提交**，
  不要中间插别的动作、不要攒着。
- 过期了就**重新发一次要新码**，**不要拿旧码反复重试**（重试必然失败，还会浪费一轮）。
- 提交后判断结果别只看 `ok:true`：用 `expect` 断言（见 6.2）或回读页面文案。

## 6. 提交前自检与支付

### 6.1 提交前的自检清单（每次都要做）

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_form_state":{"includeHidden":true}},
  {"execute_js":{"body":"return {url:location.href, txt:document.body.innerText.slice(-1200)}"}}
]}}
```

逐条核对：

1. `customer_type === "1"`（企业）；
2. `formData.subject_name` 与营业执照全称逐字一致；
3. `formData.socialcredit_code` 是 18 位且与营业执照一致；
4. `formData.business_license_stuff` **非空**（见 4.3）；
5. `legalperson_face_info.verify_result === 1`（见 5.2）；
6. `formData.mp_operator_phone` / `formData.mp_operator_email` 是用户给的；
7. 第 3 步的**企业简称 + 命名依据**已确认（见 3.4）。

### 6.2 用 `expect` 断言「动作真的生效了」

主技能第七节最值钱的习惯：**回执 `ok:true` 不代表页面真的变了**。提交类动作一定带断言：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"stopOnExpectFailure":true,"commands":[
  {"click_element_by_selector":{"selector":"<提交按钮选择器>","mode":"mouse"},
   "expect":{"js":"document.body.innerText.includes('<提交后的标志文案>')"}},
  {"wait_for_stable":{"quietMs":900,"timeoutSeconds":20}}
]}}
```

- `expectResult.passed === false` 就是「动作发了、状态没变」，当场就能发现，不用等到后面莫名失败。
- 断言脚本写错选择器也会记成 `passed:false`，错误在 `expectResult.error`。

### 6.3 支付：由人做

- 提交后进入**订单/支付页**，是**微信支付扫码**（`支付方式: 微信支付`）。**这一步只能由人完成**，智能体不要代付。
- 交接话术要写清：需要人**用微信扫码支付 ¥300**，并说明「认证生效一年」。
- 支付完成后**不要立刻假设成功**——按第 7 节去订单页回读状态。

### 6.4 支付刚完成时要做的三件事

人扫码付完之后，**立刻**在一个批次里做完这三步，别等：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/orderDetail?order_id=<订单号>"}},
  {"wait_for_stable":{"quietMs":900,"timeoutSeconds":25}},
  {"execute_js":{"body":"const t=document.body.innerText;const i=t.indexOf('支付方式');return {url:location.href,seg:i>=0?t.slice(i-200,i+900):t.slice(-900)};"}},
  {"get_requests":{"filter":"authCenter","limit":10}}
]}}
```

1. **拿到并记下订单号**（`order_id=<订单号>`）——后面查审核、开发票、申请合同全靠它；
2. **确认订单状态已从「待支付」变成正常态**（订单页能读到「支付方式: 微信支付」就说明这一单成立）；
3. **把订单号脱敏后写进交付说明**（只留后 4 位）。

**支付没走完的两种情况**：

- **人还没付**：订单会停在待支付。**不要代替人重试支付、不要连点支付按钮**——把状态告诉用户，等他付完再回读。
  重新进订单页的路径就是上面的 `go_to_url`，比在界面上找入口稳。
- **付了但页面没跳**：不要凭「页面没变」判断失败。按上面批次**直接 `go_to_url` 订单详情页回读**，
  这才是权威状态（主技能第 21 条：`ok:true` 与 `changed` 都不证明业务结果）。

> **订单号是这一整条线的锚点**：支付、审核进度、发票、合同四个页面都挂在它上面。
> 一旦拿到就记进任务笔记，别指望后面再从界面里翻出来。

## 7. 查审核结果

### 7.1 订单详情页

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/orderDetail?order_id=<订单号>"}},
  {"wait_for_stable":{"quietMs":900,"timeoutSeconds":25}},
  {"execute_js":{"body":"const t=document.body.innerText;const i=t.indexOf('订单编号');return {url:location.href, seg:i>=0?t.slice(i,i+1200):t.slice(-1200)}"}}
]}}
```

能读到：订单编号、支付方式、有效期、认证规模、认证主体、申请人、下单时间、**订单合同**、**订单发票**、审核机构。

### 7.2 判断审核过没过的三个判据

| 判据 | 位置 | 说明 |
| --- | --- | --- |
| **订单发票**从「审核完成后可申请」变成「**申请发票**」 | 订单详情页 | **最灵敏的信号**：能申请发票 = 审核已结束 |
| 企业信息页出现 **`当前认证有效期至 <日期>`** | `#profile` | 出现即已通过，日期通常是下单日 + 一年 |
| `重新认证` 按钮出现 | 订单详情页 | 说明这一单已经走完 |

> 实测：审核期间订单页的「订单发票」写的是「审核完成后可申请」；过一段时间再打开，它变成了「申请发票」，
> 同时 `#profile` 出现「当前认证有效期至 <日期>」。**别在审核期间反复刷页面**，隔几小时看一次就够。

### 7.3 顺手可办的：发票与合同

**发票**（订单详情页 →「申请发票」）：

- 弹窗 `.mall_invoice_dialog_container`，含：开票项目、**电子发票类型**（增值税普通发票 / 增值税专用发票）、
  抬头类型（企业 / 组织 / 个人）、发票抬头、纳税人识别号、（选填）开户银行、银行账号。
- **抬头与税号通常是自动带出的**，核对即可；开户银行/银行账号是**选填**，普票不用填。
- **普票 vs 专票要问用户，不要自己拍**：

  | | 增值税普通发票 | 增值税专用发票 |
  | --- | --- | --- |
  | 抵扣 | 不可抵扣 | 可抵扣进项税 |
  | 前提 | 无 | **必须是一般纳税人**；小规模纳税人拿到专票也**不能抵扣**（简易计税，无进项概念） |
  | 资料 | 名称 + 税号 | 另需开户行、账号、注册地址、电话 |

  > **小规模纳税人不能抵扣**这条要主动讲给用户：为小规模企业开专票是零收益 + 多填资料。
  > 认证费金额小，专票能抵的税额也就十几块，不值得为它跑一趟客服。
- **提交后没有自助修改入口**：实测订单页只留「审核中 / 查看发票」，要改类型只能找审核机构客服
  （订单页上就写着客服微信号、邮箱、电话、工作时间）。**所以类型一定要在提交前问清**。

**合同**（订单详情页 →「申请合同」）：

- 两条路：**申请电子合同**（可在线签署并加盖双方电子签章，**首次签署需先申请企业电子签章**）、
  **申请纸质合同**（下载打印后加盖鲜章，约 **15 个工作日**寄回）。
- **点「申请电子合同」可能会被弹回电子签章流程**——如果企业电子签章还没批下来，
  按钮状态会显示「企业电子签章申请中」，点了就跳回签章申请的步骤页。**这不是出错，是前置依赖没满足**，
  把依赖关系讲清楚交给用户，不要在那儿反复点。

### 7.4 审核被退回 / 迟迟没有结果

- **审核主体是第三方机构，不是企业微信**：订单页上会写明审核机构名称、咨询电话、邮箱、客服微信号与
  **咨询时间（工作日 9:30~17:30）**。要催办或问退回原因，走这些渠道，**不要在企业微信里找入口**。
- **常见退回原因**（都对应本文前面某一节，可以自己先查一遍再找客服）：

| 退回原因 | 自查位置 |
| --- | --- |
| 营业执照不清晰 / 公章不可辨 / 传成了补充材料 | 4.3、4.4 |
| 企业全称或信用代码与营业执照不一致 | 3.3 |
| 法人扫脸未通过 / 非法人本人 | 5.2（回读 `formData.legalperson_face_info.verify_result`） |
| 企业简称不符合命名依据 | 3.4（`801` = 基于全称中的工商字号命名） |

- **退回后一般可以「重新认证」**：订单详情页有 `重新认证` 入口。重新走一遍时，
  **不要从零开始**——先回读 3.2 的 `formData`，多数字段还在，只需要改被退回的那一项。
- **不要高频轮询审核状态**：审核是人工的，隔几小时看一次足够。
  实测「订单发票」的文案变化（见 7.2）是最省事的信号，看它比翻整个订单页还快。

## 8. 该站点的通用坑与写法

### 8.1 内容区是内层滚动容器，`window.scrollTo` 无效

**实测**：`window.scrollTo(0, 0)` 执行后 `window.scrollY` **一直是 0**，而目标元素还在 `y = -192`
（即仍在视口上方）——后台的滚动发生在**内层容器**上，不是 `window`。

```js
// 找出真正的滚动容器（scrollHeight 明显大于 clientHeight 的那个）
(() => { const out = [];
  for (const e of document.querySelectorAll('div')) {
    if (e.scrollHeight - e.clientHeight > 100 && e.clientHeight > 200) {
      const s = getComputedStyle(e);
      if (/auto|scroll/.test(s.overflowY)) out.push({ cls: String(e.className).slice(0, 40),
        scrollTop: e.scrollTop, sh: e.scrollHeight, ch: e.clientHeight });
    }
  }
  return out.slice(0, 6); })()
```

**但更好的做法是根本不滚动**：

- 需要哪个元素就用 **`click_element_by_selector` / `input_text_by_selector` 按 id/class 定位**——
  这类方法不依赖快照索引，元素在视口外也照样能操作（主技能第七节第 7 条就是为这种情况写的）。
- 只有「必须让元素进视口」时（例如要截图给人看）才去操作内层容器：

```js
(() => { const box = document.querySelector('<内层容器选择器>');
  if (box) box.scrollTop = 0; return { scrollTop: box ? box.scrollTop : null }; })()
```

### 8.2 弹窗识别：`get_modals` 不认企业微信自己的弹窗类名

主技能里 `get_modals` / `close_modal` 的选择器是 **ant-design / Element-UI / vxe / layui 专用**的
（`.ant-modal-wrap`、`.el-dialog`、`.layui-layer`、`[role=dialog]`…）。而企业微信后台用自己的类名：

| 弹窗 | 实际类名 |
| --- | --- |
| 发票申请弹窗 | `.mall_invoice_dialog_container` |
| 通用确认框 | `.qui_dialog` / `.ww_dialog` 之类 |

**所以 `get_modals` 返回 `count: 0` 不等于「没有弹窗」**——它只是没命中。这种情况自己扫可见浮层：

```js
// 扫可见浮层：position:fixed 或高 z-index，且有实际尺寸
(() => { const out = [];
  for (const e of document.querySelectorAll('div,section')) {
    const s = getComputedStyle(e);
    if (s.display === 'none' || s.visibility === 'hidden') continue;
    const z = parseInt(s.zIndex);
    if (!(s.position === 'fixed' || (!isNaN(z) && z > 50))) continue;
    const r = e.getBoundingClientRect();
    if (r.width < 120 || r.height < 50) continue;
    out.push({ cls: String(e.className).slice(0, 50), z: s.zIndex,
      x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height),
      txt: (e.innerText || '').slice(0, 120) });
  }
  return out.slice(0, 8); })()
```

- **先 `close_modal` 试一次**（它内部用真实鼠标点，且会校验弹窗数量是否真的减少），
  `closed:false` 再退回上面的自扫 + `mouse_click` 坐标。
- 关不掉的企业微信弹窗，**用 `mouse_click` 点它的关闭按钮坐标**（`get_modals` 给的 `closePoint` 思路一样，
  只是坐标要自己从 `getBoundingClientRect()` 算）。
- **提交/支付类按钮不要用 JS 派发去点**：主技能第 30 条说过，`mode=js` 意味着没走真实交互。
  关键动作用 `mode:"mouse"`。

### 8.3 索引随时失效，回读要用选择器

- 认证表单是分步的，**每点一次「下一步」索引全部重算**（主技能第 3 条）。沿用旧索引会得到
  `click_element_by_index 失败：元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引`。
- **实测踩过**：上一批次拿到的 `[35]`，下一次调用就报上面这句；同一个元素换成
  `click_element_by_selector` 或 `document.querySelector(...)` 立刻就好。
- 结论：**这个站点上优先用选择器定位，把索引当一次性用品**。

### 8.4 `changed=false` 不代表点击失败，但也不能当成成功

- 实测点「免费开通」这类按钮时，`click_element_by_index` 回 `ok:true, changed:false`，
  **但网络面板里确实打出了对应的接口请求**（说明点击生效了，只是 500ms 观察窗口内没看到 DOM 变化）。
- 判据顺序：**`data.changed` → `diff_dom_text` → 网络请求（`get_requests` / `get_response_body`）→ 页面文案**。
  主技能第 21 条说得对：`ok:true` 只保证动作没抛异常。
- **不要为了判断这个去读截图**（省 token 铁律，主技能第 28 条）。

### 8.5 hash 路由可以直接跳，比点菜单稳

- 后台是 SPA，`go_to_url` 到 `#profile` / `#/authCenter/?from=cert_vbutton` / `#/authCenter/orderDetail?order_id=...`
  都能直达。**点菜单反而容易翻车**（菜单是纯 JS 事件、有 hover 展开、还常弹二级）。
- 跳转后等 `wait_for_stable`（不是固定 `wait`），再回读 `location.href` 确认真的到了。

### 8.6 校验纪律：以 Vue 的 `formData` 为准，不以 DOM 为准

- **DOM 里有值 ≠ 进了框架模型**（主技能第 30 条）。这个站点的权威答案在 3.2 那个 `MainValidate.formData` 里。
- `input_text_by_selector` 对可见字段默认走真实输入（`committed=true`）；
  看到 `data.committed=false` 就重填一遍，别继续加值。
- 密码类字段快照里是 `[redacted]`，别指望从快照读出来。

### 8.7 弹窗、协议层会挡住点击

- 主技能提到过「用户服务协议」这类覆盖层会让 `data.coveredBy` 指向别人——
  **此时你以为在点的按钮其实被它挡着**。回执里 `data.coveredBy` 有值就先关遮挡物再点，
  而不是反复点同一个按钮。
- 企业微信后台的协议勾选（如电子签章要勾《系统使用须知》《电子认证协议》《用户隐私协议》）
  **必须真的勾上**再提交，勾选后等 1–2 秒再回读 `is_checked`（框架状态更新是异步的）。

### 8.8 报错信息对照表（看到这句就做这件事）

| 报错 / 提示 | 含义 | 动作 |
| --- | --- | --- |
| `click_element_by_index 失败：元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引` | 索引已失效（页面变过） | **换 `click_element_by_selector`**（8.3），比重新取快照再点更稳 |
| `click_element_by_selector 失败：没匹配到可操作的元素(不存在或不可见)` | 选择器没命中，或元素不可见 | 用 `execute_js` 回读 `querySelectorAll` 的真实结果与 `offsetParent`；隐藏元素改用 `mode:"js"` 或先显示出来 |
| `[ACTION_TIMEOUT] 等待元素可操作超时` | 元素在但不可操作（被遮挡/有动画） | 换 `mode:"mouse"`（真实鼠标，不做可操作性检查）；回执里 `data.coveredBy` 指出是谁挡着 |
| `input_text_by_selector 失败：[ELEMENT_HIDDEN] 元素当前不可见` | 目标是隐藏输入框 | 优先切到它所在的那一步；不行才用 `mode:"js"`，并注意回执的 `data.committed=false` |
| `upload_file 失败：缺少参数 index` | 这个构建的 `upload_file` 要求 `index` | 改用/补上 `selector`（4.4、4.5）；本站在这里**优先选择器** |
| `send_keys 失败：Unknown key: "..."` | `send_keys` 只认键名 | 中文一律用 `input_text_by_selector`（8.6） |
| `请求体不是合法 JSON：not allow unquoted fieldName` | 客户端把 JSON 拼坏了（PowerShell / `curl.exe` 常见） | 改用 `dsb.py` 或 `Invoke-RestMethod` 传 UTF-8 字节；别用 `-d '...'` 手拼中文 |
| `不支持的方法：xxx` | 服务端这个版本没有该方法 | **先 `list_methods` 确认能力再写脚本**；老版本发布包可能没有 `wait_for_idle` / `get_form_state` 等新方法，缺失就用 `execute_js` 自己实现等价逻辑 |
| `没有找到对应的浏览器实例：<id>` | 实例不在（服务重启过 / id 写错 / 已 `close`） | `list_tasks` 看活着的任务，再决定 `start` 还是改用已有 id |
| `start 失败：该 id 已经有正在运行的浏览器实例：<id>` | 同 id 重复 `start` | 先 `close` 或换 id（主技能第 12 条） |
| 页面红字 **「请上传工商营业执照」** | 营业执照没进表单 | 走第 4 节，尤其 4.1 的组件直调与 4.7 的决策树 |
| 页面提示 **「短信验证码过期」** | 码超时了 | 重新点「重新发送验证码」要新码，**立刻**提交（5.3） |
| 提交按钮点了没反应 | 可能漏勾协议 / 有遮挡层 | 查 `data.coveredBy`；回读协议勾选状态（8.7） |

> **通用纪律**：这个后台的报错大多是「索引失效」或「控件没进框架模型」两类。
> 前者换选择器，后者换真实鼠标 / 真实输入——**都不需要重启服务，也不需要重做整个流程**。

## 9. 一次典型任务的骨架（脱敏模板）

```json
{"id":1001,"method":"start","params":{"headless":false,"browser":"chrome"}}
```

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/?from=cert_vbutton"}},
  {"wait_for_stable":{"quietMs":900,"timeoutSeconds":25}},
  {"get_browser_state":{}}
]}}
```

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"input_text_by_selector":{"selector":"<企业全称输入框>","text":"<企业全称>"}},
  {"input_text_by_selector":{"selector":"<信用代码输入框>","text":"<18 位统一社会信用代码>"}},
  {"input_text_by_selector":{"selector":"<管理员手机号输入框>","text":"<管理员手机号>"}},
  {"input_text_by_selector":{"selector":"<管理员邮箱输入框>","text":"<管理员邮箱>"}},
  {"get_form_state":{"includeHidden":true}}
]}}
```

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"upload_file":{"selector":"#dsh_up_0","path":"<营业执照文件名>"}},
  {"wait":{"seconds":1}},
  {"execute_js":{"body":"(async()=>{const inp=document.querySelector('.uploadInput')||document.querySelector('input[type=file]');let vm=inp&&inp.__vue__;for(let p=inp&&inp.parentElement;!vm&&p;p=p.parentElement){if(p.__vue__&&typeof p.__vue__.upload==='function')vm=p.__vue__;}if(!vm)return{err:'no vm'};const f=inp.files;if(!f||!f.length)return{err:'no files'};const key=await new Promise((res,rej)=>vm.upload({files:f},res,rej));vm.list.push({key:key,loadingIndex:null});vm.emitChange();return{filekey:key};})()"}}
]}}
```

```json
{"id":1001,"method":"request_human_input",
 "params":{"prompt":"请法定代表人用微信扫码完成人脸识别","selector":"<二维码元素选择器>","timeoutSeconds":600}}
```

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"const i=document.querySelector('.uploadInput');let vm=i&&i.__vue__;for(let p=i&&i.parentElement;!vm&&p;p=p.parentElement){if(p.__vue__&&typeof p.__vue__.upload==='function')vm=p.__vue__;}return {keys:vm?(vm.list||[]).map(x=>x.key).filter(Boolean):[],txt:document.body.innerText.slice(-600)};"}}
]}}
```

**节奏建议**：一个批次 = 一个计划段（「填一屏字段」/「上传 + 验证」/「提交 + 断言」），
末尾放一次回读（`get_browser_state` 或 `execute_js`），下一次推理基于新结果决定后续。
**不要在一个批次里塞几十步**（主技能第 19 条：批次是顺序执行的，前面的失败会影响后面）。

**收尾**：确认没有别的任务在跑之后 `close`（最后一个任务关闭时浏览器才一起退出，不留孤儿进程）。
等人工扫脸/支付期间**不要 close**，任务还没结束。

## 10. 交付话术（收尾）

1. **说清做到了哪一步、卡在哪一步需要人做什么**，用四件事的清单：扫脸 / 短信码 / 支付 / 最终确认。
2. **给脱敏后的关键信息**：企业全称（可保留）、信用代码**只留后 4 位**、订单号**只留后 4 位**、
   管理员手机号**只留后 4 位**。**不要把这些完整值写进交付说明**。
3. **说明审核状态与后续动作**：
   - 审核中 → 提醒「**订单发票**从『审核完成后可申请』变成『申请发票』就代表审核结束」，别让用户天天刷；
   - 已通过 → 给「当前认证有效期至 `<日期>`」，提醒到期要续（认证一年一续）。
4. **发票类型要用户确认**（普票 / 专票），并把「小规模纳税人不能抵扣」讲清楚；提交后不能自助改。
5. **合同说明依赖**：电子合同需先有企业电子签章；没有签章时只能走纸质合同（约 15 个工作日寄回）。
6. **提醒清理**（重要，涉及敏感材料）：
   - `logs/trace/**` 与 `logs/agent/**`（含手机号、证件号、地址，脱敏只是尽力而为）；
   - **服务端暂存目录里的营业执照**（`DELETE /playwright/upload?name=<文件名>` 或直接删目录）；
   - `data/<id>/` 里的截图与结构化文本（一次完整流程能攒下上百个文件）。
   用 `cleanup` 时注意它**默认只预演**，要真删得传 `"dryRun":false`。
7. **别把「已提交」说成「已通过」**：审核由第三方机构做，通过与否只能以 7.2 的三个判据为准。

## 11. 脱敏约定

本文所有示例都是占位符：`<企业全称>`、`<18 位统一社会信用代码>`、`<法人姓名>`、`<法人身份证号>`、
`<管理员手机号>`、`<管理员邮箱>`、`<订单号>`、`<企业ID>`、`<企业简称>`、`<营业执照文件名>`。

- **不要把真实值写回技能文档**：真实公司名、统一社会信用代码、法人身份证号、管理员手机号/邮箱、
  订单号、企业 ID、**本机绝对路径**，一律用上面的占位符。
- 给用户看的过程记录里，信用代码/手机号/订单号**只留后 4 位**，身份证号**直接省略**。
- 营业执照扫描件与 logo 这类材料**不要放进技能仓库**，放临时目录，用完清理。
- 服务端追踪日志默认脱敏（手机号、18 位证件号/统一社会信用代码、邮箱、长数字 → `***`），
  可用 `browser.trace.redact` 追加公司名等自定义正则，但**姓名、门牌号掩不掉**，共享前自己过一眼。
