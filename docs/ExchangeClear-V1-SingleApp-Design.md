# ExchangeClear 第一阶段：单体闭环版详细设计文档

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第一阶段的开发。

第一阶段的核心目标不是直接实现完整的分布式微服务架构，而是先用一个单体 Spring Boot 应用跑通核心业务闭环：

```text
模拟结算数据
    ↓
创建文件生成任务
    ↓
按会员生成结算文件
    ↓
计算文件 MD5
    ↓
上传 MinIO
    ↓
写入文件元数据
    ↓
查询文件列表
    ↓
下载文件
    ↓
校验文件完整性
```

第一阶段完成后，系统应该具备一个可以演示、可以压测、可以继续扩展到 RocketMQ、Redis、微服务和分布式 Worker 的基础版本。

---

## 2. 第一阶段定位

### 2.1 阶段名称

**V1：单体闭环版**

### 2.2 阶段目标

第一阶段只做一件事：

> 使用单体 Spring Boot 项目，完整跑通“结算文件生成、上传、入库、查询、下载、校验”的业务闭环。

### 2.3 为什么先做单体闭环

虽然最终项目目标是分布式高并发系统，但如果一开始就引入微服务、RocketMQ、Redis、网关、注册中心、监控等组件，开发复杂度会显著提升，容易陷入基础设施搭建和调试，反而忽略核心业务。

因此第一阶段先专注于：

1. 业务流程是否合理。
2. 数据模型是否稳定。
3. 文件生成逻辑是否可行。
4. 大数据量写文件是否存在 OOM 风险。
5. 文件上传和下载链路是否完整。
6. 后续是否可以平滑引入 MQ 和 Worker。

### 2.4 第一阶段不做什么

第一阶段暂不实现以下内容：

1. Spring Cloud 微服务拆分。
2. Nacos 注册中心。
3. Spring Cloud Gateway。
4. RocketMQ 异步任务。
5. Redis 缓存。
6. Redisson 分布式锁。
7. 下载限流。
8. 下载 Token。
9. 本地消息表。
10. 分布式补偿任务。
11. Prometheus + Grafana。
12. Kubernetes 部署。

这些能力放到后续阶段逐步增强。

---

## 3. 技术选型

### 3.1 后端技术栈

| 技术 | 说明 |
|---|---|
| JDK 8 / JDK 17 | 推荐 JDK 8 或 JDK 17，按个人熟悉程度选择 |
| Spring Boot | 单体应用开发框架 |
| MyBatis | 数据访问层 |
| MySQL 8 | 业务数据库 |
| MinIO | 本地对象存储，模拟生产文件存储 |
| Maven | 项目构建 |
| Lombok | 简化实体类代码 |
| Swagger / Knife4j | 接口文档，可选 |
| JUnit | 单元测试 |
| Postman / Apifox | 接口调试 |
| Docker Compose | 本地启动 MySQL、MinIO |

### 3.2 推荐版本

| 组件 | 推荐版本 |
|---|---|
| Spring Boot | 2.7.x |
| MyBatis Spring Boot Starter | 2.3.x |
| MySQL | 8.x |
| MinIO Java SDK | 8.x |
| JDK | 8 或 17 |
| Maven | 3.6+ |

### 3.3 第一阶段最终技术栈

```text
Spring Boot + MyBatis + MySQL + MinIO + Maven
```

---

## 4. 系统总体设计

### 4.1 单体架构图

```text
┌──────────────────────────────┐
│        前端 / Postman         │
└───────────────┬──────────────┘
                │ HTTP
                ▼
┌──────────────────────────────┐
│      ExchangeClear 单体应用    │
│                              │
│  ┌──────────────┐            │
│  │ Mock 模块     │            │
│  └──────────────┘            │
│  ┌──────────────┐            │
│  │ Task 模块     │            │
│  └──────────────┘            │
│  ┌──────────────┐            │
│  │ Generator模块 │            │
│  └──────────────┘            │
│  ┌──────────────┐            │
│  │ File 模块     │            │
│  └──────────────┘            │
│  ┌──────────────┐            │
│  │ Storage模块   │            │
│  └──────────────┘            │
│  ┌──────────────┐            │
│  │ Download模块  │            │
│  └──────────────┘            │
└───────────────┬──────────────┘
                │
        ┌───────┴────────┐
        ▼                ▼
┌──────────────┐  ┌──────────────┐
│    MySQL      │  │    MinIO      │
│ 业务数据/元数据 │  │  对象存储文件 │
└──────────────┘  └──────────────┘
```

### 4.2 核心流程

```text
1. 调用 /api/mock/init 初始化会员和成交数据
2. 调用 /api/tasks/create 创建文件生成任务
3. 调用 /api/tasks/{taskNo}/generate 生成某个任务对应的文件
4. 系统查询成交数据并生成 CSV 文件
5. 系统计算文件 MD5
6. 系统上传文件到 MinIO
7. 系统写入 settle_file 文件元数据
8. 调用 /api/files 查询文件列表
9. 调用 /api/files/{fileNo}/download 下载文件
10. 本地校验文件 MD5 是否一致
```

---

## 5. 项目结构设计

### 5.1 Maven 项目结构

第一阶段建议先使用单模块项目，结构如下：

```text
exchange-clear
├── pom.xml
├── README.md
├── docker-compose.yml
├── sql
│   ├── 01_schema.sql
│   └── 02_init_data.sql
└── src
    ├── main
    │   ├── java
    │   │   └── com/example/exchangeclear
    │   │       ├── ExchangeClearApplication.java
    │   │       ├── common
    │   │       ├── config
    │   │       ├── enums
    │   │       ├── member
    │   │       ├── trade
    │   │       ├── task
    │   │       ├── file
    │   │       ├── generator
    │   │       ├── storage
    │   │       ├── download
    │   │       └── mock
    │   └── resources
    │       ├── application.yml
    │       └── mapper
    └── test
```

