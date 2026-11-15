# -*- coding: utf-8 -*-
"""只读导出旧 SQLite 数据，供 Kotlin 导入命令迁移到 PostgreSQL。"""
import argparse
import json
import sqlite3
from pathlib import Path


def export_database(source: Path, destination: Path) -> int:
    if not source.is_file():
        raise FileNotFoundError(f"没有找到旧数据库：{source}")
    # mode=ro 不会新建或修改旧数据库，连接读取也包含 WAL 中已提交的数据。
    connection = sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        with connection:
            connection.execute("BEGIN")
            submissions = [dict(row) for row in connection.execute("SELECT * FROM submissions ORDER BY id")]
            counters = [dict(row) for row in connection.execute("SELECT day,n FROM counters")]
            settings = [dict(row) for row in connection.execute(
                "SELECT key,value FROM settings WHERE key IN ('admin_hash','admin_password_initial')")]
    finally:
        connection.close()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps({"format": "radio-submissions-legacy-v1", "submissions": submissions,
                                      "counters": counters, "settings": settings}, ensure_ascii=False, indent=2), encoding="utf-8")
    return len(submissions)


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="导出旧投稿数据，原数据库保持不变")
    parser.add_argument("--database", type=Path, default=root / "data" / "broadcast.db")
    parser.add_argument("--output", type=Path, default=root / "data" / "legacy-export.json")
    args = parser.parse_args()
    count = export_database(args.database, args.output)
    print(f"已导出 {count} 条投稿到 {args.output}；文件包含管理员密码哈希，请妥善保管。")
