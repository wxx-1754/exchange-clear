# ExchangeClear 第二阶段：引入 RocketMQ 详细设计文档

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第二阶段的开发。

第一阶段已经完成单体闭环：

```text
创建文件生成任务
    ↓
同步生成文件
    ↓
上传 MinIO
    ↓
写入文件元数据
    ↓
查询文件
    ↓
下载文件
```

第二阶段的核心目标是：

> 将“同步文件生成”改造为“RocketMQ 异步任务分发 + Worker 消费生成”，为后续分布式 Worker、多实例并行处理和微服务拆分打基础。

---

## 2. 第二阶段目标

### 2.1 业务目标

1. 任务创建后不再同步生成文件。
2. 任务创建后只负责写入任务表，并发送文件生成消息。
3. Worker 消费 RocketMQ 消息后执行文件生成。
4. 支持多个 Worker 并行处理多个文件生成任务。
5. 支持文件生成失败后的 MQ 重试。
6. 支持 MQ 重复投递场景下的消费幂等。
7. 保留第一阶段的文件生成、MinIO 上传、文件元数据入库、文件查询和下载能力。

### 2.2 技术目标

1. 引入 RocketMQ 作为异步任务队列。
2. 新增 Producer 发送文件生成任务消息。
3. 新增 Consumer 消费文件生成任务消息。
4. 将原来的同步生成接口改造为“创建任务 + 投递 MQ”。
5. Worker 消费任务时根据任务状态进行幂等判断。
6. 支持多个 Worker 实例使用同一个 Consumer Group 并行消费。
7. 对失败任务使用 RocketMQ 重试机制。
8. 超过最大重试次数后进入死信队列或标记任务失败。
9. 增加任务重投接口，用于人工触发失败任务重新发送 MQ。
10. 保留后续扩展本地消息表、补偿任务和微服务拆分的边界。

---

## 3. 阶段范围

### 3.1 本阶段要做

1. 本地启动 RocketMQ。
2. 接入 RocketMQ Spring Boot Starter。
3. 新增 RocketMQ Topic。
4. 新增文件生成任务消息模型。
5. 创建任务后发送 MQ 消息。
6. Worker 消费 MQ 消息并生成文件。
7. 实现消费幂等。
8. 实现任务失败重试。
9. 实现批量投递任务。
10. 实现失败任务人工重投。
11. 支持多个 Worker 实例并行消费。

### 3.2 本阶段暂不做

1. 暂不拆分微服务。
2. 暂不引入 Nacos。
3. 暂不引入 Gateway。
4. 暂不引入 Redis。
5. 暂不实现分布式锁。
6. 暂不实现下载限流。
7. 暂不实现本地消息表。
8. 暂不实现完整对账补偿。
9. 暂不接 Prometheus + Grafana。
10. 暂不实现 Kubernetes 部署。

---

## 4. 第一阶段到第二阶段的核心变化

### 4.1 第一阶段同步生成

第一阶段调用链路：

```text
/api/tasks/{taskNo}/generate
    ↓
TaskController
    ↓
TaskService
    ↓
TradeCsvFileGenerator
    ↓
MinIO
    ↓
settle_file
```

特点：

1. HTTP 请求会阻塞直到文件生成完成。
2. 文件生成耗时会影响接口响应。
3. 多个任务只能由当前应用实例处理。
4. 不具备任务削峰能力。
5. 不具备天然的 Worker 横向扩展能力。

### 4.2 第二阶段异步生成

第二阶段调用链路：

```text
/api/tasks/create
    ↓
TaskService 创建任务
    ↓
发送 file.generate.task 消息
    ↓
RocketMQ
    ↓
FileGenerateConsumer 消费消息
    ↓
FileGenerateWorker 执行生成
    ↓
TradeCsvFileGenerator
    ↓
MinIO
    ↓
settle_file
```

特点：

1. 创建任务接口快速返回。
2. 文件生成由 Worker 异步完成。
3. RocketMQ 用于任务削峰。
4. 多个 Worker 实例可以并行消费。
5. Worker 失败后可利用 MQ 重试。
6. 消费端通过任务状态实现幂等。

---

## 5. 总体架构设计

### 5.1 第二阶段架构图

```text
┌──────────────────────────────┐
│        前端 / Postman         │
└───────────────┬──────────────┘
                │ HTTP
                ▼
┌──────────────────────────────┐
│      ExchangeClear 应用        │
│                              │
│  ┌──────────────────────┐    │
│  │ TaskController        │    │
│  └──────────┬───────────┘    │
│             │                │
│             ▼                │
│  ┌──────────────────────┐    │
│  │ TaskService           │    │
│  │ 创建任务 + 发送 MQ     │    │
│  └──────────┬───────────┘    │
│             │                │
└─────────────┼────────────────┘
              │ Producer
              ▼
┌──────────────────────────────┐
│          RocketMQ             │
│   Topic: file.generate.task   │
└─────────────┬────────────────┘
              │ Consumer Group
              ▼
┌──────────────────────────────┐
│      ExchangeClear Worker     │
│                              │
│  ┌──────────────────────┐    │
│  │ FileGenerateConsumer  │    │
│  └──────────┬───────────┘    │
│             ▼                │
│  ┌──────────────────────┐    │
│  │ FileGenerateWorker    │    │
│  └──────────┬───────────┘    │
│             ▼                │
│  ┌──────────────────────┐    │
│  │ TradeCsvFileGenerator │    │
│  └──────────┬───────────┘    │
└─────────────┼────────────────┘
              │
        ┌─────┴──────┐
        ▼            ▼
┌──────────────┐ ┌──────────────┐
│    MySQL      │ │    MinIO      │
│ 任务/元数据/成交 │ │  文件对象存储 │
└──────────────┘ └──────────────┘
```

### 5.2 多 Worker 并行架构

第二阶段虽然还不拆微服务，但可以通过启动多个应用实例模拟 Worker 并行处理。

