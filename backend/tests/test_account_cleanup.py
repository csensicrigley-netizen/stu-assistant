import sqlite3

import pytest

from app.db import DB


def seed(db, email):
    uid = db.create_user(email, "unused")["id"]
    db.upsert_items(uid, "jw", "task", [{"external_id": "task", "title": "test"}])
    db.add_device(uid, "android", "test-device")
    db.med_session_save(uid, username="test", name="test", token="test-token")
    db.grant_consent(uid)
    db.tt_manual_save(uid, "2026-2027-1", {})
    db.mark_bound(uid, "jw", "test-hash")
    db.finish_run(db.start_run(uid, "jw"), 0, "test")
    with db.conn() as c:
        c.execute("INSERT INTO plans(id,user_id,date,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                  (uid, uid, "2026-10-30", "test", 1, 1))
        c.execute("INSERT INTO push_log(user_id,kind,payload,sent_at,ok) VALUES(?,?,?,?,?)",
                  (uid, "test", "{}", 1, 1))
    return uid


def counts(db, uid):
    # Discover user-owned tables so a newly added table cannot silently escape.
    with db.conn() as c:
        tables = [row[0] for row in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        return {table: c.execute(f'SELECT COUNT(*) FROM "{table}" WHERE user_id=?', (uid,)).fetchone()[0]
                for table in tables if "user_id" in {col[1] for col in c.execute(f'PRAGMA table_info("{table}")')}}


def test_delete_cleans_every_user_table_and_preserves_other_accounts(tmp_path):
    db = DB(tmp_path / "app.db")
    removed = seed(db, "removed@example.invalid")
    kept = seed(db, "kept@example.invalid")
    assert len(counts(db, removed)) == 9
    db.delete_user(removed)
    assert db.user_by_id(removed) is None
    assert all(n == 0 for n in counts(db, removed).values())
    assert all(n == 1 for n in counts(db, kept).values())
    with db.conn() as c:
        assert c.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        assert c.execute("PRAGMA foreign_key_check").fetchall() == []
    db.delete_user(removed)  # repeated deletion is harmless


def test_delete_rolls_back_children_when_user_delete_fails(tmp_path):
    db = DB(tmp_path / "app.db")
    uid = seed(db, "kept@example.invalid")
    with db.conn() as c:
        c.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON users "
                  "BEGIN SELECT RAISE(ABORT, 'test failure'); END")
    with pytest.raises(sqlite3.IntegrityError):
        db.delete_user(uid)
    assert db.user_by_id(uid) is not None
    assert all(n == 1 for n in counts(db, uid).values())


def test_upgrade_cleans_legacy_orphans_without_deleting_valid_data(tmp_path):
    path = tmp_path / "legacy.db"
    db = DB(path)
    orphan = seed(db, "orphan@example.invalid")
    kept = seed(db, "kept@example.invalid")
    # Emulate the old release: connection-local foreign keys were disabled.
    with sqlite3.connect(path) as c:
        c.execute("DELETE FROM users WHERE id=?", (orphan,))
        c.execute("PRAGMA user_version=0")
    assert all(n == 1 for n in counts(db, orphan).values())
    for _ in range(2):
        upgraded = DB(path)
        assert all(n == 0 for n in counts(upgraded, orphan).values())
        assert all(n == 1 for n in counts(upgraded, kept).values())
        with upgraded.conn() as c:
            assert c.execute("PRAGMA user_version").fetchone()[0] == 1
