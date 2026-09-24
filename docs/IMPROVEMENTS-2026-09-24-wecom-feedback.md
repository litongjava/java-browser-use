# 改进记录：企业微信全流程反馈（B1–B11）落地情况

对应文档：[`FEEDBACK-2026-09-24-wecom-admin.md`](FEEDBACK-2026-09-24-wecom-admin.md)。
本文件记录**每条建议落到哪些代码 / 文档 / 测试**，便于回查「这条到底做了没有、在哪做的」。

回归测试：`playwright-server/src/test/java/nexus/io/ai/browser/service/BrowserFrameUpgradeTest.java`
（25 个用例，用两个本地端口模拟「主站 + 跨域 iframe」，覆盖下面绝大多数条目）。

---

## 一、最高价值的三条（反馈里点名的那三件）

### B1 暴露 frame 能力 ✅

**新增命令**

| 命令 | 说明 |
| --- | --- |
| `list_frames` | 列出页面上所有 frame（含跨域）：`index`/`url`/`name`/`isMain`/`parentIndex`/`depth`/`elementCount`/`indexRange`；读不出来的带 `readError`，没纳入快照的带 `skipped`+`skipReason` |

**新增参数**

- `get_browser_state` 加 `includeFrames`：为 `true` 时连**跨域** iframe 一起纳入快照。
- 选择器/脚本类命令加可选 `frame`：`click_element_by_selector`、`input_text_by_selector`、
  `get_element_count`、`wait_for_element`、`upload_file`、`get_element_screenshot`（selector 形式）、
  `execute_js`、`get_element_listeners`。取值是 frame 序号或 URL/name 子串。

**关键设计**

- 每个 frame 单独求值，**索引全局唯一**（`highlightIndexStart` 让 JS 从指定的起点开始编号，
  见 `resources/dom/dom_tree/index.js`），`DOMState.indexToFrame` 记着每个索引属于哪个 frame。
- **按索引命令自动路由**：`click_element_by_index` / `input_text` / … 不需要传 frame ——
  `resolveIndex` 通过 `frameForIndex` 找到目标 frame，xpath 在**该 frame 自己的文档**里求值。
- frame 句柄可能因导航/重建而 detach，所以动作执行前会**按 URL 在当前 frame 树里重新认一次**
  （`findFrameByUrl`），认不出来才退回快照句柄。
- 默认快照（不带 `includeFrames`）仍然只纳入**同源** frame —— 与历史行为一致，只是现在
  同源 iframe 里的索引也能被正确路由了（以前它们的 xpath 相对 iframe 文档，在主文档里求值必然失败）。
  页面里还有跨域 frame 没纳入时，回执带 `data.frameHint` 指出下一步。

**顺带修掉的一个隐蔽 bug**：点击 iframe 里的元素时，动作探针原本取在**顶层文档**，而 iframe 内容变化
不影响顶层 `body.innerText`，于是回执永远是 `changed:false` —— 把「生效了」误报成「没生效」。
现在探针取在**目标所在的 frame** 里（`stateProbe(inst, frame)`），回执带 `data.probedFrameUrl`。

**代码**：`dom/model/FrameSnapshot.java`（新增）、`dom/model/DOMState.java`、`dom/service/DomService.java`
（`getFrameState` / `frameTree` / `getClickableElements(Frame…)`）、`service/PlaywrightService.java`
（`getBrowserState` / `listFrames` / `frameOf` / `frameForIndex` / `frameText` / `frameList` / `frameHint`）、
`resources/dom/dom_tree/index.js`（`highlightIndexStart` / `descendIframes`）。

### B2 `upload_file` 加回读校验 ✅

上传后回读 input 的真实状态，回执里新增：