```text
                    ┌────────────────────────┐
                    │       RocketMQ          │
                    │ file.generate.task Topic│
                    └───────────┬────────────┘
                                │
            ┌───────────────────┼───────────────────┐
            ▼                   ▼                   ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ Worker 实例 1     │ │ Worker 实例 2     │ │ Worker 实例 3     │
│ port: 8081        │ │ port: 8082        │ │ port: 8083        │
│ consumer group相同 │ │ consumer group相同 │ │ consumer group相同 │
└──────────────────┘ └──────────────────┘ └──────────────────┘
```

多个 Worker 使用相同 Consumer Group：

```text
exchange-clear-file-generate-consumer-group
```

同一个 Consumer Group 下的多个实例会对 Topic 下的消息进行负载均衡消费。

---

## 6. RocketMQ 设计

## 6.1 Topic 设计

### Topic 名称

```text
file.generate.task
```

### Topic 说明

用于承载结算文件生成任务消息。

### 消息来源

```text
TaskService
```

### 消费方

```text
FileGenerateConsumer
```

### 消息含义

每一条消息代表一个待生成的文件任务。

---

## 6.2 Consumer Group 设计

### Consumer Group 名称

```text
exchange-clear-file-generate-consumer-group
```

### 设计说明

1. 所有 Worker 实例使用相同 Consumer Group。
2. RocketMQ 会在同一个 Consumer Group 内进行消息负载均衡。
3. 同一条消息只会被 Consumer Group 中的一个 Worker 实例消费。
4. 如果 Worker 消费失败，RocketMQ 会按照重试策略重新投递。

---

## 6.3 Tag 设计

### Tag 名称

```text
TRADE
```

第一阶段只支持成交文件，因此只需要一个 Tag：

```text
file.generate.task:TRADE
```

后续扩展时可以增加：

```text
file.generate.task:FUND
file.generate.task:POSITION
file.generate.task:MARGIN
file.generate.task:FEE
```

---

## 6.4 消息模型设计

### FileGenerateTaskMessage

```java
public class FileGenerateTaskMessage {

    private String messageId;

    private String taskNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer version;

    private Long createdAt;
}
```

### 消息 JSON 示例

```json
{
  "messageId": "MSG202606260001",
  "taskNo": "TASK202606260001",
  "settleDate": "2026-06-26",
  "memberId": "0001",
  "fileType": "TRADE",
  "version": 1,
  "createdAt": 1782458400000
}
```

---

## 6.5 消息 Key 设计

RocketMQ 消息 Key 建议使用任务编号：

```text
taskNo
```

示例：

```text
TASK202606260001
```

用途：

1. 方便在 RocketMQ 控制台中按 Key 查询消息。
2. 方便日志排查。
3. 方便定位某个文件任务的消息轨迹。

---

## 6.6 消息幂等业务 Key

消费端幂等不依赖 MQ MessageId，而是依赖业务唯一键。

```text
settleDate + memberId + fileType + version
```

数据库中已经通过唯一键保证：

```sql
UNIQUE KEY uk_task_biz (settle_date, member_id, file_type, version)
```

消费时优先使用 `taskNo` 查询任务记录，再结合任务状态判断是否允许执行。

---

## 7. 数据库调整设计

第二阶段在第一阶段基础上，对 `settle_file_task` 表进行轻量扩展。

## 7.1 settle_file_task 增加字段

```sql
ALTER TABLE settle_file_task
ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '业务重试次数' AFTER status,
ADD COLUMN max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大业务重试次数' AFTER retry_count,
ADD COLUMN last_message_id VARCHAR(128) DEFAULT NULL COMMENT '最后一次投递的消息ID' AFTER max_retry_count,
ADD COLUMN last_send_time DATETIME DEFAULT NULL COMMENT '最后一次发送MQ时间' AFTER last_message_id,
ADD COLUMN last_consume_time DATETIME DEFAULT NULL COMMENT '最后一次消费时间' AFTER last_send_time;
```

### 字段说明

| 字段 | 说明 |
|---|---|
| retry_count | 业务层记录的重试次数 |
| max_retry_count | 最大业务重试次数 |
| last_message_id | 最近一次发送的消息 ID |
| last_send_time | 最近一次发送 MQ 的时间 |
| last_consume_time | 最近一次消费 MQ 的时间 |

---

## 7.2 任务状态扩展

第二阶段建议任务状态增加 `SENT` 状态。

| 状态 | 说明 |
|---|---|
| INIT | 任务已创建，尚未投递 MQ |
| SENT | 任务已投递 MQ，等待 Worker 消费 |
| GENERATING | Worker 正在生成文件 |
| GENERATED | 文件已生成 |
| FAILED | 文件生成失败 |
| SEND_FAILED | MQ 发送失败 |

### 状态流转

```text
INIT
  ↓ 发送 MQ 成功
SENT
  ↓ Worker 开始消费
GENERATING
  ↓ 生成成功
GENERATED
```

异常流转：

```text
INIT
  ↓ 发送 MQ 失败
SEND_FAILED
  ↓ 人工重投 / 定时重投
SENT
```

生成失败流转：

```text
SENT
  ↓ Worker 消费
GENERATING
  ↓ 生成失败
FAILED
  ↓ 人工重投 / MQ 重试 / 重新生成
SENT / GENERATING
```

---

## 7.3 TaskStatusEnum 调整

```java
public enum TaskStatusEnum {

    INIT("INIT", "待投递"),
    SENT("SENT", "已投递"),
    GENERATING("GENERATING", "生成中"),
    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "生成失败"),
    SEND_FAILED("SEND_FAILED", "消息发送失败");

    private final String code;
    private final String desc;
}
```

---

## 8. 代码结构调整

第二阶段建议在第一阶段单体项目中新增 `mq` 和 `worker` 两个包。

```text
com.example.exchangeclear
├── mq
│   ├── config
│   │   └── RocketMqProperties.java
│   ├── message
│   │   └── FileGenerateTaskMessage.java
│   ├── producer
│   │   └── FileGenerateTaskProducer.java
│   └── consumer
│       └── FileGenerateTaskConsumer.java
├── worker
│   └── FileGenerateWorker.java
```

### 8.1 mq.message

存放 RocketMQ 消息体。

### 8.2 mq.producer

负责发送文件生成任务消息。

### 8.3 mq.consumer

负责消费文件生成任务消息。

### 8.4 worker

负责承接 Consumer 调用，执行任务幂等判断、状态流转和文件生成。

