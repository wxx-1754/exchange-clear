# ExchangeClear 结算文件生成与发布平台

ExchangeClear 是一个金融交易所结算文件生成与发布平台，按职责拆分为多个微服务，接入 Nacos 服务注册发现、Spring Cloud Gateway 统一入口、OpenFeign 服务间调用，并使用 Redis（Redisson）做缓存、限流与分布式锁，RocketMQ 做异步任务投递与状态事件广播。

项目按阶段演进，当前已迭代至第六阶段：

| 阶段 | 能力 |
|---|---|
| 一期 | 单体闭环：文件生成、MinIO 上传、文件元数据入库、查询和下载 |
| 二期 | 引入 RocketMQ：异步任务投递、Worker 消费、MQ 重试和消费幂等 |
| 三期 | 拆分微服务：task / file / worker / download，接入 Nacos、Gateway、OpenFeign |
| 四期 | 引入 Redis：文件列表缓存、文件元数据缓存、下载 Token、会员级限流、IP 级限流、部分分布式锁 |
| 五期 | 文件发布、撤销、重发与一致性补偿机制 |
| **六期** | **下载审计服务：RocketMQ 异步审计、幂等落库、审计查询与异常统计** |

第五阶段之后，文件生命周期调整为：

```text
Worker 生成文件
    ↓
文件状态：GENERATED（已生成，待发布）
    ↓
管理员或系统触发发布
    ↓
文件状态：PUBLISHED（已发布，可下载）
    ↓
只有 PUBLISHED 文件允许会员查询、申请下载 Token、下载文件
```

```text
                          ┌────────────────────┐
                          │  前端 / Postman     │
                          └─────────┬──────────┘
                                    │ HTTP
                                    ▼
                          ┌────────────────────┐
                          │  exchange-gateway   │  :9000
                          └─────────┬──────────┘
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
  ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
  │ settle-task       │   │ settle-file       │   │ settle-download   │
  │ service  :8101    │   │ service  :8102    │   │ service  :8104    │
  │ 任务/重投/重发任务 │   │ 发布/撤销/状态管理 │   │ Token/下载校验    │
  └────────┬─────────┘   └────┬───────┬──────┘   └─────────┬────────┘
           │ 发送 MQ          │ OpenFeign│                   │ OpenFeign
           ▼                  │         │                   ▼
  ┌──────────────────┐        │         │         ┌──────────────────┐
  │   RocketMQ        │        │         │         │ settle-file       │
  │ generate-task     │        │         │         │ service           │
  │ status.event      │        │         │         └──────────────────┘
  └────────┬─────────┘        │         │
           ▼                  │         │
  ┌──────────────────┐────────┘         │
  │ settle-worker     │  :8103 (可多实例) │
  │ service           │                  │
  └──────────────────┘                  │
           │                            │
      ┌────┴─────┐                ┌─────┴─────┐
      ▼          ▼                ▼           ▼
   MySQL      MinIO             Redis       MySQL
                    (缓存/Token/限流/分布式锁)

所有服务注册到 Nacos。settle-mock-service (:8105) 负责模拟数据生成，settle-audit-service (:8106) 负责下载审计消费、查询和统计。
对账补偿任务由 file-service 与 task-service 内置的 @Scheduled 定时任务承担。
```

## 服务模块

| 模块 | 端口 | 职责 |
|---|---|---|
| exchange-gateway | 9000 | 统一入口、路由转发 |
| settle-task-service | 8101 | 任务创建、状态管理、投递 RocketMQ、`GENERATING` 超时恢复任务 |
| settle-file-service | 8102 | 文件元数据管理、**发布 / 撤销 / 重发状态管理、发布锁、缓存删除、状态日志、本地消息表、对账任务** |
| settle-worker-service | 8103 | 消费 MQ、生成 CSV、上传 MinIO、保存元数据 |
| settle-download-service | 8104 | 下载 Token、下载校验（仅 `PUBLISHED` 可下载）、下载次数更新、发送下载审计 MQ |
| settle-mock-service | 8105 | 模拟会员/成交数据生成 |
| settle-audit-service | 8106 | 消费下载审计 MQ、审计落库、审计查询、异常下载统计 |
| exchange-common | - | 公共 DTO、枚举、异常、Result、Md5Util/IdGenerator、RedisKeys、Feign 接口 |
| exchange-storage | - | MinIO 对象存储公共封装（worker/download/file 共用） |

