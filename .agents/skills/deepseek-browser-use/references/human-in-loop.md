# 人机协同（验证码 / 登录 / 人工介入）

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

**遇到无法自行处理的环节，必须主动请求人类帮助，不要反复尝试或直接放弃任务。** 常见情况包括需要人工登录、输入图片验证码或短信码、扫码、拖动滑块、按顺序点击图片/文字验证、设备确认，以及其他需要用户亲自完成的操作。

人工接力流程：

1. 告诉用户当前停在哪个网站、遇到了什么问题、需要完成哪一步。例如：“当前停在登录页，需要你在浏览器中完成登录和滑块验证。完成后请告诉我，我会继续查询。”
2. 优先让用户直接操作有头浏览器（`headless=false`），用 `bring_to_front` 或 `request_human_input` 将当前页签带到最前。保留当前任务 ID、页面和浏览器，不要在等待期间刷新、关闭或继续点击验证控件。无头实例无法直接显示时，先说明需要切换到有头模式，保存当前 URL，再按同一 ID 先 `close` 后 `start`（`headless=false`）并重新打开页面；重启可能丢失未提交的表单和当前验证进度。
3. 调用 `request_human_input` 记录人工请求，并通过宿主工具的提问能力或对话消息明确通知用户；不能假定接口返回成功就代表用户已收到通知。登录、滑块或点击验证优先由用户在浏览器内完成，不要求用户在对话中提供密码。
4. 等待用户答复或观察到明确的完成状态。等待期间暂停依赖登录或验证的后续操作，不反复提交、不猜答案；请求超时只表示尚未收到答复，不代表验证已通过。
5. 用户处理后重新调用 `get_browser_state`，确认已登录或验证已通过，并取得新的元素索引，再继续原任务。若仍受阻，说明当前状态并继续请求协助。

服务提供以下三个方法记录请求与答复；用户也可以直接在浏览器中完成操作：

| 步骤 | 调用 | 做什么 |
| --- | --- | --- |
| 1 | `request_human_input`，`prompt=请输入图片验证码`，`index=7`，`timeoutSeconds=300` | 建一个待办；`index`/`selector` 指向验证码图时把图截下来，回 `data.imageBase64`、`data.imagePath`、`data.imageUrl` 三份，同时把页签带到窗口最前 |
| 2 | 人看图 → 把答案回填 | 通过 `submit_human_input`（`requestId` + `answer`）提交；**或者**直接在有头浏览器里自己把这一步操作完 |
| 3 | `get_human_input`，`requestId=hr-1-xxx`，`timeoutSeconds=60` | 取答复。`data.status` 为 `pending` / `partial` / `answered` / `expired` |

**模型读不了图怎么办**（``read_image`` 报 ``model ... does not declare image input`` 时）：这是「必须看图」环节最容易卡住的地方。三条路，按顺序试：

1. **先让服务端自己读**：`ocr_image`（或 `request_human_input` 加 `ocr: true`）用本机 OCR（Windows 自带 `Windows.Media.Ocr`）直接把图上的文字返回，识别中文需要系统装了对应语言包。验证码这类印刷体字符识别率不错，能自己答就直接答，省掉一次人工往返：

   ```json
   {"id":1001,"method":"ocr_image","params":{"selector":"#imgVerify","language":"zh-Hans-CN"}}
   # {"ok":true,"data":{"ok":true,"text":"8f3k","lineCount":1,"imagePath":"...","imageUrl":"/data/1001/shot-3.png"}}
   ```

   回执里 `data.ok:false` 且带 `data.engineMissing:true` 时说明这台机器没装 OCR 语言包（会列出已装的），这时才走下一步。
2. **把 `data.imageUrl` 贴给用户**（比本地路径好用：用户自己就能打开），让用户在对话里告诉你内容。
3. **请人直接在有头浏览器里操作**（扫码、滑块这类本来也只能人做）。

**多步人机协同用 `steps` 一次交办**：扫码 + 输码 + 支付确认是**一串**动作，每次单独发起「请求 + 等待 + 取答复」要来回好几趟，中间还容易超时。用 `steps` 把待办列出来，人一次做完：