| 字段 | 含义 |
| --- | --- |
| `data.filesLength` | input 里现在有几个文件（应为 1） |
| `data.listeners` | `{vue2, vue3, react, inline, jquery, events}` |
| `data.hasListeners` | 三态：`true` / `false`（**确认没有**）/ `null`（未知） |
| `data.listenerDetection` | `cdp`（浏览器自己报的清单，可信）/ `heuristic` |
| `data.consumed` | `listened` / `noListener` / `unknown` |
| `data.hint` | `noListener` / `unknown` 时给一句可直接操作的提示 |
| `data.changed` / `data.effective` | 上传前后页面有没有变化；`noListener` + 没变化时 `effective=false` |

探针同样取在 file input 所在的 frame 里。

**代码**：`PlaywrightService.uploadFile` / `appendUploadReadback` / `elementResolveScript`。

### B3 `get_element_listeners` ✅

新增命令 `get_element_listeners`（`index` 或 `selector` + 可选 `frame`）。

**这一条刻意做得比建议更实**：反馈里提的判定依据（Vue `_vei` / React props / 内联 `on*` / jQuery）只能给出
**正面证据** —— 探得到就说明有人监听，**探不到不等于没人监听**（原生 `addEventListener` 在元素上不留任何
可枚举痕迹）。把「没探到」报成 `false` 就是**假阴性**，而假阴性正是这个坑最危险的地方。

所以实现分两条路（见 `util/ListenerProbe.java`）：

1. **权威路（Chromium 系）**：走 CDP 的 `DOMDebugger.getEventListeners` —— 浏览器自己报的监听器清单，
   原生 `addEventListener` 也算，`detection: "cdp"`，结论可信；
2. **启发路（Firefox / CDP 不可用）**：只看框架痕迹，探到才 `true`，否则 `null`（**未知**，不是 `false`）。

`get_interactive_map` / `get_browser_state` 的元素清单里也带 `hasListeners` + `listeners`
（批量路径不能对每个元素各开一次 CDP 会话，所以那里只会是 `true` 或 `null`，**永远不是 `false`**）。

---

## 二、其余各条

### B4 `get_modals` 通用浮层兜底 ✅

三轮扫描，每条结果带 `matchedBy` 说明置信度：

| `matchedBy` | 怎么命中 |
| --- | --- |
| `selector:…` | 框架专用选择器（ant-design / element-ui / vxe / layui / 协议层） |
| `heuristic:class-name` | 类名里有 `dialog`/`modal`/`popup`/`overlay`/`mask`/`confirm`… —— **企业微信的 `qui_dialog` / `mall_invoice_dialog_container` 靠这一轮** |
| `heuristic:fixed-overlay` | 可见 + 面积够大 + (`position:fixed` 或 `z-index > 50`) 的几何兜底 |

- 启发式只保留**最内层**候选（包住另一个候选的通常是整页遮罩，真正带按钮的是它里面那个）。
- 结果带 `data.scannedBy`，所以 `count: 0` 现在是可信的（三轮都跑过），不再是危险的假阴性。
- `close_modal` 支持 `which: "class:<子串>"`（很多站点弹窗既没标题也没 `role=dialog`）。
- **顺带覆盖了 iframe 里的弹窗**：主 frame 与每个 iframe 各扫一遍，iframe 内坐标按
  `iframe.boundingBox()` 平移到主页面视口，`close_modal` 的真实鼠标点击因此仍然有效。

**代码**：`PlaywrightService.MODALS_PROBE` / `modalProbe` / `frameOffset` / `shiftPoints` / `pickModals`。

### B5 错误信息带「下一步」 ✅

| 场景 | 现在会附上 |
| --- | --- |
| 索引越界 | 快照建于多久之前、共多少个可交互元素（合法区间）、这期间发生过几次 DOM 变更、含几个 frame，以及「重新取快照 / 改用 `*_by_selector`」 |
| 元素操作失败 | 同上（`indexAction` 的失败分支） |
| 实例不存在 | **本服务进程的启动时间** + 「任务实例只存在内存里、重启即失效」+ 先 `list_tasks` 再 `start` |
| 点击无变化 | 回执 `data.hit` 给出真正命中的 `tag`/`text`/`outerHtml`（把 `click_element_by_text` 的做法推广到按索引点击） |
| 选择器够不着 iframe | 失败信息里指出「目标可能在 iframe 里，传 `frame` 参数」 |

