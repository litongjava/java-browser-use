---
name: wecom-profile-complete
description: 用 deepseek-browser-use 完善企业微信「企业信息」（work.weixin.qq.com/wework_admin/frame#profile）的实操手册：企业 logo、企业地址（三级区划级联）、企业简称（含简称命名依据，且简称只能免费改一次）、企业域名绑定、联系电话（可留空）、企业名片，以及每一步的回读确认写法。该后台是 Vue 单页应用 + hash 路由，内容区是内层滚动容器（window.scrollTo 无效），快照索引在任何点击或异步渲染后立即全部重算，视口外的元素既不进 data.text 也没有索引，所以定位一律优先用 CSS 选择器而不是索引；企业 logo 上传框是 opacity:0 且位置在视口外的隐藏 file input，正解是 upload_file 传 selector；企业微信自己的弹窗类名不一定命中 get_modals 的选择器（返回 0 不等于没有弹窗），这种情况要用 execute_js 自己扫 position:fixed 或高 z-index 的可见浮层；Vue 组件可能压根没挂事件监听器（el._vei 为 null），此时派发的 change/click 到不了框架 handler，只能直接调组件方法。企业域名绑定的前置条件是该域名已经能收信（企业微信会做 MX 校验），必须先按 skills/wecom-mail-domain 把邮箱域名绑好，绑好后本页填域名点「绑定域名」即完成，不需要再建验证邮箱。文内数据全部脱敏，替换占位符即可复用。
whenToUse: 需要完善或修改企业微信管理后台「企业信息」里的资料（企业 logo、企业地址、企业简称、企业域名、联系电话、企业名片），或需要在 work.weixin.qq.com 这个 Vue SPA + hash 路由 + 内层滚动容器的后台上做「定位元素 → 填表 → 提交 → 回读确认」这类操作时。
---

# 企业微信「企业信息」完善（实操手册）

一句话流程：**有头 Chrome → 人扫码登录 → `#profile` 回读现状 → 逐字段改（logo / 地址 / 简称 / 域名）→ 每改一项立刻回读确认**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录，不是企业微信使用指南。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这个后台上「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：把用户在本次任务里点名的字段改完并回读确认即停。**不要顺手改用户没要求的字段**
  —— 企业信息是**对外展示**的资料（企业地址、企业简称会出现在企业名片与对外沟通里），改错了别人看得见。
- **人来做的部分**：扫码登录、短信验证码、人脸识别、支付、盖章。**登录一律交给人**（见第 2 节）。
- **不做的事**：不绕过验证码、不伪造企业资料、不替企业做承诺类勾选。
- **三个必须提前告知用户的不可逆 / 有后果项**：
  1. **企业简称只能免费改一次**，之后再改需要走认证流程（见第 6 节）。改之前一定让用户确认。
  2. **企业域名绑定后，该域名的收信会指到企业微信邮箱**（企业微信会对域名做 MX 校验），
     动之前确认这个域名本来就该用来收企业邮件。
  3. **联系电话可以留空**，是可选字段 —— 用户说不填就不填，别自作主张补一个（见第 8 节）。
- 改动提交后一般只有 toast，**`ok=true` 不代表真的生效**：每一项都必须按第 3.4 节的写法回读一次。

## 1. 开工前

### 1.1 服务

先确认服务活着，再看它现在认的是什么引擎、什么 profile：

```shell
curl -s http://localhost:10049/playwright/health
curl -s http://localhost:10049/playwright/config          # engine / profileDir / action / upload
curl -s http://localhost:10049/playwright/tasks           # 有没有别的任务占着浏览器
```

`get_config`（或 `GET /playwright/config`）能回答三个高频问题：**这次用的是哪个引擎**、
**profile 落在哪个目录**、**动作超时与降级开关怎么配的**。方法名拿不准时先 `list_methods`，别猜。

### 1.2 浏览器：必须有头 + 用 Chrome

```json
{"id": 1001, "method": "start", "params": {"headless": false, "browser": "chrome"}}
```

- **`headless: false` 是硬要求**：这个后台必须由人在真实窗口里扫码登录，无头没法接力。
- **`browser: "chrome"`**：企业微信后台是普通 Vue SPA，Chromium 系完全够用，**不需要**像中国商标网那样切
  Firefox（切了反而换 profile、换登录态，白白重登一次）。
- **换引擎等于换登录态**：Chromium 与 Firefox 的 profile 格式不通用，`edge` 也有自己一份
  （`data.browser.profileDir` 能看出这次落在哪）。**别为了「试试看」切浏览器**，切完要重新扫码登录。
- **一次只能有一个浏览器**：任务还在跑时 `start` 一个不同的 `browser` 会被直接拒。要换就先 `close`
  再 `start`，**不用重启服务**。
- 登录态**跟着共享 profile 走，不跟任务 id 走**：这次登过，下次换 id 还是登着的。

回执里要看 `data.browser.type`（实际用的浏览器）、`data.browser.userProfile`
（**`false` 说明不是用户日常那份登录态**，那就必须重新走登录）、`data.browser.note`（服务替你退让过就会写在这）。
`data.browser.upload.dir` 就是上传文件的暂存目录，第 4 节要用。

### 1.3 开工前必须问清的字段