### 5.2 Java 包结构

```text
com.example.exchangeclear
├── common
│   ├── Result.java
│   ├── BizException.java
│   ├── ErrorCode.java
│   ├── GlobalExceptionHandler.java
│   └── IdGenerator.java
├── config
│   ├── MyBatisConfig.java
│   ├── MinioConfig.java
│   └── WebMvcConfig.java
├── enums
│   ├── TaskStatusEnum.java
│   ├── FileStatusEnum.java
│   └── FileTypeEnum.java
├── member
│   ├── controller
│   ├── entity
│   ├── mapper
│   └── service
├── trade
│   ├── entity
│   ├── mapper
│   └── service
├── task
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── mapper
│   └── service
├── file
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── mapper
│   └── service
├── generator
│   ├── FileGenerator.java
│   ├── TradeCsvFileGenerator.java
│   ├── FileGenerateContext.java
│   └── Md5Util.java
├── storage
│   ├── ObjectStorageService.java
│   ├── MinioStorageService.java
│   └── StorageUploadResult.java
├── download
│   ├── controller
│   └── service
└── mock
    ├── controller
    ├── dto
    └── service
```

---

## 6. 核心模块设计

## 6.1 common 公共模块

### 职责

1. 统一返回对象。
2. 统一异常。
3. 全局异常处理。
4. 编号生成。
5. 通用工具类。

### Result 返回结构

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

### Result 示例

```java
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(0);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
```

---

## 6.2 mock 模块

### 职责

用于模拟真实结算系统的数据输入。

第一阶段不接真实结算系统，通过接口生成测试数据。

### 功能

1. 初始化会员数据。
2. 初始化成交数据。
3. 清理测试数据。
4. 生成指定规模的数据。

### 接口

```http
POST /api/mock/init
```

### 请求参数

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 10,
  "tradeCountPerMember": 10000
}
```

### 处理逻辑

```text
1. 校验 settleDate、memberCount、tradeCountPerMember
2. 删除该结算日期下已有模拟数据，可选
3. 生成 memberCount 个会员
4. 每个会员生成 tradeCountPerMember 条成交数据
5. 批量插入 trade_record 表
6. 返回插入结果
```

### 返回示例

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "settleDate": "2026-06-26",
    "memberCount": 10,
    "tradeCount": 100000
  }
}
```

---

## 6.3 member 模块

### 职责

管理会员基础信息。

### 功能

1. 查询有效会员。
2. 根据 memberId 查询会员。
3. 为任务创建提供会员列表。

### 第一阶段接口

可以暂时不开放会员管理接口，只提供内部 Service。

```java
public interface MemberService {

    List<SettleMember> listActiveMembers();

    SettleMember getByMemberId(String memberId);
}
```

---

## 6.4 trade 模块

### 职责

管理模拟成交数据。

### 功能

1. 按结算日期和会员查询成交数据。
2. 分页查询成交数据。
3. 为文件生成模块提供数据来源。
4. 后续可升级为 MyBatis Cursor 流式查询。

### 第一阶段查询方式

第一阶段建议先使用分页查询实现，降低难度：

```text
pageSize = 1000
lastId = 0

while true:
    查询 id > lastId 的下一页成交数据
    写入 CSV
    如果没有数据，则结束
    更新 lastId
```

### 后续优化方向

第一阶段跑通后，再将分页查询升级为 Cursor 流式读取：

```text
MyBatis Cursor + fetchSize + BufferedWriter
```

---

## 6.5 task 模块

### 职责

管理文件生成任务。

### 功能

1. 创建文件生成任务。
2. 查询任务列表。
3. 查询任务详情。
4. 触发文件生成。
5. 更新任务状态。
6. 任务失败记录错误信息。

### 任务拆分粒度

第一阶段固定按照：

```text
settleDate + memberId + fileType + version
```

每个会员每种文件类型生成一个任务。

例如：

```text
2026-06-26 + 0001 + TRADE + 1
2026-06-26 + 0002 + TRADE + 1
2026-06-26 + 0003 + TRADE + 1
```

### 任务状态

| 状态 | 说明 |
|---|---|
| INIT | 已创建，待生成 |
| GENERATING | 文件生成中 |
| GENERATED | 文件已生成 |
| FAILED | 文件生成失败 |

### 状态流转

```text
INIT
  ↓
GENERATING
  ↓
GENERATED

INIT
  ↓
GENERATING
  ↓
FAILED
  ↓
GENERATING
  ↓
GENERATED
```

### 任务创建接口

```http
POST /api/tasks/create
```

### 请求参数

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1
}
```

### 处理逻辑

```text
1. 查询所有有效会员
2. 遍历会员列表
3. 为每个会员创建一个 settle_file_task
4. 任务状态为 INIT
5. 唯一键为 settle_date + member_id + file_type + version
6. 如果任务已存在，则跳过或返回已存在
```

### 返回示例

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "settleDate": "2026-06-26",
    "fileType": "TRADE",
    "createdCount": 10,
    "existsCount": 0
  }
}
```

### 任务生成接口

```http
POST /api/tasks/{taskNo}/generate
```

### 处理逻辑

```text
1. 根据 taskNo 查询任务
2. 校验任务存在
3. 校验任务状态为 INIT 或 FAILED
4. 更新任务状态为 GENERATING
5. 调用 TradeCsvFileGenerator 生成文件
6. 上传 MinIO
7. 写入 settle_file
8. 更新任务状态为 GENERATED
9. 如果异常，更新任务状态为 FAILED 并记录错误信息
```

