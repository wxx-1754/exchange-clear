# ExchangeClear 第三阶段：拆分微服务详细设计文档

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第三阶段的开发。

前两个阶段已经完成：

```text
第一阶段：单体闭环
    - 模拟结算数据
    - 创建文件生成任务
    - 同步生成结算文件
    - 上传 MinIO
    - 写入文件元数据
    - 查询与下载文件

第二阶段：引入 RocketMQ
    - 任务创建后发送 MQ
    - Worker 消费任务并生成文件
    - 支持 MQ 重试
    - 支持消费幂等
    - 支持多个 Worker 并行消费
```

第三阶段的核心目标是：

> 将当前单体应用按照职责拆分为多个微服务，并接入 Nacos、Gateway、OpenFeign，形成标准的 Spring Cloud Alibaba 微服务架构。

第三阶段完成后，系统将从“单体 + MQ 异步任务”升级为：

```text
Gateway 统一入口
    ↓
Nacos 服务注册发现
    ↓
多个微服务独立部署
    ↓
OpenFeign 服务间调用
    ↓
RocketMQ 异步任务分发
    ↓
Worker 服务独立扩缩容
```

---

## 2. 第三阶段建设目标

### 2.1 业务目标

1. 拆分 `task-service`，负责结算文件任务创建、任务状态管理、任务投递。
2. 拆分 `file-service`，负责文件元数据管理、文件状态管理、文件查询。
3. 拆分 `worker-service`，负责消费 RocketMQ 消息并生成文件。
4. 拆分 `download-service`，负责文件下载、下载次数更新和后续下载限流扩展。
5. 保留第一阶段和第二阶段已有的完整业务流程。
6. 保证拆分后业务链路仍然可以跑通。
7. 为后续 Redis 缓存、限流、审计、补偿、监控打基础。

### 2.2 技术目标

1. 引入 Nacos 作为服务注册发现中心。
2. 引入 Spring Cloud Gateway 作为统一入口。
3. 引入 OpenFeign 作为服务间 HTTP 调用方式。
4. 将单体项目拆分为 Maven 多模块工程。
5. 每个服务可以独立启动、独立端口、独立注册到 Nacos。
6. Gateway 根据路径将请求路由到对应服务。
7. Worker 服务通过 RocketMQ 消费任务，不再由 Controller 直接触发生成。
8. Worker 服务通过 OpenFeign 调用 file-service 保存文件元数据。
9. download-service 通过 OpenFeign 调用 file-service 查询文件元数据。
10. task-service 通过 OpenFeign 或数据库更新方式管理任务状态。
11. 服务之间通过 DTO 交互，不直接共享数据库实体对象。
12. 保留服务边界，为后续数据库按服务拆分预留空间。

---

## 3. 第三阶段范围

### 3.1 本阶段要做

1. 将项目改造成 Maven 多模块结构。
2. 新增 `exchange-common` 公共模块。
3. 新增 `exchange-gateway` 网关服务。
4. 拆分 `settle-task-service`。
5. 拆分 `settle-file-service`。
6. 拆分 `settle-worker-service`。
7. 拆分 `settle-download-service`。
8. 可选拆分 `settle-mock-service`，用于模拟数据生成。
9. 接入 Nacos 注册中心。
10. 接入 Spring Cloud Gateway。
11. 接入 OpenFeign。
12. 调整 RocketMQ Producer 和 Consumer 所在服务。
13. 调整服务间调用链路。
14. 调整配置文件和启动脚本。
15. 输出微服务版本的验收流程。

### 3.2 本阶段暂不做

1. 暂不做数据库物理拆分。
2. 暂不做分布式事务框架，例如 Seata。
3. 暂不引入 Redis。
4. 暂不实现下载限流。
5. 暂不实现下载 Token。
6. 暂不实现统一认证授权中心。
7. 暂不实现灰度发布。
8. 暂不实现 Kubernetes 部署。
9. 暂不实现 Prometheus + Grafana 完整监控。
10. 暂不实现 SkyWalking 链路追踪。

这些能力放到后续阶段逐步增强。

---

## 4. 微服务拆分原则

### 4.1 按业务职责拆分

服务拆分不应只是为了“看起来像微服务”，而应围绕业务职责进行拆分。

本项目拆分原则如下：

| 服务 | 核心职责 |
|---|---|
| settle-task-service | 管理文件生成任务 |
| settle-worker-service | 执行文件生成任务 |
| settle-file-service | 管理文件元数据 |
| settle-download-service | 提供文件下载能力 |
| settle-mock-service | 模拟结算数据和会员数据 |
| exchange-gateway | 统一入口和路由转发 |
| exchange-common | 公共 DTO、枚举、异常、工具类 |

### 4.2 避免循环调用

服务之间应避免循环依赖。

推荐调用关系：

```text
settle-task-service
    ↓ 发送 MQ
RocketMQ
    ↓ 消费
settle-worker-service
    ↓ OpenFeign
settle-file-service

settle-download-service
    ↓ OpenFeign
settle-file-service
```

不推荐：

```text
file-service 调 task-service
worker-service 调 download-service
download-service 调 task-service
task-service 调 download-service
```

### 4.3 服务间只传 DTO

服务间调用不要直接暴露数据库实体对象。

推荐：

```text
CreateFileRequest
FileMetadataDTO
TaskStatusUpdateRequest
FileQueryDTO
```

不推荐：

```text
SettleFileEntity
SettleFileTaskEntity
TradeRecordEntity
```

原因：

1. 降低服务间耦合。
2. 避免数据库字段变更影响外部服务。
3. 方便后续服务独立演进。
4. 方便后续拆分数据库。

### 4.4 当前阶段可以共享数据库

第三阶段先拆服务，不拆数据库。

理由：

1. 降低改造难度。
2. 避免过早引入分布式事务问题。
3. 当前项目重点是服务拆分、注册发现、网关路由、服务间调用。
4. 后续可以逐步演进为按服务拆库。

当前阶段数据库使用方式：

| 服务 | 访问表 |
|---|---|
| settle-task-service | settle_file_task、settle_member 可选 |
| settle-worker-service | trade_record、settle_file_task 可选 |
| settle-file-service | settle_file |
| settle-download-service | 原则上不直接访问表，通过 file-service 查询 |
| settle-mock-service | settle_member、trade_record |

推荐逐步收敛为：

```text
download-service 不直接查 settle_file
worker-service 保存文件元数据必须调用 file-service
```

---

## 5. 总体架构设计

