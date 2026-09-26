"""CLI projections keep privacy, recording and exit semantics intact."""
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from dsb import (Client, Printer, Response, UsageError, build_parser, cmd_run,
                 cmd_state, select_field)


class OutputSelectionTest(unittest.TestCase):
    def test_nested_paths_and_missing_are_distinct_from_null(self):
        self.assertIsNone(select_field({'data': {'fields': [None]}}, 'data.fields.0'))
        for path in ('data.fields.1', 'data.missing', 'data.fields.-1'):
            with self.assertRaises(UsageError):
                select_field({'data': {'fields': [None]}}, path)

    def test_selection_redacts_and_does_not_hide_failures(self):
        buf = io.StringIO()
        printer = Printer(out=buf, select='data.text')
        printer.json({'ok': True, 'data': {'text': 'Email test@example.com'}})
        self.assertEqual(json.loads(buf.getvalue()), 'Email ***邮箱***')
        buf.seek(0)
        buf.truncate()
        printer.json({'ok': False, 'msg': 'failed', 'data': None})
        self.assertFalse(json.loads(buf.getvalue())['ok'])

    def test_text_only_sends_options_and_warns_about_unreliable_snapshot(self):
        args = build_parser().parse_args(['state', '--text-only', '--include-frames',
                                         '--viewport-expansion', '-1'])
        client = Mock()
        client.command.return_value = Response('test', 200, {'ok': True, 'data': {
            'text': 'test@example.com', 'snapshotConsistent': False,
            'snapshotIssues': ['url_changed']}}, '', 1)
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(errors):
            self.assertEqual(cmd_state(client, args, Printer(out=output)), 0)
        self.assertEqual(output.getvalue().strip(), '***邮箱***')
        self.assertIn('url_changed', errors.getvalue())
        client.command.assert_called_once_with('get_browser_state', {
            'includeElements': False, 'viewportExpansion': -1, 'includeFrames': True})

    def test_business_exit_code_survives_selection(self):
        args = build_parser().parse_args(['run', 'get_title', '--select', 'data.title'])
        client = Mock()
        client.command.return_value = Response('test', 200, {'ok': False, 'msg': 'no task'}, '', 1)
        self.assertEqual(cmd_run(client, args, Printer(out=io.StringIO(), select=args.select)), 2)

    def test_projection_preserves_usable_job_id_but_masks_private_fields(self):
        payload = {'data': {'jobId': '1790232350369123456', 'phone': '13800138000'}}
        for path, expected in [('data.jobId', '1790232350369123456'), ('data.phone', '***手机号***')]:
            output = io.StringIO()
            Printer(out=output, select=path).json(payload)
            self.assertEqual(json.loads(output.getvalue()), expected)

    def test_selection_keeps_full_redacted_record(self):
        with tempfile.TemporaryDirectory() as folder:
            client = Client(record_dir=folder, session='test')
            client.request = Mock(return_value=Response('test', 200, {'ok': True, 'data': {
                'title': 'Example', 'text': 'test@example.com', 'extra': 123}}, '', 1))
            args = build_parser().parse_args(['run', 'get_title', '--select', 'data.title'])
            output = io.StringIO()
            self.assertEqual(cmd_run(client, args, Printer(out=output, select=args.select)), 0)
            self.assertEqual(json.loads(output.getvalue()), 'Example')
            record = json.loads(next(Path(folder).glob('*.res.json')).read_text(encoding='utf-8'))
            self.assertEqual(record['data']['text'], '***邮箱***')
            self.assertEqual(record['data']['extra'], 123)
            self.assertTrue((Path(folder) / 'steps.log').exists())


if __name__ == '__main__':
    unittest.main()
