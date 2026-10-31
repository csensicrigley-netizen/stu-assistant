#!/bin/bash
# 每日备份：SQLite 在线快照（WAL 安全）+ 租户目录 + 保留 14 天 + 异地到 oyishi
set -euo pipefail
DEST=/var/backups/stu-assistant
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$DEST"

# .backup 是 SQLite 官方在线备份，WAL 模式下也能拿到一致快照
sqlite3 /var/lib/stu-assistant/app.db ".backup $DEST/app-$STAMP.db" 2>/dev/null \
  || python3 - <<PY
import sqlite3
src = sqlite3.connect("/var/lib/stu-assistant/app.db")
dst = sqlite3.connect("$DEST/app-$STAMP.db")
src.backup(dst)
dst.close(); src.close()
PY

# 备份本体完整性校验：坏了当场失败（timer 标红，巡检会推警），不再带病压缩
python3 - "$DEST/app-$STAMP.db" <<'PY'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1])
ok = con.execute("PRAGMA integrity_check").fetchone()[0]
tables = len(list(con.execute("select name from sqlite_master where type='table'")))
con.close()
if ok != "ok" or tables < 1:
    raise SystemExit(f"backup integrity failed: {ok} tables={tables}")
print(f"integrity: {ok} tables: {tables}")
PY

gzip -f "$DEST/app-$STAMP.db"
tar -C /var/lib/stu-assistant -czf "$DEST/tenants-$STAMP.tar.gz" tenants
chmod 600 "$DEST"/*

# 只留最近 14 天
find "$DEST" -name "app-*.db.gz" -mtime +14 -delete
find "$DEST" -name "tenants-*.tar.gz" -mtime +14 -delete

# 异地一份到入口机 oyishi（失败只记 syslog，不否定本地备份的成功）
rsync -az --delete \
  -e "ssh -i /root/.ssh/backup_stu -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20" \
  /var/backups/stu-assistant/ root@entry-host.example:/var/backups/stu-offsite/ \
  || logger -t stu-backup "offsite rsync FAILED（本地备份仍有效）"

echo "$(date -Is) backup ok: $(ls -1 $DEST | wc -l) files"
