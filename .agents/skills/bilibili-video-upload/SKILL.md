---
name: bilibili-video-upload
description: 用 deepseek-browser-use 把本地一个视频发布到 B 站（创作中心投稿页）的实操手册：二维码登录交给人，投稿页会**自动恢复上一份未完成草稿**并把它当成本次稿件（视频位是空的，上传就是填进那个草稿），随后要覆盖标题/简介/分区/标签/创作声明，封面必须自己上传一张图（系统推荐封面可能一个都没有，用 ffmpeg 从视频里截一帧最稳），最后点「立即投稿」并到稿件管理页回读 BV 号。同时写透这个页面最坑的三件事：整页**截不出图**（每条命令白等 30 秒、且调用方完全看不到画面）、**B 站会按标题自动改分区**、以及**提交/开弹窗时页面整页重建**导致点击回执里的 changed 与 coveredBy 都是假象（如实测那两次点击其实都成功了）。
whenToUse: 需要把一个本地视频文件（或一组视频）投稿到哔哩哔哩（B 站），包括填标题简介标签分区、设封面、提交并确认稿件编号时。站点是 member.bilibili.com 创作中心投稿页（Vue + micro-app）。
---

# B 站视频投稿（创作中心）

一次真实任务（2026-09-25，发布 `使用 deepseek-browser-use 在腾讯云购买服务器.mp4`，9 分 48 秒 / 1080P / 47MB）
的完整记录:从打开投稿页到拿到 BV 号。文中数据都已脱敏,替换占位符即可复用。

> **平台说明**：本文示例用 Windows 的 `client/dsb.cmd`。macOS/Linux 下换成 `./client/dsb`（参数完全相同），
> 例如 `./client/dsb --id 3001 start --browser chrome --headful`；服务端启动/停止换成 `scripts/run/start-server.sh` /
> `stop-server.sh`。下文所有以 `client/dsb.cmd` 开头或嵌在命令里的调用，均按此替换。

## 一、站点特征

| 项 | 值 |
| --- | --- |
| 投稿页 | `https://member.bilibili.com/platform/upload/video/frame` |
| 登录页 | `https://passport.bilibili.com/login`（默认就是二维码登录） |
| 稿件管理（回读用） | `https://member.bilibili.com/platform/upload-manager/article` |
| 视频页 | `https://www.bilibili.com/video/<BV号>/` |
| 技术栈 | 创作中心外壳是 Vue 单页 + **micro-app**；投稿表单本身是 micro-app 里的 `video-up` 应用 |
| 登录态 | 在共享 profile 里（Chromium 那份），**扫码一次长期有效**；事后换任务不用再登 |

**不要去找 iframe。** 投稿表单虽然挂在 micro-app 里,`list_frames` 也只会给出主 frame 加两个无关的
`iframe.html` / `nav/index_new_pc_sync`,`get_browser_state` 在主 frame 里就能读到全部表单元素
（`data.text` 里能看到「发布视频 / 基本设置 / 立即投稿」）。这一页**不需要** `includeFrames`。

> `https://member.bilibili.com/platform/manage/home` 不是稿件管理,它会跳回创作中心首页。要回读稿件
> 请用上面表里的 `upload-manager/article`。

## 二、走一遍（每步给出可直接复制的请求体）

### 1. 起任务并打开投稿页

```bash
client/dsb.cmd --id 3001 start --browser chrome --headful
client/dsb.cmd --id 3001 run go_to_url -p url=https://member.bilibili.com/platform/upload/video/frame
```

有头（`--headful`）**是必须的**:登录要人扫码。

### 2. 登录交给人（二维码登录）

未登录时会被弹到 `https://passport.bilibili.com/login`。二维码就在主文档里,不需要 frame:

- 容器 `.login-scan__qrcode`,里面那张 140×140 的 `<img>` 的 `src` 是 `data:image/png;base64,…`。

**这一页 `get_element_screenshot` 与 `screenshot` 都会超时**(见下面「坑」),所以别指望用截图把二维码
交给远处的人。两条可行路径:

```bash
# 路径 A(推荐):人就在机器旁 —— 把窗口带到最前,让他直接扫
client/dsb.cmd --id 3001 run bring_to_front

# 路径 B(人不在机器旁):把二维码**抠出来存成文件**,再用 /data/** 静态路由给人看
client/dsb.cmd --id 3001 js "() => document.querySelector('.login-scan__qrcode img').src"
# 把回执里的 base64 落成 data/<id>/bili-qr.png,然后给人这个地址:
#   http://localhost:10049/data/<id>/bili-qr.png
```

同时用 `request_human_input` 建一条人工待办（`selector` 指向二维码容器）。**二维码有时间限制**,
快过期时页面会自己换一张,所以路径 B 的图要么及时用、要么重抠一张。

**怎么确认真的登录了**——别看页面变没变,看控制台:

```bash
client/dsb.cmd --id 3001 run get_page_snapshot -p includeConsole=true --json
# 日志里出现「手机端扫码成功，等待确认」→「登录成功, 即将跳转」就是成了
```

### 3. 认清一个陷阱:投稿页会**自动恢复上一份未完成草稿**

打开投稿页时,如果账号里有没发完的草稿,B 站会把它的**标题、简介、分区、标签**直接填进表单,
而**视频位是空的**（所以页面看起来是「点击上传或将视频拖拽到此区域」的干净状态）。

你上传的视频会填进**这份草稿**,于是:

- 稿件卡片（`.task-list-content-item .task-title-text`）显示的是**草稿标题**,不是你文件名;
- 标题输入框里是草稿标题,分区/标签/简介也全是草稿的。

**动手之前先把草稿字段抄下来**（标题/分区/标签/简介各是什么）存到文件里再改,否则等于替人删了一份草稿。
判断「是不是恢复的草稿」很简单:标题与文件名不一致、简介内容与视频无关,就是。

> 「添加视频」（`.task-list-content-btn`）点下去只会弹出系统文件选择框,**不要用它来新建稿件**——
> 它不产生新任务,而且会弹一个 Playwright 接不住的原生对话框。

### 4. 上传视频

视频输入框是隐藏的,用 `selector` 最稳(元素选择器能吃隐藏 input,`index` 不行):

```bash
# 视频文件在**服务端**能打开的路径(同机时直接给绝对路径;不同机先 POST /playwright/upload)
{"upload_file": {"selector": ".bcc-upload-wrapper input[type=file]",
                 "path": "<视频绝对路径>.mp4", "timeoutMs": 120000}}
```

上传 47MB / 本地同机大约 20 秒。**怎么确认**:回执 `data.consumed=listened`、`filesLength=1`,
随后页面里出现文件名 + 「上传完成」:

```bash
# 一行读出稿件卡片与视频项的状态
client/dsb.cmd --id 3001 js "() => JSON.stringify({tasks:[...document.querySelectorAll('.task-list-content-item')].map(e=>({title:(e.querySelector('.task-title-text')||{}).innerText,status:(e.querySelector('.task-status .text')||{}).innerText})),files:[...document.querySelectorAll('.file-item .title-text')].map(e=>e.getAttribute('title'))})"
```

### 5. 填标题与简介

| 字段 | 选择器 | 备注 |
| --- | --- | --- |
| 标题 | `input[placeholder="请输入稿件标题"]` | 唯一,≤80 字,页面有 `N/80` 计数 |
| 简介 | `.desc-container .ql-editor` | **Quill 的 contenteditable**,`input_text_by_selector` 可以直接覆盖式填进去（回执 `committed=true`）,不用 `click` + `type_text` |

```bash
{"input_text_by_selector": {"selector": "input[placeholder=\"请输入稿件标题\"]", "text": "标题"}}
{"input_text_by_selector": {"selector": ".desc-container .ql-editor", "text": "简介…\n\n项目地址：https://…"}}
```

> 简介里**不要**放 B 站会当成外链的东西之前先想清楚，正常写 URL 没问题;正文里的换行用 `\n` 即可。

### 6. 分区:填完标题**必须回头再看一眼**

这是本页最阴的一个坑:**B 站会按标题自动改分区**。实测标题从草稿的「INMO Air 2开发指南」改成
「使用 deepseek-browser-use 在腾讯云购买服务器」之后,分区被自动从「科技数码」改成了「**vlog**」。