一次问全，避免填到一半停下来。**标记「可选」的字段，用户不说就留空**：

| 字段 | 必填 | 要问什么 |
| --- | --- | --- |
| 企业 logo | 视任务 | 图片文件路径（服务端能打开的那个）、格式与尺寸 |
| 企业地址 | 视任务 | 省 / 市 / 区三级区划 + 详细地址（门牌号） |
| 企业简称 | 视任务 | 简称文字 + **命名依据选哪一项**（见 6.3），并**明确告知只能免费改一次** |
| 企业域名 | 视任务 | 域名（**并确认这个域名已经在企业微信邮箱里绑好、MX 已生效**，见第 7 节） |
| 联系电话 | **可选** | 用户明说不填就留空；要填就问清号码 |
| 企业名片 | 可选 | 是否需要，以及要展示什么 |

**地址与简称是「对外可见」的**，填之前把值复述给用户确认一次，比事后回滚便宜。

### 1.4 留档（排查用）

- 服务端：`logs/trace/<yyyyMMdd>/`（`steps.log` 时间线、`calls.jsonl` 逐条 JSON、
  `NNNNNN-<任务id>-<方法>.json` 完整请求响应、`uploads.log` 上传记录）。
- 客户端：仓库里的 `scripts/client/dsb.py`（跨平台、退出码区分传输错/业务失败/用法错、默认脱敏、
  `js` 子命令支持 `{{变量}}` 注入）；已有 PowerShell 排查习惯的可以用 `scripts/trace/browse.ps1`。
- 服务端日志**默认脱敏**（手机号、18 位统一社会信用代码、邮箱、长数字 → `***`），但这是**尽力而为**：
  公司名、门牌号这类认不出来的不会被掩掉，而且日志**不会自动清理**。
- 任务结束提醒用户清理：`cleanup`（**默认只预演**，要真删显式传 `"dryRun": false`）。

### 1.5 用客户端省掉手拼 JSON

中文、引号、换行在手拼 `-d '...'` 时很容易被吃掉（PowerShell 尤其）。稳定写法：

```shell
python scripts/client/dsb.py --port 10049 --id 1001 health
python scripts/client/dsb.py --port 10049 --id 1001 start --browser chrome
python scripts/client/dsb.py --port 10049 --id 1001 run go_to_url -p url=https://work.weixin.qq.com/wework_admin/frame
python scripts/client/dsb.py --port 10049 --id 1001 js @回读.js          # 支持 {{变量}} 注入
python scripts/client/dsb.py --port 10049 --id 1001 batch cmds.json --async --wait   # 长批次不受 HTTP 超时限制
```

## 2. 登录：交给人

后台入口：

```
https://work.weixin.qq.com/wework_admin/frame#profile
```

```json
{"id": 1001, "method": "commands", "params": {"stopOnError": false, "commands": [
  {"go_to_url": {"url": "https://work.weixin.qq.com/wework_admin/frame"}},
  {"wait": {"seconds": 4}}
]}}
```

然后**请人在弹出的 Chrome 窗口里扫码登录**。用 `bring_to_front` 把页签带到最前，或走
`request_human_input` 建一条人工请求（它会顺手把页签带到最前）。

**登录成功的判据**：左侧导航出现 `首页 / 通讯录 / 协作 / 应用管理 / 客户与上下游 / 高级功能 / 安全与管理 / 我的企业`。
**不要靠标题判断**（未登录时标题也是「企业微信」）。

```js
// 回读是否已登录：左侧导航在 = 进去了
var nav = [].slice.call(document.querySelectorAll('a span'))
  .map(function(e){ return e.innerText.trim(); })
  .filter(function(t){ return t === '我的企业' || t === '通讯录' || t === '安全与管理'; });
return {loggedIn: nav.length >= 2, nav: nav};
```

登录态长期留在共享 profile 里，**同一个浏览器后续任务不用再登**。若回执里 `data.browser.userProfile=false`，
说明这次用的是托管 profile，仍要按上面请人登一次。

## 3. 企业信息页结构总览与定位策略

### 3.1 页面长什么样

`#profile` 的 `document.body.innerText` 顺序（实测）：

```
企业信息 / 前往认证 / 当前认证有效期至<日期>
企业logo            <img class="profile_...">
企业简称            <企业简称>  修改
企业全称            <企业全称>  修改
主体类型            企业
企业名片            @<企业简称>   在桌面端完善
企业地址            <省><市><区><详细地址>
联系电话            添加                     ← 留空时就是「添加」
企业域名            <域名> 修改 删除          ← 未绑定时是「添加」
企业成员            N个成员  统计
企业部门            N 个部门
已使用/人数上限      N/1000 申请扩容
发票抬头            添加
行业类型 / 员工规模 / 创建时间 / 企业ID
```

对应的稳定类名（**定位优先用这些，别记索引**）：

| 位置 | 选择器 |
| --- | --- |
| 每个字段行 | `.profile_enterprise_item` |
| 字段名 | `.profile_stage_label` |
| 字段值 / 操作区 | `.profile_enterprise_item_main` |
| logo 行 | `.profile_enterprise_item_Logo` |
| 企业域名行 | `.profile_enterprise_item_domain` |
| 企业地址值 | `.profile_enterprise_item_address` |
| 企业域名「添加」链接 | `a[href="#profile/domain"].js_domain_add_report` |