---

## 9. application.yml 配置

```yaml
server:
  port: 8080

spring:
  application:
    name: exchange-clear

  datasource:
    url: jdbc:mysql://localhost:3306/exchange_clear?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

rocketmq:
  name-server: localhost:9876
  producer:
    group: exchange-clear-file-generate-producer-group
    send-message-timeout: 3000
    retry-times-when-send-failed: 2
  consumer:
    group: exchange-clear-file-generate-consumer-group

exchange-clear:
  mq:
    topic:
      file-generate-task: file.generate.task
    tag:
      trade: TRADE
  file:
    local-root-path: /tmp/exchange-clear
    page-size: 1000

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: exchange-clear
```

---

## 10. Docker Compose 设计

第二阶段在第一阶段 MySQL + MinIO 基础上增加 RocketMQ。

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: exchange-clear-mysql
    restart: always
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: exchange_clear
      TZ: Asia/Shanghai
    ports:
      - "3306:3306"
    command:
      --default-authentication-plugin=mysql_native_password
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
    volumes:
      - ./data/mysql:/var/lib/mysql

  minio:
    image: quay.io/minio/minio
    container_name: exchange-clear-minio
    restart: always
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    command: server /data --console-address ":9001"
    volumes:
      - ./data/minio:/data

  rocketmq-namesrv:
    image: apache/rocketmq:5.1.4
    container_name: exchange-clear-rocketmq-namesrv
    ports:
      - "9876:9876"
    command: sh mqnamesrv

  rocketmq-broker:
    image: apache/rocketmq:5.1.4
    container_name: exchange-clear-rocketmq-broker
    depends_on:
      - rocketmq-namesrv
    ports:
      - "10911:10911"
      - "10909:10909"
    environment:
      NAMESRV_ADDR: rocketmq-namesrv:9876
    command: sh mqbroker -n rocketmq-namesrv:9876

  rocketmq-dashboard:
    image: apacherocketmq/rocketmq-dashboard:latest
    container_name: exchange-clear-rocketmq-dashboard
    depends_on:
      - rocketmq-namesrv
    ports:
      - "8088:8080"
    environment:
      JAVA_OPTS: "-Drocketmq.namesrv.addr=rocketmq-namesrv:9876"
```

> 注意：不同 RocketMQ Docker 镜像版本的启动命令可能略有差异。实际项目中可以根据本地环境调整镜像版本和 broker 配置。

---

## 11. Producer 设计

## 11.1 FileGenerateTaskProducer 职责

1. 构造 FileGenerateTaskMessage。
2. 设置 Topic、Tag、Key。
3. 发送同步消息。
4. 返回发送结果。
5. 发送失败时抛出业务异常。
6. 发送成功后更新任务状态为 SENT。

## 11.2 Producer 伪代码

```java
@Service
public class FileGenerateTaskProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Value("${exchange-clear.mq.topic.file-generate-task}")
    private String topic;

    public SendResult send(FileGenerateTaskMessage message) {
        String destination = topic + ":" + message.getFileType();
        Message<FileGenerateTaskMessage> mqMessage = MessageBuilder
                .withPayload(message)
                .setHeader(MessageConst.PROPERTY_KEYS, message.getTaskNo())
                .build();

        return rocketMQTemplate.syncSend(destination, mqMessage);
    }
}
```

## 11.3 发送时机

第二阶段任务创建后立即发送 MQ。

```text
/api/tasks/create
    ↓
创建任务记录
    ↓
发送 MQ
    ↓
更新任务状态为 SENT
    ↓
返回创建结果
```

对于批量任务，需要逐条发送消息。

## 11.4 发送失败处理

发送 MQ 失败时，任务状态更新为：

```text
SEND_FAILED
```

处理方式：

1. 记录错误日志。
2. 更新 `last_send_time`。
3. 记录 `error_message`。
4. 返回接口时提示部分任务投递失败。
5. 后续通过人工重投接口重新发送。

---

## 12. Consumer 设计

## 12.1 FileGenerateTaskConsumer 职责

1. 监听 `file.generate.task` Topic。
2. 反序列化消息。
3. 打印消费日志。
4. 调用 FileGenerateWorker 执行业务逻辑。
5. 业务处理成功则正常 ACK。
6. 业务处理失败则抛出异常，触发 RocketMQ 重试。

## 12.2 Consumer 伪代码

```java
@Component
@RocketMQMessageListener(
    topic = "${exchange-clear.mq.topic.file-generate-task}",
    consumerGroup = "exchange-clear-file-generate-consumer-group",
    selectorExpression = "*",
    consumeThreadNumber = 4,
    maxReconsumeTimes = 3
)
public class FileGenerateTaskConsumer implements RocketMQListener<FileGenerateTaskMessage> {

    @Resource
    private FileGenerateWorker fileGenerateWorker;

    @Override
    public void onMessage(FileGenerateTaskMessage message) {
        fileGenerateWorker.handle(message);
    }
}
```

## 12.3 消费线程数配置

建议第一版：

```text
consumeThreadNumber = 2 或 4
maxReconsumeTimes = 3
```

原因：

1. 文件生成属于 IO 密集型任务。
2. 线程数过大可能导致数据库、磁盘和 MinIO 压力过大。
3. 后续通过压测调整。

---

## 13. Worker 设计

## 13.1 FileGenerateWorker 职责

`FileGenerateWorker` 是 Consumer 和文件生成核心逻辑之间的业务协调层。

职责：

1. 根据 taskNo 查询任务。
2. 判断任务是否存在。
3. 判断任务状态是否允许执行。
4. 将任务状态更新为 GENERATING。
5. 调用文件生成模块生成本地文件。
6. 上传 MinIO。
7. 写入文件元数据。
8. 更新任务状态为 GENERATED。
9. 生成失败时更新任务状态为 FAILED。
10. 抛出异常，让 RocketMQ 进行重试。

---

## 13.2 Worker 幂等判断

Worker 消费消息后，先查询任务状态：

| 当前状态 | 处理方式 |
|---|---|
| INIT | 允许执行，兼容手工插入但未发送状态 |
| SENT | 允许执行 |
| GENERATING | 直接返回，避免并发重复生成 |
| GENERATED | 已处理成功，直接返回成功 |
| FAILED | 允许执行，但 retry_count + 1 |
| SEND_FAILED | 不应该被消费到，记录异常后返回 |

---

## 13.3 Worker 处理流程

```text
收到 MQ 消息
    ↓