```json
{"id":1001,"method":"request_human_input","params":{
  "prompt":"需要你完成三步：扫码登录、输入短信码、确认支付",
  "steps":[
    {"prompt":"用微信扫码登录","selector":"#qrcode"},
    {"prompt":"输入收到的 6 位短信码","selector":"#smsCode"},
    {"prompt":"在浏览器里确认支付 ¥300"}]}}
# 回执：data.steps = [{stepId:"s1",prompt:"...",status:"pending",selector:"#qrcode"}, ...]

# 人做完一步就回填一步（也可以一次回填多步）
{"id":1001,"method":"submit_human_input","params":{"requestId":"hr-1-xxx","stepId":"s1","answer":"已扫码"}}
{"id":1001,"method":"submit_human_input","params":{"requestId":"hr-1-xxx","answers":{"s2":"582913","s3":"已支付"}}}
# 还有步骤没回填时 data.status 是 partial（并给出 data.pendingSteps）；全部回填后才是 answered
```

**短时效凭证用 `expiresAt` 明说**：二维码、短信码的有效期常常只有一两分钟，默认的 300 秒等待纯属浪费，事后也无从判断「是不是等的时候早就过期了」。传绝对过期时刻，过期后 `get_human_input` 会直接回 `data.status:"expired"` 与 `data.expired:true`，附带一句「重新发起」的提示，而不是让人干等：

```json
{"id":1001,"method":"request_human_input","params":{
  "prompt":"请扫码登录","selector":"#qrcode","expiresAt":1750000000000}}
```

要点：

- **`get_element_screenshot` 是这套流程的地基**：没有它，`request_human_input` 也没东西可以给人看。要单独把图拿出来（不发起人工请求）就直接调它。
- `data.imageBase64` 是 PNG 的 base64，需要向用户展示验证图片时可以使用；`data.imagePath` 是服务端本地路径，`data.imageUrl` 是可以直接 GET 的地址（贴给用户最方便）。取到图片不代表验证已完成。
- 用户直接在浏览器里操作不会自动更新人工请求记录，`get_human_input` 可能仍为 `pending`。不要只等该字段，也不能直接跳过验证：重新读取页面，确认登录或验证已成功后才继续后续步骤；一般页面变化本身不足以证明验证成功。
- **验证码有时效**：实测税务系统的图片验证码约 **120 秒**过期，而且**一次性**（用过的码再提交必然失败）。所以拿到答复后要**立刻**提交，不要攒着；提交失败先换一张新图再让人看，别拿旧码重试。用 `expiresAt` 把这件事写进请求里。
- **登录态跟着共享 profile 走，不跟任务 ID 走**：所有任务用的是同一份 profile（`data.browser.profileDir`），换任务、换 id 都不影响；是否仍有效由网站决定，登录过期时再次请求用户协助。若 `data.browser.userProfile=false`（退回托管 profile，例如 Chrome 正在运行）或 `chrome=false`（没装 Chrome），说明这次不是用户日常那份登录态，需要重新走登录流程。
- 有头模式（`headless=false`）下配合 `bring_to_front` / `request_human_input`，人工能直接看到智能体停在哪一页，接力最顺。

```shell
# 1. 请人看验证码（把图和问题一起拿出来）
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"request_human_input",
  "params":{"prompt":"请输入图片验证码","index":7}}'
# {"data":{"requestId":"hr-1001-3001","prompt":"请输入图片验证码","imageBase64":"iVBORw0...",
#          "imagePath":"data/1001/shot-3.png","imageUrl":"/data/1001/shot-3.png","expiresAt":1750000000000},...}

# 1'. 读不了图的模型：先让服务端用本机 OCR 读一遍
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"ocr_image","params":{"index":7}}'
# {"data":{"ok":true,"text":"8f3k","lineCount":1}} → 直接拿去填，人工都不用叫

# 2. 人给出答案后立刻回填
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"submit_human_input",
  "params":{"requestId":"hr-1001-3001","answer":"8f3k"}}'

# 3. 取答复并立刻提交表单
curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "id":1001,"method":"get_human_input","params":{"requestId":"hr-1001-3001"}}'
```
