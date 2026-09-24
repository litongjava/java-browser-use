---
name: cnipa-trademark-register
description: 用 deepseek-browser-use 在中国商标网（toas.sbj.cnipa.gov.cn）把一件文字商标的注册申请填到「预览」的实操手册：先切 Firefox 引擎（Chromium／本机 Chrome／Edge 在统一身份认证页会白屏，实测只有 Playwright 1.53 + Firefox 139 能渲染登录表单），登录与验证码交给人，然后沿「申请人信息 → 商标声明 → 共同申请信息 → 优先权信息 → 商品 → 商标图样」六步向导逐项填写并暂存，商品按类别检索勾选（每类控制在 10 项内避免超项加收），图样是 jpg 且像素 400×400–1500×1500，最后停在预览页由人确认、提交、缴费。同时给出该站点（ant-design + Vue SPA）的交互坑与写法：点击可操作性超时要用 JS 派发事件、下拉是虚拟列表要先打字过滤、隐藏的 file input 不在快照里、暂存／预览有二次确认弹窗、DOM 里的值与框架模型可能不同步所以必须以站点自己的预览页为准。文内数据全部脱敏，替换占位符即可复用。
whenToUse: 需要在国家知识产权局商标网上申请系统做商标注册（或续展、变更、异议等同类网申），或需要在 ant-design + Vue 的长表单向导类站点（分步、自动暂存、级联地址、虚拟列表下拉、隐藏上传框）上稳定填表时。
---

# 中国商标网：文字商标注册申请（实操手册）

一句话流程：**切 Firefox → 人登录 → 走到注册申请表单 → 六步向导填到预览 → 交人提交缴费**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录，不是商标法指南。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这个站点上「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：填完六步、在预览页核对通过即停。**提交、缴费、最终确认由人做**，智能体不要点「提交」。
- **人来做的部分**：登录、短信/图形验证码、申请人承诺的确认、提交与缴费。
- **不做的事**：不绕过验证码、不批量灌水、不代签承诺。填的必须是真实申请数据。
- **要提醒用户的事**：申请表开头的「申请人承诺」弹窗是法律性质的诚信承诺（恶意注册、虚假材料属失信行为），
  必须由人知悉后再点「确定」；如果你代点了，要在交付说明里写明。

## 1. 开工前

### 1.1 服务

```json
POST http://localhost:10049/playwright/command
{"id": 1001, "method": "start", "params": {"headless": false, "browser": "firefox"}}
```

- `browser: "firefox"` 是**必须**的，理由见 1.2。也可以用服务端配置 `browser.engine=firefox`（等价）。
- `headless: false`：这个站点的登录必须由人在真实窗口里完成，有头窗口是前提。
- 开工前确认健康检查通过；站点**开放时间 08:00–22:00**，夜里跑会连表单都打不开。
- **`start` 可能第一次慢**：有头 Firefox 启动实测偶发「第一次等满启动超时（默认 60 秒）、服务自动重试后
  2 秒起来」。所以 `start` 请给足超时（客户端至少 150 秒），**看到第一次卡住不要立刻重发** ——
  同一个 id 重复 `start` 会直接被拒。若两次都失败，多半是上一次服务被强杀留下了孤儿浏览器占着
  profile：先结束残留的 firefox 进程再重启服务（详见主技能第 32 条）。
- **停服务前先 `close` 任务**：关掉最后一个任务时浏览器会一起退出，不会留下孤儿。

### 1.2 为什么必须是 Firefox

统一身份认证（`sso.cnipa.gov.cn`）的 SPA 带开发者工具检测（disable-devtool 的 Performance 检测器）。
2026-09-22 用同一脚本、全新上下文从官网入口实测 135 秒：

| 浏览器 | 结果 |
| --- | --- |
| 内嵌 Chromium 153 / 本机 Google Chrome 153 | 白屏，`/login` 先 412 后 400，正文为空、输入框 0 个 |
| Microsoft Edge 145 | 同上 |
| Firefox 155（Playwright 1.63） | 同上 |
| WebKit 26.6 | 同上 |
| **Playwright 1.53 + Firefox 139** | **正常渲染登录表单**（`/am/#/login`，200） |

结论：**先切 Firefox 再开页面**，别在 Chromium 系上反复重试浪费时间。若切了 Firefox 仍白屏，按主技能
第 29 条记录最终 URL、HTTP 状态与控制台错误，再决定下一步，不要凭猜归因。

**坑：`browser: "firefox"` 不一定真的生效——老版本的发布包会忽略它。** 实测（2026-09-24）
同一台机器上跑着两个服务实例：一个是**旧发布 jar**（`start` 只认 headless，**不读 `browser` 参数**），
一个是从工作区源码起的开发态服务。两者都回 `ok:true`，但旧 jar 仍然用**内嵌 Chromium** 打开页面
→ 瑞数直接把页面打成白屏。**判断方法（按顺序，先看回执再看页面）**：

1. `start` 回执现在直接给 `data.requestedBrowser` / `data.effectiveBrowser` / `data.engineHonored`，
   **`engineHonored:false` 就是「参数被忽略了」**，还会带一句 `engineWarning` 说明；
2. `get_config` 看服务端认的 `engine` / `configuredType`（也能看到解析后的 `profileDir`）；
3. 回读页面特征：

```js
return { ua: navigator.userAgent, webdriver: navigator.webdriver,
         chrome: typeof window.chrome, buildID: navigator.buildID };   // 139.0 / false / undefined / 有值 = 真 Firefox
```

`navigator.webdriver` 必须是 **false**（服务已注入 `dom.webdriver.enabled=false` +
`addInitScript` 抹掉 `navigator.webdriver`），`typeof window.chrome === 'undefined'` 才是 Firefox。
**要 Firefox 就必须从当前源码起服务**（`mvn spring-boot:run`），或确认发布包版本已支持 `browser` 参数。

**profile 目录默认按端口分开**（`shared-<端口>`）：同一台机器上跑多个实例时各自一份 profile，
不会互相抢锁——但**换了端口就等于换了登录态**，要复用已登录的会话就显式配 `browser.profileDir`
指到同一个目录。用 `list_tasks` 可以随时看到活着的任务与共享浏览器的 profile 目录。