根据 taskNo 查询任务
    ↓
任务不存在？
    ├── 是：记录日志，直接返回
    └── 否
        ↓
任务状态为 GENERATED？
    ├── 是：说明重复消费，直接返回
    └── 否
        ↓
任务状态是否允许执行？
    ├── 否：记录日志，直接返回
    └── 是
        ↓
更新任务状态为 GENERATING
        ↓
调用文件生成逻辑
        ↓
上传 MinIO
        ↓
写入 settle_file
        ↓
更新任务状态为 GENERATED
        ↓
消费成功
```

## 13.4 Worker 伪代码

```java
@Service
public class FileGenerateWorker {

    @Resource
    private SettleFileTaskMapper taskMapper;

    @Resource
    private TradeCsvFileGenerator tradeCsvFileGenerator;

    @Resource
    private ObjectStorageService objectStorageService;

    @Resource
    private SettleFileService settleFileService;

    public void handle(FileGenerateTaskMessage message) {
        SettleFileTask task = taskMapper.selectByTaskNo(message.getTaskNo());

        if (task == null) {
            log.warn("task not found, taskNo={}", message.getTaskNo());
            return;
        }

        if (TaskStatusEnum.GENERATED.getCode().equals(task.getStatus())) {
            log.info("task already generated, taskNo={}", task.getTaskNo());
            return;
        }

        if (!allowGenerate(task)) {
            log.info("task status not allow generate, taskNo={}, status={}",
                    task.getTaskNo(), task.getStatus());
            return;
        }

        int updated = taskMapper.updateStatusToGenerating(task.getTaskNo());
        if (updated == 0) {
            log.info("task already locked by another worker, taskNo={}", task.getTaskNo());
            return;
        }

        try {
            FileGenerateContext context = buildContext(task);
            FileGenerateResult result = tradeCsvFileGenerator.generate(context);

            String objectName = buildObjectName(task, result.getFileName());
            StorageUploadResult uploadResult = objectStorageService.upload(result.getLocalFilePath(), objectName);

            settleFileService.saveGeneratedFile(task, result, uploadResult);

            taskMapper.updateStatusToGenerated(task.getTaskNo());

        } catch (Exception e) {
            taskMapper.updateStatusToFailed(task.getTaskNo(), truncate(e.getMessage()));
            throw e;
        }
    }
}
```

> 注意：真实代码中不建议把文件生成、MinIO 上传这类长耗时外部 IO 全部放入一个数据库事务中。推荐只在更新数据库状态时开启短事务。上面的伪代码用于表达主流程，实际实现时应拆分事务边界。

---

## 14. 事务边界设计

## 14.1 不推荐的做法

不推荐将整个文件生成过程放入一个大事务：

```text
开启事务
    ↓
更新任务状态
    ↓
查询大量成交数据
    ↓
生成本地文件
    ↓
上传 MinIO
    ↓
写入文件元数据
    ↓
提交事务
```

问题：

1. 事务时间过长。
2. 数据库连接长时间占用。
3. 文件生成和 MinIO 上传不是数据库事务资源。
4. 失败回滚无法回滚已经上传的文件。
5. 容易造成锁等待和连接池耗尽。

## 14.2 推荐事务边界

推荐拆成多个短事务。

```text
事务 1：
    将任务状态从 SENT 更新为 GENERATING

非事务：
    生成本地文件
    上传 MinIO

事务 2：
    插入 settle_file
    更新任务状态为 GENERATED

异常处理事务：
    更新任务状态为 FAILED
    记录 error_message
```

### 状态更新防并发

更新 GENERATING 时建议使用条件更新：

```sql
UPDATE settle_file_task
SET status = 'GENERATING',
    start_time = NOW(),
    last_consume_time = NOW()
WHERE task_no = ?
  AND status IN ('INIT', 'SENT', 'FAILED');