> 这些类名是站点自己生成的，版本升级可能变。**用之前先回读一次确认存在**（`get_element_count` 最省事），
> 不要当永久契约。

### 3.2 定位策略：选择器 > 索引

这个后台**索引失效极快**：任何点击、任何异步渲染之后，索引全部重算。沿用上一次快照的 `[index]`
会拿到两种报错之一：

```
click_element_by_index 失败：元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引
click_element_by_index 索引越界: 999
```

所以纪律是二选一：

- **全程用选择器**（推荐）：`click_element_by_selector` / `input_text_by_selector` /
  `get_form_state` 都按 CSS 选择器走，不受索引重算影响。
- **必须按索引时**：严格「一次 `get_browser_state` → 只做一个动作 → 再取快照」，中间不插入任何其它动作。

### 3.3 两个结构性坑：内层滚动容器 + 视口外元素

**坑一：内容区是内层滚动容器，不是 `window`。** 实测 `window.scrollTo(0, 0)` 毫无效果
（`window.scrollY` 恒为 0，而目标元素还在 `y=-192`）。所以：

```js
// 正解：让元素自己滚进视口，不用管是谁在滚
var el = document.querySelector('.profile_enterprise_item_domain');
if (el) el.scrollIntoView({block: 'center'});
return {y: el ? Math.round(el.getBoundingClientRect().top) : null};
```

要确认到底是哪个容器在滚（排查用）：

```js
return [].slice.call(document.querySelectorAll('div'))
  .filter(function(e){ return e.scrollHeight > e.clientHeight + 50 && e.clientHeight > 300; })
  .map(function(e){ return {cls: String(e.className).slice(0, 50),
                            sh: e.scrollHeight, ch: e.clientHeight, top: e.scrollTop}; });
```

服务端也有现成的：`scroll_to_text`（按文本滚动到可见，找不到会等满 30 秒）——
**别用它探测元素是否存在**，那是 `wait_for_element` 的活。

**坑二：快照只覆盖当前视口。** 视口外的元素**不在 `data.text` 里、也没有索引**。
所以「找不到元素」的第一反应应该是**它不在视口内**，而不是选择器写错了。判断顺序：

1. `get_element_count` 按选择器数一下（不需要索引、不受视口影响）；
2. 有数量但快照里没有 → `scrollIntoView` 把它滚进来，再取快照；
3. 还是没有 → 才怀疑选择器。

```json
{"id":1001,"method":"get_element_count","params":{"selector":".profile_enterprise_item"}}
```

### 3.4 回读确认（每一项改完都要做）

**`ok=true` 只代表动作没抛异常，不代表点中了东西。** 用 `data.changed` 只能说明「观察窗口内页面动过」，
所以改完必须回读真实值：

```js
// 一次性回读企业信息页所有字段（标签 → 值），比逐个 get_element_text 省得多
return [].slice.call(document.querySelectorAll('.profile_enterprise_item')).map(function(e){
  var k = e.querySelector('.profile_stage_label');
  var v = e.querySelector('.profile_enterprise_item_main');
  return (k ? k.innerText.trim() : '?') + ' = ' +
         (v ? v.innerText.trim().replace(/\s+/g, ' ') : '(空)');
});
```

要按「字段真的变了」下断言时，用批量指令的 `expect`（见第 10 节）：
**回执说成功不算数，断言通过才算数**。

## 4. 企业 logo

### 4.1 怎么点

logo 行是 `.profile_enterprise_item_Logo`，点它旁边的「修改」打开上传弹窗：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"var row=document.querySelector('.profile_enterprise_item_Logo');if(!row)return {noRow:true};var a=[].slice.call(row.querySelectorAll('a,span,div')).filter(function(e){return e.innerText.trim()==='修改'})[0];if(a){a.id='dsh_logo_edit';a.click()}return {clicked:!!a};"}},
  {"wait":{"seconds":3}}
]}}
```

### 4.2 上传框是「隐藏且跑到视口外」的 file input

**这是本任务最容易卡住的一步。** 弹窗里的 file input：

- `opacity: 0`
- 位置在视口外（实测 `x = -1166`）

后果有两个，都很迷惑人：

1. 它**不在快照里**（不可见 → 没有索引）→ 按索引的 `upload_file` 无从下手；
2. 它**也不是「看得见但被挡住」**，所以 `mode:"mouse"` 这类强制真实鼠标的招数对它没用。

**正解：`upload_file` 直接传 `selector`。** 它不需要元素可见，也不需要索引 —— 主技能第六节
「上传文件」写得很明确：

```json
{"id":1001,"method":"upload_file",
 "params":{"selector":"input[type=file]","path":"<服务端能打开的 logo 文件路径>"}}