### 1.3 开工前必须问清的字段

一次问全，避免填到一半停下来：

| 组 | 字段 |
| --- | --- |
| 申请人 | 申请人类型（法人/自然人）、名称、身份证明文件名称（如营业执照）、证件号码、证件 PDF |
| 地址 | 申请人地址三级区划 + 详细地址 + 邮编；国内申请人联系地址三级区划 + 详细地址 + 邮编（**两个邮编别混**） |
| 联系人 | 姓名、手机号 |
| 商标 | 类别（可多选，每类一件申请、一份官费）、每类商品/服务项、商标名称、商标说明、图样 |
| 偏好 | 是否要电子送达、是否需要指定颜色、是否有优先权/共同申请人 |

### 1.4 留档（排查用）

- 服务端：`logs/trace/<yyyyMMdd>/`（`steps.log` 时间线、`calls.jsonl` 逐条 JSON、`NNNNNN-<任务id>-<方法>.json` 完整请求响应、`uploads.log` 上传记录）。
- 客户端：用仓库里的 `scripts/trace/browse.ps1` 按序号成对落盘 `NNN.req.json` / `NNN.res.json`，
  **请求在发出前就落盘**，连「服务没起来、请求没发出去」也看得见。跨平台或要写进脚本时用
  `client/dsb.py`（同一套留档格式与脱敏规则，退出码区分传输错/业务失败/用法错）。
- 服务端日志**默认脱敏**（手机号、18 位证件号/统一社会信用代码、邮箱、长数字 → `***`，可用
  `browser.trace.redact` 追加公司名、商标名这类自定义规则），但这是**尽力而为**：姓名、门牌号这类
  认不出来的个人信息不会被掩掉，而且两份日志**都不会自动清理**。任务结束提醒用户清理。
- 上传的临时文件（图样、证件 PDF）落在服务端的暂存目录（默认 `<启动目录>/upload`，`start` 回执里
  的 `data.browser.upload.dir` 能看到实际路径），同样**不会自动清理**；不需要了用
  `DELETE /playwright/upload?name=<文件名>` 或直接删目录。

### 1.5 整站维护怎么判断、怎么等到恢复

这个站点会**按公告整站停机**（升级改版、系统维护），表现是**所有业务 URL 都 302 到一张维护图**：

```
https://sbj.cnipa.gov.cn/index.html                     -> 302 -> https://tzwh.sbj.cnipa.gov.cn/2026/maintain2026.png
.../onlineApplication/applyRegister/registerApply?...   -> 302 -> 同一张图
```

判断与应对：

- **别把维护当成「站点把我拦了」或「选择器失效」**：先 `go_to_url` 一次首页看最终 URL 是不是那张
  维护图（`get_page_snapshot` 的 `data.url`、`document.contentType === 'image/png'` 就能确认），
  是维护就停下来等，别反复重试、别改脚本。
- **公告页本身也被重定向**（`/notice/...html` 一起跳维护图），所以拿不到恢复时间时，用
  **浏览器截图 + 本地 OCR** 读那张维护图：图里就是公告正文（标题、发布时间、暂停时段）。
  Windows 自带 OCR 支持中文（`zh-Hans-CN`），实测可读：
  `set_viewport` 放大到 1500×1500 → `screenshot`（默认落盘到 `data/<id>/shot-N.png`，不会把
  base64 灌进上下文）→ 用 PowerShell 的 `Windows.Media.Ocr` 识别（大字号中文识别率明显更好，
  年份这类数字仍可能读错，用发布时间与常识校正）。
- **恢复探测**：带 Firefox UA 定时请求首页，**不再 302 到维护图**就是恢复了
  （`curl -A "<firefox UA>" -o NUL -w "%{http_code}|%{redirect_url}"`）。注意直接请求 `toas.*`
  域名会被 CDN 以 `Ws-Action: bot` 判成机器人返回 403，**用首页做探针更可靠**。
- 维护窗口通常只有 1 小时量级（如「20:00 至 21:00 暂停服务」），**别在这期间改方案或重放旧载荷**；
  恢复后先按第 7 节的纪律回读一次表单，再继续。

## 2. 登录：交给人

```json
{"id": 1001, "method": "commands", "params": {"stopOnError": false, "commands": [
  {"go_to_url": {"url": "https://sbj.cnipa.gov.cn/index.html"}},
  {"wait": {"seconds": 3}}
]}}
```

然后**请人在弹出的 Firefox 窗口里登录**（也可以用 `request_human_input` / `submit_human_input` /
`get_human_input` 这套人机协同命令来要验证码）。登录态会长期留在共享 profile 里，**换浏览器或换引擎等于
换一套登录态**，下次还要重登。

登录成功的判据：页面上出现账号主体名称、左侧菜单出现「我的账户」。不要靠标题判断。

## 3. 走到注册申请表单

路径：首页 →「商标申请业务」→「商标注册申请」→「注册申请」；或直接打开
`https://toas.sbj.cnipa.gov.cn/toas-extra-prod/toas-ui/onlineApplication/applyRegister/registerApply?code=toas_00501`。

- 首次打开会弹**「申请人承诺」**，点「确定」才能进表单（见第 0 节）。
- 表单页 URL 后面带 `&back=1` 表示这是从草稿/其它入口回来的编辑态。

### 菜单点不动怎么办（这个站点的高频问题）

左侧是 jeecg 风格菜单，层级是 `li.jeecg-menu-submenu > div.jeecg-menu-submenu-title`，叶子项是 `li`。
Playwright 的真实点击经常等满超时（`[ACTION_TIMEOUT] 等待元素可操作超时`），脚本里用**JS 派发完整事件序列**
最稳：