```

如果影响行数为 0，说明任务状态已经被其他线程或实例修改，当前 Consumer 直接返回即可。

---

## 15. 消费幂等设计

## 15.1 为什么必须做幂等

RocketMQ 在以下场景可能导致消息重复消费：

1. Consumer 消费成功但 ACK 失败。
2. Consumer 处理超时。
3. Consumer 重启。
4. Broker 重新投递。
5. 网络异常。
6. 主动重试。
7. 人工重投。

因此不能假设一条消息只会被处理一次。

## 15.2 幂等原则

消费幂等不依赖 RocketMQ 自身，而依赖业务状态机。

核心判断：

```text
如果任务已经 GENERATED，则说明文件已经成功生成，直接返回成功。
```

### 幂等关键点

1. `settle_file_task.task_no` 唯一。
2. `settle_file` 通过业务唯一键防止重复插入。
3. Worker 通过条件更新抢占任务执行权。
4. 任务状态为 GENERATED 时直接跳过。
5. MinIO 存储路径包含版本号，避免覆盖非预期文件。

## 15.3 文件元数据幂等

`settle_file` 唯一键：

```text
settle_date + member_id + file_type + version
```

如果重复插入，说明文件元数据已经存在。

处理策略：

1. 如果任务已 GENERATED，直接返回。
2. 如果任务未 GENERATED 但文件元数据存在，则查询文件记录，校验 MD5 和路径。
3. 第一版可以简单处理为：唯一键冲突则认为已生成，并将任务状态更新为 GENERATED。

## 15.4 MinIO 文件上传幂等

对象路径：

```text
{settleDate}/{memberId}/v{version}/{fileType}/{fileName}
```

同一个任务重复上传时路径一致。

第一版可以采用覆盖上传。

更严谨的做法：

1. 上传前判断对象是否存在。
2. 如果对象已存在，比较本地 MD5 和对象元数据 MD5。
3. 如果一致，跳过上传。
4. 如果不一致，标记异常，等待人工处理。

---

## 16. MQ 重试设计

## 16.1 RocketMQ 重试机制

当 Consumer 处理消息抛出异常时，RocketMQ 会认为消费失败，并进行重试。

典型场景：

1. 数据库临时异常。
2. MinIO 临时不可用。
3. 文件系统临时异常。
4. Worker 短暂重启。
5. 网络抖动。

## 16.2 消费失败处理原则

Worker 发生异常时：

```text
1. 捕获异常
2. 更新任务状态为 FAILED
3. retry_count + 1
4. 记录 error_message
5. 继续向外抛出异常
6. 让 RocketMQ 触发重试
```

这样可以做到：

1. 数据库中能看到任务失败原因。
2. RocketMQ 负责自动重试。
3. 重试时 Worker 看到 FAILED 状态，可以再次执行。
4. 如果最终成功，任务变为 GENERATED。

## 16.3 最大重试次数

建议第一版设置：

```text
maxReconsumeTimes = 3
```

业务表中也设置：

```text
max_retry_count = 3
```

### 注意

RocketMQ 的重试次数和业务表中的 `retry_count` 不是完全等价的。

建议第一版处理方式：

1. 每次 Worker 执行失败，业务 `retry_count + 1`。
2. 如果 `retry_count >= max_retry_count`，Worker 可以直接抛出不可重试异常，或直接返回并保持 FAILED。
3. RocketMQ 达到最大重试次数后会进入死信队列。

## 16.4 是否所有异常都重试

不是所有异常都应该重试。

### 可重试异常

| 异常 | 是否重试 |
|---|---|
| MySQL 连接超时 | 是 |
| MinIO 连接失败 | 是 |
| 网络超时 | 是 |
| 临时磁盘 IO 异常 | 是 |
| RocketMQ 短暂异常 | 是 |

### 不建议重试异常

| 异常 | 是否重试 |
|---|---|
| 参数非法 | 否 |
| 任务不存在 | 否 |
| 文件类型不支持 | 否 |
| 会员不存在 | 否 |
| 数据格式错误 | 否 |
| SQL 语法错误 | 否 |

第一版可以先统一抛出异常触发重试，后续再细分异常类型。

---

## 17. 人工重投设计

## 17.1 为什么需要人工重投

以下场景需要人工重投：

1. MQ 发送失败，任务状态为 SEND_FAILED。
2. Worker 多次失败，任务状态为 FAILED。
3. 消息进入死信队列。
4. 修复数据或环境问题后需要重新生成。
5. 测试时需要重新验证任务执行。

## 17.2 重投接口

### URL

```http
POST /api/tasks/{taskNo}/resend
```

### 处理逻辑

```text
1. 根据 taskNo 查询任务
2. 校验任务存在
3. 判断任务状态
4. 如果任务是 GENERATED，拒绝重投
5. 如果任务是 GENERATING，拒绝重投
6. 如果任务是 INIT、SENT、FAILED、SEND_FAILED，允许重投
7. 构造 FileGenerateTaskMessage
8. 发送 RocketMQ
9. 更新任务状态为 SENT
10. 更新 last_message_id 和 last_send_time
```

### 返回示例

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskNo": "TASK202606260001",
    "status": "SENT"
  }
}
```

---

## 18. API 调整设计

## 18.1 创建任务接口调整

### URL

```http
POST /api/tasks/create
```

### 第二阶段行为

```text
创建任务后立即发送 MQ
```

### 请求参数

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "settleDate": "2026-06-26",
    "fileType": "TRADE",
    "createdCount": 10,
    "sentCount": 10,
    "sendFailedCount": 0,
    "existsCount": 0
  }
}
```

## 18.2 批量投递接口

### URL

```http
POST /api/tasks/send-batch
```

### 请求参数

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "status": "INIT"
}
```

### 用途

用于将指定状态的任务批量发送到 RocketMQ。

适用场景：

1. 第一阶段任务已创建但未发送。
2. MQ 发送失败后批量重发。
3. 测试环境批量触发任务生成。

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "totalCount": 10,
    "sentCount": 10,
    "failedCount": 0
  }
}
```

## 18.3 单任务重投接口

### URL

```http
POST /api/tasks/{taskNo}/resend
```

说明：

用于重投单个失败任务。

## 18.4 同步生成接口处理

第一阶段已有：

```http
POST /api/tasks/{taskNo}/generate
```

第二阶段建议保留，但仅用于调试。

可以改名或标记为内部接口：

```http
POST /api/tasks/{taskNo}/generate-sync
```

接口说明：

```text
该接口仅用于本地调试，不作为正常业务入口。
正式文件生成应通过 RocketMQ 异步完成。
```

---

## 19. 并行处理设计

## 19.1 并行维度

第二阶段的并行主要来自两个方面：

### 单实例内部并行

通过 Consumer 多线程处理消息：

```text
consumeThreadNumber = 4
```

### 多实例并行

启动多个应用实例：

```text
java -jar exchange-clear.jar --server.port=8081
java -jar exchange-clear.jar --server.port=8082
java -jar exchange-clear.jar --server.port=8083
```

多个实例使用相同 Consumer Group。

## 19.2 并行任务粒度

并行粒度仍然是：

```text
settleDate + memberId + fileType + version
```

一个会员的一类文件对应一个任务。

例如：

```text
TASK001 -> 0001 TRADE
TASK002 -> 0002 TRADE
TASK003 -> 0003 TRADE
```

多个 Worker 可以并行处理不同会员的文件。

## 19.3 并行安全

为避免重复生成，需要做到：

1. 任务业务唯一键。
2. 任务状态机。
3. 条件更新抢占任务执行权。
4. 文件元数据唯一键。
5. 消费端重复消息直接跳过。

### 抢占任务 SQL

```sql
UPDATE settle_file_task
SET status = 'GENERATING',
    start_time = NOW(),
    last_consume_time = NOW()
WHERE task_no = ?
  AND status IN ('INIT', 'SENT', 'FAILED');