---

## 6.6 generator 文件生成模块

### 职责

负责具体文件生成逻辑。

第一阶段只实现成交文件 `TRADE` 的 CSV 生成。

### 核心接口

```java
public interface FileGenerator {

    boolean support(String fileType);

    FileGenerateResult generate(FileGenerateContext context);
}
```

### FileGenerateContext

```java
public class FileGenerateContext {

    private String taskNo;
    private LocalDate settleDate;
    private String memberId;
    private String fileType;
    private Integer version;
}
```

### FileGenerateResult

```java
public class FileGenerateResult {

    private String fileName;
    private Path localFilePath;
    private Long fileSize;
    private String fileMd5;
}
```

### TradeCsvFileGenerator 处理流程

```text
1. 根据 settleDate、memberId 构造文件名
2. 创建本地临时目录
3. 创建 .tmp 临时文件
4. 写入 CSV 表头
5. 分页查询 trade_record
6. 将每条成交数据转成 CSV 行
7. 写入 BufferedWriter
8. 每 1000 条 flush 一次
9. 全部写入完成后关闭 writer
10. 将 .tmp 文件重命名为正式 .csv 文件
11. 计算 MD5
12. 返回 FileGenerateResult
```

### 文件命名规范

```text
trade_{memberId}_{yyyyMMdd}.csv
```

示例：

```text
trade_0001_20260626.csv
```

### 临时文件路径

```text
/tmp/exchange-clear/{settleDate}/{memberId}/{fileType}/{fileName}.tmp
```

示例：

```text
/tmp/exchange-clear/20260626/0001/TRADE/trade_0001_20260626.csv.tmp
```

### 正式本地文件路径

```text
/tmp/exchange-clear/{settleDate}/{memberId}/{fileType}/{fileName}
```

示例：

```text
/tmp/exchange-clear/20260626/0001/TRADE/trade_0001_20260626.csv
```

### CSV 文件格式

```csv
tradeNo,memberId,productId,contractId,direction,price,volume,amount,tradeTime
T202606260000001,0001,IF,IF2606,BUY,3500.1200,2,7000.2400,2026-06-26 09:31:01
T202606260000002,0001,IC,IC2606,SELL,5100.2300,1,5100.2300,2026-06-26 09:31:02
```

---

## 6.7 storage 存储模块

### 职责

封装对象存储操作。

第一阶段使用 MinIO，后续可以替换为 OSS、COS、OBS。

### 核心接口

```java
public interface ObjectStorageService {

    StorageUploadResult upload(Path localFilePath, String objectName);

    InputStream download(String objectName);

    boolean exists(String objectName);
}
```

### MinIO 上传路径规范

```text
/{settleDate}/{memberId}/v{version}/{fileType}/{fileName}
```

示例：

```text
/20260626/0001/v1/TRADE/trade_0001_20260626.csv
```

### StorageUploadResult

```java
public class StorageUploadResult {

    private String bucket;
    private String objectName;
    private Long fileSize;
}
```

---

## 6.8 file 文件元数据模块

### 职责

管理生成后的文件元数据。

### 功能

1. 保存文件元数据。
2. 查询文件列表。
3. 查询文件详情。
4. 更新下载次数。
5. 后续支持发布、撤销、重发。

### 文件状态

第一阶段只使用：

| 状态 | 说明 |
|---|---|
| GENERATED | 已生成 |
| FAILED | 生成失败，可选 |

后续阶段再扩展：

| 状态 | 说明 |
|---|---|
| CHECKED | 已校验 |
| PUBLISHED | 已发布 |
| REVOKED | 已撤销 |
| REISSUED | 已重发 |

### 保存元数据逻辑

```text
1. 文件生成成功
2. 文件上传 MinIO 成功
3. 构造 settle_file 记录
4. 写入文件名、大小、MD5、bucket、objectName
5. 状态设为 GENERATED
```

### 文件列表接口

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

### 返回示例

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "fileNo": "FILE202606260001",
      "settleDate": "2026-06-26",
      "memberId": "0001",
      "fileType": "TRADE",
      "fileName": "trade_0001_20260626.csv",
      "fileSize": 102400,
      "fileMd5": "e10adc3949ba59abbe56e057f20f883e",
      "status": "GENERATED",
      "version": 1
    }
  ]
}
```

---

## 6.9 download 下载模块

### 职责

提供文件下载能力。

第一阶段通过应用后端直接从 MinIO 获取文件流并返回。

### 下载接口

```http
GET /api/files/{fileNo}/download
```

### 处理逻辑

```text
1. 根据 fileNo 查询 settle_file
2. 校验文件存在
3. 根据 storage_path 从 MinIO 获取文件流
4. 设置 HTTP 响应头
5. 将文件流写入 response
6. 更新 download_count = download_count + 1
```

### 响应头

```http
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="trade_0001_20260626.csv"
```

### 后续增强

后续阶段可以改造为：

1. 下载 Token。
2. 预签名 URL。
3. HTTP Range 断点续传。
4. Redis 限流。
5. 下载审计异步落库。

---

## 7. 数据库设计

## 7.1 数据库命名

```sql
CREATE DATABASE exchange_clear DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

---

## 7.2 会员表：settle_member

```sql
CREATE TABLE settle_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    member_name VARCHAR(128) NOT NULL COMMENT '会员名称',
    status VARCHAR(32) NOT NULL COMMENT '状态：ACTIVE/INACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_member_id (member_id)
) COMMENT='会员信息表';
```

---

## 7.3 模拟成交数据表：trade_record

