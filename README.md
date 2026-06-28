# ExchangeClear V3 微服务版

ExchangeClear 第三阶段将前两阶段的单体应用按职责拆分为多个微服务，接入 Nacos 服务注册发现、Spring Cloud Gateway 统一入口、OpenFeign 服务间调用，形成标准的 Spring Cloud Alibaba 微服务架构。

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
  └────────┬─────────┘   └─────────▲────────┘   └─────────┬────────┘
           │ 发送 MQ                │ OpenFeign             │ OpenFeign
           ▼                        │                       ▼
  ┌──────────────────┐              │             ┌──────────────────┐
  │   RocketMQ        │              │             │ settle-file       │
  │ file-generate-task│              │             │ service           │
  └────────┬─────────┘              │             └──────────────────┘
           ▼                        │ OpenFeign 保存元数据
  ┌──────────────────┐──────────────┘
  │ settle-worker     │  :8103 (可多实例)
  │ service           │
  └──────────────────┘
           │
      ┌────┴─────┐
      ▼          ▼
   MySQL      MinIO

所有服务注册到 Nacos。settle-mock-service (:8105) 负责模拟数据生成。
```

## 服务模块

| 模块 | 端口 | 职责 |
|---|---|---|
| exchange-gateway | 9000 | 统一入口、路由转发 |
| settle-task-service | 8101 | 任务创建、状态管理、投递 RocketMQ |
| settle-file-service | 8102 | 文件元数据管理、查询、校验 |
| settle-worker-service | 8103 | 消费 MQ、生成 CSV、上传 MinIO、保存元数据 |
| settle-download-service | 8104 | 文件下载、下载次数更新 |
| settle-mock-service | 8105 | 模拟会员/成交数据生成 |
| exchange-common | - | 公共 DTO、枚举、异常、Result、Md5Util/IdGenerator、Feign 接口 |
| exchange-storage | - | MinIO 对象存储公共封装（worker/download 共用） |

> `trade_record` 的 entity/mapper 不再共享模块：worker-service 自带读取用的 `TradeRecordMapper`（仅 `selectNextPage`），mock-service 自带写入用的 `TradeRecordMapper`（仅 `deleteBySettleDate`/`batchInsert`），各自维护对共享表的访问，不相互暴露实体。

## 环境

- JDK 11
- Maven 3.6+
- MySQL 8、MinIO、RocketMQ、Nacos

## 一、启动基础设施

```bash
docker-compose up -d
```

首次启动时，MySQL 容器会自动执行 `sql/01_schema.sql` 建表。组件地址：

- MySQL: `localhost:3306/exchange_clear`，用户名 `root`，密码 `root`
- MinIO API: `http://localhost:9000`，Console: `http://localhost:9001`（`minioadmin/minioadmin`）
- Nacos: `http://localhost:8848/nacos`（`nacos/nacos`）
- RocketMQ NameServer: `localhost:9876`，Dashboard: `http://localhost:8088`

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
- `settle-mock-service/target/settle-mock-service-0.0.1-SNAPSHOT-exec.jar`

## 三、启动微服务

建议顺序：

```bash
java -jar exchange-gateway/target/exchange-gateway-0.0.1-SNAPSHOT.jar
java -jar settle-file-service/target/settle-file-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-task-service/target/settle-task-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-worker-service/target/settle-worker-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-download-service/target/settle-download-service-0.0.1-SNAPSHOT-exec.jar
java -jar settle-mock-service/target/settle-mock-service-0.0.1-SNAPSHOT-exec.jar
```

启动后访问 Nacos 控制台 `http://localhost:8848/nacos`，确认 6 个服务均已注册。

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
EXCHANGE_GATEWAY_PORT=9000
```

服务间调用模式可切换（默认 `feign` 走 OpenFeign，`local` 在进程内直连，仅单体兼容场景使用）：

```bash
EXCHANGE_CLEAR_FILE_METADATA_CLIENT=feign     # worker/download -> file-service
EXCHANGE_CLEAR_TASK_FILE_GENERATE_CLIENT=feign # task -> worker-service
```

## 六、演示流程

统一通过 Gateway（默认 `http://localhost:9000`）访问。

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

任务由 `INIT` 更新为 `SENT`，worker-service 消费后异步生成文件、上传 MinIO、保存元数据，任务最终变为 `GENERATED`。

3. 查询任务

```http
GET http://localhost:9000/api/tasks?settleDate=2026-06-26
```

4. 人工重投任务

```http
POST http://localhost:9000/api/tasks/{taskNo}/resend
```

5. 查询文件列表 / 详情 / 校验

```http
GET http://localhost:9000/api/files?settleDate=2026-06-26&memberId=0001
GET http://localhost:9000/api/files/{fileNo}
GET http://localhost:9000/api/files/{fileNo}/checksum
```

6. 下载文件

```http
GET http://localhost:9000/api/download/files/{fileNo}
```

## 七、说明

- 当前阶段只实现 `TRADE` 成交文件。
- RocketMQ Topic `file-generate-task`，Tag `TRADE`；Producer 在 task-service，Consumer 在 worker-service。
- Worker 状态机：`INIT/SENT/FAILED -> GENERATING -> GENERATED`，重复消费时已 `GENERATED` 直接跳过。
- 并发消费通过条件更新抢占任务执行权，仅更新成功的 Worker 继续生成。
- 发送失败标记 `SEND_FAILED`，可通过 `/api/tasks/{taskNo}/resend` 重投。
- 文件生成使用 `id > lastId LIMIT pageSize` 分页，本地先写 `.tmp` 再移动为正式 CSV。
- worker-service 通过 OpenFeign 调用 file-service 保存元数据（`POST /internal/files/generated`），不再直接插入 `settle_file`。
- download-service 通过 OpenFeign 查询元数据并更新下载次数，文件流直接从 MinIO 读取，不经 Feign 转发。
- MinIO 与 MySQL 不在同一事务中，异常补偿留到后续阶段。