```

- `path` 是**服务端**路径，不是 URL。相对路径按服务端暂存目录解析。
- 智能体与浏览器不在同一台机器时，先把文件 POST 上去，再用回执里的 `path`：

```shell
curl -F "file=@logo.png" http://localhost:10049/playwright/upload
# → {"data":{"filename":"logo.png","path":"<服务端暂存目录>/logo.png","relativePath":"logo.png","size":16799,...}}
```

- **选择器要选准**：页面里可能同时挂着别的 file input（例如「补充证明材料」那类）。传之前先用
  `get_element_count` 数一下 `input[type=file]` 有几个，必要时用更具体的选择器（带上弹窗容器的类名）。
- 这个 logo 上传框是**普通的 `ww_fileInput`，原生上传就能成功** —— 与认证流程里那个
  「Vue `ImageUploader` 的 `uploadInput` 没挂监听器、必须直接调组件方法」的坑**不是一回事**
  （那个坑见 9.4）。**先按本节的写法做，不要一上来就去调组件方法。**

### 4.3 回读

上传成功后页面上那张 `<img>` 的 `src` 会换成新地址（实测会变成 `wework.qpic.cn` 上的新链接），
并且 `width` 变成实际渲染宽度：

```js
var img = document.querySelector('.profile_enterprise_item_Logo img');
return {src: img ? img.src : null, w: img ? img.width : null, h: img ? img.height : null};
```

**判据是 `src` 变了**，不是「弹窗关了」。弹窗不自动关的话按 9.5 收掉。

> 想让隐藏 input 变成可见（**只在确实没有别的办法时用**）：`style.cssText` 强行改成
> `position:fixed; top:80px; left:80px; width:240px; height:40px; opacity:1; display:block; visibility:visible; z-index:2147483647`。
> 改动页面样式可能让站点行为与真实用户不一致，**而且这里根本不需要** —— `upload_file` 传 `selector` 就够了。

## 5. 企业地址

### 5.1 打开编辑

企业地址那一行点「修改」→ 弹窗里是 **三级区划级联下拉 + 详细地址**。

```js
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业地址'; })[0];
if (!row) return {noRow: true};
var a = [].slice.call(row.querySelectorAll('a,span,div'))
  .filter(function(e){ return e.innerText.trim() === '修改'; })[0];
if (a) a.click();
return {clicked: !!a};
```

### 5.2 级联的顺序与纪律

三级区划是**级联下拉**：先选省 → 才出市 → 选市 → 才出区。三条纪律：

1. **一级一级来，每级之间等 2 秒**。下一级的数据是上一级选完才拉的，急了就是「暂无数据」。
2. **一级失败就整段重跑**，不要只补最后一级 —— 会把上一级清掉，越补越乱。
3. **选完级联再补详细地址**（门牌号），因为级联选中会覆盖地址框的内容。

下拉的写法（原生 setter + `input` 事件触发前端过滤）：

```js
// 打开某个下拉并塞过滤词；w 是选项文本（省/市/区名）
var openAndFilter = function (sel, w) {
  var box = document.querySelector(sel);
  if (!box) return false;
  ['mousedown','mouseup','click'].forEach(function (k) {
    box.dispatchEvent(new MouseEvent(k, {bubbles: true, cancelable: true, view: window}));
  });
  var inp = box.querySelector('input') || box;
  var set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  set.call(inp, w);
  inp.dispatchEvent(new Event('input', {bubbles: true}));
  return true;
};
```

选完之后**逐级回读**，确认每一级都真的选上了再往下走：

```js
// 回读弹窗里所有输入框的当前值（区划 + 详细地址）
var dlg = [].slice.call(document.querySelectorAll('div'))
  .filter(function(e){ return e.offsetParent !== null && /详细地址/.test(e.innerText || '')
                              && e.innerText.length < 600; }).pop();
if (!dlg) return {noDlg: true};
return [].slice.call(dlg.querySelectorAll('input,textarea'))
  .map(function(e){ return {ph: e.placeholder || '', v: e.value || ''}; });
```

### 5.3 提交并回读

提交后回读 `.profile_enterprise_item_address`：

```js
var a = document.querySelector('.profile_enterprise_item_address');
return {address: a ? a.innerText.trim() : null};
```

**注意**：页面显示的是**拼接后的完整地址**，逐字和你填的详细地址可能不完全一致（级联会把省市区的
规范化名称接在前面）。**判据是「省市区对不对 + 详细地址在不在里面」**，不要因为多了/少了个空格就重填。

## 6. 企业简称（含命名依据）

### 6.1 先讲清楚：只能免费改一次

**企业简称只能免费改一次，之后再改需要走认证流程。** 所以：

- 改之前**把简称文字和命名依据一起复述给用户确认**，得到明确答复再动手；
- 用户如果只是「随口一说」，先问一句「确认现在就改成「<企业简称>」吗？这个只能免费改一次」；
- 已经改过一次的企业，这一行点「修改」可能会引导去认证 —— **别硬试**，如实告诉用户当前限制。

### 6.2 打开编辑

```js
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业简称'; })[0];
if (!row) return {noRow: true};
var a = [].slice.call(row.querySelectorAll('a,span,div'))
  .filter(function(e){ return e.innerText.trim() === '修改'; })[0];
