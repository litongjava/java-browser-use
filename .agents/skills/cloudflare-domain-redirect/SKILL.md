---
name: cloudflare-domain-redirect
description: 使用 deepseek-browser-use 在 Cloudflare 配置域名 HTTP/HTTPS 跳转，检查已有 DNS、精确匹配主机名、部署 Redirect Rule 并验证解析与边缘响应。适用于域名迁移或别名跳转，不用于一般 DNS 管理。
---

# Cloudflare 域名跳转

通过本项目的 [deepseek-browser-use](../deepseek-browser-use/SKILL.md) 和 dsb 客户端操作已登录的 Cloudflare 控制台。本文来自一次真实任务，域名、IP 和账号均替换为示例；不要套用固定账号 URL 或元素索引。

## 配置范围

- 明确来源域名、目标 HTTPS URL，以及是否保留路径、查询参数。只匹配用户需要迁移的主机名；存在 API、后台、视频等子域名时，不要选择 All incoming requests。
- 是否包含 www 要按用户范围与现有站点判断，不自动把全部子域名并入。
- 永久迁移用 301；临时跳转用 302。若业务要求保留 POST 方法，需要讨论 307/308，不能沿用网页导航的默认值。
- DNS 的 CNAME 本身不会让地址栏跳转。Redirect Rule 要求来源记录开启代理；来源域名仍需要有效的边缘证书。

## 浏览器操作

1. 按主技能检查服务、启动任务、打开 Cloudflare；需要人工登录或验证码时保留现场交接。
2. Domains → 来源域名 → DNS Records，记录本次涉及的 A/AAAA/CNAME、内容与代理状态。先检查已有 Rules、Page Rules 或 Workers，避免与现有跳转冲突。DNS 页可能弹出新版介绍，先关闭 Got it 引导。
3. Rules → Overview → Redirect to a different domain 模板。若页面布局变了，读当前页面定位 Redirect Rules。填写可识别的规则名，切换 Edit expression，并将模板中的示例域名全部替换。

   例如，仅迁移根域名与 www：

   ```text
   (http.host in {"old.example" "www.old.example"})
   ```

   动态目标表达式：

   ```text
   concat("https://new.example", http.request.uri.path)
   ```

   勾选 Preserve query string。若所有来源路径都应跳首页，改用固定目标 URL，而非上述表达式。
4. 用 `get_form_state` 复核匹配范围、目标表达式、状态码、查询参数选项。部署后回读规则列表确认名称及 active 状态。用户已授权配置时可直接部署；不因本文增加重复确认。
5. 规则就绪后，将涉及的来源 DNS 记录开启 Proxy status 并 Save，再回读 Proxied。保留已有记录内容和其他子域名配置；无需为了边缘跳转更换原 IP。

## Cloudflare 页面上的观测陷阱

- 地址栏先切换、正文随后替换：URL 已变化并不代表新页面已加载。等待目标表单或规则行出现，不使用上一屏的索引。
- 实测 Deploy 返回 ``SPURIOUS_DISPATCH``，规则却已创建。不能重复点击部署；先读规则列表或相关响应。新回执的 ``actionStatus:unknown`` 也应如此处理。
- ``snapshotConsistent:false`` 或 ``indicesUsable:false`` 表示本次索引不可用。等目标内容后重新取快照。即使 ``snapshotConsistent:true``，也只说明读取期间没有检测到变化，不证明异步业务已完成。
- 新版 DNS 编辑区域使用具名按钮、Proxy status 开关、Save。优先按真实的 aria-label 定位所需记录，避免点错多个 Edit 中的一个。
- 用 `dsb state --text-only` 阅读，或 `dsb run get_form_state --select data.fields` 筛字段，保留客户端脱敏和日志。复杂选择器、表达式和批量参数放 JSON 文件，不为缩短输出绕过 dsb。
- 一条命令仍在运行时，先等它完成，再发依赖它的下一步；不能一边点击部署，一边并发读取同一任务。

## 验证与结论

分别验证来源根域名/授权的 www、HTTP/HTTPS、路径和查询参数。HTTP 检查先不跟随跳转，确认状态码和 Location，再用浏览器检查最终目标可访问。

```shell
curl -I --max-time 20 https://old.example/
curl -I --max-time 20 'http://old.example/course?id=1'
curl -I --max-time 20 'https://www.old.example/course?id=1'
```

HTTPS 检查不要用跳过证书验证的选项。预期是 301/302 和正确 Location；目标站点的响应要另查，避免把目标站点故障归因于跳转。

当控制台已保存但普通访问失败时，分开核验：

1. 控制台回读记录为 Proxied、规则为 active。
2. 普通 DNS 查询与两个独立的加密 DNS 查询（例如 Cloudflare DoH、Google DoH）比较，必要时通过可信链路查询权威 DNS。普通 UDP 查询即使指定公共或权威服务器，也可能被本地网络中间环节干预；不能仅凭命令里的服务器参数确定响应来源。
3. 用查到的 Cloudflare IP 做 `curl --resolve` 检查来源域名的 TLS 与跳转。这只证明边缘配置，不证明普通用户访问路径已恢复。
4. 再查普通访问。报告“控制台已保存”“边缘验证通过”“公网 DNS 已更新”“本机普通访问通过”各自的实测结果；旧 IP 的成因未定位时，不断言只需等传播。

实测曾出现：两家 DoH 都返回 Cloudflare IP，指定该 IP 的 HTTPS 跳转成功，但本机普通 DNS 仍返回旧源站 IP，普通 HTTPS 失败。这应记为本机解析链路仍有差异，不能为了让验证通过而擅自修改系统 DNS 或清空全局缓存。
