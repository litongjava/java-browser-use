# 站点配方（recipes）

把「某个站点上必须这么点」的经验固化成可复用的命令序列。每个配方一个 `.json` 文件，
**文件名（不含扩展名）就是配方名**。

- 目录：默认 `<服务启动目录>/recipes`，可用配置项 `browser.recipes.dir` 改（`get_config` 能查到实际目录）。
- 列出：`list_recipes`（或 `GET /playwright/methods` 看命令清单）。
- 执行：`{"method":"run_recipe","params":{"name":"配方名","vars":{...}}}`。
- **配方不会自动生效**：必须显式点名才执行，不做「看到这个域名就自动套用」的隐式推断。

## 文件格式

```json
{
  "name": "配方名（可省，默认取文件名）",
  "description": "一句话说明它解决什么问题",
  "params": { "变量名": "给人看的说明" },
  "defaults": { "变量名": "默认值，调用方不传时用这个" },
  "commands": [
    { "命令名": { "参数": "值" } },
    { "close_modal": { "title": "{{title}}" } }
  ]
}
```

- `commands` 与批量接口 `commands` 的格式**完全一致**：每项一个键（外加可选的 `expect` 断言）。
  所以配方里的每一步都能单独贴出来手跑，排障时不用整段重来。
- `{{变量}}` 由 `vars` 注入，走 JSON 编码：字符串自动带引号并转义，中文、引号、换行都不用转义。
  带引号的写法（`"{{x}}"`）与裸写法（`{{x}}`）都支持。
- 变量先取 `defaults`，再用调用方 `vars` 覆盖，所以**写好了默认值的配方可以不传参数直接跑**。
- 回执与 `commands` 批量一致（`count`/`succeeded`/`failed`/`results`），另加 `recipe` 与 `recipeDescription`。

## 现有配方

| 配方 | 用途 |
| --- | --- |
| `close-all-modals` | 清掉页面上所有可见的 DOM 弹窗（ant 确认框、用户服务协议层、抽屉）。这类按钮只认真实鼠标事件 |
| `query-and-read-table` | 点「查询」→ 等表格内容稳定 → 读表格。搜索结果是异步刷新的，点完立刻读会读到上一次的结果 |
| `cnipa-list-drafts` | 中国商标网：「我的账户 → 申请管理 → 未提交」并把日期筛选切到「近三个月」再读列表 |
| `open-console-from-iframe` | **主站把第三方控制台套在跨域 iframe 里**时的通用套路（见下） |
| `wework-qykit-open-console` | 企业微信后台的「邮件 / 微盘 / 文档 / 会议」：现取一个未被消费的 token，把 `exmail.qq.com` 控制台当顶层页面打开 |

## 套路：主站套第三方控制台

企业微信后台把「邮件」应用套在 `exmail.qq.com` 的**跨域 iframe** 里（微盘 / 文档 / 会议同理）。这是
一整类站点，不是个例。症状是 `get_browser_state` 只拿到主站外壳、iframe 内部一个元素都没有。

**正规解法**（先用这个）：`get_browser_state` 传 `includeFrames: true`，或先 `list_frames` 看有哪些
frame。Playwright 本身能跨 frame（走浏览器协议取内容，不依赖往页面里注入 JS），所以拿到 frame 之后
读、点、填都能做。详细说明见主技能第三节「跨域 iframe」。

**为什么还需要这个配方**：有些控制台在 iframe 里的可用性受主站外壳影响（iframe 尺寸小、控件被裁掉、
主站轮询把状态重置），这时「把控制台当顶层页面打开」更稳。步骤是：

1. **从 `iframe.src` 反查第三方 URL 与参数**：

   ```json
   {"method":"list_frames","params":{}}
   {"method":"execute_js","params":{"body":"() => Array.from(document.querySelectorAll('iframe')).map(f => ({src: f.src, id: f.id, name: f.name}))"}}
   ```

2. **找主站发 token / 换登录态的接口**：在 `get_requests` 里按 `token` / `oauth` / `sso` / `qykit` 过滤。
   企业微信这个是 `POST /wework_admin/apps/qykit/login/tokenAndOAuthCode`。

3. **用 `execute_js` 同源调它**（`credentials: 'same-origin'` 会自动带上主站的登录 Cookie），拿一个**新的、
   还没被消费的** token。`execute_js` 会 await Promise，直接 `await fetch(...)` 即可：

   ```json
   {"method":"execute_js","params":{"body":"async () => (await fetch('/wework_admin/apps/qykit/login/tokenAndOAuthCode',{method:'POST',credentials:'same-origin'})).json()"}}
   ```

4. **用新 token 顶层打开**：把 `iframe.src` 里的 token 参数换成新拿到的那个，再 `go_to_url`。之后这个
   控制台就是一个普通页面，索引、点击、填表全都照常。

**两个很容易踩的点**：

- **token 是一次性的**：直接 `go_to_url` 打开 iframe 的 `src` 会 **HTTP 500**（那个 token 已经被 iframe
  自己消费了）。所以必须先现取一个新的。
- **进了控制台之后可以用改 hash 的方式跳页**：`location.hash = '#/domain'` 是**同文档跳转**，不会重新
  发起请求，也就不会让 token 失效；而 `go_to_url` 到控制台里的另一个 URL 会重新走一遍鉴权，多半失败。
  所以控制台内部的导航优先用 `execute_js` 改 hash，或用页面上的导航项点过去。

## 写配方的纪律

1. **只写这个站点上「必须这么点」的东西**，不要把通用操作也包进来（通用操作直接发命令更清楚）。
2. 关键动作后面加 `expect` 断言或 `wait_for_count` / `wait_for_stable`，让「动作发了、状态没变」当场暴露。
3. 配方里**不要写真实个人信息**（姓名、手机号、证件号、地址、流水号），需要的话用 `{{变量}}` 让调用方传。
4. 站点改版后配方会失效——`description` 里写清「依赖哪些选择器」，方便下次快速定位。
5. **配方里的命令名会被构建期检查**（`SkillDocConsistencyTest.everyRecipeOnlyMentionsRealCommands`），
   写错一个不会等到 `run_recipe` 运行时才发现。配方本身也要是合法 JSON 且带 `commands` 数组。
