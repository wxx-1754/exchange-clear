# ExchangeClear 第六阶段：审计服务详细设计文档

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第六阶段的开发。

前五个阶段已经完成或规划完成：

1. **第一阶段：单体闭环**：文件生成任务创建、结算文件生成、MinIO 上传、文件元数据入库、文件查询与下载。
2. **第二阶段：引入 RocketMQ**：文件生成任务异步投递、Worker 消费任务、MQ 重试和消费幂等。
3. **第三阶段：拆分微服务**：拆分 task-service、file-service、worker-service、download-service，接入 Nacos、Gateway、OpenFeign。
4. **第四阶段：引入 Redis**：文件列表缓存、文件元数据缓存、下载 Token、会员级限流、IP 级限流、分布式锁。
5. **第五阶段：文件发布、重发与一致性补偿**：引入 GENERATED、PUBLISHED、REVOKED、REISSUED 状态，支持发布、撤销、重发、本地消息表和对账补偿。

第六阶段用于补齐完整审计能力：

```text
会员请求下载
    ↓
download-service 完成 Token 校验、权限校验、文件状态校验、限流和下载
    ↓
发送下载审计消息到 RocketMQ
    ↓
settle-audit-service 消费审计消息
    ↓
写入 file_download_audit 审计表
    ↓
支持审计查询和异常下载统计
```

本阶段完成后，系统不只是能生成、发布和下载文件，还能完整追踪会员下载行为，满足金融系统对可追溯、可审计、可定位问题的要求。

---

## 2. 第六阶段目标

### 2.1 业务目标

1. 记录会员下载行为。
2. 记录文件下载成功、失败、拒绝、限流、Token 校验失败等结果。
3. 记录下载 IP、User-Agent、下载开始时间、结束时间、耗时。
4. 记录会员编号、文件编号、文件类型、结算日期、文件版本。
5. 支持按会员、结算日期、文件编号、IP 查询下载审计。
6. 支持统计某文件是否被下载、下载次数、最后下载时间。
7. 支持异常下载行为统计。
8. 支持定位下载失败原因。
9. 不让审计落库影响正常下载主链路。
10. 通过 RocketMQ 异步解耦 download-service 和 audit-service。

### 2.2 技术目标

1. 新增 `settle-audit-service`。
2. 新增 RocketMQ Topic：`file.download.audit`。
3. download-service 在下载链路中发送审计消息。
4. audit-service 消费审计消息并写入数据库。
5. 审计消费支持幂等。
6. 审计消息发送失败不影响下载主流程。
7. 审计落库失败通过 MQ 重试。
8. 支持审计查询接口。
9. 支持异常下载统计接口。
10. 支持审计数据按时间维度查询和归档扩展。

---

## 3. 阶段范围

### 3.1 本阶段要做

1. 新增审计服务 `settle-audit-service`。
2. 新增下载审计表 `file_download_audit`。
3. 新增 RocketMQ Topic `file.download.audit`。
4. download-service 在下载成功、失败、拒绝、限流等场景发送审计消息。
5. audit-service 消费审计消息。
6. audit-service 审计消息幂等落库。
7. 支持审计列表查询。
8. 支持审计详情查询。
9. 支持会员维度下载统计。
10. 支持文件维度下载统计。
11. 支持异常下载统计。
12. 支持按 IP 查询异常行为。
13. 支持对审计 MQ 消费失败进行重试。

### 3.2 本阶段暂不做

1. 不做复杂风控系统。
2. 不做实时告警平台。
3. 不做 ELK 日志检索平台。
4. 不做审计数据冷热分离。
5. 不做大数据离线分析。
6. 不做监管报送。
7. 不做复杂权限审批。
8. 不做审计数据脱敏平台。
9. 不做审计数据永久归档。

这些能力可以作为后续扩展。

---

## 4. 审计服务定位

### 4.1 为什么需要独立审计服务

下载审计属于典型的非主链路能力。如果 download-service 每次下载都同步写审计表，会产生以下问题：

1. 下载接口响应变慢。
2. 审计库异常会影响正常下载。
3. 下载并发较高时，审计写入会增加数据库压力。
4. 审计逻辑和下载逻辑耦合。
5. 后续审计统计、异常分析、归档扩展困难。

因此采用异步审计模式：

```text
download-service
    ↓ 发送审计 MQ
RocketMQ
    ↓
settle-audit-service
    ↓
file_download_audit
```

这样可以做到：

1. 下载链路和审计链路解耦。
2. 审计写入失败可以 MQ 重试。
3. 审计服务可以独立扩容。
4. 后续可以单独优化审计查询和统计。
5. 审计能力不会污染下载服务核心逻辑。

---

## 5. 总体架构设计

### 5.1 架构图

```text
                            ┌────────────────────┐
                            │     会员系统/用户端  │
                            └─────────┬──────────┘
                                      │
                                      ▼
                            ┌────────────────────┐
                            │       Gateway       │
                            │  透传会员/IP/请求ID  │
                            └─────────┬──────────┘
                                      │
                                      ▼
                            ┌────────────────────┐
                            │  download-service   │
                            │ Token校验/限流/下载   │
                            └─────────┬──────────┘
                                      │ 下载文件
                                      ▼
                            ┌────────────────────┐
                            │        MinIO        │
                            │      文件对象存储     │
                            └────────────────────┘

download-service 同时异步发送审计消息：

                            ┌────────────────────┐
                            │  download-service   │
                            └─────────┬──────────┘
                                      │ 发送 file.download.audit
                                      ▼
                            ┌────────────────────┐
                            │      RocketMQ       │
                            │ file.download.audit │
                            └─────────┬──────────┘
                                      │ 消费
                                      ▼
                            ┌────────────────────┐
                            │ settle-audit-service│
                            │ 审计消费/查询/统计    │
                            └─────────┬──────────┘
                                      │
                                      ▼
                            ┌────────────────────┐
                            │        MySQL        │
                            │ file_download_audit │
                            └────────────────────┘
```

