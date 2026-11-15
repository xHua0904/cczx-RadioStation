# -*- coding: utf-8 -*-
"""验证真实 SQL 脚本和旧数据导出，不替代 Spring Boot 接口集成测试。"""
import importlib.util
import json
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("export_legacy", ROOT / "tools" / "export_legacy.py")
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)

with tempfile.TemporaryDirectory(prefix="radio-migration-") as directory:
    directory = Path(directory)
    legacy = directory / "legacy.db"
    connection = sqlite3.connect(legacy)
    connection.executescript((ROOT / "src/main/resources/db/sqlite/V1__initial_schema.sql").read_text(encoding="utf-8"))
    connection.execute("INSERT INTO submissions(code,author,class_name,category,title,content,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                       ("GB261115001", "测试同学", "高一一班", "其他", "旧稿件", "旧数据应该完整保留", "2026-11-15T10:00:00+08:00", "2026-11-15T10:00:00+08:00"))
    connection.execute("INSERT INTO settings VALUES('admin_hash','test-hash')")
    connection.execute("INSERT INTO counters VALUES('2026-11-15',1)")
    connection.commit()
    output = directory / "export.json"
    assert exporter.export_database(legacy, output) == 1
    payload = json.loads(output.read_text(encoding="utf-8"))
    assert payload["submissions"][0]["content"] == "旧数据应该完整保留"
    assert payload["settings"][0]["value"] == "test-hash"
    assert "sessions" not in payload
    # 增量歌曲字段脚本验证 SQL 语法与旧行默认值。
    connection.executescript((ROOT / "src/main/resources/db/postgres/V2__song_fields.sql").read_text(encoding="utf-8"))
    assert connection.execute("SELECT song_name,artist,song_url FROM submissions").fetchone() == ("", "", "")
    connection.executescript((ROOT / "src/main/resources/db/sqlite/V1__initial_schema.sql").read_text(encoding="utf-8"))
    assert connection.execute("SELECT COUNT(*) FROM submissions").fetchone()[0] == 1
    connection.close()

original = ROOT / "data/broadcast.db"
if original.is_file():
    # 只读取真实旧库并在临时目录导出，绝不打印投稿内容及密码哈希。
    with tempfile.TemporaryDirectory(prefix="radio-legacy-") as directory:
        total = exporter.export_database(original, Path(directory) / "export.json")
        print(f"真实旧库只读导出验证通过：{total} 条投稿。")
print("SQL 增量字段、旧数据保留、只读导出验证通过。")
