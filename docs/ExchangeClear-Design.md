# 金融交易所结算文件分布式生成与高并发分发平台详细设计文档

## 1. 项目概述

### 1.1 项目名称

**ExchangeClear 金融交易所结算文件分布式生成与高并发分发平台**

### 1.2 项目背景

在金融交易所业务场景中，结算完成后需要根据成交、持仓、资金、保证金、手续费等结算数据，为不同会员机构生成对应的结算文件，并在规定时间窗口内完成发布。

传统结算文件发布系统通常采用单体应用或集中式批处理方式，虽然能够满足基础业务要求，但在以下场景中存在明显瓶颈：

1. 结算数据量大，单日成交明细可能达到千万级。
2. 会员数量多，不同会员需要生成不同类型的结算文件。
3. 文件生成任务耗时长，串行处理效率低。
4. 结算文件发布后，会员系统会集中轮询和下载，短时间内形成高并发访问。
5. 文件发布涉及金融数据，需要保证准确性、完整性、可追溯性和可审计性。
6. 文件生成、上传、发布、下载链路较长，失败后需要具备重试和补偿能力。

因此，本项目基于交易所结算文件发布场景，设计并实现一套支持分布式任务生成、高并发文件分发、最终一致性补偿、下载审计和系统可观测性的结算文件平台。

---

## 2. 建设目标

### 2.1 业务目标

1. 支持按照结算日期、会员、文件类型生成结算文件。
2. 支持成交文件、资金文件、持仓文件、保证金文件、手续费文件等多类型文件生成。
3. 支持结算文件统一发布、撤销、重发和版本管理。
4. 支持会员机构高并发查询和下载结算文件。
5. 支持文件 MD5 校验、大小校验和下载审计。
6. 支持任务失败自动重试和人工重跑。
7. 支持文件状态、任务状态、下载行为全链路可追溯。

### 2.2 技术目标

1. 使用 MQ 将结算完成事件、文件生成任务、文件发布事件、下载审计事件异步解耦。
2. 使用多 Worker 并行消费文件生成任务，提高整体生成效率。
3. 使用 MyBatis Cursor 或分页流式查询，避免千万级数据一次性加载导致 OOM。
4. 使用 MinIO 或对象存储保存结算文件，降低应用服务器文件 IO 压力。
5. 使用 Redis 缓存文件元数据和会员文件列表，降低数据库查询压力。
6. 使用 Redis/Redisson 实现会员级限流、下载 Token 和分布式锁。
7. 使用状态机、唯一索引和消费端幂等控制，解决重复生成和重复发布问题。
8. 使用本地消息表和定时对账任务，保证数据库、MQ 和对象存储之间的最终一致性。
9. 使用 Prometheus、Grafana、日志链路追踪等方式提升系统可观测性。
10. 通过 JMeter 或 k6 进行压测，验证系统在高并发查询和下载场景下的稳定性。

---

## 3. 系统边界

### 3.1 系统输入

1. 结算完成事件。
2. 结算日期。
3. 会员信息。
4. 文件类型配置。
5. 成交、持仓、资金、保证金等结算数据。
6. 会员下载请求。

### 3.2 系统输出

1. 会员维度结算文件。
2. 文件压缩包。
3. 文件 MD5 校验值。
4. 文件元数据。
5. 文件发布记录。
6. 文件下载审计记录。
7. 任务执行日志和监控指标。

### 3.3 不包含范围

1. 不负责真实交易撮合。
2. 不负责结算核心规则计算。
3. 不负责会员身份认证中心建设。
4. 不负责真实生产级灾备建设。
5. 不负责外部监管报送系统。

---

## 4. 总体架构设计

### 4.1 架构风格

系统采用微服务架构，核心链路通过 MQ 异步解耦，文件生成 Worker 可水平扩展，文件下载通过缓存、限流和对象存储进行分流。

整体架构如下：

```text
┌──────────────────────┐
│  结算系统 / 结算模拟器 │
└──────────┬───────────┘
           │ 结算完成事件
           ▼
┌──────────────────────┐
│  结算任务编排服务      │
│  Task Orchestrator    │
└──────────┬───────────┘
           │ 生成文件任务消息
           ▼
┌──────────────────────┐
│  RocketMQ / Kafka     │
└──────────┬───────────┘
           │
           ▼
┌─────────────────────────────────────────────┐
│              文件生成 Worker 集群            │
│  File Worker 1 / File Worker 2 / File Worker N │
└──────────┬──────────────────────────────────┘
           │ 流式查询
           ▼
┌──────────────────────┐
│  MySQL 结算数据与元数据 │
└──────────┬───────────┘
           │ 上传文件
           ▼
┌──────────────────────┐
│  MinIO / 对象存储      │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  文件发布服务          │
└──────────┬───────────┘
           │
           ▼
┌─────────────────────────────────────────────┐
│  网关 + 下载服务 + Redis 缓存/限流/Token       │
└──────────┬──────────────────────────────────┘
           │
           ▼
┌──────────────────────┐
│  会员系统 / 券商系统    │
└──────────────────────┘
```

---

## 5. 服务拆分设计

### 5.1 settle-task-service：结算任务编排服务

#### 职责

