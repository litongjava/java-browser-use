---
name: wecom-invoice-contract
description: 用 deepseek-browser-use 完成企业微信（work.weixin.qq.com 管理后台）「订单发票申请 + 订单合同申请 + 电子签章申请」的实操手册：先在订单详情页回读状态判断「现在到底能不能开发票」（认证审核没完时那里写着「审核完成后可申请」，审核通过后才变成可点的「申请发票」），再进 `.mall_invoice_dialog_container` 弹窗选电子发票类型与抬头类型（两个 radio 初始都没选中，必须点一下后面的「发票抬头 / 纳税人识别号」才会展开，且抬头与企业全称、税号会自动带出），提交后页面变成「审核中 / 查看发票」且没有任何自助修改或撤销入口；合同侧「申请电子合同」会被电子签章卡住（跳回签章申请页），纸质合同则要打印盖鲜章寄回约 15 个工作日；电子签章是四步向导，前两步补充企业信息与个人实名认证（扫脸）、第三步对公打款验证（用公司账户打一笔随机小金额）、第四步企业授权要下载授权书模板、只盖企业公章、扫描成 ≤5MB 的 PDF 再上传并勾选三个协议，而盖章只能由人做且可能要等几天，是必须停下来等的交接点。文内还写透了「小规模纳税人不能抵扣进项税、所以开专票是零收益」这个业务判断，以及该站点特有的坑：后台是内层滚动容器而非 window、快照只覆盖当前视口、索引失效极快、企业微信自己的弹窗类名不一定被 get_modals 命中（本次是 `.mall_invoice_dialog_container`）、隐藏的 `input[type=file][accept=".pdf"]` 要用 upload_file 传 selector、ok=true 不等于成功必须回读断言。数据全部脱敏，替换占位符即可复用。
whenToUse: 需要在企业微信管理后台处理认证/服务的订单收尾（开发票、签合同、申请企业电子签章），或需要在 work.weixin.qq.com 这类 hash 路由 + 内层滚动容器的后台里，用「先回读状态再动手」的方式走一个多步向导，并且其中若干步（扫脸、对公打款、盖章扫描）必须交给真人完成时。
---

# 企业微信：订单发票 + 订单合同 + 电子签章（实操手册）

一句话流程：**读订单详情页判断状态 → 能开发票就开（先问纳税人资格）→ 合同走电子路径会被签章卡住 →
签章四步走到第 4 步「企业授权」停下，把盖章交给真人**。

本文是「怎么用 deepseek-browser-use 把这三件事做稳」的实战记录，不是财税指南，也不是合同法指南。
命令的通用语义看主技能 `deepseek-browser-use`，本文只讲企业微信这个站点上「哪一步会翻车、怎么写才过」。

> **先记住这条主线**：这三件事不是并列的，而是**一条依赖链**——
>
> ```
> 认证审核通过 ──┬─→ 能申请发票（否则显示「审核完成后可申请」）
>                └─→ 能申请合同 ──→ 电子合同 ──→ 需要企业电子签章
>                                                  └─→ 签章第4步需要盖章的授权书（人做，可能要等几天）
> ```
>
> 所以**永远先读订单详情页**，再决定这一轮能推进到哪一步。不要假设「付了钱就能开发票」。

## 0. 边界与合规

- **做到哪一步为止**：把发票申请提交掉、把签章推进到「只差盖章」、把合同推进到「只差签章」。
  **提交动作本身**（点「提交申请」「提交」）在本文场景里是允许的，因为它是**业务流程的必经步骤且可撤回/可查**；
  但下面这些**一律由人做**。
- **必须由人做的部分**：
  | 环节 | 为什么必须人做 |
  | --- | --- |
  | **人脸识别 / 扫脸** | 实名认证的法定环节，必须本人 |
  | **对公打款** | 要真的从公司账户转出一笔钱，智能体没有支付能力 |
  | **盖章** | 公章是物理印章，必须打印后人工加盖 |
  | **微信支付 / 付款** | 资金动作 |
  | **手机短信验证码** | 只有本人手机能收到 |
- **不做的事**：不代替用户确认税务口径（普票/专票是**财务决策**，必须问用户）；不猜发票抬头与税号
  （页面上会自动带出，带不出就停下来问）；不伪造授权书、不 P 公章。
- **要提醒用户的事**：
  - 发票类型一旦提交**页面没有自助修改或撤销入口**，改类型只能联系审核机构客服；
  - 电子签章的授权书**只能盖企业公章**，盖了别的章（合同章/财务章）会被驳回；
  - 上传的授权书**不要修改模板文字内容**。

## 1. 开工前

### 1.1 服务与浏览器

```json
POST http://localhost:10049/playwright/command
{"method":"start","params":{"headless":false,"browser":"chrome"}}
```

- **`headless:false` 是必须的**：这条链路里有扫脸、扫码、盖章三处要人接手，无头窗口人看不见。
- **`browser` 不传就等价于本机 Google Chrome**（没装才退回内置 Chromium）。这里**不需要像中国商标网那样切
  Firefox** —— 企业微信后台在 Chromium 系下工作正常（实测 Chrome 全流程通过）。
- **换引擎/换浏览器等于换 profile 与登录态**：`chrome`／`chromium` 共用 `browser.profileDir` 那一份；
  `edge` 与 `firefox` 各有自己一份。企业微信的登录态跟着 profile 走，**换过去要重新登录一次**。
  要复用已登录的会话就别乱换。
- 开工前先自检，避免「服务没起来 / 引擎不是你要的」这类白跑：

```shell
curl -s http://localhost:10049/playwright/health
python client/dsb.py --port 10049 selftest
```

- 服务方法名拿不准就先问它（**别猜**，猜错只会拿到一句「不支持的方法」）：

```json
{"method":"list_methods","params":{"filter":"modal"}}
{"method":"get_config"}
{"method":"list_tasks"}
```

- **收工前先 `close` 任务再停服务**，否则强杀服务会留下孤儿浏览器占着 profile，下一次 `start` 卡在启动超时
  （详见主技能第 32 条）。全部收掉可以直接用 `shutdown`。