---

## 6. 服务职责设计

### 6.1 download-service 职责调整

download-service 继续负责：

1. 下载 Token 校验。
2. 会员权限校验。
3. 文件状态校验。
4. 会员级限流。
5. IP 级限流。
6. 从 MinIO 下载文件。

第六阶段新增职责：

1. 生成下载审计事件。
2. 在成功、失败、拒绝等场景发送审计 MQ。
3. 尽量保证审计消息发送不影响下载主流程。
4. 审计消息中携带完整下载上下文。

### 6.2 settle-audit-service 职责

settle-audit-service 负责：

1. 消费下载审计消息。
2. 将审计记录落库。
3. 保证审计消息幂等。
4. 提供审计查询接口。
5. 提供异常下载统计接口。
6. 提供文件下载统计接口。
7. 提供会员下载统计接口。
8. 支持后续审计报表扩展。

### 6.3 RocketMQ 职责

RocketMQ 负责：

1. 承载下载审计消息。
2. 解耦下载服务和审计服务。
3. 在 audit-service 消费失败时进行重试。
4. 支持审计服务多实例消费。
5. 支持后续死信队列排查。

---

## 7. 审计事件类型设计

### 7.1 审计操作类型

```java
public enum AuditActionEnum {

    CREATE_TOKEN("CREATE_TOKEN", "创建下载Token"),
    DOWNLOAD_FILE("DOWNLOAD_FILE", "下载文件");

    private final String code;
    private final String desc;
}
```

### 7.2 下载结果状态

```java
public enum DownloadStatusEnum {

    SUCCESS("SUCCESS", "下载成功"),
    FAILED("FAILED", "下载失败"),
    DENIED("DENIED", "权限拒绝"),
    TOKEN_INVALID("TOKEN_INVALID", "Token无效"),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "Token过期"),
    LIMITED("LIMITED", "请求被限流"),
    FILE_NOT_FOUND("FILE_NOT_FOUND", "文件不存在"),
    FILE_NOT_PUBLISHED("FILE_NOT_PUBLISHED", "文件未发布"),
    FILE_REVOKED("FILE_REVOKED", "文件已撤销"),
    FILE_REISSUED("FILE_REISSUED", "文件已重发");

    private final String code;
    private final String desc;
}
```

### 7.3 审计记录触发场景

| 场景 | 是否记录审计 | 状态 |
|---|---|---|
| 下载成功 | 是 | SUCCESS |
| MinIO 下载异常 | 是 | FAILED |
| 文件不存在 | 是 | FILE_NOT_FOUND |
| Token 不存在 | 是 | TOKEN_INVALID |
| Token 过期 | 是 | TOKEN_EXPIRED |
| 非文件所属会员下载 | 是 | DENIED |
| 文件未发布 | 是 | FILE_NOT_PUBLISHED |
| 文件已撤销 | 是 | FILE_REVOKED |
| 文件已重发 | 是 | FILE_REISSUED |
| 会员级限流 | 是 | LIMITED |
| IP 级限流 | 是 | LIMITED |
| 创建 Token 成功 | 可选 | SUCCESS |
| 创建 Token 失败 | 可选 | FAILED / DENIED |

建议第一版重点记录：

1. 文件下载成功。
2. 文件下载失败。
3. Token 校验失败。
4. 权限拒绝。
5. 文件状态不允许下载。
6. 请求被限流。

Token 创建行为可以作为可选审计项。

---

## 8. RocketMQ 设计

### 8.1 Topic 设计

```text
file.download.audit
```

用于承载文件下载审计消息。

### 8.2 Consumer Group

```text
exchange-clear-download-audit-consumer-group
```

说明：

1. settle-audit-service 使用该 Consumer Group。
2. 多个 audit-service 实例使用同一个 Consumer Group。
3. RocketMQ 在多个实例之间负载均衡审计消息。

### 8.3 Tag 设计

| Tag | 说明 |
|---|---|
| DOWNLOAD_SUCCESS | 下载成功 |
| DOWNLOAD_FAILED | 下载失败 |
| DOWNLOAD_DENIED | 下载拒绝 |
| DOWNLOAD_LIMITED | 下载限流 |
| TOKEN_INVALID | Token 无效 |
| TOKEN_EXPIRED | Token 过期 |

第一版也可以统一使用：

```text
DOWNLOAD
```

但推荐按结果分类，便于 RocketMQ 控制台排查。

### 8.4 消息 Key

使用审计编号：

```text
auditNo
```

示例：

```text
AUDIT202606260001
```

用途：

1. RocketMQ 控制台按 Key 查询。
2. 审计消费幂等。
3. 日志链路追踪。

### 8.5 审计消息体

```java
public class DownloadAuditMessage {

    private String auditNo;
    private String requestId;
    private String action;
    private String downloadStatus;
    private String failReason;

    private String fileNo;
    private String fileName;
    private String fileType;
    private LocalDate settleDate;
    private String memberId;
    private Integer version;

    private String clientIp;
    private String userAgent;
    private String tokenDigest;

    private Long fileSize;
    private Long downloadBytes;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long costMs;

    private Long eventTime;
}
```