**代码**：`PlaywrightService.snapshotSuffix` / `indexHint` / `recordSnapshot` / `notFound` /
`STARTED_AT_TEXT` / `indexAction`；`BrowserInstance.domStateAt` / `domStateMutations`。

### B6 `start` 回执提示 profile 的登录态状况 ✅

- `data.profileSeenBefore`：这份 profile 目录在**这次启动之前**就已经有内容了吗
  （启动之后目录一定存在，所以必须在启动前判断 —— 见 `profileDirsExistingBeforeLaunch`）。
- `data.profileNote`：一句人话，例如
  「该 profile 目录本次是**首次创建**，里面没有任何登录态：任何需要登录的站点都要重新登录一次」/
  「引擎从 chromium 切到 firefox：两种引擎的 profile 格式不通用，**登录态不通用**，需要重新登录」/
  「这份 profile 之前用过（上次是 browser=chrome）：里面已有的登录态应该还在」。
- 引擎切换靠 profile 目录里的标记文件 `.dsh-browser-use-profile.json` 判断（用户自己的 Chrome profile
  不写这个文件）。

**代码**：`PlaywrightService.applyProfileHistory` / `profileLoginNote` / `readProfileMarker` /
`writeProfileMarker`；`SharedBrowser.profileSeenBefore` / `previousProfileEngine`。

### B7 人机协同补强 ✅

1. **读不了图的模型**：`request_human_input` 现在同时回 `data.imagePath`（服务端本地路径）与
   `data.imageUrl`（可直接 GET，贴给用户最方便），不再只有 `imageBase64`；
   并新增命令 **`ocr_image`** —— 用 Windows 自带 OCR（`Windows.Media.Ocr`，支持 `zh-Hans-CN`）把图上的
   文字读出来，`request_human_input` 传 `ocr: true` 也会顺带读一遍。没装语言包时明确说「本机没有可用的
   OCR 语言包」并列出已装的语言，而不是给一段乱码。
2. **多步人机协同**：`request_human_input` 支持 `steps: [{prompt, index?, selector?}, ...]`，人一次做完；
   `submit_human_input` 用 `stepId` 逐条回填或 `answers: {s1: …}` 一次回填多步，
   还有步骤没回填时 `status` 是 `partial` 并给出 `pendingSteps`。
3. **超时语义**：新增 `expiresAt`（绝对过期时刻，适合二维码这类短时效凭证）；过期时
   `get_human_input` 直接回 `status: "expired"` + `data.expired: true` + 一句「重新发起」的提示，
   而不是让人干等。`expiresAt` 已经过去时 `request_human_input` 当场说清「这个请求没有生效」。

**代码**：`PlaywrightService.requestHumanInput` / `submitHumanInput` / `getHumanInput` / `ocrImage`；
`util/WindowsOcr.java`（新增）；`resources/scripts/windows-ocr.ps1`（新增）。

### B8 把「一次性 token / 跨域控制台」沉淀成官方配方 ✅

- 新增配方 `recipes/open-console-from-iframe.json`（通用套路，带 `list_frames` / `get_requests` 步骤）与
  `recipes/wework-qykit-open-console.json`（企业微信专用：现取一个未被消费的 token）。
- `recipes/README.md` 加了一节「套路：主站套第三方控制台」，写明四步
  （反查 `iframe.src` → 找主站发 token 的接口 → 同源 `execute_js` 现取 → 顶层打开），
  以及两个容易踩的点：**token 是一次性的**（直接打开 `src` 会 500）、**进控制台后用改 hash 跳页**
  （同文档跳转不会重新请求，因此不会让 token 失效）。
- 主技能第十二节同步写上这两条与「先试 `includeFrames`，不行再换 token」。

### B9 `viewportExpansion` 的可用性问题 ✅

