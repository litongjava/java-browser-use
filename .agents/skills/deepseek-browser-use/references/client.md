# 客户端 dsb（首选）

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

## 也可以不手拼 JSON：用现成客户端（**首选**）

手工拼 `-d '...'` 在参数带中文、引号、换行时很容易出错（PowerShell 尤其爱吃掉引号），返回体还得自己解析。
仓库里的 `dsb` 客户端把这几件事都替你办了：**子命令式传参**、**批量与异步**、**每一步的请求与响应都留档**。
凡是「发请求 → 读页面 → 再发请求」的任务，用它比手拼 JSON 少一大类无谓的失败。

三个客户端（都在仓库里，跟着仓库一起分发）：

| 客户端 | 位置 | 适合 | 例子 |
| --- | --- | --- | --- |
| `dsb.py`（Python 3，只用标准库，跨平台，也可当库 import） | 仓库根 `client/dsb.py` | 写进脚本、批量、异步、跨平台 | `python client/dsb.py --port 10049 start --browser chrome` |
| `dsb.cmd`（Windows 薄包装，透传参数与退出码） | 仓库根 `client/dsb.cmd` | Windows 上少打一截前缀，直接敲就行 | `client\dsb.cmd --port 10049 health` |
| `browse.ps1` | `scripts/trace/browse.ps1` | 已有的 PowerShell 排查习惯 | `browse.ps1 -PayloadFile req.json -Session t1` |

**Windows 上直接用它，不必写 `python dsb.py`**：`dsb.cmd` 只做两件事 —— 找 `python`（取不到就退回 `py`），
再把 `%~dp0dsb.py` 连同全部参数交出去，退出码原样 `exit /b` 透传。两点注意：

- 在 **PowerShell** 里当前目录不在 `PATH`，要写成 `.\client\dsb.cmd ...`；在 **cmd.exe** 里 `client\dsb.cmd ...` 就行。
- `dsb.cmd` 中间隔着一层 cmd.exe，参数里的 `&`、`^`、`%` 可能被提前吃掉（中文与引号不受影响，已验证）。
  遇到这种参数不要换回别的发送方式，而是**把参数从命令行挪进文件**：`--params @文件.json`、`batch cmds.json`、
  `js @脚本.js` —— 长脚本、带中文的 JSON、带引号的选择器都走这条路，连转义都不用想。

`dsb` 的要点（完整用法与退出码见 `client/README.md`）：

```shell
# 通用选项放子命令前后都行；退出码 0 成功 / 1 传输错 / 2 业务失败 / 3 用法错
python client/dsb.py --port 10049 health
python client/dsb.py --port 10049 --id 1001 start --browser chrome --headful
python client/dsb.py --port 10049 --id 1001 run go_to_url -p url=https://example.com
python client/dsb.py --port 10049 --id 1001 state --full          # 标题/URL/元素/结构化文本
python client/dsb.py --port 10049 --id 1001 js @脚本.js --var who=dsb   # 支持 {{变量}} 注入
python client/dsb.py --port 10049 --id 1001 batch cmds.json --async --wait   # 长批次不受 HTTP 超时限制
python client/dsb.py --port 10049 --id 1001 recipes --run close-all-modals
python client/dsb.py --port 10049 upload 图样.jpg                 # 送文件到服务端暂存区
python client/dsb.py --port 10049 last                            # 重放最近一次响应
```

用它还有两个直接好处：**`steps.log` 一行一次调用**（时间、序号、任务 ID、方法、成败、耗时、摘要），第几步开始
不对一眼就能看出来；**退出码把「服务没起」与「业务失败」分开**（`1` 与 `2`），写脚本时不用去解析 `msg` 猜。
Windows 下把上面例子里的 `python client/dsb.py` 换成 `.\client\dsb.cmd` 即可，其余参数完全一致。

三个容易用错的地方：

- **`--summary`（`--compact` 是同一个开关）只管本地输出**，与服务端协议里的 `responseMode:"compact"`
  （响应精简模式）不是一回事；后者要用 `--response-mode compact` 传（**信封级字段**，不是 `params` 里的）。
  摘要为空时（`get_tabs`/`get_console_logs`/`get_dialog` 这类没有可摘要字段的方法）dsb 会自动退回打印一行
  JSON —— 静默只回一句 `get_tabs OK 21ms` 等于把答案吞了。
- **默认脱敏不会掩掉「下一步还要回填的凭据」**：`requestId`、`jobId` 与 `hr-<n>-<雪花号>` 原样保留，
  其余（手机号、证件号、邮箱、长号码）照旧打码。理由很实际：把要回填的 ID 掩成 `***` 之后，
  `submit_human_input` / `get_response_body(requestId=…)` 就没法用了，比泄露它更糟。
- **多行脚本不要写在命令行里**：经 cmd/PowerShell 传参会只剩第一行。用 `js @脚本.js`、`--params @文件.json`
  或 `batch cmds.json`。

不确定服务端现在是什么状态（引擎、profile 目录、命令数、配方数）时，先跑一次自检：

```shell
python client/dsb.py --port 10049 selftest --browser chrome
```