### 8.6 消息示例

#### 下载成功消息

```json
{
  "auditNo": "AUDIT202606260001",
  "requestId": "REQ202606260001",
  "action": "DOWNLOAD_FILE",
  "downloadStatus": "SUCCESS",
  "failReason": null,
  "fileNo": "FILE202606260001",
  "fileName": "trade_0001_20260626.csv",
  "fileType": "TRADE",
  "settleDate": "2026-06-26",
  "memberId": "0001",
  "version": 1,
  "clientIp": "192.168.1.10",
  "userAgent": "Mozilla/5.0",
  "tokenDigest": "SHA256_TOKEN_DIGEST",
  "fileSize": 102400,
  "downloadBytes": 102400,
  "startTime": "2026-06-26 18:30:00",
  "endTime": "2026-06-26 18:30:02",
  "costMs": 2000,
  "eventTime": 1782479400000
}
```

#### Token 无效消息

```json
{
  "auditNo": "AUDIT202606260002",
  "requestId": "REQ202606260002",
  "action": "DOWNLOAD_FILE",
  "downloadStatus": "TOKEN_INVALID",
  "failReason": "下载Token不存在或已失效",
  "fileNo": "FILE202606260001",
  "memberId": "0001",
  "clientIp": "192.168.1.10",
  "userAgent": "Mozilla/5.0",
  "tokenDigest": "SHA256_TOKEN_DIGEST",
  "downloadBytes": 0,
  "startTime": "2026-06-26 18:31:00",
  "endTime": "2026-06-26 18:31:00",
  "costMs": 10,
  "eventTime": 1782479460000
}
```

---

## 9. 数据库设计

### 9.1 文件下载审计表：file_download_audit

```sql
CREATE TABLE file_download_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    audit_no VARCHAR(64) NOT NULL COMMENT '审计编号',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '请求ID',
    action VARCHAR(32) NOT NULL COMMENT '行为类型：CREATE_TOKEN/DOWNLOAD_FILE',
    download_status VARCHAR(32) NOT NULL COMMENT '下载结果状态',
    fail_reason VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',

    file_no VARCHAR(64) DEFAULT NULL COMMENT '文件编号',
    file_name VARCHAR(255) DEFAULT NULL COMMENT '文件名称',
    file_type VARCHAR(32) DEFAULT NULL COMMENT '文件类型',
    settle_date DATE DEFAULT NULL COMMENT '结算日期',
    member_id VARCHAR(32) DEFAULT NULL COMMENT '会员编号',
    version INT DEFAULT NULL COMMENT '文件版本',

    client_ip VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    user_agent VARCHAR(512) DEFAULT NULL COMMENT 'User-Agent',
    token_digest VARCHAR(128) DEFAULT NULL COMMENT 'Token摘要，不保存完整Token',

    file_size BIGINT DEFAULT NULL COMMENT '文件大小',
    download_bytes BIGINT DEFAULT NULL COMMENT '实际下载字节数',

    start_time DATETIME DEFAULT NULL COMMENT '下载开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '下载结束时间',
    cost_ms BIGINT DEFAULT NULL COMMENT '下载耗时毫秒',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    UNIQUE KEY uk_audit_no (audit_no),
    KEY idx_member_date (member_id, settle_date),
    KEY idx_file_no (file_no),
    KEY idx_status_created (download_status, created_at),
    KEY idx_client_ip_created (client_ip, created_at),
    KEY idx_created_at (created_at)
) COMMENT='文件下载审计表';
```

### 9.2 字段说明

| 字段 | 说明 |
|---|---|
| audit_no | 审计编号，全局唯一 |
| request_id | 请求链路 ID |
| action | 审计行为，创建 Token 或下载文件 |
| download_status | 下载结果 |
| fail_reason | 失败原因 |
| file_no | 文件编号 |
| file_name | 文件名称 |
| file_type | 文件类型 |
| settle_date | 结算日期 |
| member_id | 会员编号 |
| version | 文件版本 |
| client_ip | 客户端 IP |
| user_agent | 客户端 User-Agent |
| token_digest | Token 摘要，不保存完整 Token |
| file_size | 文件大小 |
| download_bytes | 下载字节数 |
| start_time | 下载开始时间 |
| end_time | 下载结束时间 |
| cost_ms | 下载耗时 |
| created_at | 审计记录创建时间 |

### 9.3 为什么不保存完整 Token

Token 属于下载授权凭证，即使有效期较短，也不建议完整落库。

建议保存：

```text
token_digest = SHA-256(token)
```

用途：

1. 支持排查。
2. 避免泄露完整 Token。
3. 满足安全最小暴露原则。

### 9.4 索引设计说明

| 索引 | 用途 |
|---|---|
| uk_audit_no | 审计消息幂等 |
| idx_member_date | 按会员和结算日期查询 |
| idx_file_no | 查询某文件下载记录 |
| idx_status_created | 统计失败、限流、拒绝记录 |
| idx_client_ip_created | 按 IP 分析异常行为 |
| idx_created_at | 按时间范围查询和归档 |

### 9.5 数据量说明

下载审计表增长速度可能较快。第一版使用 MySQL 单表即可。

后续生产级优化：

1. 按月分表。
2. 按 settle_date 分区。
3. 冷数据归档。
4. 审计数据同步到 Elasticsearch。
5. 离线数仓分析。