### 5.1 第三阶段架构图

```text
                        ┌────────────────────┐
                        │   前端 / Postman    │
                        └─────────┬──────────┘
                                  │ HTTP
                                  ▼
                        ┌────────────────────┐
                        │  exchange-gateway  │
                        │ Spring Cloud Gateway│
                        └─────────┬──────────┘
                                  │ lb://服务名
               ┌──────────────────┼──────────────────┐
               ▼                  ▼                  ▼
┌────────────────────────┐ ┌─────────────────────┐ ┌────────────────────────┐
│ settle-task-service     │ │ settle-file-service  │ │ settle-download-service │
│ 任务服务                 │ │ 文件元数据服务         │ │ 文件下载服务             │
└───────────┬────────────┘ └──────────┬──────────┘ └───────────┬────────────┘
            │                          ▲                        │
            │ 发送 MQ                   │ OpenFeign              │ OpenFeign
            ▼                          │                        ▼
┌────────────────────────┐             │              ┌─────────────────────┐
│       RocketMQ          │             │              │ settle-file-service  │
│ file.generate.task      │             │              └─────────────────────┘
└───────────┬────────────┘             │
            │                          │
            ▼                          │
┌────────────────────────┐             │
│ settle-worker-service   │─────────────┘
│ 文件生成 Worker          │ OpenFeign 保存文件元数据
└───────────┬────────────┘
            │
       ┌────┴─────┐
       ▼          ▼
┌──────────┐ ┌──────────┐
│  MySQL   │ │  MinIO   │
└──────────┘ └──────────┘

所有服务注册到 Nacos：
exchange-gateway
settle-task-service
settle-file-service
settle-worker-service
settle-download-service
settle-mock-service
```

### 5.2 核心调用链路

#### 5.2.1 创建任务链路

```text
客户端
  ↓
Gateway
  ↓
settle-task-service
  ↓
写入 settle_file_task
  ↓
发送 RocketMQ file.generate.task 消息
  ↓
返回任务创建结果
```

#### 5.2.2 文件生成链路

```text
RocketMQ
  ↓
settle-worker-service 消费消息
  ↓
查询任务信息
  ↓
查询 trade_record
  ↓
生成 CSV 文件
  ↓
上传 MinIO
  ↓
OpenFeign 调用 settle-file-service
  ↓
写入 settle_file 文件元数据
  ↓
更新任务状态为 GENERATED
```

#### 5.2.3 文件查询链路

```text
客户端
  ↓
Gateway
  ↓
settle-file-service
  ↓
查询 settle_file
  ↓
返回文件列表
```

#### 5.2.4 文件下载链路

```text
客户端
  ↓
Gateway
  ↓
settle-download-service
  ↓ OpenFeign
settle-file-service 查询文件元数据
  ↓
settle-download-service 从 MinIO 读取文件流
  ↓
返回文件流
  ↓ OpenFeign
settle-file-service 更新 download_count
```

---

## 6. Maven 多模块设计

### 6.1 工程结构

```text
exchange-clear
├── pom.xml
├── exchange-common
│   └── pom.xml
├── exchange-gateway
│   └── pom.xml
├── settle-task-service
│   └── pom.xml
├── settle-file-service
│   └── pom.xml
├── settle-worker-service
│   └── pom.xml
├── settle-download-service
│   └── pom.xml
├── settle-mock-service
│   └── pom.xml
├── docker-compose.yml
└── sql
    ├── 01_schema.sql
    └── 02_init_data.sql
```

### 6.2 父工程 pom.xml

父工程负责统一管理 Spring Boot、Spring Cloud、Spring Cloud Alibaba、MyBatis、RocketMQ、MinIO 等版本。

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>exchange-clear</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>exchange-common</module>
        <module>exchange-gateway</module>
        <module>settle-task-service</module>
        <module>settle-file-service</module>
        <module>settle-worker-service</module>
        <module>settle-download-service</module>
        <module>settle-mock-service</module>
    </modules>

    <properties>
        <java.version>1.8</java.version>
        <spring.boot.version>2.7.18</spring.boot.version>
        <spring.cloud.version>2021.0.8</spring.cloud.version>
        <spring.cloud.alibaba.version>2021.0.5.0</spring.cloud.alibaba.version>
        <rocketmq.spring.version>2.2.3</rocketmq.spring.version>
        <mybatis.spring.boot.version>2.3.1</mybatis.spring.boot.version>
        <minio.version>8.5.7</minio.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring.boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring.cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring.cloud.alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

---

## 7. 服务拆分设计

## 7.1 exchange-common 公共模块

### 7.1.1 职责

`exchange-common` 不作为独立服务启动，只作为公共依赖被其他服务引用。

职责：

1. 公共返回对象。
2. 公共异常。
3. 公共枚举。
4. 公共 DTO。
5. 公共工具类。
6. Feign API 接口定义，可选。

### 7.1.2 包结构

```text
exchange-common
└── src/main/java/com/example/exchangeclear/common
    ├── result
    │   └── Result.java
    ├── exception
    │   ├── BizException.java
    │   └── ErrorCode.java
    ├── enums
    │   ├── TaskStatusEnum.java
    │   ├── FileStatusEnum.java
    │   └── FileTypeEnum.java
    ├── dto
    │   ├── file
    │   │   ├── FileMetadataDTO.java
    │   │   ├── SaveGeneratedFileRequest.java
    │   │   └── FileQueryRequest.java
    │   ├── task
    │   │   ├── FileGenerateTaskMessage.java
    │   │   └── TaskStatusUpdateRequest.java
    │   └── download
    │       └── DownloadFileDTO.java
    └── util
        ├── IdGenerator.java
        ├── DateUtil.java
        └── Md5Util.java
```

### 7.1.3 注意事项

`exchange-common` 不要放业务 Service 实现。

允许放：

1. DTO。
2. VO。
3. 枚举。
4. 常量。
5. 工具类。
6. 通用异常。
7. Feign Client 接口定义。

不建议放：

1. Mapper。
2. Entity。
3. 业务 Service。
4. Controller。
5. 具体业务实现逻辑。

---

## 7.2 exchange-gateway 网关服务

### 7.2.1 职责

1. 作为所有外部请求统一入口。
2. 根据路径路由到不同服务。
3. 屏蔽后端服务真实端口。
4. 后续扩展统一鉴权、限流、日志、灰度。

### 7.2.2 路由规划