### 1.2 开工前必须问清的字段

一次问全，避免走到弹窗里才发现缺信息：

| 组 | 要问的 | 为什么 |
| --- | --- | --- |
| 身份 | 用哪个管理员账号操作（一般是超级管理员） | 签章与实名认证绑的是**具体自然人** |
| 税务 | **公司是小规模纳税人还是一般纳税人？** | **决定发票开普票还是专票**，见第 3.4 节 |
| 发票 | 开票资料是否与页面自动带出的一致（抬头、税号） | 不一致要停下来问，别自己改 |
| 签章 | 管理员姓名、身份证号、对公账户（户名/账号/开户行） | 签章第 2、3 步要用 |
| 合同 | 要电子合同还是纸质合同 | 决定走哪条路 |
| 授权书 | **能不能盖章、大概几天能盖好** | 这是**排期信息**，决定这一轮做到哪里停 |

### 1.3 留档（排查用）

- 服务端：`logs/trace/<yyyyMMdd>/`（`steps.log` 时间线、`calls.jsonl` 逐条 JSON、`NNNNNN-<任务id>-<方法>.json`
  完整请求响应、`uploads.log` 上传记录）。
- 客户端：`client/dsb.py`（跨平台、退出码区分传输错/业务失败/用法错、默认脱敏），或
  `scripts/trace/browse.ps1`（PowerShell 习惯）。
- 追踪日志**默认脱敏**（手机号、18 位证件号/统一社会信用代码、邮箱、长数字 → `***`），但这是**尽力而为**：
  企业名、姓名、门牌号这类认不出来的不会被掩掉。任务结束提醒用户清理（`cleanup`，**默认只预演**）。
- 上传的临时文件（授权书 PDF）落在服务端暂存目录（`start` 回执的 `data.browser.upload.dir`），**不会自动清理**。

## 2. 订单详情页与状态判断

### 2.1 怎么进订单详情页

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/orderDetail?order_id=<订单号>"}},
  {"wait":{"seconds":12}}
]}}
```

- 企业微信管理后台是 **hash 路由 SPA**：`#` 后面才是路由，**直接 `go_to_url` 带完整 hash 的 URL 比点菜单稳得多**
  （实测点左侧「我的企业」→ 再找订单入口，经常要多跳两次，而直接给 URL 一步到位）。
- `<订单号>` 从「企业微信认证」的下单记录里拿，**也可以从当前页面反查**：先在后台随便进一个能显示订单的页面，
  再用 `execute_js` 读 `location.href`。
- 页面要等 **10–13 秒**才渲染完（后台是重型 SPA，异步拉好几组接口），`wait` 给足，别用 3 秒。

### 2.2 页面上有什么（实测）

订单详情页渲染出来是这样一组字段，**把它们当作状态机来读**：

| 区块 | 内容 |
| --- | --- |
| 标题 | 认证详情 |
| 商品 | 企业微信认证 ¥300 |
| 有效期 | `<起>-<止>`（审核通过后才会有值） |
| 认证规模 | 如「小型企业规模」 |
| 认证主体 | 如「企业法人」 |
| 申请人 | `<管理员姓名>` |
| 下单时间 | `<yyyy/MM/dd HH:mm>` |
| 订单编号 | `<订单号>` + 「复制」按钮 |
| 支付方式 | 如「微信支付」 |
| **订单合同** | 「申请合同」（或已申请后的状态） |
| **订单发票** | **「审核完成后可申请」** 或 **「申请发票」** 或 **「审核中 / 查看发票」** |
| 审核机构 | 第三方审核机构：`<审核机构名称>` + 咨询电话 + 咨询邮箱 + 在线客服微信号 `<客服微信号>` + 咨询时间 |
| 操作 | 重新认证 / 咨询客服 |

回读整页文本用 `get_browser_state`，或只要这一段就切片段：

```js
(() => {
  var t = document.body.innerText;
  var i = t.indexOf('订单');
  return { url: location.href, seg: t.slice(i >= 0 ? i : 0, i + 1400) };
})()
```

### 2.3 状态是会变的：先读再判断（本节最重要）

**「能不能开发票」不取决于你，取决于认证审核状态。** 实测同一条订单在两天内出现过两种文案：

| 订单发票处的文案 | 含义 | 你该做什么 |
| --- | --- | --- |
| **审核完成后可申请** | 认证还在审核中 | **不要点**（点不动），告诉用户等审核；先去推进签章/合同 |
| **申请发票**（可点） | 审核已通过 | 可以走第 3 节 |
| **审核中 / 查看发票** | 发票申请已提交 | 结束，等 1–7 个工作日 |

同样地，**企业信息页会透露审核结果**：认证通过后，「前往认证」按钮旁边会出现

```
当前认证有效期至 <yyyy>年<M>月<D>日
```

而订单详情页的「有效期」也会从空变成 `<起>-<止>`。**这两处是判断「审核过没过」最省事的信号**，
比去翻通知快。

所以每轮开工的第一件事固定是：

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;var i=t.indexOf(\"订单发票\");return {url:location.href,seg:i>=0?t.slice(i,i+260):t.slice(-600)}})()"}},
  {"expect":{"js":"document.body.innerText.indexOf('审核完成后可申请') < 0","truthy":true}}
]}}
```

> **`expect` 的用法**：断言在命令执行**之后**求值，断言没过时批次整体仍是 `code:0`、`data.failed` 仍是 0，
> 但 `data.expectFailed` 会计数、`msg` 会指出是哪一步没过，`data.note` 说明「命令都执行成功，是断言没过」。
> 这是「动作发了、状态没变」唯一能当场暴露出来的办法。

### 2.4 两个 id 不是一回事（踩过）

**订单详情页用 `?order_id=`，合同/签章页用另一个 id，别混用、别互推。**

| 用途 | URL 形态 | 用哪个 id |
| --- | --- | --- |
| 订单详情 | `#/authCenter/orderDetail?order_id=<订单号>` | **订单号**（很长的一串数字） |
| 合同选择类型 | `#/businessCommon/contract/signIndex/certification/<订单id>` | **订单 id**（**与订单号不同**，形如时间戳串） |
| 签章申请 | `#/businessCommon/contract/signApply?businessType=certification&orderId=<订单id>` | **同一个订单 id** |