---

## 10. download-service 改造设计

### 10.1 改造目标

download-service 需要在以下位置生成审计事件：

1. IP 限流失败。
2. 会员限流失败。
3. Token 校验失败。
4. 权限校验失败。
5. 文件状态不允许下载。
6. MinIO 下载失败。
7. 文件下载成功。

### 10.2 下载审计发送原则

1. 下载主流程优先。
2. 审计发送失败不影响成功下载。
3. 审计消息尽量异步发送。
4. 关键失败场景也要记录审计。
5. 审计消息必须包含 requestId。
6. 不保存完整 Token，只保存摘要。

### 10.3 下载流程加入审计后

```text
请求下载文件
    ↓
记录 startTime
    ↓
解析 memberId、clientIp、userAgent、requestId
    ↓
IP 限流
    ├── 失败：发送 LIMITED 审计，返回 429
    ↓
会员限流
    ├── 失败：发送 LIMITED 审计，返回 429
    ↓
校验 Token
    ├── 失败：发送 TOKEN_INVALID/TOKEN_EXPIRED 审计，返回 403
    ↓
查询文件元数据
    ├── 不存在：发送 FILE_NOT_FOUND 审计，返回 404
    ↓
校验会员权限
    ├── 失败：发送 DENIED 审计，返回 403
    ↓
校验文件状态 PUBLISHED
    ├── 不是 PUBLISHED：发送状态异常审计，返回 403
    ↓
从 MinIO 下载文件
    ├── 失败：发送 FAILED 审计，返回 500
    ↓
下载成功
    ↓
发送 SUCCESS 审计
```

### 10.4 审计发送伪代码

```java
public void download(String fileNo, String token, HttpServletRequest request, HttpServletResponse response) {
    long start = System.currentTimeMillis();
    LocalDateTime startTime = LocalDateTime.now();

    AuditContext audit = AuditContext.from(request);
    audit.setFileNo(fileNo);
    audit.setTokenDigest(DigestUtils.sha256Hex(token));

    try {
        if (!ipLimitService.tryAcquire(audit.getClientIp())) {
            auditProducer.sendLimited(audit, "IP请求过于频繁", startTime, System.currentTimeMillis() - start);
            throw new TooManyRequestException("当前IP请求过于频繁");
        }

        if (!memberLimitService.tryAcquire(audit.getMemberId())) {
            auditProducer.sendLimited(audit, "会员请求过于频繁", startTime, System.currentTimeMillis() - start);
            throw new TooManyRequestException("会员请求过于频繁");
        }

        DownloadTokenPayload payload = tokenService.validate(token);
        if (payload == null) {
            auditProducer.sendTokenInvalid(audit, "Token无效", startTime, System.currentTimeMillis() - start);
            throw new BizException("Token无效");
        }

        FileDTO file = fileClient.getFileMeta(fileNo);
        if (file == null) {
            auditProducer.sendFailed(audit, "文件不存在", DownloadStatusEnum.FILE_NOT_FOUND, startTime, System.currentTimeMillis() - start);
            throw new BizException("文件不存在");
        }

        audit.fillFileInfo(file);

        if (!Objects.equals(file.getMemberId(), audit.getMemberId())) {
            auditProducer.sendDenied(audit, "会员无权下载该文件", startTime, System.currentTimeMillis() - start);
            throw new BizException("无权下载该文件");
        }

        if (!FileStatusEnum.PUBLISHED.getCode().equals(file.getStatus())) {
            auditProducer.sendFailed(audit, "文件状态不允许下载", DownloadStatusEnum.FILE_NOT_PUBLISHED, startTime, System.currentTimeMillis() - start);
            throw new BizException("文件尚未发布，禁止下载");
        }

        long bytes = minioDownloadService.download(file, response);

        audit.setDownloadBytes(bytes);
        auditProducer.sendSuccess(audit, startTime, LocalDateTime.now(), System.currentTimeMillis() - start);

    } catch (Exception e) {
        // 如果前面已经针对明确失败原因发送过审计，这里避免重复发送。
        throw e;
    }
}
```

### 10.5 避免重复发送审计

下载过程可能在多个分支失败。建议使用：

```text
auditSent 标识
```

或封装统一方法，保证一次请求只发送一条最终审计记录。

---

## 11. 审计 Producer 设计

### 11.1 DownloadAuditProducer 职责

1. 构造审计消息。
2. 根据状态选择 Topic + Tag。
3. 发送 MQ。
4. 捕获发送异常。
5. 记录发送失败日志。
6. 不阻塞下载主流程。

### 11.2 Producer 伪代码

```java
@Service
public class DownloadAuditProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Value("${exchange-clear.mq.topic.download-audit}")
    private String topic;

    public void send(DownloadAuditMessage message) {
        try {
            String tag = buildTag(message.getDownloadStatus());
            String destination = topic + ":" + tag;

            Message<DownloadAuditMessage> mqMessage = MessageBuilder
                    .withPayload(message)
                    .setHeader(MessageConst.PROPERTY_KEYS, message.getAuditNo())
                    .build();

            rocketMQTemplate.asyncSend(destination, mqMessage, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("send download audit success, auditNo={}, msgId={}",
                            message.getAuditNo(), sendResult.getMsgId());
                }

                @Override
                public void onException(Throwable throwable) {
                    log.error("send download audit failed, auditNo={}", message.getAuditNo(), throwable);
                }
            });

        } catch (Exception e) {
            log.error("send download audit exception, auditNo={}", message.getAuditNo(), e);
        }
    }
}
```

