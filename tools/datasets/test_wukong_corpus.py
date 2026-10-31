import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from wukong_corpus import normalize, sql_text
from check_resources import probe, resolve
from import_corpus import Mysql, load_input


class CorpusTests(unittest.TestCase):
    def row(self, caption="美食与咖啡的日常记录", url="https://example.org/image.jpg"):
        return normalize({"caption": caption, "url": url}, "wukong_release/wukong_100m_0.csv", 1)

    def test_fields_match_business_lengths_without_inventing_author_or_dimensions(self):
        row = self.row("旅行风景" * 100)
        self.assertEqual(120, len(row["title"]))
        self.assertEqual(400, len(row["content"]))
        self.assertIsNone(row["author_id"])
        self.assertIsNone(row["published_at"])
        self.assertIsNone(row["clips"][0]["width"])
        self.assertEqual("RESEARCH_ONLY", row["visibility"])

    def test_original_caption_is_preserved(self):
        text = "  咖啡与美食\n 每日记录  "
        row = self.row(text)
        self.assertEqual(text, row["original_caption"])
        self.assertEqual("咖啡与美食 每日记录", row["content"])

    def test_non_chinese_and_overlong_caption_are_rejected(self):
        self.assertIsNone(self.row("An English caption"))
        self.assertIsNone(self.row("旅" * 5001))
        self.assertIsNone(self.row("美食" + " " * 70000))

    def test_credentials_and_private_urls_are_rejected(self):
        for url in ["file:///etc/passwd", "http://127.0.0.1/a", "http://[::1]/a", "http://10.0.0.1/a", "http://localhost/a", "http://host.local/a", "http://user:pass@example.org/a", "https://example.org:3306/a", "https://example.org/a\nHost:x"]:
            with self.subTest(url=url): self.assertIsNone(self.row(url=url))

    def test_basic_contact_screening(self):
        self.assertIsNone(self.row("美食联系方式 13812345678"))
        self.assertIsNone(self.row("咖啡邮件联系 test@example.com"))

    def test_deduplication_hashes_are_deterministic(self):
        first, second = self.row(), self.row()
        self.assertEqual(first["source_record_id"], second["source_record_id"])
        other = normalize({"caption": first["original_caption"], "url": first["source_url"]}, first["source_file"], 2)
        self.assertNotEqual(first["source_record_id"], other["source_record_id"])
        self.assertEqual(first["content_hash"], other["content_hash"])

    def test_sql_values_never_interpolate_source_text(self):
        hostile = "'); DROP DATABASE pixelflow_app; --\\咖啡"
        self.assertNotIn("DROP", sql_text(hostile))
        self.assertEqual("CONVERT(X'' USING utf8mb4)", sql_text(""))
        self.assertEqual("NULL", sql_text(None))

    def test_business_database_is_never_an_import_target(self):
        for name in ["pixelflow_app", "pixelflow_analytics", "mysql", "pixelflow_corpus;DROP DATABASE mysql"]:
            with self.subTest(name=name), self.assertRaises(ValueError): Mysql(Path("unused"), name)

    def test_private_dns_resolution_is_blocked_before_http(self):
        resolve.cache_clear()
        with patch("socket.getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 80))]), patch("http.client.HTTPConnection") as connection:
            self.assertEqual("BLOCKED", probe("http://private.example/image.jpg")["resource_status"])
            connection.assert_not_called()
        resolve.cache_clear()

    def write_input(self, folder, rows):
        data = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows).encode()
        digest = hashlib.sha256(data).hexdigest()
        paths = [folder / name for name in ("records.jsonl", "manifest.json", "checks.json")]
        paths[0].write_bytes(data)
        paths[1].write_text(json.dumps({"records_sha256": digest, "accepted": len(rows)}))
        paths[2].write_text(json.dumps({"records_sha256": digest, "count": len(rows), "records": {r["source_record_id"]: {"resource_status": "REACHABLE"} for r in rows}}))
        return paths

    def test_valid_input_and_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = self.write_input(Path(folder), [self.row()])
            self.assertEqual(1, len(load_input(*paths)[0]))
            paths[0].write_bytes(paths[0].read_bytes() + b" ")
            with self.assertRaises(ValueError): load_input(*paths)

    def test_publication_or_caption_tampering_is_rejected_even_with_new_checksum(self):
        with tempfile.TemporaryDirectory() as folder:
            for field, value in [("visibility", "PUBLISHED"), ("title", "篡改后的标题"), ("author_id", "real-user")]:
                row = self.row(); row[field] = value
                paths = self.write_input(Path(folder), [row])
                with self.subTest(field=field), self.assertRaises(ValueError): load_input(*paths)

    def test_duplicate_records_and_unchecked_resources_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = self.write_input(Path(folder), [self.row(), self.row()])
            with self.assertRaises(ValueError): load_input(*paths)
            paths = self.write_input(Path(folder), [self.row()])
            checks = json.loads(paths[2].read_text()); checks["records"] = {}
            paths[2].write_text(json.dumps(checks))
            with self.assertRaises(ValueError): load_input(*paths)


if __name__ == "__main__": unittest.main()
