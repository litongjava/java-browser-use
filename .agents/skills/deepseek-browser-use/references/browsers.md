# 浏览器、引擎与 profile

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

**一个浏览器，多个任务。** 服务用的是**本机安装的 Google Chrome**（没装才退回内嵌 Chromium）和**一份共享的持久化 profile**（默认在 `~/.config/browseruse/profiles/shared`）：所有任务共用同一个 Chrome 进程与同一份 profile，任务之间靠**页签**隔离。登录一次就留在 profile 里，后续任务不用再登。

> 为什么不再一个任务一份 profile：用户数据目录天生是单例 —— 同一个目录同时只允许一个 Chrome 进程（第二个进程会把命令行交给已有实例然后自己退出），所以「共用一份 profile」和「一个任务一个浏览器」只能二选一。现在的取舍是：**共用浏览器与 profile，页签按任务隔离**。
>
> 想直接用用户日常那份 profile（现成的 Cookie 与登录态）？Chrome 136 起**不允许在默认用户数据目录上开启远程调试**（Playwright / Puppeteer / Selenium 一视同仁），所以默认不走这条路。只有在服务端把 `browser.chrome.useUserProfile` 打开、并且这台机器允许远程调试默认 profile（企业策略 `RemoteDebuggingAllowed=1`，或 Chrome 低于 136）时才会用上，这时 `data.browser.mode` 是 `cdp`。

## 用哪个浏览器：`start` 时选（`browser` 参数）

**一个站点在这个浏览器下用不了，就换一个再试** —— 这是最容易见效的一招，不用改服务端配置。`start` 时传 `browser` 即可：

```json
{"method":"start","params":{"headless":true,"browser":"edge"}}
```

| `browser` 取值 | 用的是什么 | 说明 |
| --- | --- | --- |
| 不传（或 `auto`） | 本机安装的 Google Chrome，没装则内置 Chromium | 默认，行为与以前完全一致 |
| `chrome` | 本机安装的 Google Chrome | 没装会**直接失败**（不会偷偷换成内置的），失败信息里会说怎么改 |
| `edge` | 本机安装的 Microsoft Edge | 同上；Edge 用**它自己一份 profile**，登录态与 Chrome 那份不通用 |
| `chromium` | 内置的那份 Chromium（发行包内嵌；开发态是 Playwright 自带的） | 完全不碰本机 Chrome，适合「怀疑是本机 Chrome 的扩展／登录态干扰」时对照 |
| `firefox` | Playwright 自带的 Firefox | 等价于服务端把 `browser.engine` 配成 `firefox`，见下 |

也可以简写成 `msedge` / `google-chrome` / `ff` 这些别名；写了不认识的值会返回 `start 失败：无法识别的浏览器类型：xxx，可选值：auto / chromium / chrome / edge / firefox`。

**先看返回再干活**：`data.browser.type` 就是这次实际用的浏览器（`auto` 不会出现在这里，已经落成确定值），配合 `data.browser.chrome` / `userProfile` / `mode` / `profileDir` 一起看。`data.browser.note` 非空时说明服务替你做了退让（例如「没有找到本机安装的 Google Chrome，改用内嵌/Playwright 自带的 Chromium」）。

**什么时候换**：站点明确报「浏览器不支持」、页面白屏但换引擎就好、或者需要对照「同一页面在两个浏览器下的差异」时。换了浏览器**不保证**一定有救 —— 但比反复重试同一个浏览器划算。

**注意三件事**：

- **一次只能有一个浏览器**：浏览器与 profile 全进程共用。任务还在跑时 `start` 传一个不同的 `browser` 会返回 `start 失败：浏览器已经在运行（browser=chrome，headless=true，profile=…）：所有任务共用同一个浏览器，不能中途切换浏览器类型…`。要换就先 `close` 掉在跑的任务，再 `start`；**不用重启服务**。
- **换浏览器等于换一套登录态**：`chrome`／`chromium` 共用 `browser.profileDir` 那一份；`edge` 用它自己那份（`~/.config/browseruse/profiles/edge`）；`firefox` 的 profile 格式与 Chromium 系不通用。所以换过去之后，需要登录的站点要重新登一次（登录态会留在那份 profile 里，后续任务不用再登）。
- **`edge` 走的是「自己拉进程 + CDP」这条路**（`data.browser.mode` 是 `cdp`，和用用户自己的 Chrome profile 一样）：这样才能带着沙箱跑（实测 Edge 配上沙箱时，Playwright 的管道启动会让它启动即退出）。随之而来的差别有两条：`set_credentials` 在这条路上不可用；页面触发的下载落到浏览器自己的下载目录（不是 `~/Downloads/broswer`，`pdf` 命令不受影响，它自己算路径）。
- **`edge` 这条路用的是浏览器自己的 UA**（含 `Edg/`）。内置 Chromium 会伪装成 Chrome 的 UA，本机 Chrome 与 Edge 都用各自的 UA —— 所以 UA 里出现 `Edg/` 才说明这次真的用上了 Edge。

## 用哪个引擎：Chrome 还是 Firefox（`browser.engine`）