```js
// 展开/点击一个菜单项：先按文字找到 span，再往上找到真正的可点容器
const el = Array.from(document.querySelectorAll('span'))
  .find(e => e.children.length === 0 && e.textContent.trim() === '我的账户');
const target = el.closest('.jeecg-menu-submenu-title') || el.closest('li') || el.parentElement;
['mousedown','mouseup','click'].forEach(k =>
  target.dispatchEvent(new MouseEvent(k, {bubbles: true, cancelable: true, view: window})));
```

要点：

- **展开是逐级的**：「我的账户」→ 展开出「申请管理」→ 再展开出「未提交 / 已提交」。一次只点一级，
  每级之间等 2–3 秒再回读菜单文本确认展开了。
- 菜单点了没反应时**不要死磕**：直接 `go_to_url` 到已知 URL 更快。常用两个：
  - 注册申请表单：`.../onlineApplication/applyRegister/registerApply?code=toas_00501`
  - 未提交列表：`.../toasMyAccount/applyManage/toasMyAccountApply?code=toas_0051701`

## 4. 六步向导

> **先判断你在哪一代 UI 上**（2026-09 起站点升级过，两代都在线上）：
>
> | | 旧版：分步向导 | 新版：单页表单 |
> | --- | --- | --- |
> | 怎么认 | 有 `.ant-steps-item-active`，一次只渲染一步 | 顶部有 `1.数据填写 / 2.预览 / 3.提交` 文字，下面是一条「申请人信息 / 商标声明 / 共同申请信息 / 优先权信息 / 商品 / 商标图样」区块导航条，**一次只显示一块** |
> | 怎么走 | 「下一步」= 换步 | 「下一步」= 换**区块**，并**自动暂存该区块**（右下角弹「××暂存成功…未提交」） |
> | 末步按钮 | 第 6 步出现「预览」 | 走到「商标图样」区块后按钮变「预览」 |
>
> 新版的具体节奏见 **4.2.1**；4.1–4.6 的字段规则两代通用。

### 4.1 总览

| 步 | 名称 | 关键点 |
| --- | --- | --- |
| 1 | 申请人信息 | 类型、证件名称/号码、证件 PDF、两级地址+邮编、联系人 |
| 2 | 商标声明 | 声音/三维/颜色组合一律「否」、商标类型「普通商标」 |
| 3 | 共同申请信息 | 「是否共同申请」=否 |
| 4 | 优先权信息 | 「优先权声明」=无 |
| 5 | 商品 | 选类别 → 查询 → 勾选 → 添加所选商品 |
| 6 | 商标图样 | 上传 jpg、商标名称、是否肖像、商标说明；**只有这一步有「预览」按钮** |

### 4.2 每一步的节奏（重要）

「下一步」会触发该步的**异步暂存**，点太快会被吞掉。稳定写法：

1. 点「下一步」（JS 点击即可）；
2. 等 **3.5–5 秒**；
3. 回读 `.ant-steps-item-active` 的文本，**确认真的到了下一步**；
4. 没动就再点一次。

```js
// 回读当前步
(document.querySelector('.ant-steps-item-active') || {}).innerText.replace(/\n+/g, ' ')
```

一次请求里连点 5 次「下一步」通常会只前进 3–4 步，**必须回读确认**，不要假设点了几次就走了几步。

### 4.2.1 新版单页表单：区块推进 + 自动暂存（2026-09 实测）

一次只显示一个区块，**看不见的区块里的控件是不可见元素**——所以「添加」「批量删除」「费用试算」
「商品导入」这些商品区块的按钮，在别的区块上回读都是 `vis=false`；**不要以为它们不存在**，
先推区块再找按钮。

```js
// 区块推进:一路点到「添加」可见 = 已进入商品区块
const btn = (t) => [...document.querySelectorAll('button')]
  .find(b => b.textContent.replace(/\s+/g,'') === t && vis(b));
for (let i = 0; i < 7 && !btn('添加'); i++) { jsClick(btn('下一步')); await sleep(5500); }
```

要点：

1. **每次「下一步」= 一次异步暂存**（提示语会写明暂存了哪个区块）。间隔给足 **5–6 秒**，
   太密会撞上服务端的 `不允许重复提交，请稍候再试`（见下）。
2. 区块顺序固定：申请人信息 → 商标声明 → 共同申请信息 → 优先权信息 → 商品 → 商标图样。
   实测从申请人信息推到商品区块要 **4 次**「下一步」。
3. 走完「商标图样」区块后「下一步」消失、出现 **「预览」**；点「预览」也会先暂存该区块。
4. **`不允许重复提交，请稍候再试`**：点「预览」/「暂存」太频繁会返回这句提示且**页面不动**。
   等 **30–60 秒**再点一次即可成功（不是配置问题、不用重启服务）。
5. 「预览」成功后：进度条 `2.预览[active]`，按钮变成 **修改 / 提交 / 继续申请**——
   **到这里草稿已经存好了**，提交与缴费留给用户。
6. 中途 **不要刷新页面**：刷新会丢草稿上下文（见 4.6 最后一条）。

**区块级暂存的一个副作用**：级联下拉（申请人地址 / 联系地址）在区块重渲染后可能被清空，
**详细地址会被级联重新覆盖成「省市区」**。所以顺序永远是：**先跑完级联 → 再补详细地址/邮编/联系人**，
并且在「预览」页回读一遍（预览页把同一份数据渲染两遍，回读时会出现两个相同值，属正常）。

### 4.2.2 一次做多件申请：「继续申请」能带什么、不能带什么

「预览」页的 **继续申请** 用来开下一件（同申请人换类别、或换商标），弹窗是：

```
请选择本次申请已填写的信息带入下一个申请：
  全选 | 申请人或共同申请人信息 | 商品信息 | 商标图样        取 消  保 存
```

实测（2026-09）：

| 勾选项 | 实际效果 |
| --- | --- |
| 申请人或共同申请人信息 | 带**申请人名称、证件名称/号码、证件 PDF、两级地址详细地址、邮编、联系人**；**不带三级级联下拉** |
| 商标图样 | 带图样文件 + **商标名称 + 商标说明**（换商标时必须**取消勾选**，否则会带着上一件的图） |
| 商品信息 | 带上一件的商品。**换类别时不要勾**（商品是按类选的，带过来就是错的） |