> **改分区不止发生在改标题那一下(2026-09-25 第二次投稿实测)**：那次填完标题回读仍是「科技数码」,
> 等**简介、标签、封面都填完之后**再看,分区已经被自动改成了「人工智能」。
> 所以「填完标题看一眼」是不够的——**提交前最后一次回读才算数**,最后对字段时务必再确认一次分区。
> （顺带:自动改出来的分区不一定差,那次「人工智能」就比草稿里的「科技数码」更贴切;
> 要采纳就采纳,要改回来才动下拉。）

分区是自定义下拉,不是 `<select>`:

```bash
# 1) 打开下拉
{"click_element_by_selector": {"selector": ".video-human-type .select-controller"}}
# 2) 按 title 属性点选项（下拉项在 .drop-list-v2-item，title 就是分区名）
{"click_element_by_selector": {"selector": ".video-human-type .drop-list-v2-item[title='科技数码']"}}
# 3) 回读确认
client/dsb.cmd --id 3001 js "() => document.querySelector('.video-human-type .select-item-cont').innerText"
```

**第 2 步的回执很可能是 `changed:false` + `coveredBy: div.header`(提示说「被遮挡物吃掉了」),
但点击其实生效了。** 这不是你的错觉,也不是选择器问题(见下面「坑 3」)——**以第 3 步回读的值为准**,
不要因为回执不好看就反复重点。

### 7. 标签:一个 input,但有两个同名兄弟

```bash
# 先清掉草稿带过来的标签（每个 chips 右边都有一个 × 图标）
{"click_element_by_selector": {"selector": ".tag-pre-wrp .label-item-v2-container svg.close"}}   # 有几个就点几次
# 再加自己的:填值 + 回车
{"input_text_by_selector": {"selector": ".tag-container .tag-input-wrp input.input-val", "text": "腾讯云"}}
{"send_keys": {"keys": "Enter"}}
```

三个必须知道的点:

1. **真正的输入框是 `.tag-pre-wrp` 的兄弟,不是它的子节点。** 写 `.tag-pre-wrp input.input-val`
   会匹配 **0 个**元素。记住这条路径:`.tag-container .tag-input-wrp input.input-val`。
2. 页面上有**两个**同样 `placeholder="按回车键创建标签"` 的输入框,另一个在**隐藏的弹窗**
   （`bcc-dialog__body`,0×0）里。选择器要对准 `.tag-container` 那一个,否则会去点隐藏的那份。
3. **有的标签会被 B 站静默丢掉**。实测 `deepseek` 连着两次都是「输入框清空了、标签没多出来、
   页面**没有任何报错提示**」。这不是命令失败,是站点不接受;别在这里反复重试浪费轮次,
   换一个标签或干脆少一个。**每次只加一条、加完回读**能最快发现哪一种被吞:

```bash
client/dsb.cmd --id 3001 js "() => JSON.stringify({tags:[...document.querySelectorAll('.tag-pre-wrp .label-item-v2-content')].map(e=>e.innerText.trim()),hint:(document.querySelector('.tag-last-wrp')||{}).innerText})"
```

### 8. 创作声明（必填,加了就不能改）

```bash
{"click_element_by_selector": {"selector": ".creation-statement-container .bcc-select-input-wrap"}}
{"click_element_by_selector": {"selector": ".creation-statement-container .bcc-select-list-wrap li.bcc-option"}}  # 第 1 个 = 内容无需标注
```

选项按顺序是:内容无需标注 / 含AI生成内容 / 含虚构演绎内容 / 内容含营销信息 / 个人观点，仅供参考 / 内容为转载。
**通用的默认答案是「内容无需标注」**（真实录屏、没有 AI 生成内容、没有虚构情节时它一定不会错;
而「内容为自制：未经作者允许，禁止转载」属于另一个可选的授权声明区块）。

### 9. 封面（必填,而且系统推荐可能一个都没有）

封面是 `*` 必填项。**不要指望系统推荐封面**:实测这个接口返回空列表
（`.ai-cover-list` 带 `is-empty`,页面显示「这里还什么都没有呢～」),而视频已经「上传完成」——
所以必须自己出一张图。

最稳的做法:用本地 ffmpeg 从**视频本身**截一帧当封面。

