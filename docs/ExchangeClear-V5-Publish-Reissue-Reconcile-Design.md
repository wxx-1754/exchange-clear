# ExchangeClear 第五阶段：文件发布、重发与一致性补偿详细设计文档

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第五阶段的设计与开发。

前四个阶段已经完成以下能力：

1. 第一阶段：单体闭环，完成文件生成、MinIO 上传、文件元数据入库、查询和下载。
2. 第二阶段：引入 RocketMQ，完成异步任务投递、Worker 消费、MQ 重试和消费幂等。
3. 第三阶段：拆分微服务，形成 task-service、file-service、worker-service、download-service，并接入 Nacos、Gateway、OpenFeign。
4. 第四阶段：引入 Redis，完成文件列表缓存、文件元数据缓存、下载 Token、会员级限流、IP 级限流和部分分布式锁。

第五阶段在已有能力基础上，正式引入 **文件发布、撤销、重发和一致性补偿机制**。

在第五阶段之前，文件生成成功后状态为 `GENERATED`，并且可以被会员查询和下载。

第五阶段之后，文件生命周期调整为：

```text
Worker 生成文件
    ↓
文件状态：GENERATED
    ↓
管理员或系统触发发布
    ↓
文件状态：PUBLISHED
    ↓
会员可以查询、申请下载 Token、下载文件
```

因此，第五阶段完成后：

```text
只有 PUBLISHED 文件允许会员下载。
```

---

## 2. 第五阶段建设目标

### 2.1 业务目标

1. 引入 `PUBLISHED` 文件状态。
2. 文件生成后状态仍为 `GENERATED`，表示“已生成、待发布”。
3. 管理员或系统可以触发某结算日期、某文件类型、某版本的文件发布。
4. 发布前校验该结算日期下文件是否全部生成完成。
5. 发布前校验文件元数据是否完整。
6. 发布前可选校验 MinIO 文件是否存在。
7. 发布时批量将文件状态从 `GENERATED` 更新为 `PUBLISHED`。
8. 下载服务只允许 `PUBLISHED` 文件下载。
9. 支持撤销文件，状态变为 `REVOKED`。
10. 支持重发文件，旧文件状态变为 `REISSUED`，新文件使用 `version + 1` 重新生成。
11. 发布、撤销、重发后删除 Redis 文件列表缓存和文件元数据缓存。
12. 引入本地消息表，保证数据库状态变更和 MQ 事件投递最终一致。
13. 引入对账补偿任务，处理 MQ 发送失败、任务超时、MinIO 文件缺失等异常。

### 2.2 技术目标

1. 扩展文件状态机。
2. 新增文件发布接口。
3. 新增文件撤销接口。
4. 新增文件重发接口。
5. 新增文件发布分布式锁。
6. 新增文件撤销分布式锁。
7. 新增文件重发分布式锁。
8. 新增文件状态变更日志。
9. 新增发布批次表。
10. 新增本地消息表。
11. 新增 reconcile-service 或补偿任务模块。
12. 调整 download-service 文件状态校验逻辑。
13. 调整 Redis 缓存删除策略。
14. 调整 RocketMQ 事件投递逻辑。

---

## 3. 本阶段边界

### 3.1 本阶段要做

1. `GENERATED -> PUBLISHED` 发布流程。
2. `GENERATED/PUBLISHED -> REVOKED` 撤销流程。
3. `PUBLISHED/REVOKED -> REISSUED` 重发流程。
4. 只有 `PUBLISHED` 文件允许下载。
5. 发布、撤销、重发状态日志。
6. 发布批次记录。
7. 发布、撤销、重发后删除 Redis 缓存。
8. 本地消息表。
9. 本地消息补偿任务。
10. 任务超时恢复任务。
11. MinIO 文件存在性对账任务。
12. 文件状态对账任务。
13. MQ 发送失败补偿。

### 3.2 本阶段暂不做

1. 不做复杂审批流。
2. 不做完整权限系统。
3. 不做前端管理页面。
4. 不做监管报送。
5. 不做跨机房容灾。
6. 不做复杂工作流引擎。
7. 不做历史版本文件内容对比。
8. 不做完整审计报表。
9. 不做 Redis Cluster 和高可用治理。

---

## 4. 文件状态机设计

### 4.1 文件状态枚举

```java
public enum FileStatusEnum {

    GENERATED("GENERATED", "已生成，待发布"),
    PUBLISHED("PUBLISHED", "已发布，可下载"),
    REVOKED("REVOKED", "已撤销，不可下载"),
    REISSUED("REISSUED", "已被新版本替代，不可下载"),
    FAILED("FAILED", "生成失败");

    private final String code;
    private final String desc;
}
```

### 4.2 状态流转图

```text
                    ┌──────────────┐
                    │  GENERATED   │
                    │  已生成待发布  │
                    └──────┬───────┘
                           │ 发布
                           ▼
                    ┌──────────────┐
                    │  PUBLISHED   │
                    │  已发布可下载  │
                    └──────┬───────┘
                           │
             ┌─────────────┼─────────────┐
             │ 撤销         │ 重发         │
             ▼             ▼             │
      ┌──────────────┐ ┌──────────────┐   │
      │   REVOKED    │ │   REISSUED   │   │
      │   已撤销      │ │ 已被新版本替代 │   │
      └──────────────┘ └──────────────┘   │
                                          │
                                          ▼
                                  新版本 GENERATED
                                          │
                                          ▼
                                  新版本 PUBLISHED
```

