#!/usr/bin/env bash
# Install or update STU 事项助手 on a Debian 12+ / Ubuntu 24.04+ server
# (Python 3.11+ is required by stu-mcp; Ubuntu 22.04 ships 3.10).
#
# Mainland servers: sudo drops your environment, so pass a PyPI mirror explicitly:
#   sudo env PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple bash deploy/install.sh your.domain
#
#   sudo bash deploy/install.sh your.domain     # with Caddy: automatic HTTPS on 80/443
#   sudo bash deploy/install.sh --no-proxy      # service only on 127.0.0.1:8100,
#                                               # put your own HTTPS proxy in front
#
# Run it again to update: code and the website are replaced, the data in
# /var/lib/stu-assistant and the secret in /etc/stu-assistant.env are kept.
set -euo pipefail

ARG="${1:-}"
if [[ -z "$ARG" ]]; then
  echo "用法: sudo bash deploy/install.sh 你的域名   或   sudo bash deploy/install.sh --no-proxy" >&2
  exit 2
fi
[[ $EUID -eq 0 ]] || { echo "请用 sudo 运行" >&2; exit 1; }

BUNDLE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP=/opt/stu-assistant
DATA=/var/lib/stu-assistant
ENVF=/etc/stu-assistant.env
SVC=stu-assistant
WHEEL="stu_mcp-0.7.3-py3-none-any.whl"

say() { printf '\n== %s\n' "$*"; }

say "检查安装包"
for f in backend/app/main.py backend/requirements.txt backend/constraints.txt "backend/wheels/$WHEEL" web/index.html deploy/stu-assistant.service; do
  [[ -e "$BUNDLE/$f" ]] || { echo "安装包不完整，缺少 $f" >&2; exit 1; }
done
(cd "$BUNDLE/backend/wheels" && sha256sum -c SHA256SUMS)

say "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 python3-venv ca-certificates curl
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' \
  || { echo "需要 Python 3.11 以上（Ubuntu 24.04、Debian 12 自带）" >&2; exit 1; }

say "账号与目录"
id "$SVC" >/dev/null 2>&1 || useradd --system --home-dir "$DATA" --no-create-home --shell /usr/sbin/nologin "$SVC"
install -d -m 0755 "$APP"
install -d -m 0700 -o "$SVC" -g "$SVC" "$DATA"

say "复制程序与网页版"
rm -rf "$APP/app.new" "$APP/web.new"
cp -a "$BUNDLE/backend/app" "$APP/app.new"
cp -a "$BUNDLE/web" "$APP/web.new"
find "$APP/app.new" -name '__pycache__' -prune -exec rm -rf {} +
install -m 0644 "$BUNDLE/backend/requirements.txt" "$BUNDLE/backend/constraints.txt" "$APP/"
install -d -m 0755 "$APP/wheels"
install -m 0644 "$BUNDLE/backend/wheels/$WHEEL" "$APP/wheels/"

say "Python 环境"
[[ -x "$APP/venv/bin/python" ]] || python3 -m venv "$APP/venv"
"$APP/venv/bin/pip" install --disable-pip-version-check -q --upgrade pip
"$APP/venv/bin/pip" install --disable-pip-version-check -q \
  -c "$APP/constraints.txt" -r "$APP/requirements.txt" "$APP/wheels/$WHEEL"

# swap in the new code only after the environment installed cleanly
rm -rf "$APP/app.old" "$APP/web.old"
[[ -d "$APP/app" ]] && mv "$APP/app" "$APP/app.old"
[[ -d "$APP/web" ]] && mv "$APP/web" "$APP/web.old"
mv "$APP/app.new" "$APP/app"
mv "$APP/web.new" "$APP/web"
rm -rf "$APP/app.old" "$APP/web.old"
chown -R root:root "$APP"
chmod -R a+rX "$APP"

say "配置（只在第一次生成密钥）"
if [[ ! -f "$ENVF" ]]; then
  umask 077
  SECRET="$("$APP/venv/bin/python" -c 'import secrets; print(secrets.token_urlsafe(48))')"
  cat > "$ENVF" <<EOF
STU_APP_HOME=$DATA
STU_APP_WEB_DIR=$APP/web
STU_APP_JWT_SECRET=$SECRET
EOF
  chown root:root "$ENVF"
  chmod 0600 "$ENVF"
  echo "已生成 $ENVF"
else
  echo "保留已有的 $ENVF"
fi

say "系统服务"
install -m 0644 "$BUNDLE/deploy/stu-assistant.service" /etc/systemd/system/stu-assistant.service
systemctl daemon-reload
systemctl enable "$SVC" >/dev/null
systemctl restart "$SVC"
for _ in $(seq 1 30); do
  curl -fsS http://127.0.0.1:8100/v1/health >/dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS http://127.0.0.1:8100/v1/health >/dev/null || { journalctl -u "$SVC" -n 40 --no-pager; exit 1; }
echo "服务已运行：http://127.0.0.1:8100"

if [[ "$ARG" != "--no-proxy" ]]; then
  DOMAIN="$ARG"
  say "Caddy（自动申请 HTTPS 证书，需要域名已解析到本机、80 和 443 端口可访问）"
  if ! command -v caddy >/dev/null 2>&1; then
    apt-get install -y debian-keyring debian-archive-keyring apt-transport-https gnupg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
      | gpg --batch --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
      > /etc/apt/sources.list.d/caddy-stable.list
    chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -y
    apt-get install -y caddy
  fi
  cat > /etc/caddy/stu-assistant.caddy <<EOF
$DOMAIN {
	encode zstd gzip
	header Strict-Transport-Security "max-age=31536000"
	reverse_proxy 127.0.0.1:8100
}
EOF
  grep -q 'import /etc/caddy/stu-assistant.caddy' /etc/caddy/Caddyfile 2>/dev/null \
    || printf '\nimport /etc/caddy/stu-assistant.caddy\n' >> /etc/caddy/Caddyfile
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  systemctl reload caddy || systemctl restart caddy
  echo "网页版：https://$DOMAIN"
fi

say "自检"
# runuser ships with util-linux; minimal images often have no sudo
( cd "$APP" && runuser -u "$SVC" -- env $(grep -v '^#' "$ENVF" | xargs) "$APP/venv/bin/python" -m app.doctor ) || true

say "完成"
echo "查看日志：journalctl -u $SVC -f"