实测这两个值**完全不一样**（订单号 19 位、订单 id 是另一种形态）。**不要拿订单号去拼合同 URL** ——
会打开一个空页面或报错，然后你会以为是权限问题白排查半天。

**怎么拿到订单 id**：从订单详情页点一次「申请合同」，看跳转后的 URL 里的 `<订单id>`，记下来；
之后所有签章/合同 URL 都用它。

## 3. 发票申请

### 3.1 弹窗结构（实测）

点「申请发票」后弹出 **`.mall_invoice_dialog_container`**：

```
申请发票
申请后 7 个工作日内通过「企业微信团队」发送电子发票

开票项目
企业微信认证 ( ¥ 300 )

电子发票类型
  ○ 增值税 普通 发票      不可用于抵扣税款
  ○ 增值税 专用 发票      可用于抵扣税款

抬头类型
  企业 | 组织 | 个人

                                        取消   提交申请
```

**关键：两个 radio 初始都没选中，后面的字段是隐藏的。必须点 radio 才会展开。**

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"click_element_by_selector":{"selector":"a:has-text('申请发票')","mode":"auto"}},
  {"wait":{"seconds":8}},
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');if(!d)return {none:true};var r=d.querySelectorAll('input[type=radio]');return {n:r.length,checked:[].slice.call(r).map(function(e){return e.checked}),txt:d.innerText.slice(0,400)}})()"}}
]}}
```

选中「增值税普通发票」（**第 0 个 radio**）之后，再回读一次，字段才出现：

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');if(!d)return {none:true};var r=d.querySelectorAll('input[type=radio]');r[0].click();return {checked:r[0].checked}})()"}},
  {"wait":{"seconds":4}},
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');if(!d)return {none:true};return {txt:d.innerText.slice(0,900),inputs:[].slice.call(d.querySelectorAll('input,textarea')).map(function(e){return {type:e.type||'ta',ph:e.placeholder||'',v:e.value||'',chk:e.checked}})}})()"}}
]}}
```

**选「增值税普通发票」展开后的字段（实测）**：

| 字段 | 是否自动带出 | 备注 |
| --- | --- | --- |
| 抬头类型 | 默认「企业」 | 三选一：企业 / 组织 / 个人 |
| **发票抬头** | ✅ **自动带出企业全称** | 不用手填 |
| **纳税人识别号** | ✅ **自动带出 18 位统一社会信用代码** | 不用手填 |
| 开户银行 | — | **选填**（placeholder 写着「选填，所填信息将展示在发票上」） |
| 银行账号 | — | **选填** |

> **普票的开户银行/账号留空即可** —— 它们是选填，且普通发票本来就不需要这两项。
> 别为了「看起来完整」去填，填了会**印在发票上**，反而多一层信息暴露。

**选「增值税专用发票」展开的字段更多**（要一般纳税人资格与完整开票资料：地址、电话、开户行、账号）。
所以**开专票之前必须先确认纳税人资格**，见 3.4。

### 3.2 提交与收尾

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');if(!d)return {none:true};var b=[].slice.call(d.querySelectorAll('a,button')).filter(function(e){return e.innerText.trim()==='提交申请'})[0];if(!b)return {noBtn:true};b.click();return {ok:true}})()"}},
  {"wait":{"seconds":10}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;return {dlg:!!document.querySelector('.mall_invoice_dialog_container'),tail:t.slice(-700)}})()"}}
]}}
```

提交成功会就地变成一张**回执卡**：

```
申请发票
申请已提交

预计在 1-7 个工作日内通过「企业微信团队」发送发票申请结果及电子发票
发票金额      ¥300.00
发票类型      增值税普通发票
发票抬头      <企业全称>
申请人        <管理员姓名>
申请时间      <yyyy>年<M>月<D>日 <HH:mm>
                                        完成
```

点「完成」关掉，然后**回读订单发票处**确认状态已翻转：

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');if(d){var b=[].slice.call(d.querySelectorAll('a,button')).filter(function(e){return e.innerText.trim()==='完成'})[0];if(b)b.click()}return {ok:true}})()"}},
  {"wait":{"seconds":6}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;var i=t.indexOf('订单发票');return {seg:i>=0?t.slice(i,i+160):null}})()"}},
  {"expect":{"js":"document.body.innerText.indexOf('审核中') >= 0","truthy":true}}
]}}
```

预期回读到：

```
订单发票
审核中
查看发票
```

### 3.3 没有自助修改入口（踩过）

提交后订单发票处**只有「查看发票」，没有「撤销」也没有「修改」**。实测把整页的可点元素都列出来看过：

```
返回 / 申请合同 / 查看发票 / 关于腾讯 / 用户协议 / 使用规范 / 隐私政策 / 更新日志 / 帮助中心 / 中文
```

**没有改发票类型的路。** 要改只能找审核机构客服：

| 渠道 | 值 |
| --- | --- |
| 在线客服微信 | `<客服微信号>` |
| 邮箱 | 页面「审核机构」里给的咨询邮箱 |
| 电话 | 页面「审核机构」里给的咨询电话 |
| 时间 | 页面「审核机构」里给的咨询时间（一般是工作日 9:30~17:30） |

**所以：提交前一定要把类型确认清楚。** 这是本文档反复强调「先问纳税人资格」的原因。

> 遇到这种情况的交付话术：**别自己承诺「我帮你改」**。如实说「已提交、页面没有修改入口，
> 要改需要联系客服，我可以帮你起草消息（附订单号 + 要改成的类型 + 需要补的资料）」。

### 3.4 普票还是专票：纳税人资格决定（这一节写透）

**这是本 skill 最该讲清的业务判断，因为用户真的会问「小规模能不能抵扣」。**

#### 结论先给