```bash
# 先用 2×2 拼图扫一眼哪一段画面适合当封面(一次只看一张图,省 token)
ffmpeg -y -v error -i "<视频>.mp4" -vf "fps=1/146,scale=480:-1,tile=2x2" -frames:v 1 sheet.png
# 选中时间点后按原分辨率截一张
ffmpeg -y -v error -ss 292 -i "<视频>.mp4" -frames:v 1 cover.png
```

然后进封面编辑器上传:

```bash
# 1) 打开「封面制作」对话框
{"click_element_by_selector": {"selector": ".cover .cover-slot .cover-empty"}}
# 2) 上传图片（对话框里的 file input 接受 image/png,image/jpeg）
{"upload_file": {"selector": ".cover-editor-panel-select input[type=file]", "path": "<cover.png 绝对路径>", "timeoutMs": 60000}}
# 3) 点「完成」（注意：它是个 div，不是 <button>）
{"click_element_by_selector": {"selector": ".cover-editor-content-right-bottom .button.submit"}}
# 4) 回读：coverEmpty 变成 false 才算设上
client/dsb.cmd --id 3001 js "() => JSON.stringify({coverEmpty: !!document.querySelector('.cover .cover-empty')})"
```

上传成功后回执里会出现 `blob:` 预览,页面文案变成「双比例同步改动」——**4:3 与 16:9 两个比例由
编辑器自动生成**,不用分别上传。对话框里还有模板/文字/贴纸/滤镜,直接跳过即可。

### 10. 提交前对一遍,再点「立即投稿」

```bash
client/dsb.cmd --id 3001 js "() => JSON.stringify({title:(document.querySelector('input[placeholder=\"请输入稿件标题\"]')||{}).value,zone:(document.querySelector('.video-human-type .select-item-cont')||{}).innerText,tags:[...document.querySelectorAll('.tag-pre-wrp .label-item-v2-content')].map(e=>e.innerText.trim()),statement:(document.querySelector('.bcc-select-input-inner')||{}).value,coverEmpty:!!document.querySelector('.cover .cover-empty'),files:[...document.querySelectorAll('.file-item .title-text')].map(e=>e.getAttribute('title'))})"
```

立即投稿是 `span.submit-add`（`存草稿` 是 `span.submit-draft`）:

```bash
{"click_element_by_selector": {"selector": ".submit-add"}}
```

**点完不要重试。** 这一下会让整页重建,回执很可能是 `changed:false` / 文本长度掉到 0 / 走了
`ACTION_UNCERTAIN`——那都不代表没提交。正确做法是先等十几秒,再读页面文案与稿件管理页:

```bash
# 成不成看这句文案
client/dsb.cmd --id 3001 js "() => document.body.innerText.replace(/\s+/g,' ').slice(0,200)"
# 「稿件投递成功 … 立即加热 查看进度 再投一个」= 提交成功

# 再到稿件管理页回读（拿 BV 号）
client/dsb.cmd --id 3001 run go_to_url -p url=https://member.bilibili.com/platform/upload-manager/article
client/dsb.cmd --id 3001 js "() => JSON.stringify([...document.querySelectorAll('a[href*=\"/video/BV\"]')].map(a=>({href:a.getAttribute('href'),text:a.innerText.trim()})))"
```

稿件管理页列表第一行就是刚投的稿件:标题、时长、时间,链接形如
`https://www.bilibili.com/video/BV…`。**注意统计数字可能全是 0、总计数也可能还没算上它**,
那只是审核/转码还没走完,不代表投稿失败——以「稿件投递成功」文案 + 列表里出现该稿件为准。

## 三、这个页面特有的坑

### 坑 1:整页**截不出图**,而没人告诉你

实测在这个页面上 `page.screenshot()` **一次都没成功过**,每次都等满默认 30 秒超时。后果有两层:

- **每条命令白等 30 秒**（一次会话几十条命令 = 十几分钟纯等待）;
- 调用方**完全没有画面可看**,而页面上出现过**整页白屏**,只读文本根本判断不出来 ——
  实测是等到人来说「屏幕刚才都白了」才知道。

服务端现在的行为:自动截图连续失败到阈值就**熔断**一段时间,并在每条回执里给出

```
capture_degraded: true
capture_note: 自动截图不可用(连续 N 次失败,已暂停 X 秒,首次失败原因:Timeout …)…
```

**看到 ``capture_degraded`` 就要改变工作方式**,而不是继续照原样往下点:

