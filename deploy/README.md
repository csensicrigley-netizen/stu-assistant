# STU 事项助手 · 部署说明

一台服务器同时提供三样东西：后端接口、网页版（浏览器打开就能登录使用）、安卓 App 的下载。App 和网页版用同一个账号、同一份数据。

## 需要准备

- 一台 Linux 服务器：Ubuntu 24.04 或 Debian 12（自带 Python 3.11 以上），1 核 1 GB 内存起步。
- 一个域名，解析到这台服务器。
- 服务器 80 和 443 端口能从外网访问（Caddy 用它们自动申请 HTTPS 证书）。
- 服务器能连上学校系统。装完后的自检会逐项告诉你能不能连上。

## 安装

1. 在开发电脑上打包（把域名换成你的）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File deploy\make-release.ps1 -Server https://你的域名 -Online
   ```

   生成 `release\stu-assistant-server.tar.gz` 和 `release\STU事项助手-1.0.0.apk`。

2. 上传到服务器并安装：

   ```bash
   scp release/stu-assistant-server.tar.gz root@服务器:/root/
   ssh root@服务器
   tar -xzf stu-assistant-server.tar.gz
   sudo bash stu-assistant-server/deploy/install.sh 你的域名
   ```

   如果 80/443 端口已经被别的网站占用，或者你用自己的反向代理 / CDN，运行 `install.sh --no-proxy`，把 HTTPS 流量转发到 `127.0.0.1:8100` 即可。

   国内服务器：`sudo` 会清掉环境变量，PyPI 镜像要这样传进去：

   ```bash
   sudo env PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple bash stu-assistant-server/deploy/install.sh 你的域名
   ```

   Caddy 从 cloudsmith 的软件源安装，如果服务器连不上它，安装会停在 Caddy 这一步（后端服务已经装好并在运行）。这时改用 `--no-proxy`，用你自己的 nginx 配 HTTPS 转发。

3. 打开 `https://你的域名`，注册账号，连接校园账号。

安装脚本最后会运行自检。如果「学校统一认证」或「教务系统」一项失败，说明这台服务器连不上学校，网页能打开，但学生连接不了校园账号。海外服务器和部分云厂商的机房常见这种情况，需要换一台国内能直连学校的服务器。

## 更新

在开发电脑重新运行第 1 步，把新的压缩包上传，再执行同一条安装命令。用户数据和密钥会保留。

## 备份

| 位置 | 内容 | 丢了会怎样 |
|---|---|---|
| 服务器 `/var/lib/stu-assistant` | 用户账号、课表缓存、每个用户加密的学校会话 | 所有人要重新注册 |
| 服务器 `/etc/stu-assistant.env` | 登录签名密钥 | 所有人要重新登录 |
| 开发电脑 `android-signing\` | 安卓 App 的签名密钥和口令 | 以后发不了更新，学生只能卸载重装 |

`android-signing\` 里是私钥，不要上传到服务器，也不要发给别人。

## 常用命令

```bash
systemctl status stu-assistant          # 服务状态
journalctl -u stu-assistant -f          # 实时日志
# 重新自检（以 root 读取配置，再切到服务账号运行）
sudo bash -c 'cd /opt/stu-assistant && runuser -u stu-assistant -- env $(grep -v "^#" /etc/stu-assistant.env | xargs) venv/bin/python -m app.doctor'
```

## 需要知道的安全边界

- 学校密码只在连接那一刻交给学校统一认证，服务器不保存密码，只保存学校返回的登录会话（cookie），每个用户单独加密。
- 加密用的密钥也在这台服务器上。所以**服务器管理员在技术上能读取用户的学校会话**。只把这个服务提供给信任你的同学，并保护好服务器的 root 权限。
- 教务系统的登录回调目前要走学校的明文 HTTP 地址（stu-mcp 的兼容路线），这一段登录票据不加密。这是学校系统的现状，不是这个服务能单方面解决的。
- 用户在「我的 → 删除账号和数据」可以随时清空自己在服务器上的全部数据。
- 只读不代办：不提交作业、不签到、不缴费、不报修。公交只给线路和官方查询入口，不提供实时到站。
- 个人项目，与汕头大学官方无关。
