#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dsb —— deepseek-browser-use 的命令行客户端

只依赖 Python 标准库(不需要 pip install),同时能当库用:

    from dsb import Client
    c = Client(port=10049)
    c.start(browser="firefox", headless=True)
    c.command("go_to_url", {"url": "https://example.com"})

设计取向(为什么长成这样):

1. **请求与响应都留档**。每次调用都会往 `logs/agent/<会话>/` 落一份
   `NNN.req.json` / `NNN.res.json` 和一行 `steps.log`,排查「第几步开始不对」时先看它。
   落盘前默认脱敏(手机号、证件号、邮箱、长数字)。
2. **不拼字符串**。参数用 `-p key=value`(值会按 JSON 解析)或 `--params @file.json`,
   避开 PowerShell/cmd 吃引号的坑;长脚本用 `js @脚本.js`。
3. **长批次用异步**。`batch --async --wait` 先拿 `jobId` 再轮询,客户端超时不再等于任务失败。
4. **退出码说人话**:0 成功 / 1 传输或协议错 / 2 业务失败(服务端回了 ok:false) / 3 用法错。

用法见 `dsb --help`,完整命令清单见 `dsb methods`。
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from datetime import datetime
from pathlib import Path

__version__ = "1.0.0"

# ---------------------------------------------------------------- 退出码

EXIT_OK = 0
EXIT_TRANSPORT = 1
EXIT_BUSINESS = 2
EXIT_USAGE = 3

# ---------------------------------------------------------------- 默认值

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 10049
DEFAULT_TASK_ID = 1001
DEFAULT_TIMEOUT = 300.0
DEFAULT_SESSION = "dsb"

#: 命令接口与运维接口的路径(服务端注册见 PlaywrightAppConfig)
PATH_COMMAND = "/playwright/command"
PATH_UPLOAD = "/playwright/upload"
PATH_HEALTH = "/playwright/health"
PATH_TASKS = "/playwright/tasks"
PATH_METHODS = "/playwright/methods"
PATH_CONFIG = "/playwright/config"

# ---------------------------------------------------------------- 脱敏