默认 `chromium`：本机安装的 Google Chrome，没装才退回内嵌/Playwright 自带的 Chromium —— 与以前完全一致。服务端把 `browser.engine` 配成 `firefox` 时改走 `playwright().firefox().launchPersistentContext(...)`：Playwright 自带的那份 Firefox（版本由 Playwright 依赖决定），配同一份托管 profile。**`start` 时传 `browser=firefox` 是同一件事**（不用改服务端配置），引擎与浏览器类型的关系见上一节。

**什么时候需要切**：有的站点在 Chromium 下根本用不了。中国商标网统一身份认证（`sso.cnipa.gov.cn/am/`）的 SPA 会做开发者工具检测（disable-devtool 的 Performance 检测器），Chromium（本机 Chrome 与内嵌 Chromium 都一样）会走到空白页或 HTTP 400，而 Firefox 139 下同一流程能正常渲染出登录表单。切过去之后 `start` 的返回里 `data.browser.engine` 是 `firefox`、`data.browser.chrome` 与 `data.browser.userProfile` 都是 `false`、`mode` 是 `managed`。

**注意这几处与 Chromium 不同**（是引擎能力差异，不是配置错了）：

| 差别 | 说明 |
| --- | --- |
| `pdf` 命令 | 只有 Chromium 支持，Firefox 下会直接返回失败原因（提示换成 Chromium 系的浏览器：`browser=chrome` / `chromium` / `edge`），不要以为是自己参数写错了 |
| 用户自己的 Chrome profile | `browser.chrome.useUserProfile` 与 Firefox 无关，打开也不会被 Firefox 用上 |
| 启动参数 | 没有 `--no-sandbox`／`chromiumSandbox`／`--profile-directory` 这些 Chromium 概念，Firefox 下不传 |
| 本机装的 Firefox | 用不上：Playwright 的 Firefox 是打过补丁的构建（走 juggler 协议），`browser.firefox.path` 一般不需要配 |
| UA | **不覆写成 Chrome**：Firefox 的价值就在于它是 Firefox，UA 里会是 `Firefox/<版本>` |

`browser.engine` 是**浏览器级**配置：改了它之后，如果还有任务在跑，`start` 会明确报错而不是把别人的页签弄没；没有任务在跑时，下一次 `start` 会自动把旧浏览器收掉、按新引擎重建。登录态跟着 profile 走，**换引擎会换一套登录态**（Chromium 与 Firefox 的 profile 格式不通用），所以切过去之后需要重新登录一次。

- `id` 就是任务标识。`start` 时自己指定（例如用业务里的任务号），不传则自动生成雪花 ID。
- 每个任务有**自己的一组页签**：`get_tabs` / `data.tabs` / `switch_tab` 的索引只在这个任务的页签里数，别的任务的页签看不见也点不到。弹窗与 `new_tab` 开出来的新页签归开它的任务。
- **同一个 `id` 不能重复 `start`**：会返回 `start 失败：该 id 已经有正在运行的浏览器实例：1001，请先调用 close，或换一个 id`。要重来就先 `close` 再 `start`。
- `close` 只关掉这个任务的页签；**最后一个任务关闭时**浏览器才会一起退出（profile 的占用也随之释放）。
- 实例本身只在内存里，服务重启后 id 失效（登录态在 profile 里，仍在）。
- `start` 的返回里有 `data.browser`：`type`（这次实际用的浏览器：`chrome`／`edge`／`chromium`／`firefox`）、`chrome`（是否用上了本机 Chrome）、`userProfile`（是否用上了用户自己的 profile）、`engine`、`mode`（`managed` = Playwright 的托管 profile，`cdp` = 服务自己拉进程再接上：用户自己的 Chrome profile 与 `edge` 都是这条）、`executable`、`profileDir`、`profileDirectory`、`headless`，以及服务替你做了退让时的 `note`。**看到 `userProfile=false` 就说明这次不是用户日常那份登录态**，需要登录的站点要重新走登录流程或请人协助；**看到 `type` 不是你要的那个，先看 `note`**（见上节「用哪个浏览器」）。
- **`data.profileSeenBefore` 与 `data.profileNote` 回答的是另一件事**：`engineHonored:true` 只说明「浏览器参数被采纳了」，**不说明「这份 profile 里有登录态」**。这两个字段直接说清：
  - `profileSeenBefore:false` → 「该 profile 目录本次是**首次创建**，里面没有任何登录态：任何需要登录的站点都要重新登录一次」；
  - `profileNote` 里出现「引擎从 X 切到 Y，登录态不通用」→ 换过引擎（Chromium ↔ Firefox 的 profile 格式不通用），需要重新登录；
  - `profileNote` 里出现「这份 profile 之前用过（上次是 browser=…）」→ 已有登录态应该还在。

  实测踩过：服务端默认引擎被配成 `firefox`，而企业微信 / DNSPod / 腾讯云的登录态都在 **Chromium profile** 里，不知情直接 `start`（不传 `browser`）会起 Firefox，然后「所有站点都退登录了」。看这两个字段就不用事后回想。
- 服务没有鉴权，默认只监听本机；对外暴露前必须自行加访问控制。

> 登录态不跟着任务 ID 走，而是跟着共享 profile 走：换任务、换 id 都不影响。`userProfile=false` 时用的是托管 profile（`~/.config/browseruse/profiles/shared`），那份 profile 里的登录态是 agent 自己养起来的 —— 第一次登录之后同样会长期保留。