1. 接收结算完成事件。
2. 根据结算日期、会员、文件类型拆分文件生成任务。
3. 创建任务记录。
4. 投递文件生成任务 MQ。
5. 提供任务查询、暂停、恢复、重跑能力。

#### 核心能力

1. 任务拆分。
2. 任务幂等创建。
3. 任务优先级排序。
4. 任务状态管理。
5. 失败任务重试。
6. 任务补偿扫描。

#### 拆分粒度

推荐使用如下粒度：

```text
settleDate + memberId + fileType + version
```

例如：

```text
2026-06-26 + 0001 + TRADE + v1
2026-06-26 + 0001 + FUND  + v1
2026-06-26 + 0002 + TRADE + v1
```

---

### 5.2 settle-file-worker：文件生成 Worker 服务

#### 职责

1. 消费文件生成任务。
2. 查询对应会员和文件类型的数据。
3. 流式生成文件。
4. 计算文件 MD5。
5. 压缩文件。
6. 上传对象存储。
7. 更新任务状态和文件元数据。
8. 发送文件生成成功或失败事件。

#### 核心能力

1. MQ 幂等消费。
2. 多实例并行处理。
3. 大数据量流式读取。
4. 文件流式写入。
5. 临时文件管理。
6. 失败重试。
7. 资源隔离。
8. JVM 内存控制。

#### 文件生成流程

```text
1. Worker 消费 file.generate.task 消息
2. 根据 taskId 查询任务记录
3. 判断任务状态是否允许执行
4. 将任务状态更新为 GENERATING
5. 创建本地临时文件
6. 使用 Cursor 流式查询结算数据
7. 使用 BufferedWriter 写入文件
8. 生成完成后计算 MD5 和文件大小
9. 压缩文件
10. 上传 MinIO
11. 写入 settle_file 元数据
12. 将任务状态更新为 GENERATED
13. 发送 file.generate.result 消息
```

---

### 5.3 settle-file-service：文件元数据服务

#### 职责

1. 管理结算文件元数据。
2. 管理文件状态机。
3. 提供文件列表查询。
4. 提供文件详情查询。
5. 支持文件发布、撤销、重发。
6. 支持文件 MD5 校验。
7. 支持文件版本管理。

#### 文件状态

```text
INIT        初始化
GENERATING  生成中
GENERATED   已生成
CHECKED     已校验
PUBLISHED   已发布
REVOKED     已撤销
REISSUED    已重发
FAILED      生成失败
```

#### 状态流转

```text
INIT
  ↓
GENERATING
  ↓
GENERATED
  ↓
CHECKED
  ↓
PUBLISHED
  ↓
REVOKED / REISSUED
```

---

### 5.4 settle-download-service：文件下载服务

#### 职责

1. 提供会员文件列表查询。
2. 提供文件下载接口。
3. 校验会员权限。
4. 校验文件状态。
5. 生成短期下载 Token。
6. 支持 HTTP Range 断点续传。
7. 支持会员级、IP 级限流。
8. 记录下载审计日志。

#### 下载流程

```text
1. 会员请求文件列表
2. 查询 Redis 缓存
3. 缓存未命中则查询 MySQL
4. 返回已发布文件列表
5. 会员请求下载
6. 校验会员权限
7. 校验文件状态是否为 PUBLISHED
8. 校验限流规则
9. 生成短期下载 Token 或预签名 URL
10. 发送下载审计 MQ
11. 返回下载地址或流式输出文件
```

---

### 5.5 settle-audit-service：审计服务

#### 职责

1. 消费下载审计消息。
2. 记录会员下载行为。
3. 记录下载 IP、时间、文件、结果。
4. 支持审计查询。
5. 支持异常下载行为统计。

#### 审计信息

1. 会员编号。
2. 文件编号。
3. 文件类型。
4. 结算日期。
5. 下载 IP。
6. User-Agent。
7. 下载开始时间。
8. 下载结果。
9. 下载耗时。
10. 失败原因。

---

### 5.6 settle-reconcile-service：对账补偿服务

#### 职责

1. 定时扫描异常任务。
2. 扫描本地消息表。
3. 补偿 MQ 投递失败消息。
4. 检查数据库与对象存储状态是否一致。
5. 检查文件大小和 MD5 是否一致。
6. 对失败任务进行自动重试或告警。

#### 异常场景

1. 数据库状态为 PUBLISHED，但对象存储文件不存在。
2. 对象存储文件存在，但数据库状态不是 GENERATED/PUBLISHED。
3. 文件大小与元数据记录不一致。
4. 文件 MD5 与元数据记录不一致。
5. 任务长时间停留在 GENERATING。
6. 本地消息表存在未发送消息。
7. MQ 死信队列存在积压消息。

---

## 6. 核心业务流程设计

### 6.1 结算完成后创建文件生成任务

```text
结算系统完成结算
    ↓
发送 settlement.completed 事件
    ↓
任务编排服务接收事件
    ↓
查询当日有效会员列表
    ↓
查询需要生成的文件类型
    ↓
按 memberId + fileType 拆分任务
    ↓
批量插入任务表
    ↓
发送 file.generate.task 消息
```

### 6.2 文件生成流程

