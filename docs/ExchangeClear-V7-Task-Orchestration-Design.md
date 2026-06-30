# ExchangeClear 第七阶段：结算任务编排服务增强详细设计文档

## 1. 文档说明

本文档用于指导 `settle-task-service` 从“文件生成任务创建与投递服务”升级为“结算任务编排服务”。

当前 `settle-task-service` 已经具备以下基础能力：

1. 通过 REST 接口创建文件生成任务。
2. 按结算日期、文件类型、活跃会员拆分任务。
3. 使用 `settle_file_task` 表保存任务记录。
4. 通过 RocketMQ 投递文件生成任务。
5. 支持任务查询、手动同步生成、批量生成、批量投递和单任务重投。
6. 支持 `GENERATING` 超时任务补偿为 `FAILED`。

但是，距离完整的“结算编排服务”仍存在以下缺口：

1. 缺少结算完成事件消费入口。
2. 缺少事件驱动的任务拆分流程。
3. 缺少暂停和恢复能力。
4. 缺少任务优先级字段、排序规则和调度规则。
5. 缺少基于 `retry_count/max_retry_count` 的失败任务自动重试。
6. 补偿扫描只覆盖 `GENERATING` 超时，未覆盖待投递、发送失败、生成失败、已投递但未消费等场景。

本文档围绕以上缺口给出目标职责、状态机、数据库、MQ、接口、定时任务、实现步骤和测试方案。

---

## 2. 建设目标

### 2.1 业务目标

1. `settle-task-service` 可以接收“结算完成事件”。
2. 接收到结算完成事件后，自动按结算日期、会员、文件类型和版本拆分文件生成任务。
3. 任务创建具备幂等性，重复事件不会重复创建任务。
4. 创建任务后可以可靠投递文件生成 MQ。
5. 支持任务查询、暂停、恢复、重跑、重投和批量操作。
6. 支持任务优先级，优先处理更重要的任务。
7. 支持失败任务自动重试，并受最大重试次数控制。
8. 支持补偿扫描，自动处理异常滞留任务。
9. 支持多实例部署下的分布式锁，避免重复扫描和重复调度。
10. 保持和现有 `settle-worker-service`、`settle-file-service` 的兼容性。

### 2.2 技术目标

1. 新增结算完成事件消费者。
2. 新增结算完成事件消费记录，保证事件幂等。
3. 扩展 `settle_file_task` 表，补充优先级、来源事件、重试时间、暂停原因等字段。
4. 扩展 `TaskStatusEnum`，增加 `PAUSED` 状态。
5. 扩展任务状态流转校验。
6. 新增任务暂停、恢复、重跑接口。
7. 新增自动调度任务，扫描可投递任务并发送 MQ。
8. 新增失败任务重试任务，按重试次数和下次重试时间执行。
9. 新增 `SENT` 超时补偿，处理已投递但长时间未消费任务。
10. 调整查询接口，支持分页、状态、会员、文件类型、优先级和时间范围筛选。

---

## 3. 当前现状与缺口

### 3.1 当前已具备能力

| 能力 | 当前实现 |
|---|---|
| 手动创建任务 | `POST /api/tasks/create` |
| 按会员拆任务 | 查询活跃会员后循环创建 `settle_file_task` |
| 幂等创建 | `INSERT IGNORE` + `uk_task_biz(settle_date, member_id, file_type, version)` |
| MQ 投递 | `FileGenerateTaskProducer.syncSend` |
| 发送失败处理 | 更新任务为 `SEND_FAILED` |
| 查询任务 | `GET /api/tasks` |
| 手动重投 | `POST /api/tasks/{taskNo}/resend` |
| 批量投递 | `POST /api/tasks/send-batch` |
| 状态更新 | 内部接口 `POST /internal/tasks/{taskNo}/status` |
| 生成中超时补偿 | `GeneratingTaskTimeoutJob` |

### 3.2 当前缺口

| 缺口 | 影响 |
|---|---|
| 没有结算完成事件消费者 | 任务创建需要人工或其他系统调用 REST，编排链路不闭环 |
| 没有事件消费幂等记录 | 无法区分重复事件、处理中事件、失败事件 |
| 没有暂停状态 | 无法临时阻断异常任务继续投递或执行 |
| 没有恢复接口 | 任务暂停后无法通过标准接口恢复 |
| 没有优先级字段 | 任务只能按日期、会员、文件类型、版本固定排序 |
| 没有自动失败重试 | 失败任务主要依赖人工重投或 RocketMQ 消费重试 |
| 补偿扫描范围较窄 | `INIT`、`SEND_FAILED`、`FAILED`、`SENT` 超时任务不会自动恢复 |
| 查询接口未分页 | 任务量大时存在查询性能风险 |

---

## 4. 目标职责边界

### 4.1 task-service 目标职责

`settle-task-service` 负责结算文件生成任务的编排，不负责具体文件内容生成。

目标职责：

1. 接收结算完成事件。
2. 根据结算日期、会员、文件类型拆分文件生成任务。
3. 幂等创建任务记录。
4. 按优先级投递文件生成任务 MQ。
5. 管理任务状态。
6. 支持任务暂停、恢复、重投和重跑。
7. 扫描并补偿异常任务。
8. 提供任务查询和运维接口。

### 4.2 worker-service 职责保持不变

`settle-worker-service` 继续负责：

1. 消费文件生成 MQ。
2. 将任务状态从 `SENT/FAILED/INIT` 更新为 `GENERATING`。
3. 生成文件。
4. 上传对象存储。
5. 保存文件元数据。
6. 将任务更新为 `GENERATED` 或 `FAILED`。

### 4.3 file-service 职责保持不变

`settle-file-service` 继续负责：

1. 文件元数据保存。
2. 文件元数据查询。
3. 文件发布、撤销、重发等生命周期能力。

---

## 5. 总体架构

```text
结算服务 / 清算服务
    ↓
RocketMQ：settlement-complete-event
    ↓
settle-task-service
    ├── SettlementCompleteEventConsumer
    ├── TaskSplitService
    ├── TaskOrchestrationService
    ├── TaskDispatchService
    ├── TaskRetryService
    ├── TaskPauseService
    └── TaskCompensationJob
    ↓
MySQL：settle_task_event、settle_file_task
    ↓
RocketMQ：file-generate-task
    ↓
settle-worker-service
    ↓
settle-file-service
```

