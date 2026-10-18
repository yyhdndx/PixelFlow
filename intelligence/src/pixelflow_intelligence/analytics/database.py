"""MySQL driver; SQLite remains available for offline tests and legacy import.

The small adapter accepts the Store's static qmark SQL and SQLite-style rows.
Do not pass arbitrary SQL containing literal question marks through this adapter.
"""
import sqlite3
from urllib.parse import urlsplit, unquote
import pymysql

DATABASE_ERRORS = (sqlite3.Error, pymysql.Error)


class Row(dict):
    def __getitem__(self, key):
        return tuple(self.values())[key] if isinstance(key, int) else super().__getitem__(key)


class RowCursor(pymysql.cursors.DictCursor):
    dict_type = Row


class MySQLConnection:
    def __init__(self, dsn):
        url = urlsplit(dsn)
        if (url.scheme != "mysql" or not url.hostname or not url.username
                or not url.path.strip("/") or url.query or url.fragment):
            raise ValueError("Expected mysql://user:password@host:port/database without query or fragment")
        self.connection = pymysql.connect(
            host=url.hostname, port=url.port or 3306, user=unquote(url.username),
            password=unquote(url.password or ""), database=unquote(url.path[1:]),
            charset="utf8mb4", cursorclass=RowCursor, autocommit=False,
            connect_timeout=10, read_timeout=60, write_timeout=60,
            init_command="SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED")

    def execute(self, sql, parameters=()):
        cursor = self.connection.cursor()
        cursor.execute(sql.replace("?", "%s"), parameters)
        return cursor

    def executescript(self, sql):
        for statement in sql.split(";"):
            if statement.strip():
                self.execute(statement)

    def commit(self):
        self.connection.commit()

    def rollback(self):
        self.connection.rollback()

    def close(self):
        self.connection.close()

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        if kind is None:
            self.commit()
        else:
            self.rollback()