### 11.3 审计消息发送失败怎么办

第一版策略：

```text
审计 MQ 发送失败只记录日志，不影响下载结果。
```

原因：

1. 下载主流程不能被审计系统强耦合。
2. 审计是旁路链路。
3. 第一版保持简单。

后续增强方案：

```text
download-service 增加 download_audit_outbox 本地消息表。
```

---

## 12. audit-service Consumer 设计

### 12.1 Consumer 职责

1. 消费 `file.download.audit`。
2. 校验消息合法性。
3. 根据 `auditNo` 做幂等判断。
4. 写入 `file_download_audit`。
5. 消费失败抛异常，触发 RocketMQ 重试。

### 12.2 Consumer 伪代码

```java
@Component
@RocketMQMessageListener(
    topic = "${exchange-clear.mq.topic.download-audit}",
    consumerGroup = "exchange-clear-download-audit-consumer-group",
    selectorExpression = "*",
    consumeThreadNumber = 4,
    maxReconsumeTimes = 3
)
public class DownloadAuditConsumer implements RocketMQListener<DownloadAuditMessage> {

    @Resource
    private DownloadAuditService downloadAuditService;

    @Override
    public void onMessage(DownloadAuditMessage message) {
        downloadAuditService.saveAudit(message);
    }
}
```

### 12.3 审计落库幂等

通过唯一索引：

```sql
UNIQUE KEY uk_audit_no (audit_no)
```

处理逻辑：

```text
1. 查询 audit_no 是否存在
2. 如果存在，直接返回成功
3. 如果不存在，插入审计记录
4. 如果并发插入唯一键冲突，捕获异常并返回成功
```

### 12.4 saveAudit 伪代码

```java
@Transactional
public void saveAudit(DownloadAuditMessage message) {
    FileDownloadAudit exists = auditMapper.selectByAuditNo(message.getAuditNo());
    if (exists != null) {
        log.info("audit already exists, auditNo={}", message.getAuditNo());
        return;
    }

    FileDownloadAudit audit = convert(message);

    try {
        auditMapper.insert(audit);
    } catch (DuplicateKeyException e) {
        log.info("duplicate audit message, auditNo={}", message.getAuditNo());
    }
}
```

---

## 13. 审计查询接口设计

### 13.1 查询审计列表

```http
GET /api/audits/downloads
```

查询参数：

| 参数 | 说明 |
|---|---|
| memberId | 会员编号 |
| fileNo | 文件编号 |
| settleDate | 结算日期 |
| fileType | 文件类型 |
| downloadStatus | 下载状态 |
| clientIp | 客户端 IP |
| startTime | 开始时间 |
| endTime | 结束时间 |
| pageNo | 页码 |
| pageSize | 每页数量 |

请求示例：

```http
GET /api/audits/downloads?memberId=0001&settleDate=2026-06-26&pageNo=1&pageSize=20
```

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 120,
    "records": [
      {
        "auditNo": "AUDIT202606260001",
        "fileNo": "FILE202606260001",
        "fileName": "trade_0001_20260626.csv",
        "memberId": "0001",
        "fileType": "TRADE",
        "settleDate": "2026-06-26",
        "downloadStatus": "SUCCESS",
        "clientIp": "192.168.1.10",
        "costMs": 2000,
        "createdAt": "2026-06-26 18:30:00"
      }
    ]
  }
}
```

### 13.2 查询审计详情

```http
GET /api/audits/downloads/{auditNo}
```

### 13.3 查询某文件下载记录

```http
GET /api/audits/files/{fileNo}/downloads
```

用于判断某文件：

1. 是否被下载过。
2. 被哪些 IP 下载过。
3. 被下载了多少次。
4. 最后一次下载时间。
5. 是否存在失败下载记录。

### 13.4 查询某会员下载记录

```http
GET /api/audits/members/{memberId}/downloads
```

用于查询某会员在指定日期范围内的下载行为。

---

## 14. 审计统计接口设计

### 14.1 文件下载统计

```http
GET /api/audits/stats/file-download
```

查询参数：

```text
settleDate
fileType
fileNo
```

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "fileNo": "FILE202606260001",
    "successCount": 10,
    "failedCount": 2,
    "lastSuccessTime": "2026-06-26 18:40:00",
    "lastClientIp": "192.168.1.10"
  }
}
```

### 14.2 会员下载统计

```http
GET /api/audits/stats/member-download
```

查询参数：

```text
memberId
startTime
endTime
```

### 14.3 异常下载统计

```http
GET /api/audits/stats/abnormal
```

查询参数：

```text
startTime
endTime
```

统计维度：

1. Token 无效次数。
2. Token 过期次数。
3. 权限拒绝次数。
4. 限流次数。
5. 文件未发布访问次数。
6. 文件已撤销访问次数。
7. 按 IP 排名前 N。
8. 按会员排名前 N。

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "tokenInvalidCount": 30,
    "tokenExpiredCount": 12,
    "deniedCount": 8,
    "limitedCount": 20,
    "fileNotPublishedCount": 5,
    "topIps": [
      {
        "clientIp": "192.168.1.10",
        "count": 15
      }
    ],
    "topMembers": [
      {
        "memberId": "0001",
        "count": 10
      }
    ]
  }
}
```

---

## 15. 异常下载行为统计设计

### 15.1 异常行为定义

本阶段不做自动风控拦截，只做统计。

异常行为包括：

1. 同一 IP 短时间内大量 Token 无效。
2. 同一会员短时间内大量下载失败。
3. 同一 IP 多次访问不属于自己的文件。
4. 大量访问未发布文件。
5. 大量访问已撤销或已重发文件。
6. 大量触发限流。

### 15.2 示例统计 SQL

按 IP 统计失败次数：

```sql
SELECT client_ip, COUNT(*) AS cnt
FROM file_download_audit
WHERE created_at >= ?
  AND created_at < ?
  AND download_status <> 'SUCCESS'