> `trade_record` 的 entity/mapper 不再共享模块：worker-service 自带读取用的 `TradeRecordMapper`（仅 `selectNextPage`），mock-service 自带写入用的 `TradeRecordMapper`（仅 `deleteBySettleDate`/`batchInsert`），各自维护对共享表的访问，不相互暴露实体。

## 文件状态机

```text
                    ┌──────────────┐
                    │  GENERATED   │  已生成，待发布
                    └──────┬───────┘
                           │ 发布
                           ▼
                    ┌──────────────┐
                    │  PUBLISHED   │  已发布，可下载
                    └──────┬───────┘
                           │
             ┌─────────────┼─────────────┐
             │ 撤销         │ 重发         │
             ▼             ▼             │
      ┌──────────────┐ ┌──────────────┐   │
      │   REVOKED    │ │   REISSUED   │   │
      │   已撤销      │ │ 已被新版本替代 │   │
      └──────────────┘ └──────────────┘   │
                                          ▼
                                  新版本 GENERATED → PUBLISHED
```

| 当前状态 | 目标状态 | 操作 | 是否允许 |
|---|---|---|---|
| GENERATED | PUBLISHED | 发布 | 允许 |
| GENERATED / PUBLISHED | REVOKED | 撤销 | 允许 |
| GENERATED / PUBLISHED / REVOKED | REISSUED | 重发 | 允许 |
| FAILED | GENERATED | 重新生成成功 | 允许 |

**下载规则**：只有 `PUBLISHED` 文件允许创建下载 Token 和下载；`GENERATED / REVOKED / REISSUED / FAILED` 一律禁止下载。撤销/重发后即使旧 Token 仍未过期，下载时也会二次校验文件状态。

## 环境依赖

- JDK 11
- Maven 3.6+
- MySQL 8、MinIO、RocketMQ、Nacos、**Redis**（五期新增）

## 一、启动基础设施

```bash
docker-compose up -d
```

首次启动时，MySQL 容器会自动执行 `sql/01_schema.sql` 建表。五期新增表（`file_publish_batch` / `file_status_log` / `local_message` / `file_reissue_record`）、六期新增表（`file_download_audit`）已包含在 `01_schema.sql` 中；增量脚本见 `docs/sql/03_v5_publish_reissue_reconcile.sql` 和 `docs/sql/04_v6_download_audit.sql`。

组件地址：

- MySQL: `localhost:3306/exchange_clear`，用户名 `root`，密码 `root`
- MinIO API: `http://localhost:9000`，Console: `http://localhost:9001`（`minioadmin/minioadmin`）
- Nacos: `http://localhost:8848/nacos`（`nacos/nacos`）
- RocketMQ NameServer: `localhost:9876`，Dashboard: `http://localhost:8088`
- Redis: `localhost:6379`（默认无密码，database 0）

> **端口冲突提示**：Gateway 默认占用 9000，与 MinIO 的 S3 API 端口相同。本地同时运行两者时，需用 `EXCHANGE_GATEWAY_PORT=9090`（或其它端口）将网关改到非 9000 端口，否则 worker/download 的 `minio.endpoint=http://localhost:9000` 会误连到网关。

## 二、构建

```bash
mvn clean package -DskipTests
```

各服务可执行 jar（带 `exec` classifier，gateway 除外）：

- `exchange-gateway/target/exchange-gateway-0.0.1-SNAPSHOT.jar`
- `settle-task-service/target/settle-task-service-0.0.1-SNAPSHOT-exec.jar`
- `settle-file-service/target/settle-file-service-0.0.1-SNAPSHOT-exec.jar`
- `settle-worker-service/target/settle-worker-service-0.0.1-SNAPSHOT-exec.jar`
- `settle-download-service/target/settle-download-service-0.0.1-SNAPSHOT-exec.jar`
- `settle-audit-service/target/settle-audit-service-0.0.1-SNAPSHOT-exec.jar`
- `settle-mock-service/target/settle-mock-service-0.0.1-SNAPSHOT-exec.jar`

## 三、启动微服务

建议顺序：

```bash
java -jar exchange-gateway/target/exchange-gateway-0.0.1-SNAPSHOT.jar
java -jar settle-file-service/target/settle-file-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-task-service/target/settle-task-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-worker-service/target/settle-worker-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-download-service/target/settle-download-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-audit-service/target/settle-audit-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-mock-service/target/settle-mock-service-0.0.1-SNAPSHOT-exec.jar
```

启动后访问 Nacos 控制台 `http://localhost:8848/nacos`，确认 7 个服务均已注册。

也可用 `mvn -pl <module> spring-boot:run` 单独运行某个服务。