```text
Worker 消费任务消息
    ↓
查询任务记录
    ↓
幂等判断
    ↓
更新任务状态为 GENERATING
    ↓
创建临时文件
    ↓
流式查询数据库
    ↓
流式写入文件
    ↓
计算 MD5
    ↓
压缩文件
    ↓
上传 MinIO
    ↓
写入文件元数据
    ↓
更新任务状态为 GENERATED
    ↓
发送生成结果事件
```

### 6.3 文件发布流程

```text
运营或系统触发发布
    ↓
校验当日文件是否全部生成
    ↓
校验文件 MD5 和大小
    ↓
获取分布式发布锁
    ↓
批量更新文件状态为 PUBLISHED
    ↓
清理或刷新 Redis 文件列表缓存
    ↓
发送 file.publish.event 消息
    ↓
释放分布式锁
```

### 6.4 文件下载流程

```text
会员请求下载
    ↓
网关鉴权
    ↓
下载服务校验会员权限
    ↓
校验文件是否已发布
    ↓
校验 Redis 限流规则
    ↓
生成短期下载 Token
    ↓
返回预签名 URL 或执行流式下载
    ↓
发送 download.audit 消息
    ↓
审计服务异步落库
```

---

## 7. 数据库设计

### 7.1 文件生成任务表：settle_file_task

```sql
CREATE TABLE settle_file_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    status VARCHAR(32) NOT NULL COMMENT '任务状态',
    priority INT NOT NULL DEFAULT 0 COMMENT '任务优先级',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_task_biz (settle_date, member_id, file_type, version),
    KEY idx_status (status),
    KEY idx_settle_date (settle_date),
    KEY idx_member_id (member_id)
) COMMENT='结算文件生成任务表';
```

### 7.2 文件元数据表：settle_file

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
    publish_time DATETIME DEFAULT NULL COMMENT '发布时间',
    download_count BIGINT NOT NULL DEFAULT 0 COMMENT '下载次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_file_biz (settle_date, member_id, file_type, version),
    KEY idx_member_date (member_id, settle_date),
    KEY idx_status (status),
    KEY idx_file_no (file_no)
) COMMENT='结算文件元数据表';
```

### 7.3 本地消息表：local_message

```sql
CREATE TABLE local_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
    topic VARCHAR(128) NOT NULL COMMENT '消息Topic',
    tag VARCHAR(64) DEFAULT NULL COMMENT '消息Tag',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务键',
    payload TEXT NOT NULL COMMENT '消息内容',
    status VARCHAR(32) NOT NULL COMMENT '消息状态',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_retry_time (status, next_retry_time),
    KEY idx_biz_key (biz_key)
) COMMENT='本地消息表';
```

### 7.4 下载审计表：file_download_audit

```sql
CREATE TABLE file_download_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    audit_no VARCHAR(64) NOT NULL COMMENT '审计编号',
    file_no VARCHAR(64) NOT NULL COMMENT '文件编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    client_ip VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    user_agent VARCHAR(512) DEFAULT NULL COMMENT 'User-Agent',
    download_status VARCHAR(32) NOT NULL COMMENT '下载状态',
    fail_reason VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    cost_ms BIGINT DEFAULT NULL COMMENT '下载耗时',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_member_date (member_id, settle_date),
    KEY idx_file_no (file_no),
    KEY idx_created_at (created_at)
) COMMENT='文件下载审计表';
```

### 7.5 会员信息表：settle_member

```sql
CREATE TABLE settle_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    member_name VARCHAR(128) NOT NULL COMMENT '会员名称',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_member_id (member_id)
) COMMENT='会员信息表';
```

### 7.6 文件类型配置表：settle_file_type_config

```sql
CREATE TABLE settle_file_type_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    file_type_name VARCHAR(128) NOT NULL COMMENT '文件类型名称',
    file_format VARCHAR(32) NOT NULL COMMENT '文件格式',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_file_type (file_type)
) COMMENT='文件类型配置表';
```

---

## 8. MQ 设计

### 8.1 Topic 设计

| Topic | 说明 |
|---|---|
| settlement.completed | 结算完成事件 |
| file.generate.task | 文件生成任务 |
| file.generate.result | 文件生成结果 |
| file.publish.event | 文件发布事件 |
| file.download.audit | 文件下载审计 |
| file.retry.task | 文件重试任务 |

### 8.2 文件生成任务消息

```json
{
  "messageId": "MSG202606260001",
  "taskNo": "TASK202606260001",
  "settleDate": "2026-06-26",
  "memberId": "0001",
  "fileType": "TRADE",
  "version": 1,
  "priority": 10,
  "createdAt": "2026-06-26 18:00:00"
}
```

### 8.3 消费幂等设计

消费端需要根据业务唯一键判断是否重复消费：

```text
settleDate + memberId + fileType + version
```

幂等判断逻辑：

```text
1. 查询任务记录
2. 如果任务不存在，记录异常并丢弃消息
3. 如果任务状态为 GENERATED 或 PUBLISHED，说明已经处理成功，直接 ACK
4. 如果任务状态为 GENERATING 且更新时间较近，说明其他 Worker 正在处理，直接 ACK 或延迟重试
5. 如果任务状态为 INIT 或 FAILED，则允许执行
```

### 8.4 消息可靠性设计

1. 生产端发送消息失败时，写入本地消息表。
2. 定时任务扫描本地消息表进行重投。
3. 消费端处理失败时，依赖 MQ 重试机制。
4. 超过最大重试次数后进入死信队列。
5. 对死信队列提供人工补偿入口。
6. 所有消息处理必须支持幂等。

---

## 9. Redis 设计

### 9.1 Redis Key 设计

| Key | 说明 | TTL |
|---|---|---|
| file:list:{settleDate}:{memberId} | 会员文件列表缓存 | 10 分钟 |
| file:meta:{fileNo} | 文件元数据缓存 | 30 分钟 |
| download:limit:member:{memberId} | 会员下载限流 | 1 秒 |
| download:limit:ip:{ip} | IP 下载限流 | 1 秒 |
| download:token:{token} | 下载 Token | 5 分钟 |
| lock:publish:{settleDate} | 文件发布分布式锁 | 30 秒 |
| lock:task:{taskNo} | 任务执行锁 | 10 分钟 |

### 9.2 文件列表缓存

查询流程：

```text
1. 先查 Redis：file:list:{settleDate}:{memberId}
2. 命中则直接返回
3. 未命中则查 MySQL
4. 查询结果写入 Redis
5. 返回文件列表
```

缓存失效场景：

1. 文件发布成功。
2. 文件撤销。
3. 文件重发。
4. 文件状态变更。
5. 手动刷新缓存。

### 9.3 下载限流

会员级限流示例：

```text
Key: download:limit:member:0001
规则：每个会员每秒最多 20 次下载请求
```

IP 级限流示例：

```text
Key: download:limit:ip:192.168.1.100
规则：每个 IP 每秒最多 50 次下载请求
```

推荐使用 Redis Lua 脚本保证限流计数的原子性。

---

## 10. 文件存储设计

### 10.1 存储方式

项目使用 MinIO 作为对象存储，也可以替换为阿里云 OSS、腾讯云 COS、华为云 OBS 等对象存储。

### 10.2 文件路径规范

```text
/{settleDate}/{memberId}/v{version}/{fileType}/{fileName}
```

示例：

```text
/20260626/0001/v1/TRADE/trade_0001_20260626.csv
/20260626/0001/v1/TRADE/trade_0001_20260626.zip
```

### 10.3 临时文件规范

```text
/tmp/exchange-clear/{settleDate}/{memberId}/{fileType}/{fileName}.tmp
```

示例：

```text
/tmp/exchange-clear/20260626/0001/TRADE/trade_0001_20260626.csv.tmp
```

### 10.4 文件生成原则

1. 先生成临时文件。
2. 文件生成成功后计算 MD5。
3. 压缩文件。
4. 上传对象存储。
5. 上传成功后写入数据库。
6. 数据库状态更新成功后才能认为任务成功。
7. 正式发布后的文件不可覆盖，只能通过新版本重发。

---

## 11. 接口设计

### 11.1 创建结算文件生成任务

```http
POST /api/settle/tasks
```

请求参数：

```json
{
  "settleDate": "2026-06-26",
  "fileTypes": ["TRADE", "FUND", "POSITION"],
  "memberIds": ["0001", "0002"]
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "batchNo": "BATCH202606260001",
    "taskCount": 6
  }
}
```

### 11.2 查询任务列表

```http
GET /api/settle/tasks?settleDate=2026-06-26&status=GENERATING
```

响应：

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
      "status": "GENERATING",
      "retryCount": 0
    }
  ]
}
```