if (a) a.click();
return {clicked: !!a};
```

### 6.3 简称命名依据（关键字段）

弹窗里除了简称输入框，还有一个**命名依据**的下拉 / 单选项。实测取值：

| 值 | 含义 |
| --- | --- |
| `801` | **基于全称中的工商字号命名** |

还有其它选项（基于商标命名、基于字号+行业命名等），**以弹窗里实际列出的为准**，别照抄这张表。

**怎么选**：看用户给的简称是从哪儿来的 ——

- 简称就是企业全称里的字号 → 选 `801`「基于全称中的工商字号命名」（**最常用**）；
- 简称来自注册商标 → 选基于商标那一项；
- 拿不准就**停下来问用户**，不要替他选。选错可能被驳回，而且**简称只能免费改一次**。

### 6.4 提交有二次确认

点提交后会弹**二次确认弹窗**（再确认一次简称）。这一步别漏：

- 二次确认弹窗**必须用真实鼠标点**（`mode:"mouse"` 或 `mouse_click_by_selector`），
  JS 派发的 click 在确认类控件上经常无效（见 9.5）；
- 点完**回读简称是否真的变了**，而不是看弹窗关没关。

```js
// 回读简称（以及企业名片里的 @简称 是否跟着变）
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业简称'; })[0];
return {shortName: row ? row.querySelector('.profile_enterprise_item_main').innerText.trim() : null};
```

**企业名片会自动跟着变**（显示成 `@<企业简称>`），不需要单独去改。

## 7. 企业域名绑定

### 7.1 前置条件：域名必须已经能收信

**这是本页最容易被忽略的一条**：绑定企业域名时，**企业微信会对该域名做 MX 校验**，
所以域名必须**已经能收信**。完整顺序是：

```
① 在 DNSPod / 其它解析商给域名加两条 MX 记录
     @ MX  mxbiz1.qq.com.  优先级 5
     @ MX  mxbiz2.qq.com.  优先级 10
② 在企业微信邮箱控制台把该域名开通、绑好，看到「<域名> — 使用中」
     → 这一步见 skills/wecom-mail-domain
③ 回到本页（#profile/domain）填域名 → 点「绑定域名」
```

**顺序不能颠倒**：先来本页绑域名，会卡在 MX 校验过不去。

**验证 MX 真的生效了**（别只看解析商后台显示「已添加」，各地缓存不一样）：

```shell
nslookup -type=MX <域名> 8.8.8.8
nslookup -type=MX <域名> 119.29.29.29     # 腾讯自己的递归，最贴近企业微信的视角
```

两条记录都出来才算数。解析商后台显示「添加成功」时往往**权威 NS 已经生效、但递归缓存还没刷新**，
所以要查递归。

### 7.2 页面上的两种状态

| 状态 | DOM | 怎么办 |
| --- | --- | --- |
| **未绑定** | `<a href="#profile/domain" class="js_domain_add_report">添加</a>` | 点它，或直接 `go_to_url` 到 `#profile/domain` |
| **已绑定** | 显示 `<域名>` + `修改` `删除` | 已经好了，**回读确认即可，别重复绑** |

直接按路由跳过去最稳（不依赖快照索引）：

```json
{"id":1001,"method":"go_to_url",
 "params":{"url":"https://work.weixin.qq.com/wework_admin/frame#profile/domain"}}
```

### 7.3 绑定页只有一个输入框

`#profile/domain` 的页面文本：

```
绑定企业域名
企业域名:  [ input placeholder="abc.com" ]
绑定域名
```

写法：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"var i=[].slice.call(document.querySelectorAll('input')).filter(function(e){return e.offsetParent!==null&&/abc\\.com/.test(e.placeholder||'')})[0];if(!i)return {none:true};i.id='dsh_dom';return {ok:true};"}},
  {"input_text_by_selector":{"selector":"#dsh_dom","text":"<域名>"}},
  {"wait":{"seconds":2}},
  {"execute_js":{"body":"var b=[].slice.call(document.querySelectorAll('a,button')).filter(function(e){return e.offsetParent!==null&&e.innerText.trim()==='绑定域名'});if(!b.length)return {n:0};b[b.length-1].click();return {n:b.length};"}},
  {"wait":{"seconds":10}}
]}}
```

### 7.4 成功判据

页面文本直接变成：

```
绑定企业域名
已绑定 <域名> 企业域名
```

```js
var t = document.body.innerText;
var i = t.indexOf('绑定企业域名');
return {seg: i >= 0 ? t.slice(i, i + 120) : t.slice(-300)};
```

**不需要再建验证邮箱。** 域名验证是通过企业微信邮箱那边的 MX 校验完成的；本页填域名点绑定就结束，
**不会**出现「请创建 `workweixin<随机串>@<域名>` 邮箱并接收验证邮件」那一步。
（踩过的弯路：以为要先去建那个随机地址的邮箱，其实只要邮箱域名绑好了，本页直接过。）

绑定完成后回 `#profile` 回读，那一行应该变成 `<域名> 修改 删除`。

## 8. 联系电话与企业名片

### 8.1 联系电话：**可以留空**

这一行留空时显示的是「添加」。**它是可选字段** —— 用户明确说不填就**不要填**，
别自作主张补一个号码（这是对外可见的企业信息，填错了要再改）。

要填的时候：点「添加」（`a.js_tel_edit_btn` 是它的类名）→ 填号码 → 提交 → 回读。

```js
var a = document.querySelector('a.js_tel_edit_btn');
return {hasTelAddLink: !!a};
```

### 8.2 企业名片