- 弹窗里的勾选**是输入框，用 `input.click()` 切换**，切完回读 `input.checked` 确认。
- 保存按钮文字是 `保 存`（中间有空格），匹配时先 `replace(/\s+/g,'')`。
- 带入后**必做两件事**：① 重跑两个地址的三级级联；② 级联后重新补详细地址/邮编/联系人。
- 「继续申请」带过来的是**页面内存里的状态**，还没落库；**此时刷新 = 全丢**。
  要它变成草稿，必须走完区块点「预览」（或点「暂存」）。

### 4.3 申请人信息（第 1 步）

- **申请人类型**：`法人或其他组织` / 自然人。选错会让后面「身份证明文件名称」的可选项变化。
- **身份证明文件名称**：如 `营业执照`；**号码**：法人填 18 位统一社会信用代码，自然人填身份证号。
- **身份证明文件（中文）**：PDF，≤5M，单文件；文件名建议只用汉字/字母/数字（见 4.6 的上传坑）。
- **申请人地址 / 国内申请人联系地址**：都是**三级级联下拉 + 详细地址 textarea + 邮编** 的组合，
  级联选中后会自动把区划写进详细地址，**要再补上街道门牌**。
- 两个邮编分别对应两个地址，填完回读确认没有互相串（字段 id 不同，见 8.2）。
- **联系人**：名称 + 手机号。

### 4.4 商品（第 5 步）

1. 点「添加」打开「添加所选商品」对话框（内含关键字、商品编码、类别三个输入 + 查询按钮）。
   **新版 UI 下要先走到「商品」区块**，「添加」才可见（见 4.2.1）；打开过程中常弹「用户服务协议」。
2. **类别下拉是虚拟列表**（45 个类只渲染前 10 个），必须**先打字过滤再点选**（写法见 9.2）。
3. 点「查询」。列表是分页/虚拟的，**按关键字检索比翻页可靠得多**：
   **一个商品名搜一次 → 精确匹配行 → 勾一行**，比「查询整类再翻页找 10 项」稳得多
   （整类查询会返回 2000+ 行）。**搜索是异步的，要轮询到同名行出现**（见 9.2.1）。
4. 勾选目标项：**一次点击只勾一行**，并用页面上的「已勾选 N 项」**校验增量 = 1**（见 9.2.1）。
5. 点「添加所选商品」把它加进申请；对话框不会自动关，用右上角关闭
   （**这个 × 要真实鼠标点**，见 9.6）。
6. 回读商品表确认条数：**10 项就是 10 行，出现同名两行 = 勾重了**，按 9.2.1 清掉重来。

**项数控制**：一类内 10 项（含）以内不额外加收，超过要按项加收官费。**默认每类挑 10 项**，
要加要减先问用户。

**一套可复用的组合（教育 / 软件 / 技术服务类企业，三件申请）**：需要「同一套项目在多个商标上重复申请」时，
照这张表选即可，省掉每次重新挑项。第 9 类偏「可下载的软件与电子出版物」，第 41 类偏「培训与出版服务」，
第 42 类全部落在 4220（软件与信息技术服务）——**同组内的项才算「类似商品」，跨组选满 10 项不会互相增强保护**，
所以每类都尽量集中在一两个类似组里。

| 第 9 类（0901 为主 + 0910） | 第 41 类（4101/4102/4104/4105） | 第 42 类（全部 4220） |
| --- | --- | --- |
| 计算机软件（已录制） | 培训 | 计算机编程 |
| 已录制的或可下载的计算机软件平台 | 教育 | 计算机软件设计 |
| 计算机软件应用程序（可下载） | 辅导（培训） | 计算机软件更新 |
| 电子出版物（可下载） | 安排和组织培训班 | 计算机软件维护 |
| 可下载的音乐文件 | 提供教育信息 | 计算机系统设计 |
| 可下载的影像文件 | 提供不可下载的在线电子出版物 | 为他人创建和维护网站 |
| 可下载的计算机应用软件 | 电子书籍和杂志的在线出版 | 计算机软件安装 |
| 可下载的计算机游戏软件 | 书籍出版 | 计算机程序和数据的数据转换（非有形转换） |
| 计算机程序（可下载软件） | 组织教育或娱乐竞赛 | 计算机软件咨询 |
| 教学仪器（0910） | 提供不可下载的在线视频 | 软件即服务（SaaS） |

### 4.5 商标图样（第 6 步）

- **图样**：jpg、<2M、像素 400×400–1500×1500、只能一个文件、文件名建议只用汉字/字母/数字。
- **商标名称**：字符集限制较严（汉字/字母/数字/`-`/`，`/`；`/空白/`。`/`.`/`'`/`‘’`/`（）`）。
- **是否使用肖像作为商标**：一般「否」。
- **商标说明**：**必填**。文字商标写清「由哪些汉字构成、字体、有无含义」，例如：
  `该商标为文字商标，由汉字<商标名称>构成，字体为黑体，无特定含义。`
- **其他说明文件**：选填，留空即可。

### 4.6 上传文件（这个站点最坑的一环）

三处上传：身份证明文件（.pdf）、商标图样（.jpg）、其他说明文件（.pdf），对应的 file input 是：

| 用途 | 选择器 |
| --- | --- |
| 商标图样（jpg） | `#form_item_imageAttJson` |
| 身份证明文件（pdf） | `#form_item_idetCertCnAttJson` |
| 其他说明文件（pdf） | `#form_item_othAttJson` |

它们的 `input[type=file]` 都是 `display:none`，**隐藏元素不进快照、拿不到索引**。用**选择器**直接传即可，
`upload_file` 不需要元素可见，也不必再想办法把它显示出来：

```json
{"id":1001,"method":"upload_file",
 "params":{"selector":"#form_item_imageAttJson","path":"图样.jpg"}}
```

`path` 是**服务端**路径。智能体与浏览器不在同一台机器时（客户端-服务器模式），先把文件 POST 到服务端：

