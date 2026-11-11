#!/usr/bin/env bash
# =====================================================================
# MySQL 容器首次启动时执行 · 按文档顺序导入 sql/ 下所有 schema/migration
# 仅在数据卷为空时由 docker-entrypoint 自动调用，已初始化的库不会重跑
# =====================================================================
set -euo pipefail

SQL_DIR=/sql-source
DB=docmind
# --default-character-set=utf8mb4：强制客户端按 utf8mb4 读取 SQL 文件，
# 否则不带 SET NAMES 的 schema 文件（persona 等）里的中文会被当 latin1 导入而乱码
MYSQL="mysql --default-character-set=utf8mb4 -uroot -p${MYSQL_ROOT_PASSWORD}"

echo "[mindcrew-init] importing schema from ${SQL_DIR} ..."

# 1) 最先：建库 + 主体表 + 种子数据
if [[ -f "${SQL_DIR}/docmind-init.sql" ]]; then
  echo "[mindcrew-init] -> docmind-init.sql"
  ${MYSQL} < "${SQL_DIR}/docmind-init.sql"
fi

# 2) 先建表后改表：*-schema.sql 必须早于 *-migration.sql
#    否则会出现 migration ALTER 一张还没 CREATE 的表（如 agent-crew-fork
#    -migration 字母序排在 agent-crew-schema 之前）导致导入失败、init 中断。
shopt -s nullglob

import_one() {
  local f="$1"
  local name
  name=$(basename "$f")
  # 跳过已经处理过的、跳过 README 等说明文件
  if [[ "${name}" == "docmind-init.sql" ]]; then
    return
  fi
  echo "[mindcrew-init] -> ${name}"
  ${MYSQL} "${DB}" < "$f"
}

# 2a) 先导入所有 schema（建表），各自内部按字母序
for f in $(ls "${SQL_DIR}"/*-schema.sql 2>/dev/null | sort); do
  import_one "$f"
done

# 2b) 再导入其余文件（migration / 种子等），跳过上一步已处理的 *-schema.sql
for f in $(ls "${SQL_DIR}"/*.sql 2>/dev/null | sort); do
  case "$(basename "$f")" in
    *-schema.sql) continue ;;
  esac
  import_one "$f"
done

echo "[mindcrew-init] all SQL files imported."