## 四、多实例 Worker（横向扩容）

Worker 使用相同的 `spring.application.name=settle-worker-service` 和同一个 RocketMQ Consumer Group，多实例并行消费同一 Topic，由条件更新抢占任务执行权保证同一任务不重复生成。

```bash
./scripts/start-workers.sh   # 启动 8201/8202/8203 三个 worker 实例
```

或手动：

```bash
java -jar settle-worker-service/target/settle-worker-service-0.0.1-SNAPSHOT-exec.jar --server.port=8201
java -jar settle-worker-service/target/settle-worker-service-0.0.1-SNAPSHOT-exec.jar --server.port=8202
java -jar settle-worker-service/target/settle-worker-service-0.0.1-SNAPSHOT-exec.jar --server.port=8203
```

## 五、配置覆盖

所有连接信息均可通过环境变量覆盖，常用项：

```bash
NACOS_SERVER_ADDR=localhost:8848
MYSQL_URL=jdbc:mysql://localhost:3306/exchange_clear?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
MYSQL_USERNAME=root
MYSQL_PASSWORD=root
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=exchange-clear
ROCKETMQ_NAME_SERVER=localhost:9876
REDIS_HOST=localhost
REDIS_PORT=6379
EXCHANGE_GATEWAY_PORT=9000
```

服务间调用模式可切换（默认 `feign` 走 OpenFeign，`local` 在进程内直连，仅单体兼容场景使用）：

```bash
EXCHANGE_CLEAR_FILE_METADATA_CLIENT=feign     # worker/download -> file-service
EXCHANGE_CLEAR_TASK_FILE_GENERATE_CLIENT=feign # task -> worker-service
```

### 五期配置项

发布 / 撤销 / 重发与对账相关配置（均有默认值，可不显式配置）：

```properties
# 发布前校验
exchange-clear.publish.check-task-complete=true
exchange-clear.publish.check-file-meta=true
exchange-clear.publish.check-minio-exists=true
# 发布/撤销/重发分布式锁
exchange-clear.publish.lock-wait-seconds=3
exchange-clear.publish.lock-lease-seconds=300
exchange-clear.revoke.lock-wait-seconds=3
exchange-clear.revoke.lock-lease-seconds=60
exchange-clear.reissue.lock-wait-seconds=3
exchange-clear.reissue.lock-lease-seconds=60
# 本地消息表
exchange-clear.local-message.status-event-topic=file.status.event
exchange-clear.local-message.file-generate-task-topic=file.generate.task
exchange-clear.local-message.retry-interval-seconds=60
exchange-clear.local-message.max-retry-count=5
exchange-clear.local-message.batch-size=100
# 对账补偿任务（file-service）
exchange-clear.reconcile.local-message-enabled=true
exchange-clear.reconcile.local-message-delay-millis=60000
exchange-clear.reconcile.file-state-enabled=true
exchange-clear.reconcile.file-state-delay-millis=300000
exchange-clear.reconcile.minio-check-enabled=true
exchange-clear.reconcile.minio-check-days=7
exchange-clear.reconcile.minio-check-delay-millis=300000
exchange-clear.reconcile.lock-wait-seconds=1
exchange-clear.reconcile.lock-lease-seconds=120
# 任务超时恢复（task-service）
exchange-clear.reconcile.generating-timeout-enabled=true
exchange-clear.reconcile.generating-timeout-minutes=30
exchange-clear.reconcile.generating-timeout-delay-millis=60000
```

### 六期配置项

下载审计相关配置（均有默认值，可不显式配置）：

```properties
exchange-clear.audit.enabled=true
exchange-clear.audit.save-token-digest=true
exchange-clear.audit.max-fail-reason-length=1000
exchange-clear.audit.max-user-agent-length=512
exchange-clear.mq.topic.download-audit=file.download.audit
exchange-clear.mq.consumer-group.download-audit=exchange-clear-download-audit-consumer-group
rocketmq.producer.group=exchange-clear-download-audit-producer-group
rocketmq.consumer.group=exchange-clear-download-audit-consumer-group
```

## 六、演示流程

统一通过 Gateway（默认 `http://localhost:9000`）访问。

### 6.1 初始化与文件生成

1. 初始化模拟数据

```http
POST http://localhost:9000/api/mock/init
Content-Type: application/json

{
  "settleDate": "2026-06-26",
  "memberCount": 30,
  "tradeCountPerMember": 10000
}
```

2. 创建生成任务并投递 RocketMQ