```bash
curl -F "file=@图样.jpg" http://<服务端>:10049/playwright/upload
# → {"data":{"filename":"图样.jpg","path":"<服务端暂存目录>/图样.jpg","relativePath":"图样.jpg",...}}
```

再把回执里的 `path`（或 `relativePath`）填给 `upload_file` 的 `path`。上传后回读该项文本：
出现「文件名」= 已进列表；出现「文件上传中」= 还在传。

> 历史写法：早期没有选择器支持时，得先用 `execute_js` 把隐藏的 file input 临时显示出来
> （`f.style.cssText='display:block !important;...'`）、取快照索引、再按索引 `upload_file`。
> **现在不要再用这条路**：改动页面样式可能触发站点的表单校验差异，而且多一次快照往返。

**坑：重复上传**。列表里已有一个文件时再传会弹「上传文件数量超出1个,无法上传」，并在列表里留下一个
**永远停在「文件上传中」的幽灵条目**，它会卡住表单校验（报「选择图样必填」）。
处理顺序：先点已有条目的删除按钮清掉 → 再传一次；**别用「再传一次」去覆盖**。

**坑：刷新页面会开一份新的空白申请**。表单页刷新/重新导航会丢掉当前草稿上下文，回到空白表单。
要回到草稿必须走 **我的账户 → 申请管理 → 未提交 → 编辑**（列表里点「编辑」会带 `&back=1` 打开草稿）。
草稿**自首次暂存起只保留 7 天**。

## 5. 图样怎么来（文字商标）

没有现成图样时，用 PowerShell + `System.Drawing` 画一张纯文字图，比截图/画图工具可控：

- 尺寸 **500×500**（落在 400–1500 之间）、白底黑字、居中、JPEG 质量 ~95；
- 字体按候选列表逐个试（黑体 SimHei、微软雅黑、宋体、楷体…），取第一个装得下的；
- **写完必须校验不是「豆腐块」**：用 `LockBits` 统计墨迹像素占比、墨迹包围盒、把图切成 2×2 分格
  统计各格墨迹量并做哈希——四格哈希全相同或墨迹占比异常（接近 0 或接近全黑）就说明字体没命中、
  画出来的是方框或缺字。

验收参考值（500×500 四字商标）：墨迹占比约 3%–8%，包围盒明显小于整图且居中，四个分格墨迹量互不相同。

## 6. 暂存 / 预览 / 提交

| 动作 | 会发生什么 |
| --- | --- |
| 每步「下一步」 | 静默暂存，右上角弹「<步骤名>暂存成功」 |
| 点「暂存」 | 弹**「暂存提醒」**对话框（含「7天内不再提示」等选项），必须点里面的「保存」才落盘；点完弹窗**不会自己关**，用关闭按钮收掉 |
| 点「预览」 | 可能先触发一次暂存；**没进预览页就再点一次**。成功标志：页面上出现「修改 / 提交 / 继续申请」三个按钮 |
| 「继续申请」 | 弹「请选择本次申请已填写的信息带入下一个申请」：可勾「申请人或共同申请人信息」「商品信息」「商标图样」。**换类别时不要勾「商品信息」**（商品是按类别的），勾前两个即可 |
| 「提交」 | **由人点** |

多类别做法：一件申请做完 → 预览页点「继续申请」→ 勾「申请人信息 + 商标图样」→ 保存 → 新申请已经带好
申请人资料和图样 → 只需换类别重选商品 → 再走到预览。**每类一件申请、一份官费**。

## 7. 校验纪律（决定成败的一条）

**以站点自己的预览页为准，不要以 DOM 里的值为准。**

- 用 `execute_js` 直接改 `input.value` **不会**进入框架（Vue）的模型，页面上看着填好了，
  预览页和提交内容里却是空的。表单字段一律用**真实输入**（`input_text_by_selector` / `input_text` /
  `type_text`，内部走 Playwright 的 fill/type 会派发正常事件）；原生 setter + `dispatchEvent` 只用于
  **搜索框**这类「只为触发前端过滤」的场景。走 `"mode":"js"` 时服务会在回执里标 `data.committed=false`，
  看到它就别指望这个值进得了预览页。
- 预览页把「详细地址」「商标说明」这类长文本渲染在 **input 里**，`innerText` 读不到，别据此判定「没填上」。
  用 `get_element_value`，或遍历 `input/textarea` 的 `value` 找关键字。
- **一次读回整屏表单**用 `get_form_state`（比逐个回读省得多），它直接给 `data.fields` 与 `data.errors`：

```json
{"id":1001,"method":"get_form_state","params":{"selector":"#form_item_tmDesc","includeHidden":true}}
```

  返回里每个字段带 `label`/`value`/`visible`/`disabled`/`required`/`invalid`/`error`，`data.errors`
  是「哪个字段、错在哪」的清单——比下面这段手写 JS 更省事（手写版适合要站点特有的类名时）：

```js
// 有校验错误的项（标签名 + 错误文案）
({bad: Array.from(document.querySelectorAll('.ant-form-item-has-error'))
        .map(i => (i.querySelector('label') || {}).innerText),
  why: Array.from(document.querySelectorAll('.ant-form-item-explain-error')).map(e => e.innerText)})
```

- **右上角提示可能是陈旧的**：`.ant-message-notice` 会一直挂着。判断当前这一步之前，先
  `document.querySelectorAll('.ant-message-notice').forEach(e => e.remove())` 清空，再做动作看新提示。
- **别读图**：`data.screenshot` 只是地址，读图极贵；「点击生效没有」用回执里的 `data.changed` 或
  `diff_dom_text` 判断。只有验证码/二维码这类文本表达不了时才取图（优先 `get_element_screenshot`）。

## 8. 关键字段定位（写法与坑）

### 8.1 别靠索引记字段

每次动作后索引全部重算，**回读要用 id/选择器**，不要用上一次快照的 `[index]`。

### 8.2 常用字段 id（该站点的表单控件带稳定 id）