```sql
CREATE TABLE trade_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    trade_no VARCHAR(64) NOT NULL COMMENT '成交编号',
    product_id VARCHAR(32) NOT NULL COMMENT '产品编号',
    contract_id VARCHAR(32) NOT NULL COMMENT '合约编号',
    direction VARCHAR(16) NOT NULL COMMENT '买卖方向：BUY/SELL',
    price DECIMAL(18, 4) NOT NULL COMMENT '成交价格',
    volume INT NOT NULL COMMENT '成交数量',
    amount DECIMAL(18, 4) NOT NULL COMMENT '成交金额',
    trade_time DATETIME NOT NULL COMMENT '成交时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_settle_member_id (settle_date, member_id, id),
    KEY idx_trade_no (trade_no)
) COMMENT='模拟成交数据表';
```

### 索引设计

| 索引 | 说明 |
|---|---|
| idx_settle_member_id | 文件生成时按结算日期、会员分页查询 |
| idx_trade_no | 成交编号查询 |

### 为什么索引中包含 id

文件生成时建议使用基于 id 的游标分页：

```sql
SELECT *
FROM trade_record
WHERE settle_date = ?
  AND member_id = ?
  AND id > ?
ORDER BY id
LIMIT ?;
```

相比 `LIMIT offset, size`，这种方式在大数据量下性能更稳定。

---

## 7.4 文件生成任务表：settle_file_task

```sql
CREATE TABLE settle_file_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型：TRADE/FUND/POSITION',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    status VARCHAR(32) NOT NULL COMMENT '任务状态：INIT/GENERATING/GENERATED/FAILED',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_task_biz (settle_date, member_id, file_type, version),
    UNIQUE KEY uk_task_no (task_no),
    KEY idx_status (status),
    KEY idx_settle_date (settle_date),
    KEY idx_member_id (member_id)
) COMMENT='结算文件生成任务表';
```

### 唯一键设计

```text
settle_date + member_id + file_type + version
```

用于保证同一会员、同一日期、同一文件类型、同一版本只创建一个任务。

---

## 7.5 文件元数据表：settle_file

```sql
CREATE TABLE settle_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_no VARCHAR(64) NOT NULL COMMENT '文件编号',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
    file_size BIGINT NOT NULL COMMENT '文件大小',
    file_md5 VARCHAR(64) NOT NULL COMMENT '文件MD5',
    storage_bucket VARCHAR(128) NOT NULL COMMENT '对象存储桶',
    storage_path VARCHAR(512) NOT NULL COMMENT '对象存储路径',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    status VARCHAR(32) NOT NULL COMMENT '文件状态',
    publish_time DATETIME DEFAULT NULL COMMENT '发布时间，第一阶段可为空',
    download_count BIGINT NOT NULL DEFAULT 0 COMMENT '下载次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_file_biz (settle_date, member_id, file_type, version),
    UNIQUE KEY uk_file_no (file_no),
    KEY idx_member_date (member_id, settle_date),
    KEY idx_status (status),
    KEY idx_task_no (task_no)
) COMMENT='结算文件元数据表';
```

---

## 8. 枚举设计

## 8.1 TaskStatusEnum

```java
public enum TaskStatusEnum {

    INIT("INIT", "待生成"),
    GENERATING("GENERATING", "生成中"),
    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "生成失败");

    private final String code;
    private final String desc;
}
```

## 8.2 FileStatusEnum

```java
public enum FileStatusEnum {

    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "生成失败");

    private final String code;
    private final String desc;
}
```

## 8.3 FileTypeEnum

```java
public enum FileTypeEnum {

    TRADE("TRADE", "成交文件");

    private final String code;
    private final String desc;
}
```

---

## 9. 接口设计

## 9.1 初始化模拟数据

### URL

```http
POST /api/mock/init
```

### 请求参数

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 10,
  "tradeCountPerMember": 10000
}
```

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "settleDate": "2026-06-26",
    "memberCount": 10,
    "tradeCount": 100000
  }
}
```

### 校验规则

| 字段 | 规则 |
|---|---|
| settleDate | 必填 |
| memberCount | 1 - 1000 |
| tradeCountPerMember | 1 - 1000000 |

---

## 9.2 创建文件生成任务

### URL

```http
POST /api/tasks/create
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
    "existsCount": 0
  }
}
```

---

## 9.3 查询任务列表

### URL

```http
GET /api/tasks?settleDate=2026-06-26&status=INIT
```

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "taskNo": "TASK202606260001",
      "settleDate": "2026-06-26",
      "memberId": "0001",
      "fileType": "TRADE",
      "version": 1,
      "status": "INIT",
      "startTime": null,
      "endTime": null
    }
  ]
}
```

---

## 9.4 生成单个任务文件

### URL

```http
POST /api/tasks/{taskNo}/generate
```

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskNo": "TASK202606260001",
    "fileNo": "FILE202606260001",
    "fileName": "trade_0001_20260626.csv",
    "fileSize": 102400,
    "fileMd5": "e10adc3949ba59abbe56e057f20f883e"
  }
}
```

---

## 9.5 批量生成任务文件

### URL

```http
POST /api/tasks/generate-batch
```

### 请求参数

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE"
}
```

### 说明

第一阶段可以提供一个批量生成接口，内部串行遍历 INIT 或 FAILED 任务并逐个生成。

后续引入 MQ 后，该接口改造成批量投递消息。

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "successCount": 10,
    "failedCount": 0
  }
}
```

---

## 9.6 查询文件列表