```http
POST http://localhost:9000/api/tasks/create
Content-Type: application/json

{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

任务由 `INIT` 更新为 `SENT`，worker-service 消费后异步生成文件、上传 MinIO、保存元数据，任务最终变为 `GENERATED`，文件状态为 `GENERATED`。

3. 查询任务 / 文件列表

```http
GET http://localhost:9000/api/tasks?settleDate=2026-06-26
GET http://localhost:9000/api/files?settleDate=2026-06-26&memberId=0001
GET http://localhost:9000/api/files/{fileNo}
```

### 6.2 文件发布（GENERATED → PUBLISHED）

文件生成完成后需发布才能下载。发布前会校验：任务全部生成完成、待发布文件数量与已生成任务数量一致、文件元数据完整（file_name/file_md5/file_size/storage_bucket/storage_path）、可选 MinIO 文件存在性。

```http
POST http://localhost:9000/api/files/publish
Content-Type: application/json

{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1,
  "operator": "admin"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "batchNo": "PUB202606260001",
    "settleDate": "2026-06-26",
    "publishCount": 30,
    "status": "SUCCESS"
  }
}
```

查询发布批次：

```http
GET http://localhost:9000/api/files/publish-batches?settleDate=2026-06-26
```

### 6.3 下载（仅 PUBLISHED）

```http
GET http://localhost:9000/api/download/files/{fileNo}
```

若文件未发布或已失效，返回 `403003 文件尚未发布或已失效，禁止下载`。

### 6.4 撤销文件（GENERATED/PUBLISHED → REVOKED）

```http
POST http://localhost:9000/api/files/{fileNo}/revoke
Content-Type: application/json

{
  "operator": "admin",
  "reason": "文件数据异常，撤销下载"
}
```

撤销后下载返回 `403004 文件已撤销，禁止下载`。

### 6.5 重发文件（→ REISSUED，生成新版本）

重发不覆盖旧文件：旧文件状态变为 `REISSUED`，按 `version + 1` 创建新版本生成任务，新文件生成后状态为 `GENERATED`，需再次发布为 `PUBLISHED` 才能下载。

```http
POST http://localhost:9000/api/files/{fileNo}/reissue
Content-Type: application/json

{
  "operator": "admin",
  "reason": "文件内容错误，重新生成"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "reissueNo": "REISSUE202606260001",
    "oldFileNo": "FILE202606260001",
    "newVersion": 2,
    "status": "INIT"
  }
}
```

旧文件下载返回 `403005 文件已重发，请下载新版本文件`。

### 6.6 查询状态变更日志

```http
GET http://localhost:9000/api/files/{fileNo}/status-logs
```

### 6.7 下载审计查询与统计

下载成功、下载失败、Token 无效、权限拒绝、限流、文件未发布/撤销/重发等场景会由 download-service 发送 `file.download.audit` 消息，audit-service 消费后写入 `file_download_audit`。

```http
GET http://localhost:9000/api/audits/downloads?fileNo={fileNo}&pageNo=1&pageSize=20
GET http://localhost:9000/api/audits/downloads/{auditNo}
GET http://localhost:9000/api/audits/files/{fileNo}/downloads
GET http://localhost:9000/api/audits/members/{memberId}/downloads
GET http://localhost:9000/api/audits/stats/file-download?fileNo={fileNo}
GET http://localhost:9000/api/audits/stats/member-download?memberId={memberId}
GET http://localhost:9000/api/audits/stats/abnormal?startTime=2026-06-26 00:00:00&endTime=2026-06-27 00:00:00
```

## 七、一致性保障设计

### 7.1 分布式锁

使用 Redisson 分布式锁，避免多实例重复操作（锁均在数据库事务之外获取，提交后才释放）：

| 锁 | Key | 用途 |
|---|---|---|
| 发布锁 | `exchange-clear:lock:file:publish:{settleDate}` | 防止多实例同时发布同一结算日期 |
| 撤销锁 | `exchange-clear:lock:file:revoke:{fileNo}` | 防止同一文件重复撤销 |
| 重发锁 | `exchange-clear:lock:file:reissue:{fileNo}` | 防止同一文件重复重发 |
| 对账锁 | `exchange-clear:lock:reconcile:{jobName}` | 防止对账任务多实例并发执行 |

### 7.2 本地消息表

文件发布 / 撤销 / 重发时需同时更新数据库状态和发送 MQ 事件。由于 MySQL 与 RocketMQ 不在同一事务，引入 `local_message` 表保证最终一致：

```text
同一数据库事务内：更新业务表 + 插入 local_message(INIT)
    ↓ 事务提交后(afterCommit)