### 5.1 主链路

```text
结算完成
    ↓
发送结算完成事件
    ↓
task-service 消费事件
    ↓
记录事件消费状态
    ↓
按文件类型和会员拆分任务
    ↓
幂等写入 settle_file_task
    ↓
调度可投递任务
    ↓
发送 file-generate-task MQ
    ↓
worker-service 消费并生成文件
    ↓
更新任务状态
```

### 5.2 补偿链路

```text
TaskDispatchJob
    扫描 INIT / SEND_FAILED / FAILED 且允许投递的任务
    ↓
按优先级投递 MQ

TaskRetryJob
    扫描 FAILED / SEND_FAILED 且到达 next_retry_time 的任务
    ↓
判断 retry_count < max_retry_count
    ↓
重新投递 MQ

SentTimeoutJob
    扫描 SENT 且长时间未消费的任务
    ↓
回退为 INIT 或重新投递

GeneratingTaskTimeoutJob
    扫描 GENERATING 超时任务
    ↓
标记为 FAILED，等待重试或人工处理
```

---

## 6. 结算完成事件设计

### 6.1 Topic 和 Tag

Topic：

```text
settlement-complete-event
```

Tag：

```text
DEFAULT
TRADE
FUND
POSITION
```

第一版可以只支持 `TRADE`，但事件结构需要支持多文件类型扩展。

### 6.2 消息体

```json
{
  "eventId": "SETTLE_EVT_20260630_000001",
  "settleDate": "2026-06-30",
  "fileTypes": ["TRADE"],
  "version": 1,
  "priority": 100,
  "source": "settle-core",
  "completedAt": "2026-06-30T18:00:00",
  "remark": "日终结算完成"
}
```

### 6.3 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| eventId | String | 是 | 事件唯一编号，用于幂等 |
| settleDate | LocalDate | 是 | 结算日期 |
| fileTypes | List<String> | 是 | 需要生成的文件类型 |
| version | Integer | 否 | 文件版本，默认 1 |
| priority | Integer | 否 | 任务基础优先级，默认 0 |
| source | String | 否 | 事件来源系统 |
| completedAt | LocalDateTime | 否 | 结算完成时间 |
| remark | String | 否 | 备注 |

### 6.4 消费原则

1. `eventId` 必须全局唯一。
2. 消费前先写入或锁定事件消费记录。
3. 如果事件已经 `SUCCESS`，直接跳过。
4. 如果事件处于 `PROCESSING` 且未超时，跳过或稍后重试。
5. 如果事件处于 `FAILED`，允许补偿任务重新处理。
6. 单个事件处理失败不能影响其他事件消费。

---

## 7. 事件幂等表设计

新增表：

```sql
CREATE TABLE IF NOT EXISTS settle_task_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    event_id VARCHAR(128) NOT NULL COMMENT '事件ID',
    settle_date DATE NOT NULL COMMENT '结算日期',
    file_types VARCHAR(256) NOT NULL COMMENT '文件类型列表，逗号分隔',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    priority INT NOT NULL DEFAULT 0 COMMENT '基础优先级',
    source VARCHAR(64) DEFAULT NULL COMMENT '来源系统',
    status VARCHAR(32) NOT NULL COMMENT '状态：INIT/PROCESSING/SUCCESS/FAILED',
    task_created_count INT NOT NULL DEFAULT 0 COMMENT '本次创建任务数',
    task_exists_count INT NOT NULL DEFAULT 0 COMMENT '已存在任务数',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    started_at DATETIME DEFAULT NULL COMMENT '开始处理时间',
    finished_at DATETIME DEFAULT NULL COMMENT '处理完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_updated (status, updated_at),
    KEY idx_settle_date (settle_date)
) COMMENT='结算完成事件消费记录表';
```

### 7.1 事件状态

| 状态 | 说明 |
|---|---|
| INIT | 已接收，待处理 |
| PROCESSING | 处理中 |
| SUCCESS | 处理成功 |
| FAILED | 处理失败，等待补偿或人工处理 |

### 7.2 事件状态流转

```text
INIT
  ↓ 开始处理
PROCESSING
  ↓ 任务创建完成
SUCCESS

PROCESSING
  ↓ 异常
FAILED

FAILED
  ↓ 补偿重试
PROCESSING
```

---

## 8. 任务表扩展设计

当前 `settle_file_task` 已有：

```text
task_no
settle_date
member_id
file_type
version
status
retry_count
max_retry_count
last_message_id
last_send_time
last_consume_time
error_message
start_time
end_time
created_at
updated_at
```

建议补充字段：

```sql
ALTER TABLE settle_file_task
ADD COLUMN priority INT NOT NULL DEFAULT 0 COMMENT '任务优先级，数值越大越优先',
ADD COLUMN source_event_id VARCHAR(128) DEFAULT NULL COMMENT '来源结算完成事件ID',
ADD COLUMN dispatch_count INT NOT NULL DEFAULT 0 COMMENT 'MQ投递次数',
ADD COLUMN next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
ADD COLUMN last_retry_time DATETIME DEFAULT NULL COMMENT '最后重试时间',
ADD COLUMN paused_reason VARCHAR(1000) DEFAULT NULL COMMENT '暂停原因',
ADD COLUMN operator VARCHAR(64) DEFAULT NULL COMMENT '最近操作人';
```

建议新增索引：

```sql
CREATE INDEX idx_task_dispatch
ON settle_file_task(status, next_retry_time, priority, created_at);

CREATE INDEX idx_task_event
ON settle_file_task(source_event_id);

CREATE INDEX idx_task_query
ON settle_file_task(settle_date, file_type, status, member_id);
```

### 8.1 字段设计说明

| 字段 | 说明 |
|---|---|
| priority | 用于调度排序，数值越大越优先 |
| source_event_id | 任务来源事件，便于追踪和按事件补偿 |
| dispatch_count | MQ 投递次数，和业务失败重试次数区分 |
| next_retry_time | 控制失败任务何时允许再次调度 |
| last_retry_time | 最近一次重试时间 |
| paused_reason | 暂停原因 |
| operator | 最近一次人工操作人 |

### 8.2 兼容性说明