企业名片显示成 `@<企业简称>`，**会跟着企业简称自动变**，一般不需要单独操作。
页面上写「在桌面端完善」的提示属于客户端功能，**网页后台点不动是正常的**，别在这里耗时间。

如果任务确实要求完善名片，先回读它的当前值确认：

```js
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业名片'; })[0];
return {card: row ? row.innerText.replace(/\s+/g, ' ').trim() : null};
```

## 9. 该站点的通用坑（Vue SPA + hash 路由）

### 9.1 内层滚动容器：`window.scrollTo` 无效

见 3.3。**记住结论**：滚动一律用 `el.scrollIntoView({block:'center'})`，
不要碰 `window.scrollTo`（实测 `window.scrollY` 恒为 0，页面根本没动）。

### 9.2 索引失效极快

见 3.2。任何点击 / 异步渲染之后索引全部重算，沿用旧索引会拿到
「元素不存在或页面已变化」或「索引越界」。

**稳妥节奏**：一次 `get_browser_state` → 只做一个动作 → 再取快照；或者干脆全程用选择器。

### 9.3 视口外元素不进快照

见 3.3。**「找不到元素」先怀疑它在视口外**，不是选择器写错。

**三条解法，按优先级**：

1. **`get_browser_state` 传 `viewportExpansion`**（**最省事，很多人不知道它存在**）：

   ```json
   {"id":2001,"method":"get_browser_state","params":{"highlight":false,"viewportExpansion":1500}}
   ```

   视口外扩的像素数，默认 `0`。企业信息这种「一屏放不下」的页面，直接调大到 `1000~2000`
   就能一次拿到首屏之外的元素索引，**不用滚动**。回执里的 `data.pixels_above` / `data.pixels_below`
   会告诉你上下还差多少没进快照——**它们不为 0 就说明扩得还不够**。
2. **改用选择器定位**：`click_element_by_selector` / `input_text_by_selector` /
   `click_element_by_text`（按 id/class/文本定位，**不依赖快照索引**，也不受视口影响）。
3. **滚动**：先找到内层滚动容器再滚（见 9.1）。

### 9.4 Vue 组件可能压根没挂事件监听器（`el._vei` 为 null）

这是本后台**最隐蔽**的一个坑，在认证流程的营业执照上传上真踩过。

**怎么判断**：Vue 2 会把事件 invoker 挂在元素上，取 `el._vei`：

```js
var a = document.querySelector('<某个可点元素的选择器>');
return {vei: a && a._vei ? Object.keys(a._vei) : null,
        hasVue: !!(a && a.__vue__)};
```

- `_vei` **为 `null`** → 这个元素**没有挂事件监听器**，你用 JS 派发 `change` / `click`
  **到不了框架的 handler**。表现是：`input.files` 明明设对了、事件也派发了，页面**毫无反应**，
  而且校验还报「请上传××」。
- 注意 **`_vei` 为 null 不等于元素不可点** —— 也可能是 Vue 3（invoker 存在 WeakMap 里，不挂元素上）。
  所以这一步只作为「怀疑点」的证据，不是判决。

**这种情况下的出路**（按顺序）：

1. **换个入口**：很多时候同一件事有两条路（比如用 `upload_file` 传 `selector` 走原生上传，
   就绕开了「必须调组件方法」的问题）——**优先找这种路**；
2. **直接调组件方法**：沿 `__vue__` 或 `parentElement` 链找到组件实例，调它自己的方法，
   再**复刻组件自己的状态写回**（这一步最容易漏，漏了就是「接口成功但页面没变」）：

```js
// 示意：找到实例 → 调它的上传方法 → 把结果按组件自己的方式写回 data + emit
var el = document.querySelector('<上传组件根元素>');
var vm = el && el.__vue__;
if (!vm) return {noVm: true};
// 1) 调组件方法（参数形状照组件源码给）
vm.upload({files: fileInput.files}, function onSuccess(res){ /* ... */ }, function onError(e){ /* ... */ });
// 2) 复刻组件自己的状态写回，否则页面不更新
vm.list.push({key: res.filekey, loadingIndex: null});
vm.emitChange();
return {done: true};
```

3. **最后才考虑改样式**把元素显示出来（见 4.3 的注意事项）。

**判断这一步到底成没成**：不要看接口返回 200，要看**页面校验错误消没消、以及最终提交时数据在不在**。
以认证页为例，成功判据是那个上传组件从 `image_uploader form_err` 变回正常、错误文案消失。

### 9.5 弹窗：企业微信自己的类名不一定命中 `get_modals`

页面会弹各种浮层：二次确认、成功提示、协议层、运营 banner。**浮层会挡住后面的点击**，
`data.coveredBy` 会告诉你目标中心点上实际命中的是谁。

**先试服务端的方法**：

```json
{"id":1001,"method":"get_modals","params":{}}
{"id":1001,"method":"close_modal","params":{"which":"top"}}
```

`close_modal` **一律用真实鼠标点**，并且点完**校验数量是否真的减少**（回执里 `data.closed`、
`data.countBefore`、`data.countAfter`）。`data.closed=false` 说明点了但数量没减，
这时用 `get_modals` 拿 `closePoint` / `buttonPoints` 的坐标，再 `mouse_click` 那个坐标。