GROUP BY client_ip
ORDER BY cnt DESC
LIMIT 10;
```

按会员统计限流次数：

```sql
SELECT member_id, COUNT(*) AS cnt
FROM file_download_audit
WHERE created_at >= ?
  AND created_at < ?
  AND download_status = 'LIMITED'
GROUP BY member_id
ORDER BY cnt DESC
LIMIT 10;
```

查询某文件是否被下载：

```sql
SELECT COUNT(*)
FROM file_download_audit
WHERE file_no = ?
  AND download_status = 'SUCCESS';
```

---

## 16. RequestId 设计

### 16.1 为什么需要 RequestId

下载链路经过：

```text
Gateway
    ↓
download-service
    ↓
file-service
    ↓
MinIO
    ↓
RocketMQ
    ↓
audit-service
```

没有 RequestId 时，排查一次下载请求会比较困难。

因此建议 Gateway 统一生成或透传：

```text
X-Request-Id
```

### 16.2 RequestId 流程

```text
用户请求
    ↓
Gateway 检查 X-Request-Id
    ↓
如果不存在，生成 requestId
    ↓
透传给 download-service
    ↓
download-service 放入审计消息
    ↓
audit-service 落库
```

### 16.3 请求头

```http
X-Request-Id: REQ202606260001
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

---

## 17. 安全设计

### 17.1 Token 脱敏

审计表不保存完整 Token。

保存：

```text
SHA-256(token)
```

字段：

```text
token_digest
```

### 17.2 User-Agent 长度限制

`user_agent` 字段长度：

```text
512
```

如果超过，截断保存。

### 17.3 failReason 长度限制

`fail_reason` 字段长度：

```text
1000
```

超过后截断。

### 17.4 审计查询权限

审计查询接口应只允许：

1. 管理员。
2. 运维人员。
3. 审计人员。
4. 内部系统。

普通会员不应直接访问全量审计查询接口。

如果后续给会员提供下载记录查询，只能查询自己的记录。

---

## 18. 可靠性设计

### 18.1 审计消息发送失败

第一版：

```text
download-service 发送审计 MQ 失败，只记录日志，不影响下载主流程。
```

原因：

1. 下载成功不能因为审计 MQ 异常而失败。
2. 审计是旁路链路。
3. 第一版保持简单。

增强版：

```text
download-service 增加审计本地消息表，发送失败后补偿。
```

### 18.2 审计消费失败

audit-service 消费失败时：

```text
抛出异常
    ↓
RocketMQ 重试
    ↓
超过最大重试次数进入死信
```

### 18.3 审计落库幂等

使用：

```text
audit_no 唯一键
```

确保重复消息不会重复落库。

### 18.4 审计服务故障

如果 audit-service 故障：

1. RocketMQ 消息积压。
2. download-service 不受影响。
3. audit-service 恢复后继续消费。
4. 需要监控 MQ 积压量。

---

## 19. 配置项设计

```yaml
exchange-clear:
  audit:
    enabled: true
    save-token-digest: true
    max-fail-reason-length: 1000
    max-user-agent-length: 512

  mq:
    topic:
      download-audit: file.download.audit
    consumer-group:
      download-audit: exchange-clear-download-audit-consumer-group

rocketmq:
  name-server: localhost:9876
  producer:
    group: exchange-clear-download-audit-producer-group
  consumer:
    group: exchange-clear-download-audit-consumer-group
```

---

## 20. 代码结构设计

### 20.1 download-service 新增结构

```text
download-service
├── audit
│   ├── DownloadAuditProducer.java
│   ├── DownloadAuditMessageBuilder.java
│   ├── AuditContext.java
│   └── AuditStatusMapper.java
└── service
    └── DownloadService.java
```

### 20.2 settle-audit-service 结构

```text
settle-audit-service
├── AuditServiceApplication.java
├── controller
│   ├── DownloadAuditController.java
│   └── DownloadAuditStatsController.java
├── consumer
│   └── DownloadAuditConsumer.java
├── service
│   ├── DownloadAuditService.java
│   └── DownloadAuditStatsService.java
├── mapper
│   └── FileDownloadAuditMapper.java
├── entity
│   └── FileDownloadAudit.java
├── dto
│   ├── DownloadAuditQuery.java
│   ├── DownloadAuditDTO.java
│   ├── FileDownloadStatsDTO.java
│   ├── MemberDownloadStatsDTO.java
│   └── AbnormalDownloadStatsDTO.java
└── enums
    ├── AuditActionEnum.java
    └── DownloadStatusEnum.java
```

---

## 21. 审计与现有阶段的关系

### 21.1 与第四阶段 Redis 的关系

第四阶段已经实现：

1. 下载 Token。
2. 会员级限流。
3. IP 级限流。

第六阶段需要记录：

1. Token 校验失败审计。
2. 会员限流审计。
3. IP 限流审计。

### 21.2 与第五阶段发布状态的关系

第五阶段已经引入：

1. PUBLISHED。
2. REVOKED。
3. REISSUED。