1. 新增字段都有默认值或允许为空，不影响旧数据。
2. 现有 `uk_task_biz(settle_date, member_id, file_type, version)` 保持不变。
3. 现有 `task_no` 唯一键保持不变。
4. 现有 `retry_count/max_retry_count` 保持不变，并纳入自动重试逻辑。

---

## 9. 任务状态机设计

### 9.1 状态枚举

建议扩展 `TaskStatusEnum`：

```java
public enum TaskStatusEnum {

    INIT("INIT", "待投递"),
    SENT("SENT", "已投递"),
    GENERATING("GENERATING", "生成中"),
    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "生成失败"),
    SEND_FAILED("SEND_FAILED", "消息发送失败"),
    PAUSED("PAUSED", "已暂停");
}
```

第一版不建议新增 `CANCELED`。结算文件任务和金融文件生成有审计要求，取消任务容易引入“任务不存在但业务应生成”的歧义。确实需要废弃任务时，可以后续增加独立的作废流程。

### 9.2 状态流转图

```text
             ┌──────────┐
             │   INIT   │
             └────┬─────┘
                  │ 投递成功
                  ▼
             ┌──────────┐
             │   SENT   │
             └────┬─────┘
                  │ Worker抢占
                  ▼
          ┌──────────────┐
          │  GENERATING  │
          └────┬─────┬───┘
               │     │
        生成成功│     │生成失败/超时
               ▼     ▼
        ┌──────────┐ ┌──────────┐
        │GENERATED │ │  FAILED  │
        └──────────┘ └────┬─────┘
                           │ 重试/重投
                           ▼
                         SENT

INIT / SENT / FAILED / SEND_FAILED
        │ 暂停
        ▼
     PAUSED
        │ 恢复
        ▼
     INIT 或 FAILED
```

### 9.3 允许的状态流转

| 当前状态 | 目标状态 | 触发方 | 说明 |
|---|---|---|---|
| INIT | SENT | task-service | MQ 投递成功 |
| INIT | SEND_FAILED | task-service | MQ 投递失败 |
| SENT | GENERATING | worker-service | Worker 开始生成 |
| INIT | GENERATING | worker-service | 兼容同步生成 |
| FAILED | GENERATING | worker-service | 兼容重跑 |
| GENERATING | GENERATED | worker-service | 文件生成成功 |
| GENERATING | FAILED | worker-service/job | 文件生成失败或超时 |
| SEND_FAILED | SENT | task-service | 自动重试或人工重投成功 |
| FAILED | SENT | task-service | 自动重试或人工重投成功 |
| INIT | PAUSED | user/job | 暂停未投递任务 |
| SENT | PAUSED | user/job | 暂停已投递但未消费任务，不能撤回已在 MQ 中的消息 |
| FAILED | PAUSED | user/job | 暂停失败任务，阻止自动重试 |
| SEND_FAILED | PAUSED | user/job | 暂停发送失败任务 |
| PAUSED | INIT | user | 恢复为待投递 |
| PAUSED | FAILED | user | 恢复为失败，等待重试 |

### 9.4 不允许的状态流转

| 当前状态 | 禁止操作 | 原因 |
|---|---|---|
| GENERATED | 暂停 | 已完成任务不需要暂停 |
| GENERATED | 重投 | 避免重复生成覆盖业务结果 |
| GENERATING | 暂停 | Worker 正在执行，暂停无法中断当前线程 |
| GENERATING | 重投 | 避免并发生成同一任务 |
| PAUSED | Worker 生成 | Worker 必须拒绝处理暂停任务 |

---

## 10. 任务拆分设计

### 10.1 拆分维度

任务按以下业务键拆分：

```text
settleDate + memberId + fileType + version
```

### 10.2 拆分来源

任务可以来自：

1. 结算完成事件。
2. 管理端手动创建。
3. 文件重发流程创建新版本任务。
4. 补偿任务发现缺失后创建。

### 10.3 拆分流程

```text
接收结算完成事件
    ↓
校验 settleDate
    ↓
校验 fileTypes
    ↓
查询活跃会员列表
    ↓
遍历 fileTypes
    ↓
遍历 members
    ↓
构建 SettleFileTask
    ↓
设置 priority、source_event_id、status=INIT
    ↓
INSERT IGNORE
    ↓
统计 createdCount / existsCount
```

### 10.4 任务优先级计算

任务最终优先级建议由三部分组成：

```text
finalPriority = eventPriority + fileTypePriority + memberPriority
```

第一版可以简化为：

```text
finalPriority = eventPriority
```

后续扩展：

| 维度 | 示例 |
|---|---|
| 文件类型优先级 | TRADE 高于 FUND，FUND 高于 POSITION |
| 会员优先级 | 核心会员高于普通会员 |
| 事件优先级 | 日终正式结算高于补偿重跑 |

---

## 11. 任务幂等设计

### 11.1 任务创建幂等

继续使用业务唯一键：

```text
uk_task_biz(settle_date, member_id, file_type, version)
```

创建时使用：

```sql
INSERT IGNORE INTO settle_file_task (...)
```

如果插入成功：

```text
createdCount + 1
```

如果插入被忽略：

```text
existsCount + 1
```

### 11.2 事件消费幂等

使用：

```text
uk_event_id(event_id)
```

消费流程：

```text
尝试插入 settle_task_event
    ↓
插入成功：新事件，继续处理
    ↓
插入失败：查询事件状态
        ↓
        SUCCESS：直接 ACK
        PROCESSING：判断是否超时
        FAILED：可重试
```

### 11.3 MQ 投递幂等

文件生成任务消息使用：

```text
message key = taskNo
```

Worker 侧继续使用任务状态抢占：

```sql
UPDATE settle_file_task
SET status = 'GENERATING'
WHERE task_no = ?
  AND status IN ('INIT', 'SENT', 'FAILED');
```

如果更新影响行数为 0，说明任务已被其他 Worker 处理或状态不允许处理。

---

## 12. 任务优先级排序设计

### 12.1 排序原则

调度任务时按以下顺序排序：

```text
priority DESC
settle_date ASC
created_at ASC
member_id ASC
file_type ASC
version ASC
```

说明：

1. 优先级数值越大越先处理。
2. 同优先级下，较早结算日期优先。
3. 同日期下，较早创建任务优先。
4. 会员、文件类型、版本作为稳定排序字段，避免分页漂移。

### 12.2 可投递任务查询 SQL