| 纳税人身份 | 进项税能否抵扣 | 该开哪种 |
| --- | --- | --- |
| **小规模纳税人** | ❌ **不能** | **普票**（专票零收益 + 多填资料） |
| **一般纳税人** | ✅ 能 | 专票（若在乎那十几块钱） |

#### 为什么小规模不能抵扣

小规模纳税人适用**简易计税**：

```
一般纳税人：应纳税额 = 销项税 − 进项税      ← 有「进项税」这一环，所以专票能抵
小规模纳税人：应纳税额 = 销售额 × 征收率    ← 整条链里没有「进项税」这个概念
```

小规模按 **3%**（现行优惠期常为 **1%**）的**征收率**直接对销售额计税，**不存在进项抵扣的机制**。
所以：

> **取得增值税专用发票 ≠ 可以抵扣。抵扣的前提是你是一般纳税人。**

拿到专票也只能**价税合计全额计入成本/费用**，和拿普票的账务结果一样。

#### 三个常见误区

1. **「小规模不能收专票」——错。** 小规模**可以**合法取得增值税专用发票（包括电子专票），不违法；
   只是**没有抵扣收益**，还让供应商多填一堆资料（开户行、账号、地址、电话）。
2. **「小规模期间先囤专票，等转成一般纳税人再抵」——行不通。** 小规模期间取得的专票，
   转为一般纳税人后**不得追溯抵扣**，只能从**转登记日的下期**起对之后取得的专票抵扣。
   为了「以后抵」去开专票是白费。
3. **「专票一定比普票好」——对小规模不成立。** 唯一区别是专票的红冲流程更麻烦。

#### 落到一笔 ¥300 的认证服务上

即使是一般纳税人，能抵的也就十几块：

| 若按 | 进项税 ≈ |
| --- | --- |
| 6%（现代服务） | ¥300 ÷ 1.06 × 6% ≈ **¥16.98** |
| 3% | ≈ **¥8.74** |

**所以「小规模直接开普票」不只是合规上正确，经济上也几乎没损失。**

#### 怎么确认纳税人资格（问用户，不要猜）

**这个信息不在公开渠道**，公开检索查不到，必须让用户自己看：

- **电子税务局 → 我的信息 → 纳税人资格信息**（看「增值税一般纳税人」资格的生效日期）
- 或者直接问**代账会计**（最省事，一句话的事）

判断参考：

- 年应税销售额**超过 500 万**会被强制登记为一般纳税人；
- **新成立的小公司默认是小规模**；
- 一般纳税人通常会有「一般纳税人资格认定」的记录。

#### 标准问法（照抄）

> 「这笔 ¥300 的认证费发票，你们公司是**小规模纳税人**还是**一般纳税人**？
> 如果是小规模，**开普票就行**——小规模是简易计税，拿到专票也不能抵扣进项税，
> 开专票只是让我们多填开户行、账号、地址、电话，没有任何收益。
> 如果是一般纳税人，专票能抵，但 ¥300 也就抵十几块钱。」

**用户答「小规模」→ 直接开普票，不要再纠缠。** 用户答「不确定」→ 让他去电子税务局看一眼，
或者**先别提交**（见 3.3：提交后改不了）。

## 4. 合同申请

### 4.1 选择合同类型

订单详情页点「申请合同」→ 进入：

```
#/businessCommon/contract/signIndex/certification/<订单id>
```

页面内容（实测）：

```
申请合同
请选择合同类型
  企业电子签章申请中                       ← 状态提示，见 4.2

  申请电子合同
  可在线签署并加盖双方电子签章，立即生成电子合同。
  首次签署需申请企业电子签章。

  申请纸质合同
  可下载并打印后加盖鲜章，约需15个工作日寄回。
```

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var a=[].slice.call(document.querySelectorAll('a')).filter(function(e){return e.offsetParent!==null&&e.innerText.trim()==='申请合同'})[0];if(!a)return {none:true};a.click();return {ok:true}})()"}},
  {"wait":{"seconds":10}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;return {url:location.href,tail:t.slice(-900)}})()"}}
]}}
```

### 4.2 电子合同被电子签章卡住（依赖链，踩过）

**点「申请电子合同」不会生成合同，而是直接跳到电子签章申请页：**

```
#/businessCommon/contract/signApply?businessType=certification&orderId=<订单id>
```

也就是说：

```
申请电子合同 ──→ 需要企业电子签章 ──→ 签章第4步需要盖章的授权书 ──→ 盖章由人做
```

**签章没完成，电子合同就申请不了。** 页面上那句 **「企业电子签章申请中」** 就是判断依据 ——
读到它就别在合同页耗着了，直接去推进签章（第 5 节）。

**怎么判断签章到底完成没有**：重新进一次 `signApply` 页，看它停在**第 4 步**还是显示**已完成/可签署**。
不要靠记忆，也不要靠「上次点过」来判断。

### 4.3 纸质合同（备选）

**申请纸质合同**：下载并打印 → 加盖**鲜章** → 寄回 → 约 **15 个工作日**。

什么时候走这条：

- 用户明确要求纸质；
- 或者电子签章的盖章排期太久（比如要等两周），而合同又急着签。

**建议话术**（实测有效）：**优先等电子签章**。因为反正都要盖一次章，电子签章那份盖完，
合同就是几分钟的事；纸质反而多等半个月。只有「章短期内盖不了 + 合同又必须马上生效」时才走纸质。

## 5. 电子签章四步

### 5.1 总览

入口：`#/businessCommon/contract/signApply?businessType=certification&orderId=<订单id>`

页面上是一条四步进度条，**每一步做完会打勾**：

| 步 | 名称 | 谁做 | 关键点 |
| --- | --- | --- | --- |
| 1 | **补充企业信息** | 智能体 | 企业名称 / 统一社会信用代码 / 住所 **自动带出**，核对即可 |
| 2 | **个人实名认证** | **人** | 管理员姓名 + 身份证号 + **扫脸** |
| 3 | **对公打款验证** | **人** | 用公司账户向指定收款方打一笔**随机小金额** |
| 4 | **企业授权** | **人（盖章）** | 下载授权书 → 打印 → 盖**企业公章** → 扫描 PDF ≤5MB → 上传 → 勾三个协议 → 提交 |