第六阶段需要记录：

1. 未发布文件下载尝试。
2. 已撤销文件下载尝试。
3. 已重发旧文件下载尝试。
4. 正常 PUBLISHED 文件下载成功。

### 21.3 与 Gateway 的关系

Gateway 需要透传：

1. X-Request-Id。
2. X-Member-Id。
3. X-Client-IP。
4. User-Agent。

download-service 使用这些信息构造审计消息。

---

## 22. 测试方案

### 22.1 下载成功审计测试

步骤：

```text
1. 文件生成并发布为 PUBLISHED
2. 创建下载 Token
3. 使用 Token 下载文件成功
4. 查看 RocketMQ 是否有审计消息
5. 查看 file_download_audit 是否有 SUCCESS 记录
```

预期：

```text
审计表中存在 SUCCESS 记录，包含 memberId、fileNo、clientIp、costMs。
```

### 22.2 Token 无效审计测试

步骤：

```text
1. 使用错误 Token 下载文件
2. download-service 返回 Token 无效
3. audit-service 消费审计消息
4. 查询审计表
```

预期：

```text
审计表中存在 TOKEN_INVALID 记录。
```

### 22.3 权限拒绝审计测试

步骤：

```text
1. 使用 memberId=0002 下载 memberId=0001 的文件
2. 请求被拒绝
3. 查询审计表
```

预期：

```text
审计表中存在 DENIED 记录。
```

### 22.4 文件未发布审计测试

步骤：

```text
1. 文件状态为 GENERATED
2. 尝试创建 Token 或下载
3. 查询审计表
```

预期：

```text
审计表中存在 FILE_NOT_PUBLISHED 记录。
```

### 22.5 限流审计测试

步骤：

```text
1. 降低会员级限流阈值为 2 次/秒
2. 快速请求下载接口
3. 触发限流
4. 查询审计表
```

预期：

```text
审计表中存在 LIMITED 记录。
```

### 22.6 MQ 重复消费幂等测试

步骤：

```text
1. 手动重复发送相同 auditNo 的审计消息
2. audit-service 消费
3. 查询审计表记录数
```

预期：

```text
相同 auditNo 只落库一条记录。
```

---

## 23. 验收标准

### 23.1 功能验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 新增 settle-audit-service | 必须 |
| 2 | 新增 file.download.audit Topic | 必须 |
| 3 | download-service 可以发送下载审计消息 | 必须 |
| 4 | audit-service 可以消费审计消息 | 必须 |
| 5 | 下载成功可以记录 SUCCESS 审计 | 必须 |
| 6 | 下载失败可以记录 FAILED 审计 | 必须 |
| 7 | Token 无效可以记录 TOKEN_INVALID 审计 | 必须 |
| 8 | 权限拒绝可以记录 DENIED 审计 | 必须 |
| 9 | 限流可以记录 LIMITED 审计 | 必须 |
| 10 | 文件未发布可以记录 FILE_NOT_PUBLISHED 审计 | 必须 |
| 11 | 审计消息重复消费不会重复落库 | 必须 |
| 12 | 支持按会员查询审计 | 必须 |
| 13 | 支持按文件查询审计 | 必须 |
| 14 | 支持按 IP 查询审计 | 必须 |
| 15 | 支持异常下载统计 | 建议 |
| 16 | 审计服务异常不影响下载主流程 | 必须 |

### 23.2 技术验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | 审计表 audit_no 唯一 | 必须 |
| 2 | 审计消费具备幂等处理 | 必须 |
| 3 | 审计消息包含 requestId | 必须 |
| 4 | 审计消息包含 memberId、fileNo、clientIp | 必须 |
| 5 | 不保存完整 Token | 必须 |
| 6 | User-Agent 和 failReason 做长度截断 | 必须 |
| 7 | 审计发送失败不影响下载成功 | 必须 |
| 8 | 审计消费失败可触发 MQ 重试 | 必须 |
| 9 | 查询接口支持分页 | 必须 |
| 10 | 审计表索引满足常见查询 | 必须 |

---

## 24. 开发计划

### 24.1 Day 1：审计表和服务骨架

完成：

1. 新建 settle-audit-service。
2. 创建 file_download_audit 表。
3. 创建实体、Mapper、Service。
4. 接入 Nacos。
5. 接入 RocketMQ Consumer。

### 24.2 Day 2：download-service 发送审计消息

完成：

1. 新增 DownloadAuditMessage。
2. 新增 DownloadAuditProducer。
3. 下载成功发送 SUCCESS。
4. 下载失败发送 FAILED。
5. Token 失败发送 TOKEN_INVALID。
6. 限流发送 LIMITED。

### 24.3 Day 3：audit-service 消费落库

完成：

1. 实现 DownloadAuditConsumer。
2. 实现审计落库。
3. 实现 auditNo 幂等。
4. 完成重复消费测试。

### 24.4 Day 4：审计查询接口

完成：

1. 审计列表查询。
2. 审计详情查询。
3. 按文件查询。
4. 按会员查询。
5. 分页查询。

### 24.5 Day 5：统计接口

完成：

1. 文件下载统计。
2. 会员下载统计。
3. 异常下载统计。
4. TOP IP 统计。
5. TOP 会员异常统计。

### 24.6 Day 6：联调测试

完成：

1. 下载成功审计测试。
2. Token 无效审计测试。
3. 权限拒绝审计测试。
4. 文件状态异常审计测试。
5. 限流审计测试。
6. MQ 重试测试。