### 11.3 重跑任务

```http
POST /api/settle/tasks/{taskNo}/retry
```

响应：

```json
{
  "code": 0,
  "message": "任务已重新投递",
  "data": true
}
```

### 11.4 发布文件

```http
POST /api/settle/files/publish
```

请求参数：

```json
{
  "settleDate": "2026-06-26",
  "version": 1
}
```

响应：

```json
{
  "code": 0,
  "message": "发布成功",
  "data": {
    "publishCount": 1200
  }
}
```

### 11.5 查询会员文件列表

```http
GET /api/member/files?settleDate=2026-06-26
```

响应：

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
      "fileName": "trade_0001_20260626.zip",
      "fileSize": 10240000,
      "fileMd5": "a1b2c3d4",
      "status": "PUBLISHED",
      "version": 1
    }
  ]
}
```

### 11.6 下载文件

```http
GET /api/member/files/{fileNo}/download
```

响应方式：

1. 返回预签名 URL。
2. 或直接进行流式下载。

推荐响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "downloadUrl": "https://minio.example.com/xxx?token=xxx",
    "expireSeconds": 300
  }
}
```

### 11.7 校验文件 MD5

```http
GET /api/member/files/{fileNo}/checksum
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileNo": "FILE202606260001",
    "fileMd5": "a1b2c3d4",
    "fileSize": 10240000
  }
}
```

---

## 12. 大数据量文件生成设计

### 12.1 问题

成交数据可能达到千万级，如果一次性查询到内存再写文件，容易导致：

1. JVM 内存溢出。
2. Full GC 频繁。
3. 数据库连接长时间占用。
4. 文件生成耗时不可控。
5. 单个任务失败后重试成本高。

### 12.2 解决方案

采用流式读取 + 流式写入。

