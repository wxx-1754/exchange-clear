@echo off
REM 启动多个 settle-worker-service 实例并行消费文件生成任务。
REM
REM 多实例共用相同的 spring.application.name=settle-worker-service 和同一个
REM RocketMQ Consumer Group，由条件更新抢占任务执行权，保证同一任务不会被重复生成。
REM
REM 用法：
REM   scripts\start-workers.bat
REM   set WORKER_PORTS=8201 8202 && scripts\start-workers.bat
REM
REM 依赖：已执行 mvn clean package -DskipTests 生成 exec jar。

setlocal enabledelayedexpansion

set "PROJECT_DIR=%~dp0.."

REM 定位 worker exec jar
set "JAR="
for %%f in ("%PROJECT_DIR%\settle-worker-service\target\settle-worker-service-*-exec.jar") do set "JAR=%%f"

if not defined JAR (
  echo [ERROR] 未找到 worker exec jar，请先执行: mvn clean package -DskipTests
  exit /b 1
)
if not exist "%JAR%" (
  echo [ERROR] 未找到 worker exec jar，请先执行: mvn clean package -DskipTests
  exit /b 1
)

REM 默认启动三个实例
if not defined WORKER_PORTS set "WORKER_PORTS=8201 8202 8203"

echo [INFO] worker jar: %JAR%

for %%P in (%WORKER_PORTS%) do (
  set "PORT=%%P"
  set "LOG_FILE=%PROJECT_DIR%\settle-worker-service\target\worker-!PORT!.log"
  echo [INFO] 启动 worker 实例 port=!PORT!, log=!LOG_FILE!
  start "worker-!PORT!" /D "%PROJECT_DIR%" java -jar "%JAR%" --server.port=!PORT! > "!LOG_FILE!" 2>&1
)

echo [INFO] 全部 worker 实例已启动，日志见 settle-worker-service\target\worker-*.log
echo [INFO] 停止实例: 在任务管理器中结束 java 进程，或 taskkill /im java.exe /f
endlocal