```sql
SELECT
    id,
    task_no,
    settle_date,
    member_id,
    file_type,
    version,
    status,
    retry_count,
    max_retry_count,
    priority,
    next_retry_time,
    last_message_id,
    last_send_time,
    last_consume_time,
    error_message,
    start_time,
    end_time,
    created_at,
    updated_at
FROM settle_file_task
WHERE status IN ('INIT', 'SEND_FAILED', 'FAILED')
  AND retry_count < max_retry_count
  AND (next_retry_time IS NULL OR next_retry_time <= NOW())
ORDER BY priority DESC,
         settle_date ASC,
         created_at ASC,
         member_id ASC,
         file_type ASC,
         version ASC
LIMIT #{limit};
```

### 12.3 为什么不调度 PAUSED

`PAUSED` 是人工或系统明确阻断状态，所有自动调度和补偿扫描必须排除。

---

## 13. MQ 投递设计

### 13.1 投递方式

继续使用 RocketMQ 同步发送：

```text
rocketMQTemplate.syncSend(destination, message)
```

### 13.2 投递成功

```sql
UPDATE settle_file_task
SET status = 'SENT',
    last_message_id = #{messageId},
    last_send_time = NOW(),
    dispatch_count = dispatch_count + 1,
    error_message = NULL
WHERE task_no = #{taskNo}
  AND status IN ('INIT', 'FAILED', 'SEND_FAILED');
```

### 13.3 投递失败

```sql
UPDATE settle_file_task
SET status = 'SEND_FAILED',
    last_send_time = NOW(),
    dispatch_count = dispatch_count + 1,
    next_retry_time = DATE_ADD(NOW(), INTERVAL #{retryDelaySeconds} SECOND),
    error_message = #{errorMessage}
WHERE task_no = #{taskNo}
  AND status IN ('INIT', 'FAILED', 'SEND_FAILED');
```

### 13.4 投递前校验

允许投递：

```text
INIT
FAILED
SEND_FAILED
```

谨慎允许：

```text
SENT
```

`SENT` 表示已经投递过，只有人工重投或 `SENT` 超时补偿可以重新投递。

禁止投递：

```text
GENERATING
GENERATED
PAUSED
```

---

## 14. 自动重试设计

### 14.1 重试对象

自动重试扫描以下状态：

```text
FAILED
SEND_FAILED
```

可选扫描：

```text
INIT
```

`INIT` 代表未投递任务，通常由调度任务扫描即可，不需要算作失败重试。

### 14.2 重试限制

必须满足：

```text
retry_count < max_retry_count
next_retry_time <= now 或 next_retry_time is null
status not in PAUSED/GENERATING/GENERATED
```

### 14.3 退避策略

第一版建议指数退避，最大不超过 30 分钟：

```text
delaySeconds = min(60 * 2 ^ retry_count, 1800)
```

示例：

| retry_count | 下次重试间隔 |
|---|---|
| 0 | 60 秒 |
| 1 | 120 秒 |
| 2 | 240 秒 |
| 3 | 480 秒 |
| 4+ | 最多 1800 秒 |

### 14.4 retry_count 语义

当前 Worker 在生成失败时会 `retry_count + 1`。

建议保持这个语义：

```text
retry_count = 文件生成失败次数
```

MQ 发送失败不建议增加 `retry_count`，否则 MQ 短暂不可用会消耗业务生成重试次数。

MQ 投递次数使用新增字段：

```text
dispatch_count
```

### 14.5 超过最大重试次数

当：

```text
retry_count >= max_retry_count
```

处理策略：

1. 自动重试任务不再投递。
2. 保持任务为 `FAILED`。
3. 查询接口展示错误信息和重试次数。
4. 允许人工 `resend` 或 `rerun`，但需要记录操作人。

---

## 15. 补偿扫描设计

### 15.1 补偿任务清单

| 任务 | 处理范围 | 第一版是否实现 |
|---|---|---|
| TaskDispatchJob | INIT、SEND_FAILED、FAILED 可投递任务 | 是 |
| GeneratingTaskTimeoutJob | GENERATING 超时任务 | 已有，需增强 |
| SentTimeoutJob | SENT 长时间未消费任务 | 是 |
| SettlementEventRetryJob | FAILED / PROCESSING 超时事件 | 是 |
| TaskFileConsistencyJob | 任务和文件元数据不一致 | 建议 |

### 15.2 TaskDispatchJob

职责：

```text
扫描可投递任务，按优先级发送 file-generate-task MQ。
```

配置项：

```properties
exchange-clear.task.dispatch.enabled=true
exchange-clear.task.dispatch.delay-millis=30000
exchange-clear.task.dispatch.batch-size=100
exchange-clear.task.dispatch.lock-wait-seconds=1
exchange-clear.task.dispatch.lock-lease-seconds=120
```

流程：

```text
获取分布式锁
    ↓
查询可投递任务
    ↓
逐条发送 MQ
    ↓
成功更新 SENT
    ↓
失败更新 SEND_FAILED 和 next_retry_time
    ↓
释放锁
```

### 15.3 GeneratingTaskTimeoutJob

当前已有该任务，建议增强为：

```text
1. 扫描 GENERATING 且 updated_at 超时的任务
2. 更新为 FAILED
3. retry_count + 1
4. 设置 next_retry_time
5. 由 TaskDispatchJob 后续自动重试
```

增强 SQL：

```sql
UPDATE settle_file_task
SET status = 'FAILED',
    end_time = NOW(),
    retry_count = retry_count + 1,
    next_retry_time = DATE_ADD(NOW(), INTERVAL #{retryDelaySeconds} SECOND),
    error_message = #{errorMessage}
WHERE status = 'GENERATING'
  AND updated_at < DATE_SUB(NOW(), INTERVAL #{timeoutMinutes} MINUTE);
```

### 15.4 SentTimeoutJob

问题：

```text
任务已经更新为 SENT，但 Worker 长时间没有消费。
```

可能原因：

1. MQ 消息丢失。
2. Consumer 暂停。
3. Worker 全部不可用。
4. Tag 或 consumer group 配置错误。

第一版处理策略：

```text
将超时 SENT 任务重新投递 MQ。
```

扫描条件：

```sql
SELECT *
FROM settle_file_task
WHERE status = 'SENT'
  AND last_send_time < DATE_SUB(NOW(), INTERVAL #{sentTimeoutMinutes} MINUTE)
ORDER BY priority DESC, last_send_time ASC
LIMIT #{batchSize};
```

