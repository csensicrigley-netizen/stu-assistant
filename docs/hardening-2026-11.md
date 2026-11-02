# 站点加固记录（2026-11）

线上站点已按纵深防御完成多轮加固。本文是公开摘要；凭据、隧道、密钥路径等敏感细节只在私有维护手册（`docs/网站维护与加固-交接给agent.md`，不入公开仓）。

配套产物：

- `deploy/nginx/stu-headers.conf` —— 安全响应头 snippet
- `deploy/nginx/stu-limits.conf` —— 限流/连接数 zone
- `deploy/nginx/site.conf.example` —— 线上边缘配置模板（可直接对照部署）
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

## 2026-11-02 第二轮加固（对抗演练后）

一次完整的外部黑盒 + 内部白盒演练暴露了下列问题，均已修复并留下验证方法。

### 边缘（nginx / fail2ban）

1. **fail2ban 的两个 nginx jail 实际从未生效**
   - 现象：`fail2ban-client status nginx-limit-req` 显示 `No file is currently monitored`，`Total failed: 0`，而 `error.log` 里躺着数百条 `limiting requests`
   - 根因：`jail.d/defaults-debian.conf` 的 `backend = systemd` 覆盖了 `logpath`；nginx 只写文件、不写 journal，jail 去 journal 里永远捞不到
   - 修复：为 `nginx-limit-req`（logpath `error.log`）和 `nginx-444`（logpath `access.log`）显式设 `backend = auto`
   - 验证：连发 20 次 429 → `Total failed: 13` → 攻击 IP 被 DROP（后续请求直接超时）
2. TLS 全局 `ssl_protocols` 残留 `TLSv1 TLSv1.1`（vhost 已覆盖，但其他站点会继承）→ 收敛为 1.2/1.3
3. `X-Forwarded-For` 原用 `$proxy_add_x_forwarded_for`（追加，保留客户端伪造值）→ 改 `$remote_addr`（丢弃伪造），限速 key 恒为真实来源
4. 上游 `Server` 头透出 → `proxy_hide_header Server`

### 应用层（FastAPI）

5. **JWT 无法吊销**：新增 `users.token_version` + token 内 `ver` claim
   - 登出 `POST /v1/account/logout` 会 +1，旧 token 立即 `401 token_revoked`
   - 老 token 没有 `ver`（按 0 处理），与老用户默认值 0 对齐，**升级不踢已登录用户**
6. **绑定入口可被当作撞库代理**：`/v1/bind`、`/v1/relogin` 会拿任意学号密码去打学校系统
   - 新增单账号学号锁定：24h 内最多尝试 3 个不同学号（正常学生只绑自己那一个）
   - `relogin` 限速从「仅学号」改为「账号 + 学号 + IP」三重
7. 每日数据库冷备（`.backup`，WAL 安全）+ 7 天保留

### 仍存在的结构性风险（设计取舍，非 bug）

- 学生教务凭据集中托管：每租户独立 `master.key` + Fernet 密文，但密钥与密文同盘同用户，**主机被 root 拿下即全量泄露**。根治需密钥外置（KMS / 独立主机 / systemd credentials）
- `/v1/charging` 匿名可读属产品设计（公开充电桩看板），未加鉴权
- 注册无邮箱验证：受 nginx 与进程内双层限流约束，但仍是账号膨胀的入口