1. 画面取证改用文本:`get_browser_state` / `diff_dom_text` / `execute_js` 读 DOM;
2. **不要**只凭文本断言「页面正常」——白屏、样式错乱、遮罩在文本里看不出来;
3. 关键步骤（提交、支付、扫码）请人看一眼浏览器窗口（`request_human_input`）。

调参:`browser.capture.timeoutMs`（单次超时,默认 8000）、`browser.capture.failThreshold`（默认 3）、
`browser.capture.cooldownMs`（默认 120000）。

### 坑 2:登录要人扫,二维码又截不出来

见第 2 步的两条路径。要点是:**别用截图去交二维码**（这一页截图必超时),要么把人叫到机器旁,
要么用 `execute_js` 把 `data:image/png;base64,…` 抠出来落成文件,再用 `/data/**` 给人看。
判断登录成功**看控制台日志**,不要看页面结构。

### 坑 3:整页重建时,点击回执里的 `changed` 与 `coveredBy` 都是假象

打开封面对话框、点「立即投稿」这两下都会让 micro-app **整页重建**（先清空 DOM 再重建）。
在这中间取的探针什么都不能说明,于是老回执会给出这种**看着像结论、其实全错**的组合:

```
observationComplete: false, textLengthAfter: 0, changed: false, coveredBy: {className: "header"}
hint: …目标中心点上命中的是别的元素——很可能被遮挡物吃掉了…
```

实测那两次点击**都生效了**（分区改了、稿件投出去了）。`coveredBy` 报的 `div.header` 是页面被清空时
`elementFromPoint` 撞到残留节点的产物,**那个遮挡物根本不存在**。

服务端现在的行为:

- 发现探针取不到 DOM（或正文被清空）时**先等页面回来再下结论**（最多约 2.5 秒）;
- 等到恢复:回执给 `page_appears_blank: true` + `probeRecovered: true`,并说明「下面的 changed 是恢复之后测的」;
- 等不到恢复:回执给 `probeTrustworthy: false` + `observationComplete: false`,**并盖掉遮挡提示**,
  改成一句可执行的 `hint`。

**你的判据**:看回执里的 `probeTrustworthy` / `observationComplete`。

- 两者都是 `true` → `changed` 与 `coveredBy` 可以采信;
- 有一个是 `false` → **什么都别下结论**,既不要重复点击（可能重复提交）,也不要急着重新取快照
  （页面还没稳),等几秒重新 `get_browser_state` 看真实结果。

### 坑 4:选择器匹配 0 个时报的是 `ELEMENT_NOT_FOUND`,不是超时

`.tag-pre-wrp input.input-val` 这类「把兄弟关系写成父子关系」的选择器,以前会等满动作超时后报
`ACTION_TIMEOUT` + 「不能据此确定元素不存在」,于是会去查监听器、查遮挡,方向全错。
现在动作类命令会先数一遍:匹配 0 个就当场以 `ELEMENT_NOT_FOUND` 失败,并提示
「先 `get_element_count` 复核数量,再检查选择器里的父子/兄弟关系」。

**看到 `ELEMENT_NOT_FOUND` 就去改选择器,别去查遮挡、别去查监听器。**

反过来,**匹配到多个同名控件时,服务会优先挑可见的那个**,并把解析结果写进回执
（`matched` / `chosenIndex` / `selectorNote`）。这一页的标签输入框就是这种「隐藏弹窗里还有一份」的形态。

### 坑 5:批量动作别贪大

一次会话里把「填值 + 回车」拼成 12 条命令的批量,跑在中途页面整页重建,客户端侧直接看不到结果。
**小步走**:一个字段一次调用、加完回读;批量的条目越多,一次页面重渲染吞掉的越多。
长批次用 `client/dsb.cmd --id <id> batch cmds.json --async --wait`,别让它撞 HTTP 超时。

## 三·补、第二次投稿（2026-09-25）新增的实测结论

这次发的是「使用deepseek-browser-use给deepseek账号充值.mp4」（93.7MB / 11分24秒 / 1080P / BV1FAh262EXf）。
与上面那次相比有四处**不一样**，值得单独记：

