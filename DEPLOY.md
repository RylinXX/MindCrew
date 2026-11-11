# MindCrew · Docker 全栈部署文档

> 把 Spring Boot 后端 + Vue 前端 + 全部中间件（MySQL / Redis / Milvus / MinIO / etcd）
> 一键拉起。本机开发、内网部署、单机生产都可以用同一份 `docker-compose.yml`。

---

## 1. 一图看懂

```
                ┌────────────────────────────────────────────┐
   浏览器 ──→   │ frontend  (nginx:80)                       │
                │   ├── /          → Vue 静态资源            │
                │   ├── /api/*     → 反代 backend:8080       │
                │   └── /uploads/* → 反代 backend:8080       │
                └─────────────────────┬──────────────────────┘
                                      │
                ┌─────────────────────▼──────────────────────┐
                │ backend  (Spring Boot:8080)                │
                │   ├── MySQL    : mysql:3306/docmind        │
                │   ├── Redis    : redis:6379                │
                │   ├── Milvus   : milvus:19530              │
                │   ├── MinIO    : minio:9000 (bucket=mindcrew)
                │   └── LLM      : 阿里云百炼 (HTTPS 出网)   │
                └────────────────────────────────────────────┘

   宿主机统一暴露端口（默认值，可通过 .env 改）：
     80   → 前端 UI
     8080 → 后端 API（如不需要可注释掉，只走 nginx）
     3306 → MySQL  （仅开发期建议开放）
     6379 → Redis  （仅开发期建议开放）
     19530→ Milvus
     9000 → MinIO API
     9001 → MinIO 控制台 http://localhost:9001
```

---

## 2. 前置环境

| 软件 | 最低版本 | 备注 |
|---|---|---|
| Docker Engine | 20.10+ | macOS / Windows 装 Docker Desktop |
| docker compose | v2 (内置 `docker compose` 子命令) | 不要用老版 `docker-compose` |
| 可用内存 | ≥ 8 GB | Milvus + JVM + LibreOffice 比较吃内存 |
| 磁盘 | ≥ 20 GB | 镜像约 4 GB，数据卷增长视使用而定 |

> 校验：`docker version`、`docker compose version` 都能正常输出即可。

---

## 3. 部署步骤

### 3.1 准备代码

```bash
# 进入项目根（与 src/、pom.xml 同级）
cd /path/to/MindCrew

# 必备文件清单（应该都已在仓库里）：
#   ├── Dockerfile                  ← 后端镜像
#   ├── .dockerignore
#   ├── docker-compose.yml
#   ├── .env.docker.example
#   ├── docker/mysql/init.sh        ← MySQL 首次启动初始化脚本
#   ├── sql/*.sql                   ← 业务库 schema
#   ├── pom.xml + src/              ← 后端源码
#   └── MindCrew-frontend/
#         ├── Dockerfile            ← 前端镜像
#         ├── nginx.conf
#         └── ...
```

### 3.2 写 `.env`

```bash
cp .env.docker.example .env
vim .env
```