### 4.3 允许的状态流转

| 当前状态 | 目标状态 | 操作 | 是否允许 |
|---|---|---|---|
| GENERATED | PUBLISHED | 发布 | 允许 |
| GENERATED | REVOKED | 撤销 | 允许 |
| PUBLISHED | REVOKED | 撤销 | 允许 |
| PUBLISHED | REISSUED | 重发 | 允许 |
| REVOKED | REISSUED | 重发 | 允许 |
| FAILED | GENERATED | 重新生成成功 | 允许 |
| REISSUED | PUBLISHED | 旧版本重新发布 | 不建议 |
| REVOKED | PUBLISHED | 撤销后恢复发布 | 不建议 |

### 4.4 下载状态规则

第五阶段之后，download-service 必须只允许下载：

```text
PUBLISHED
```

不允许下载：

```text
GENERATED
REVOKED
REISSUED
FAILED
```

下载校验逻辑：

```text
1. 校验下载 Token 是否有效
2. 查询文件元数据
3. 校验 file.memberId == 当前 memberId
4. 校验 file.status == PUBLISHED
5. 从 MinIO 下载文件
```

如果文件不是 `PUBLISHED`，返回：

```json
{
  "code": 403003,
  "message": "文件尚未发布或已失效，禁止下载",
  "data": null
}
```

---

## 5. 总体架构设计

### 5.1 架构图

```text
                              ┌────────────────────┐
                              │   管理员 / 调度系统  │
                              └─────────┬──────────┘
                                        │ 发布/撤销/重发
                                        ▼
                              ┌────────────────────┐
                              │       Gateway       │
                              └─────────┬──────────┘
                                        │
          ┌─────────────────────────────┼─────────────────────────────┐
          ▼                             ▼                             ▼
┌──────────────────┐          ┌──────────────────┐          ┌──────────────────┐
│   task-service    │          │   file-service    │          │ download-service │
│ 任务创建/重投/重发 │          │ 发布/撤销/状态管理 │          │ Token/下载校验    │
└─────────┬────────┘          └─────────┬────────┘          └─────────┬────────┘
          │                             │                             │
          ▼                             ▼                             ▼
┌──────────────────┐          ┌──────────────────┐          ┌──────────────────┐
│     RocketMQ      │          │      Redis        │          │      MinIO        │
│ 任务/状态事件       │          │ 缓存/Token/锁      │          │ 文件对象存储       │
└─────────┬────────┘          └─────────┬────────┘          └─────────┬────────┘
          │                             │                             │
          ▼                             ▼                             ▼
┌──────────────────┐          ┌──────────────────┐          ┌──────────────────┐
│  worker-service   │          │ reconcile-service│          │      MySQL        │
│  文件生成 Worker   │          │ 对账补偿任务       │          │ 任务/文件/消息表   │
└──────────────────┘          └──────────────────┘          └──────────────────┘
```

### 5.2 服务职责变化

| 服务 | 第五阶段新增职责 |
|---|---|
| file-service | 文件发布、撤销、重发状态管理、发布锁、缓存删除、状态日志 |
| task-service | 支持创建新版本重发任务、任务重投、任务状态辅助查询 |
| worker-service | 支持按指定 version 生成新版本文件 |
| download-service | 只允许 PUBLISHED 文件创建 Token 和下载 |
| reconcile-service | 本地消息补偿、任务超时恢复、MinIO 文件对账、文件状态对账 |
| Redis | 发布锁、撤销锁、重发锁、缓存、Token |
| RocketMQ | 文件生成任务、文件状态变更事件 |
| MySQL | 文件状态、发布批次、本地消息、重发记录、状态日志 |

---

## 6. 数据库设计

## 6.1 settle_file 表调整

如果已有 `settle_file` 表，建议补充以下字段：

```sql
ALTER TABLE settle_file
ADD COLUMN revoke_time DATETIME DEFAULT NULL COMMENT '撤销时间',
ADD COLUMN reissue_time DATETIME DEFAULT NULL COMMENT '重发时间',
ADD COLUMN status_reason VARCHAR(1000) DEFAULT NULL COMMENT '状态变更原因';
```

确认已有字段：

```text
file_no
settle_date
member_id
file_type
file_name
file_size
file_md5
storage_bucket
storage_path
version
status
publish_time
download_count
```

### 重要索引

```sql
CREATE INDEX idx_settle_file_publish
ON settle_file(settle_date, file_type, version, status);

CREATE INDEX idx_settle_file_member_date
ON settle_file(member_id, settle_date);
```

---

## 6.2 发布批次表：file_publish_batch

```sql
CREATE TABLE file_publish_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    batch_no VARCHAR(64) NOT NULL COMMENT '发布批次号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    file_type VARCHAR(32) DEFAULT NULL COMMENT '文件类型，为空表示全部类型',
    version INT DEFAULT NULL COMMENT '版本，为空表示当前版本',
    status VARCHAR(32) NOT NULL COMMENT '发布批次状态：INIT/PUBLISHING/SUCCESS/FAILED',
    total_count INT NOT NULL DEFAULT 0 COMMENT '应发布文件数量',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功发布文件数量',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败文件数量',
    operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    start_time DATETIME DEFAULT NULL COMMENT '开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_settle_date (settle_date),
    KEY idx_status (status)
) COMMENT='文件发布批次表';
```