主技能「坑与限制」补了一条**症状导向**的条目：「快照里找不到明明在页面上的元素」→ 先看
`data.pixels_above` / `data.pixels_below` 是不是非 0 → 三条解法按优先级（`viewportExpansion` /
改用选择器 / 滚动后再取快照），并点明这条以前只写在参数表里、症状导向搜不到。

### B10 文档可发现性：症状 → 命令索引表 ✅

主技能开头（「省 token 铁律」之后）新增一张**症状 → 命令**索引表，覆盖：
元素不在快照里、整页读不到（iframe）、点了返回 ok 但没反应、不确定页面动没动、
表单填了说为空、上传了没反应、弹窗挡住点击、元素有没有挂事件、要人扫码/输码、
模型读不了图、长批次超时、索引老是失效、换引擎后全退登录、实例不存在、skill 检查报错。

### B11 `SkillDocConsistencyTest` 对站点 skill 的系统性误报 ✅

按反馈推荐的第一种方案实现，并**把规则写进文档**：

1. **双反引号**包起来的标识符一律不当命令检查（`` ``subject_name`` ``）；单反引号仍然是命令。
   测试里加了 `doubleBacktickEscapesNonCommandIdentifiers` 固定住这条约定 —— 约定一旦失效就会红。
2. 也支持 frontmatter 的 `nonCommands: [a, b]`（测试会并入白名单）。
3. 新增 **`skills/README.md`**，把「单反引号 vs 双反引号」的对照表、其余约定与站点 skill 的建议结构
   写下来（`skillAuthoringConventionsAreDocumented` 会检查它存在且写明这条约定）。
4. **配方也过一遍**：新增 `everyRecipeOnlyMentionsRealCommands`，检查 `recipes/*.json` 的
   JSON 合法性、`commands` 数组、以及每一步的命令名是否真实存在（允许 `expect` 键）。

站点 skill 里为绕过旧检查而加的「点号 / `#` / 对象前缀」写法保留着（它们本身也更贴合语境），
不再需要新的变通。

---

## 三、其它顺带改动

- `DomService.buildExpression()` 结果做了缓存：脚本 50KB 上下，带 frame 的快照要对每个 frame 各读一次文件，
  缓存掉明显更划算。
- `execute_js` 回执新增 `data.awaited`，并在文档里写明它**会 await Promise**（反馈 P8）——
  调用方不必再用已废弃的同步 XHR 规避。
- `CommandTableTest` 补上新增命令的缺参数用例覆盖。
- 新增测试 `BrowserFrameUpgradeTest`（25 个用例）：用两个本地端口模拟「主站 + 跨域 iframe」，
  覆盖 frame 读/写/路由、监听器探测（有监听 / 没人监听）、上传回读、弹窗三轮兜底、
  按类名关弹窗、报错带下一步、profile 登录态、多步人机协同与 `expiresAt`、`ocr_image` 结构化返回。

## 四、没做 / 已知边界

- **`recoverByIdentity`（索引失效后按「同 tag + 同文本」找回元素）仍只在主 frame 里找**：
  目标在 iframe 里时这条补救不生效，会直接报错。影响有限（按 URL 重认 frame 那一步已经覆盖了
  iframe 刷新的常见情况），但确实是个已知边界。
- **`ocr_image` 只在 Windows 上有**：非 Windows 会明确回「本机没有可用的 Windows PowerShell」，
  并提示改用 `request_human_input` 或把 `data.imageUrl` 贴给用户；没有做其它平台的 OCR 后端。
- **`get_interactive_map` 的 `hasListeners` 是启发式的**（只会是 `true` 或 `null`）：
  对每个元素各开一次 CDP 会话太贵。要「确认没有监听器」就单独对那个元素调 `get_element_listeners`。
- **几何兜底可能多列出一条整页遮罩**：这是看 `matchedBy` 判断置信度的原因；宁可多看一条，也不要
  再出现「明明有弹窗却说 `count:0`」的假阴性。