回读进度条判断当前停在哪一步：

```js
(() => {
  var t = document.body.innerText;
  return { url: location.href, tail: t.slice(-1100) };
})()
```

第 4 步的页面文本（实测）长这样，**用它当锚点判断「是不是已经到第 4 步了」**：

```
申请电子签章
补充企业信息 / 个人实名认证 / 对公打款验证 / 4 企业授权
下载授权书，盖章后上传提交，以完成企业授权
企业授权后，你和其他被授权人可获得企业电子签章使用权限，代表企业签署合同。
被授权人(选填)  添加
可添加除你以外的其他企业管理员，你和被授权人均可签署电子合同。
企业授权书
上传授权书文件
需 下载授权书模板，盖章扫描后上传，请勿修改授权书文字内容
需加盖企业公章，请勿加盖其他印章
支持上传 PDF 格式扫描件，大小不超过 5 M，请保证文件内容和公章清晰可辨认
电子签章服务由"腾讯电子合同平台"与"天威诚信"提供。提交前请阅读并同意
《腾讯电子合同签署平台系统使用须知》《天威诚信电子认证协议》及《天威诚信用户隐私协议》
提交
```

### 5.2 第 1 步：补充企业信息

- 企业名称、统一社会信用代码、住所**自动带出**（来自已认证的企业资料）。
- **智能体的活就是核对**：回读一次，和营业执照比对，**不一致就停下来问用户**，不要自己改。
- 核对完进入第 2 步。

### 5.3 第 2 步：个人实名认证（人做）

- 需要：**管理员姓名 + 身份证号 + 人脸识别（扫脸）**。
- **扫脸必须由人做**，而且**必须在有头窗口里做**（`headless:false`）。
- 用**正式的人机协同 API**，不要自己在聊天里贴截图路径：

```json
{"method":"request_human_input",
 "params":{"prompt":"请在弹出的浏览器窗口里完成个人实名认证的扫脸","timeoutSeconds":600}}
```

  传 `selector`（指向二维码/扫脸区域）时，服务会把该元素截成 `data.imageBase64` 一并返回，
  并**把当前页签带到窗口最前**，人可以直接看到该点哪里。

- 人做完之后：

```json
{"method":"get_human_input","params":{"requestId":"<上一步返回的 requestId>","timeoutSeconds":60}}
```

  `data.status` 为 `pending` / `answered` / `expired`。**超时只表示还没收到答复，不代表验证没通过。**

> **重要**：用户**直接在浏览器里操作**不会自动更新人工请求记录，`get_human_input` 可能一直是 `pending`。
> **不要只等这个字段**，也不要直接跳过验证 —— 重新 `get_browser_state` 读页面，**看到进度条第 2 步打勾**才算通过。

### 5.4 第 3 步：对公打款验证（人做）

- 页面给一个**对公账户**（户名预填 / 账号 / 开户行），要求用户**从公司账户**打一笔**随机小金额**过去。
- 实测金额是**几分到几毛**级别（本次是 **¥0.26**），收款方通常是**第三方支付公司**，
  不是「腾讯」这类直觉上的主体 —— **不要因为收款方名字陌生就让用户别打**，那是正常的资金通道。
- 页面上的信息形态：

```
对公账户名称（预填）
账号            <对公账号>
开户行          <开户行>
打款金额        <打款金额>
付款方可能是      <第三方支付公司名称>（账号 <...>）
```

- **智能体在这里只做两件事**：① 把页面上的收款信息**准确读出来给用户**（用 `execute_js` 回读，
  不要靠肉眼从截图抄）；② 等用户说打完款后，回读页面确认金额被接受。
- **不要让用户反复打款**。金额和账户读错一次，就要用户重来一次。

### 5.5 第 4 步：企业授权 —— 明确的交接点

**这一步是整条链上唯一必须「停下来等」的地方，而且可能要等几天。**

流程：

```
下载授权书模板 → 打印 → 盖企业公章（只盖公章）→ 扫描成 PDF（≤5MB）→ 上传 → 勾三个协议 → 提交
```

#### 硬性要求（页面上写死的，别自作主张）

| 要求 | 值 |
| --- | --- |
| 格式 | **PDF** 扫描件 |
| 大小 | **不超过 5M** |
| 印章 | **只能盖企业公章**，勿加盖其他印章 |
| 内容 | **请勿修改授权书模板的文字内容** |
| 清晰度 | 文件内容和公章清晰可辨认 |

#### 上传：用 `upload_file` 传 `selector`

页面上的 file input 是**隐藏的**（`input[type=file][accept=".pdf"]`），**不进快照、拿不到索引**。
**用选择器直接传即可 —— `upload_file` 不需要元素可见，也不必想办法把它显示出来**：

```json
{"method":"upload_file",
 "params":{"selector":"input[type=file][accept=\".pdf\"]","path":"<授权书.pdf>"}}
```

- `path` 是**服务端**能打开的路径。**智能体与浏览器不在同一台机器时**（客户端-服务器模式），
  先把文件 POST 到服务端暂存接口，再用回执里的 `path`：

```shell
curl -F "file=@<授权书.pdf>" http://<服务端>:10049/playwright/upload
# → {"data":{"filename":"<授权书.pdf>","path":"<服务端暂存目录>/<授权书.pdf>","relativePath":"...","size":...,"sha256":"..."}}
```

- 上传后回读该项文本确认（出现文件名 = 已进列表）。

#### 提交前必须勾的三个协议

```
《腾讯电子合同签署平台系统使用须知》
《天威诚信电子认证协议》
《天威诚信用户隐私协议》
```

**这三条是法律性质的授权同意**，见第 0 节。勾选后点「提交」。

#### 「被授权人（选填）」

- 页面上有「被授权人(选填)」+「添加」按钮，可添加**除你以外的其他企业管理员**，
  你和被授权人都可以签署电子合同。