```text
数据库 Cursor
    ↓
每次 fetch 1000 / 5000 / 10000 条
    ↓
BufferedWriter 写文件
    ↓
定期 flush
    ↓
生成完成后关闭资源
```

### 12.3 关键参数

| 参数 | 建议值 |
|---|---|
| fetchSize | 1000 - 10000 |
| flushBatchSize | 1000 |
| Worker 线程数 | CPU 核数 × 2 或按压测调整 |
| 单 Worker JVM 内存 | 512MB - 2GB |
| 单文件最大行数 | 根据测试数据模拟 100 万行 |
| 最大重试次数 | 3 次 |

### 12.4 伪代码

```java
public void generateFile(FileGenerateTask task) {
    Path tempFile = createTempFile(task);

    try (
        Cursor<TradeRecord> cursor = tradeMapper.scanByMember(
            task.getSettleDate(),
            task.getMemberId()
        );
        BufferedWriter writer = Files.newBufferedWriter(
            tempFile,
            StandardCharsets.UTF_8
        )
    ) {
        int count = 0;

        for (TradeRecord record : cursor) {
            writer.write(convertToLine(record));
            writer.newLine();

            count++;
            if (count % 1000 == 0) {
                writer.flush();
            }
        }

        writer.flush();

        String md5 = calculateMd5(tempFile);
        Path zipFile = zip(tempFile);
        uploadToMinio(zipFile, task, md5);
        updateTaskSuccess(task, md5, Files.size(zipFile));

    } catch (Exception e) {
        cleanTempFile(tempFile);
        updateTaskFailed(task, e);
        throw e;
    }
}
```

---

## 13. 幂等设计

### 13.1 任务创建幂等

通过唯一索引控制：

```text
settle_date + member_id + file_type + version
```

重复创建时直接返回已存在任务。

### 13.2 MQ 消费幂等

消费前检查任务状态：

| 当前状态 | 处理方式 |
|---|---|
| INIT | 可以执行 |
| FAILED | 可以重试执行 |
| GENERATING | 判断是否超时，未超时则跳过 |
| GENERATED | 直接 ACK |
| PUBLISHED | 直接 ACK |

### 13.3 文件上传幂等

对象存储路径中包含版本号：

```text
/{settleDate}/{memberId}/v{version}/{fileType}/{fileName}
```

正式发布后不允许覆盖同版本文件。

### 13.4 下载审计幂等

下载审计可以接受重复记录，因为审计记录本身表示一次下载行为。

如果需要强幂等，可使用：

```text
requestId + fileNo + memberId
```

---

## 14. 一致性设计

### 14.1 一致性问题

系统中涉及 MySQL、MQ、MinIO、Redis，多组件之间无法通过本地事务保证强一致，因此采用最终一致性方案。

### 14.2 文件生成一致性

推荐顺序：

```text
1. 生成本地临时文件
2. 计算 MD5
3. 上传 MinIO
4. 开启数据库事务
5. 写入 settle_file
6. 更新 settle_file_task 状态
7. 写入 local_message
8. 提交事务
9. 异步发送 MQ
```

### 14.3 本地消息表补偿

当数据库事务提交成功但 MQ 发送失败时，本地消息表会保留待发送消息。

定时任务扫描：

```sql
SELECT * FROM local_message
WHERE status = 'INIT'
   OR status = 'FAILED'
  AND next_retry_time <= NOW();
```

然后重新投递 MQ。

### 14.4 对账补偿

定时对账任务检查：

1. 数据库存在文件记录，但 MinIO 文件不存在。
2. MinIO 存在文件，但数据库无记录。
3. 数据库记录 MD5 与实际文件 MD5 不一致。
4. 数据库记录大小与实际文件大小不一致。
5. 任务长时间处于 GENERATING 状态。

处理方式：

| 异常 | 处理方式 |
|---|---|
| 文件缺失 | 标记任务失败并重试 |
| MD5 不一致 | 标记文件异常，禁止发布 |
| 任务超时 | 重置为 FAILED，等待重试 |
| 本地消息未发送 | 重新投递 MQ |
| Redis 缓存不一致 | 删除缓存并重建 |

---

## 15. 高并发下载设计

### 15.1 问题

结算文件发布后，会员系统可能在短时间内集中轮询和下载文件。

高并发压力主要集中在：

1. 文件列表查询。
2. 文件详情查询。
3. 文件下载。
4. 下载审计写入。
5. 权限校验。
6. 数据库连接。

### 15.2 解决方案

1. Redis 缓存文件列表。
2. Redis 缓存文件元数据。
3. 下载服务只负责鉴权、限流和审计，不直接承担全部文件传输压力。
4. 大文件下载使用 MinIO 预签名 URL。
5. 下载审计通过 MQ 异步落库。
6. 使用会员级和 IP 级限流。
7. 支持 HTTP Range 断点续传。
8. 网关层接入限流和熔断。

### 15.3 下载架构

```text
会员系统
  ↓
API Gateway
  ↓
下载服务
  ↓
Redis 校验 Token / 限流
  ↓
MySQL 查询元数据或命中缓存
  ↓
生成 MinIO 预签名 URL
  ↓
会员直连对象存储下载
```

### 15.4 限流策略