注意：

1. 重新投递前再次查询状态。
2. 如果状态已变为 `GENERATING/GENERATED`，跳过。
3. 如果任务已暂停，跳过。

### 15.5 SettlementEventRetryJob

职责：

```text
补偿结算完成事件处理失败或处理中超时。
```

扫描条件：

```sql
SELECT *
FROM settle_task_event
WHERE status = 'FAILED'
   OR (status = 'PROCESSING' AND updated_at < DATE_SUB(NOW(), INTERVAL #{timeoutMinutes} MINUTE))
ORDER BY updated_at ASC
LIMIT #{batchSize};
```

处理方式：

1. 将事件状态更新为 `PROCESSING`。
2. 重新执行任务拆分。
3. 任务创建继续依赖 `uk_task_biz` 幂等。
4. 成功后更新事件为 `SUCCESS`。
5. 失败后更新事件为 `FAILED`。

### 15.6 TaskFileConsistencyJob

第一版建议只记录异常，不自动修复。

检查项：

1. `settle_file_task.status = GENERATED`，但 `settle_file` 不存在。
2. `settle_file` 存在，但任务状态不是 `GENERATED`。
3. 同一业务键存在任务，但文件版本不一致。
4. 任务 `GENERATED`，但文件元数据缺失 `file_md5/storage_path`。

处理策略：

```text
记录 warn 日志 + 可选写入异常表。
```

后续可以扩展：

```text
自动回退任务状态或自动重发。
```

---

## 16. 暂停与恢复设计

### 16.1 暂停场景

1. 某会员数据异常，暂停该会员任务。
2. 某文件类型生成逻辑异常，暂停该文件类型任务。
3. Worker 或下游服务异常，暂停批量任务防止失败扩大。
4. 人工排查问题时临时阻断自动重试。

### 16.2 单任务暂停接口

```http
POST /api/tasks/{taskNo}/pause
```

请求：

```json
{
  "operator": "admin",
  "reason": "会员数据异常，暂停生成"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskNo": "TASK202606300001",
    "status": "PAUSED"
  }
}
```

### 16.3 单任务恢复接口

```http
POST /api/tasks/{taskNo}/resume
```

请求：

```json
{
  "operator": "admin",
  "targetStatus": "INIT"
}
```

说明：

1. `targetStatus` 第一版只允许 `INIT` 或 `FAILED`。
2. 默认恢复到 `INIT`。
3. 恢复后由 `TaskDispatchJob` 自动投递。

### 16.4 批量暂停接口

```http
POST /api/tasks/pause-batch
```

请求：

```json
{
  "settleDate": "2026-06-30",
  "fileType": "TRADE",
  "memberId": "0001",
  "statuses": ["INIT", "FAILED", "SEND_FAILED", "SENT"],
  "operator": "admin",
  "reason": "批量暂停排查"
}
```

### 16.5 批量恢复接口

```http
POST /api/tasks/resume-batch
```

请求：

```json
{
  "settleDate": "2026-06-30",
  "fileType": "TRADE",
  "memberId": "0001",
  "targetStatus": "INIT",
  "operator": "admin"
}
```

### 16.6 暂停限制

允许暂停：

```text
INIT
SENT
FAILED
SEND_FAILED
```

禁止暂停：

```text
GENERATING
GENERATED
PAUSED
```

说明：

1. `GENERATING` 正在执行，暂停不能中断当前 Worker。
2. `GENERATED` 已完成，无需暂停。
3. `PAUSED` 重复暂停没有意义。

---

## 17. 重跑与重投设计

### 17.1 概念区分

| 操作 | 含义 |
|---|---|
| resend | 重投 MQ，不改变业务输入 |
| rerun | 重新执行生成流程，通常也通过投递 MQ 实现 |
| reissue | 文件重发，创建新版本任务 |

### 17.2 resend 适用场景

1. `SEND_FAILED`：上次 MQ 发送失败。
2. `FAILED`：生成失败后需要再次执行。
3. `INIT`：任务未投递，需要立即投递。
4. `SENT`：长时间未消费，人工确认后重投。

禁止：

```text
GENERATING
GENERATED
PAUSED
```

### 17.3 rerun 适用场景

第一版可以复用 `resend` 能力。

接口：

```http
POST /api/tasks/{taskNo}/rerun
```

请求：

```json
{
  "operator": "admin",
  "reason": "修复生成逻辑后重跑"
}
```

处理：

```text
1. 校验任务状态为 FAILED / SEND_FAILED / INIT / SENT
2. 如果状态为 PAUSED，要求先恢复
3. 如果状态为 GENERATED，拒绝，提示走文件重发流程
4. 调用投递逻辑发送 MQ
```

---

## 18. 查询接口设计

### 18.1 任务分页查询

```http
GET /api/tasks?pageNum=1&pageSize=20&settleDate=2026-06-30&fileType=TRADE&status=FAILED&memberId=0001
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "pageNum": 1,
    "pageSize": 20,
    "total": 1,
    "records": [
      {
        "taskNo": "TASK202606300001",
        "settleDate": "2026-06-30",
        "memberId": "0001",
        "fileType": "TRADE",
        "version": 1,
        "status": "FAILED",
        "priority": 100,
        "retryCount": 2,
        "maxRetryCount": 3,
        "nextRetryTime": "2026-06-30T18:05:00",
        "lastMessageId": "MQ001",
        "lastSendTime": "2026-06-30T18:00:00",
        "lastConsumeTime": "2026-06-30T18:00:02",
        "errorMessage": "文件生成失败"
      }
    ]
  }
}
```

### 18.2 建议查询条件

| 条件 | 说明 |
|---|---|
| settleDate | 结算日期 |
| memberId | 会员编号 |
| fileType | 文件类型 |
| version | 文件版本 |
| status | 任务状态 |
| minPriority | 最小优先级 |
| sourceEventId | 来源事件 |
| createdStartTime | 创建开始时间 |
| createdEndTime | 创建结束时间 |

### 18.3 查询排序

默认排序：

```text
settle_date DESC
priority DESC
created_at DESC
member_id ASC
file_type ASC
version ASC
```

---

## 19. 内部接口调整

### 19.1 状态更新接口

现有接口：

```http
POST /internal/tasks/{taskNo}/status
```