### URL

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "fileNo": "FILE202606260001",
      "settleDate": "2026-06-26",
      "memberId": "0001",
      "fileType": "TRADE",
      "fileName": "trade_0001_20260626.csv",
      "fileSize": 102400,
      "fileMd5": "e10adc3949ba59abbe56e057f20f883e",
      "status": "GENERATED",
      "version": 1,
      "downloadCount": 0
    }
  ]
}
```

---

## 9.7 下载文件

### URL

```http
GET /api/files/{fileNo}/download
```

### 响应

返回文件流。

### 响应头

```http
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="trade_0001_20260626.csv"
```

---

## 9.8 查询文件校验信息

### URL

```http
GET /api/files/{fileNo}/checksum
```

### 返回参数

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileNo": "FILE202606260001",
    "fileName": "trade_0001_20260626.csv",
    "fileSize": 102400,
    "fileMd5": "e10adc3949ba59abbe56e057f20f883e"
  }
}
```

---

## 10. 核心流程详细设计

## 10.1 模拟数据初始化流程

```text
调用 /api/mock/init
    ↓
校验请求参数
    ↓
生成会员编号：0001、0002、0003...
    ↓
批量插入 settle_member
    ↓
循环生成 trade_record
    ↓
每 1000 条批量插入一次
    ↓
返回会员数量和成交数据数量
```

### 数据生成规则

| 字段 | 生成规则 |
|---|---|
| member_id | 0001、0002、0003 |
| trade_no | T + yyyyMMdd + 自增序号 |
| product_id | IF、IC、IH、IM 随机 |
| contract_id | IF2606、IC2606 等 |
| direction | BUY / SELL 随机 |
| price | 3000 - 6000 随机 |
| volume | 1 - 10 随机 |
| amount | price * volume |
| trade_time | settleDate 当天交易时间随机 |

---

## 10.2 任务创建流程

```text
调用 /api/tasks/create
    ↓
校验 settleDate、fileType
    ↓
查询有效会员列表
    ↓
遍历会员
    ↓
构造 taskNo
    ↓
插入 settle_file_task
    ↓
如果唯一键冲突，则计入 existsCount
    ↓
返回创建数量
```

### taskNo 生成规则

```text
TASK + yyyyMMddHHmmss + 6位随机数
```

示例：

```text
TASK202606261830000001
```

---

## 10.3 单任务生成流程

```text
调用 /api/tasks/{taskNo}/generate
    ↓
查询任务
    ↓
判断任务状态是否为 INIT 或 FAILED
    ↓
更新任务状态为 GENERATING
    ↓
创建 FileGenerateContext
    ↓
调用 TradeCsvFileGenerator.generate()
    ↓
生成本地 CSV 文件
    ↓
计算 MD5
    ↓
上传 MinIO
    ↓
写入 settle_file
    ↓
更新任务状态为 GENERATED
    ↓
返回文件信息
```

### 失败处理

```text
如果任意步骤失败：
    1. 记录异常日志
    2. 删除临时文件
    3. 更新任务状态为 FAILED
    4. 记录 error_message
    5. 返回错误信息
```

---

## 10.4 CSV 文件生成流程

```text
创建临时目录
    ↓
创建 .tmp 文件
    ↓
写入 CSV 表头
    ↓
lastId = 0
    ↓
循环查询：
    SELECT * FROM trade_record
    WHERE settle_date = ?
      AND member_id = ?
      AND id > ?
    ORDER BY id
    LIMIT 1000
    ↓
写入当前批次数据
    ↓
更新 lastId
    ↓
直到查询结果为空
    ↓
flush 并关闭 writer
    ↓
.tmp 文件 rename 为 .csv
    ↓
计算 MD5
```

### 推荐分页方式

不要使用：

```sql
SELECT *
FROM trade_record
WHERE settle_date = ?
  AND member_id = ?
ORDER BY id
LIMIT ?, ?;
```

推荐使用：

```sql
SELECT *
FROM trade_record
WHERE settle_date = ?
  AND member_id = ?
  AND id > ?
ORDER BY id
LIMIT ?;
```

原因：

1. 避免大 offset 导致扫描成本上升。
2. 更接近后续 Cursor/流式处理思想。
3. 更适合大数据量文件生成。

---

## 10.5 文件上传流程

```text
TradeCsvFileGenerator 返回本地文件路径
    ↓
构造 objectName
    ↓
调用 MinioStorageService.upload()
    ↓
MinIO 上传成功
    ↓
返回 bucket、objectName、fileSize
```

### objectName 规则

```text
{settleDate}/{memberId}/v{version}/{fileType}/{fileName}
```

示例：

```text
20260626/0001/v1/TRADE/trade_0001_20260626.csv
```

---

## 10.6 文件下载流程

```text
调用 /api/files/{fileNo}/download
    ↓
根据 fileNo 查询 settle_file
    ↓
判断文件是否存在
    ↓
根据 storage_path 从 MinIO 下载
    ↓
设置 response header
    ↓
流式写出到 HTTP response
    ↓
download_count + 1
```

---

## 11. 本地文件目录设计

### 11.1 临时目录

```text
/tmp/exchange-clear/{settleDate}/{memberId}/{fileType}/
```

示例：

```text
/tmp/exchange-clear/20260626/0001/TRADE/
```

### 11.2 临时文件

```text
trade_0001_20260626.csv.tmp
```

### 11.3 正式文件

```text
trade_0001_20260626.csv
```

### 11.4 清理策略

第一阶段先简单处理：

1. 生成失败时删除 `.tmp` 文件。
2. 上传成功后可以保留本地正式文件，便于调试。
3. 后续可增加定时清理本地文件功能。

后续增强：

1. 上传成功后删除本地文件。
2. 定时清理超过 N 天的临时目录。
3. 对异常残留 `.tmp` 文件进行扫描清理。

---

## 12. MinIO 设计

## 12.1 Docker 启动 MinIO