#: 默认的脱敏规则。顺序有意义:先处理结构化的长号码,再处理手机号,最后兜底长数字串。
#: 目标是「日志能定位问题,但抄出去不会泄露个人信息」,不追求数学意义上的不可逆。
#:
#: 注意两件事:
#:
#: 1. **边界不能用 `\b`** —— Python 的 `\w` 在 Unicode 模式下**包含中文**,而真实文本几乎总是
#:    「统一社会信用代码91310118MAK7DA2R14」这种紧贴中文的形态,`\b` 在汉字与数字之间根本不成立,
#:    规则会静默失效(踩过一次)。所以统一用 `(?<!…)` / `(?!…)` 自己界定字符集。
#: 2. **顺序有意义**:身份证比统一社会信用代码更具体,必须排在前面 —— 两者都是 18 位,
#:    `11010119900307123X` 也能被信用代码那条匹配上,反了就会把身份证标成信用代码。
#: 3. **身份证要带出生日期校验**:`\d{17}[\dXx]` 会把「18 位订单号」也当成证件号(标签错,虽然也打码了),
#:    加上 `(19|20)yy(01-12)(01-31)` 之后,订单号会落到「长号码」那条,标签才是对的。
REDACT_RULES = (
    ("idcard", re.compile(r"(?<![\dXx])\d{6}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?![\dXx])"),
     "***证件号***"),
    # 信用代码里必定含字母(登记管理部门码/机构类别码),所以要求「这 18 位里至少有一个字母」,
    # 否则 18 位纯数字的订单号会被误标成信用代码(打码没问题,但标签会误导排查)
    ("uscc", re.compile(r"(?<![0-9A-Za-z])(?=[0-9A-HJ-NPQRTUWXY]*[A-HJ-NPQRTUWXY])"
                        r"[0-9A-HJ-NPQRTUWXY]{18}(?![0-9A-Za-z])"), "***统一社会信用代码***"),
    ("phone", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), "***手机号***"),
    # 邮箱只认 ASCII:用 `\w` 会把紧贴的中文标签(「邮箱」「联系邮箱」)一起吃掉,日志可读性变差
    ("email", re.compile(r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+"), "***邮箱***"),
    ("bankcard", re.compile(r"(?<!\d)\d{16,19}(?!\d)"), "***长号码***"),
    ("longdigit", re.compile(r"(?<!\d)\d{15,}(?!\d)"), "***长数字***"),
)


#: 「下一步还要回填给接口的凭据」所在的键:这些值不脱敏
#:
#: 理由是实测踩过:``request_human_input`` 回的 ``requestId`` 是 ``hr-1-1790232350369``,被「长号码」
#: 规则把数字段打成 ``***`` 之后,``submit_human_input`` / ``get_response_body(requestId=…)`` 就没法用了 ——
#: **让人看不见自己下一步要用的凭据,比泄露它的代价更大**。同理 ``jobId``(异步批次取结果要用)。
ID_KEYS = frozenset({"requestId", "jobId"})

#: 文本形态下的同类保护:JSON 里的 requestId/jobId 字段值、以及 hr-<n>-<雪花号> 形式的人工请求号
PROTECTED_ID = re.compile(r'"(?:requestId|jobId)"\s*:\s*"[^"]*"' r'|hr-\d+-\d+')


def redact(text: str, extra: tuple[str, ...] = ()) -> str:
    """把文本里的敏感信息打码(用于落盘与终端输出)

    会把 :data:`ID_KEYS` 对应的值与 ``hr-<n>-<雪花号>`` 先摘出来,脱敏完再放回去。
    """
    if not text:
        return text
    guarded: list[str] = []

    def stash(match: re.Match) -> str:
        guarded.append(match.group(0))
        return f"\x00dsbid{len(guarded) - 1}\x00"

    text = PROTECTED_ID.sub(stash, text)
    for _, pattern, replacement in REDACT_RULES:
        text = pattern.sub(replacement, text)
    for word in extra:
        if word:
            text = text.replace(word, "***")
    for index, value in enumerate(guarded):
        text = text.replace(f"\x00dsbid{index}\x00", value)
    return text


def redact_obj(value, extra: tuple[str, ...] = ()):
    """递归脱敏:JSON 结构里只处理字符串叶子(``requestId``/``jobId`` 这类凭据原样保留)"""
    if isinstance(value, str):
        return redact(value, extra)
    if isinstance(value, dict):
        return {key: (item if key in ID_KEYS and isinstance(item, str) else redact_obj(item, extra))
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact_obj(item, extra) for item in value]
    return value


# ---------------------------------------------------------------- HTTP 层


class TransportError(RuntimeError):
    """连不上、超时、响应不是合法 JSON —— 都属于这一类"""


class UsageError(Exception):
    """用法错:参数写错了、本地文件不存在、没有可重放的记录

    这类问题与服务端无关,所以退出码单独区分(3),免得脚本里把「我把命令敲错了」当成「服务端挂了」。
    """


class Response:
    """一次调用的结果:服务端信封 + 本地观测信息"""

    def __init__(self, url: str, status: int, envelope: dict, raw: str, elapsed_ms: int):
        self.url = url
        self.status = status
        self.envelope = envelope
        self.raw = raw
        self.elapsed_ms = elapsed_ms

    @property
    def ok(self) -> bool:
        return bool(self.envelope.get("ok"))

    @property
    def code(self) -> int:
        return int(self.envelope.get("code") or 0)

    @property
    def msg(self) -> str:
        return self.envelope.get("msg") or ""

    @property
    def data(self):
        return self.envelope.get("data")

    def __repr__(self) -> str:  # pragma: no cover - 只为调试方便
        return f"<Response ok={self.ok} code={self.code} msg={self.msg!r} elapsed={self.elapsed_ms}ms>"


def _decode_body(raw: bytes, encoding: str) -> str:
    """服务端可能 gzip 压缩(见服务端响应处理),这里三种情况都兜住"""
    encoding = (encoding or "").lower()
    if "gzip" in encoding:
        raw = gzip.decompress(raw)
    elif "deflate" in encoding:
        try:
            raw = zlib.decompress(raw)
        except zlib.error:
            raw = zlib.decompress(raw, -zlib.MAX_WBITS)
    return raw.decode("utf-8-sig", errors="replace")


class Client:
    """deepseek-browser-use 的 HTTP 客户端(可当库用)

    参数与命令行选项一一对应,方便脚本里直接构造:

        c = Client(host="10.0.0.5", port=10049, task_id=2001, session="cnipa")
        c.command("click_element_by_selector", {"selector": "#ok", "mode": "mouse"})
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT, base_url: str | None = None,
                 task_id: int | str = DEFAULT_TASK_ID, timeout: float = DEFAULT_TIMEOUT,
                 session: str | None = DEFAULT_SESSION, record: bool = True,
                 redact_enabled: bool = True, redact_patterns: tuple[str, ...] = (),
                 verbose: bool = False, record_dir: str | None = None,
                 response_mode: str | None = None, diagnostics: bool = False):
        self.base_url = (base_url or f"http://{host}:{port}").rstrip("/")
        self.task_id = _as_task_id(task_id)
        self.timeout = float(timeout)
        self.session = session
        self.record = record and bool(session)
        self.redact_enabled = redact_enabled
        self.redact_patterns = tuple(redact_patterns or ())
        self.verbose = verbose
        self.response_mode = response_mode
        self.diagnostics = bool(diagnostics)
        self.record_dir = Path(record_dir) if record_dir else _default_record_dir(session)
        self.counter = _next_index(self.record_dir)
        self.last_response: Response | None = None

    # ------------------------------------------------------------ 底层请求

    def _url(self, path: str) -> str:
        return self.base_url + path

    def request(self, path: str, *, method: str = "GET", body: bytes | None = None,
                content_type: str | None = None, query: dict | None = None,
                timeout: float | None = None) -> Response:
        url = self._url(path)
        if query:
            url = url + "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        headers = {"Accept": "application/json", "User-Agent": f"dsb/{__version__}"}
        if content_type:
            headers["Content-Type"] = content_type
        if body is not None and content_type is None:
            headers["Content-Type"] = "application/octet-stream"
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        started = time.time()
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                raw = response.read()
                status = response.status
                encoding = response.headers.get("Content-Encoding", "")
        except urllib.error.HTTPError as error:  # 服务端自己回了 4xx/5xx
            raw = error.read()
            status = error.code
            encoding = error.headers.get("Content-Encoding", "") if error.headers else ""
        except urllib.error.URLError as error:
            raise TransportError(f"连不上 {url}:{error.reason}(服务起了吗?地址对吗?)") from None
        except TimeoutError:
            raise TransportError(f"请求超时 {url}(超过 {timeout or self.timeout:.0f} 秒)") from None
        elapsed_ms = int((time.time() - started) * 1000)

        text = _decode_body(raw, encoding)
        try:
            envelope = json.loads(text)
        except ValueError:
            raise TransportError(
                f"响应不是合法 JSON({url},HTTP {status},耗时 {elapsed_ms}ms):{text[:300]}") from None
        if not isinstance(envelope, dict):
            raise TransportError(f"响应不是 JSON 对象({url}):{text[:200]}")
        return Response(url, status, envelope, text, elapsed_ms)

    # ------------------------------------------------------------ 记录

    def _record(self, label: str, payload, response: Response | None, error: str | None = None) -> None:
        """把这一次调用落盘:NNN.req.json / NNN.res.json + 一行 steps.log"""
        if not self.record:
            return
        index = self.counter
        self.counter += 1
        try:
            self.record_dir.mkdir(parents=True, exist_ok=True)
            request_text = json.dumps(self._mask(payload), ensure_ascii=False, indent=2)
            (self.record_dir / f"{index:03d}.req.json").write_text(request_text, encoding="utf-8")
            if response is not None:
                response_text = json.dumps(self._mask(response.envelope), ensure_ascii=False, indent=2)
            else:
                response_text = json.dumps({"sendFailed": True, "error": error}, ensure_ascii=False, indent=2)
            (self.record_dir / f"{index:03d}.res.json").write_text(response_text, encoding="utf-8")

            stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            if response is None:
                line = f"{stamp} #{index:03d} id={self.task_id} {label} SEND-FAILED {error}"
            else:
                verdict = "OK" if response.ok else "FAIL"
                detail = _summarize(response.data)
                line = (f"{stamp} #{index:03d} id={self.task_id} {label} {verdict} "
                        f"{response.elapsed_ms}ms{detail}")
                if not response.ok:
                    line += f" msg={response.msg}"
            with (self.record_dir / "steps.log").open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")
        except OSError as error_io:  # 落盘失败不该让调用失败
            if self.verbose:
                print(f"[warn] 记录失败:{error_io}", file=sys.stderr)

    def _mask(self, value):
        return redact_obj(value, self.redact_patterns) if self.redact_enabled else value

    # ------------------------------------------------------------ 命令接口

    def command(self, method: str, params: dict | None = None, *, task_id: int | str | None = None,
                timeout: float | None = None, label: str | None = None) -> Response:
        """发一条命令。失败(业务失败)不抛异常,由调用方看 `ok`;传输失败抛 TransportError。"""
        payload = {"id": _as_task_id(task_id if task_id is not None else self.task_id),
                   "method": method, "params": params or {}}
        # responseMode / diagnostics 是**信封级**字段(与 method/params 平级),不是 params 里的东西
        if self.response_mode:
            payload["responseMode"] = self.response_mode
        if self.diagnostics:
            payload["diagnostics"] = True
        try:
            response = self.request(PATH_COMMAND, method="POST", body=_json_bytes(payload),
                                    content_type="application/json", timeout=timeout)
        except TransportError as error:
            self._record(label or method, payload, None, str(error))
            raise
        self.last_response = response
        self._record(label or method, payload, response)
        return response

    def batch(self, commands: list, *, stop_on_error: bool = False, stop_on_expect_failure: bool = False,
              asynchronous: bool = False, max_duration_ms: int | None = None,
              timeout: float | None = None, task_id: int | str | None = None) -> Response:
        """跑一批命令。`asynchronous=True` 时立刻返回 jobId(不占用 HTTP 超时)。"""
        params: dict = {"commands": commands, "stopOnError": stop_on_error}
        if stop_on_expect_failure:
            params["stopOnExpectFailure"] = True
        if asynchronous:
            params["async"] = True
        if max_duration_ms:
            params["maxDurationMs"] = max_duration_ms
        return self.command("commands", params, task_id=task_id, timeout=timeout, label="commands")

    # ------------------------------------------------------------ 便捷方法

    def health(self) -> Response:
        return self.request(PATH_HEALTH)

    def methods(self) -> Response:
        return self.request(PATH_METHODS)

    def config(self) -> Response:
        return self.request(PATH_CONFIG)

    def tasks(self) -> Response:
        return self.request(PATH_TASKS)

    def start(self, *, browser: str | None = None, headless: bool = True,
              task_id: int | str | None = None) -> Response:
        params: dict = {"headless": headless}
        if browser:
            params["browser"] = browser
        return self.command("start", params, task_id=task_id)

    def close(self, task_id: int | str | None = None) -> Response:
        return self.command("close", {}, task_id=task_id)

    def shutdown(self) -> Response:
        return self.command("shutdown", {})

    def upload(self, path: str | Path, *, filename: str | None = None) -> Response:
        """把本地文件送到服务端暂存区(裸字节 + ?filename=,服务端三种写法都认)"""
        file = Path(path)
        if not file.is_file():
            raise UsageError(f"找不到要上传的文件:{file}")
        data = file.read_bytes()
        name = filename or file.name
        payload = {"file": str(file), "filename": name, "size": len(data),
                   "sha256": hashlib.sha256(data).hexdigest()}
        try:
            response = self.request(PATH_UPLOAD, method="POST", body=data,
                                    content_type="application/octet-stream", query={"filename": name})
        except TransportError as error:
            self._record("upload", payload, None, str(error))
            raise
        self.last_response = response
        self._record("upload", payload, response)
        return response

    def uploads(self) -> Response:
        return self.request(PATH_UPLOAD)

    def delete_upload(self, name: str) -> Response:
        return self.request(PATH_UPLOAD, method="DELETE", query={"name": name})

    def job(self, job_id: str, *, include_result: bool = True) -> Response:
        return self.command("get_job", {"jobId": job_id, "includeResult": include_result})

    def wait_job(self, job_id: str, *, poll: float = 1.0, timeout: float = 600.0,
                 on_tick=None) -> Response:
        """轮询异步任务直到结束(running 之外的状态都算结束)"""
        deadline = time.time() + timeout
        last = None
        while True:
            last = self.job(job_id)
            status = (last.data or {}).get("status") if isinstance(last.data, dict) else None
            if on_tick:
                on_tick(last)
            if status and status != "running":
                return last
            if time.time() >= deadline:
                raise TransportError(f"等任务 {job_id} 超时(超过 {timeout:.0f} 秒,最后状态 {status})")
            time.sleep(poll)


# ---------------------------------------------------------------- 工具


def _json_bytes(payload) -> bytes:
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def _as_task_id(value) -> int | str:
    """服务端要求 id 是数字或数字字符串,这里提前拦住非数字(否则只能等服务端报错)"""
    if isinstance(value, bool):
        raise UsageError("任务 id 必须是数字,不能是布尔值")
    if isinstance(value, int):
        return value
    text = str(value).strip()
    if text.isdigit():
        return int(text)
    raise UsageError(f"任务 id 必须是数字(例如 1001 或雪花 ID),收到的是:{value!r}")


def _default_record_dir(session: str | None) -> Path:
    """记录目录:与 PowerShell 客户端保持一致,落在仓库的 logs/agent/<会话>/ 下"""
    if not session:
        return Path("logs") / "agent" / "dsb"
    base = os.environ.get("DSB_RECORD_DIR")
    if base:
        return Path(base) / session
    return Path("logs") / "agent" / session


def _next_index(directory: Path) -> int:
    """接着已有的编号往下记,不覆盖上一轮的记录"""
    if not directory.is_dir():
        return 1
    highest = 0
    for item in directory.glob("*.res.json"):
        match = re.match(r"(\d+)\.res\.json$", item.name)
        if match:
            highest = max(highest, int(match.group(1)))
    for item in directory.glob("*.req.json"):
        match = re.match(r"(\d+)\.req\.json$", item.name)
        if match:
            highest = max(highest, int(match.group(1)))
    return highest + 1


def _summarize(data) -> str:
    """把回执里的关键字段压成一行,便于 steps.log 里一眼看出发生了什么

    只挑「一眼能判断这一步成不成」的标量字段;挑不出任何字段时返回空串,由调用方决定退化成什么
    (``--summary`` 模式会退回一行 JSON,不会静默吞掉内容)。
    """
    if not isinstance(data, dict):
        return ""
    parts = []
    for key in ("count", "countBlocking", "countStrict", "succeeded", "failed", "expectFailed", "seq", "status",
                "mode", "matched", "stable", "closed", "elementCount", "jobId", "step", "title", "url",
                "value", "visible", "enabled", "checked", "result", "actionStatus", "retrySafe",
                "observationComplete", "snapshotConsistent", "indicesUsable"):
        if key not in data or data[key] in (None, "", [], {}):
            continue
        value = data[key]
        if not isinstance(value, (str, int, float, bool)):
            continue
        if isinstance(value, str) and len(value) > 40:
            value = value[:40] + "…"
        parts.append(f"{key}={value}")
    if "screenshot" in data:
        parts.append(f"shot={data['screenshot']}")
    if "results" in data and isinstance(data["results"], list):
        marks = []
        for step in data["results"][:12]:
            if not isinstance(step, dict):
                continue
            mark = "ok" if step.get("ok") else "fail"
            if isinstance(step.get("expectResult"), dict) and not step["expectResult"].get("passed"):
                mark = "expect-fail"
            marks.append(f"{step.get('index')}:{step.get('command')}={mark}")
        parts.append(" | " + " ".join(marks))
    return (" " + " ".join(parts)) if parts else ""


def parse_kv(items: list[str] | None) -> dict:
    """把 `-p key=value` 解析成 dict。值优先按 JSON 解析(数字/布尔/数组/对象/字符串)。"""
    result: dict = {}
    for item in items or []:
        if "=" not in item:
            raise UsageError(f"参数要写成 key=value 的形式,收到的是:{item!r}")
        key, _, value = item.partition("=")
        key = key.strip()
        if not key:
            raise UsageError(f"参数名不能为空:{item!r}")
        result[key] = parse_value(value)
    return result


def parse_value(text: str):
    """值按 JSON 解析;解析不了就当字符串(这样 `-p text=Mac Mini` 也能用)"""
    text = text.strip()
    if text == "":
        return ""
    try:
        return json.loads(text)
    except ValueError:
        return text


def load_payload(source: str | None, kv: list[str] | None) -> dict:
    """参数来源:`--params <JSON|@文件>` 与 `-p k=v` 合并,后者优先"""
    params: dict = {}
    if source:
        if source.startswith("@"):
            text = read_local_file(source[1:])
        elif source == "-":
            text = sys.stdin.read()
        else:
            text = source
        try:
            loaded = json.loads(text)
        except ValueError as error:
            raise UsageError(f"参数不是合法 JSON:{error}") from None
        if not isinstance(loaded, dict):
            raise UsageError("参数必须是 JSON 对象,例如 {\"selector\":\"#ok\"}")
        params.update(loaded)
    params.update(parse_kv(kv))
    return params


def read_local_file(path: str) -> str:
    """读本地文件;读不到算用法错(退出码 3),不要抛出难看的 traceback

    编码用 `utf-8-sig`:**Windows PowerShell 的 `Out-File -Encoding utf8` 默认写 BOM**,
    而带 BOM 的文本喂给 `json.loads` 会直接报 "Unexpected UTF-8 BOM" —— 用户自己存的
    参数文件十有八九带 BOM,所以这里必须容忍(没有 BOM 时 `utf-8-sig` 与 `utf-8` 等价)。
    """
    try:
        return Path(path).read_text(encoding="utf-8-sig")
    except OSError as error:
        raise UsageError(f"读不了文件 {path}:{error}") from None


def read_text_source(source: str) -> str:
    """读一段文本:`@文件` 或直接给内容,`-` 表示标准输入"""
    if source == "-":
        return sys.stdin.read()
    if source.startswith("@"):
        return read_local_file(source[1:])
    return source


def read_batch_source(source: str | None) -> str:
    """批量命令的来源:省略或 `-` 读标准输入,其余按**文件路径**读(`@` 前缀可写可不写)"""
    if not source or source == "-":
        return sys.stdin.read()
    return read_local_file(source[1:] if source.startswith("@") else source)


def setup_stdout() -> None:
    """管道输出用 UTF-8(便于 `| jq`);终端输出按控制台编码并容错,避免中文直接崩掉"""
    try:
        if sys.stdout.isatty():
            sys.stdout.reconfigure(errors="replace")
        else:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass


# ---------------------------------------------------------------- 输出


class Printer:
    """输出策略:默认「摘要 + JSON」,`--json` 只出 JSON,`--summary`(`--compact`)只出摘要

    `--summary` 是**本地输出**开关,与服务端协议里的 `responseMode:"compact"`(响应精简模式)不是
    一回事 —— 后者要用 `--response-mode compact` 传。同名但不同义,以前实测照文档用错过一次,
    所以这里连名字一起改清楚。
    """

    def __init__(self, mode: str = "full", mask: bool = True, patterns: tuple[str, ...] = (),
                 out=sys.stdout, select: str | None = None):
        self.mode = mode
        self.mask = mask
        self.patterns = patterns
        self.out = out
        self.select = select

    def _clean(self, text: str) -> str:
        return redact(text, self.patterns) if self.mask else text

    def response(self, response: Response, *, label: str = "", index: int | None = None,
                 payload=None) -> None:
        """默认打印「一行摘要 + 完整信封」;`payload` 给定时改印它(比如只要批量里的某一步)"""
        if self.select is not None:
            self.json(response.envelope if payload is None else payload)
            return
        if self.mode != "json":
            prefix = f"#{index:03d} " if index else ""
            verdict = "OK" if response.ok else "FAIL"
            head = f"{prefix}{label} {verdict} {response.elapsed_ms}ms"
            detail = _summarize(response.data)
            if not response.ok and response.msg:
                detail += f" msg={response.msg}"
            print(self._clean(head + detail), file=self.out)
            # 摘要为空时**不能**只回一行「OK 27ms」:那等于把答案吞了(实测 get_tabs / get_console_logs
            # / get_dialog 都是这样,让人以为「没有数据」)。这时退回一行 JSON,信息不丢、也还是一行。
            if self.mode == "compact" and not detail:
                body = response.data if payload is None else payload
                print(self._clean(json.dumps(body, ensure_ascii=False, separators=(",", ":"))), file=self.out)
        if self.mode != "compact":
            self.json(response.envelope if payload is None else payload)

    def json(self, value) -> None:
        """`--compact` 时压成一行(这类子命令没有「摘要」可言,一行 JSON 就是它的一行)"""
        if self.select is not None:
            # Failed responses retain the full error envelope and business exit code.
            if isinstance(value, dict) and value.get("ok") is False:
                print(self._clean(json.dumps(value, ensure_ascii=False, indent=2)), file=self.out)
                return
            data = value.get("data", {}) if isinstance(value, dict) else {}
            if isinstance(data, dict):
                if data.get("snapshotConsistent") is False:
                    self.warn("快照不可靠:" + str(data.get("snapshotIssues")))
                if data.get("actionStatus") == "unknown" or data.get("observationComplete") is False:
                    self.warn("动作结果或观测不完整，请先读取业务结果，勿自动重试")
            # Redact before dropping object keys, so requestId/jobId keep their
            # existing exemption even when the projection returns only a string.
            value = select_field(redact_obj(value, self.patterns) if self.mask else value, self.select)
            print(json.dumps(value, ensure_ascii=False, indent=2), file=self.out)
            return
        if self.mode == "compact":
            print(self._clean(json.dumps(value, ensure_ascii=False, separators=(",", ":"))), file=self.out)
        else:
            print(self._clean(json.dumps(value, ensure_ascii=False, indent=2)), file=self.out)

    def line(self, text: str) -> None:
        if self.mode != "json":
            print(self._clean(text), file=self.out)

    def warn(self, text: str) -> None:
        print(self._clean(text), file=sys.stderr)


# ---------------------------------------------------------------- 命令实现


def build_client(args) -> Client:
    """按「命令行 > 环境变量 > 内置默认值」的优先级拼出客户端"""
    def opt(name, fallback=None):
        value = getattr(args, name, None)
        return fallback if value is None else value

    base_url = opt("base_url") or os.environ.get("DSB_BASE_URL")
    host = opt("host") or os.environ.get("DSB_HOST") or DEFAULT_HOST
    port = opt("port") or int(os.environ.get("DSB_PORT") or DEFAULT_PORT)
    task_id = opt("id") or os.environ.get("DSB_TASK_ID") or DEFAULT_TASK_ID
    session = None if getattr(args, "no_record", False) else (
        opt("session") or os.environ.get("DSB_SESSION") or DEFAULT_SESSION)
    patterns = tuple(opt("redact_pattern", ()) or ())
    env_patterns = os.environ.get("DSB_REDACT")
    if env_patterns:
        patterns += tuple(part for part in re.split(r"[,;]", env_patterns) if part.strip())
    return Client(base_url=base_url, host=host, port=port, task_id=task_id,
                  timeout=opt("timeout", DEFAULT_TIMEOUT), session=session,
                  redact_enabled=not getattr(args, "no_redact", False), redact_patterns=patterns,
                  verbose=getattr(args, "verbose", False), record_dir=opt("record_dir"),
                  response_mode=getattr(args, "response_mode", None),
                  diagnostics=bool(getattr(args, "diagnostics", False)))


def printer_for(args) -> Printer:
    mode = "json" if getattr(args, "json", False) or getattr(args, "select", None) is not None else (
        "compact" if getattr(args, "compact", False) else "full")
    return Printer(mode=mode, mask=not getattr(args, "no_redact", False),
                   patterns=tuple(getattr(args, "redact_pattern", None) or ()),
                   select=getattr(args, "select", None))


def select_field(value, path: str):
    """Select a dot-separated object path, with numeric list indices; never eval input."""
    for part in path.split("."):
        if isinstance(value, dict) and part in value:
            value = value[part]
        elif isinstance(value, list) and part.isdigit() and int(part) < len(value):
            value = value[int(part)]
        else:
            raise UsageError(f"--select 路径不存在:{path} (在 {part})")
    return value


def pick_index(envelope: dict, index: int | None):
    """从批量回执里取出第 N 步;`--index` 也可以用在单条响应上(index 必须是 0)"""
    data = envelope.get("data")
    if not isinstance(data, dict) or "results" not in data:
        if index in (None, 0):
            return envelope
        raise UsageError(f"--index {index} 越界:这条响应不是批量结果(没有 data.results)")
    results = data["results"]
    if index is None:
        return envelope
    if index < 0 or index >= len(results):
        raise UsageError(f"--index {index} 越界:这一批共 {len(results)} 步")
    return results[index]


def cmd_health(client: Client, args, out: Printer) -> int:
    response = client.health()
    out.json(pick_index(response.envelope, args.index))
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_methods(client: Client, args, out: Printer) -> int:
    response = client.methods()
    if not response.ok:
        out.json(response.envelope)
        return EXIT_BUSINESS
    names = response.data.get("methods", []) if isinstance(response.data, dict) else []
    if args.filter:
        names = [name for name in names if args.filter.lower() in name.lower()]
    if args.json:
        out.json({"count": len(names), "methods": names})
    elif args.compact:
        print(" ".join(names))
    else:
        out.line(f"共 {len(names)} 个方法(过滤条件:{args.filter or '无'})")
        for name in names:
            print("  " + name)
    return EXIT_OK


def cmd_config(client: Client, args, out: Printer) -> int:
    response = client.config()
    out.json(pick_index(response.envelope, args.index))
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_tasks(client: Client, args, out: Printer) -> int:
    response = client.tasks()
    out.json(pick_index(response.envelope, args.index))
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_recipes(client: Client, args, out: Printer) -> int:
    if args.run:
        params: dict = {"name": args.run}
        variables = parse_kv(args.var)
        if variables:
            params["vars"] = variables
        response = client.command("run_recipe", params, label=f"run_recipe({args.run})")
        out.response(response, label=f"run_recipe({args.run})", index=None)
        return EXIT_OK if response.ok else EXIT_BUSINESS
    response = client.command("list_recipes", {})
    if args.json or not response.ok:
        out.json(pick_index(response.envelope, args.index))
        return EXIT_OK if response.ok else EXIT_BUSINESS
    data = response.data if isinstance(response.data, dict) else {}
    out.line(f"配方目录:{data.get('dir')}(共 {data.get('count')} 个)")
    for recipe in data.get("recipes", []):
        out.line(f"  {recipe.get('name')}  步数={recipe.get('stepCount')}  {recipe.get('description', '')}")
    return EXIT_OK


def cmd_start(client: Client, args, out: Printer) -> int:
    response = client.start(browser=args.browser, headless=not args.headful)
    out.response(response, label="start")
    if response.ok and isinstance(response.data, dict):
        browser = response.data.get("browser") or {}
        if response.data.get("engineHonored") is False:
            out.warn("注意:engineHonored=false —— 这个服务实例没有按 browser 参数切浏览器(常见于旧发布包)")
        if browser:
            out.line(f"  引擎={browser.get('engine')} 类型={browser.get('type')} "
                     f"profile={browser.get('profileDir')}")
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_close(client: Client, args, out: Printer) -> int:
    response = client.close()
    out.response(response, label="close")
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_shutdown(client: Client, args, out: Printer) -> int:
    response = client.shutdown()
    out.response(response, label="shutdown")
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_run(client: Client, args, out: Printer) -> int:
    params = load_payload(args.params, args.param)
    response = client.command(args.method, params)
    picked = pick_index(response.envelope, args.index) if args.index is not None else None
    out.response(response, label=args.method, payload=picked)
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_batch(client: Client, args, out: Printer) -> int:
    text = read_batch_source(args.file)
    try:
        commands = json.loads(text)
    except ValueError as error:
        raise UsageError(f"批量命令不是合法 JSON:{error}") from None
    if isinstance(commands, dict) and "commands" in commands:
        commands = commands["commands"]  # 允许直接喂 {"commands":[...]} 或整个请求体
    if not isinstance(commands, list):
        raise UsageError("批量命令必须是数组,例如 [{\"get_title\":{}},{\"get_url\":{}}]")

    response = client.batch(commands, stop_on_error=not args.keep_going,
                            stop_on_expect_failure=args.stop_on_expect_failure,
                            asynchronous=args.async_mode, max_duration_ms=args.max_duration_ms)
    if args.async_mode:
        out.response(response, label="commands(async)")
        if not response.ok or not args.wait:
            return EXIT_OK if response.ok else EXIT_BUSINESS
        job_id = (response.data or {}).get("jobId")
        if not job_id:
            out.warn("服务端没有返回 jobId,无法等待")
            return EXIT_BUSINESS
        return wait_and_report(client, out, job_id, poll=args.poll, timeout=args.wait_timeout)
    out.response(response, label="commands",
                 payload=pick_index(response.envelope, args.index) if args.index is not None else None)
    return EXIT_OK if response.ok else EXIT_BUSINESS


def wait_and_report(client: Client, out: Printer, job_id: str, *, poll: float, timeout: float) -> int:
    """轮询异步任务并汇报进度(每步一行,方便盯长批次)"""
    seen = 0

    def tick(response: Response) -> None:
        nonlocal seen
        data = response.data if isinstance(response.data, dict) else {}
        steps = int(data.get("steps") or 0)
        if steps > seen:
            seen = steps
            out.line(f"  任务 {job_id}:已跑 {steps} 步(状态 {data.get('status')})")

    try:
        final = client.wait_job(job_id, poll=poll, timeout=timeout, on_tick=tick)
    except TransportError as error:
        out.warn(str(error))
        return EXIT_TRANSPORT
    data = final.data if isinstance(final.data, dict) else {}
    status = data.get("status")
    result = data.get("data") if isinstance(data.get("data"), dict) else {}
    out.line(f"任务 {job_id} 结束:状态={status} 成功={result.get('succeeded')} 失败={result.get('failed')} "
             f"断言未过={result.get('expectFailed')} 耗时={data.get('durationMs')}ms")
    if final.envelope.get("msg"):
        out.line(f"  原因:{final.envelope.get('msg')}")
    if data.get("error"):
        out.line(f"  错误:{data.get('error')}")
    if not out.mode == "compact":
        out.json(data)
    return EXIT_OK if status == "done" else EXIT_BUSINESS


def cmd_job(client: Client, args, out: Printer) -> int:
    if args.wait:
        return wait_and_report(client, out, args.job_id, poll=args.poll, timeout=args.wait_timeout)
    response = client.job(args.job_id)
    out.json(pick_index(response.envelope, args.index))
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_upload(client: Client, args, out: Printer) -> int:
    response = client.upload(args.file, filename=args.filename)
    out.response(response, label=f"upload({args.filename or Path(args.file).name})")
    if not (args.json or args.compact) and response.ok and isinstance(response.data, dict):
        out.line(f"  服务端路径:{response.data.get('path')}")
        out.line(f"  可直接喂给 upload_file 的 path:{response.data.get('relativePath')}")
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_uploads(client: Client, args, out: Printer) -> int:
    if args.delete:
        response = client.delete_upload(args.delete)
        out.response(response, label=f"delete_upload({args.delete})")
        return EXIT_OK if response.ok else EXIT_BUSINESS
    response = client.uploads()
    out.json(pick_index(response.envelope, args.index))
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_state(client: Client, args, out: Printer) -> int:
    """页面状态摘要:标题、URL、元素数,以及可选的元素清单/正文"""
    params: dict = {"includeElements": not args.text_only}
    if args.viewport_expansion is not None:
        params["viewportExpansion"] = args.viewport_expansion
    if args.include_frames:
        params["includeFrames"] = True
    if args.max_elements is not None:
        params["maxElements"] = args.max_elements
    response = client.command("get_browser_state", params)
    if args.json or out.select is not None or not response.ok:
        out.json(pick_index(response.envelope, args.index))
        return EXIT_OK if response.ok else EXIT_BUSINESS
    data = response.data if isinstance(response.data, dict) else {}
    if data.get("snapshotConsistent") is False:
        out.warn("快照不可靠:" + str(data.get("snapshotIssues") or data.get("snapshotHint")))
    if args.text_only:
        out.line(str(data.get("text") or ""))
        return EXIT_OK
    out.line(f"标题:{data.get('title')}")
    out.line(f"URL:{data.get('url')}")
    out.line(f"元素:{data.get('elementCount')} 个(内联 {len(data.get('elements') or [])}"
             f"{',已截断' if data.get('elementsTruncated') else ''})  截图:{data.get('screenshot')}")
    tabs = data.get("tabs") or []
    if len(tabs) > 1:
        out.line(f"页签 {len(tabs)} 个:" + " / ".join(
            f"[{tab.get('index')}]{tab.get('title')}" for tab in tabs))
    if args.full:
        for element in data.get("elements") or []:
            out.line(f"  [{element.get('index')}] <{element.get('tag')}> {element.get('text') or ''}"
                     f"{(element.get('value') or '') and ' value=' + str(element.get('value'))}")
        if data.get("text"):
            out.line("---- 可交互结构化文本 ----")
            out.line(str(data.get("text")))
    return EXIT_OK


def cmd_js(client: Client, args, out: Printer) -> int:
    params: dict = {"body": read_text_source(args.script)}
    variables = parse_kv(args.var)
    if variables:
        params["vars"] = variables
    response = client.command("execute_js", params, label="execute_js")
    # JS 的「值」才是重点:默认只打摘要 + 返回值,信封里的截图/序号是噪音
    if out.mode == "json" or out.select is not None:
        out.json(pick_index(response.envelope, args.index) if args.index is not None else response.envelope)
    else:
        out.response(response, label="execute_js",
                     payload=(response.data or {}).get("result") if response.ok else None)
    return EXIT_OK if response.ok else EXIT_BUSINESS


def cmd_last(client: Client, args, out: Printer) -> int:
    """重放本会话最近一次的响应(与 PowerShell 客户端的 -Last 一致)"""
    if not client.record_dir.is_dir():
        raise UsageError(f"还没有任何记录:{client.record_dir}")
    files = sorted(client.record_dir.glob("*.res.json"))
    if not files:
        raise UsageError(f"还没有任何记录:{client.record_dir}")
    latest = files[-1]
    envelope = json.loads(latest.read_text(encoding="utf-8"))
    index = int(re.match(r"(\d+)", latest.name).group(1))
    if envelope.get("sendFailed"):
        out.warn(f"#{index:03d} 这次是发送失败:{envelope.get('error')}")
        return EXIT_TRANSPORT
    out.json(pick_index(envelope, args.index))
    return EXIT_OK


# ---------------------------------------------------------------- 自检

#: 自检用的任务 id:特意避开业务常用的小号(1001/2002…),自检可以随便跑、随便关
SELFTEST_TASK_ID = 990001

SELFTEST_PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>dsb selftest</title>
<style>
  @keyframes slide { from { transform: translateX(0); } to { transform: translateX(60px); } }
  #moving { display: inline-block; animation: slide 1.6s linear infinite alternate; }
  .ant-modal-wrap { position: fixed; left: 300px; top: 300px; width: 420px; height: 220px; z-index: 1000; }
</style></head><body>
  <div id="moving">一直在动的按钮</div>
  <div id="clickFlag">idle</div>
  <div id="rows"></div>
  <div class="ant-modal-wrap" id="modalWrap">
    <div class="ant-modal" role="dialog">
      <div class="ant-modal-content">
        <div class="ant-modal-confirm-title">确认提交申请？</div>
        <div class="ant-modal-confirm-btns">
          <button class="ant-btn" id="mCancel">取消</button>
          <button class="ant-btn ant-btn-primary" id="mOk">确定</button>
        </div>
      </div>
      <button class="ant-modal-close" id="mClose">×</button>
    </div>
  </div>
  <script>
    document.getElementById('moving').addEventListener('click', function () {
      document.getElementById('clickFlag').textContent = 'moving-clicked';
    });
    document.getElementById('mCancel').addEventListener('click', function () {
      document.getElementById('modalWrap').remove();
    });
    setTimeout(function () {
      var box = document.getElementById('rows');
      for (var i = 0; i < 3; i++) { box.insertAdjacentHTML('beforeend', '<div class="row">r' + i + '</div>'); }
    }, 400);
  </script>
</body></html>
"""


def cmd_selftest(client: Client, args, out: Printer) -> int:
    """对当前服务跑一遍端到端自检:连不上、参数不对、新命令没生效都会在这里暴露"""
    failures: list[str] = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        mark = "通过" if condition else "失败"
        out.line(f"  [{mark}] {name}{('  ' + detail) if detail else ''}")
        if not condition:
            failures.append(name)

    # 自检要能反复跑,所以用自己的任务 id(默认 990001,不会撞上业务任务),并先清掉上一轮的残留
    task_id = getattr(args, "id", None) or os.environ.get("DSB_TASK_ID") or SELFTEST_TASK_ID
    client.task_id = _as_task_id(task_id)
    out.line(f"自检目标:{client.base_url}(任务 id={client.task_id},会话={client.session or '未记录'})")

    health = client.health()
    check("健康检查", health.ok, health.msg)

    methods = client.methods()
    names = (methods.data or {}).get("methods", []) if methods.ok else []
    check("命令清单", len(names) > 0, f"{len(names)} 个方法")
    for needed in ("get_modals", "close_modal", "mouse_click", "wait_for_stable", "wait_for_count",
                   "get_config", "list_tasks", "run_recipe", "get_job", "cleanup"):
        check(f"新命令可用:{needed}", needed in names)

    client.close()  # 上一轮自检可能留下同名实例,先清掉(不存在时失败也无所谓)
    started = client.start(browser=args.browser, headless=True)
    check("启动任务", started.ok, started.msg)
    if not started.ok:
        return EXIT_BUSINESS
    try:
        honored = (started.data or {}).get("engineHonored")
        check("引擎参数被采纳", honored is not False,
              f"请求={(started.data or {}).get('requestedBrowser')} 实际={(started.data or {}).get('effectiveBrowser')}")

        # 自检页面用本地文件,不依赖外网
        fixture = client.record_dir / "selftest-fixture.html"
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_text(SELFTEST_PAGE, encoding="utf-8")
        url = fixture.resolve().as_uri()

        page = client.command("go_to_url", {"url": url})
        check("打开自检页面", page.ok, page.msg)

        title = client.command("get_title", {})
        check("读标题", title.ok and "selftest" in str((title.data or {}).get("title")),
              str((title.data or {}).get("title")))

        batch = client.batch([
            {"wait_for_count": {"selector": ".row", "min": 3, "timeoutSeconds": 10}},
            {"get_modals": {}},
            {"close_modal": {"which": "top", "button": "取消"}},
            {"get_modals": {}},
            {"click_element_by_selector": {"selector": "#moving", "mode": "auto", "timeoutMs": 900}},
        ])
        results = (batch.data or {}).get("results", []) if isinstance(batch.data, dict) else []
        check("批量执行", batch.ok and len(results) == 5, batch.msg)

        def step_data(position: int) -> dict:
            """按位置取某一步的 data(同名的命令可能出现多次,按名字取会拿错)"""
            if position < len(results) and isinstance(results[position], dict):
                return results[position].get("data") or {}
            return {}

        check("wait_for_count 等到 3 行", step_data(0).get("count") == 3)
        check("get_modals 看到 1 个弹窗", step_data(1).get("count") == 1)
        check("close_modal 真的关掉了", step_data(2).get("closed") is True)
        check("关掉之后弹窗归零", step_data(3).get("count") == 0)
        check("点击降级到真实鼠标", step_data(4).get("mode") == "mouse", f"mode={step_data(4).get('mode')}")

        # 断言:故意让 expect 不通过,验证「动作成功但状态没变」会被标出来
        expect = client.batch([{"execute_js": {"body": "() => 1"},
                                "expect": {"js": "() => 2", "equals": 3}}])
        check("expect 断言能报出不一致",
              (not expect.ok) and ((expect.data or {}).get("expectFailed") == 1)
              and ((expect.data or {}).get("failed") == 0), expect.msg)

        # 异步批次
        async_start = client.batch([{"execute_js": {"body": "() => 42"}}], asynchronous=True)
        job_id = (async_start.data or {}).get("jobId") if isinstance(async_start.data, dict) else None
        check("异步批次返回 jobId", bool(job_id), str(job_id))
        if job_id:
            final = client.wait_job(job_id, poll=0.5, timeout=30)
            check("异步任务跑完", (final.data or {}).get("status") == "done", str((final.data or {}).get("status")))

        tasks = client.tasks()
        check("任务清单能看到自己", tasks.ok and any(
            str(item.get("id")) == str(client.task_id) for item in ((tasks.data or {}).get("tasks") or [])))
        config = client.config()
        check("生效配置可读", config.ok and bool((config.data or {}).get("engine")),
              f"引擎={(config.data or {}).get('engine')} profile={(config.data or {}).get('profileDir', {}).get('resolved')}")

        recipes = client.command("list_recipes", {})
        check("配方库可读", recipes.ok, f"{((recipes.data or {}).get('count'))} 个配方")

        cleanup = client.command("cleanup", {"scope": "data", "olderThanHours": 24})
        check("cleanup 默认只预演", cleanup.ok and (cleanup.data or {}).get("dryRun") is True)
    finally:
        closed = client.close()
        check("关掉任务", closed.ok, closed.msg)

    if failures:
        out.line(f"自检结果:{len(failures)} 项失败 -> {', '.join(failures)}")
        return EXIT_BUSINESS
    out.line("自检结果:全部通过")
    return EXIT_OK


# ---------------------------------------------------------------- 命令行


def add_common_options(parser: argparse.ArgumentParser, *, suppress_defaults: bool = False) -> None:
    """把通用选项挂到某个 parser 上

    `dsb --port 10050 health` 和 `dsb health --port 10050` 都得能用,所以顶层 parser 与各子命令
    parser 都要认这些选项。argparse 的坑是:子 parser 的默认值会**覆盖**顶层已经解析出来的值,所以挂到
    子命令上时默认值用 SUPPRESS —— 只有用户真的在子命令后面写了这个选项,才会覆盖顶层那份。
    """

    def add(*names, **kwargs):
        if suppress_defaults:
            kwargs["default"] = argparse.SUPPRESS
        parser.add_argument(*names, **kwargs)

    add("--base-url", help="完整基地址,例如 http://10.0.0.5:10049(优先于 --host/--port)")
    add("--host", help=f"服务端主机(默认 {DEFAULT_HOST})")
    add("--port", type=int, help=f"服务端端口(默认 {DEFAULT_PORT})")
    add("--id", help=f"任务 ID(默认 {DEFAULT_TASK_ID});必须是数字")
    add("--timeout", type=float, default=DEFAULT_TIMEOUT, help="单次请求超时秒数(默认 300)")
    add("--session", help=f"记录会话名,落到 logs/agent/<会话>/(默认 {DEFAULT_SESSION})")
    add("--record-dir", help="记录目录的根(默认 logs/agent)")
    add("--no-record", action="store_true", help="不把请求与响应写到本地")
    add("--no-redact", action="store_true", help="关闭脱敏(默认对手机号/证件号/邮箱等打码)")
    add("--redact-pattern", action="append", help="额外要打码的词,可重复")
    add("--json", action="store_true", help="只输出 JSON(便于管道)")
    add("--select", metavar="PATH", help="仅输出指定字段的 JSON，如 data.text / data.fields.0；仍脱敏和记录，失败保留完整错误")
    add("--compact", "--summary", dest="compact", action="store_true",
        help="只输出一行摘要(--summary 是同一个开关的正名;注意它只管本地输出,"
             "服务端的响应精简模式要用 --response-mode compact)")
    add("--response-mode", dest="response_mode", metavar="MODE",
        help="请求信封里的 responseMode(服务端目前认 compact=响应精简模式),与 --compact/--summary 无关")
    add("--diagnostics", action="store_true",
        help="请求信封里带 diagnostics:true,精简模式也保留点击诊断字段")
    add("--index", type=int, help="从批量结果里取第 N 步(单条响应只能用 0)")
    add("-v", "--verbose", action="store_true", help="打印调试信息")


class ArgumentParser(argparse.ArgumentParser):
    """把 argparse 的「参数错」也变成 UsageError,好统一成退出码 3

    argparse 默认在参数错时直接 `sys.exit(2)` 并打印一大段 usage,脚本里没法区分它和业务失败。
    """

    def error(self, message):  # noqa: D401 - argparse 的钩子
        raise UsageError(message)


def build_parser() -> argparse.ArgumentParser:
    parser = ArgumentParser(
        prog="dsb",
        description="deepseek-browser-use 命令行客户端(只用 Python 标准库)",
        epilog="通用选项放在子命令前面或后面都行。\n"
               "退出码:0 成功 / 1 传输或协议错 / 2 业务失败 / 3 用法错。\n"
               "环境变量:DSB_BASE_URL、DSB_HOST、DSB_PORT、DSB_TASK_ID、DSB_SESSION、DSB_REDACT。\n"
               "例子:dsb --port 10049 health | dsb start --browser firefox | "
               "dsb run go_to_url -p url=https://example.com | dsb batch cmds.json --async --wait",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", action="version", version=f"dsb {__version__}")
    add_common_options(parser)

    # 子命令的通用选项用 SUPPRESS 默认值,避免把顶层解析出来的值覆盖成 None
    common = ArgumentParser(add_help=False)
    add_common_options(common, suppress_defaults=True)

    subs = parser.add_subparsers(dest="command", required=True)

    subs.add_parser("health", parents=[common], help="健康检查")
    p = subs.add_parser("methods", parents=[common], help="命令清单")
    p.add_argument("filter", nargs="?", help="只看名字里含这个片段的方法")
    subs.add_parser("config", parents=[common], help="服务端生效配置")
    subs.add_parser("tasks", parents=[common], help="当前活着的任务")
    subs.add_parser("last", parents=[common], help="重放本会话最近一次的响应")

    p = subs.add_parser("recipes", parents=[common], help="配方列表;--run 直接跑一个")
    p.add_argument("--run", help="要跑的配方名")
    p.add_argument("--var", action="append", help="配方变量,写成 k=v,可重复")

    p = subs.add_parser("start", parents=[common], help="起一个任务")
    p.add_argument("--browser", help="auto/chromium/chrome/edge/firefox")
    p.add_argument("--headful", action="store_true", help="弹出真实窗口(默认无头)")

    subs.add_parser("close", parents=[common], help="关掉这个任务")
    subs.add_parser("shutdown", parents=[common], help="关掉所有任务与共享浏览器")

    p = subs.add_parser("run", parents=[common], help="执行一条命令")
    p.add_argument("method", help="命令名,例如 go_to_url")
    p.add_argument("--params", help="参数 JSON,或 @文件.json,或 - 读标准输入")
    p.add_argument("-p", "--param", action="append", help="参数 key=value(值按 JSON 解析),可重复")

    p = subs.add_parser("batch", parents=[common], help="批量执行命令(数组 JSON,默认读标准输入)")
    p.add_argument("file", nargs="?", help="命令数组文件;省略或 - 表示读标准输入")
    p.add_argument("--keep-going", action="store_true", help="一条失败也继续跑完(服务端 stopOnError=false)")
    p.add_argument("--stop-on-expect-failure", action="store_true", help="expect 断言没过就停下")
    p.add_argument("--async", dest="async_mode", action="store_true", help="后台跑,立刻返回 jobId")
    p.add_argument("--wait", action="store_true", help="配合 --async:轮询到任务结束")
    p.add_argument("--poll", type=float, default=1.0, help="轮询间隔秒数(默认 1)")
    p.add_argument("--wait-timeout", type=float, default=600.0, help="等任务的上限秒数(默认 600)")
    p.add_argument("--max-duration-ms", type=int, help="整批的总时长上限")

    p = subs.add_parser("job", parents=[common], help="查/等异步任务")
    p.add_argument("job_id")
    p.add_argument("--wait", action="store_true", help="轮询到任务结束")
    p.add_argument("--poll", type=float, default=1.0, help="轮询间隔秒数(默认 1)")
    p.add_argument("--wait-timeout", type=float, default=600.0, help="等任务的上限秒数(默认 600)")

    p = subs.add_parser("upload", parents=[common], help="把文件送到服务端暂存区")
    p.add_argument("file")
    p.add_argument("--filename", help="服务端保存成什么名字(默认用本地文件名)")

    p = subs.add_parser("uploads", parents=[common], help="列出暂存文件;--delete 删一个")
    p.add_argument("--delete", help="要删除的暂存文件名")

    p = subs.add_parser("state", parents=[common], help="页面状态摘要")
    p.add_argument("--max-elements", type=int, help="内联元素条数上限")
    p.add_argument("--full", action="store_true", help="连元素清单与结构化文本一起打印")
    p.add_argument("--text-only", action="store_true", help="仅输出脱敏后的结构化文本，不重复列出元素")
    p.add_argument("--viewport-expansion", type=int, help="扩展快照视口像素；-1 纳入全部元素")
    p.add_argument("--include-frames", action="store_true", help="纳入跨域 iframe")

    p = subs.add_parser("js", parents=[common], help="执行 JavaScript")
    p.add_argument("script", help="脚本内容,或 @脚本.js,或 - 读标准输入")
    p.add_argument("--var", action="append", help="注入 {{变量}},写成 k=v,可重复")

    p = subs.add_parser("selftest", parents=[common], help="对当前服务跑一遍端到端自检")
    p.add_argument("--browser", help="自检时用哪个浏览器(默认服务配置)")

    return parser


#: 子命令名;其中 start / close 既是子命令**也是**服务端方法名,不能一律当成误用
SUBCOMMAND_NAMES = ("health", "methods", "config", "tasks", "last", "recipes", "start", "close", "shutdown",
                    "run", "batch", "job", "upload", "uploads", "state", "js", "selftest")

#: 这两个名字在服务端也是合法方法,`dsb run start` / `dsb run close` 是正常用法
SUBCOMMAND_ALSO_METHOD = ("start", "close")


def subcommand_misuse_hint(argv: list[str] | None) -> str | None:
    """「把子命令当成 run 的方法名」这种用法错,给一句对症的提示

    实测踩过:照着文档敲 `dsb run js @脚本.js`(前面还带着 --port/--id),只拿到一句
    `用法错:unrecognized arguments: @脚本.js` —— 真正的原因是 js / batch / state 这些是**子命令**,
    不是 run 的方法名,而 argparse 的通用提示完全指不到这一点,只能去翻 --help。
    """
    if not argv:
        return None
    bare = [item for item in argv if not item.startswith("-")]
    if "run" not in bare:
        return None
    rest = bare[bare.index("run") + 1:]
    if not rest:
        return None
    name = rest[0]
    if name not in SUBCOMMAND_NAMES or name in SUBCOMMAND_ALSO_METHOD:
        return None
    return (f"提示:{name} 是子命令,不是 run 的方法名 —— 直接写成 `dsb {name} ...`"
            f"(例如 `dsb js @脚本.js`、`dsb batch cmds.json`、`dsb state --full`);"
            f"run 只用来调服务端方法,例如 `dsb run go_to_url -p url=https://example.com`")


HANDLERS = {
    "health": cmd_health,
    "methods": cmd_methods,
    "config": cmd_config,
    "tasks": cmd_tasks,
    "last": cmd_last,
    "recipes": cmd_recipes,
    "start": cmd_start,
    "close": cmd_close,
    "shutdown": cmd_shutdown,
    "run": cmd_run,
    "batch": cmd_batch,
    "job": cmd_job,
    "upload": cmd_upload,
    "uploads": cmd_uploads,
    "state": cmd_state,
    "js": cmd_js,
    "selftest": cmd_selftest,
}


def main(argv: list[str] | None = None) -> int:
    setup_stdout()
    try:
        args = build_parser().parse_args(argv)
    except UsageError as error:  # argparse 的参数错也统一成退出码 3
        print(f"用法错:{error}", file=sys.stderr)
        hint = subcommand_misuse_hint(argv if argv is not None else sys.argv[1:])
        if hint:
            print(hint, file=sys.stderr)
        print("提示:dsb --help 看用法,dsb methods 看服务端支持的命令", file=sys.stderr)
        return EXIT_USAGE
    if args.select is not None:
        args.json = True
    out = printer_for(args)
    try:
        client = build_client(args)
        return HANDLERS[args.command](client, args, out)
    except UsageError as error:
        out.warn(f"用法错:{error}")
        return EXIT_USAGE
    except TransportError as error:
        # 传输层问题:服务没起、地址不对、超时、响应不是 JSON
        out.warn(f"ERROR {error}")
        return EXIT_TRANSPORT
    except KeyboardInterrupt:
        out.warn("已中断")
        return 130


if __name__ == "__main__":
    sys.exit(main())