保留，用于 worker-service 更新任务状态。

需要调整：

1. 支持 `PAUSED` 判断。
2. Worker 不能将 `PAUSED` 更新为 `GENERATING`。
3. `FAILED` 时更新 `retry_count`、`next_retry_time`。
4. `GENERATED` 时清空 `next_retry_time`。

### 19.2 任务计数接口

现有：

```http
GET /internal/tasks/unfinished-count
GET /internal/tasks/generated-count
```

建议增加：

```http
GET /internal/tasks/status-count
```

用途：

```text
按 settleDate/fileType/version 聚合统计各状态任务数量。
```

响应：

```json
{
  "INIT": 10,
  "SENT": 5,
  "GENERATING": 2,
  "GENERATED": 380,
  "FAILED": 3,
  "SEND_FAILED": 0,
  "PAUSED": 1
}
```

---

## 20. 分布式锁设计

### 20.1 锁 Key

| 场景 | Redis Key |
|---|---|
| 事件处理 | `exchange-clear:lock:task:event:{eventId}` |
| 自动投递扫描 | `exchange-clear:lock:task:dispatch` |
| 失败任务重试 | `exchange-clear:lock:task:retry` |
| SENT 超时补偿 | `exchange-clear:lock:task:sent-timeout` |
| 生成中超时补偿 | `exchange-clear:lock:reconcile:generating-timeout` |
| 单任务重投 | `exchange-clear:lock:task:resend:{taskNo}` |
| 单任务暂停/恢复 | `exchange-clear:lock:task:operation:{taskNo}` |

### 20.2 锁配置

```properties
exchange-clear.lock.task-event.wait-seconds=3
exchange-clear.lock.task-event.lease-seconds=120
exchange-clear.lock.task-dispatch.wait-seconds=1
exchange-clear.lock.task-dispatch.lease-seconds=120
exchange-clear.lock.task-operation.wait-seconds=3
exchange-clear.lock.task-operation.lease-seconds=30
```

---

## 21. 配置项设计

```properties
# 结算完成事件消费
exchange-clear.mq.topic.settlement-complete-event=settlement-complete-event
exchange-clear.mq.tag.settlement-complete-default=DEFAULT

# 任务调度
exchange-clear.task.dispatch.enabled=true
exchange-clear.task.dispatch.delay-millis=30000
exchange-clear.task.dispatch.batch-size=100

# 失败重试
exchange-clear.task.retry.enabled=true
exchange-clear.task.retry.delay-millis=60000
exchange-clear.task.retry.batch-size=100
exchange-clear.task.retry.initial-delay-seconds=60
exchange-clear.task.retry.max-delay-seconds=1800

# SENT 超时补偿
exchange-clear.task.sent-timeout.enabled=true
exchange-clear.task.sent-timeout.minutes=10
exchange-clear.task.sent-timeout.delay-millis=60000
exchange-clear.task.sent-timeout.batch-size=100

# GENERATING 超时补偿
exchange-clear.reconcile.generating-timeout-enabled=true
exchange-clear.reconcile.generating-timeout-minutes=30
exchange-clear.reconcile.generating-timeout-delay-millis=60000

# 事件补偿
exchange-clear.task.event-retry.enabled=true
exchange-clear.task.event-retry.processing-timeout-minutes=10
exchange-clear.task.event-retry.delay-millis=60000
exchange-clear.task.event-retry.batch-size=50
```

---

## 22. 代码结构建议

```text
settle-task-service
├── src/main/java/com/wuxx/exchangeclear/task
│   ├── controller
│   │   ├── TaskController.java
│   │   ├── InternalTaskController.java
│   │   └── TaskOperationController.java
│   ├── service
│   │   ├── TaskService.java
│   │   ├── TaskSplitService.java
│   │   ├── TaskDispatchService.java
│   │   ├── TaskRetryService.java
│   │   ├── TaskOperationService.java
│   │   └── SettlementTaskEventService.java
│   ├── job
│   │   ├── TaskDispatchJob.java
│   │   ├── TaskRetryJob.java
│   │   ├── SentTimeoutJob.java
│   │   ├── SettlementEventRetryJob.java
│   │   └── GeneratingTaskTimeoutJob.java
│   ├── lock
│   │   ├── TaskResendLockService.java
│   │   ├── TaskDispatchLockService.java
│   │   └── TaskOperationLockService.java
│   ├── mapper
│   │   ├── SettleFileTaskMapper.java
│   │   └── SettlementTaskEventMapper.java
│   └── entity
│       ├── SettleFileTask.java
│       └── SettlementTaskEvent.java
├── src/main/java/com/wuxx/exchangeclear/mq
│   ├── consumer
│   │   └── SettlementCompleteEventConsumer.java
│   ├── message
│   │   └── SettlementCompleteEventMessage.java
│   └── producer
│       └── FileGenerateTaskProducer.java
└── src/main/resources/mapper
    └── task
        ├── SettleFileTaskMapper.xml
        └── SettlementTaskEventMapper.xml
```

---

## 23. 关键类职责

### 23.1 SettlementCompleteEventConsumer

职责：

1. 消费 `settlement-complete-event`。
2. 校验消息格式。
3. 调用 `SettlementTaskEventService.handleEvent`。
4. 异常时抛出，让 RocketMQ 触发消费重试。

### 23.2 SettlementTaskEventService

职责：

1. 事件幂等处理。
2. 维护 `settle_task_event` 状态。
3. 调用 `TaskSplitService` 拆分任务。
4. 记录创建数量和已存在数量。

### 23.3 TaskSplitService

职责：

1. 根据事件查询活跃会员。
2. 按文件类型和会员生成任务。
3. 设置任务优先级和来源事件。
4. 幂等插入任务。

### 23.4 TaskDispatchService

职责：

1. 查询可投递任务。
2. 按优先级排序。
3. 投递文件生成 MQ。
4. 更新任务为 `SENT` 或 `SEND_FAILED`。

### 23.5 TaskRetryService

职责：

1. 计算下次重试时间。
2. 判断是否超过最大重试次数。
3. 扫描失败任务并重新投递。

### 23.6 TaskOperationService

职责：

1. 暂停任务。
2. 恢复任务。
3. 手动重投。
4. 手动重跑。
5. 批量暂停和批量恢复。

---

## 24. 事务设计