```bash
docker run -p 9000:9000 -p 9001:9001 \
  --name minio \
  -e "MINIO_ROOT_USER=minioadmin" \
  -e "MINIO_ROOT_PASSWORD=minioadmin" \
  quay.io/minio/minio server /data --console-address ":9001"
```

### 控制台地址

```text
http://localhost:9001
```

### 默认账号

```text
用户名：minioadmin
密码：minioadmin
```

---

## 12.2 application.yml 配置

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

mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  type-aliases-package: com.example.exchangeclear
  configuration:
    map-underscore-to-camel-case: true

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

---

## 12.3 MinIO Bucket

Bucket 名称：

```text
exchange-clear
```

启动应用时自动检查 Bucket 是否存在：

```text
1. 如果 Bucket 存在，直接使用
2. 如果 Bucket 不存在，自动创建
```

---

## 13. 事务设计

## 13.1 任务状态更新事务

生成任务时，至少需要保证：

```text
1. 任务状态从 INIT 更新为 GENERATING
2. 文件生成成功后写入 settle_file
3. 任务状态更新为 GENERATED
```

### 简化事务方案

第一阶段可以采用如下方式：

```text
1. 更新任务状态为 GENERATING，单独事务提交
2. 执行文件生成和 MinIO 上传，不放在数据库事务内
3. 上传成功后，开启事务：
   - 插入 settle_file
   - 更新 settle_file_task 为 GENERATED
4. 如果上传前失败，更新任务状态为 FAILED
5. 如果上传后数据库失败，记录日志，后续人工处理
```

### 为什么 MinIO 上传不放在数据库事务里

MinIO 文件上传属于外部 IO，无法和 MySQL 本地事务组成强一致事务。

第一阶段先接受该限制，在后续阶段通过本地消息表和对账补偿解决。

---

## 13.2 文件元数据插入幂等

`settle_file` 表有唯一键：

```text
settle_date + member_id + file_type + version
```

如果重复生成同一文件：

1. 第一阶段可以禁止重复生成。
2. 如果任务已经 GENERATED，直接返回已有文件。
3. 如果需要重发，后续通过 version + 1 实现。

---

## 14. 幂等设计

## 14.1 创建任务幂等

重复调用：

```http
POST /api/tasks/create
```

不会重复创建任务。

通过唯一键保证：

```text
settle_date + member_id + file_type + version
```

## 14.2 生成任务幂等

如果任务状态是：

| 状态 | 处理方式 |
|---|---|
| INIT | 允许生成 |
| FAILED | 允许重新生成 |
| GENERATING | 返回任务正在生成 |
| GENERATED | 返回文件已生成，不重复生成 |

## 14.3 文件元数据幂等

通过唯一键：

```text
settle_date + member_id + file_type + version
```

避免重复写入同一文件记录。

---

## 15. 异常处理设计

## 15.1 常见异常

| 异常 | 处理方式 |
|---|---|
| 参数错误 | 返回 400 类业务错误 |
| 任务不存在 | 返回任务不存在 |
| 任务状态不允许生成 | 返回当前状态不允许操作 |
| 成交数据为空 | 可以生成只有表头的文件，也可以返回失败，建议生成空文件 |
| 本地文件写入失败 | 更新任务为 FAILED |
| MD5 计算失败 | 更新任务为 FAILED |
| MinIO 上传失败 | 更新任务为 FAILED |
| 数据库写入失败 | 记录日志，返回失败 |
| 文件下载失败 | 返回文件下载失败 |

## 15.2 错误信息记录

`settle_file_task.error_message` 最多保存 2000 字符。

记录时建议截断：

```text
如果异常堆栈过长，只保存前 2000 字符
```

---

## 16. 日志设计

## 16.1 日志关键点

任务生成链路中需要打印关键日志：

```text
1. 开始生成任务
2. 查询任务成功
3. 更新任务状态为 GENERATING
4. 开始生成本地文件
5. 每处理 N 条数据打印一次进度，可选
6. 文件生成完成
7. MD5 计算完成
8. MinIO 上传完成
9. settle_file 写入完成
10. 任务状态更新为 GENERATED
11. 任务生成失败
```

## 16.2 日志示例

```text
[taskNo=TASK202606260001] start generate file
[taskNo=TASK202606260001] file generating, memberId=0001, fileType=TRADE
[taskNo=TASK202606260001] write records count=10000
[taskNo=TASK202606260001] local file generated, path=/tmp/exchange-clear/20260626/0001/TRADE/trade_0001_20260626.csv
[taskNo=TASK202606260001] md5 calculated, md5=e10adc3949ba59abbe56e057f20f883e
[taskNo=TASK202606260001] minio uploaded, objectName=20260626/0001/v1/TRADE/trade_0001_20260626.csv
[taskNo=TASK202606260001] generate success
```

---

## 17. 性能设计

## 17.1 第一阶段数据规模

第一阶段建议按三档数据量逐步验证。

### 小规模验证

```text
会员数量：10
每会员成交数据：10000
总成交数据：100000
```

### 中规模验证

```text
会员数量：100
每会员成交数据：10000
总成交数据：1000000
```

### 大规模验证

```text
会员数量：400
总成交数据：10000000
```

第一阶段先完成小规模和中规模验证，不必一开始就挑战千万级。

---

## 17.2 文件生成性能优化点

1. 使用基于 id 的分页，不使用大 offset。
2. 使用 BufferedWriter。
3. 每 1000 条 flush 一次。
4. 不把全部数据放入内存。
5. 文件生成过程中只保留当前批次数据。
6. 为 `trade_record(settle_date, member_id, id)` 建索引。
7. 避免在循环中频繁创建重量级对象。
8. 批量插入模拟数据。

---

## 17.3 内存控制

文件生成时禁止：

```java
List<TradeRecord> all = tradeMapper.selectAll(...);
```

