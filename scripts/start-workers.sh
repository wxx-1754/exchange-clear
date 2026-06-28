#!/usr/bin/env bash
# 启动多个 settle-worker-service 实例并行消费文件生成任务。
#
# 多实例共用相同的 spring.application.name=settle-worker-service 和同一个
# RocketMQ Consumer Group，由条件更新抢占任务执行权，保证同一任务不会被重复生成。
#
# 用法：
#   ./scripts/start-workers.sh            # 启动 8201/8202/8203 三个实例
#   WORKER_PORTS="8201 8202" ./scripts/start-workers.sh
#
# 依赖：已执行 mvn clean package -DskipTests 生成 exec jar。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 定位 worker exec jar
JAR=$(ls "$PROJECT_DIR"/settle-worker-service/target/settle-worker-service-*-exec.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "[ERROR] 未找到 worker exec jar，请先执行: mvn clean package -DskipTests" >&2
  exit 1
fi

# 默认启动三个实例
WORKER_PORTS="${WORKER_PORTS:-8201 8202 8203}"

echo "[INFO] worker jar: $JAR"

for port in $WORKER_PORTS; do
  LOG_FILE="$PROJECT_DIR/settle-worker-service/target/worker-${port}.log"
  echo "[INFO] 启动 worker 实例 port=${port}, log=${LOG_FILE}"
  nohup java -jar "$JAR" --server.port="${port}" > "$LOG_FILE" 2>&1 &
  echo "[INFO] worker pid=$! port=${port}"
done

echo "[INFO] 全部 worker 实例已启动，日志见 settle-worker-service/target/worker-*.log"
echo "[INFO] 停止实例: pkill -f settle-worker-service-*-exec.jar"
