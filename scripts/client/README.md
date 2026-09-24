# dsb —— deepseek-browser-use 的 Python 客户端

一个文件、只用标准库的客户端(不需要 `pip install`),既能当命令行用,也能当库用。
PowerShell 那份是 `scripts/trace/browse.ps1`,这个 `dsb.py` 是给「跨平台、要写进脚本、要跟 Python 生态配合」的场景准备的。

```
scripts/client/
├── dsb.py          客户端本体(单文件,标准库)
├── dsb.cmd         Windows 包装:直接敲 dsb 就行
├── dsb             Linux/macOS 包装(chmod +x 后可直接 ./dsb)
├── test_dsb.py     本地自测(不连服务):脱敏规则、参数解析、编号接续
└── README.md       本文件
```

## 30 秒上手

```bash
# 服务端在哪:命令行 > 环境变量 > 默认(localhost:10049)
python scripts/client/dsb.py --port 10049 health

# 起一个 Firefox 任务(默认无头),看回执里的 engineHonored 确认引擎真的换了
python scripts/client/dsb.py start --browser firefox --id 1001

# 发一条命令
python scripts/client/dsb.py --id 1001 run go_to_url -p url=https://example.com

# 看页面状态(标题/URL/元素数)
python scripts/client/dsb.py --id 1001 state

# 长批次:后台跑 + 轮询,不受 HTTP 超时限制
python scripts/client/dsb.py --id 1001 batch cmds.json --async --wait

# 关掉任务
python scripts/client/dsb.py --id 1001 close
```

Windows 下把 `python scripts/client/dsb.py` 换成 `scripts\client\dsb.cmd`(或先把 `scripts\client` 加进 `PATH`,直接敲 `dsb`)。

## 子命令

| 子命令 | 作用 |
| --- | --- |
| `health` / `config` / `tasks` / `methods [过滤词]` | 运维自省:健康检查、生效配置、活着的任务、命令清单 |
| `start [--browser firefox] [--headful]` | 起任务;`--browser` 支持 auto/chromium/chrome/edge/firefox |
| `close` / `shutdown` | 关掉本任务 / 关掉所有任务与共享浏览器 |
| `run <method> [-p k=v] [--params @文件]` | 发一条命令 |
| `batch [文件\|-] [--async] [--wait] [--keep-going] [--stop-on-expect-failure]` | 批量命令(数组 JSON,默认读标准输入) |
| `job <jobId> [--wait]` | 查/等异步批次 |
| `recipes [--run 名字] [--var k=v]` | 列配方 / 跑配方(显式点名才跑) |
| `state [--max-elements N] [--full]` | 页面状态摘要,`--full` 连元素清单与结构化文本 |
| `js <脚本\|@脚本.js\|-> [--var k=v]` | 执行 JS,支持 `{{变量}}` 注入 |
| `upload <文件> [--filename 名字]` / `uploads [--delete 名字]` | 文件送到服务端暂存区 / 列、删暂存文件 |
| `last` | 重放本会话最近一次的响应(等价 PowerShell 客户端的 `-Last`) |
| `selftest [--browser firefox]` | 对当前服务跑一遍端到端自检(30 项检查) |

通用选项(放在子命令**前面或后面都行**):`--base-url` / `--host` / `--port` / `--id` / `--timeout` /
`--session` / `--no-record` / `--no-redact` / `--redact-pattern` / `--json` / `--compact` / `--index`。

环境变量:`DSB_BASE_URL`、`DSB_HOST`、`DSB_PORT`、`DSB_TASK_ID`、`DSB_SESSION`、`DSB_RECORD_DIR`、`DSB_REDACT`。

## 退出码(写脚本时最该记住的一条)

| 码 | 含义 | 典型原因 |
| --- | --- | --- |
| 0 | 成功 | `ok:true` |
| 1 | 传输/协议错 | 服务没起、端口不对、超时、响应不是合法 JSON |
| 2 | 业务失败 | 服务端回了 `ok:false`(任务不存在、找不到元素、断言没过……) |
| 3 | 用法错 | 参数写错、`-p` 少了 `=`、id 不是数字、要上传的文件不存在 |

`1` 和 `2` 必须分开:前者要去看服务,后者要看自己的命令和页面。批量里**每一步**的失败原因在
`data.results[i].error`(`{code,message,retryable,retryAfterMs}`),按 `retryable` 决定要不要退避重试。

## 每次都留档

默认(会话名 `dsb`)每次调用都会往 `logs/agent/<会话>/` 落盘:

```
logs/agent/dsb/001.req.json    发出去的完整请求
logs/agent/dsb/001.res.json    收到的完整响应
logs/agent/dsb/steps.log       一行一次调用:时间 #序号 id 方法 OK/FAIL 耗时 摘要
```

`steps.log` 长得像这样,排查「第几步开始不对」先看它:

```
2026-09-24 11:24:03 #001 id=1001 execute_js OK 54ms seq=8 shot=/data/1001/8.png
2026-09-24 11:24:03 #002 id=1001 commands FAIL 71ms count=2 succeeded=2 failed=0 expectFailed=1  | 0:get_title=ok 1:execute_js=expect-fail msg=第 1 条命令 execute_js 的断言没通过：实际值 2 不满足 equals 3
```

编号是接着上一轮往下排的,不会覆盖旧记录;`dsb last` 直接重放最近一份响应。

**默认脱敏**:落盘与终端输出都会把手机号、身份证、统一社会信用代码、邮箱、长号码打码
(`***手机号***` 之类),`--no-redact` 关掉,`--redact-pattern 某某公司` / `DSB_REDACT=a,b` 追加要打码的词。
脱敏规则本身有本地自测:`python scripts/client/test_dsb.py`。

## 当库用

```python
import sys
sys.path.insert(0, "scripts/client")
from dsb import Client, TransportError

c = Client(host="10.0.0.5", port=10049, task_id=2001, session="my-task")  # session=None 则不记录
c.start(browser="firefox", headless=True)

batch = c.batch([
    {"go_to_url": {"url": "https://example.com"}},
    {"get_title": {}, "expect": {"js": "() => document.title", "contains": "Example"}},
])
if not batch.ok:
    for step in batch.data["results"]:
        if not step["ok"]:
            print(step["command"], step.get("error"))

job = c.batch([{"execute_js": {"body": "() => 1"}}], asynchronous=True)
print(c.wait_job(job.data["jobId"])["data"]["status"])   # done / failed / cancelled

c.close()
```

约定:`TransportError` 表示「根本没拿到合法响应」;业务失败**不抛异常**,看 `response.ok` /
`response.msg` / `response.data`。`Client.upload(路径)` 走 `POST /playwright/upload`(裸字节 + `?filename=`),
回执里的 `path` 可以直接喂给 `upload_file`。

## 自检

```bash
python scripts/client/dsb.py --port 10049 selftest --browser firefox
```

它用自己的任务 id(`990001`,不会撞上业务任务)起一个浏览器,依次验证:健康检查、命令清单里新命令是否都在、
`start` 的 `engineHonored`、打开本地自检页、`wait_for_count`、`get_modals`/`close_modal`、点击降级到真实鼠标、
`expect` 断言能报出不一致、异步批次与 `get_job`、任务清单/配置/配方可读、`cleanup` 默认只预演。
自检页面是本地生成的 HTML,**不依赖外网**。