推荐：

```java
long lastId = 0L;
while (true) {
    List<TradeRecord> list = tradeMapper.selectNextPage(settleDate, memberId, lastId, pageSize);
    if (list.isEmpty()) {
        break;
    }

    for (TradeRecord record : list) {
        writer.write(toCsvLine(record));
        writer.newLine();
        lastId = record.getId();
    }

    writer.flush();
}
```

---

## 18. 本地开发环境

## 18.1 Docker Compose

第一阶段可以准备如下 `docker-compose.yml`：

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
```

## 18.2 启动命令

```bash
docker-compose up -d
```

## 18.3 验证 MySQL

```bash
docker exec -it exchange-clear-mysql mysql -uroot -proot
```

## 18.4 验证 MinIO

访问：

```text
http://localhost:9001
```

---

## 19. 开发顺序

## 19.1 Day 1：项目骨架

完成内容：

1. 创建 Spring Boot 项目。
2. 接入 MySQL。
3. 接入 MyBatis。
4. 创建公共返回 Result。
5. 创建全局异常处理。
6. 创建枚举类。
7. 创建数据库表。
8. 应用可以启动。

验收标准：

```text
1. 应用启动成功
2. 可以连接 MySQL
3. 可以访问健康检查接口
```

---

## 19.2 Day 2：模拟数据

完成内容：

1. 实现 settle_member 实体和 Mapper。
2. 实现 trade_record 实体和 Mapper。
3. 实现 MockDataService。
4. 实现 /api/mock/init。
5. 支持批量插入会员和成交数据。

验收标准：

```text
1. 调用 /api/mock/init 成功
2. settle_member 有 10 条数据
3. trade_record 有 100000 条数据
```

---

## 19.3 Day 3：任务创建

完成内容：

1. 实现 settle_file_task 实体和 Mapper。
2. 实现 TaskService。
3. 实现 /api/tasks/create。
4. 实现 /api/tasks 查询接口。
5. 任务唯一键幂等。

验收标准：

```text
1. 调用 /api/tasks/create 后生成 10 个任务
2. 重复调用不会重复创建
3. 可以查询 INIT 状态任务
```

---

## 19.4 Day 4：文件生成

完成内容：

1. 实现 FileGenerator 接口。
2. 实现 TradeCsvFileGenerator。
3. 实现基于 id 的分页查询成交数据。
4. 生成 CSV 文件。
5. 计算 MD5。
6. 实现 /api/tasks/{taskNo}/generate。

验收标准：

```text
1. 单个任务可以生成 CSV 文件
2. 文件路径正确
3. 文件内容有表头和成交数据
4. MD5 可以正确计算
5. 任务状态从 INIT 变为 GENERATED
```

---

## 19.5 Day 5：MinIO 和文件元数据

完成内容：

1. 接入 MinIO Java SDK。
2. 实现 ObjectStorageService。
3. 实现 MinioStorageService。
4. 实现 settle_file 实体和 Mapper。
5. 文件生成成功后上传 MinIO。
6. 写入 settle_file 表。

验收标准：

```text
1. MinIO 控制台可以看到上传文件
2. settle_file 表有文件元数据
3. storage_path 正确
4. file_md5 正确
```

---

## 19.6 Day 6：文件查询和下载

完成内容：

1. 实现 /api/files 文件列表接口。
2. 实现 /api/files/{fileNo}/download 文件下载接口。
3. 从 MinIO 读取文件流。
4. 更新 download_count。
5. 实现 /api/files/{fileNo}/checksum。

验收标准：

```text
1. 可以查询会员文件列表
2. 可以下载文件
3. 下载文件内容正确
4. 下载后 download_count + 1
5. 本地计算 MD5 与数据库一致
```

---

## 19.7 Day 7：整理 README 和演示脚本

完成内容：

1. 编写 README。
2. 编写启动步骤。
3. 编写接口调用顺序。
4. 编写 SQL 脚本。
5. 编写 Postman/Apifox 接口集合。
6. 记录第一阶段完成情况。
7. 写后续计划。

验收标准：

```text
新人按照 README 可以启动项目并跑通完整流程
```

---

## 20. 第一阶段验收标准

## 20.1 功能验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 可以初始化会员和成交数据 | 必须 |
| 2 | 可以创建文件生成任务 | 必须 |
| 3 | 重复创建任务不会重复插入 | 必须 |
| 4 | 可以查询任务列表 | 必须 |
| 5 | 可以生成单个会员成交文件 | 必须 |
| 6 | 文件生成过程不会一次性加载全部数据 | 必须 |
| 7 | 可以计算文件 MD5 | 必须 |
| 8 | 可以上传文件到 MinIO | 必须 |
| 9 | 可以写入文件元数据 | 必须 |
| 10 | 可以查询文件列表 | 必须 |
| 11 | 可以下载文件 | 必须 |
| 12 | 下载文件 MD5 与数据库一致 | 必须 |
| 13 | 生成失败时任务状态变为 FAILED | 必须 |
| 14 | 支持批量生成任务 | 可选 |
| 15 | 支持 Swagger/Knife4j 接口文档 | 可选 |

---

## 20.2 技术验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 项目可以一键启动 | 必须 |
| 2 | MySQL 和 MinIO 可通过 Docker Compose 启动 | 必须 |
| 3 | SQL 脚本完整 | 必须 |
| 4 | 代码分层清晰 | 必须 |
| 5 | Controller 不直接写业务逻辑 | 必须 |
| 6 | 文件生成使用分页或流式方式 | 必须 |
| 7 | 大文件写入使用 BufferedWriter | 必须 |
| 8 | 有统一异常处理 | 必须 |
| 9 | 有基础日志 | 必须 |
| 10 | 有 README 文档 | 必须 |

---

## 21. 第一阶段演示流程

### 21.1 启动基础环境

```bash
docker-compose up -d
```

### 21.2 启动 Spring Boot 应用

```bash
mvn spring-boot:run
```

### 21.3 初始化数据

```http
POST http://localhost:8080/api/mock/init
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "memberCount": 10,
  "tradeCountPerMember": 10000
}
```

### 21.4 创建任务

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

### 21.5 查询任务

```http
GET http://localhost:8080/api/tasks?settleDate=2026-06-26&status=INIT
```

### 21.6 生成文件

```http
POST http://localhost:8080/api/tasks/{taskNo}/generate
```

### 21.7 查询文件

```http
GET http://localhost:8080/api/files?settleDate=2026-06-26&memberId=0001
```

### 21.8 下载文件

```http
GET http://localhost:8080/api/files/{fileNo}/download
```

### 21.9 查询校验信息

```http
GET http://localhost:8080/api/files/{fileNo}/checksum
```

---

## 22. 后续演进方向

第一阶段完成后，可以按照以下路线继续演进。

## 22.1 第二阶段：引入 RocketMQ

目标：

1. 任务创建后不再同步生成。
2. 将任务发送到 RocketMQ。
3. Worker 消费任务并生成文件。
4. 支持多个 Worker 并行处理。
5. 支持 MQ 重试和消费幂等。

改造前：

```text
Controller → TaskService → FileGenerator
```

改造后：

```text
TaskService → RocketMQ → WorkerConsumer → FileGenerator
```

---

## 22.2 第三阶段：拆分微服务

目标：

1. 拆分 task-service。
2. 拆分 file-service。
3. 拆分 worker-service。
4. 拆分 download-service。
5. 接入 Nacos。
6. 接入 Gateway。
7. 使用 OpenFeign 进行服务间调用。

---

## 22.3 第四阶段：引入 Redis

目标：

1. 文件列表缓存。
2. 文件元数据缓存。
3. 下载限流。
4. 下载 Token。
5. 分布式锁。

---

## 22.4 第五阶段：一致性补偿

目标：

1. 本地消息表。
2. MQ 发送失败补偿。
3. MinIO 与 MySQL 文件状态对账。
4. 任务超时恢复。
5. 失败任务自动重试。

---

## 22.5 第六阶段：监控和压测

目标：

1. Prometheus。
2. Grafana。
3. JMeter 压测。
4. JVM 监控。
5. 文件生成耗时监控。
6. 下载 QPS 监控。
7. 形成压测报告。

---

## 23. 第一阶段简历描述建议

第一阶段只是基础闭环，不建议作为最终简历完整亮点，但可以作为后续项目的基础描述。

可以写成：

```text
基于金融交易所结算文件发布场景，设计并实现结算文件生成与发布平台的基础闭环能力。系统支持模拟会员和成交数据，按照结算日期、会员、文件类型创建文件生成任务，基于分页查询和 BufferedWriter 实现大数据量成交文件生成，完成文件 MD5 校验、MinIO 对象存储上传、文件元数据入库、文件查询和下载能力，为后续引入 MQ 分布式 Worker、Redis 缓存限流和微服务拆分奠定基础。
```

第一阶段完成后，简历重点应该继续升级为：

```text
在基础闭环之上，引入 RocketMQ 将文件生成任务异步分发给多个 Worker 实例，实现结算文件的分布式并行生成；引入 Redis 缓存文件元数据并实现会员级下载限流，提升结算文件集中下载场景下的系统稳定性。
```

---

## 24. 关键注意事项

### 24.1 不要一开始追求复杂

第一阶段要避免：

1. 过早拆服务。
2. 过早引入 MQ。
3. 过早做复杂权限。
4. 过早接监控。
5. 过早压千万级数据。

先把完整业务流程跑通。

### 24.2 数据量要逐步放大

推荐顺序：

```text
1 万条
10 万条
100 万条
1000 万条
```

不要一开始就直接造 1000 万条，否则开发效率会很低。

### 24.3 文件生成不能一次性加载

这是第一阶段最重要的工程要求。

禁止：

```java
List<TradeRecord> all = tradeMapper.selectAllByMember(...);
```

推荐：

```java
while (true) {
    List<TradeRecord> page = tradeMapper.selectNextPage(...);
    if (page.isEmpty()) {
        break;
    }
    write(page);
}
```

### 24.4 保留后续扩展点

第一阶段虽然是单体，但代码结构要为后续扩展预留边界：

| 当前模块 | 后续可拆分为 |
|---|---|
| task 包 | task-service |
| generator 包 | worker-service |
| file 包 | file-service |
| download 包 | download-service |
| storage 包 | 公共 storage sdk 或 file-service 内部模块 |
| mock 包 | mock-service 或测试工具 |

---

## 25. 总结

第一阶段的核心目标是：

> 先用单体应用跑通金融交易所结算文件生成与发布的完整业务闭环。

完成第一阶段后，你将拥有：

1. 可运行的 Spring Boot 项目。
2. 完整数据库表结构。
3. 模拟会员和成交数据。
4. 文件生成任务管理。
5. 成交文件 CSV 生成能力。
6. 文件 MD5 校验能力。
7. MinIO 文件上传能力。
8. 文件元数据管理能力。
9. 文件查询和下载能力。
10. 后续扩展到 MQ、Redis、微服务的清晰边界。

第一阶段不是最终求职亮点，但它是后续分布式高并发版本的地基。

只要这个闭环做扎实，后续引入 RocketMQ、Redis、Nacos、Gateway、Worker 多实例时，就不会变成技术堆砌，而是围绕真实业务问题逐步演进。