| 外部路径 | 目标服务 |
|---|---|
| /api/tasks/** | settle-task-service |
| /api/files/** | settle-file-service |
| /api/download/** | settle-download-service |
| /api/mock/** | settle-mock-service |

### 7.2.3 application.yml

```yaml
server:
  port: 9000

spring:
  application:
    name: exchange-gateway
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
    gateway:
      discovery:
        locator:
          enabled: true
      routes:
        - id: settle-task-service
          uri: lb://settle-task-service
          predicates:
            - Path=/api/tasks/**

        - id: settle-file-service
          uri: lb://settle-file-service
          predicates:
            - Path=/api/files/**

        - id: settle-download-service
          uri: lb://settle-download-service
          predicates:
            - Path=/api/download/**

        - id: settle-mock-service
          uri: lb://settle-mock-service
          predicates:
            - Path=/api/mock/**
```

### 7.2.4 启动类

```java
@SpringBootApplication
@EnableDiscoveryClient
public class ExchangeGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExchangeGatewayApplication.class, args);
    }
}
```

### 7.2.5 注意事项

Gateway 使用的是 WebFlux，不要引入 `spring-boot-starter-web`，否则可能和 WebFlux 冲突。

推荐依赖：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

---

## 7.3 settle-task-service 任务服务

### 7.3.1 职责

1. 创建文件生成任务。
2. 查询任务列表。
3. 查询任务详情。
4. 发送文件生成 MQ 消息。
5. 更新任务状态。
6. 支持任务重投。
7. 暴露任务相关接口。

### 7.3.2 负责的数据表

```text
settle_file_task
settle_member，可选
```

在第三阶段，`settle_member` 可以暂时仍由 task-service 查询，用于创建任务。

后续可以拆到 member-service。

### 7.3.3 包结构

```text
settle-task-service
└── src/main/java/com/example/exchangeclear/task
    ├── TaskServiceApplication.java
    ├── controller
    │   └── TaskController.java
    ├── service
    │   ├── SettleFileTaskService.java
    │   └── FileGenerateTaskProducer.java
    ├── mapper
    │   └── SettleFileTaskMapper.java
    ├── entity
    │   └── SettleFileTask.java
    ├── dto
    │   ├── CreateTaskRequest.java
    │   ├── CreateTaskResponse.java
    │   └── ResendTaskResponse.java
    └── mq
        └── FileGenerateTaskProducer.java
```

### 7.3.4 核心接口

#### 创建任务

```http
POST /api/tasks/create
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "settleDate": "2026-06-26",
    "fileType": "TRADE",
    "createdCount": 30,
    "sentCount": 30,
    "sendFailedCount": 0,
    "existsCount": 0
  }
}
```

#### 查询任务

```http
GET /api/tasks?settleDate=2026-06-26&status=SENT
```

#### 重投任务

```http
POST /api/tasks/{taskNo}/resend
```

#### 更新任务状态内部接口

worker-service 需要更新任务状态时，可以有两种方式：

方案一：worker-service 直接访问 `settle_file_task` 表。

方案二：worker-service 通过 OpenFeign 调用 task-service 内部接口。

第三阶段推荐先采用方案一，降低链路复杂度；后续再收敛为方案二。

如果采用方案二，可设计：

```http
POST /internal/tasks/{taskNo}/status
```

请求：

```json
{
  "fromStatus": "SENT",
  "toStatus": "GENERATING",
  "errorMessage": null
}
```

---

## 7.4 settle-file-service 文件元数据服务

### 7.4.1 职责

1. 保存生成后的文件元数据。
2. 查询文件列表。
3. 查询文件详情。
4. 查询文件校验信息。
5. 更新下载次数。
6. 维护文件状态。
7. 后续扩展发布、撤销、重发。

### 7.4.2 负责的数据表

```text
settle_file
```

### 7.4.3 包结构

```text
settle-file-service
└── src/main/java/com/example/exchangeclear/file
    ├── FileServiceApplication.java
    ├── controller
    │   ├── FileController.java
    │   └── InternalFileController.java
    ├── service
    │   └── SettleFileService.java
    ├── mapper
    │   └── SettleFileMapper.java
    ├── entity
    │   └── SettleFile.java
    └── dto
        ├── SaveGeneratedFileRequest.java
        ├── FileMetadataDTO.java
        └── FileQueryRequest.java
```

### 7.4.4 对外接口

#### 查询文件列表

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

#### 查询文件详情

```http
GET /api/files/{fileNo}
```

#### 查询校验信息

```http
GET /api/files/{fileNo}/checksum
```

### 7.4.5 内部接口

#### 保存生成文件元数据

```http
POST /internal/files/generated
```

请求：

```json
{
  "taskNo": "TASK202606260001",
  "settleDate": "2026-06-26",
  "memberId": "0001",
  "fileType": "TRADE",
  "fileName": "trade_0001_20260626.csv",
  "fileSize": 102400,
  "fileMd5": "e10adc3949ba59abbe56e057f20f883e",
  "storageBucket": "exchange-clear",
  "storagePath": "20260626/0001/v1/TRADE/trade_0001_20260626.csv",
  "version": 1,
  "status": "GENERATED"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileNo": "FILE202606260001"
  }
}
```

#### 更新下载次数

```http
POST /internal/files/{fileNo}/download-count/increase
```

---

## 7.5 settle-worker-service 文件生成 Worker 服务

### 7.5.1 职责

1. 消费 RocketMQ 文件生成任务消息。
2. 查询任务信息。
3. 根据任务生成 CSV 文件。
4. 计算 MD5。
5. 上传 MinIO。
6. 通过 OpenFeign 调用 file-service 保存文件元数据。
7. 更新任务状态为 GENERATED 或 FAILED。
8. 支持多个 Worker 实例并行部署。

### 7.5.2 负责的数据表

当前阶段为了降低复杂度，worker-service 可以访问：

```text
trade_record
settle_file_task
```

同时通过 OpenFeign 调用 file-service 写入：

```text
settle_file
```

后续更严格拆分时，worker-service 可以不直接访问任务表，而是通过 task-service 操作任务状态。

### 7.5.3 包结构

```text
settle-worker-service
└── src/main/java/com/example/exchangeclear/worker
    ├── WorkerServiceApplication.java
    ├── mq
    │   └── FileGenerateTaskConsumer.java
    ├── service
    │   └── FileGenerateWorker.java
    ├── generator
    │   ├── FileGenerator.java
    │   ├── TradeCsvFileGenerator.java
    │   └── FileGenerateContext.java
    ├── storage
    │   ├── ObjectStorageService.java
    │   └── MinioStorageService.java
    ├── mapper
    │   ├── TradeRecordMapper.java
    │   └── SettleFileTaskMapper.java
    ├── entity
    │   ├── TradeRecord.java
    │   └── SettleFileTask.java
    └── feign
        └── FileServiceClient.java
```

### 7.5.4 MQ 消费

```java
@Component
@RocketMQMessageListener(
    topic = "file.generate.task",
    consumerGroup = "exchange-clear-file-generate-consumer-group",
    selectorExpression = "*",
    consumeThreadNumber = 4,
    maxReconsumeTimes = 3
)
public class FileGenerateTaskConsumer implements RocketMQListener<FileGenerateTaskMessage> {

    private final FileGenerateWorker fileGenerateWorker;

    @Override
    public void onMessage(FileGenerateTaskMessage message) {
        fileGenerateWorker.handle(message);
    }
}
```

### 7.5.5 调用 file-service 保存元数据

```java
@FeignClient(name = "settle-file-service", contextId = "fileServiceClient")
public interface FileServiceClient {

    @PostMapping("/internal/files/generated")
    Result<SaveGeneratedFileResponse> saveGeneratedFile(@RequestBody SaveGeneratedFileRequest request);

    @GetMapping("/internal/files/{fileNo}")
    Result<FileMetadataDTO> getFile(@PathVariable("fileNo") String fileNo);
}
```

### 7.5.6 Worker 处理流程

```text
RocketMQ 投递消息
    ↓
worker-service 消费消息
    ↓
根据 taskNo 查询任务
    ↓
任务状态幂等判断
    ↓
条件更新任务状态为 GENERATING
    ↓
查询 trade_record
    ↓
生成 CSV 文件
    ↓
计算 MD5
    ↓
上传 MinIO
    ↓
OpenFeign 调用 file-service 保存文件元数据
    ↓
更新任务状态为 GENERATED
```

---

## 7.6 settle-download-service 下载服务

### 7.6.1 职责

1. 对外提供文件下载接口。
2. 通过 OpenFeign 调用 file-service 查询文件元数据。
3. 从 MinIO 获取文件流。
4. 返回文件流给客户端。
5. 调用 file-service 更新下载次数。
6. 后续扩展下载 Token、限流、审计。

### 7.6.2 包结构

```text
settle-download-service
└── src/main/java/com/example/exchangeclear/download
    ├── DownloadServiceApplication.java
    ├── controller
    │   └── DownloadController.java
    ├── service
    │   └── DownloadService.java
    ├── storage
    │   ├── ObjectStorageService.java
    │   └── MinioStorageService.java
    └── feign
        └── FileServiceClient.java
```

### 7.6.3 下载接口

```http
GET /api/download/files/{fileNo}
```

### 7.6.4 下载流程

```text
客户端请求 /api/download/files/{fileNo}
    ↓
Gateway 路由到 download-service
    ↓
download-service 调用 file-service 查询文件元数据
    ↓
判断文件是否存在
    ↓
判断文件状态是否可下载
    ↓
从 MinIO 获取文件流
    ↓
写入 HTTP Response
    ↓
调用 file-service 更新 download_count
```

### 7.6.5 为什么下载接口放在 download-service

文件元数据查询和文件下载是两个不同职责：

| 能力 | 所属服务 |
|---|---|
| 文件元数据管理 | file-service |
| 文件流下载 | download-service |

原因：

1. 下载服务后续会承载限流、Token、审计等能力。
2. 下载服务可能成为高并发入口，需要独立扩容。
3. 文件服务保持纯元数据管理职责，避免和大文件 IO 混在一起。

---

## 7.7 settle-mock-service 模拟数据服务

### 7.7.1 职责

1. 初始化会员数据。
2. 初始化模拟成交数据。
3. 清理测试数据。
4. 模拟结算完成事件。

### 7.7.2 是否必须拆分

建议第三阶段拆分出来。

理由：

1. 与核心业务服务解耦。
2. 方便测试环境造数。
3. 后续可以替换为真实结算系统事件输入。

### 7.7.3 接口

```http
POST /api/mock/init
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 30,
  "tradeCountPerMember": 10000
}
```

---

## 8. Nacos 设计

## 8.1 Nacos 职责

第三阶段只使用 Nacos 的服务注册发现能力。

暂不使用：

1. Nacos 配置中心。
2. Nacos 权限管理。
3. 多命名空间隔离。

后续可以扩展为配置中心。

## 8.2 服务注册列表

所有服务启动后，应在 Nacos 控制台看到：

```text
exchange-gateway
settle-task-service
settle-file-service
settle-worker-service
settle-download-service
settle-mock-service
```

## 8.3 Nacos Docker 配置

```yaml
nacos:
  image: nacos/nacos-server:v2.3.2
  container_name: exchange-clear-nacos
  environment:
    MODE: standalone
  ports:
    - "8848:8848"
    - "9848:9848"
```

访问地址：

```text
http://localhost:8848/nacos
```

默认账号密码：

```text
nacos / nacos
```

## 8.4 服务配置示例

每个服务配置：

```yaml
spring:
  application:
    name: settle-task-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
```

启动类添加：

```java
@SpringBootApplication
@EnableDiscoveryClient
public class TaskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
```

---

## 9. Gateway 设计

## 9.1 网关职责

Gateway 作为统一入口，所有外部请求都通过网关访问。

外部不直接访问：

```text
settle-task-service:8101
settle-file-service:8102
settle-worker-service:8103
settle-download-service:8104
settle-mock-service:8105
```

外部统一访问：

```text
http://localhost:9000
```

## 9.2 路由规则

| 请求路径 | 路由服务 |
|---|---|
| /api/tasks/** | settle-task-service |
| /api/files/** | settle-file-service |
| /api/download/** | settle-download-service |
| /api/mock/** | settle-mock-service |

## 9.3 application.yml

```yaml
server:
  port: 9000

spring:
  application:
    name: exchange-gateway
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
    gateway:
      routes:
        - id: settle-task-service
          uri: lb://settle-task-service
          predicates:
            - Path=/api/tasks/**

        - id: settle-file-service
          uri: lb://settle-file-service
          predicates:
            - Path=/api/files/**

        - id: settle-download-service
          uri: lb://settle-download-service
          predicates:
            - Path=/api/download/**

        - id: settle-mock-service
          uri: lb://settle-mock-service
          predicates:
            - Path=/api/mock/**
```

## 9.4 后续可扩展能力

后续可以在 Gateway 增加：

1. 统一鉴权。
2. 请求日志。
3. TraceId 生成。
4. 全局限流。
5. IP 黑白名单。
6. 跨域处理。
7. 灰度路由。
8. 熔断降级。

---

## 10. OpenFeign 设计

## 10.1 OpenFeign 使用原则

1. 只用于同步的轻量级服务间调用。
2. 不用于大文件流转。
3. 不用于长耗时任务执行。
4. 文件生成任务仍然通过 RocketMQ 异步触发。
5. download-service 下载文件时不通过 file-service 转发文件流，只查询元数据。

## 10.2 Feign 调用关系

| 调用方 | 被调用方 | 用途 |
|---|---|---|
| worker-service | file-service | 保存生成后的文件元数据 |
| download-service | file-service | 查询文件元数据、更新下载次数 |
| task-service | file-service，可选 | 查询生成结果，可选 |
| worker-service | task-service，可选 | 更新任务状态，可选 |

## 10.3 worker-service 调 file-service

```java
@FeignClient(name = "settle-file-service", contextId = "workerFileServiceClient")
public interface FileServiceClient {

    @PostMapping("/internal/files/generated")
    Result<SaveGeneratedFileResponse> saveGeneratedFile(@RequestBody SaveGeneratedFileRequest request);
}
```

## 10.4 download-service 调 file-service

```java
@FeignClient(name = "settle-file-service", contextId = "downloadFileServiceClient")
public interface FileServiceClient {

    @GetMapping("/internal/files/{fileNo}")
    Result<FileMetadataDTO> getFileByFileNo(@PathVariable("fileNo") String fileNo);

    @PostMapping("/internal/files/{fileNo}/download-count/increase")
    Result<Boolean> increaseDownloadCount(@PathVariable("fileNo") String fileNo);
}
```

## 10.5 开启 Feign

启动类增加：

```java
@EnableFeignClients
@EnableDiscoveryClient
@SpringBootApplication
public class DownloadServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DownloadServiceApplication.class, args);
    }
}
```

## 10.6 Feign 超时配置

```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 3000
        readTimeout: 5000
```

说明：

1. 查询元数据接口应该较快。
2. 不应通过 Feign 进行文件流传输。
3. 如果 Feign 读超时较长，容易拖垮调用方线程。

---

## 11. RocketMQ 在微服务架构下的调整

## 11.1 Producer 所属服务

Producer 放在：

```text
settle-task-service
```

原因：

1. task-service 负责创建文件生成任务。
2. 任务创建后由 task-service 投递 MQ。
3. 任务状态 SENT / SEND_FAILED 由 task-service 管理。

## 11.2 Consumer 所属服务

Consumer 放在：

```text
settle-worker-service
```

原因：

1. worker-service 专职处理文件生成。
2. worker-service 可多实例独立扩容。
3. worker-service 消费 MQ 后完成文件生成和 MinIO 上传。

## 11.3 Topic 保持不变

```text
file.generate.task
```

## 11.4 Consumer Group 保持不变

```text
exchange-clear-file-generate-consumer-group
```

## 11.5 微服务后的消息流

```text
settle-task-service
    ↓ 发送消息
RocketMQ file.generate.task
    ↓ 消费
settle-worker-service
    ↓ 生成文件
MinIO
    ↓ 保存元数据
settle-file-service
```

---

## 12. 数据库访问设计

## 12.1 当前阶段数据库策略

第三阶段采用：

```text
拆服务，不拆库
```

所有服务暂时共用同一个数据库：

```text
exchange_clear
```

## 12.2 表归属规划

| 表 | 当前访问服务 | 未来归属服务 |
|---|---|---|
| settle_member | mock-service、task-service | member-service，可选 |
| trade_record | mock-service、worker-service | trade-data-service，可选 |
| settle_file_task | task-service、worker-service | task-service |
| settle_file | file-service | file-service |

## 12.3 为什么暂不拆库

原因：

1. 拆库会引入分布式事务问题。
2. 当前阶段目标是服务拆分和调用链路打通。
3. 数据库物理拆分属于后续演进。
4. 过早拆库会显著增加开发复杂度。

## 12.4 后续拆库方向

后续可以演进为：

```text
task_db
    settle_file_task

file_db
    settle_file

trade_db
    trade_record
    settle_member
```

如果拆库后涉及跨库一致性，则需要引入：

1. 本地消息表。
2. 事件驱动。
3. 定时补偿。
4. 对账任务。
5. 避免强依赖分布式事务。

---

## 13. 配置文件设计

## 13.1 settle-task-service 配置

```yaml
server:
  port: 8101

spring:
  application:
    name: settle-task-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
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

exchange-clear:
  mq:
    topic:
      file-generate-task: file.generate.task
```

## 13.2 settle-worker-service 配置

```yaml
server:
  port: 8103

spring:
  application:
    name: settle-worker-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_clear?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

rocketmq:
  name-server: localhost:9876
  consumer:
    group: exchange-clear-file-generate-consumer-group

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: exchange-clear

exchange-clear:
  file:
    local-root-path: /tmp/exchange-clear
    page-size: 1000
```

## 13.3 settle-file-service 配置

```yaml
server:
  port: 8102

spring:
  application:
    name: settle-file-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_clear?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
```

## 13.4 settle-download-service 配置

```yaml
server:
  port: 8104

spring:
  application:
    name: settle-download-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: exchange-clear

feign:
  client:
    config:
      default:
        connectTimeout: 3000
        readTimeout: 5000
```

## 13.5 settle-mock-service 配置

```yaml
server:
  port: 8105

spring:
  application:
    name: settle-mock-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_clear?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
```

---

## 14. Docker Compose 设计

第三阶段需要启动：

1. MySQL。
2. MinIO。
3. RocketMQ NameServer。
4. RocketMQ Broker。
5. RocketMQ Dashboard。
6. Nacos。

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

  nacos:
    image: nacos/nacos-server:v2.3.2
    container_name: exchange-clear-nacos
    restart: always
    environment:
      MODE: standalone
    ports:
      - "8848:8848"
      - "9848:9848"

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

---

## 15. 服务接口设计

## 15.1 task-service 接口

### 创建任务

```http
POST /api/tasks/create
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "settleDate": "2026-06-26",
    "fileType": "TRADE",
    "createdCount": 30,
    "sentCount": 30,
    "sendFailedCount": 0,
    "existsCount": 0
  }
}
```

### 查询任务

```http
GET /api/tasks?settleDate=2026-06-26&status=GENERATED
```

### 重投任务

```http
POST /api/tasks/{taskNo}/resend
```

---

## 15.2 file-service 接口

### 查询文件列表

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

### 查询文件详情

```http
GET /api/files/{fileNo}
```

### 查询文件校验信息

```http
GET /api/files/{fileNo}/checksum
```

### 内部保存文件元数据

```http
POST /internal/files/generated
```

### 内部查询文件元数据

```http
GET /internal/files/{fileNo}
```

### 内部更新下载次数

```http
POST /internal/files/{fileNo}/download-count/increase
```

---

## 15.3 download-service 接口

### 下载文件

```http
GET /api/download/files/{fileNo}
```

注意：

第三阶段建议将下载路径从第一阶段的：

```text
/api/files/{fileNo}/download
```

调整为：

```text
/api/download/files/{fileNo}
```

原因：

1. 文件元数据查询归 file-service。
2. 文件下载归 download-service。
3. 后续下载限流、Token、审计都放在 download-service。

---

## 15.4 mock-service 接口

### 初始化数据

```http
POST /api/mock/init
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 30,
  "tradeCountPerMember": 10000
}
```

---

## 16. 核心流程详细设计

## 16.1 初始化测试数据流程

```text
客户端
  ↓
Gateway: /api/mock/init
  ↓
settle-mock-service
  ↓
写入 settle_member
  ↓
写入 trade_record
  ↓
返回初始化结果
```

## 16.2 创建任务流程

```text
客户端
  ↓
Gateway: /api/tasks/create
  ↓
settle-task-service
  ↓
查询有效会员
  ↓
为每个会员创建 settle_file_task
  ↓
发送 RocketMQ 消息
  ↓
任务状态更新为 SENT
  ↓
返回 createdCount / sentCount
```

## 16.3 Worker 文件生成流程

```text
RocketMQ
  ↓
settle-worker-service
  ↓
查询 settle_file_task
  ↓
状态幂等判断
  ↓
条件更新为 GENERATING
  ↓
查询 trade_record
  ↓
生成 CSV 文件
  ↓
计算 MD5
  ↓
上传 MinIO
  ↓ OpenFeign
settle-file-service 保存文件元数据
  ↓
更新任务状态为 GENERATED
```

## 16.4 文件下载流程

```text
客户端
  ↓
Gateway: /api/download/files/{fileNo}
  ↓
settle-download-service
  ↓ OpenFeign
settle-file-service 查询文件元数据
  ↓
settle-download-service 从 MinIO 获取文件流
  ↓
返回文件流
  ↓ OpenFeign
settle-file-service 更新 download_count
```

---

## 17. 启动顺序

### 17.1 启动基础设施

```bash
docker-compose up -d
```

确保以下组件启动成功：

```text
MySQL: localhost:3306
MinIO: localhost:9000 / 9001
Nacos: localhost:8848
RocketMQ NameServer: localhost:9876
RocketMQ Dashboard: localhost:8088
```

### 17.2 启动服务

推荐启动顺序：

```text
1. exchange-gateway
2. settle-file-service
3. settle-task-service
4. settle-worker-service
5. settle-download-service
6. settle-mock-service
```

也可以任意顺序启动，因为服务会注册到 Nacos。

### 17.3 验证服务注册

访问：

```text
http://localhost:8848/nacos
```

确认服务列表中存在：

```text
exchange-gateway
settle-task-service
settle-file-service
settle-worker-service
settle-download-service
settle-mock-service
```

---

## 18. 验收流程

## 18.1 基础设施验收

| 验收项 | 预期 |
|---|---|
| MySQL 启动 | 可以连接 exchange_clear 数据库 |
| MinIO 启动 | 可以访问控制台 |
| Nacos 启动 | 可以看到服务注册列表 |
| RocketMQ 启动 | Dashboard 可以访问 |
| Gateway 启动 | 可以通过 9000 访问后端服务 |

## 18.2 微服务验收

| 编号 | 验收项 | 预期 |
|---|---|---|
| 1 | 所有服务独立启动 | 每个服务端口独立 |
| 2 | 服务注册到 Nacos | Nacos 控制台可见 |
| 3 | Gateway 路由 task-service | /api/tasks/** 正常 |
| 4 | Gateway 路由 file-service | /api/files/** 正常 |
| 5 | Gateway 路由 download-service | /api/download/** 正常 |
| 6 | Gateway 路由 mock-service | /api/mock/** 正常 |
| 7 | Feign 调用成功 | download-service 可调用 file-service |
| 8 | Worker 调 file-service 成功 | 文件元数据可以保存 |

## 18.3 业务验收

### 步骤 1：初始化数据

```http
POST http://localhost:9000/api/mock/init
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 30,
  "tradeCountPerMember": 10000
}
```

预期：

```text
settle_member 生成 30 条会员数据
trade_record 生成 300000 条成交数据
```

### 步骤 2：创建任务

```http
POST http://localhost:9000/api/tasks/create
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

预期：

```text
生成 30 个任务
发送 30 条 RocketMQ 消息
任务状态变为 SENT
```

### 步骤 3：Worker 消费

预期：

```text
settle-worker-service 消费 MQ 消息
生成 CSV 文件
上传 MinIO
调用 settle-file-service 保存文件元数据
任务状态变为 GENERATED
```

### 步骤 4：查询文件

```http
GET http://localhost:9000/api/files?settleDate=2026-06-26&memberId=0001
```

预期：

```text
返回 0001 会员的 TRADE 文件元数据
```

### 步骤 5：下载文件

```http
GET http://localhost:9000/api/download/files/{fileNo}
```

预期：

```text
可以成功下载 CSV 文件
```

---

## 19. 多实例 Worker 验收

第三阶段应验证 worker-service 可以多实例部署。

### 19.1 启动多个 Worker

```bash
java -jar settle-worker-service.jar --server.port=8201
java -jar settle-worker-service.jar --server.port=8202
java -jar settle-worker-service.jar --server.port=8203
```

三个实例使用相同：

```text
spring.application.name=settle-worker-service
rocketmq.consumer.group=exchange-clear-file-generate-consumer-group
```

### 19.2 验收方式

1. 初始化 100 个会员。
2. 创建 100 个文件生成任务。
3. 观察三个 Worker 的日志。
4. 确认三个实例都有消费任务。
5. 确认所有任务最终变为 GENERATED。

### 19.3 预期结果

```text
多个 worker-service 实例并行消费同一个 Topic 中的任务。
同一任务不会被重复生成。
```

---

## 20. 异常处理设计

## 20.1 Gateway 找不到服务

### 场景

Gateway 路由到某个服务时，Nacos 中没有可用实例。

### 处理

1. 返回 503。
2. 检查目标服务是否启动。
3. 检查服务名是否一致。
4. 检查 Nacos 地址是否正确。

常见错误：

```text
lb://settle-file-service 与 spring.application.name 不一致
```

## 20.2 Feign 调用失败

### 场景

worker-service 调 file-service 保存文件元数据失败。

### 处理

第一版：

1. Worker 捕获异常。
2. 更新任务状态为 FAILED。
3. 抛出异常触发 MQ 重试。

后续：

1. 引入本地消息表。
2. 保存文件元数据失败后进行补偿。
3. 对已上传 MinIO 但未入库的文件进行对账。

## 20.3 Nacos 不可用

### 场景

Nacos 挂掉后，新服务无法注册，Gateway 无法获取最新实例列表。

### 第一版处理

1. 本地开发阶段重启 Nacos。
2. 服务重新注册。

后续生产级方案：

1. Nacos 集群部署。
2. 开启本地缓存。
3. 服务实例健康检查。

## 20.4 Worker 生成成功但 file-service 调用失败

### 场景

CSV 已生成，MinIO 上传成功，但调用 file-service 保存元数据失败。

### 当前处理

1. 任务标记 FAILED。
2. RocketMQ 重试。
3. 重试时可能再次上传同一路径文件。
4. 依赖文件元数据唯一键和任务状态保证幂等。

后续优化：

1. 本地消息表。
2. 文件对账任务。
3. MinIO 文件存在性校验。
4. MD5 比对。

---

## 21. 日志设计

## 21.1 Gateway 日志

建议记录：

1. 请求路径。
2. 路由服务。
3. 响应状态。
4. 请求耗时。

## 21.2 服务日志统一字段

所有服务日志中建议包含：

```text
traceId
serviceName
taskNo
fileNo
memberId
settleDate
```

第三阶段可以先手动在关键日志中打印 taskNo。

后续引入链路追踪后，由系统自动生成 traceId。

## 21.3 Worker 日志示例

```text
[settle-worker-service] receive message, taskNo=TASK202606260001
[settle-worker-service] start generate, taskNo=TASK202606260001, memberId=0001
[settle-worker-service] upload minio success, taskNo=TASK202606260001
[settle-worker-service] call file-service success, taskNo=TASK202606260001, fileNo=FILE202606260001
[settle-worker-service] task generated success, taskNo=TASK202606260001
```

## 21.4 Feign 调用日志

开发环境可以开启 Feign 日志：

```yaml
logging:
  level:
    com.example.exchangeclear: DEBUG

feign:
  client:
    config:
      default:
        loggerLevel: basic
```

---

## 22. 开发计划

## 22.1 Day 1：Maven 多模块改造

完成：

1. 创建父工程。
2. 创建 exchange-common。
3. 创建各服务模块。
4. 迁移公共 DTO、枚举、Result、异常。
5. 各服务可以单独打包。

验收：

```text
mvn clean package 可以成功构建所有模块。
```

## 22.2 Day 2：接入 Nacos

完成：

1. Docker Compose 增加 Nacos。
2. 所有服务加入 Nacos Discovery 依赖。
3. 所有服务配置 spring.application.name。
4. 所有服务启动后注册到 Nacos。

验收：

```text
Nacos 控制台可以看到所有服务。
```

## 22.3 Day 3：接入 Gateway

完成：

1. 创建 exchange-gateway。
2. 配置服务路由。
3. 通过 Gateway 调用 mock、task、file、download 接口。
4. 禁止直接依赖后端服务端口进行演示。

验收：

```text
http://localhost:9000/api/** 可以路由到对应服务。
```

## 22.4 Day 4：拆分 task-service 和 worker-service

完成：

1. 迁移任务创建逻辑到 task-service。
2. 迁移 RocketMQ Producer 到 task-service。
3. 迁移 RocketMQ Consumer 到 worker-service。
4. 迁移文件生成逻辑到 worker-service。
5. Worker 可以消费 task-service 发出的消息。

验收：

```text
task-service 创建任务后，worker-service 可以消费并执行文件生成。
```

## 22.5 Day 5：拆分 file-service

完成：

1. 迁移 settle_file 表相关逻辑到 file-service。
2. 提供保存文件元数据内部接口。
3. 提供文件查询对外接口。
4. worker-service 通过 OpenFeign 调用 file-service 保存元数据。

验收：

```text
worker-service 不再直接插入 settle_file，而是通过 file-service 保存。
```

## 22.6 Day 6：拆分 download-service

完成：

1. 迁移下载逻辑到 download-service。
2. download-service 通过 OpenFeign 查询 file-service。
3. download-service 从 MinIO 获取文件流。
4. download-service 调用 file-service 更新下载次数。

验收：

```text
通过 Gateway 的 /api/download/files/{fileNo} 可以下载文件。
```

## 22.7 Day 7：联调和文档整理

完成：

1. 完整跑通：mock -> task -> MQ -> worker -> file -> download。
2. 验证多个 worker-service 实例。
3. 更新 README。
4. 更新启动脚本。
5. 更新接口文档。
6. 输出第三阶段验收记录。

---

## 23. 第三阶段验收标准

## 23.1 功能验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 拆分 task-service | 必须 |
| 2 | 拆分 file-service | 必须 |
| 3 | 拆分 worker-service | 必须 |
| 4 | 拆分 download-service | 必须 |
| 5 | 接入 Nacos | 必须 |
| 6 | 所有服务注册到 Nacos | 必须 |
| 7 | 接入 Gateway | 必须 |
| 8 | 外部请求统一通过 Gateway | 必须 |
| 9 | 使用 OpenFeign 进行服务间调用 | 必须 |
| 10 | task-service 可以发送 RocketMQ | 必须 |
| 11 | worker-service 可以消费 RocketMQ | 必须 |
| 12 | worker-service 可以调用 file-service 保存元数据 | 必须 |
| 13 | download-service 可以调用 file-service 查询元数据 | 必须 |
| 14 | 文件下载可以正常完成 | 必须 |
| 15 | 多 Worker 实例可以并行消费 | 必须 |

## 23.2 技术验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | Maven 多模块结构清晰 | 必须 |
| 2 | common 模块不包含业务实现 | 必须 |
| 3 | 每个服务独立启动 | 必须 |
| 4 | 每个服务端口独立 | 必须 |
| 5 | 服务名与 Gateway 路由一致 | 必须 |
| 6 | Feign Client 命名清晰 | 必须 |
| 7 | 服务间只传 DTO | 必须 |
| 8 | Worker 不通过 Feign 传输文件流 | 必须 |
| 9 | Gateway 不写业务逻辑 | 必须 |
| 10 | RocketMQ Producer/Consumer 分属不同服务 | 必须 |

---

## 24. 第三阶段演示流程

### 24.1 启动基础设施

```bash
docker-compose up -d
```

### 24.2 启动微服务

```bash
java -jar exchange-gateway.jar
java -jar settle-mock-service.jar
java -jar settle-task-service.jar
java -jar settle-file-service.jar
java -jar settle-worker-service.jar
java -jar settle-download-service.jar
```

### 24.3 查看 Nacos

访问：

```text
http://localhost:8848/nacos
```

确认服务全部注册。

### 24.4 初始化数据

```http
POST http://localhost:9000/api/mock/init
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 30,
  "tradeCountPerMember": 10000
}
```

### 24.5 创建任务

```http
POST http://localhost:9000/api/tasks/create
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

### 24.6 查询文件

```http
GET http://localhost:9000/api/files?settleDate=2026-06-26&memberId=0001
```

### 24.7 下载文件

```http
GET http://localhost:9000/api/download/files/{fileNo}
```

---

## 25. 后续演进方向

第三阶段完成后，可以继续进入第四阶段。

## 25.1 第四阶段：引入 Redis

目标：

1. 文件列表缓存。
2. 文件元数据缓存。
3. 下载 Token。
4. 会员级限流。
5. IP 级限流。
6. 分布式锁。

## 25.2 第五阶段：一致性补偿

目标：

1. 本地消息表。
2. MQ 发送失败补偿。
3. MinIO 与 MySQL 对账。
4. 超时 GENERATING 任务恢复。
5. 死信队列处理。

## 25.3 第六阶段：监控和压测

目标：

1. Prometheus。
2. Grafana。
3. JMeter。
4. RocketMQ 积压监控。
5. Gateway 请求量监控。
6. Feign 调用耗时监控。
7. Worker 文件生成耗时监控。

---

## 26. 简历描述建议

第三阶段完成后，可以在简历中这样描述：

```text
在结算文件生成平台中完成微服务化改造，基于 Spring Cloud Alibaba 将系统拆分为任务服务、文件元数据服务、文件生成 Worker 服务、文件下载服务和模拟数据服务。系统接入 Nacos 实现服务注册发现，接入 Spring Cloud Gateway 作为统一入口，并使用 OpenFeign 完成 Worker 与文件服务、下载服务与文件服务之间的服务间调用。文件生成任务通过 RocketMQ 从任务服务异步分发到 Worker 服务，Worker 服务可多实例部署并行消费任务，实现结算文件生成能力的横向扩展。
```

项目亮点可以写：

```text
1. 按职责将单体系统拆分为 task-service、file-service、worker-service、download-service，明确服务边界。
2. 使用 Nacos 实现服务注册发现，Gateway 统一对外暴露接口，屏蔽后端服务细节。
3. 使用 OpenFeign 实现服务间调用，worker-service 通过 file-service 保存文件元数据，download-service 通过 file-service 查询文件元数据。
4. 将 RocketMQ Producer 放在 task-service，Consumer 放在 worker-service，实现任务编排与任务执行解耦。
5. 支持多个 worker-service 实例使用同一 Consumer Group 并行消费文件生成任务，提高结算文件生成吞吐能力。
```

---

## 27. 面试可讲问题

### 27.1 为什么要拆分 task-service 和 worker-service？

任务编排和任务执行是两个不同职责。task-service 负责创建任务、管理任务状态和发送 MQ；worker-service 负责消费任务、生成文件和上传对象存储。拆分后 worker-service 可以独立扩容，应对结算文件生成高峰。

### 27.2 为什么文件下载要单独拆成 download-service？

文件下载后续会成为高并发入口，需要独立做下载 Token、限流、审计、断点续传和对象存储分流。如果下载逻辑和 file-service 混在一起，会导致元数据管理服务承担大量文件 IO 压力，不利于扩展。

### 27.3 为什么不在第三阶段拆数据库？

第三阶段重点是服务拆分、注册发现、网关路由和服务间调用。如果同时拆数据库，会引入分布式事务和数据一致性问题，复杂度过高。因此先拆服务、共用数据库，后续再结合本地消息表和补偿机制逐步拆库。

### 27.4 Gateway 的作用是什么？

Gateway 作为统一入口，屏蔽后端服务地址，将 `/api/tasks/**`、`/api/files/**`、`/api/download/**` 等路径路由到对应服务。后续还可以在 Gateway 层实现统一鉴权、限流、日志、灰度和跨域处理。

### 27.5 OpenFeign 适合做什么，不适合做什么？

OpenFeign 适合做服务间轻量级同步调用，例如查询文件元数据、保存文件元数据、更新下载次数。不适合传输大文件流或执行长耗时任务。长耗时文件生成任务应该通过 RocketMQ 异步处理。

### 27.6 多个 Worker 如何并行消费？

多个 worker-service 实例使用相同的 RocketMQ Consumer Group 订阅同一个 Topic。RocketMQ 会在同一个 Consumer Group 内进行负载均衡，不同文件生成任务会被不同 Worker 实例消费。由于任务粒度按 `settleDate + memberId + fileType` 拆分，不同会员文件可以并行生成。

---

## 28. 总结

第三阶段的核心目标是把前两个阶段的单体系统改造成标准微服务架构。

完成第三阶段后，系统具备：

1. 多服务独立部署能力。
2. Nacos 服务注册发现能力。
3. Gateway 统一入口能力。
4. OpenFeign 服务间调用能力。
5. RocketMQ 跨服务异步任务能力。
6. Worker 服务独立扩容能力。
7. 文件下载服务独立演进能力。
8. 后续引入 Redis、限流、补偿、监控的清晰边界。

这一阶段完成后，项目已经具备较完整的分布式系统雏形。后续再引入 Redis 高并发保护、一致性补偿和监控压测后，就可以作为一个完整的 Java 分布式高并发项目用于求职展示。