- **默认不填**。要加人先问用户（这关系到谁能代表公司签合同，是权限决策）。

#### 交接点：停下来等，不要轮询

**实测用户会明确说「需要等几天才可以盖章」。** 所以：

- **不要反复回读页面**看有没有盖章（没有意义，章在人手里）；
- **不要为了「推进」去点提交**（没上传文件提交必然失败，还可能留下失败记录）；
- **不要自己生成或 P 一个授权书**（违法且必然被驳回）。

正确做法是**明确交接**：

```json
{"method":"request_human_input",
 "params":{"prompt":"第4步需要企业授权书：请打印模板、加盖企业公章、扫描成 ≤5MB 的 PDF，然后把文件路径给我，我来上传提交。这一步需要多久都可以，弄好告诉我即可。","timeoutSeconds":86400}}
```

然后把当前进度如实写进交付话术（第 8 节），**结束这一轮**。等用户回来给文件路径，再继续。

### 5.6 状态提示怎么读

页面上的状态文案是**唯一的真相**，不要靠记忆或推测：

| 文案 | 含义 |
| --- | --- |
| 第 N 步高亮 / 前面步骤打勾 | 当前停在第 N 步 |
| **企业电子签章申请中** | 签章未完成，电子合同申请不了（见 4.2） |
| 提交后出现「已完成」类文案 | 签章申请已提交，等审核 |

## 6. 该站点的通用坑

### 6.1 后台内容区是内层滚动容器，不是 `window`

**这是本站点最基础的一个坑**：企业微信后台的滚动发生在**内层容器**上。

```js
window.scrollY        // 恒为 0
window.scrollTo(0, 0) // 无效
```

**后果**：靠「滚到顶部再取快照」来让元素进入视口是**没用的**。
**正解是别依赖滚动** —— 优先用**选择器/文本**定位，它们不需要元素在视口里：

```json
{"click_element_by_selector":{"selector":"a:has-text('申请发票')","mode":"auto"}}
{"click_element_by_text":{"text":"申请合同","mode":"auto"}}
```

### 6.2 快照只覆盖当前视口，而且索引失效极快

- `get_browser_state` 的 `data.text` **只包含当前视口内的元素**。视口外的元素**没有 `[index]`**，
  你会在快照里完全看不到它（不是「不存在」，是「没进来」）。
  **要一次拿到首屏之外的元素，给 `get_browser_state` 传 `viewportExpansion`**（视口外扩像素，默认 `0`）：

  ```json
  {"id":2001,"method":"get_browser_state","params":{"highlight":false,"viewportExpansion":1500}}
  ```

  回执里的 `data.pixels_above` / `data.pixels_below` 会告诉你上下还差多少没进快照——
  **它们不为 0 就说明扩得还不够**。
- **索引失效快**：这个后台异步渲染多，**动作之后必须重新取快照**，否则会拿到

```
click_element_by_index 失败：元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引
```

- **结论**：在这个站点上，**能用选择器就别用索引**。索引只适合「刚取完快照、立刻点」的场景。

### 6.3 弹窗：企业微信自己的弹窗类名要靠启发式兜底才认得出来

- `get_modals` / `close_modal` 是**弹窗的正确工具**（内部走**真实鼠标点击**，
  而 JS 派发在这些控件上常常完全无效）。
- **这个站点是个很好的例子**：本次发票弹窗的类名是 **`.mall_invoice_dialog_container`**，
  既不在 ant/Element/vxe/layui 那套类名规则里，也没有 `role=dialog`。

**服务端现在有三轮扫描兜底**（框架选择器 → 类名线索 → 几何兜底），每条结果都带 `data.matchedBy`
说明命中来源：

| `matchedBy` | 含义 |
| --- | --- |
| `selector:.ant-modal-wrap, .ant-drawer-open` 等 | 框架专用选择器（置信度最高） |
| `heuristic:class-name` | 类名里有 `dialog`/`modal`/`popup`/`overlay`/… —— **`.mall_invoice_dialog_container` 靠这一轮命中** |
| `heuristic:fixed-overlay` | 可见 + 够大 + `position:fixed` 或 `z-index > 50` 的几何兜底 |

所以：

- `data.count = 0` 现在是**可信的**（三轮都跑过，`data.scannedBy` 会列出来），不必再怀疑「是不是漏检了」；
- `data.count > 0` 时看 `matchedBy` 判断置信度：`selector:…` 最可信，`heuristic:…` 是兜底；
- **弹窗没有标题、也没有 `role=dialog` 时按类名关**：
  ```json
  {"close_modal":{"which":"class:mall_invoice_dialog_container"}}
  ```
  `get_modals` 返回的每一项都带 `className`，照着填即可。

**仍然建议保留的一手**：用直接查询确认弹窗在不在（它比任何启发式都确定）：

```js
!!document.querySelector('.mall_invoice_dialog_container')
```

**一套稳妥的组合**：先 `get_modals`（拿到 `closePoint`/`buttonPoints` 与 `matchedBy`），
再用 `close_modal`（真实鼠标 + 关完校验数量）；启发式都命不中时才退回自己算坐标 + `mouse_click`：

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"get_modals":{}},
  {"close_modal":{"which":"class:mall_invoice_dialog_container","button":"取消"}}
]}}
```

> 真要走 `mouse_click`：先自己按类名查、算中心坐标，再点。**注意坐标顺序**：`mouse_click` 的参数是
> `x`,`y`；而很多人在 JS 里会习惯性写成 `top,left`，**别读错**。

### 6.4 radio 点了要重新读 DOM 才会看到展开的字段

- **`input[type=radio].click()` 能生效**（实测），不需要真实鼠标。
- 但**点完必须重新读一次弹窗 DOM** 才能看到新展开的字段 —— 因为框架是异步渲染的。
- 别在同一批里「点 radio → 立刻读字段」，中间给 **3–4 秒**：

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');var r=d.querySelectorAll('input[type=radio]');r[0].click();return {checked:r[0].checked}})()"}},
  {"wait":{"seconds":4}},
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');return {txt:d.innerText.slice(0,900)}})()"}}
]}}
```

