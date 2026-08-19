# 自托管快速部署（5 分钟）

从 GitHub Release 直接下载预构建产物，无需源码编译即可部署完整的 Maodouchat 服务栈
（Caddy 自动 HTTPS + PostgreSQL 16 + Maodouchat Server）。

## 前置要求

- 一台有公网 IP 的 Linux 服务器（2GB 内存以上）
- 一个已解析到该服务器的域名（如 `chat.example.com`），用于自动签发 HTTPS 证书
- 已安装 Docker 与 Docker Compose v2

## 一、下载 Release 产物

在 GitHub Release 页面下载两个文件：

| 文件 | 说明 |
| --- | --- |
| `maodouchat-server-<版本>.tar.gz` | 服务端运行包（预构建，含启动脚本） |
| `maodouchat-selfhost-<版本>.tar.gz` | 自托管套件（compose / Caddyfile / 环境变量模板） |

对照 `SHA256SUMS.txt` 校验：

```bash
sha256sum -c SHA256SUMS.txt
```

## 二、解压并准备目录

```bash
mkdir maodouchat && cd maodouchat
tar -xzf maodouchat-selfhost-<版本>.tar.gz          # 得到 docker-compose.yml / Caddyfile / .env.example
tar -xzf maodouchat-server-<版本>.tar.gz            # 得到 maodouchat-server/ 运行包
chmod +x maodouchat-server/bin/maodouchat-server
cp .env.example .env
```

## 三、填写 `.env`

最少需要改这几项：

```ini
PUBLIC_HOST=chat.example.com            # 你的域名
ACME_EMAIL=you@example.com              # 证书通知邮箱
BASE_URL=https://chat.example.com
ADMIN_PATH=my-admin                     # 管理后台路径（随机字符串，勿用 admin）
JWT_SECRET=$(openssl rand -base64 48 的输出)
PUSH_HMAC_SECRET=$(openssl rand -base64 48 的输出)
POSTGRES_PASSWORD=$(长随机密码)
RELAXED_VERIFICATION=true               # 无私有部署无 SMTP/TURN 时开启（验证码打印到日志）
BOOTSTRAP_FIRST_USER_AS_ADMIN=true      # 第一个注册用户自动成为管理员（一次性）
# 第三方服务器品牌信息（App 内「第三方服务器模式」会展示）
SERVER_NAME=我的毛豆服务器
SERVER_DESCRIPTION=朋友间私聊小站
```

## 四、启动

```bash
docker compose up -d
docker compose ps          # 等待 server/proxy 变为 healthy
curl https://chat.example.com/health/ready
```

看到 `{"status":"ready",...}` 即部署成功。随后注册第一个账号（自动成为管理员），
把 `.env` 中 `BOOTSTRAP_FIRST_USER_AS_ADMIN` 改回 `false` 并 `docker compose up -d` 重启。

## 五、手机 App 连接

1. 安装 Release 中的通用 APK（任意服务器均可使用同一个包）
2. 打开 App：**设置 → 服务器**，填入 `https://chat.example.com`
3. 点击「测试连接」成功后保存，App 进入**第三方服务器模式**
   （会话列表与服务器设置页会展示服务器名称与运营方公告）

## 第三方服务器玩法

- **品牌化**：`SERVER_NAME` / `SERVER_DESCRIPTION` / `SERVER_CONTACT_URL` 让客户端展示你的服务器身份
- **运营公告**：写入 `SERVER_ANNOUNCEMENT` 环境变量，或把内容放进存储目录的
  `server-announcement.txt`（免重启即时生效），所有连接此服务器的用户可见
- **邀请制小站**：`ALLOW_REGISTRATION=false` 关闭公开注册
- **AI 能力**：配置 `OPENAI_API_KEY` / `OPENAI_BASE_URL`（兼容任意 OpenAI 协议中转站）
  即可开启翻译、总结、语义搜索等 AI 功能
- **管理后台**：`https://chat.example.com/<ADMIN_PATH>/` 内容审核、风控、审计导出
- **机器人平台**：开发者账号可通过 Bot API 接入自定义机器人

## 升级

下载新版运行包替换 `maodouchat-server/` 目录后 `docker compose up -d` 即可；
数据库结构由服务端启动时自动迁移。升级前建议备份 PostgreSQL 数据卷与 uploads 目录。

## 不用 Docker 的裸机部署

```bash
tar -xzf maodouchat-server-<版本>.tar.gz
cd maodouchat-server
export DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/maodouchat?user=...&password=...'
export DATABASE_DRIVER=org.postgresql.Driver
export JWT_SECRET='<至少32位随机字符>'
export BASE_URL='https://chat.example.com'
export STORAGE_DIR=$PWD/uploads
./bin/maodouchat-server
```

再用任意反代（Caddy/Nginx）把 443 转到 8080。完整说明见 `docs/docker-deployment.md`。