**必须改的**：
- `BAILIAN_API_KEY` —— 阿里云百炼，[申请入口](https://bailian.console.aliyun.com/?apiKey=1)

**生产建议改的**：
- `MYSQL_ROOT_PASSWORD` —— 别留默认 `root`
- `REDIS_PASSWORD` —— 开发可以空，生产填强密码
- `MINIO_ROOT_PASSWORD` —— 默认 `minioadmin` 不安全
- `CRYPTO_MASTER_KEY` —— 32 字符以上随机串：
  ```bash
  openssl rand -base64 48
  ```
- `JWT_SECRET` —— 同上，要够长够随机
- 如端口被占，按需改 `FRONTEND_PORT` / `BACKEND_PORT` 等

### 3.3 一键启动

```bash
docker compose up -d --build
```

首次启动会做的事（按依赖顺序）：

1. **构建镜像**（约 5~15 分钟，取决于网络）
   - 后端：Maven 多阶段构建 → Eclipse Temurin 17 JRE + LibreOffice + FFmpeg
   - 前端：Node 20 构建 Vue → Nginx Alpine
2. **拉起中间件**：MySQL、Redis、MinIO、etcd、Milvus
3. **MySQL 初始化**：`docker/mysql/init.sh` 自动按文档顺序导入 `sql/` 下全部脚本
   （客户端强制 `--default-character-set=utf8mb4`，避免中文种子数据乱码；见 6.8）
4. **MinIO 桶初始化**：`minio-init` 一次性服务创建 `mindcrew` 桶并设为公共读
5. **后端启动**：所有依赖 `healthy` 之后才会启动，避免连接失败
6. **前端启动**：依赖后端，Nginx 反代到 `backend:8080`

### 3.4 看日志，确认启动

```bash
# 看整体状态
docker compose ps

# 期望看到所有服务 STATUS = Up (healthy)；minio-init 是 Exited (0)（正常）
# NAME                    STATUS
# mindcrew-frontend       Up
# mindcrew-backend        Up (healthy)
# mindcrew-milvus         Up (healthy)
# mindcrew-mysql          Up (healthy)
# mindcrew-redis          Up (healthy)
# mindcrew-minio          Up (healthy)
# mindcrew-etcd           Up (healthy)
# mindcrew-minio-init     Exited (0)

# 跟后端启动日志
docker compose logs -f backend

# 直到看到这类输出就稳了：
#   Tomcat started on port 8080 (http)
#   Started MindCrewApplication in xx.xxx seconds
```

### 3.5 验证

| 入口 | URL |
|---|---|
| 前端 UI | http://localhost |
| 后端 API（健康） | http://localhost:8080/ |
| MinIO 控制台 | http://localhost:9001 （用户名/密码 = `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`）|
| Milvus 健康检查 | http://localhost:9091/healthz |

如果业务库要登录，看 `sql/docmind-init.sql` 里的种子用户。

---

## 4. 常用运维命令

```bash
# 停服务、保留数据
docker compose down

# 停服务并清空所有数据卷（⚠️ 库、向量、对象、上传全没）
docker compose down -v

# 重启单个服务
docker compose restart backend

# 只重构后端（前端没改的话）
docker compose up -d --build backend

# 跟某个服务的日志
docker compose logs -f --tail=200 backend

# 进入容器排查
docker compose exec backend bash
docker compose exec mysql mysql -uroot -p docmind

# 看磁盘占用
docker system df -v
```

### 4.1 数据卷位置

所有持久化数据放在 Docker 管理的命名卷里：

> 卷名带**双 `mindcrew_` 前缀**：`docker-compose.yml` 里 `name: mindcrew`（项目名）+ 卷声明 `mindcrew_xxx`，
> Docker 实际命名为 `<项目名>_<卷声明>`。`docker volume rm` 时务必用下表的完整名。

| 卷名 | 用途 |
|---|---|
| `mindcrew_mindcrew_mysql_data` | MySQL 数据文件 |
| `mindcrew_mindcrew_redis_data` | Redis RDB 持久化 |
| `mindcrew_mindcrew_minio_data` | MinIO 对象存储 |
| `mindcrew_mindcrew_etcd_data` | etcd 元数据 |
| `mindcrew_mindcrew_milvus_data` | Milvus 向量索引 |
| `mindcrew_mindcrew_backend_uploads` | 后端 `/app/uploads` 上传目录（头像等） |
| `mindcrew_mindcrew_backend_logs` | 后端日志 |

查看：

```bash
docker volume ls | grep mindcrew
docker volume inspect mindcrew_mindcrew_mysql_data
```

备份示例（MySQL）：

```bash
docker compose exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" docmind' \
  > backup-$(date +%F).sql
```

---

## 5. 切换到阿里云 OSS（可选）

如果不想本地存 MinIO，要走阿里云 OSS：

1. `.env` 里把 `STORAGE_TYPE=oss`
2. 填齐 `OSS_ENDPOINT` / `OSS_REGION` / `OSS_BUCKET` / `OSS_ACCESS_KEY` / `OSS_SECRET_KEY`
3. `docker compose up -d backend` 重启后端

> 注意：Milvus 仍然依赖 MinIO 容器（它用 MinIO 存索引文件），所以 `minio` 服务别停。

---

## 6. 排错速查

### 6.1 后端启动报 `Communications link failure`
MySQL 还没好就启动了。看 `docker compose ps` 里 mysql 的 STATUS，等到 `(healthy)` 再起 backend：

```bash
docker compose restart backend
```

### 6.2 后端 `Failed to connect to Milvus`
Milvus 首次启动需要 1~2 分钟（要先连 etcd + MinIO）。耐心等，或：

```bash
docker compose logs milvus | tail -50
```

看到 `Milvus Proxy successfully started` 就 OK 了。

### 6.3 上传 PDF / Office 没反应
- LibreOffice 在镜像里路径是 `/usr/bin/soffice`（已经通过环境变量 `OFFICE_SOFFICE_PATH` 注入）
- FFmpeg 路径 `/usr/bin/ffmpeg`
- 验证：
  ```bash
  docker compose exec backend soffice --version
  docker compose exec backend ffmpeg -version
  ```

### 6.4 前端报 `502 Bad Gateway`
说明 nginx 找不到 `backend`。两种可能：
- backend 容器没起来 → `docker compose logs backend`
- backend 还没准备好（健康检查没通过）→ 等一会儿再刷新

### 6.5 端口已被占用
改 `.env` 里对应的 `*_PORT`，重新 `docker compose up -d`。

### 6.6 SQL 没初始化 / 表建不全
**只有数据卷为空时**，MySQL 才会跑 `docker-entrypoint-initdb.d/`。如果之前启动过、库已存在但 schema 不对（典型现象：后端报 `Table 'docmind.xxx' doesn't exist`），需要清空数据卷重新初始化：

```bash
docker compose rm -sf mysql backend
docker volume rm mindcrew_mindcrew_mysql_data    # ⚠️ 完整双前缀卷名
docker compose up -d
```

⚠️ 这会清空业务库，确认你接受。

> **排查建表失败**：`init.sh` 用了 `set -euo pipefail`，任何一个 SQL 文件报错都会中断后续导入；
> 而 MySQL 配了 `restart: unless-stopped`，首次 init 失败后容器会自动重启并跳过 init，
> **表面 healthy 但表残缺**。所以建表出问题时，先看首次 init 日志定位是哪个文件挂了：
> ```bash
> docker logs mindcrew-mysql 2>&1 | grep -iE 'mindcrew-init|ERROR'
> ```
> 它会打印 `[mindcrew-init] -> 文件名` 一路到底，停在哪个文件就是哪个文件的 SQL 有问题
> （常见：跨文件列依赖、`create database` 没加 `IF NOT EXISTS` 与 `MYSQL_DATABASE` 冲突）。

### 6.7 ASR / 视觉识别失败
阿里云百炼 ASR 走异步 API，会**回拉**音频 URL。如果 MinIO 只在内网，DashScope 拿不到，需要：
- 内网部署：忽略此功能，或换通义 OSS
- 公网部署：MinIO 9000 端口必须公网可达，且 `application.yml` 里把 `minio.endpoint` 指向公网域名

### 6.8 页面中文乱码（如 `ä¸­ç«`）
现象：Soul 人格、大模型 Provider 等页面的中文显示成 `ä¸­ç«` 这类乱码，但知识库种子数据正常。

原因：**导入时客户端字符集不对**。`init.sh` 导入 SQL 文件时若不指定 `--default-character-set=utf8mb4`，
容器里 mysql 客户端会按 latin1 读取 UTF-8 文件，把中文双重编码存进库（后端 JDBC 是 UTF-8，读没问题，是数据本身已坏）。
带 `SET NAMES utf8mb4` 的文件（如 `docmind-init.sql`）不受影响，所以只有部分页面乱码。

修复：`init.sh` 已统一加上 `--default-character-set=utf8mb4`。但**库里已存的乱码不会自动修复**，
必须清空 MySQL 数据卷重新导入：

```bash
docker compose rm -sf mysql backend
docker volume rm mindcrew_mindcrew_mysql_data
docker compose up -d
```

验证（应输出正常中文，而非 `ä¸­ç«`）：
```bash
docker exec mindcrew-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" \
  docmind -e "SELECT name FROM system_persona;"
```

---

## 7. 生产环境 checklist

部署到正式机器前过一遍：

- [ ] `.env` 里所有 `please-change-me` / 默认密码都换了
- [ ] `CRYPTO_MASTER_KEY` / `JWT_SECRET` 用 `openssl rand` 重新生成
- [ ] `docker-compose.yml` 里 MySQL / Redis 的宿主机端口映射注释掉（只走内网）
- [ ] HTTPS：在前面再加一层 Nginx / Caddy 终结 TLS（参考 [Caddy 自动证书](https://caddyserver.com/)）
- [ ] 日志：`mindcrew_backend_logs` 接入 ELK / Loki，或定期清理
- [ ] 备份：MySQL `mysqldump`、MinIO `mc mirror`、Milvus 用官方 `birdwatcher` 工具，每天跑一次
- [ ] 监控：`docker stats` 或部署 cAdvisor + Prometheus
- [ ] JVM：根据物理内存把 `JAVA_OPTS` 的 `-Xmx` 调到合理值（一般是机器内存的 50~60%）

---

## 8. 文件清单（新增/修改）

本次 Docker 化新增/修改的文件，全部在项目根：

```
.
├── Dockerfile                       (新增) 后端镜像
├── .dockerignore                    (新增)
├── docker-compose.yml               (新增) 一键启动
├── .env.docker.example              (新增) 环境变量模板
├── DEPLOY.md                        (新增) 本文档
├── docker/
│   └── mysql/
│       └── init.sh                  (新增) MySQL 首次启动按顺序导入 sql/（utf8mb4 客户端）
└── MindCrew-frontend/
    ├── Dockerfile                   (修改) npm ci + healthcheck
    ├── nginx.conf                   (修改) 修 WS Connection 头 + 补 /uploads/
    └── .dockerignore                (新增)
```

应用代码（Java、Vue）零改动，全部通过环境变量覆盖 `application.yml`。