| 字段 | 选择器 |
| --- | --- |
| 申请人名称 | `#form_item_applicantCnName` |
| 身份证明文件号码 | `#form_item_idetCertNo` |
| 申请人详细地址 | `#form_item_applicantCnAddr` |
| 申请人邮编 | `#form_item_applicantPostalCode` |
| 联系详细地址 | `#form_item_dmstcApplicantAddr` |
| 联系邮编 | `#form_item_dmstcApplicantPostalCode` |
| 联系人名称 / 电话 | `#form_item_contactName` / `#form_item_contactPhone` |
| 商标名称 / 商标说明 | `#form_item_tmName` / `#form_item_tmDesc` |
| 商品关键字 / 类别 | `#addUpdateForm_2_goodsCnName` / `#addUpdateForm_2_intlCls` |

> 这些 id 是站点自己生成的，版本升级可能变；**用之前先回读一次确认存在**，不要当永久契约。
> 同名的 id 在页面里可能出现多次（表单组件被复用），取第一个即可。

## 9. 该站点的通用坑与写法（ant-design + Vue SPA）

### 9.1 点击可操作性超时

`click_element_by_index` / `click_element_by_selector` 在这类站点上经常返回
`[ACTION_TIMEOUT] 等待元素可操作超时`（默认 5 秒），**这不代表元素不存在**。

**首选做法：传 `mode`，让服务自己降级**（比手写 JS 省一步，而且回执里会写明用了哪种方式）：

```json
{"id":1001,"method":"click_element_by_selector",
 "params":{"selector":"button:has-text('下一步')","mode":"auto"}}
```

- `mode` 不传或 `auto`：**原生 → 真实鼠标 → JS 派发**三档依次尝试，回执给 `data.mode`（实际用上的那档）与 `data.fallbackReason`（降级原因）；
- `mode:"mouse"`：**直接用真实鼠标点元素中心**，不做可操作性检查 —— 元素带动画/一直不稳定时这是最有效的一档（实测 ant 弹窗按钮就靠它）；
- `mode:"js"`：直接派发事件，不做可操作性检查（已知这类站点点不动时的确定写法）；
- `timeoutMs` 可以按次调大超时（例如 `"timeoutMs":15000`），比全局改 `browser.action.timeoutMs` 更局部。

回执里 `data.effective` 表示**这次点击有没有真的改变页面**，`data.coveredBy` 说明目标中心点上实际命中的是谁
（被协议层/遮罩挡住时先 `close_modal` 关掉遮挡物再点，而不是反复点）。

**其次：用 `execute_js` 自己派发**（需要精确控制事件序列时，例如左侧菜单的多级 dispatch 链）：

```js
const b = Array.from(document.querySelectorAll('button'))
  .find(x => x.textContent.replace(/\s+/g, '') === '下一步');
if (b) b.click();
```

文字里带空格的按钮（「保 存」「预 览」）用 `replace(/\s+/g,'')` 归一化再比。

> 看到回执里 `mode=js` 要记住：**这一步不是真实鼠标交互**。点击本身通常有效（页面状态会变），
> 但「提交」「缴费」这类关键动作建议再用 `get_form_state` / 预览页确认一次结果。

### 9.2 下拉是虚拟列表：先打字过滤，再点选项

45 个类别的下拉只渲染前 10 项，直接找「42」找不到。**先把关键字塞进 select 的搜索 input 触发过滤**：

```js
// 1) 点 .ant-select-selector 打开下拉
const sel = Array.from(document.querySelectorAll('.ant-select'))
  .find(s => s.querySelector('#addUpdateForm_2_intlCls'));
['mousedown','mouseup','click'].forEach(k =>
  sel.querySelector('.ant-select-selector')
     .dispatchEvent(new MouseEvent(k, {bubbles:true, cancelable:true, view:window})));
// 2) 往它的 input 里塞过滤词（原生 setter + input 事件）
const inp = document.querySelector('#addUpdateForm_2_intlCls');
const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
set.call(inp, '42');
inp.dispatchEvent(new Event('input', {bubbles:true}));
// 3) 选项过滤成只剩「42」，再点它
```

同一手法也用于**三级地址级联**：级联下拉的搜索是按**名称**过滤的（省/市/区名），
塞行政区划代码通常搜不到（返回「暂无数据」）。

**坑：`ant-select-dropdown-hidden` 不能用来判断「哪个下拉是打开着的」**。ant-design-vue 会把
**所有**下拉都留在 DOM 里，很多并不带 `-hidden` 类（只是 `display:none` 或尺寸为 0），
于是「找最后一个可见下拉」会命中**别的字段的陈旧下拉**，点上去毫无反应，
还会把已经选好的上一级**清掉**。可靠判据只有**几何**：

```js
const openDropdown = () => {
  const ds = [...document.querySelectorAll('.ant-select-dropdown')].filter(d => {
    const r = d.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight
        && r.right > 0 && r.left < innerWidth;      // 真的在视口里 = 当前打开的那个
  });
  return ds[ds.length - 1] || null;                 // 最靠后的通常是刚打开的
};
// 选项文本可能带编码前缀(如 "310000上海市"),比较前先归一化
const norm = s => String(s).replace(/\s+/g, '').replace(/^\d{4,6}/, '');
const findOption = w => {
  const d = openDropdown(); if (!d) return null;
  const os = [...d.querySelectorAll('.ant-select-item-option')];
  return os.find(x => norm(x.textContent) === w)
      || os.find(x => norm(x.textContent).includes(w)) || null;
};
```

**坑：三级地址的三个 select 共用同一个 id**（如 `form_item_applicantRegionCode`），
`aria-controls` 也相同——**不能按 id/属性定位到具体某一级**，只能按**该字段内 combobox 的序号**
（`item.querySelectorAll('input[role=combobox]')[i]`）定位。级联要点：
① 每级之间等 **2 秒**（下一级的数据是上一级选完后才拉的，急了就是「暂无数据」）；
② 一级失败就**整段重跑**（不要只补最后一级，会把上一级清掉）；
③ 级联完成后**必须重新补详细地址**（见 4.2.1）。

### 9.2.1 商品对话框的列表怎么认（别按行数认表）