### 批次状态

| 状态 | 说明 |
|---|---|
| INIT | 初始化 |
| PUBLISHING | 发布中 |
| SUCCESS | 发布成功 |
| FAILED | 发布失败 |

---

## 6.3 文件状态变更日志表：file_status_log

```sql
CREATE TABLE file_status_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_no VARCHAR(64) NOT NULL COMMENT '文件编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    version INT NOT NULL COMMENT '版本',
    before_status VARCHAR(32) DEFAULT NULL COMMENT '变更前状态',
    after_status VARCHAR(32) NOT NULL COMMENT '变更后状态',
    operation_type VARCHAR(32) NOT NULL COMMENT '操作类型：GENERATE/PUBLISH/REVOKE/REISSUE',
    batch_no VARCHAR(64) DEFAULT NULL COMMENT '批次号',
    operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    reason VARCHAR(1000) DEFAULT NULL COMMENT '原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_file_no (file_no),
    KEY idx_settle_date (settle_date),
    KEY idx_batch_no (batch_no)
) COMMENT='文件状态变更记录表';
```

---

## 6.4 本地消息表：local_message

```sql
CREATE TABLE local_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
    topic VARCHAR(128) NOT NULL COMMENT 'Topic',
    tag VARCHAR(64) DEFAULT NULL COMMENT 'Tag',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务键',
    message_type VARCHAR(64) NOT NULL COMMENT '消息类型',
    payload TEXT NOT NULL COMMENT '消息内容JSON',
    status VARCHAR(32) NOT NULL COMMENT '状态：INIT/SENT/FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    max_retry_count INT NOT NULL DEFAULT 5 COMMENT '最大重试次数',
    next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    sent_time DATETIME DEFAULT NULL COMMENT '发送成功时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_retry_time (status, next_retry_time),
    KEY idx_biz_key (biz_key),
    KEY idx_message_type (message_type)
) COMMENT='本地消息表';
```

### 本地消息状态

| 状态 | 说明 |
|---|---|
| INIT | 待发送 |
| SENT | 已发送成功 |
| FAILED | 发送失败，等待补偿 |

---

## 6.5 文件重发记录表：file_reissue_record

```sql
CREATE TABLE file_reissue_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    reissue_no VARCHAR(64) NOT NULL COMMENT '重发编号',
    old_file_no VARCHAR(64) NOT NULL COMMENT '旧文件编号',
    new_file_no VARCHAR(64) DEFAULT NULL COMMENT '新文件编号',
    settle_date DATE NOT NULL COMMENT '结算日期',
    member_id VARCHAR(32) NOT NULL COMMENT '会员编号',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    old_version INT NOT NULL COMMENT '旧版本',
    new_version INT NOT NULL COMMENT '新版本',
    status VARCHAR(32) NOT NULL COMMENT '状态：INIT/GENERATING/GENERATED/PUBLISHED/FAILED',
    reason VARCHAR(1000) DEFAULT NULL COMMENT '重发原因',
    operator VARCHAR(64) DEFAULT NULL COMMENT '操作人',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_reissue_no (reissue_no),
    KEY idx_old_file_no (old_file_no),
    KEY idx_new_file_no (new_file_no),
    KEY idx_settle_member_type (settle_date, member_id, file_type)
) COMMENT='文件重发记录表';
```

---

## 7. Redis Key 设计

### 7.1 文件发布锁

```text
exchange-clear:lock:file:publish:{settleDate}
```

示例：

```text
exchange-clear:lock:file:publish:20260626
```

用途：防止多个 file-service 实例同时发布同一结算日期文件。

---

### 7.2 文件撤销锁

```text
exchange-clear:lock:file:revoke:{fileNo}
```

用途：防止同一个文件被重复撤销。

---

### 7.3 文件重发锁

```text
exchange-clear:lock:file:reissue:{fileNo}
```

用途：防止同一个文件被重复重发。

---

### 7.4 对账任务锁

```text
exchange-clear:lock:reconcile:{jobName}
```

示例：

```text
exchange-clear:lock:reconcile:local-message
exchange-clear:lock:reconcile:generating-timeout
exchange-clear:lock:reconcile:minio-file-check
```

---

### 7.5 缓存 Key

沿用第四阶段：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{fileNo}
exchange-clear:download:token:{token}
```

---

## 8. RocketMQ 事件设计

### 8.1 Topic 设计

| Topic | 说明 |
|---|---|
| file.generate.task | 文件生成任务 |
| file.status.event | 文件状态变更事件 |
| file.reissue.task | 文件重发任务，可选 |

---

### 8.2 文件状态变更事件

Topic：

```text
file.status.event
```

Tag：

```text
PUBLISHED
REVOKED
REISSUED
```

消息体示例：

```json
{
  "messageId": "MSG202606260001",
  "eventType": "PUBLISHED",
  "fileNo": "FILE202606260001",
  "settleDate": "2026-06-26",
  "memberId": "0001",
  "fileType": "TRADE",
  "version": 1,
  "operator": "admin",
  "eventTime": "2026-06-26 18:30:00"
}
```

用途：

1. 通知其他系统文件已发布。
2. 支撑后续审计。
3. 支撑后续通知推送。
4. 支撑后续监管报送。
5. 支撑系统间最终一致性。

---

## 9. 文件发布流程设计

## 9.1 发布前提

发布前必须满足：

1. 指定结算日期下任务已经全部生成完成。
2. 待发布文件状态必须为 `GENERATED`。
3. 文件元数据完整。
4. 文件 MD5 不为空。
5. 文件大小字段合法。
6. 文件 storage_bucket、storage_path 不为空。
7. MinIO 文件存在，可配置是否校验。
8. 当前结算日期没有正在执行的发布操作。

---

## 9.2 发布接口

```http
POST /api/files/publish
```

请求：

```json
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
    "publishCount": 400,
    "status": "SUCCESS"
  }
}
```

---

## 9.3 发布主流程

```text
调用 /api/files/publish
    ↓