### 24.1 事件处理事务

事件处理建议分两步：

```text
事务 1：
    记录或更新 settle_task_event 为 PROCESSING

事务 2：
    拆分并插入 settle_file_task
    更新 settle_task_event 为 SUCCESS
```

原因：

1. 事件记录应尽早落库。
2. 拆分任务可能较多，失败后需要留下事件状态供补偿。
3. 任务创建依赖唯一键幂等，重试安全。

### 24.2 MQ 投递事务

第一版沿用现状：

```text
先插入任务，再发送 MQ，发送成功更新 SENT，发送失败更新 SEND_FAILED。
```

增强点：

1. 自动调度任务可以补偿 `INIT/SEND_FAILED`。
2. 发送失败不会丢任务。
3. 不强制引入本地消息表，降低改造范围。

后续可选：

```text
引入 local_message 表，将任务创建和待发送消息放入同一事务。
```

---

## 25. 接口清单

### 25.1 对外接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/tasks/create` | POST | 手动创建任务，保留兼容 |
| `/api/tasks` | GET | 分页查询任务 |
| `/api/tasks/{taskNo}/resend` | POST | 单任务重投 |
| `/api/tasks/{taskNo}/rerun` | POST | 单任务重跑 |
| `/api/tasks/{taskNo}/pause` | POST | 单任务暂停 |
| `/api/tasks/{taskNo}/resume` | POST | 单任务恢复 |
| `/api/tasks/pause-batch` | POST | 批量暂停 |
| `/api/tasks/resume-batch` | POST | 批量恢复 |
| `/api/tasks/send-batch` | POST | 批量投递，保留兼容 |

### 25.2 内部接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `/internal/tasks` | POST | 创建单任务，保留兼容 |
| `/internal/tasks/{taskNo}/status` | POST | Worker 更新状态 |
| `/internal/tasks/unfinished-count` | GET | 未完成任务数量 |
| `/internal/tasks/generated-count` | GET | 已生成任务数量 |
| `/internal/tasks/status-count` | GET | 按状态统计任务数量 |

---

## 26. SQL 调整清单

### 26.1 新增事件表

```sql
CREATE TABLE IF NOT EXISTS settle_task_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    event_id VARCHAR(128) NOT NULL COMMENT '事件ID',
    settle_date DATE NOT NULL COMMENT '结算日期',
    file_types VARCHAR(256) NOT NULL COMMENT '文件类型列表，逗号分隔',
    version INT NOT NULL DEFAULT 1 COMMENT '文件版本',
    priority INT NOT NULL DEFAULT 0 COMMENT '基础优先级',
    source VARCHAR(64) DEFAULT NULL COMMENT '来源系统',
    status VARCHAR(32) NOT NULL COMMENT '状态：INIT/PROCESSING/SUCCESS/FAILED',
    task_created_count INT NOT NULL DEFAULT 0 COMMENT '本次创建任务数',
    task_exists_count INT NOT NULL DEFAULT 0 COMMENT '已存在任务数',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    started_at DATETIME DEFAULT NULL COMMENT '开始处理时间',
    finished_at DATETIME DEFAULT NULL COMMENT '处理完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_updated (status, updated_at),
    KEY idx_settle_date (settle_date)
) COMMENT='结算完成事件消费记录表';
```

### 26.2 扩展任务表

```sql
ALTER TABLE settle_file_task
ADD COLUMN priority INT NOT NULL DEFAULT 0 COMMENT '任务优先级，数值越大越优先',
ADD COLUMN source_event_id VARCHAR(128) DEFAULT NULL COMMENT '来源结算完成事件ID',
ADD COLUMN dispatch_count INT NOT NULL DEFAULT 0 COMMENT 'MQ投递次数',
ADD COLUMN next_retry_time DATETIME DEFAULT NULL COMMENT '下次重试时间',
ADD COLUMN last_retry_time DATETIME DEFAULT NULL COMMENT '最后重试时间',
ADD COLUMN paused_reason VARCHAR(1000) DEFAULT NULL COMMENT '暂停原因',
ADD COLUMN operator VARCHAR(64) DEFAULT NULL COMMENT '最近操作人';
```

### 26.3 新增索引

```sql
CREATE INDEX idx_task_dispatch
ON settle_file_task(status, next_retry_time, priority, created_at);

CREATE INDEX idx_task_event
ON settle_file_task(source_event_id);

CREATE INDEX idx_task_query
ON settle_file_task(settle_date, file_type, status, member_id);
```

---

## 27. 开发步骤建议

### 27.1 第一阶段：表结构和状态机

1. 新增 `settle_task_event` 表。
2. 扩展 `settle_file_task` 字段。
3. 扩展 `TaskStatusEnum`，新增 `PAUSED`。
4. 扩展 `SettleFileTask` 实体。
5. 调整 Mapper XML 字段映射。

### 27.2 第二阶段：事件消费和任务拆分

1. 新增 `SettlementCompleteEventMessage`。
2. 新增 `SettlementCompleteEventConsumer`。
3. 新增 `SettlementTaskEventService`。
4. 新增 `TaskSplitService`。
5. 实现事件幂等处理。
6. 实现事件失败记录和补偿入口。

### 27.3 第三阶段：优先级调度

1. 新增 `TaskDispatchService`。
2. 新增 `TaskDispatchJob`。
3. 调整可投递任务 SQL。
4. 按优先级排序投递 MQ。
5. 增加分布式锁。

### 27.4 第四阶段：暂停、恢复、重跑

1. 新增暂停接口。
2. 新增恢复接口。
3. 新增重跑接口。
4. 新增批量暂停和批量恢复接口。
5. 调整状态流转校验。
6. Worker 侧拒绝处理 `PAUSED`。

### 27.5 第五阶段：自动重试和补偿

1. 新增 `TaskRetryJob`。
2. 新增 `SentTimeoutJob`。
3. 增强 `GeneratingTaskTimeoutJob`。
4. 新增 `SettlementEventRetryJob`。
5. 增加 retry/backoff 配置。

### 27.6 第六阶段：查询和运维能力

1. 查询接口改为分页。
2. 增加多条件筛选。
3. 增加状态统计接口。
4. 增加任务详情接口。
5. 增加补偿任务日志。

---

## 28. 测试方案

### 28.1 单元测试