| 维度 | 规则 |
|---|---|
| 会员维度 | 单会员每秒最多 20 次下载请求 |
| IP 维度 | 单 IP 每秒最多 50 次请求 |
| 文件维度 | 单文件每秒最多 100 次请求 |
| 接口维度 | 下载接口整体 QPS 上限 3000 |
| 网关维度 | 超限返回 429 |

---

## 16. 安全设计

### 16.1 权限控制

1. 会员只能查询和下载自己的文件。
2. 内部管理员可以查询全部文件。
3. 文件发布、撤销、重发需要管理员权限。
4. 下载 Token 绑定 memberId、fileNo 和过期时间。

### 16.2 下载 Token

Token 内容：

```json
{
  "memberId": "0001",
  "fileNo": "FILE202606260001",
  "expireAt": "2026-06-26 18:05:00"
}
```

Token 存储：

```text
download:token:{token}
```

TTL：

```text
5 分钟
```

### 16.3 防越权

下载时校验：

```text
1. 当前登录会员 memberId
2. 文件所属 memberId
3. Token 中 memberId
4. 文件状态是否 PUBLISHED
```

只有四者匹配才允许下载。

---

## 17. 异常处理设计

### 17.1 文件生成失败

可能原因：

1. 数据库查询异常。
2. 文件写入异常。
3. 磁盘空间不足。
4. MinIO 上传失败。
5. Worker 宕机。
6. JVM OOM。

处理方式：

1. 捕获异常并记录错误信息。
2. 删除临时文件。
3. 更新任务状态为 FAILED。
4. retry_count + 1。
5. 未超过最大重试次数则重新投递 MQ。
6. 超过最大重试次数后进入人工处理。

### 17.2 Worker 宕机

处理方式：

1. 任务状态可能停留在 GENERATING。
2. 定时任务扫描长时间未更新的 GENERATING 任务。
3. 判断超过超时时间后重置为 FAILED。
4. 重新投递任务消息。
5. 清理临时文件。

### 17.3 MQ 消息积压

处理方式：

1. Grafana 监控 MQ 积压量。
2. 动态扩容 Worker 实例。
3. 提高 Worker 线程数。
4. 优先处理高优先级任务。
5. 对异常任务降级或隔离。

### 17.4 Redis 故障

处理方式：

1. 文件查询降级到 MySQL。
2. 限流能力降级到本地限流。
3. 下载 Token 可使用 JWT 替代 Redis Token。
4. 记录告警。
5. Redis 恢复后重新预热缓存。

### 17.5 MinIO 故障

处理方式：

1. 文件上传失败后任务标记 FAILED。
2. 下载接口返回文件服务不可用。
3. 对象存储恢复后任务自动重试。
4. 关键文件可配置多副本存储。

---

## 18. 可观测性设计

### 18.1 监控指标

#### 任务指标

| 指标 | 说明 |
|---|---|
| file_task_total | 文件任务总数 |
| file_task_success_total | 成功任务数 |
| file_task_failed_total | 失败任务数 |
| file_task_running_total | 执行中任务数 |
| file_task_duration_seconds | 文件生成耗时 |
| file_task_retry_total | 任务重试次数 |

#### MQ 指标

| 指标 | 说明 |
|---|---|
| mq_lag | 消息积压量 |
| mq_consume_tps | 消费 TPS |
| mq_produce_tps | 生产 TPS |
| mq_consume_failed_total | 消费失败次数 |

#### 下载指标

| 指标 | 说明 |
|---|---|
| download_qps | 下载 QPS |
| download_success_total | 下载成功次数 |
| download_failed_total | 下载失败次数 |
| download_limit_total | 限流次数 |
| download_duration_ms | 下载耗时 |
| download_bandwidth | 下载带宽 |

#### 缓存指标

| 指标 | 说明 |
|---|---|
| redis_cache_hit_rate | 缓存命中率 |
| redis_cache_miss_total | 缓存未命中次数 |
| redis_limit_reject_total | 限流拒绝次数 |

#### JVM 指标

| 指标 | 说明 |
|---|---|
| jvm_memory_used | JVM 内存使用 |
| jvm_gc_pause_seconds | GC 停顿时间 |
| jvm_threads_live | 活跃线程数 |
| jvm_cpu_usage | CPU 使用率 |

### 18.2 Grafana 看板

建议建设以下看板：

1. 结算文件生成任务看板。
2. MQ 消息积压看板。
3. 文件下载流量看板。
4. Redis 缓存命中率看板。
5. JVM 内存与 GC 看板。
6. MySQL 慢 SQL 看板。
7. MinIO 存储和下载带宽看板。

### 18.3 告警规则

| 告警项 | 规则 |
|---|---|
| 任务失败数过多 | 5 分钟内失败任务超过 10 个 |
| MQ 积压过高 | 积压消息超过 10000 条 |
| Worker 无消费 | 5 分钟内消费 TPS 为 0 |
| 下载失败率高 | 5 分钟内失败率超过 5% |
| Redis 不可用 | Redis 连接失败 |
| MinIO 不可用 | 文件上传或下载失败 |
| JVM 内存过高 | Old 区使用率超过 85% |
| Full GC 频繁 | 5 分钟内 Full GC 超过 3 次 |

---

## 19. 部署设计

### 19.1 推荐部署结构