```

如果返回影响行数为 1，说明当前 Worker 抢占成功。

如果返回影响行数为 0，说明任务已经被其他 Worker 处理或状态不允许处理。

## 19.4 Worker 数量建议

第一版建议：

```text
Worker 实例数：1
consumeThreadNumber：2
```

验证成功后：

```text
Worker 实例数：3
每个实例 consumeThreadNumber：2
总消费并行度：约 6
```

不要一开始把线程数开太大，否则可能压垮 MySQL 或 MinIO。

---

## 20. 关键流程设计

## 20.1 创建任务并发送 MQ

```text
调用 /api/tasks/create
    ↓
查询有效会员列表
    ↓
为每个会员创建任务
    ↓
任务状态 INIT
    ↓
构造 FileGenerateTaskMessage
    ↓
发送 RocketMQ
    ↓
发送成功：更新任务状态为 SENT
    ↓
发送失败：更新任务状态为 SEND_FAILED
    ↓
返回 createdCount、sentCount、sendFailedCount
```

## 20.2 Worker 消费生成文件

```text
RocketMQ 投递消息
    ↓
FileGenerateTaskConsumer 接收消息
    ↓
FileGenerateWorker.handle(message)
    ↓
查询任务
    ↓
判断任务状态
    ↓
条件更新为 GENERATING
    ↓
生成 CSV
    ↓
计算 MD5
    ↓
上传 MinIO
    ↓
保存 settle_file
    ↓
更新任务状态为 GENERATED
    ↓
消费成功
```

## 20.3 Worker 失败重试

```text
Worker 消费消息
    ↓
生成文件失败 / 上传失败 / 数据库异常
    ↓
更新任务状态为 FAILED
    ↓
retry_count + 1
    ↓
抛出异常
    ↓
RocketMQ 稍后重新投递
    ↓
Worker 再次消费
```

## 20.4 重复消费处理

```text
Worker 收到重复消息
    ↓
根据 taskNo 查询任务
    ↓
发现状态为 GENERATED
    ↓
直接返回成功
    ↓
不重复生成文件
```

## 20.5 并发消费处理

```text
Worker A 和 Worker B 同时收到同一任务消息
    ↓
两个 Worker 都尝试更新任务状态为 GENERATING
    ↓
Worker A 更新成功，影响行数 = 1
    ↓
Worker B 更新失败，影响行数 = 0
    ↓
Worker A 继续生成文件
    ↓
Worker B 直接返回
```

---

## 21. 失败场景设计

## 21.1 MQ 发送失败

### 场景

创建任务成功，但发送 MQ 失败。

### 处理

```text
1. 任务状态更新为 SEND_FAILED
2. 记录 error_message
3. 返回 sendFailedCount
4. 后续通过 /api/tasks/{taskNo}/resend 重投
```

## 21.2 Worker 生成文件失败

### 场景

本地文件写入失败、数据查询失败、MD5 计算失败。

### 处理

```text
1. 任务状态更新为 FAILED
2. retry_count + 1
3. 删除 .tmp 临时文件
4. 抛出异常触发 RocketMQ 重试
```

## 21.3 MinIO 上传失败

### 处理

```text
1. 保留或删除本地生成文件
2. 任务状态更新为 FAILED
3. 记录错误信息
4. 抛出异常触发 MQ 重试
```

建议第一版：

```text
保留本地正式文件，方便排查；删除 .tmp 文件。
```

## 21.4 数据库写入 settle_file 失败

### 场景

文件已经上传 MinIO，但数据库写入失败。

### 处理

第一版：

```text
1. 任务状态更新为 FAILED
2. 记录错误信息
3. 抛出异常触发 MQ 重试
```

重试时可能再次上传同一路径文件。

后续阶段通过本地消息表和对账补偿优化。

## 21.5 Worker 宕机

### 场景

任务状态已更新为 GENERATING，但 Worker 进程宕机。

### 第一版处理

第一版可以通过人工 SQL 或接口重置状态：

```sql
UPDATE settle_file_task
SET status = 'FAILED'
WHERE status = 'GENERATING'
  AND updated_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE);