**但这个后台有个陷阱**：`get_modals` 的选择器是 ant / Element / vxe / layui 专用的，
**企业微信自己那套弹窗类名不一定命中 —— 返回 `count: 0` 不等于没有弹窗。**
这种情况用 `execute_js` 自己扫可见浮层：

```js
// 扫「可能挡住点击」的浮层：position:fixed 或高 z-index，且面积够大
var vis = function (e) {
  var s = getComputedStyle(e);
  if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
  var r = e.getBoundingClientRect();
  return r.width > 150 && r.height > 60;
};
return [].slice.call(document.querySelectorAll('div,section'))
  .filter(function (e) {
    if (!vis(e)) return false;
    var s = getComputedStyle(e), z = parseInt(s.zIndex);
    return s.position === 'fixed' || (!isNaN(z) && z > 50);
  })
  .map(function (e) {
    var r = e.getBoundingClientRect();
    return {cls: String(e.className).slice(0, 45), z: getComputedStyle(e).zIndex,
            x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
            txt: (e.innerText || '').slice(0, 150)};
  });
```

**还有一个判据很好用**：看屏幕中心点命中的是谁 —— 如果命中的不是你以为在点的按钮，就是被挡住了。

```js
var c = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
return {centerHit: c ? c.tagName + '.' + String(c.className).slice(0, 50) : null};
```

### 9.6 点击类命令的 `mode` 与「看起来点成了」

点击类方法都接受 `mode`：`auto`（默认，原生 → 真实鼠标 → JS 派发依次尝试）/ `native` / `mouse`
（强制真实鼠标，不做可操作性检查）/ `js`（只派发事件，不要求可见、不被遮挡影响）。
回执里 `data.mode` 是**实际用上的那一档**，`data.fallbackReason` 是降级原因。

- **看到 `data.mode=js` 就要知道「这次没走真实交互」**：点击本身通常有效，但**提交类关键动作**
  要额外回读确认；
- **`ok=true` 不代表点中了东西**：实测点悬浮菜单时文本命中的是纯文本容器，方法返回成功、页面毫无变化。
  连 `data.changed` / `data.effective` 一起看；
- **确认类控件（二次确认弹窗的按钮）优先 `mode:"mouse"` 或 `mouse_click_by_selector`** ——
  这类控件对 JS 派发的 click 经常完全无响应，而接口照样回成功。

### 9.7 别用 JS 点左侧主菜单

左侧导航（首页 / 通讯录 / 协作 / 我的企业 …）是 `<a>` + Vue 事件，**很多没有可用的 href**，
JS 派发 click 和真实鼠标点击**都可能不跳转**。要切页时**直接 `go_to_url` 到 hash 路由**更快：

| 页面 | URL |
| --- | --- |
| 企业信息 | `.../wework_admin/frame#profile` |
| 企业域名绑定 | `.../wework_admin/frame#profile/domain` |
| 邮件（跨域 iframe，见下） | `.../wework_admin/frame#apps/qykit/proxy/exmail` |

### 9.8 跨域 iframe 里的内容读不到

`协作 → 邮件` 打开的是 `exmail.qq.com` 的**跨域 iframe**，有两个后果：

1. `get_browser_state` 的 `data.text` **只包含外层企业微信外壳**，iframe 里一个字都读不到；
2. 服务**没有 frame 相关方法**（`get_frames` / `list_frames` / `switch_frame` /
   `execute_js_in_frame` 全部返回「不支持的方法」），所以**没法直接操作 iframe 内部**。

**本文的任务（企业信息）不需要进那个 iframe** —— 企业域名绑定走的是 `#profile/domain`，
是后台自己的路由。**只有在必须操作邮箱控制台时**才需要绕过它，
那属于 `skills/wecom-mail-domain` 的范围。

> 顺带记一条通用教训：**iframe 的 `src` 里的 token 常常是一次性的**。实测把 iframe 的
> `src` 直接拿去顶层 `go_to_url` 会返回 `500 : HTTP Error 500`，因为 token 已经被 iframe 消费掉了。
> 遇到这种情况要看的是**页面自己是怎么拿到这个 token 的**（翻 `get_requests` 找发 token 的那个接口），
> 而不是反复重放旧 URL。

### 9.9 `ok=true` 不是业务成功

再强调一次，因为它在这个后台特别容易骗人：**`ok=true` 只代表动作没抛异常**。
`data.changed=false` 也不表示点击失败（可能只是观察窗口内没抓到变化）。
唯一可靠的判据是**回读目标元素 / 页面文本 / 接口响应**。禁止仅凭 `ok` 就重复提交 —— 重复提交
会把确认框一层层叠起来（实测在别处叠到过 16 个），之后所有「取第一个可见弹窗」的逻辑都在操作最老的那个。

### 9.10 服务端瞬时错误不要当成任务失败

`execute_js` 偶尔会返回网络观察钩子里的空指针（形如
`Cannot invoke "com.microsoft.playwright.Request.method()" because "request" is null`）。
**脚本其实已经跑完了**，页面状态也变了。处理方式：**重跑一次同样的回读**，用页面现状判断，
不要从头重做整个流程。

## 10. 一次典型任务的骨架（脱敏模板）

```json
{"id": 1001, "method": "start", "params": {"headless": false, "browser": "chrome"}}
```

