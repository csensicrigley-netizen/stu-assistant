# 站点加固记录（2026-11）

线上 `stu.oyishi.asia` 已按纵深防御完成两轮加固。本文是公开摘要；凭据、隧道、密钥路径等敏感细节只在私有维护手册（`docs/网站维护与加固-交接给agent.md`，不入公开仓）。

配套产物：

- `deploy/nginx/stu-headers.conf` —— 安全响应头 snippet
- `deploy/nginx/stu-limits.conf` —— 限流/连接数 zone
- `deploy/nginx/oyishi-icu.conf.example` —— 线上边缘配置模板（可直接对照部署）
- `deploy/stu-backup.sh` —— 每日备份脚本（目标主机写成 `entry-host.example` 占位，用前替换）

## 边缘（nginx）

- 安全响应头：HSTS / `X-Content-Type-Options: nosniff` / `X-Frame-Options: DENY` / Referrer-Policy / Permissions-Policy / CSP
  - **CSP `script-src 'self' + 'sha256-…'`**：`index.html` 的内联主题引导脚本按哈希放行。**改动该内联脚本必须重算哈希**（`stu-headers.conf` 里有注释指路），否则整站白屏。
- `server_tokens off`；80 强跳 443；HTTP/2
- TLS 仅 1.2/1.3（Mozilla intermediate 套件），session tickets off
- 限流三档：静态页 20r/s、业务 API 8r/s、注册/登录 10r/m（各带 burst）；登录类路径（auth / webvpn/login / med/login / relogin / bind）独立严档
- 每 IP 并发连接 `limit_conn 30`
- 慢速攻击（slowloris 类）超时收敛：header/body 12s、send 30s
- 扫描器黑洞：`wp-admin`、`.env`、`.git` 等常见探针路径直接 `return 444`（不回包）
- fail2ban：`sshd` + `nginx-limit-req`

## 后端主机

- ufw `default deny incoming`，仅放 22
- fail2ban：`sshd`（5 次/10 分钟封 1 小时，忽略入口机 IP）+ `recidive`（惯犯封 1 周）
- sshd：root 禁密码（prohibit-password）、`MaxAuthTries 3`、`AllowUsers` 白名单；上传通道账号 SFTP-only chroot
- 数据目录 700 专用系统用户；密钥文件 root 600
- systemd 已有 `NoNewPrivileges` / `ProtectSystem=strict`

## 数据

- 每日备份：SQLite 在线快照（`.backup` API，WAL 安全）+ **备份本体 `integrity_check`** + 租户目录打包，14 天保留，并异地同步到入口机（rsync 失败只记 syslog，不影响本地备份成功）
- journald `SystemMaxUse=100M`

## 变更纪律（踩过的坑）

- nginx 每次改前备份、改后 `nginx -t` 通过才 `reload`
- **嵌套 location 继承 `proxy_pass` 不可靠**（曾致 `/v1/auth/login` 被 nginx 404）——登录限流用顶层正则 location + 完整代理配置
- 带 `add_header` 的 location 会清空 server 级响应头，必须重新 include 安全头 snippet
- 上游依赖每月巡检：只升 patch、须过 pytest

## 遗留清单

- 无 CDN/WAF 缓冲层（DDoS 时无缓冲；需云账号层操作）
- 上传通道仍为密码 SFTP（建议换密钥）
- 单 worker 为刻意设计（SQLite + 租户锁），勿随意加