### 6.5 `ok=true` 不代表成功

**这是主技能反复强调、在这个站点上尤其致命的一条。**

- 点击类方法只保证「动作没抛异常」。实测在这个后台里，**点「免费开通」「开通」这类按钮经常返回
  `ok=true` 且 `data.changed=false`，页面毫无变化**（弹窗没渲染出来，或路由没跳）。
- 判断标准：

| 看什么 | 说明 |
| --- | --- |
| `data.changed` | 是否观察到变化（`false` 不代表失败，但也不代表成功） |
| `data.effective` | 这次点击有没有真的改变页面 |
| `data.mode` | **实际用上的**那一档（`native`/`mouse`/`js`）。**看到 `js` 就当没点成** |
| `data.coveredBy` | 目标中心点实际命中的是别人（被遮罩挡住）→ 先关遮挡物再点 |

- **最可靠的做法是加 `expect` 断言**，让「动作发了、状态没变」当场暴露：

```json
{"method":"commands","params":{"stopOnError":false,"stopOnExpectFailure":true,"commands":[
  {"click_element_by_selector":{"selector":"a:has-text('申请发票')","mode":"auto"}},
  {"wait":{"seconds":8}},
  {"expect":{"js":"!!document.querySelector('.mall_invoice_dialog_container')","truthy":true}}
]}}
```

- **回读页面文本**永远是最稳的确认方式（本文档每个关键动作后面都跟了一次 `execute_js` 回读）。

### 6.6 hash 路由：直接跳比点菜单稳

企业微信后台是 hash 路由 SPA。**知道目标路由时，直接 `go_to_url` 带 hash 的完整 URL**
比「点左侧菜单 → 等 → 再点子菜单」少好几次往返，也少几个「点了没反应」的机会。

已知路由（`<...>` 处替换）：

| 页面 | 路由 |
| --- | --- |
| 企业信息 | `#profile` |
| 企业域名 | `#profile/domain` |
| 订单详情 | `#/authCenter/orderDetail?order_id=<订单号>` |
| 合同选择类型 | `#/businessCommon/contract/signIndex/certification/<订单id>` |
| 电子签章申请 | `#/businessCommon/contract/signApply?businessType=certification&orderId=<订单id>` |

> 路由是站点自己的实现，**版本升级可能变**。用之前先 `execute_js` 读一次 `location.href` 确认，
> 不要当永久契约。

### 6.7 人机协同用 `request_human_input`，不要在聊天里贴路径

扫脸、短信码、盖章这类**必须由人做**的事，用正式的人机协同 API 把请求和人绑定：

| 步骤 | 调用 | 做什么 |
| --- | --- | --- |
| 1 | `request_human_input`（`prompt`、可选 `index`/`selector`、`timeoutSeconds`） | 建待办；传 `selector` 会把元素截成 `data.imageBase64` 返回，**并把页签带到窗口最前** |
| 2 | 人看图 → 操作 | 通过 `submit_human_input`（`requestId`+`answer`）提交，**或者**直接在有头浏览器里自己做完 |
| 3 | `get_human_input`（`requestId`、`timeoutSeconds`） | 取答复，`data.status` 为 `pending`/`answered`/`expired` |

**为什么不要自己在聊天里贴截图路径**：`request_human_input` 会**自动把页签带到最前**，
人不用去找窗口；而且请求与答复是**有记录、可追踪**的（`requestId`、`expiresAt`）。
贴路径既没有这个效果，也没法回填。

**但「把图贴进聊天」这件事本身是对的 —— 要贴成图片，不是贴路径。** 实测用户明确要求：
> 「以后再遇到验证码显示在聊天框方便我扫码」

`request_human_input` 回执里有 `data.imageUrl`（可直接 GET 的地址，形如
`/data/<任务id>/shot-N.png`）。**在回复里用 markdown 图片语法把它贴出来**，用户就能在对话里
直接看到二维码并扫码，不用去浏览器窗口找、也不用点开链接：

```markdown
![签约二维码](http://localhost:10049/data/2001/shot-3.png)
```

三条要点：

1. **给绝对地址**（补上 `http://<host>:<port>`）——`data.imageUrl` 只是站内相对路径，
   在聊天里点不开。
2. **同时仍然要 `bring_to_front`**：二维码有短时效（实测 900 秒），让用户能立刻看到有头窗口里的
   那一份最稳妥；聊天里的图是**方便**，不是替代。
3. **不要把 `data.imageBase64` 回填到上下文里**——它很大（一张 180×180 的二维码就有几十 KB base64），
   而且模型多半读不了图。要图就给 URL。

配合 `bring_to_front` 可以把任意页签推到人眼前（只切窗口、**不改当前操作页**）。

## 7. 一次典型任务的骨架

**推荐节奏**：一个批次 = 一个计划段（「动作段 + 末尾回读」）。批次里先做动作，
末尾放一次 `execute_js` 回读，下一次推理基于新结果决定后续。**不要在批次里塞几十步。**

### 阶段 1：确认状态

```json
{"method":"start","params":{"headless":false,"browser":"chrome"}}
```
```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/authCenter/orderDetail?order_id=<订单号>"}},
  {"wait":{"seconds":12}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;var i=t.indexOf('订单发票');var j=t.indexOf('有效期');return {url:location.href,invoice:i>=0?t.slice(i,i+200):null,valid:j>=0?t.slice(j,j+60):null}})()"}}
]}}
```

**根据回读结果分支**：

- 看到「审核完成后可申请」→ **别开发票**，转去推进签章（阶段 4）。
- 看到「申请发票」→ 进阶段 2。
- 看到「审核中」→ 发票已提交，结束这条线。

### 阶段 2：问清纳税人资格（**必须先问，不能跳**）

见 3.4 的标准问法。**在拿到答复之前不要提交发票申请**（3.3：提交后改不了）。

### 阶段 3：提交发票申请