```json
{"id": 1001, "method": "commands", "params": {"stopOnError": false, "commands": [
  {"go_to_url": {"url": "https://work.weixin.qq.com/wework_admin/frame#profile"}},
  {"wait": {"seconds": 5}},
  {"execute_js": {"body": "return [].slice.call(document.querySelectorAll('.profile_enterprise_item')).map(function(e){var k=e.querySelector('.profile_stage_label');var v=e.querySelector('.profile_enterprise_item_main');return (k?k.innerText.trim():'?')+' = '+(v?v.innerText.trim().replace(/\\s+/g,' '):'(空)');});"}}
]}}
```

拿到现状之后，**一次只改一个字段**，改完立刻回读。带断言的写法（推荐）：

```json
{"id": 1001, "method": "commands",
 "params": {"stopOnError": false, "stopOnExpectFailure": true, "commands": [
  {"go_to_url": {"url": "https://work.weixin.qq.com/wework_admin/frame#profile/domain"}},
  {"wait_for_stable": {"selector": "body", "quietMs": 800, "timeoutSeconds": 15}},
  {"input_text_by_selector": {"selector": "#dsh_dom", "text": "<域名>"}},
  {"click_element_by_selector": {"selector": "a:has-text('绑定域名')", "mode": "mouse"},
   "expect": {"js": "document.body.innerText.indexOf('已绑定') >= 0", "truthy": true}},
  {"get_form_state": {}}
]}}
```

**为什么用 `expect`**：`click_element_by_selector` 回 `ok:true` 只说明动作发出去了，
`expect` 才断言「页面上真的出现『已绑定』」。断言没过时批次整体 `code:0`，
`data.expectFailed` 计数、`msg` 指出是哪一步，**命令本身不算失败**（`data.failed` 仍是 0），
`data.note` 会说明「命令都执行成功，是断言没过」—— 这正是「点了但没生效」的准确信号。

**等待的选择**（别死等 `wait {seconds:N}`）：

| 场景 | 用什么 |
| --- | --- |
| 点一下等它保存完，不知道要多久 | `wait_for_idle`（看「忙不忙」） |
| 表单 / 页面内容渲染完了要读值 | `wait_for_stable`（看「内容变没变」，顺带返回稳定后的文本） |
| 等弹窗全部消失 | `wait_for_count` + `max: 0` |
| 等某个元素出现 | `wait_for_element` |
| 等页面文本出现关键字 | `wait_for_text` |

批量里推荐的节奏是「**动作段 + 末尾回读**」：一个批次做几个动作，最后用一次 `execute_js`
或 `get_browser_state` 回读，下一次推理基于新结果决定后续。**不要在批次里塞几十步**。

不确定服务端有什么能力时，先自省再动手：

```shell
curl -s http://localhost:10049/playwright/methods      # 或 list_methods
curl -s http://localhost:10049/playwright/config
curl -s http://localhost:10049/playwright/tasks
```

## 11. 交付话术（收尾）

1. **给一张「字段 → 改前 → 改后」的表**，逐行写清楚这次动了哪些、哪些**故意没动**（例如联系电话留空）。
   用第 3.4 节的那段 JS 回读，**以回读结果为准**，不要凭「我记得点过了」写。
2. **明确交接没做的部分**：本次没被点名的字段一律没碰；联系电话按用户要求留空。
3. **提醒不可逆项**：
   - 企业简称的免费改名次数**已经用掉了**，之后再改要走认证；
   - 企业域名绑定后该域名的收信指向企业微信邮箱。
4. **提醒人去客户端核对**：企业名片在网页后台点不动（提示「在桌面端完善」），
   要完善得在企业微信客户端里做。
5. **提醒清理**：`logs/trace/**`、`logs/agent/**`、`data/<id>/` 里的截图与结构化文本
   都含企业信息且**不会自动清理**：

```json
{"method":"cleanup","params":{"scope":"all","olderThanHours":24,"dryRun":false}}
{"method":"cleanup","params":{"scope":"data","keepLatest":3,"dryRun":false}}
```

   （**`dryRun` 默认是 `true`，只预演不删**；要真删必须显式传 `false`。）
6. **最后关任务**：`close`（关掉这个任务的页签）或 `shutdown`（关掉全部任务与共享浏览器，
   服务进程不退出）。**规范做法是「先 `close` 任务，再停服务」**，否则强杀服务会留下占着
   profile 目录的孤儿浏览器，下一次 `start` 可能卡在启动超时。

## 12. 脱敏约定

本文所有示例都是占位符：`<企业全称>`、`<18 位统一社会信用代码>`、`<企业简称>`、
`<省><市><区><详细地址>`、`<域名>`、`<企业ID>`、`<管理员手机号>`。

- **不要把真实值写回技能文档**（真实公司名、统一社会信用代码、企业地址、域名、企业 ID、
  管理员手机号、本机绝对路径）。
- 给用户看的过程记录里，企业 ID、手机号、统一社会信用代码**只留后 4 位或直接省略**。
- 截图与结构化文本落在服务端 `data/<id>/` 下，含企业信息，**任务结束记得清理**（见 11.5）。
- 引用其它技能用**仓库相对路径**（如 `skills/wecom-mail-domain`），不要写本机绝对路径。
