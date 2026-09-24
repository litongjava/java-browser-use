"""dsb 客户端的脱敏规则自测(不连服务,纯本地)

为什么单独写一个文件:PowerShell 5.1 把 `python -c "…"` 里的双引号吃掉,测试用例没法直接内联,
所以固定成文件跑:`python scripts/client/test_dsb.py`。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from dsb import (Client, Printer, Response, UsageError, parse_kv, parse_value, read_batch_source,  # noqa: E402
                 read_local_file, redact, redact_obj, _summarize)

CASES = [
    # (原文, 期望结果)
    ("统一社会信用代码91310118MAK7DA2R14", "统一社会信用代码***统一社会信用代码***"),
    ("91310118MAK7DA2R14", "***统一社会信用代码***"),
    ("USCC=91310118MAK7DA2R14;", "USCC=***统一社会信用代码***;"),
    ("身份证11010119900307123X", "身份证***证件号***"),
    ("证件号 11010119900307123x 结束", "证件号 ***证件号*** 结束"),
    ("电话13800138000", "电话***手机号***"),
    ("13800138000", "***手机号***"),
    ("订单号123456789012345678", "订单号***长号码***"),
    ("卡号6222021234567890123", "卡号***长号码***"),
    ("邮箱zhang.san+tag@example.co.uk", "邮箱***邮箱***"),
    # 不该动的:商标注册号、官费、日期、命令名
    ("商标注册号94198837,官费270元", "商标注册号94198837,官费270元"),
    ("2026-09-24 10:37:02 click_element_by_selector", "2026-09-24 10:37:02 click_element_by_selector"),
    ("id=1001 seq=3 elementCount=88", "id=1001 seq=3 elementCount=88"),
]

KV_CASES = [
    (["url=https://example.com", "timeoutMs=900"], {"url": "https://example.com", "timeoutMs": 900}),
    (["headless=true", "mode=mouse"], {"headless": True, "mode": "mouse"}),
    (["text=Mac Mini", "max=5"], {"text": "Mac Mini", "max": 5}),
    (["index=0"], {"index": 0}),
]

VALUE_CASES = [
    ("1", 1), ("0", 0), ("true", True), ("false", False), ("1.5", 1.5),
    ('{"a":1}', {"a": 1}), ("[1,2]", [1, 2]), ("Mac Mini", "Mac Mini"), ('"引号"', "引号"), ("", ""),
]


def main() -> int:
    failed = 0

    for source, expected in CASES:
        actual = redact(source)
        ok = actual == expected
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] redact({source!r}) -> {actual!r}"
              + ("" if ok else f"  期望 {expected!r}"))

    for items, expected in KV_CASES:
        actual = parse_kv(items)
        ok = actual == expected
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] parse_kv({items}) -> {actual}"
              + ("" if ok else f"  期望 {expected}"))

    for text, expected in VALUE_CASES:
        actual = parse_value(text)
        ok = actual == expected and type(actual) is type(expected)
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] parse_value({text!r}) -> {actual!r}"
              + ("" if ok else f"  期望 {expected!r}"))

    # 结构递归脱敏:字典键不动,字符串叶子打码
    masked = redact_obj({"联系人": "李四 13800138000", "nested": [{"证件": "91310118MAK7DA2R14"}]})
    ok = masked == {"联系人": "李四 ***手机号***", "nested": [{"证件": "***统一社会信用代码***"}]}
    failed += 0 if ok else 1
    print(f"[{'通过' if ok else '失败'}] redact_obj 递归脱敏 -> {masked}")

    # 任务 id 必须是数字:非数字要抛用法错,而不是把错误推给服务端
    for bad in ("abc", "10a", ""):
        try:
            Client(task_id=bad, session=None)
        except UsageError:
            print(f"[通过] 非法 id {bad!r} 抛 UsageError")
        else:
            failed += 1
            print(f"[失败] 非法 id {bad!r} 没有抛 UsageError")
    for good, expected in ((1001, 1001), ("1001", 1001), (990001, 990001)):
        actual = Client(task_id=good, session=None).task_id
        ok = actual == expected
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] id {good!r} -> {actual!r}")

    # 摘要行:批量结果要能一眼看出哪一步挂了
    summary = _summarize({"count": 2, "failed": 1, "results": [
        {"index": 0, "command": "get_title", "ok": True},
        {"index": 1, "command": "click_element_by_index", "ok": False},
    ]})
    ok = "count=2" in summary and "1:click_element_by_index=fail" in summary
    failed += 0 if ok else 1
    print(f"[{'通过' if ok else '失败'}] _summarize -> {summary.strip()}")

    # 记录目录的编号接着往下走,不覆盖上一轮
    from dsb import _next_index
    import tempfile
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder)
        (path / "001.req.json").write_text("{}", encoding="utf-8")
        (path / "007.res.json").write_text("{}", encoding="utf-8")
        actual = _next_index(path)
        ok = actual == 8
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] _next_index -> {actual}(期望 8)")

        # Windows PowerShell 的 Out-File -Encoding utf8 会写 BOM,读参数文件必须容忍它
        bom = path / "with-bom.json"
        bom.write_bytes("\ufeff[{\"get_title\":{}}]".encode("utf-8"))
        try:
            text = read_local_file(str(bom))
            ok = text.startswith("[{") and "\ufeff" not in text
        except UsageError as error:
            ok = False
            text = str(error)
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] 带 BOM 的文件能被读成干净 JSON -> {text[:20]!r}")

        plain = path / "no-bom.json"
        plain.write_bytes("[{\"get_title\":{}}]".encode("utf-8"))
        ok = read_local_file(str(plain)).startswith("[{")
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] 不带 BOM 的文件照常读")

        # batch 的位置参数是**文件路径**,不是 JSON 正文(踩过一次:把文件名当 JSON 解析了)
        ok = read_batch_source(str(plain)).startswith("[{")
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] read_batch_source 按文件路径读")

        try:
            read_batch_source(str(path / "missing.json"))
        except UsageError:
            print("[通过] 文件不存在时抛 UsageError(退出码 3,不是 traceback)")
        else:
            failed += 1
            print("[失败] 文件不存在时没有抛 UsageError")

        # 输出策略:--compact 只一行,--json 只有 JSON
        import io
        sample = Response("http://x", 200, {"data": {"count": 2, "results": [
            {"index": 0, "command": "get_title", "ok": True}]}, "ok": True, "code": 1}, "{}", 12)
        buffer = io.StringIO()
        Printer(mode="compact", out=buffer).response(sample, label="commands")
        lines = [line for line in buffer.getvalue().splitlines() if line.strip()]
        ok = len(lines) == 1 and lines[0].startswith("commands OK")
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] --compact 只输出一行 -> {lines}")

        buffer = io.StringIO()
        Printer(mode="json", out=buffer).response(sample, label="commands")
        ok = buffer.getvalue().lstrip().startswith("{")
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] --json 只输出 JSON")

        buffer = io.StringIO()
        Printer(mode="full", out=buffer).response(sample, label="commands", payload={"picked": 1})
        ok = "commands OK" in buffer.getvalue() and '"picked": 1' in buffer.getvalue()
        failed += 0 if ok else 1
        print(f"[{'通过' if ok else '失败'}] payload 指定时只印被挑中的那一步")

    print(f"\n结果:{'全部通过' if failed == 0 else f'{failed} 项失败'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