### 24.7 Day 7：文档和验收

完成：

1. 更新 README。
2. 更新接口文档。
3. 更新 RocketMQ Topic 文档。
4. 补充审计表说明。
5. 补充演示流程。

---

## 25. 演示流程

### 25.1 下载成功审计演示

```text
1. 生成文件
2. 发布文件为 PUBLISHED
3. 创建下载 Token
4. 下载文件
5. 查询审计记录
```

查询：

```http
GET /api/audits/downloads?fileNo=FILE202606260001
```

### 25.2 异常下载审计演示

```text
1. 使用错误 Token 下载
2. 使用错误 memberId 下载
3. 使用未发布文件下载
4. 快速请求触发限流
5. 查询异常统计
```

查询：

```http
GET /api/audits/stats/abnormal?startTime=2026-06-26 00:00:00&endTime=2026-06-26 23:59:59
```

---

## 26. 后续演进方向

### 26.1 审计本地消息表

当前第一版审计消息发送失败只记录日志。

后续可以增强为：

```text
download-service 写 download_audit_outbox
    ↓
异步发送 MQ
    ↓
失败后补偿重发
```

### 26.2 审计数据归档

审计表数据量较大，后续可以：

1. 按月分表。
2. 按 settle_date 分区。
3. 定期归档到历史表。
4. 同步到 Elasticsearch。
5. 同步到离线数仓。

### 26.3 异常告警

基于审计统计可以增加：

1. 单 IP 异常下载告警。
2. 单会员大量失败告警。
3. 大量 Token 无效告警。
4. 大量访问撤销文件告警。
5. 下载失败率过高告警。

### 26.4 审计看板

后续可以建设 Grafana 或前端页面：

1. 下载成功趋势。
2. 下载失败趋势。
3. 异常 IP 排名。
4. 会员下载排名。
5. 文件下载状态。
6. 限流次数趋势。

---

## 27. 简历描述建议

第六阶段完成后，可以在简历中这样描述：

```text
设计并实现 settle-audit-service 审计服务，通过 RocketMQ 异步消费 download-service 产生的下载审计消息，记录会员下载文件的行为轨迹，包括会员编号、文件编号、文件类型、结算日期、下载 IP、User-Agent、下载结果、耗时和失败原因。审计服务支持按会员、文件、IP、时间范围查询下载记录，并支持异常下载行为统计。通过 auditNo 唯一键实现审计消息消费幂等，保证 RocketMQ 重复投递场景下不会重复落库。
```

项目亮点：

```text
1. 使用 RocketMQ 将下载主链路和审计落库异步解耦，避免审计写入影响下载性能。
2. 设计 file_download_audit 审计表，完整记录会员下载行为、下载结果、IP、User-Agent、耗时和失败原因。
3. 支持下载成功、Token 无效、权限拒绝、文件未发布、文件撤销、限流等多种审计状态。
4. 使用 auditNo 唯一键保证审计消息消费幂等。
5. 提供按会员、文件、IP、时间范围的审计查询能力。
6. 提供异常下载统计能力，为后续风控和告警提供数据基础。
```

---

## 28. 面试可讲问题

### 28.1 为什么审计服务要异步化？

下载属于用户主链路，不能因为审计数据库写入慢或审计服务异常导致下载失败。通过 RocketMQ 将下载服务和审计服务解耦，download-service 只负责发送审计消息，audit-service 异步消费落库，从而降低下载接口耗时并提高系统稳定性。

### 28.2 审计消息丢失怎么办？

第一版中，审计消息发送失败只记录日志，不影响下载主流程。后续可以在 download-service 增加本地消息表，将审计消息先写入本地库，再异步发送 MQ，通过补偿任务保证审计消息最终投递。当前阶段重点是建立审计服务能力和异步链路。

### 28.3 如何保证审计消费幂等？

审计表中 `audit_no` 建立唯一索引。audit-service 消费消息时先查询 auditNo 是否存在，如果存在则直接返回；如果并发插入导致唯一键冲突，也捕获异常并视为消费成功。这样可以避免 RocketMQ 重复投递导致重复审计记录。

### 28.4 审计记录哪些失败场景？

记录下载成功、下载失败、Token 无效、Token 过期、权限拒绝、文件不存在、文件未发布、文件已撤销、文件已重发和请求被限流等场景。这样可以完整追踪会员下载行为，并支持异常下载统计。

### 28.5 为什么不保存完整 Token？

Token 是下载授权凭证，即使有效期短，也不应该完整落库。审计表只保存 Token 的 SHA-256 摘要，用于排查和关联，同时避免敏感凭证泄露。

### 28.6 审计数据量大怎么办？

第一版可以使用 MySQL 单表支撑开发和演示。生产级可以按月份或结算日期分区、冷热归档，或者同步到 Elasticsearch、数仓系统，用于复杂查询和报表统计。

---

## 29. 总结

第六阶段补齐了项目中的审计能力，使系统不只是能够生成、发布和下载文件，还能完整追踪会员下载行为。

完成本阶段后，系统具备：

1. 独立审计服务。
2. 下载审计 MQ。
3. 下载成功审计。
4. 下载失败审计。
5. Token 异常审计。
6. 权限拒绝审计。
7. 限流审计。
8. 文件状态异常审计。
9. 审计消息消费幂等。
10. 审计查询。
11. 异常下载统计。

这使项目更贴近金融系统真实要求：

```text
文件可生成、可发布、可下载、可追踪、可审计、可追责。
```