发送 MQ：成功 → SENT；失败 → FAILED(记 retry_count、next_retry_time)
    ↓
补偿任务扫描 INIT/FAILED 重试，超过最大次数等待人工处理
```

涉及的消息类型：

| 场景 | message_type | topic | tag |
|---|---|---|---|
| 文件发布 | FILE_PUBLISHED | file.status.event | PUBLISHED |
| 文件撤销 | FILE_REVOKED | file.status.event | REVOKED |
| 文件重发 | FILE_REISSUED | file.status.event | REISSUED |
| 新版本生成任务 | FILE_GENERATE_TASK | file.generate.task | {fileType} |

> 重发流程中，新版本生成任务的 MQ 投递同样走本地消息表（`autoSend=false`，由 file-service 在事务提交后统一发送），避免"数据库回滚但 MQ 已发出"的不一致。

### 7.3 Redis 缓存一致性

发布 / 撤销 / 重发成功后，在事务提交后（`afterCommit`）删除相关缓存，删除失败仅记日志不回滚，依赖 TTL 兜底：

- `exchange-clear:file:list:{settleDate}:{memberId}`（文件列表）
- `exchange-clear:file:meta:{fileNo}`（文件元数据）

## 八、对账补偿任务

由 `@Scheduled` 定时任务承担，每个任务执行前获取对账分布式锁，避免多实例重复执行：

| 任务 | 所在服务 | 频率 | 职责 |
|---|---|---|---|
| LocalMessageRetryJob | file-service | 1 分钟 | 扫描 `local_message` 中 INIT/FAILED 且到期的消息重试发送 |
| GeneratingTaskTimeoutJob | task-service | 1 分钟 | 扫描 `GENERATING` 超过 30 分钟的任务，置为 `FAILED` 并记录超时原因 |
| FileStateReconcileJob | file-service | 5 分钟 | 检查任务/文件状态不一致（如 PUBLISHED 但元数据缺失、REISSUED 但无新版本），记异常日志 |
| MinioFileExistsCheckJob | file-service | 5 分钟 | 检查最近 7 天文件在 MinIO 中是否存在，缺失则记异常日志 |

> 第一版对账任务只记录异常日志、不自动修改核心状态（超时任务除外），由人工介入处理。

## 九、数据库表

| 表 | 说明 |
|---|---|
| settle_file | 文件元数据，含 status / publish_time / revoke_time / reissue_time / status_reason |
| settle_file_task | 文件生成任务 |
| file_publish_batch | 发布批次（INIT/PUBLISHING/SUCCESS/FAILED） |
| file_status_log | 文件状态变更日志（GENERATE/PUBLISH/REVOKE/REISSUE） |
| local_message | 本地消息表（INIT/SENT/FAILED） |
| file_reissue_record | 文件重发记录（关联新旧文件与版本） |
| file_download_audit | 下载审计记录（下载结果、会员、文件、IP、User-Agent、耗时、失败原因） |
| trade_record | 成交明细（mock 写入，worker 读取） |

建表脚本：`sql/01_schema.sql`；五期增量：`docs/sql/03_v5_publish_reissue_reconcile.sql`；六期增量：`docs/sql/04_v6_download_audit.sql`。

## 十、说明

- 当前阶段只实现 `TRADE` 成交文件。
- RocketMQ Topic：`file.generate.task`（生成任务，Producer 在 file-service 重发与 task-service，Consumer 在 worker-service）、`file.status.event`（状态变更事件）、`file.download.audit`（下载审计，Producer 在 download-service，Consumer 在 audit-service）。
- 任务状态机：`INIT/SENT/FAILED -> GENERATING -> GENERATED`，`SEND_FAILED` 可通过 `/api/tasks/{taskNo}/resend` 重投。
- Worker 并发消费通过条件更新抢占任务执行权，仅更新成功的 Worker 继续生成；重复消费时已 `GENERATED` 直接跳过。
- 文件生成使用 `id > lastId LIMIT pageSize` 分页，本地先写 `.tmp` 再移动为正式 CSV。
- worker-service 通过 OpenFeign 调用 file-service 保存元数据（`POST /internal/files/generated`），不再直接插入 `settle_file`。
- download-service 通过 OpenFeign 查询元数据并更新下载次数，文件流直接从 MinIO 读取，不经 Feign 转发；下载时二次校验文件状态为 `PUBLISHED`。
- 五期详细设计见 `docs/ExchangeClear-V5-Publish-Reissue-Reconcile-Design.md`。