```json
{"method":"commands","params":{"stopOnError":false,"stopOnExpectFailure":true,"commands":[
  {"click_element_by_selector":{"selector":"a:has-text('申请发票')","mode":"auto"}},
  {"wait":{"seconds":8}},
  {"expect":{"js":"!!document.querySelector('.mall_invoice_dialog_container')","truthy":true}},
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');var r=d.querySelectorAll('input[type=radio]');r[0].click();return {checked:r[0].checked}})()"}},
  {"wait":{"seconds":4}},
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');return {txt:d.innerText.slice(0,900)}})()"}}
]}}
```

**回读确认抬头与税号已自动带出**，再提交：

```json
{"method":"commands","params":{"stopOnError":false,"stopOnExpectFailure":true,"commands":[
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');var b=[].slice.call(d.querySelectorAll('a,button')).filter(function(e){return e.innerText.trim()==='提交申请'})[0];if(!b)return {noBtn:true};b.click();return {ok:true}})()"}},
  {"wait":{"seconds":10}},
  {"expect":{"js":"document.body.innerText.indexOf('申请已提交') >= 0","truthy":true}},
  {"execute_js":{"body":"(()=>{var d=document.querySelector('.mall_invoice_dialog_container');if(d){var b=[].slice.call(d.querySelectorAll('a,button')).filter(function(e){return e.innerText.trim()==='完成'})[0];if(b)b.click()}return {ok:true}})()"}},
  {"wait":{"seconds":6}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;var i=t.indexOf('订单发票');return {seg:i>=0?t.slice(i,i+160):null}})()"}}
]}}
```

### 阶段 4：合同 → 被签章卡住 → 转签章

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{var a=[].slice.call(document.querySelectorAll('a')).filter(function(e){return e.offsetParent!==null&&e.innerText.trim()==='申请合同'})[0];if(!a)return {none:true};a.click();return {ok:true}})()"}},
  {"wait":{"seconds":10}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;return {url:location.href,tail:t.slice(-900)}})()"}}
]}}
```

**从跳转后的 URL 里记下 `<订单id>`**（第 2.4 节），后面都要用。

### 阶段 5：签章推进到第 4 步，然后停

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://work.weixin.qq.com/wework_admin/frame#/businessCommon/contract/signApply?businessType=certification&orderId=<订单id>"}},
  {"wait":{"seconds":12}},
  {"execute_js":{"body":"(()=>{var t=document.body.innerText;return {url:location.href,tail:t.slice(-1100)}})()"}}
]}}
```

**回读确认停在第 4 步后，交接给人（5.5），结束这一轮。**

### 收工

```json
{"method":"close","params":{}}
```

```json
{"method":"cleanup","params":{"scope":"trace","olderThanHours":24}}
```

> `cleanup` **默认只预演**（`dryRun:true`），要真删必须显式传 `"dryRun":false`。
> `scope` 取 `all`/`data`/`trace`/`upload`；`all` 不会动 `upload`（暂存文件可能正在被任务使用）。

## 8. 交付话术（收尾）

按这个顺序汇报，**每条都要能对应到页面上的文案**（不要凭记忆）：

1. **认证状态**：认证是否通过、有效期 `<起>-<止>`（这两个值在订单详情页的「有效期」和
   企业信息页的「当前认证有效期至」都能读到，**两处一致才算稳**）。
2. **发票**：
   - 已提交 → 报**发票金额 / 发票类型 / 发票抬头 / 申请人 / 申请时间**，并说明
     **「预计 1-7 个工作日内通过『企业微信团队』发送」**、当前状态是**审核中**；
   - 未提交 → 说明卡在哪（一般是「审核完成后可申请」，即认证还没审完）。
   - **主动说明「页面没有自助修改入口」**，避免用户事后想改才发现。
3. **合同**：说明走的是电子还是纸质，以及**当前被什么卡住**。如果被签章卡住，明确说
   **「签章第 4 步的授权书盖章完成前，电子合同申请不了」** —— 不要让用户以为合同已经在办了。
4. **签章**：报**当前停在第几步**，以及**下一步需要谁做什么**。第 4 步要写清三个硬性要求
   （**PDF / ≤5MB / 只盖企业公章**）和「请勿修改授权书文字内容」。
5. **明确交接**：用一句话说清**球在谁那边**，例如
   > 「现在需要你做一件事：打印授权书模板 → 盖企业公章 → 扫描成 ≤5MB 的 PDF → 把路径给我，
   > 我来上传提交。这一步要几天都可以，弄好告诉我即可。」

   **不要说「等我继续」** —— 你继续不了，章在人手里。
6. **提醒清理**：`logs/trace/**`、`logs/agent/**` 与服务端上传暂存目录里可能留有**企业名、姓名、
   身份证号、对公账号**等敏感信息，且**都不会自动清理**。提醒用户清理，或用 `cleanup`。

## 9. 脱敏约定

本文所有示例都是占位符：

| 占位符 | 含义 |
| --- | --- |
| `<企业全称>` | 认证主体企业名称 |
| `<18 位统一社会信用代码>` | 纳税人识别号 |
| `<订单号>` | 订单详情页的 `?order_id=` 参数（长数字串） |
| `<订单 id>` | 合同/签章 URL 里的那个 id（**与订单号不同**） |
| `<管理员姓名>` | 申请人 / 实名认证人 |
| `<管理员身份证号>` | 实名认证用 |
| `<对公账号>` / `<开户行>` | 打款验证用 |
| `<打款金额>` | 随机小金额 |
| `<审核机构名称>` | 第三方审核机构 |
| `<客服微信号>` | 审核机构在线客服 |

**严格要求**：

- **不要把真实值写回技能文档** —— 包括但不限于：公司名、统一社会信用代码、身份证号、对公账号、
  订单号/订单 id、客服联系方式、**本机绝对路径**。
- 给用户看的过程记录里，订单号、身份证号、账号**只留后 4 位或直接省略**。
- 授权书 PDF 等**含公章与主体信息的文件不要放进技能仓库**，放临时目录，用完提醒用户清理。