「添加所选商品」对话框里同时挂着好几张表：左侧类别说明表有 **45 行**、结果表可能只有 **1 行**。
按「行数最多的表」取结果表会在搜索命中很少时**取到类别表**，于是永远「找不到行」。判据用**列数**：

```js
const resultTable = () => {
  const m = modal(); if (!m) return null;
  const cands = [...m.querySelectorAll('table')]
    .filter(t => [...t.querySelectorAll('tbody tr')].some(r => r.querySelectorAll('td').length >= 5));
  return cands.sort((a,b) => b.querySelectorAll('tbody tr').length - a.querySelectorAll('tbody tr').length)[0] || null;
};
```

结果表每行 5 列：`勾选框 | 类别 | 类似群 | 商品编码 | 商品名称`（**名称在第 5 列 = `tds[4]`**）。

**坑：搜索结果是异步刷新的，点「查询」后立刻读会读到上一次的结果**——表现为「行没找到，
但近似结果里明明有」。正确做法是**先等内容稳定、再读**，或**轮询到出现同名行为止**：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"click_element_by_selector":{"selector":"button:has-text('查询')","mode":"mouse"}},
  {"wait_for_stable":{"selector":".vxe-table--body-wrapper","quietMs":900,"timeoutSeconds":15}}
]}}
```

`wait_for_stable` 的 `data.text` 就是稳定后的表格文本，直接读它即可；等「结果行出现」用
`wait_for_count`（`{"selector":".vxe-table--body tbody tr","min":1}`）。要手写轮询的话：

```js
let hit = null;
for (let t = 0; t < 18 && !hit; t++) {
  await sleep(1000);
  hit = rows().find(r => nameOf(r) === w);      // nameOf = tds[4].innerText.trim()
}
```

**坑：勾选必须一次点击只勾一行，并且用「已勾选 N 项」计数校验**。给 `<label>` 同时派发
`mousedown/mouseup/click` **又**调 `label.click()` 会变成两次切换，实测把 6 项勾成了 12 项
（后面「添加所选商品」就**双份入库**）。稳定写法：**只调原生 `input.click()`**，然后校验计数增量：

```js
const before = counter();                       // counter = 页面上的「已勾选N项」
cb.click(); await sleep(800);
const after = counter();
if (after - before !== 1) { /* 增量不是 1 就修正/重试，别继续往下走 */ }
```

**已重复入库怎么清**：商品表每行的操作列是 `<a>删除</a>`，**但它要求先勾选该行**（否则弹
「请勾选商品」）。正确姿势：勾选所有行 → 点「批量删除」→ 确认框点「确定」（**确认框必须用
真实鼠标点，见 9.6**）。

### 9.3 中文输入不要用 `send_keys`

`send_keys` 只认**键名**，塞中文会得到 `send_keys 失败：Unknown key: "..."`。
中文一律用 `input_text_by_selector`（按选择器）或 `input_text` / `type_text`（按索引）。

### 9.4 不可见 / 0×0 的 textarea

`input_text_by_selector` 对 `display:none` 或 `offsetWidth===0` 的元素会返回
`[ELEMENT_HIDDEN] 元素当前不可见`。分步向导里**非当前步的字段都是隐藏的**，所以：

- 要填哪个字段，先**切到它所在的那一步**（这是首选：走真实交互，值会进框架模型）；
- 实在没法切过去时，用 `"mode":"js"` 强制设值——**但回执会给 `data.committed=false`**，
  这种值在预览页/提交校验里可能被判为空，关键字段不要用它；
- 回读值不受可见性限制，隐藏字段的 `value` 照样能读到（`get_form_state` 加 `includeHidden: true` 更省事）。

### 9.5 勾选后要等一拍

`check_element_by_index` / JS 点 checkbox 之后，**框架状态更新是异步的**：
立刻回读会读到「未勾选」，等 1–2 秒再读才对。用 `已勾选 N 项` 这类页面计数做交叉验证。

### 9.6 弹窗类操作

- 「暂存提醒」「继续申请」这类弹窗要**先判断有没有弹**再决定点哪个按钮，不要盲点；
- 弹窗点完常**不自动关闭**，用右上角关闭按钮收掉再继续；
- 弹窗内容用 `.ant-modal-wrap` 过滤 `display !== 'none' && offsetHeight > 100` 来定位当前真正可见的那个。

**坑（本任务最大的一个）：有些弹窗按钮对 JS 派发的 click 完全无响应**。典型是
**`Modal.confirm` 的「确定/取消」** 和 **「添加所选商品」对话框右上角的 ×**：
用 `dispatchEvent(new MouseEvent('click'))`、甚至元素原生 `el.click()`，
回执都正常（无报错）、按钮看起来也被点了，**但弹窗不关、操作不生效**。

后果会**层层累积**：反复点「批量删除」→ 每次都弹一个确认框且都不消失，
最后页面上叠了 **16 个** `ant-modal-wrap`，之后所有「取第一个可见弹窗」的逻辑
都在操作**最老的那个陈旧弹窗**，于是越点越乱。

两条出路（按顺序试）：

1. **`close_modal`（现在首选，服务端已内置真实鼠标点击）**：

```json
{"id":1001,"method":"close_modal","params":{"which":"top","button":"取消"}}
```

   回执里的 `closed` 是**校验过的**结果（点完重新数了一遍弹窗数量）：`closed:false` 说明点了但数量没减少，
   这时用 `get_modals` 拿 `closePoint` / `buttonPoints` 的坐标，再 `mouse_click` 那个坐标：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_modals":{}},
  {"mouse_click":{"x":1000,"y":204}}
]}}
```

   坐标取目标按钮的**视口中心**（`r.left + r.width/2`, `r.top + r.height/2`），
   坐标格式别读错：`Math.round(r.top)+','+Math.round(r.left)` 是 **top,left**（不是 left,top）。
2. **点击类命令的 `mode`**：`mode:"mouse"` 强制真实鼠标（不做可操作性检查，实测有效）；
   `mode:"auto"`（默认）现在是「原生 → 真实鼠标 → JS」三档，回执里 `data.mode` 是**实际用上的**那一档、
   `data.effective` 表示有没有真的改变页面。**看到 `mode:"js"` 就当没点成**，别只看 `ok:true`。