1. **视频没上传之前，投稿表单根本不渲染**。刚打开投稿页时 `tasks` / `files` / `tags` 都是空数组，
   `input[placeholder='请输入稿件标题']`、`.desc-container .ql-editor`、`.video-human-type`、
   `.bcc-select-input-inner` **全都读不到**（值是 undefined），页面上只有「点击上传或将视频拖拽到此区域」。
   **先 `upload_file` 把视频交进去，表单才出现**，草稿的标题/简介/分区/标签也是这时候才被填上的。
   所以第 3 步「先把草稿字段抄下来」要挪到第 4 步「上传视频」**之后**做，否则抄到的是一片空。
2. **不一定要扫码登录**：这次直接 `go_to_url` 到投稿页就没被踢到 passport，共享 profile 里的登录态是长期的。
   但**每次都要先 `get_url` 确认一次**，别假定它还在。
3. **`wait_for_element` 不能用来判断封面对话框开没开**：对话框里的 file input 天生隐藏，
   而 `wait_for_element` 等的是「可见」，于是必然等满超时——可对话框其实早就开了（`get_element_count` 数得到）。
   判据用 `get_element_count`。
4. **`wait_for_stable` 在这个页面会直接报「读不到内容指纹」**（不是 `stable:false`）。
   这页上要等就用固定 `wait -p seconds=N`，或者轮询 `get_element_count`。

### 模型读不了图时，怎么给投稿挑封面（实操）

``read_image`` 报 `model … does not declare image input` 时，用 **ffmpeg 抽帧 + `ocr_image` 扫关键词**，
就能在完全看不见画面的情况下选出封面帧：

```bash
# 1) 每 40 秒抽一帧(1080p PNG,文件名用 ASCII,省得跟 shell 打架)
ffmpeg -y -v error -ss <秒> -i "<视频>.mp4" -frames:v 1 tmp\bili-frame-070.png
# 2) 逐帧 OCR,只把命中的关键词打出来,不吃 token
client/dsb.cmd --port 10049 --json run ocr_image -p path=<帧的绝对路径>
```

- **`--json` 是必须的**：不加它，dsb 会先打一行 `ocr_image OK 631ms`，`ConvertFrom-Json` 直接解析失败
  （实测这一下白跑了 18 次）。
- 实测用关键词 `Scan QR|Payment successful|Topped-up` 扫 18 帧，直接定位出：收银台二维码在 320s、
  支付成功在 400s、账单页在 520s/560s —— 一次抽帧 + 一轮 OCR 就够选封面了。
- OCR 会把中文按字拆开成「账 号 充 值」，所以**关键词优先用英文**，中文只作辅助。

两个与封面有关的补充：

- 封面 PNG 1920×1080（588KB）完全可用；上传后 B 站 CDN 上的地址变成 `.jpg` 结尾，
  而且最先读到的背景图是**编辑器自动生成的 4:3 版本**（实测 1440×1080）——
  别因为「我传的是 PNG/16:9，怎么变 JPG/4:3 了」就以为传错了。
- 确认封面真的设上了：`!!document.querySelector('.cover .cover-empty')` 为 `false`，
  且封面容器里能读到 `archive.biliimg.com` 的 `background-image`。

### 标签：这次的观察

上次记的「`deepseek` 会被静默丢掉」这次**没有复现**：同一个词写成 `DeepSeek` 一次就加上了。
所以那不像是关键词黑名单，更像当时的偶发。**结论不变**：一条一条加、加完立刻回读，
被吞了就换个词，别在同一个词上反复重试。

### 动作类命令撞上伪故障时（`SPURIOUS_DISPATCH`）

这次往标签框里填第一个标签时，`input_text_by_selector` 回的是
`[SPURIOUS_DISPATCH] 底层对象已被释放…`——**动作类不会自动重发**，回执 `ok:false` 但「有没有生效」未知。
正确姿势就是服务提示的那句：**先用只读命令确认页面状态**。这次回读标签列表，发现那个标签确实没加上、
输入框也空了，**再重试**就成了。不要看到 `ok:false` 就重发（可能重复提交），也不要因为回执难看就改选择器。

## 四、收尾

- 视频已发布的话,任务可以 `close` 掉;登录态留在 profile 里,下次投稿不用再扫码。
- 稿件投出去之后**在浏览器里停一会儿**（比如停在稿件管理页或视频页),方便人自己复核;
  要收就说一声,别默默把窗口关掉。
