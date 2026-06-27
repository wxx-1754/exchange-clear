# ExchangeClear V2 RocketMQ 异步任务版

ExchangeClear 第二阶段在第一阶段基础闭环上引入 RocketMQ，将结算文件生成改造为异步任务模型：

```text
初始化模拟数据 -> 创建文件生成任务 -> 投递 RocketMQ -> Worker 消费生成 CSV -> 上传 MinIO -> 写入文件元数据 -> 查询/下载/校验
```

## 环境

- JDK 11
- Maven 3.6+
- MySQL 8
- MinIO
- RocketMQ

本地基础组件可直接启动：

```bash
docker-compose up -d
```

默认配置：

- MySQL: `localhost:3306/exchange_clear`, 用户名 `root`, 密码 `root`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`, 用户名/密码 `minioadmin/minioadmin`
- RocketMQ NameServer: `localhost:9876`
- RocketMQ Dashboard: `http://localhost:8088`

## 启动应用

```bash
mvn spring-boot:run
```

应用启动时会执行 `src/main/resources/sql/01_schema.sql`，自动创建 V1 所需表。

可通过环境变量覆盖连接信息：

```bash
MYSQL_URL=jdbc:mysql://localhost:3306/exchange_clear?createDatabaseIfNotExist=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=root
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=exchange-clear
ROCKETMQ_NAME_SERVER=localhost:9876
```

如果是从 V1 已有数据库升级，需要先执行 `docs/sql/02_v2_rocketmq_upgrade.sql` 给 `settle_file_task` 补充 MQ 投递与重试字段；全新库会由 `src/main/resources/sql/01_schema.sql` 自动创建完整表结构。

## 演示流程

1. 初始化模拟数据

```http
POST http://localhost:8080/api/mock/init
Content-Type: application/json

{
  "settleDate": "2026-06-26",
  "memberCount": 10,
  "tradeCountPerMember": 1000
}
```

2. 创建生成任务并投递 RocketMQ

```http
POST http://localhost:8080/api/tasks/create
Content-Type: application/json

{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

创建成功后，新增任务会从 `INIT` 更新为 `SENT`，随后由 `FileGenerateTaskConsumer` 消费并交给 Worker 异步生成文件。

3. 查询任务

```http
GET http://localhost:8080/api/tasks?settleDate=2026-06-26
```

4. 人工重投单个任务

```http
POST http://localhost:8080/api/tasks/{taskNo}/resend
```

也可以批量投递指定状态的任务：

```http
POST http://localhost:8080/api/tasks/send-batch
Content-Type: application/json

{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "status": "SEND_FAILED"
}
```

5. 同步生成调试入口

```http
POST http://localhost:8080/api/tasks/{taskNo}/generate-sync
```

该接口仅用于本地调试，正常业务入口应使用 RocketMQ 异步生成。

6. 查询文件列表

```http
GET http://localhost:8080/api/files?settleDate=2026-06-26&memberId=0001
```

7. 下载文件

```http
GET http://localhost:8080/api/files/{fileNo}/download
```

8. 查询校验信息

```http
GET http://localhost:8080/api/files/{fileNo}/checksum
```

## V2 说明

- 只实现 `TRADE` 成交文件。
- 创建任务后立即发送 `file.generate.task` 消息，Tag 为 `TRADE`。
- Worker 使用 `INIT/SENT/FAILED -> GENERATING -> GENERATED` 状态机处理任务。
- 重复消费时如果任务已是 `GENERATED`，Worker 会直接跳过，避免重复生成。
- 并发消费时通过条件更新抢占任务执行权，只有更新成功的 Worker 会继续生成文件。
- 发送失败会将任务标记为 `SEND_FAILED`，可通过 `/api/tasks/{taskNo}/resend` 或 `/api/tasks/send-batch` 重投。
- 文件生成使用 `id > lastId LIMIT pageSize` 分页，不一次性加载全量成交数据。
- 本地文件先写 `.tmp`，生成完成后移动为正式 CSV。
- 上传成功后写入 `settle_file`，并将任务状态更新为 `GENERATED`。
- MinIO 与 MySQL 不在同一事务中，异常补偿留到后续阶段实现。