```text
exchange-clear-gateway       2 实例
settle-task-service          2 实例
settle-file-service          2 实例
settle-download-service      2 实例
settle-file-worker           3-6 实例
settle-audit-service         2 实例
settle-reconcile-service     1-2 实例
MySQL                        1 主 1 从
Redis                        3 节点
RocketMQ / Kafka             3 节点
MinIO                        单机或集群
Prometheus + Grafana         1 套
```

### 19.2 Docker Compose 开发环境

开发环境可以使用 Docker Compose 启动：

1. MySQL。
2. Redis。
3. RocketMQ。
4. MinIO。
5. Prometheus。
6. Grafana。
7. 各 Spring Boot 服务。

### 19.3 生产级扩展方向

1. 使用 Kubernetes 部署。
2. Worker 根据 MQ 积压自动扩缩容。
3. MinIO 集群化。
4. MySQL 主从或分库分表。
5. Redis Cluster。
6. 引入 SkyWalking 或 OpenTelemetry 做链路追踪。

---

## 20. 压测设计

### 20.1 文件生成压测

#### 测试数据

```text
会员数量：400
文件类型：5
任务数量：2000
成交数据：1000 万
单会员最大数据量：100 万
Worker 实例数：3
每个 Worker 线程数：8
```

#### 关注指标

1. 总生成耗时。
2. 单文件生成耗时。
3. Worker CPU 使用率。
4. Worker 内存峰值。
5. GC 次数和停顿时间。
6. MQ 积压量。
7. MySQL 慢 SQL。
8. 任务成功率。

#### 目标

```text
任务成功率 >= 99.9%
Worker 内存峰值 <= 1GB
失败任务可自动重试
生成过程无 OOM
```

### 20.2 文件列表查询压测

#### 场景

```text
1000 并发用户查询会员文件列表
QPS 目标：3000+
```

#### 优化点

1. Redis 缓存文件列表。
2. 缓存命中率大于 95%。
3. 数据库 QPS 明显下降。
4. P95 响应时间小于 200ms。

### 20.3 文件下载压测

#### 场景

```text
500 并发下载
文件大小：50MB - 500MB
支持 Range 断点续传
下载审计异步写入
```

#### 关注指标

1. 下载 QPS。
2. 下载吞吐量。
3. 带宽使用。
4. 下载失败率。
5. 下载限流次数。
6. 审计 MQ 积压量。
7. MinIO 响应时间。

---

## 21. 核心技术难点与解决方案

### 21.1 千万级数据文件生成导致 OOM

#### 问题

成交数据量大，如果一次性加载到内存，会导致 JVM 内存溢出。

#### 方案

1. 使用 MyBatis Cursor 流式读取。
2. 设置合理 fetchSize。
3. 使用 BufferedWriter 流式写入。
4. 每 1000 条 flush。
5. 避免在内存中持有完整文件内容。

---

### 21.2 MQ 重复消费导致文件重复生成

#### 问题

MQ 天然可能出现重复投递，Worker 重复消费可能导致重复生成文件。

#### 方案

1. 使用业务唯一键。
2. 数据库唯一索引防重。
3. 消费前检查任务状态。
4. Redisson 分布式锁防止并发执行。
5. 已成功任务直接 ACK。

---

### 21.3 数据库与对象存储状态不一致

#### 问题

文件上传成功后数据库更新失败，或者数据库状态成功但对象存储文件异常。

#### 方案

1. 文件生成后先上传对象存储。
2. 数据库记录文件元数据。
3. 使用本地消息表记录后续事件。
4. 定时任务扫描异常状态。
5. 对文件大小和 MD5 进行对账。

---

### 21.4 发布后集中下载压力大

#### 问题

会员系统集中轮询和下载，可能冲击数据库和应用服务器。

#### 方案

1. Redis 缓存文件列表。
2. Redis 缓存文件元数据。
3. 使用 MinIO 预签名 URL 下载。
4. 下载审计异步写 MQ。
5. 网关和服务层双重限流。
6. 支持断点续传。

---

### 21.5 失败任务重试产生脏文件

#### 问题

文件生成一半失败，可能留下不完整文件。

#### 方案

1. 所有文件先写临时目录。
2. 生成成功后才上传正式路径。
3. 失败时删除临时文件。
4. 正式文件路径包含版本号。
5. 发布后的文件不可覆盖。

---

## 22. 项目分阶段实施计划

### V1：单体闭环版本

目标：

1. 初始化数据库表。
2. 生成模拟会员和结算数据。
3. 按会员和文件类型生成文件。
4. 上传 MinIO。
5. 查询文件列表。
6. 下载文件。
7. 计算 MD5。

技术栈：

```text
Spring Boot + MySQL + MyBatis + MinIO
```

### V2：分布式任务版本

目标：

1. 引入 RocketMQ。
2. 任务编排服务拆分任务。
3. Worker 多实例消费任务。
4. 实现任务状态机。
5. 实现失败重试。
6. 实现消费幂等。

技术栈：

```text
Spring Boot + RocketMQ + MySQL + MinIO
```

### V3：高并发下载版本

目标：

1. 引入 Redis。
2. 实现文件列表缓存。
3. 实现下载 Token。
4. 实现会员级限流。
5. 实现预签名 URL。
6. 下载审计异步落库。

技术栈：