```

后续阶段增加定时任务自动扫描超时的 GENERATING 任务。

---

## 22. 日志设计

## 22.1 Producer 日志

```text
[MQ-PRODUCER] send file generate task start, taskNo=TASK202606260001
[MQ-PRODUCER] send file generate task success, taskNo=TASK202606260001, msgId=xxx
[MQ-PRODUCER] send file generate task failed, taskNo=TASK202606260001, error=xxx
```

## 22.2 Consumer 日志

```text
[MQ-CONSUMER] receive file generate task, taskNo=TASK202606260001, messageId=MSG202606260001
[MQ-CONSUMER] task already generated, skip, taskNo=TASK202606260001
[MQ-CONSUMER] task status not allow generate, taskNo=TASK202606260001, status=GENERATING
[MQ-CONSUMER] consume success, taskNo=TASK202606260001
[MQ-CONSUMER] consume failed, taskNo=TASK202606260001, error=xxx
```

## 22.3 Worker 日志

```text
[WORKER] start generate file, taskNo=TASK202606260001, memberId=0001
[WORKER] update status to GENERATING success, taskNo=TASK202606260001
[WORKER] local file generated, taskNo=TASK202606260001, path=/tmp/exchange-clear/...
[WORKER] minio upload success, taskNo=TASK202606260001, objectName=...
[WORKER] save file metadata success, taskNo=TASK202606260001
[WORKER] task generated success, taskNo=TASK202606260001
```

---

## 23. 测试方案

## 23.1 功能测试

### 测试 1：创建任务后自动发送 MQ

步骤：

```text
1. 初始化 10 个会员、每会员 10000 条成交数据
2. 调用 /api/tasks/create
3. 查询 settle_file_task
4. 观察任务状态是否从 INIT 变为 SENT
5. RocketMQ Dashboard 查看消息
```

预期：

```text
任务创建成功，并成功发送 MQ。
```

### 测试 2：Worker 消费生成文件

步骤：

```text
1. 启动 Consumer
2. 调用 /api/tasks/create
3. 等待 Worker 消费
4. 查询任务状态
5. 查询 settle_file
6. 查看 MinIO 文件
```

预期：

```text
任务状态变为 GENERATED，MinIO 中存在文件，settle_file 中存在元数据。
```

### 测试 3：重复消息幂等

步骤：

```text
1. 对同一个 taskNo 手动调用 /api/tasks/{taskNo}/resend
2. 或者重复发送相同消息
3. 观察 Worker 日志
4. 查询 settle_file 记录数量
```

预期：

```text
Worker 识别任务已 GENERATED，不重复生成文件，settle_file 不产生重复记录。
```

### 测试 4：消费失败重试

步骤：

```text
1. 临时关闭 MinIO
2. 创建文件生成任务
3. Worker 消费失败
4. 查询任务状态为 FAILED
5. 恢复 MinIO
6. 等待 RocketMQ 重试或手动重投
7. 查询任务最终变为 GENERATED
```

预期：

```text
失败后可重试，恢复后最终生成成功。
```

### 测试 5：多个 Worker 并行消费

步骤：

```text
1. 启动 3 个应用实例，端口分别为 8081、8082、8083
2. 三个实例使用相同 consumer group
3. 初始化 30 个会员
4. 创建 30 个任务
5. 观察不同实例日志
```

预期：

```text
不同 Worker 实例都能消费到任务，任务被并行处理。
```

---

## 23.2 并发测试

### 场景

```text
会员数量：100
每会员成交数据：10000
总任务数：100
Worker 实例数：3
每实例消费线程数：2
```

### 观察指标

1. 任务总耗时。
2. 单任务平均耗时。
3. 任务成功数。
4. 任务失败数。
5. RocketMQ 消息积压。
6. MySQL CPU 和连接数。
7. MinIO 上传耗时。
8. JVM 内存使用。

---

## 24. 验收标准

## 24.1 功能验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 可以启动 RocketMQ | 必须 |
| 2 | 应用可以连接 RocketMQ | 必须 |
| 3 | 创建任务后可以发送 MQ | 必须 |
| 4 | 任务发送成功后状态变为 SENT | 必须 |
| 5 | Worker 可以消费消息 | 必须 |
| 6 | Worker 消费后可以生成文件 | 必须 |
| 7 | 文件可以上传 MinIO | 必须 |
| 8 | 文件元数据可以写入 settle_file | 必须 |
| 9 | 任务成功后状态变为 GENERATED | 必须 |
| 10 | 消费失败后任务状态变为 FAILED | 必须 |
| 11 | 消费失败可以触发 MQ 重试 | 必须 |
| 12 | 重复消费不会重复生成文件 | 必须 |
| 13 | 多个 Worker 实例可以并行消费 | 必须 |
| 14 | 支持单任务重投 | 必须 |
| 15 | 支持批量投递任务 | 建议 |

## 24.2 技术验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | Producer 和 Consumer 代码分层清晰 | 必须 |
| 2 | 消息体字段完整 | 必须 |
| 3 | Topic、Tag、Group 命名清晰 | 必须 |
| 4 | Consumer 具备幂等判断 | 必须 |
| 5 | Worker 使用条件更新抢占任务 | 必须 |
| 6 | 任务状态流转清晰 | 必须 |
| 7 | 失败异常能触发 MQ 重试 | 必须 |
| 8 | 日志中包含 taskNo | 必须 |
| 9 | 可通过 RocketMQ Dashboard 查看消息 | 建议 |
| 10 | 可启动多个 Worker 实例 | 必须 |

---

## 25. 开发计划

## 25.1 Day 1：接入 RocketMQ

完成：

1. Docker Compose 增加 RocketMQ。
2. Spring Boot 引入 RocketMQ Starter。
3. 配置 name-server。
4. 启动应用验证 Producer/Consumer 可用。
5. 创建测试 Topic。

验收：

```text
RocketMQ 正常启动，应用可连接 RocketMQ。
```

## 25.2 Day 2：实现 Producer

完成：

1. 新增 FileGenerateTaskMessage。
2. 新增 FileGenerateTaskProducer。
3. TaskService 创建任务后发送 MQ。
4. 增加任务状态 SENT、SEND_FAILED。
5. 记录 last_message_id、last_send_time。

验收：

```text
调用 /api/tasks/create 后，可以在 RocketMQ 中看到消息，任务状态为 SENT。
```

## 25.3 Day 3：实现 Consumer 和 Worker

完成：

1. 新增 FileGenerateTaskConsumer。
2. 新增 FileGenerateWorker。
3. Worker 查询任务并执行文件生成。
4. 成功后更新任务状态 GENERATED。
5. 失败后更新任务状态 FAILED。

验收：

```text
Worker 能消费 MQ 消息并成功生成文件。
```

## 25.4 Day 4：实现幂等和重试

完成：

1. Worker 根据任务状态判断是否允许执行。
2. 使用条件更新抢占任务。
3. 文件元数据唯一键防重。
4. 消费失败抛异常触发 MQ 重试。
5. 实现 retry_count。

验收：

```text
重复发送同一条任务消息不会重复生成文件。
```

## 25.5 Day 5：实现重投和批量发送

完成：

1. `/api/tasks/{taskNo}/resend`。
2. `/api/tasks/send-batch`。
3. 支持 SEND_FAILED、FAILED 任务重投。
4. 保留同步生成接口作为调试入口。

验收：

```text
失败任务可以人工重投并最终生成成功。
```

## 25.6 Day 6：多 Worker 并行测试

完成：

1. 启动多个应用实例。
2. 使用相同 Consumer Group。
3. 批量创建 100 个任务。
4. 观察任务是否分布在多个实例上执行。
5. 记录任务耗时。

验收：

```text
多个 Worker 实例能够并行消费并生成文件。
```

## 25.7 Day 7：整理文档和演示

完成：

1. 更新 README。
2. 更新接口文档。
3. 更新启动脚本。
4. 补充 RocketMQ Dashboard 使用说明。
5. 补充测试用例。
6. 补充第二阶段总结。

---

## 26. 第二阶段演示流程

### 26.1 启动环境

```bash
docker-compose up -d
```

启动：

1. MySQL。
2. MinIO。
3. RocketMQ NameServer。
4. RocketMQ Broker。
5. RocketMQ Dashboard。

### 26.2 启动应用

单实例：

```bash
java -jar exchange-clear.jar --server.port=8080
```

多实例：

```bash
java -jar exchange-clear.jar --server.port=8081
java -jar exchange-clear.jar --server.port=8082
java -jar exchange-clear.jar --server.port=8083
```

### 26.3 初始化数据

```http
POST http://localhost:8080/api/mock/init
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 30,
  "tradeCountPerMember": 10000
}
```

### 26.4 创建任务并发送 MQ

```http
POST http://localhost:8080/api/tasks/create
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