| 测试类 | 测试重点 |
|---|---|
| `TaskSplitServiceTest` | 按会员和文件类型拆分任务 |
| `SettlementTaskEventServiceTest` | 事件幂等、重复事件、失败事件 |
| `TaskDispatchServiceTest` | 投递成功、投递失败、状态不允许投递 |
| `TaskRetryServiceTest` | 重试次数、退避时间、超过最大次数 |
| `TaskOperationServiceTest` | 暂停、恢复、重跑状态流转 |

### 28.2 集成测试

1. 发送结算完成事件后生成任务。
2. 重复发送同一 `eventId` 不重复创建任务。
3. 重复发送不同 `eventId` 但相同业务键，不重复创建任务。
4. `TaskDispatchJob` 按优先级投递任务。
5. MQ 投递失败后任务变为 `SEND_FAILED`。
6. 失败任务到达 `next_retry_time` 后自动重试。
7. `PAUSED` 任务不会被自动投递。
8. `SENT` 超时任务会被重新投递。
9. `GENERATING` 超时任务会变为 `FAILED`。

### 28.3 回归测试

1. 原 `POST /api/tasks/create` 仍可创建任务。
2. 原 `GET /api/tasks` 查询兼容。
3. 原 `POST /api/tasks/{taskNo}/resend` 仍可使用。
4. Worker 原状态更新接口仍可使用。
5. 文件生成成功后仍能保存文件元数据。

---

## 29. 验收标准

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | task-service 可以消费结算完成事件 | 必须 |
| 2 | 重复事件不会重复创建任务 | 必须 |
| 3 | 相同业务键任务不会重复创建 | 必须 |
| 4 | 可以按结算日期、会员、文件类型、版本拆分任务 | 必须 |
| 5 | 创建任务后可以投递文件生成 MQ | 必须 |
| 6 | MQ 发送失败后任务进入 SEND_FAILED | 必须 |
| 7 | SEND_FAILED 可以自动重试 | 必须 |
| 8 | FAILED 可以按 retry_count/max_retry_count 自动重试 | 必须 |
| 9 | 超过最大重试次数后停止自动重试 | 必须 |
| 10 | 任务可以暂停 | 必须 |
| 11 | 暂停任务不会被自动投递 | 必须 |
| 12 | 任务可以恢复 | 必须 |
| 13 | 支持任务重跑或重投 | 必须 |
| 14 | 调度按 priority DESC 排序 | 必须 |
| 15 | GENERATING 超时可以补偿为 FAILED | 必须 |
| 16 | SENT 超时可以补偿重投 | 建议 |
| 17 | 查询接口支持分页 | 必须 |
| 18 | 提供状态统计接口 | 建议 |

---

## 30. 风险与注意事项

### 30.1 重复投递风险

MQ 可能重复投递，补偿任务也可能重复投递。

控制方式：

1. Worker 使用任务状态抢占。
2. `GENERATED` 任务禁止再次生成。
3. 重投接口禁止 `GENERATING/GENERATED`。

### 30.2 暂停不能撤回已发送 MQ

任务状态为 `SENT` 后暂停，只能阻止后续自动补偿投递，不能撤回 RocketMQ 中已经存在的消息。

Worker 必须在执行前重新查询任务状态，如果已变为 `PAUSED`，应跳过。

### 30.3 自动重试可能放大故障

如果下游 Worker、对象存储或数据库持续异常，自动重试可能造成流量放大。

控制方式：

1. 指数退避。
2. 最大重试次数。
3. 批量大小限制。
4. 支持全局关闭自动重试。
5. 支持暂停指定日期、会员、文件类型任务。

### 30.4 查询性能风险

任务量大后，无分页查询会影响数据库。

控制方式：

1. 查询接口分页。
2. 增加组合索引。
3. 限制 pageSize 最大值。
4. 大范围查询建议走导出任务，不直接返回全量。

### 30.5 状态机兼容风险

新增 `PAUSED` 后，所有判断任务可投递、可生成、可更新的地方都必须同步调整。

重点检查：

1. `TaskService.allowSend`
2. `TaskService.validateInternalStatusTransition`
3. `FileGenerateWorker.allowGenerate`
4. `SettleFileTaskMapper.selectPendingTasks`
5. `SettleFileTaskMapper.selectTasksForSend`

---

## 31. 推荐最小落地方案

如果希望按最小改动快速补齐核心能力，建议第一轮只做：

1. 新增结算完成事件消费者。
2. 新增 `settle_task_event` 表。
3. 事件消费后复用现有 `TaskService.createTasks` 的拆分逻辑。
4. 扩展任务表增加 `priority/source_event_id/next_retry_time/dispatch_count`。
5. 新增 `PAUSED` 状态和暂停/恢复接口。
6. 新增 `TaskDispatchJob`，扫描 `INIT/SEND_FAILED/FAILED` 并投递。
7. 增强 `GeneratingTaskTimeoutJob`，让超时任务可进入自动重试链路。
8. 查询接口补充分页。

这一轮完成后，核心职责覆盖情况：

| 职责 | 覆盖情况 |
|---|---|
| 接收结算完成事件 | 覆盖 |
| 任务拆分 | 覆盖 |
| 创建任务记录 | 覆盖 |
| 投递 MQ | 覆盖 |
| 查询、暂停、恢复、重跑 | 覆盖 |
| 任务幂等 | 覆盖 |
| 优先级排序 | 覆盖 |
| 状态管理 | 基本覆盖 |
| 失败重试 | 基本覆盖 |
| 补偿扫描 | 基本覆盖 |

---

## 32. 总结

本阶段的核心是让 `settle-task-service` 从“被动创建任务”升级为“事件驱动的结算任务编排服务”。

完成后，系统应具备以下闭环：

```text
结算完成事件
    ↓
任务幂等拆分
    ↓
优先级调度
    ↓
MQ 投递
    ↓
Worker 生成
    ↓
状态回写
    ↓
失败重试和补偿扫描
```

这套设计优先复用当前项目已有的表结构、状态更新方式、RocketMQ 投递方式和 Redisson 分布式锁模式，避免引入重量级工作流框架。

后续如果任务依赖关系、审批、人工干预、跨批次编排变复杂，再考虑引入独立调度引擎或工作流引擎。当前阶段不建议过早引入复杂框架，保持 Spring Boot + MyBatis + RocketMQ + Redis 的简单可控实现即可。
