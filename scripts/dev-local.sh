#!/usr/bin/env bash
# MindCrew 本地一键运行脚本（无 Docker 环境）
# 依赖：临时 JDK17、Maven（工作区 环境/ 目录）、本地编译好的 MySQL/Redis、Node/pnpm
# 用法：bash scripts/dev-local.sh start|stop|status
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
RT="/tmp/mindcrew-runtime"
JAVA_HOME="${JAVA_HOME:-$(ls -d /tmp/jdk17/*/zulu-*.jdk/Contents/Home 2>/dev/null | head -1)}"
MVN="sh /Users/mac/Downloads/mindcrew-xianyu-v1.0/环境/apache-maven-3.6.1/bin/mvn"
MYSQL_HOME="$RT/mysql-8.4.3-macos14-arm64"
MYSQL="$MYSQL_HOME/bin/mysql"
PORT_DB=3306; PORT_REDIS=6379; PORT_MOCK=18090; PORT_API=18080; PORT_WEB=5280

is_up() { (echo > "/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

start_db() {
  if [ ! -d "$RT/mysql-data" ]; then
    echo "[db] 初始化 MySQL 数据目录..."
    "$MYSQL_HOME/bin/mysqld" --initialize-insecure --basedir="$MYSQL_HOME" \
      --datadir="$RT/mysql-data" --log-error="$RT/logs/mysql-init.log" || return 1
    : > "$RT/logs/mysql-init.done"
  fi
  if ! is_up $PORT_DB; then
    echo "[db] 启动 MySQL..."
    nohup "$MYSQL_HOME/bin/mysqld" --basedir="$MYSQL_HOME" --datadir="$RT/mysql-data" \
      --port=$PORT_DB --bind-address=127.0.0.1 --socket="$RT/mysql.sock" \
      --pid-file="$RT/mysql.pid" --log-error="$RT/logs/mysql.log" --mysqlx=OFF \
      > "$RT/logs/mysqld.out" 2>&1 &
    sleep 6
  fi
  if [ ! -f "$RT/logs/schema-imported" ]; then
    echo "[db] 初始化账号与 schema..."
    "$MYSQL" -uroot --socket="$RT/mysql.sock" -e \
      "ALTER USER 'root'@'localhost' IDENTIFIED BY 'root';
       CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY 'root';
       GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION; FLUSH PRIVILEGES;" || return 1
    for f in docmind-init usage-stats-schema retrieval-log-migration dept-position-acl-schema \
             kb-acl-department-migration kb-category-schema agent-crew-schema agent-crew-fork-migration \
             persona-schema llm-provider-schema api-key-schema audit-pii-schema feedback-golden-schema \
             coach-schema coach-source-quote-migration conversation-flag-migration voice-persona-schema cs-center-schema; do
      if [ "$f" = "docmind-init" ]; then
        "$MYSQL" -uroot -proot -h127.0.0.1 < "$PROJ/sql/$f.sql" || echo "[db] $f 导入失败"
      else
        "$MYSQL" -uroot -proot -h127.0.0.1 docmind < "$PROJ/sql/$f.sql" || echo "[db] $f 导入失败"
      fi
    done
    touch "$RT/logs/schema-imported"
  fi
}

start_redis() {
  if is_up $PORT_REDIS; then return 0; fi
  echo "[redis] 启动..."
  nohup "$RT/redis-7.2.5/src/redis-server" --port $PORT_REDIS --bind 127.0.0.1 \
    --save "" --appendonly no > "$RT/logs/redis.log" 2>&1 &
  sleep 2
}

start_mock() {
  if is_up $PORT_MOCK; then return 0; fi
  echo "[llm] 启动本地 Mock LLM（无真实 Key 时使用）..."
  nohup python3 "$PROJ/scripts/mock_llm.py" > "$RT/logs/mock-llm.log" 2>&1 &
  sleep 1
}

start_api() {
  if is_up $PORT_API; then return 0; fi
  echo "[api] 启动后端 :$PORT_API ..."
  cd "$PROJ"
  export JAVA_HOME BAILIAN_API_KEY="${BAILIAN_API_KEY:-mock-key}" \
    CRYPTO_MASTER_KEY="${CRYPTO_MASTER_KEY:-please-change-me-in-production-32chars-min}"
  nohup $MVN -o -Dmaven.repo.local=/tmp/m2repo spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=$PORT_API --milvus.enabled=false" \
    > "$RT/logs/backend.log" 2>&1 &
  for _ in $(seq 1 30); do is_up $PORT_API && break; sleep 1; done
}

start_web() {
  if is_up $PORT_WEB; then return 0; fi
  echo "[web] 启动前端 :$PORT_WEB ..."
  cd "$PROJ/MindCrew-frontend"
  VITE_PROXY_TARGET="http://localhost:$PORT_API" VITE_DEV_PORT=$PORT_WEB \
    nohup ./node_modules/.bin/vite --port $PORT_WEB --strictPort --host 0.0.0.0 \
    > "$RT/logs/frontend.log" 2>&1 &
  sleep 4
}

status() {
  for n in "MySQL:$PORT_DB" "Redis:$PORT_REDIS" "MockLLM:$PORT_MOCK" "后端:$PORT_API" "前端:$PORT_WEB"; do
    p="${n#*:}"; name="${n%%:*}"
    is_up "$p" && echo "  ✔ $name 端口 $p" || echo "  ✘ $name 端口 $p"
  done
}

case "${1:-start}" in
  start)
    mkdir -p "$RT/logs"
    start_db && start_redis && start_mock && start_api && start_web
    echo; status
    echo
    echo "前端: http://127.0.0.1:$PORT_WEB   账号: admin / admin123"
    echo "后端: http://127.0.0.1:$PORT_API"
    ;;
  stop)
    pkill -f "MindCrewApplication" 2>/dev/null
    pkill -f "vite --port $PORT_WEB" 2>/dev/null
    pkill -f "mock_llm.py" 2>/dev/null
    "$RT/redis-7.2.5/src/redis-cli" -p $PORT_REDIS shutdown nosave 2>/dev/null
    "$MYSQL" -uroot -proot -h127.0.0.1 -e "SHUTDOWN" 2>/dev/null
    echo "已停止（MySQL 数据保留在 $RT/mysql-data）"
    ;;
  status) status ;;
  *) echo "用法: bash scripts/dev-local.sh start|stop|status" ;;
esac