### 26.5 查询任务状态

```http
GET http://localhost:8080/api/tasks?settleDate=2026-06-26
```

观察状态：

```text
INIT -> SENT -> GENERATING -> GENERATED
```

### 26.6 查询文件列表

```http
GET http://localhost:8080/api/files?settleDate=2026-06-26&memberId=0001
```

### 26.7 下载文件

```http
GET http://localhost:8080/api/files/{fileNo}/download
```

### 26.8 重投失败任务

```http
POST http://localhost:8080/api/tasks/{taskNo}/resend
```

---

## 27. 后续演进方向

## 27.1 第三阶段：微服务拆分

将单体中的模块拆分为：

```text
settle-task-service
settle-worker-service
settle-file-service
settle-download-service
settle-mock-service
```

并引入：

```text
Nacos + Gateway + OpenFeign
```

## 27.2 第四阶段：Redis 高并发下载保护

引入 Redis，实现：

1. 文件列表缓存。
2. 文件元数据缓存。
3. 下载 Token。
4. 会员级限流。
5. 分布式锁。

## 27.3 第五阶段：一致性补偿

引入：

1. 本地消息表。
2. 定时补偿任务。
3. MinIO 与 MySQL 文件状态对账。
4. 超时 GENERATING 任务恢复。
5. 死信队列处理。

## 27.4 第六阶段：监控压测

引入：

1. Prometheus。
2. Grafana。
3. JMeter / k6。
4. RocketMQ 积压监控。
5. Worker 生成耗时监控。
6. JVM 内存和 GC 监控。

---

## 28. 简历描述建议

第二阶段完成后，可以在简历中这样描述：

```text
在结算文件生成基础闭环之上，引入 RocketMQ 对文件生成任务进行异步化改造。任务创建后不再同步生成文件，而是写入任务表并发送 MQ 消息，由 Worker 消费任务后执行成交文件生成、MD5 校验、MinIO 上传和文件元数据入库。系统支持多个 Worker 实例使用同一 Consumer Group 并行消费任务，并通过任务状态机、数据库唯一键和条件更新机制解决 MQ 重复消费和并发消费导致的文件重复生成问题。
```

项目亮点可以写：

```text
1. 使用 RocketMQ 将文件生成任务异步化，实现任务削峰和 Worker 横向扩展。
2. 设计 INIT、SENT、GENERATING、GENERATED、FAILED、SEND_FAILED 状态机，清晰管理任务生命周期。
3. 基于 taskNo 和 settleDate + memberId + fileType + version 业务唯一键实现消费幂等。
4. 使用条件更新抢占任务执行权，避免多个 Worker 并发消费同一任务导致重复生成。
5. 支持 RocketMQ 消费失败自动重试和失败任务人工重投，提高任务处理可靠性。
6. 支持启动多个 Worker 实例并行消费任务，提升结算文件生成效率。
```

---

## 29. 面试可讲问题

### 29.1 为什么引入 RocketMQ？

结算文件生成是耗时任务，如果在 HTTP 请求中同步生成，会导致接口响应慢、调用方阻塞、服务线程被长时间占用。引入 RocketMQ 后，任务创建和文件生成解耦，创建任务接口只负责写任务和发送消息，真正的文件生成由 Worker 异步消费完成，从而实现削峰填谷和 Worker 横向扩展。

### 29.2 如何保证 MQ 重复消费不会重复生成文件？

通过业务状态机和唯一键保证幂等。Worker 消费消息后先根据 taskNo 查询任务，如果任务已经是 GENERATED，直接返回成功。如果任务是 SENT 或 FAILED，则通过条件更新将状态改为 GENERATING，只有更新成功的 Worker 才能继续生成文件。同时，settle_file 表上有 settleDate + memberId + fileType + version 的唯一索引，避免重复写入文件元数据。

### 29.3 多个 Worker 如何并行处理？

多个 Worker 实例使用相同的 Consumer Group 订阅同一个 Topic。RocketMQ 会在 Consumer Group 内对消息进行负载均衡，不同任务会被分发到不同 Worker 实例。任务粒度按 settleDate + memberId + fileType 拆分，因此多个会员的文件可以并行生成。

### 29.4 Worker 处理失败怎么办？

Worker 捕获异常后，会将任务状态更新为 FAILED，并记录 error_message 和 retry_count，然后继续抛出异常，让 RocketMQ 触发消费重试。如果重试后成功，任务状态更新为 GENERATED；如果超过最大重试次数，可以进入死信队列或通过人工重投接口处理。

### 29.5 为什么不用 RocketMQ MessageId 做幂等？

RocketMQ 的 MessageId 只能代表某次消息投递，业务上同一个任务可能被多次发送或重投，因此幂等应该基于业务唯一键，而不是消息 ID。本项目使用 taskNo 和 settleDate + memberId + fileType + version 作为业务幂等依据。

### 29.6 任务状态为什么要有 SENT？

SENT 用于区分“任务已创建但未发送”和“任务已发送等待消费”。如果没有 SENT 状态，就无法判断任务是否已经成功投递到 MQ，也不方便处理 MQ 发送失败、批量重投和任务排查。

---

## 30. 总结

第二阶段的核心目标是将第一阶段的同步文件生成改造为 RocketMQ 异步任务模型。

完成第二阶段后，系统具备以下能力：

1. 任务创建后异步生成文件。
2. RocketMQ 承载文件生成任务。
3. Worker 消费任务并生成文件。
4. 支持多个 Worker 并行消费。
5. 支持 MQ 消费失败重试。
6. 支持消费幂等，避免重复生成。
7. 支持失败任务人工重投。
8. 保留后续拆分微服务和引入 Redis 的扩展边界。

这一阶段是项目从“单体业务闭环”向“分布式任务处理系统”演进的关键一步。

后续继续引入微服务、Redis、限流、补偿、监控后，项目就可以完整体现大厂 Java 后端常见的分布式高并发能力。
