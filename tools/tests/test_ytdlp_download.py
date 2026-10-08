"""Exercise the real download helper without importing Chaquopy or yt-dlp."""
import ast
import io
import os
from pathlib import Path
import tempfile
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading
from unittest.mock import patch


source = Path(__file__).resolve().parents[2] / "app/src/main/python/ytdlp_bridge.py"
tree = ast.parse(source.read_text(encoding="utf-8"))
helper = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
              and node.name == "_fetch_to_file")
namespace = {"os": os, "re": __import__("re"), "time": __import__("time")}
exec(compile(ast.Module(body=[helper], type_ignores=[]), str(source), "exec"), namespace)
fetch = namespace["_fetch_to_file"]


class Response(io.BytesIO):
    def __init__(self, data, status=206, range_header=None, length=None, etag=None):
        super().__init__(data)
        self.status = status
        self.headers = {}
        if range_header is not None:
            self.headers["Content-Range"] = range_header
        if length is not None:
            self.headers["Content-Length"] = str(length)
        if etag is not None:
            self.headers["ETag"] = etag


class DownloadTest(unittest.TestCase):
    def setUp(self):
        temp_root = source.parents[4] / "build"
        temp_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temp_root)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "song.m4a"
        self.info = {"url": "https://fixture.invalid/old", "format_id": "140", "ext": "m4a"}
        self.requests = []

    def run_fetch(self, responses, refresh=None, size=None):
        pending = iter(responses)

        def open_response(request, timeout):
            self.requests.append((request.full_url, request.get_header("Range")))
            response = next(pending)
            if isinstance(response, Exception):
                raise response
            return response

        info = dict(self.info)
        if size:
            info["filesize"] = size
        with patch("urllib.request.urlopen", side_effect=open_response), patch("time.sleep"):
            fetch(info, str(self.path), chunk=4, refresh=refresh)

    def assert_clean_failure(self):
        self.assertFalse(self.path.exists())
        self.assertFalse(Path(str(self.path) + ".part").exists())

    def test_verified_ranges_include_short_final_chunk(self):
        self.run_fetch([Response(b"abcd", range_header="bytes 0-3/6"),
                        Response(b"ef", range_header="bytes 4-5/6")])
        self.assertEqual(b"abcdef", self.path.read_bytes())
        self.assertEqual(["bytes=0-3", "bytes=4-5"], [r[1] for r in self.requests])

    def test_short_chunk_retries_same_offset_without_duplicates(self):
        self.run_fetch([Response(b"abcd", range_header="bytes 0-3/8"),
                        Response(b"e", range_header="bytes 4-7/8"),
                        Response(b"efgh", range_header="bytes 4-7/8")])
        self.assertEqual(b"abcdefgh", self.path.read_bytes())
        self.assertEqual(["bytes=0-3", "bytes=4-7", "bytes=4-7"], [r[1] for r in self.requests])

    def test_empty_response_is_never_saved_as_finished(self):
        with self.assertRaises(OSError):
            self.run_fetch([Response(b"", range_header="bytes 0-3/4") for _ in range(3)])
        self.assertEqual(3, len(self.requests))
        self.assert_clean_failure()

    def test_wrong_offset_or_unknown_total_is_rejected(self):
        for header in ("bytes 1-3/4", "bytes 0-3/*", "bytes 0-4/4", None):
            with self.subTest(header=header), self.assertRaises(ValueError):
                self.run_fetch([Response(b"abcd", range_header=header)])
            self.assert_clean_failure()

    def test_known_size_must_match_server(self):
        with self.assertRaises(ValueError):
            self.run_fetch([Response(b"abcd", range_header="bytes 0-3/4")], size=8)
        self.assert_clean_failure()

    def test_changed_content_or_size_cannot_be_joined(self):
        for second in (Response(b"efgh", range_header="bytes 4-7/9", etag="one"),
                       Response(b"efgh", range_header="bytes 4-7/8", etag="two")):
            with self.assertRaises(ValueError):
                self.run_fetch([Response(b"abcd", range_header="bytes 0-3/8", etag="one"), second])
            self.assert_clean_failure()

    def test_ignored_range_at_zero_accepts_complete_sized_file(self):
        self.run_fetch([Response(b"abcdefgh", status=200, length=8)])
        self.assertEqual(b"abcdefgh", self.path.read_bytes())

    def test_ignored_range_after_progress_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_fetch([Response(b"abcd", range_header="bytes 0-3/8"),
                            Response(b"abcdefgh", status=200, length=8)])
        self.assert_clean_failure()

    def test_full_response_needs_reliable_length(self):
        with self.assertRaises(ValueError):
            self.run_fetch([Response(b"abcd", status=200)])
        self.assert_clean_failure()

    def test_network_failures_have_bounded_retries(self):
        with self.assertRaises(OSError):
            self.run_fetch([TimeoutError("fixture") for _ in range(3)])
        self.assertEqual(3, len(self.requests))
        self.assert_clean_failure()

    def test_expired_link_refreshes_once_and_keeps_verified_prefix(self):
        fresh = dict(self.info, url="https://fixture.invalid/new")
        calls = []

        def refresh():
            calls.append(True)
            return fresh

        self.run_fetch([Response(b"abcd", range_header="bytes 0-3/8"),
                        urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None),
                        Response(b"efgh", range_header="bytes 4-7/8")], refresh=refresh)
        self.assertEqual(b"abcdefgh", self.path.read_bytes())
        self.assertEqual(1, len(calls))
        self.assertEqual((fresh["url"], "bytes=4-7"), self.requests[-1])

    def test_refresh_cannot_change_format(self):
        with self.assertRaises(ValueError):
            self.run_fetch([urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None)],
                           refresh=lambda: dict(self.info, format_id="251", ext="webm"))
        self.assert_clean_failure()

    def test_last_attempt_refresh_gets_one_request_with_new_link(self):
        fresh = dict(self.info, url="https://fixture.invalid/new")
        self.run_fetch([TimeoutError("fixture"), TimeoutError("fixture"),
                        urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None),
                        Response(b"abcd", range_header="bytes 0-3/4")], refresh=lambda: fresh)
        self.assertEqual(b"abcd", self.path.read_bytes())
        self.assertEqual(4, len(self.requests))
        self.assertEqual(fresh["url"], self.requests[-1][0])

    def test_extra_refreshed_request_still_has_a_finite_limit(self):
        with self.assertRaises(OSError):
            self.run_fetch([TimeoutError("fixture"), TimeoutError("fixture"),
                            urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None),
                            TimeoutError("fixture")], refresh=lambda: dict(self.info))
        self.assertEqual(4, len(self.requests))
        self.assert_clean_failure()

    def test_failed_download_preserves_existing_file(self):
        self.path.write_bytes(b"existing")
        with self.assertRaises(OSError):
            self.run_fetch([TimeoutError("fixture") for _ in range(3)])
        self.assertEqual(b"existing", self.path.read_bytes())
        self.assertFalse(Path(str(self.path) + ".part").exists())

    def test_content_length_must_agree_with_range(self):
        with self.assertRaises(ValueError):
            self.run_fetch([Response(b"abcd", range_header="bytes 0-3/4", length=3)])
        self.assert_clean_failure()

    def test_server_errors_retry_same_request(self):
        self.run_fetch([urllib.error.HTTPError(self.info["url"], 503, "busy", {}, None),
                        Response(b"abcd", range_header="bytes 0-3/4")])
        self.assertEqual(b"abcd", self.path.read_bytes())
        self.assertEqual(self.requests[0], self.requests[1])

    def test_refresh_is_bounded_and_rejects_changed_size(self):
        calls = []
        def refresh():
            calls.append(True)
            return dict(self.info, url="https://fixture.invalid/new")
        with self.assertRaises(urllib.error.HTTPError):
            self.run_fetch([urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None),
                            urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None)],
                           refresh=refresh)
        self.assertEqual(1, len(calls))
        self.assert_clean_failure()
        with self.assertRaises(ValueError):
            self.run_fetch([Response(b"abcd", range_header="bytes 0-3/8"),
                            urllib.error.HTTPError(self.info["url"], 403, "expired", {}, None)],
                           refresh=lambda: dict(self.info, filesize=9))
        self.assert_clean_failure()

    def test_interrupted_full_response_resumes_with_range(self):
        self.run_fetch([Response(b"abcd", status=200, length=8),
                        Response(b"efgh", range_header="bytes 4-7/8")])
        self.assertEqual(b"abcdefgh", self.path.read_bytes())
        self.assertEqual("bytes=4-7", self.requests[-1][1])

    def test_actual_http_disconnect_retries_uncommitted_chunk(self):
        ranges = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                ranges.append(self.headers["Range"])
                start = int(self.headers["Range"].split("=")[1].split("-")[0])
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{start + 3}/8")
                self.send_header("Content-Length", "4")
                self.end_headers()
                data = b"abcdefgh"[start:start + 4]
                self.wfile.write(data[:1] if len(ranges) == 2 else data)
                self.close_connection = True
        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            info = dict(self.info, url=f"http://127.0.0.1:{server.server_port}/song")
            with patch("time.sleep"):
                fetch(info, str(self.path), chunk=4)
            self.assertEqual(b"abcdefgh", self.path.read_bytes())
            self.assertEqual(["bytes=0-3", "bytes=4-7", "bytes=4-7"], ranges)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