```text
Redis + Redisson + Gateway + MinIO + MQ
```

### V4：一致性和可观测性版本

目标：

1. 实现本地消息表。
2. 实现对账补偿任务。
3. 接入 Prometheus。
4. 接入 Grafana。
5. 建设监控看板。
6. 使用 JMeter/k6 压测。
7. 输出压测报告。

技术栈：

```text
XXL-JOB + Prometheus + Grafana + JMeter
```

---

## 23. 简历描述建议

### 项目名称

**金融交易所结算文件分布式生成与高并发分发平台**

### 项目描述

基于金融交易所结算文件发布场景，设计并实现一套分布式文件生成与高并发下载平台。系统支持按照结算日期、会员、文件类型拆分文件生成任务，通过 MQ 异步调度多个 Worker 并行生成结算文件，使用对象存储保存文件，并通过 Redis 缓存、限流和短期下载 Token 支撑会员集中下载。系统同时提供文件 MD5 校验、版本管理、失败重试、下载审计、任务补偿和监控告警能力。

### 技术栈

Spring Boot、Spring Cloud Alibaba、MySQL、Redis、RocketMQ、MinIO、MyBatis、Redisson、XXL-JOB、Prometheus、Grafana、JMeter。

### 项目亮点

1. 设计 `settleDate + memberId + fileType + version` 作为任务唯一业务键，通过数据库唯一索引、状态机和消费端幂等控制解决 MQ 重复消费和文件重复生成问题。
2. 基于 MyBatis Cursor 和 BufferedWriter 实现千万级成交数据流式文件生成，避免一次性加载导致 OOM。
3. 使用 RocketMQ 拆分文件生成、发布、通知、审计链路，实现结算文件生成任务异步化和削峰填谷。
4. 使用 Redis 缓存会员文件列表和文件元数据，结合 Redisson 实现分布式锁、会员级限流和短期下载 Token。
5. 文件存储采用 MinIO，对外提供预签名 URL 和 HTTP Range 断点续传能力，降低应用服务器文件 IO 压力。
6. 通过本地消息表和定时对账任务解决 MySQL、MQ、MinIO 之间的最终一致性问题。
7. 建设 Prometheus + Grafana 监控看板，监控任务积压、文件生成耗时、下载 QPS、缓存命中率、失败任务数、JVM 内存和 GC 指标。

---

## 24. 面试可讲问题

### 24.1 为什么使用 MQ？

结算文件生成属于耗时任务，不能阻塞结算主流程。MQ 可以解耦结算完成事件和文件生成任务，同时起到削峰填谷作用。即使文件生成 Worker 短时间处理不过来，任务也可以在 MQ 中排队，后续通过扩容 Worker 提升消费能力。

### 24.2 MQ 重复消费怎么办？

通过业务唯一键、数据库唯一索引和任务状态机保证幂等。Worker 消费任务时先查询任务状态，只有 INIT 和 FAILED 状态允许执行。如果任务已经是 GENERATED 或 PUBLISHED，则说明已经处理成功，直接 ACK。

### 24.3 文件生成一半失败怎么办？

文件生成时先写入临时文件，只有生成完成、MD5 计算成功并上传对象存储后，才写入正式文件元数据。失败时删除临时文件，并将任务状态更新为 FAILED，等待自动重试或人工重跑。

### 24.4 数据库和对象存储如何保证一致？

数据库和对象存储无法放在同一个本地事务中，因此采用最终一致性方案。系统通过文件元数据、MD5、文件大小、本地消息表和定时对账任务发现并修复不一致问题。

### 24.5 高并发下载如何保护数据库？

文件列表和文件元数据会缓存到 Redis。下载接口只负责鉴权、限流和生成预签名 URL，真实文件下载压力由 MinIO 或 Nginx 承担。下载审计通过 MQ 异步落库，避免每次下载同步写数据库。

### 24.6 如何处理千万级数据文件生成？

采用 Cursor 流式读取和 BufferedWriter 流式写入，避免一次性加载全量数据。任务按照会员和文件类型拆分，由多个 Worker 并行处理，从而控制单任务内存占用并提升整体吞吐。

### 24.7 如何判断系统瓶颈？

通过监控和压测观察 MQ 积压量、Worker 消费速率、文件生成耗时、MySQL 慢 SQL、Redis 缓存命中率、下载 QPS、JVM GC、MinIO 带宽等指标，判断瓶颈在数据库、Worker、MQ、缓存还是对象存储。

---

## 25. 总结

本项目基于金融交易所结算文件发布场景，围绕“分布式生成”和“高并发分发”两个核心问题进行设计。

相比普通秒杀或商城项目，该项目具备更强的业务真实性和差异化，能够体现候选人在以下方面的能力：

1. 金融业务系统理解能力。
2. 大数据量文件处理能力。
3. 分布式任务调度能力。
4. MQ 异步解耦和幂等设计能力。
5. Redis 缓存与限流能力。
6. 高并发下载系统设计能力。
7. 最终一致性和补偿机制设计能力。
8. 系统可观测性和压测能力。
9. JVM 内存控制和线上问题排查能力。

该项目非常适合作为 Java 后端工程师求职中的核心项目，尤其适合投递交易、支付、金融科技、电商基础设施、数据平台、广告平台、业务中台等方向。