**收尾必做**：清掉残留弹窗——`close_modal` 传 `which:"all"` 一次收掉（它每关一个就重新数一次，
数量到 0 就停），或**从最后一个（最新）往前**手动点，并**每次回读弹窗数量**，直到 `0` 再继续下一步。
`get_modals` 的 `data.count` 就是当前可见弹窗数，`data.top` 是最后弹出来的那个。

**另一个会挡住一切的东西：「用户服务协议」/「申请人承诺」覆盖层**。它不是 `.ant-modal-wrap`，
选择器是 **`.agreement-container`**（内含「我接受」按钮），而「申请人承诺」是标题为
`申请人承诺` 的普通弹窗（按钮「确定」）。**它们会在添加商品、点「添加」、点「下一步」时反复弹**。
识别特征：`document.elementFromPoint(innerWidth/2, innerHeight/2)` 返回 `agreement-container`
——**此时你以为在点的按钮其实全被它挡着**（这正是「点了没反应」的另一大来源）。写法：

```js
const vis = el => { if (!el) return false; const cs = getComputedStyle(el), r = el.getBoundingClientRect();
  return cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0; };
// 每次关键动作前都先清一遍
const ag = [...document.querySelectorAll('.agreement-container')].filter(vis);
if (ag.length) {
  const acc = [...ag[0].querySelectorAll('button,a,div,span')]
    .find(x => x.textContent.replace(/\s+/g,'') === '我接受');
  if (acc) { jsClick(acc); await sleep(3500); }
}
```

实测「我接受」用 JS 派发 click **有效**（和 9.6 的确认框不同），可以放心用。

### 9.7 服务端的瞬时错误不要当成任务失败

`execute_js` 偶尔会返回 `Cannot invoke "com.microsoft.playwright.Request.method()"
because "request" is null`（网络观察钩子里的空指针）。**脚本其实已经跑完了**，
页面状态也变了。处理方式：**重跑一次同样的回读**，用页面现状判断，不要从头重做整个申请。

### 9.8 别用 JS 点站点的主菜单

顶部导航（我的账户 / 申请管理 / 待支付业务管理 …）是 `<li>` + Vue 事件、**没有 href**，
JS 派发 click 和真实鼠标点击**都可能不跳转**。要核对草稿列表时，
**不要在这上面耗时间**：区块暂存的提示语（「××暂存成功…未提交」）本身就是落库证据，
把「去 我的账户 → 申请管理 → 未提交 核对」写进交付话术交给用户即可。

## 10. 一次典型任务的骨架（脱敏模板）

```json
{"id": 1001, "method": "start", "params": {"headless": false, "browser": "firefox"}}
```
```json
{"id": 1001, "method": "commands", "params": {"stopOnError": false, "commands": [
  {"go_to_url": {"url": "https://toas.sbj.cnipa.gov.cn/toas-extra-prod/toas-ui/onlineApplication/applyRegister/registerApply?code=toas_00501"}},
  {"wait": {"seconds": 4}},
  {"execute_js": {"body": "const bad=Array.from(document.querySelectorAll('.ant-form-item-has-error')).map(i=>(i.querySelector('label')||{}).innerText);const a=document.querySelector('.ant-steps-item-active');return {step:a?a.innerText.replace(/\\n+/g,' '):'-',bad:bad};"}}
]}}
```

批量里推荐的节奏是「**动作段 + 末尾回读**」：一个批次里连做几个动作，最后用一次
`execute_js` 回读当前步/错误/计数，下一次推理基于新结果决定后续。不要在批次里塞几十步。

## 11. 交付话术（收尾）

1. 到 **我的账户 → 申请管理 → 未提交**，点「查询」刷新，确认**条数与业务类型**对得上（草稿按类别一件一条）；
   > 提示：这个菜单是纯 Vue 事件、没有 href，**自动化点不动很正常**（见 9.8）。别在这里耗时间，
   > 把核对动作交给用户；你的落库证据是每个区块的「××暂存成功」提示与「预览」页本身。
   >
   > **列表的日期筛选默认是「今天」**：昨天或更早的草稿在这个默认视图里**看不到**。要核对历史草稿先点
   > 「近三个月」（实测这一步之后 7 条草稿才全部出现）。别因为默认视图少了几条就断言「没保存成功」。
2. 告诉用户：**每类的流水号（脱敏后只留后 4 位）、类别、商品项数、官费口径（≤10 项不加收）**；
3. **多商标 × 多类别**时要给一张清单表，逐行写「商标名 / 类别 / 商品项数 / 状态（已到预览页=已暂存）」，
   并说明**每个商标每类都是独立一件、独立官费**；
4. 明确交接：**「已填到预览页，请你核对后提交并缴费」**，并提醒草稿 **7 天**过期；
5. 提醒用户清理 `logs/trace/**` 与 `logs/agent/**`（含证件号、手机号、地址，未脱敏），
   或用 `cleanup`（`{"scope":"trace","olderThanHours":24}`，**默认只预演**，要真删加 `"dryRun":false`）；
   截图攒得太多可以用 `{"scope":"data","keepLatest":3,"dryRun":false}` 只留每个任务最近几张。
6. **核对数量时以「已提交」+「未提交（近三个月）」两边加起来为准**：只查一边很容易得出
   「有两件没保存」的错误结论（本任务真的踩过：默认「今天」筛选下少了 2 件，其实都在）。

## 12. 脱敏约定

本文所有示例都是占位符：`<申请人名称>`、`<18 位统一社会信用代码>`、`<联系人手机号>`、
`<省><市><区><详细地址>`、`<邮编>`、`<商标名称>`。

- **不要把真实值写回技能文档**（真实姓名、手机号、证件号、地址、公司名、商标名、流水号、本机绝对路径）。
- 给用户看的过程记录里，流水号、手机号、证件号**只留后 4 位或直接省略**。
- 生成的图样文件名建议用商标名（汉字），但**图样本身不要放进技能仓库**，放临时目录。