参数校验
    ↓
获取发布分布式锁
    ↓
创建发布批次 file_publish_batch，状态 INIT
    ↓
更新批次状态为 PUBLISHING
    ↓
校验任务是否全部生成完成
    ↓
查询待发布文件列表
    ↓
校验文件元数据完整性
    ↓
可选校验 MinIO 文件存在性
    ↓
开启数据库事务
        ↓
        批量更新 settle_file：GENERATED -> PUBLISHED
        ↓
        写入 file_status_log
        ↓
        插入 local_message 文件发布事件
        ↓
        更新发布批次为 SUCCESS
    ↓
提交事务
    ↓
删除 Redis 文件列表缓存和文件元数据缓存
    ↓
异步发送本地消息表中的 MQ 消息
    ↓
释放发布锁
```

---

## 9.4 发布状态更新 SQL

```sql
UPDATE settle_file
SET status = 'PUBLISHED',
    publish_time = NOW(),
    status_reason = ?
WHERE settle_date = ?
  AND file_type = ?
  AND version = ?
  AND status = 'GENERATED';
```

如果 `fileType` 为空，表示发布全部文件类型，则不加 file_type 条件。

---

## 9.5 发布分布式锁

```java
public PublishResult publish(PublishRequest request) {
    String lockKey = RedisKeys.filePublishLock(request.getSettleDate());
    RLock lock = redissonClient.getLock(lockKey);

    boolean locked = false;
    try {
        locked = lock.tryLock(3, 300, TimeUnit.SECONDS);
        if (!locked) {
            throw new BizException("当前结算日期文件正在发布，请勿重复操作");
        }

        return doPublish(request);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BizException("获取发布锁失败");
    } finally {
        if (locked && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

建议锁时间：

```text
leaseTime = 300 秒
```

---

## 9.6 发布完整性校验

### 任务完成校验

```sql
SELECT COUNT(*)
FROM settle_file_task
WHERE settle_date = ?
  AND file_type = ?
  AND version = ?
  AND status <> 'GENERATED';
```

如果结果大于 0，禁止发布。

---

### 文件数量校验

第一版推荐：

```text
待发布文件数量 == GENERATED 任务数量
```

如果数量不一致，禁止发布。

---

### 文件元数据校验

每个待发布文件必须满足：

```text
file_no 不为空
file_name 不为空
file_size 不为空
file_md5 不为空
storage_bucket 不为空
storage_path 不为空
status = GENERATED
```

---

### MinIO 存在性校验

可配置是否校验：

```yaml
exchange-clear:
  publish:
    check-minio-exists: true
```

开发环境建议开启全量校验。

压测环境可以配置关闭或抽样校验。

---

## 9.7 发布后删除 Redis 缓存

发布成功后需要删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{fileNo}
```

删除策略：

```text
1. 查询本次发布涉及的文件列表
2. 按 memberId 去重删除 file:list 缓存
3. 逐个删除 file:meta 缓存
4. 删除失败只记录日志，不回滚发布事务
5. 依赖 TTL 兜底
```

---

## 10. 文件撤销流程设计

## 10.1 撤销场景

适用于：

1. 文件内容错误。
2. 文件生成规则错误。
3. 上游结算数据修正。
4. 管理员手工撤销。
5. 对账任务发现文件异常。

允许撤销的状态：

```text
GENERATED
PUBLISHED
```

撤销后状态：

```text
REVOKED
```

---

## 10.2 撤销接口

```http
POST /api/files/{fileNo}/revoke
```

请求：

```json
{
  "operator": "admin",
  "reason": "文件数据异常，撤销下载"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileNo": "FILE202606260001",
    "status": "REVOKED"
  }
}
```

---

## 10.3 撤销流程

```text
调用撤销接口
    ↓
获取文件撤销锁
    ↓
查询文件元数据
    ↓
校验状态是否允许撤销
    ↓
开启数据库事务
        ↓
        更新状态为 REVOKED
        ↓
        写入 file_status_log
        ↓
        插入 local_message 文件撤销事件
    ↓
提交事务
    ↓
删除 file:meta:{fileNo}
    ↓
删除 file:list:{settleDate}:{memberId}
    ↓
异步发送撤销事件 MQ
    ↓
释放锁
```

---

## 10.4 撤销状态更新 SQL

```sql
UPDATE settle_file
SET status = 'REVOKED',
    revoke_time = NOW(),
    status_reason = ?
WHERE file_no = ?
  AND status IN ('GENERATED', 'PUBLISHED');
```

如果影响行数为 0，说明状态不允许撤销。

---

## 10.5 撤销后的下载行为

如果下载已撤销文件，返回：

```json
{
  "code": 403004,
  "message": "文件已撤销，禁止下载",
  "data": null
}
```

---

## 11. 文件重发流程设计

## 11.1 重发场景

1. 文件内容错误。
2. 文件格式错误。
3. 结算数据修正。
4. 文件 MD5 校验失败。
5. MinIO 文件缺失。
6. 业务规则修正后需要重新生成。

---

## 11.2 重发原则

1. 不覆盖旧文件。
2. 使用 `version + 1` 生成新文件。
3. 旧文件状态更新为 `REISSUED`。
4. 新版本创建新的文件生成任务。
5. 新文件生成成功后状态为 `GENERATED`。
6. 新文件必须再次发布为 `PUBLISHED` 后才能下载。
7. 重发必须记录原因、操作人、旧版本和新版本。

---

## 11.3 重发接口

```http
POST /api/files/{fileNo}/reissue
```

请求：

```json
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

---

## 11.4 重发主流程

```text
调用重发接口
    ↓
获取重发锁
    ↓
查询旧文件
    ↓
校验旧文件状态是否允许重发
    ↓
计算 newVersion = oldVersion + 1
    ↓
开启数据库事务
        ↓
        创建 file_reissue_record
        ↓
        将旧文件状态更新为 REISSUED
        ↓
        写入 file_status_log
        ↓
        创建新版本 settle_file_task
        ↓
        插入 local_message 文件生成任务消息
    ↓
提交事务
    ↓
删除旧文件 Redis 缓存
    ↓
异步发送新版本文件生成 MQ
    ↓
释放重发锁
```

---

## 11.5 旧文件状态更新 SQL

```sql
UPDATE settle_file
SET status = 'REISSUED',
    reissue_time = NOW(),
    status_reason = ?
WHERE file_no = ?
  AND status IN ('GENERATED', 'PUBLISHED', 'REVOKED');
```

---

## 11.6 新版本任务业务键

新任务唯一业务键：

```text
settleDate + memberId + fileType + newVersion
```

新任务状态：

```text
INIT 或 SENT
```

如果创建任务后立即发送 MQ：

```text
INIT -> SENT -> GENERATING -> GENERATED
```

新版本发布后：

```text
GENERATED -> PUBLISHED
```

---

## 11.7 重发后的下载行为

旧文件状态为：

```text
REISSUED
```

旧文件不允许下载。

返回：

```json
{
  "code": 403005,
  "message": "文件已重发，请下载新版本文件",
  "data": null
}
```

新版本文件只有在 `PUBLISHED` 后才允许下载。

---

## 12. 本地消息表设计

## 12.1 为什么需要本地消息表

文件发布、撤销、重发时通常需要同时做两件事：

```text
1. 更新 MySQL 中文件状态
2. 发送 RocketMQ 事件
```

MySQL 和 RocketMQ 不是同一个事务资源，可能出现：

```text
数据库更新成功，但 MQ 发送失败。
```

因此引入本地消息表：

```text
同一个数据库事务内：
    1. 更新业务表
    2. 插入 local_message

事务提交后：
    3. 发送 MQ
    4. 成功则 local_message 改为 SENT
    5. 失败则 local_message 改为 FAILED，等待补偿
```

---

## 12.2 本地消息写入场景

| 场景 | message_type | topic | tag |
|---|---|---|---|
| 文件发布 | FILE_PUBLISHED | file.status.event | PUBLISHED |
| 文件撤销 | FILE_REVOKED | file.status.event | REVOKED |
| 文件重发 | FILE_REISSUED | file.status.event | REISSUED |
| 新版本生成任务 | FILE_GENERATE_TASK | file.generate.task | TRADE |

---

## 12.3 本地消息状态流转

```text
INIT
  ↓ 发送成功
SENT

INIT
  ↓ 发送失败
FAILED
  ↓ 补偿重试成功
SENT
```

---

## 12.4 本地消息补偿 SQL

```sql
SELECT *
FROM local_message
WHERE status IN ('INIT', 'FAILED')
  AND (next_retry_time IS NULL OR next_retry_time <= NOW())
  AND retry_count < max_retry_count
ORDER BY id
LIMIT 100;
```

---

## 13. 对账补偿任务设计

## 13.1 reconcile-service 职责

建议新增独立服务：

```text
reconcile-service
```

职责：

1. 本地消息补偿。
2. 生成中任务超时恢复。
3. 文件状态对账。
4. MinIO 文件存在性检查。
5. Redis 缓存清理补偿，可选。
6. 死信消息人工处理辅助。

---

## 13.2 本地消息补偿任务

任务名称：

```text
LocalMessageRetryJob
```

执行频率：

```text
每 1 分钟执行一次
```

流程：

```text
获取补偿锁
    ↓
扫描 local_message 中 INIT / FAILED 且到期的消息
    ↓
逐条发送 MQ
    ↓
发送成功：状态改为 SENT
    ↓
发送失败：retry_count + 1，更新 next_retry_time
    ↓
超过最大重试次数：保持 FAILED，等待人工处理
    ↓
释放补偿锁
```

锁 Key：

```text
exchange-clear:lock:reconcile:local-message
```

---

## 13.3 任务超时恢复任务

任务名称：

```text
GeneratingTaskTimeoutJob
```

解决问题：Worker 宕机导致任务长时间停留在 `GENERATING`。

扫描 SQL：

```sql
SELECT *
FROM settle_file_task
WHERE status = 'GENERATING'
  AND updated_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE);
```

处理方式：

```text
1. 将任务状态更新为 FAILED
2. 记录 error_message = 任务生成超时
3. 第一版不自动重投，等待人工重投
4. 后续可配置自动重投
```

---

## 13.4 文件状态对账任务

任务名称：

```text
FileStateReconcileJob
```

检查内容：

1. `settle_file_task.status = GENERATED`，但 `settle_file` 不存在。
2. `settle_file.status = GENERATED`，但任务状态不是 `GENERATED`。
3. `settle_file.status = PUBLISHED`，但文件元数据不完整。
4. `settle_file.status = PUBLISHED`，但 MinIO 文件不存在。
5. `settle_file.status = REISSUED`，但没有新版本文件。
6. `file_reissue_record` 状态与新旧文件状态不一致。

第一版处理策略：

```text
只记录异常日志，不自动修改核心状态。
```

---

## 13.5 MinIO 文件存在性对账任务

任务名称：

```text
MinioFileExistsCheckJob
```

扫描范围：

```text
最近 7 天文件
```

流程：

```text
查询最近 N 天 settle_file
    ↓
逐个检查 MinIO object 是否存在
    ↓
如果不存在：
        记录异常日志
        如果文件未发布，可建议重发
        如果文件已发布，建议人工撤销或重发
```

第一版建议：

```text
只记录异常，不自动修改 PUBLISHED 状态。
```

---

## 14. Redis 缓存一致性设计

### 14.1 发布后删除缓存

发布成功后删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{fileNo}
```

原因：文件状态从 `GENERATED` 变成 `PUBLISHED`。

---

### 14.2 撤销后删除缓存

撤销成功后删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{fileNo}
```

原因：文件状态变成 `REVOKED`，不能继续下载。

---

### 14.3 重发后删除缓存

重发成功后删除旧文件缓存：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{oldFileNo}
```

新文件生成成功后删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{newFileNo}
```

---

### 14.4 Token 处理

文件撤销或重发后，已有 Token 可能仍未过期。

因此下载时必须再次查询文件状态：

```text
file.status == PUBLISHED
```

即使 Token 有效，只要文件状态不是 `PUBLISHED`，也禁止下载。

第一版不需要主动删除历史 Token，依赖 TTL 和状态二次校验即可。

---

## 15. download-service 调整设计

## 15.1 创建 Token 调整

创建 Token 时必须校验：

```text
file.status == PUBLISHED
```

流程：

```text
请求创建 Token
    ↓
查询文件元数据
    ↓
校验文件所属会员
    ↓
校验文件状态为 PUBLISHED
    ↓
生成 Token
    ↓
写入 Redis
    ↓
返回 Token
```

---

## 15.2 下载接口调整

使用 Token 下载时也要再次校验：

```text
1. Token 有效
2. Token 中 fileNo 匹配
3. Token 中 memberId 匹配
4. 文件所属 memberId 匹配
5. 文件状态为 PUBLISHED
```

---

## 16. 接口设计

## 16.1 发布文件

```http
POST /api/files/publish
```

请求：

```json
{
  "settleDate": "2026-06-26",
  "fileType": "TRADE",
  "version": 1,
  "operator": "admin"
}
```

---

## 16.2 查询发布批次

```http
GET /api/files/publish-batches?settleDate=2026-06-26
```

---

## 16.3 撤销文件

```http
POST /api/files/{fileNo}/revoke
```

请求：

```json
{
  "operator": "admin",
  "reason": "文件数据异常，撤销下载"
}
```

---

## 16.4 重发文件

```http
POST /api/files/{fileNo}/reissue
```

请求：

```json
{
  "operator": "admin",
  "reason": "文件内容错误，重新生成"
}
```

---

## 16.5 查询文件状态日志

```http
GET /api/files/{fileNo}/status-logs
```

---

## 16.6 本地消息人工重试

```http
POST /api/local-messages/{messageId}/retry
```

---

## 17. 异常处理设计

| 异常场景 | 返回信息 |
|---|---|
| 仍有任务未生成 | 仍有文件生成任务未完成，禁止发布 |
| 待发布文件不存在 | 待发布文件不存在，禁止发布 |
| MinIO 文件缺失 | 对象存储文件不存在，禁止发布 |
| 文件已发布 | 文件已发布，请勿重复发布 |
| 状态不允许撤销 | 当前文件状态不允许撤销 |
| 状态不允许重发 | 当前文件状态不允许重发 |
| 发布锁获取失败 | 当前结算日期文件正在发布，请稍后重试 |
| 撤销锁获取失败 | 当前文件正在撤销，请稍后重试 |
| 重发锁获取失败 | 当前文件正在重发，请稍后重试 |

---

## 18. 配置项设计

```yaml
exchange-clear:
  publish:
    check-task-complete: true
    check-file-meta: true
    check-minio-exists: true
    lock-wait-seconds: 3
    lock-lease-seconds: 300

  revoke:
    lock-wait-seconds: 3
    lock-lease-seconds: 60

  reissue:
    lock-wait-seconds: 3
    lock-lease-seconds: 60

  download:
    allowed-statuses:
      - PUBLISHED

  local-message:
    retry-interval-seconds: 60
    max-retry-count: 5
    batch-size: 100

  reconcile:
    local-message-enabled: true
    generating-timeout-enabled: true
    generating-timeout-minutes: 30
    minio-check-enabled: true
    minio-check-days: 7
```

---

## 19. 代码结构建议

### 19.1 file-service

```text
file-service
├── controller
│   ├── FileController.java
│   ├── FilePublishController.java
│   └── FileReissueController.java
├── service
│   ├── FileService.java
│   ├── FilePublishService.java
│   ├── FileRevokeService.java
│   ├── FileReissueService.java
│   └── FileStatusLogService.java
├── lock
│   ├── FilePublishLockService.java
│   ├── FileRevokeLockService.java
│   └── FileReissueLockService.java
├── mapper
│   ├── SettleFileMapper.java
│   ├── FilePublishBatchMapper.java
│   ├── FileStatusLogMapper.java
│   └── FileReissueRecordMapper.java
└── dto
    ├── PublishRequest.java
    ├── PublishResult.java
    ├── RevokeRequest.java
    └── ReissueRequest.java
```

### 19.2 reconcile-service

```text
reconcile-service
├── job
│   ├── LocalMessageRetryJob.java
│   ├── GeneratingTaskTimeoutJob.java
│   ├── FileStateReconcileJob.java
│   └── MinioFileExistsCheckJob.java
├── service
│   ├── LocalMessageRetryService.java
│   ├── TaskTimeoutRecoverService.java
│   ├── FileStateReconcileService.java
│   └── MinioCheckService.java
└── lock
    └── ReconcileLockService.java
```

---

## 20. 测试方案

### 20.1 发布测试

```text
1. 初始化数据
2. 创建任务
3. Worker 生成文件
4. 文件状态为 GENERATED
5. 调用发布接口
6. 文件状态变为 PUBLISHED
7. Redis 缓存被删除
8. 可以创建下载 Token
9. 可以下载文件
```

### 20.2 未发布下载测试

```text
1. 文件状态为 GENERATED
2. 不发布
3. 创建下载 Token
4. 预期返回：文件尚未发布
```

### 20.3 撤销测试

```text
1. 发布文件
2. 下载成功
3. 撤销文件
4. 文件状态变为 REVOKED
5. 再次下载失败
```

### 20.4 重发测试

```text
1. 发布 v1 文件
2. 调用重发接口
3. v1 状态变为 REISSUED
4. 创建 v2 任务
5. Worker 生成 v2
6. v2 状态 GENERATED
7. 发布 v2
8. v2 状态 PUBLISHED
9. v1 不可下载，v2 可下载
```

### 20.5 本地消息补偿测试

```text
1. 模拟 MQ 发送失败
2. 发布文件
3. local_message 状态为 FAILED
4. 启动补偿任务
5. 补偿发送成功
6. local_message 状态变为 SENT
```

### 20.6 任务超时恢复测试

```text
1. 手工将任务状态改为 GENERATING
2. updated_at 设置为 1 小时前
3. 启动超时恢复任务
4. 任务状态变为 FAILED
```

---

## 21. 验收标准

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 文件生成成功后状态为 GENERATED | 必须 |
| 2 | GENERATED 文件不能下载 | 必须 |
| 3 | 可以将 GENERATED 批量发布为 PUBLISHED | 必须 |
| 4 | PUBLISHED 文件可以创建 Token 并下载 | 必须 |
| 5 | 发布前校验任务是否全部完成 | 必须 |
| 6 | 发布前校验文件元数据完整 | 必须 |
| 7 | 发布操作使用分布式锁 | 必须 |
| 8 | 发布后删除 Redis 文件列表缓存 | 必须 |
| 9 | 发布后删除 Redis 文件元数据缓存 | 必须 |
| 10 | 支持撤销为 REVOKED | 必须 |
| 11 | REVOKED 文件不能下载 | 必须 |
| 12 | 支持重发为 REISSUED | 必须 |
| 13 | REISSUED 旧文件不能下载 | 必须 |
| 14 | 重发后创建新版本任务 | 必须 |
| 15 | 本地消息表可以记录待发送消息 | 必须 |
| 16 | 本地消息补偿任务可以重发失败消息 | 必须 |
| 17 | 对账任务可以扫描超时 GENERATING 任务 | 必须 |
| 18 | 对账任务可以检查 MinIO 文件存在性 | 建议 |

---

## 22. 开发计划

### Day 1：状态机和数据库改造

1. 扩展 FileStatusEnum。
2. 调整 download-service，只允许 PUBLISHED 下载。
3. 新增 file_publish_batch 表。
4. 新增 file_status_log 表。
5. 新增 local_message 表。
6. 新增 file_reissue_record 表。

### Day 2：发布流程

1. 实现发布接口。
2. 实现发布分布式锁。
3. 实现发布前校验。
4. 批量更新 GENERATED 为 PUBLISHED。
5. 写入发布批次和状态日志。
6. 删除 Redis 缓存。

### Day 3：撤销流程

1. 实现撤销接口。
2. 实现撤销锁。
3. 更新状态为 REVOKED。
4. 写入状态日志。
5. 删除 Redis 缓存。
6. 校验撤销后不可下载。

### Day 4：重发流程

1. 实现重发接口。
2. 实现重发锁。
3. 旧文件状态更新为 REISSUED。
4. 创建重发记录。
5. 创建新版本任务。
6. 发送新版本生成 MQ。
7. 删除 Redis 缓存。

### Day 5：本地消息表

1. 实现 LocalMessageService。
2. 发布、撤销、重发时写入本地消息表。
3. MQ 发送成功更新 SENT。
4. MQ 发送失败更新 FAILED。

### Day 6：对账补偿任务

1. 实现本地消息补偿任务。
2. 实现 GENERATING 超时恢复任务。
3. 实现 MinIO 文件存在性检查任务。
4. 对补偿任务加分布式锁。

### Day 7：测试和文档

1. 发布测试。
2. 撤销测试。
3. 重发测试。
4. 本地消息补偿测试。
5. 对账任务测试。
6. README 更新。
7. 接口文档更新。

---

## 23. 简历描述建议

```text
设计并实现结算文件发布、撤销、重发与一致性补偿机制。文件生成成功后先进入 GENERATED 状态，发布后变更为 PUBLISHED，下载服务只允许会员下载 PUBLISHED 文件。系统支持文件撤销 REVOKED 和重发 REISSUED，重发时通过 version + 1 创建新版本生成任务，避免覆盖旧文件。通过 Redisson 分布式锁保证同一结算日期文件不会被重复发布，通过本地消息表解决数据库状态变更与 MQ 事件投递之间的一致性问题，并通过定时对账任务补偿 MQ 发送失败、任务超时、MinIO 文件缺失等异常场景。
```

项目亮点：

```text
1. 设计 GENERATED、PUBLISHED、REVOKED、REISSUED 文件状态机，明确文件从生成到发布、撤销、重发的生命周期。
2. 使用 Redisson 分布式锁控制文件发布、撤销、重发，避免多实例重复操作。
3. 发布前校验任务完成情况、文件元数据完整性和 MinIO 文件存在性，保证发布文件可靠。
4. 下载服务只允许 PUBLISHED 文件下载，避免未确认文件被会员提前获取。
5. 重发文件时使用 version + 1 生成新版本，旧版本标记为 REISSUED，避免覆盖历史文件。
6. 使用本地消息表保证文件状态变更与 MQ 事件投递最终一致。
7. 设计对账补偿任务，自动扫描本地消息发送失败、生成任务超时、文件状态不一致和对象存储文件缺失等异常。
```

---

## 24. 面试可讲问题

### 24.1 为什么要引入 PUBLISHED 状态？

生成成功只代表文件已经生成并上传，不代表业务上已经确认可以对会员开放。引入 PUBLISHED 状态后，可以将“文件生成”和“文件发布”解耦，在发布前完成完整性校验、文件数量校验、MD5 校验和对象存储检查，避免错误文件被提前下载。

### 24.2 为什么只有 PUBLISHED 文件允许下载？

GENERATED 文件可能还没有经过业务确认，可能存在部分会员文件未生成、文件内容错误、对象存储缺失等问题。只有 PUBLISHED 表示该文件已经通过发布流程确认，可以对会员开放下载。

### 24.3 发布为什么需要分布式锁？

file-service 多实例部署后，可能出现两个实例同时发布同一结算日期文件。如果没有锁，可能导致重复更新状态、重复发送 MQ、重复删除缓存和发布批次混乱。使用 Redisson 分布式锁可以保证同一结算日期同一时间只有一个发布操作执行。

### 24.4 为什么需要本地消息表？

文件发布时需要同时更新数据库状态和发送 MQ 事件。数据库和 MQ 不在同一个事务中，可能出现数据库更新成功但 MQ 发送失败的问题。本地消息表可以在同一个数据库事务中记录待发送消息，事务提交后再发送 MQ。如果发送失败，补偿任务可以扫描本地消息表重试，保证最终一致性。

### 24.5 重发为什么不直接覆盖旧文件？

金融文件需要可追溯，不能覆盖历史文件。重发时通过 version + 1 生成新文件，旧文件状态改为 REISSUED，新文件重新生成并发布。这样可以保留历史版本，便于审计和问题追踪。

### 24.6 对账补偿任务解决什么问题？

对账补偿任务用于处理分布式系统中的异常状态，例如 MQ 发送失败、本地消息未发送、Worker 宕机导致任务长时间 GENERATING、数据库记录存在但 MinIO 文件缺失、文件状态和任务状态不一致等。它保证系统即使在部分失败场景下也能最终恢复到一致状态。

---

## 25. 总结

第五阶段将项目从“文件生成与下载系统”升级为“具备正式发布、撤销、重发和一致性补偿能力的结算文件平台”。

完成本阶段后，系统具备：

1. 文件发布流程。
2. `GENERATED -> PUBLISHED` 状态流转。
3. 只允许 `PUBLISHED` 文件下载。
4. 文件撤销 `REVOKED`。
5. 文件重发 `REISSUED`。
6. 版本化重发。
7. 文件发布分布式锁。
8. 发布、撤销、重发后的 Redis 缓存删除。
9. 本地消息表。
10. MQ 发送补偿。
11. 任务超时恢复。
12. MinIO 文件对账。
13. 文件状态日志。
14. 发布批次记录。

这一阶段是项目从“能生成文件”升级为“具备金融业务发布管控和分布式一致性保障”的关键阶段。
