# ExchangeClear 第四阶段：引入 Redis 详细设计文档

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第四阶段的开发。

前三个阶段已经完成：

1. **第一阶段：单体闭环版**：完成结算数据模拟、文件生成任务创建、文件生成、MinIO 上传、文件元数据入库、文件查询和下载。
2. **第二阶段：引入 RocketMQ**：任务创建后异步发送 MQ，Worker 消费任务并生成文件，支持多个 Worker 并行处理、MQ 重试和消费幂等。
3. **第三阶段：拆分微服务**：拆分 task-service、file-service、worker-service、download-service，接入 Nacos、Gateway，并使用 OpenFeign 进行服务间调用。

第四阶段的核心目标是：

> 引入 Redis，解决文件列表查询、文件元数据查询、下载鉴权、下载限流、分布式锁等高并发访问与分布式协作问题。

---

## 2. 第四阶段目标

### 2.1 业务目标

1. 支持会员高频查询文件列表时不直接打到数据库。
2. 支持文件元数据高频查询时优先走 Redis 缓存。
3. 支持下载前生成短期下载 Token。
4. 下载时必须校验 Token，避免下载地址长期暴露。
5. 支持会员级限流，防止单个会员异常高频下载。
6. 支持 IP 级限流，防止单个客户端或脚本恶意刷接口。
7. 支持分布式锁，避免多实例下重复发布、重复重试、重复处理。
8. 降低 MySQL 压力，提高系统在高并发查询和下载场景下的稳定性。

### 2.2 技术目标

1. 接入 Redis。
2. 接入 Redisson。
3. 在 file-service 中实现文件列表缓存。
4. 在 file-service 中实现文件元数据缓存。
5. 在 download-service 中实现下载 Token。
6. 在 download-service 中实现会员级限流。
7. 在 download-service 中实现 IP 级限流。
8. 在 file-service / task-service / worker-service 中实现分布式锁。
9. 设计统一 Redis Key 规范。
10. 设计缓存过期策略。
11. 设计缓存一致性方案。
12. 设计限流 Lua 脚本或基于 Redisson 的限流方案。
13. 设计 Redis 故障降级策略。
14. 设计 Redis 压测和验收方案。

---

## 3. 阶段范围

### 3.1 本阶段要做

1. Docker Compose 增加 Redis。
2. 各微服务接入 Redis。
3. 各微服务接入 Redisson。
4. file-service 实现文件列表缓存。
5. file-service 实现文件元数据缓存。
6. download-service 实现下载 Token 创建与校验。
7. download-service 实现会员级限流。
8. download-service 实现 IP 级限流。
9. task-service / file-service / worker-service 实现分布式锁使用场景。
10. Gateway 保留透传用户、会员、IP 信息的能力。
11. 增加 Redis Key 规范文档。
12. 增加缓存删除、刷新和降级策略。
13. 增加测试接口或管理接口用于观察缓存行为。

### 3.2 本阶段暂不做

1. 暂不做 Redis Cluster。
2. 暂不做 Redis Sentinel 高可用。
3. 暂不做复杂风控规则。
4. 暂不做多级缓存。
5. 暂不做本地缓存 Caffeine。
6. 暂不做热点 Key 自动拆分。
7. 暂不做完整安全认证中心。
8. 暂不做 K8s 部署。

---

## 4. Redis 引入后的总体架构

```text
                          ┌────────────────────┐
                          │      用户/会员系统   │
                          └─────────┬──────────┘
                                    │
                                    ▼
                          ┌────────────────────┐
                          │      Gateway        │
                          │  统一入口/IP透传     │
                          └─────────┬──────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
┌────────────────────┐ ┌────────────────────┐ ┌────────────────────┐
│  task-service       │ │  file-service       │ │ download-service    │
│  任务服务            │ │  文件元数据服务       │ │ 文件下载服务         │
└─────────┬──────────┘ └─────────┬──────────┘ └─────────┬──────────┘
          │                      │                      │
          │ 分布式锁              │ 文件列表/元数据缓存     │ Token/限流
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 ▼
                       ┌────────────────────┐
                       │       Redis         │
                       │ 缓存/Token/限流/锁   │
                       └─────────┬──────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
     ┌────────────────┐ ┌────────────────┐ ┌────────────────┐
     │     MySQL       │ │     MinIO       │ │    RocketMQ     │
     │ 元数据/任务/审计 │ │ 文件对象存储     │ │ 异步任务/审计     │
     └────────────────┘ └────────────────┘ └────────────────┘
```

---

## 5. Redis 使用场景总览

| 场景 | 所属服务 | Redis 能力 | 目的 |
|---|---|---|---|
| 文件列表缓存 | file-service | String / JSON | 减少 MySQL 查询 |
| 文件元数据缓存 | file-service / download-service | String / JSON | 下载前快速获取文件信息 |
| 下载 Token | download-service | String + TTL | 防止下载地址长期有效 |
| 会员级限流 | download-service | Lua / Redisson RateLimiter | 防止单会员刷下载 |
| IP 级限流 | download-service / gateway | Lua / Redisson RateLimiter | 防止单 IP 刷接口 |
| 发布分布式锁 | file-service | Redisson Lock | 防止多实例重复发布 |
| 任务重投锁 | task-service | Redisson Lock | 防止多实例重复重投 |
| Worker 任务执行锁 | worker-service | Redisson Lock / DB 条件更新 | 防止极端情况下重复生成 |
| 缓存刷新锁 | file-service | Redisson Lock | 防止缓存击穿时并发回源 |

---

## 6. Redis Key 规范设计

### 6.1 Key 命名原则

统一使用如下格式：

```text
exchange-clear:{业务域}:{具体类型}:{业务标识}
```

原则：

1. 统一前缀：`exchange-clear`。
2. 使用小写单词。
3. 多个字段用冒号分隔。
4. 结算日期统一使用 `yyyyMMdd`。
5. Key 中避免使用中文。
6. Key 必须有明确 TTL 或释放策略。
7. 重要 Key 必须在文档中登记。

### 6.2 Key 总览

| Key | 类型 | 所属服务 | 说明 | TTL |
|---|---|---|---|---|
| exchange-clear:file:list:{settleDate}:{memberId} | String(JSON) | file-service | 会员文件列表缓存 | 10 分钟 |
| exchange-clear:file:meta:{fileNo} | String(JSON) | file-service | 文件元数据缓存 | 30 分钟 |
| exchange-clear:download:token:{token} | String(JSON) | download-service | 下载 Token | 5 分钟 |
| exchange-clear:limit:member:{memberId} | String/Counter | download-service | 会员级限流 | 1 秒 |
| exchange-clear:limit:ip:{ip} | String/Counter | download-service | IP 级限流 | 1 秒 |
| exchange-clear:lock:publish:{settleDate} | Lock | file-service | 文件发布锁 | 30 秒或业务配置 |
| exchange-clear:lock:task:resend:{taskNo} | Lock | task-service | 任务重投锁 | 30 秒 |
| exchange-clear:lock:worker:{taskNo} | Lock | worker-service | Worker 执行锁，可选 | 10 分钟 |
| exchange-clear:lock:cache:file-list:{settleDate}:{memberId} | Lock | file-service | 文件列表缓存回源锁 | 10 秒 |

---

## 7. 依赖引入设计

### 7.1 Maven 依赖

在需要使用 Redis 的服务中增加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.24.3</version>
</dependency>
```

建议接入 Redis 的服务：

1. file-service。
2. download-service。
3. task-service。
4. worker-service。
5. gateway，可选。

### 7.2 application.yml 配置

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    password:
    database: 0
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 16
        max-idle: 8
        min-idle: 2
        max-wait: 3000ms

redisson:
  single-server-config:
    address: redis://localhost:6379
    database: 0
```

如果使用 `redisson.yml`：

```yaml
singleServerConfig:
  address: "redis://localhost:6379"
  database: 0
  connectionMinimumIdleSize: 8
  connectionPoolSize: 32
threads: 8
nettyThreads: 16
```

### 7.3 Docker Compose 增加 Redis

```yaml
redis:
  image: redis:7.2
  container_name: exchange-clear-redis
  restart: always
  ports:
    - "6379:6379"
  command: redis-server --appendonly yes
  volumes:
    - ./data/redis:/data
```

---

## 8. 文件列表缓存设计

### 8.1 适用接口

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

服务：

```text
file-service
```

### 8.2 查询流程

```text
请求查询文件列表
    ↓
构造 Redis Key
    ↓
查询 Redis
    ↓
缓存命中？
    ├── 是：反序列化并返回
    └── 否：
        ↓
        查询 MySQL
        ↓
        写入 Redis
        ↓
        返回结果
```

### 8.3 缓存伪代码

```java
public List<FileDTO> listFiles(LocalDate settleDate, String memberId) {
    String key = buildFileListKey(settleDate, memberId);

    String cacheValue = redisTemplate.opsForValue().get(key);
    if (StringUtils.hasText(cacheValue)) {
        return JsonUtils.toList(cacheValue, FileDTO.class);
    }

    List<FileDTO> files = fileMapper.selectBySettleDateAndMemberId(settleDate, memberId);

    redisTemplate.opsForValue().set(
        key,
        JsonUtils.toJson(files),
        Duration.ofMinutes(10)
    );

    return files;
}
```

### 8.4 缓存击穿保护

当某个会员的文件列表缓存过期时，可能出现大量请求同时回源 MySQL。

解决方式：

1. 使用 Redisson 分布式锁保护回源。
2. 一个线程回源 MySQL。
3. 其他线程短暂等待后重新读取缓存。
4. 如果等待后仍没有缓存，再降级查询数据库。

### 8.5 空值缓存

如果某会员某日没有文件，也建议缓存空列表，避免恶意请求反复打数据库。

空列表缓存 TTL 建议短一些：

```text
1 分钟
```

示例：

```json
[]
```

### 8.6 缓存一致性

文件列表缓存需要在以下场景删除：

1. 文件生成成功。
2. 文件发布成功。
3. 文件撤销。
4. 文件重发。
5. 文件状态变更。
6. 文件元数据更新。

推荐策略：

```text
更新 MySQL 成功
    ↓
删除 Redis 缓存
```

第一版不建议使用“更新数据库后直接更新缓存”，因为文件列表是聚合数据，直接维护更复杂。

---

## 9. 文件元数据缓存设计

### 9.1 适用接口

```http
GET /api/files/{fileNo}
GET /api/files/{fileNo}/checksum
POST /api/download/token
GET /api/download/files/{fileNo}
```

### 9.2 查询流程

```text
根据 fileNo 查询文件元数据
    ↓
查询 Redis：exchange-clear:file:meta:{fileNo}
    ↓
命中则返回
    ↓
未命中则查询 MySQL
    ↓
写入 Redis
    ↓
返回文件元数据
```

### 9.3 缓存伪代码

```java
public FileDTO getFileMeta(String fileNo) {
    String key = buildFileMetaKey(fileNo);

    String cacheValue = redisTemplate.opsForValue().get(key);
    if (StringUtils.hasText(cacheValue)) {
        if ("NULL".equals(cacheValue)) {
            return null;
        }
        return JsonUtils.toObject(cacheValue, FileDTO.class);
    }

    FileDTO file = fileMapper.selectByFileNo(fileNo);
    if (file == null) {
        redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(1));
        return null;
    }

    redisTemplate.opsForValue().set(key, JsonUtils.toJson(file), Duration.ofMinutes(30));
    return file;
}
```

### 9.4 缓存一致性

以下场景需要删除文件元数据缓存：

1. 文件状态变更。
2. 文件发布。
3. 文件撤销。
4. 文件重发。
5. 文件元数据修正。

Key：

```text
exchange-clear:file:meta:{fileNo}
```

注意：

`download_count` 如果频繁更新，不建议每次都刷新元数据缓存，否则缓存会频繁失效。

建议：

1. 文件元数据缓存中可以不强依赖最新 `download_count`。
2. 下载次数单独异步统计或延迟刷新。
3. 管理端需要准确下载次数时直接查数据库。

---

## 10. 下载 Token 设计

### 10.1 为什么需要下载 Token

如果下载接口直接是：

```http
GET /api/files/{fileNo}/download
```

可能存在以下问题：

1. 下载地址可被长期保存。
2. 文件编号泄露后可能被反复尝试。
3. 难以控制一次下载授权的有效期。
4. 不方便绑定会员、IP、过期时间。
5. 不方便做下载前置限流和审计。

因此第四阶段增加下载 Token。

### 10.2 下载流程调整

```text
会员请求创建下载 Token
    ↓
校验会员权限
    ↓
校验文件状态
    ↓
会员级限流
    ↓
IP 级限流
    ↓
生成短期 Token
    ↓
Redis 保存 Token，TTL 5 分钟
    ↓
返回 Token

会员携带 Token 请求下载
    ↓
校验 Token
    ↓
校验 Token 中 memberId、fileNo、clientIp
    ↓
从 MinIO 下载文件
    ↓
Token 可选择一次性失效
```

### 10.3 创建 Token 接口

```http
POST /api/download/token
```

请求参数：

```json
{
  "fileNo": "FILE202606260001"
}
```

请求头：

```http
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

返回参数：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "4de8c87f9f93406cbbe1d2d8b0a1faaa",
    "expireSeconds": 300
  }
}
```

### 10.4 使用 Token 下载接口

```http
GET /api/download/files/{fileNo}?token=4de8c87f9f93406cbbe1d2d8b0a1faaa
```

处理逻辑：

```text
1. 获取 fileNo、token、memberId、clientIp
2. 查询 Redis Token
3. Token 不存在则返回 401 或 403
4. 校验 token 中 fileNo 是否一致
5. 校验 token 中 memberId 是否一致
6. 校验 token 中 clientIp 是否一致，可选
7. 查询文件元数据
8. 校验文件状态为 PUBLISHED 或 GENERATED
9. 从 MinIO 获取文件流
10. 返回文件
11. 可选：删除 Token，实现一次性下载
```

### 10.5 Token 数据结构

```java
public class DownloadTokenPayload {

    private String token;

    private String fileNo;

    private String memberId;

    private String clientIp;

    private LocalDateTime expireAt;

    private Boolean oneTime;
}
```

Redis Value：

```json
{
  "token": "4de8c87f9f93406cbbe1d2d8b0a1faaa",
  "fileNo": "FILE202606260001",
  "memberId": "0001",
  "clientIp": "192.168.1.10",
  "expireAt": "2026-06-26 18:05:00",
  "oneTime": false
}
```

### 10.6 Token 过期时间

建议：

```text
5 分钟
```

### 10.7 Token 是否一次性使用

第一版建议：

```text
非一次性 Token，有效期内可重复下载。
```

原因：

1. 文件可能较大，下载中断后需要重试。
2. 一次性 Token 对断点续传不友好。
3. 后续支持 Range 下载时需要 Token 在有效期内可复用。

---

## 11. 会员级限流设计

### 11.1 限流目标

防止某个会员系统异常或脚本频繁请求下载接口，导致 download-service、file-service、Redis、MinIO 压力过大。

### 11.2 限流规则

默认规则：

```text
单会员每秒最多 20 次下载相关请求
```

配置项：

```yaml
exchange-clear:
  limit:
    member:
      enabled: true
      permits-per-second: 20
```

### 11.3 基于 Redis Lua 的固定窗口限流

Lua 脚本：

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local expireMillis = tonumber(ARGV[2])

local current = redis.call('INCR', key)

if current == 1 then
    redis.call('PEXPIRE', key, expireMillis)
end

if current > limit then
    return 0
end

return 1
```

Java 调用逻辑：

```java
public boolean tryAcquireMember(String memberId) {
    String key = "exchange-clear:limit:member:" + memberId;
    Long result = redisTemplate.execute(
        limitScript,
        Collections.singletonList(key),
        String.valueOf(20),
        String.valueOf(1000)
    );
    return result != null && result == 1L;
}
```

### 11.4 会员级限流失败响应

HTTP 状态码：

```http
429 Too Many Requests
```

响应体：

```json
{
  "code": 429001,
  "message": "会员下载请求过于频繁，请稍后重试",
  "data": null
}
```

---

## 12. IP 级限流设计

### 12.1 限流目标

防止单个 IP 恶意刷接口，尤其是在以下场景：

1. 未携带会员身份反复请求。
2. 猜测 fileNo。
3. 重复创建 Token。
4. 下载接口被脚本刷。
5. 同一个公网出口流量异常。

### 12.2 IP 获取方式

通过 Gateway 统一处理并透传真实 IP。

常见请求头：

```http
X-Forwarded-For
X-Real-IP
```

推荐在 Gateway 增加过滤器：

```text
1. 读取 X-Forwarded-For
2. 如果不存在，读取 remoteAddress
3. 将解析后的客户端 IP 放入 X-Client-IP
4. 后端服务统一读取 X-Client-IP
```

### 12.3 限流规则

默认规则：

```text
单 IP 每秒最多 50 次请求
```

配置项：

```yaml
exchange-clear:
  limit:
    ip:
      enabled: true
      permits-per-second: 50
```

### 12.4 IP 限流失败响应

```json
{
  "code": 429002,
  "message": "当前 IP 请求过于频繁，请稍后重试",
  "data": null
}
```

### 12.5 会员限流与 IP 限流执行顺序

推荐顺序：

```text
1. IP 级限流
2. 会员级限流
3. 权限校验
4. 文件状态校验
5. 生成 Token 或执行下载
```

原因：

1. IP 限流可以更早拦截无效流量。
2. 会员限流用于保护业务维度。
3. 权限和文件查询可能涉及数据库或 RPC，应尽量放在限流之后。

---

## 13. 分布式锁设计

### 13.1 为什么需要分布式锁

微服务拆分后，同一个服务可能有多个实例。

以下场景需要控制同一业务动作只能有一个实例执行：

1. 同一结算日期文件发布。
2. 同一任务人工重投。
3. 同一任务补偿扫描。
4. 同一文件状态变更。
5. 缓存击穿时的数据库回源。
6. Worker 极端重复消费。

### 13.2 Redisson RLock 示例

```java
RLock lock = redissonClient.getLock("exchange-clear:lock:publish:20260626");

boolean locked = lock.tryLock(3, 30, TimeUnit.SECONDS);

if (!locked) {
    throw new BizException("当前结算日期正在发布，请勿重复操作");
}

try {
    // 执行业务逻辑
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 13.3 文件发布锁

Key：

```text
exchange-clear:lock:publish:{settleDate}
```

使用服务：

```text
file-service
```

流程：

```text
请求发布某日文件
    ↓
获取发布锁
    ↓
获取失败：返回正在发布
    ↓
获取成功：
        校验文件是否全部生成
        批量更新文件状态为 PUBLISHED
        删除文件列表缓存
        删除文件元数据缓存
    ↓
释放锁
```

### 13.4 任务重投锁

Key：

```text
exchange-clear:lock:task:resend:{taskNo}
```

使用服务：

```text
task-service
```

流程：

```text
请求重投任务
    ↓
获取 taskNo 级别锁
    ↓
查询任务状态
    ↓
状态允许则发送 MQ
    ↓
更新任务状态为 SENT
    ↓
释放锁
```

### 13.5 Worker 执行锁

Key：

```text
exchange-clear:lock:worker:{taskNo}
```

说明：

第二阶段已经通过数据库条件更新保证幂等：

```sql
UPDATE settle_file_task
SET status = 'GENERATING'
WHERE task_no = ?
  AND status IN ('INIT', 'SENT', 'FAILED');
```

这已经可以解决绝大多数重复消费问题。

因此 Worker 分布式锁是增强方案：

```text
可做，但不必强依赖。
```

推荐策略：

1. 仍以数据库状态机作为最终幂等保障。
2. Redisson Lock 用于减少无意义并发。
3. 即使 Redis 故障，也不能破坏任务幂等。

---

## 14. 接口调整设计

### 14.1 文件列表接口

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

调整点：

```text
先查 Redis 文件列表缓存，未命中再查 MySQL。
```

### 14.2 文件详情接口

```http
GET /api/files/{fileNo}
```

调整点：

```text
先查 Redis 文件元数据缓存，未命中再查 MySQL。
```

### 14.3 文件校验信息接口

```http
GET /api/files/{fileNo}/checksum
```

调整点：

```text
优先使用文件元数据缓存中的 fileMd5 和 fileSize。
```

### 14.4 创建下载 Token 接口

```http
POST /api/download/token
```

请求参数：

```json
{
  "fileNo": "FILE202606260001"
}
```

返回参数：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "4de8c87f9f93406cbbe1d2d8b0a1faaa",
    "expireSeconds": 300
  }
}
```

### 14.5 Token 下载接口

```http
GET /api/download/files/{fileNo}?token=4de8c87f9f93406cbbe1d2d8b0a1faaa
```

调整点：

```text
必须校验 Token 后才允许下载。
```

---

## 15. 服务改造设计

### 15.1 file-service 改造

新增能力：

1. 文件列表缓存。
2. 文件元数据缓存。
3. 缓存删除。
4. 缓存刷新。
5. 缓存击穿保护。
6. 文件发布锁。

建议包结构：

```text
file-service
└── com.example.file
    ├── cache
    │   ├── FileListCacheService.java
    │   └── FileMetaCacheService.java
    ├── lock
    │   └── FilePublishLockService.java
    └── service
        └── FileService.java
```

### 15.2 download-service 改造

新增能力：

1. 创建下载 Token。
2. 校验下载 Token。
3. 会员级限流。
4. IP 级限流。
5. 下载前读取文件元数据缓存。
6. 限流失败返回 429。

建议包结构：

```text
download-service
└── com.example.download
    ├── token
    │   ├── DownloadTokenService.java
    │   └── DownloadTokenPayload.java
    ├── limit
    │   ├── DownloadLimitService.java
    │   └── RedisRateLimitService.java
    ├── controller
    │   └── DownloadController.java
    └── service
        └── DownloadService.java
```

### 15.3 task-service 改造

新增能力：

1. 任务重投分布式锁。
2. 批量重投分布式锁，可选。
3. 防止同一任务被重复重投。

### 15.4 worker-service 改造

可选新增能力：

1. Worker 执行锁。
2. 减少同一任务重复执行竞争。
3. 保留数据库条件更新作为最终幂等保障。

### 15.5 gateway 改造

新增能力：

1. 解析客户端真实 IP。
2. 透传 `X-Client-IP`。
3. 透传 `X-Member-Id`。
4. 可选：在网关层做 IP 级限流。

第一版建议：

```text
IP 限流放在 download-service，Gateway 只负责透传 IP。
```

---

## 16. 缓存一致性详细设计

### 16.1 文件生成成功

Worker 生成文件成功后：

```text
1. 写入 settle_file
2. 删除文件列表缓存
3. 删除文件元数据缓存，通常新文件还未被缓存，可以不删
```

删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
```

### 16.2 文件发布成功

发布文件后：

```text
1. 更新 MySQL 文件状态为 PUBLISHED
2. 删除相关会员文件列表缓存
3. 删除相关文件元数据缓存
```

### 16.3 文件撤销

撤销文件后：

```text
1. 更新 MySQL 状态为 REVOKED
2. 删除 file:meta:{fileNo}
3. 删除 file:list:{settleDate}:{memberId}
```

### 16.4 文件重发

重发文件时：

```text
1. 新增 version
2. 生成新文件
3. 旧文件状态改为 REISSUED 或 REVOKED
4. 删除旧文件和新文件相关缓存
```

### 16.5 下载次数更新

下载次数频繁变化，不建议每次都删除文件元数据缓存。

策略：

1. `download_count` 不作为缓存强一致字段。
2. 下载次数可以异步统计。
3. 如果管理端需要实时下载次数，可直接查数据库。
4. 文件列表缓存中的 `download_count` 可以允许短时间不准确。

---

## 17. Redis 故障降级设计

### 17.1 Redis 查询失败

如果 Redis 查询失败：

```text
1. 记录错误日志。
2. 降级查询 MySQL。
3. 不影响核心查询功能。
4. 返回结果给用户。
```

适用：

1. 文件列表查询。
2. 文件元数据查询。

### 17.2 Redis 写入失败

如果 Redis 写入缓存失败：

```text
1. 记录日志。
2. 不影响主流程。
3. 直接返回数据库结果。
```

### 17.3 Redis Token 故障

如果 Redis 不可用，下载 Token 无法创建或校验。

第一版策略：

```text
直接返回系统繁忙，禁止下载。
```

响应：

```json
{
  "code": 503001,
  "message": "下载服务暂时不可用，请稍后重试",
  "data": null
}
```

### 17.4 Redis 限流故障

可以配置两种策略：

| 策略 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| Fail Open | Redis 故障时放行请求 | 可用性高 | 失去限流保护 |
| Fail Close | Redis 故障时拒绝请求 | 保护系统 | 用户体验较差 |

推荐：

```text
文件查询缓存：Fail Open
下载 Token：Fail Close
下载限流：Fail Open 或配置化
```

配置项：

```yaml
exchange-clear:
  limit:
    fail-open: true
```

---

## 18. 安全设计

### 18.1 防越权下载

创建 Token 时必须校验：

```text
当前 memberId == 文件所属 memberId
```

下载时必须再次校验：

```text
1. token 存在
2. token.fileNo == 请求 fileNo
3. token.memberId == 当前 memberId
4. 文件所属 memberId == 当前 memberId
```

不能只校验 Token，不校验文件归属。

### 18.2 Token 不应包含敏感明文

Token 本身使用随机字符串：

```text
UUID / SecureRandom
```

不建议使用简单的：

```text
memberId + fileNo
```

### 18.3 下载接口必须校验文件状态

允许下载的状态：

```text
PUBLISHED
```

如果项目还没有实现发布流程，测试阶段可以允许：

```text
GENERATED
```

建议配置化：

```yaml
exchange-clear:
  download:
    allowed-statuses:
      - PUBLISHED
      - GENERATED
```

生产语义下应只允许：

```text
PUBLISHED
```

---

## 19. 监控指标设计

虽然本阶段暂不接 Prometheus，但代码中可以预留日志和指标点。

### 19.1 缓存指标

| 指标 | 说明 |
|---|---|
| file_list_cache_hit | 文件列表缓存命中次数 |
| file_list_cache_miss | 文件列表缓存未命中次数 |
| file_meta_cache_hit | 文件元数据缓存命中次数 |
| file_meta_cache_miss | 文件元数据缓存未命中次数 |
| cache_query_error | Redis 查询异常次数 |
| cache_write_error | Redis 写入异常次数 |

### 19.2 Token 指标

| 指标 | 说明 |
|---|---|
| download_token_create_success | Token 创建成功次数 |
| download_token_create_failed | Token 创建失败次数 |
| download_token_validate_success | Token 校验成功次数 |
| download_token_validate_failed | Token 校验失败次数 |
| download_token_expired | Token 过期次数 |

### 19.3 限流指标

| 指标 | 说明 |
|---|---|
| member_limit_reject | 会员级限流拒绝次数 |
| ip_limit_reject | IP 级限流拒绝次数 |
| limit_redis_error | 限流 Redis 异常次数 |

### 19.4 分布式锁指标

| 指标 | 说明 |
|---|---|
| lock_acquire_success | 获取锁成功次数 |
| lock_acquire_failed | 获取锁失败次数 |
| lock_release_success | 释放锁成功次数 |
| lock_business_timeout | 持锁业务超时次数 |

---

## 20. 测试方案

### 20.1 文件列表缓存测试

步骤：

```text
1. 查询某会员文件列表
2. 第一次请求应查询 MySQL 并写入 Redis
3. 第二次请求应命中 Redis
4. 删除 Redis Key
5. 再次请求应重新查询 MySQL
```

验收：

```text
Redis 中存在 exchange-clear:file:list:{settleDate}:{memberId}
```

### 20.2 文件元数据缓存测试

步骤：

```text
1. 调用文件详情接口
2. 第一次请求查询 MySQL
3. 第二次请求命中 Redis
4. 更新文件状态
5. 验证缓存被删除
```

### 20.3 下载 Token 测试

步骤：

```text
1. 调用 /api/download/token 创建 Token
2. 查看 Redis 中 token key
3. 使用 Token 下载文件
4. 使用错误 memberId 下载
5. 使用错误 fileNo 下载
6. 等待 Token 过期后再次下载
```

验收：

```text
正确 Token 可以下载，错误或过期 Token 不能下载。
```

### 20.4 会员级限流测试

步骤：

```text
1. 配置单会员每秒最多 5 次请求
2. 使用同一个 memberId 连续请求创建 Token 接口
3. 超过 5 次后应返回 429
```

### 20.5 IP 级限流测试

步骤：

```text
1. 配置单 IP 每秒最多 10 次请求
2. 使用同一个 X-Client-IP 连续请求
3. 超过阈值后应返回 429
```

### 20.6 分布式锁测试

文件发布锁测试：

```text
1. 启动两个 file-service 实例
2. 同时请求发布同一结算日期
3. 只有一个实例获取锁成功
4. 另一个实例返回正在发布
```

任务重投锁测试：

```text
1. 启动两个 task-service 实例
2. 同时重投同一个 taskNo
3. 只有一个实例发送 MQ
```

---

## 21. 压测方案

### 21.1 文件列表查询压测

场景：

```text
会员数量：400
每个会员文件数：5
并发用户：500
持续时间：5 分钟
```

对比：

1. 未接 Redis。
2. 接入 Redis 后。

关注指标：

1. 平均响应时间。
2. P95 响应时间。
3. MySQL QPS。
4. Redis QPS。
5. 缓存命中率。

目标：

```text
缓存命中率 >= 90%
P95 响应时间明显下降
MySQL QPS 明显下降
```

### 21.2 下载 Token 压测

场景：

```text
并发用户：300
单会员 QPS：20
单 IP QPS：50
持续时间：5 分钟
```

关注指标：

1. Token 创建成功数。
2. 限流拒绝数。
3. Redis CPU。
4. Redis 响应时间。
5. download-service 响应时间。

### 21.3 限流压测

场景：

```text
同一 memberId 每秒发起 100 次请求
限流阈值：20 次/秒
```

预期：

```text
每秒约 20 次成功，其余返回 429。
```

---

## 22. 验收标准

### 22.1 功能验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | Redis 可以通过 Docker Compose 启动 | 必须 |
| 2 | file-service 可以连接 Redis | 必须 |
| 3 | download-service 可以连接 Redis | 必须 |
| 4 | 文件列表查询支持 Redis 缓存 | 必须 |
| 5 | 文件元数据查询支持 Redis 缓存 | 必须 |
| 6 | 缓存未命中可以回源 MySQL | 必须 |
| 7 | 文件状态变更后可以删除缓存 | 必须 |
| 8 | 可以创建下载 Token | 必须 |
| 9 | 下载时可以校验 Token | 必须 |
| 10 | 错误 Token 不能下载 | 必须 |
| 11 | 过期 Token 不能下载 | 必须 |
| 12 | 支持会员级限流 | 必须 |
| 13 | 支持 IP 级限流 | 必须 |
| 14 | 超过限流阈值返回 429 | 必须 |
| 15 | 支持发布分布式锁 | 必须 |
| 16 | 支持任务重投分布式锁 | 建议 |
| 17 | 支持缓存击穿保护 | 建议 |

### 22.2 技术验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | Redis Key 命名规范统一 | 必须 |
| 2 | 所有缓存 Key 有 TTL | 必须 |
| 3 | 限流逻辑原子执行 | 必须 |
| 4 | 分布式锁使用 tryLock | 必须 |
| 5 | 分布式锁 finally 中释放 | 必须 |
| 6 | Redis 异常有降级策略 | 必须 |
| 7 | Token 使用随机字符串 | 必须 |
| 8 | Token 绑定 fileNo 和 memberId | 必须 |
| 9 | 下载前二次校验文件归属 | 必须 |
| 10 | 缓存一致性策略清晰 | 必须 |

---

## 23. 开发计划

### 23.1 Day 1：接入 Redis 和 Redisson

完成：

1. Docker Compose 增加 Redis。
2. 各服务增加 Redis 依赖。
3. 各服务增加 Redisson 依赖。
4. 配置 Redis 连接池。
5. 编写 RedisKey 工具类。
6. 验证 Redis 读写。

### 23.2 Day 2：文件列表缓存

完成：

1. 实现 FileListCacheService。
2. 查询文件列表优先走 Redis。
3. 缓存未命中回源 MySQL。
4. 写入 Redis。
5. 文件状态变更后删除缓存。
6. 支持空列表缓存。

### 23.3 Day 3：文件元数据缓存

完成：

1. 实现 FileMetaCacheService。
2. 文件详情优先走 Redis。
3. 下载服务查询文件信息优先走缓存。
4. 文件状态变更后删除缓存。
5. 支持 NULL 值缓存。

### 23.4 Day 4：下载 Token

完成：

1. 新增 DownloadTokenService。
2. 实现 Token 创建接口。
3. Token 写入 Redis。
4. 实现 Token 校验。
5. 下载接口强制校验 Token。
6. Token 过期自动失效。

### 23.5 Day 5：会员级和 IP 级限流

完成：

1. 实现 Redis Lua 限流脚本。
2. 实现会员级限流。
3. 实现 IP 级限流。
4. 超限返回 429。
5. 增加配置项控制限流开关和阈值。

### 23.6 Day 6：分布式锁

完成：

1. 实现文件发布锁。
2. 实现任务重投锁。
3. 实现缓存回源锁。
4. Worker 执行锁可选。
5. 多实例并发测试。

### 23.7 Day 7：测试和文档

完成：

1. 编写接口测试用例。
2. 编写 Redis Key 文档。
3. 更新 README。
4. 完成缓存命中测试。
5. 完成限流测试。
6. 完成 Token 测试。
7. 完成分布式锁测试。

---

## 24. 演示流程

### 24.1 启动环境

```bash
docker-compose up -d
```

启动组件：

```text
MySQL
MinIO
RocketMQ
Nacos
Redis
```

### 24.2 查询文件列表缓存

```http
GET http://localhost:9000/api/files?settleDate=2026-06-26&memberId=0001
```

观察 Redis：

```bash
GET exchange-clear:file:list:20260626:0001
```

### 24.3 查询文件元数据缓存

```http
GET http://localhost:9000/api/files/FILE202606260001
```

观察 Redis：

```bash
GET exchange-clear:file:meta:FILE202606260001
```

### 24.4 创建下载 Token

```http
POST http://localhost:9000/api/download/token
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

请求：

```json
{
  "fileNo": "FILE202606260001"
}
```

观察 Redis：

```bash
KEYS exchange-clear:download:token:*
```

### 24.5 使用 Token 下载

```http
GET http://localhost:9000/api/download/files/FILE202606260001?token=4de8c87f9f93406cbbe1d2d8b0a1faaa
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

### 24.6 测试限流

使用 JMeter 或循环请求：

```bash
for i in {1..100}; do
  curl -H "X-Member-Id: 0001" -H "X-Client-IP: 192.168.1.10" \
  http://localhost:9000/api/download/token
done
```

预期：

```text
部分请求返回 429。
```

---

## 25. 后续演进方向

### 25.1 第五阶段：一致性补偿

目标：

1. 本地消息表。
2. MQ 发送失败补偿。
3. MinIO 与 MySQL 文件状态对账。
4. 任务超时恢复。
5. 失败任务自动重试。
6. 死信队列处理。

### 25.2 第六阶段：监控和压测

目标：

1. Prometheus。
2. Grafana。
3. JMeter 压测。
4. Redis 缓存命中率监控。
5. Redis 限流拒绝数监控。
6. 下载 QPS 监控。
7. Worker 生成耗时监控。
8. RocketMQ 积压监控。

### 25.3 生产级 Redis 优化

目标：

1. Redis Sentinel。
2. Redis Cluster。
3. 多级缓存。
4. 热点 Key 拆分。
5. 缓存预热。
6. 缓存批量删除优化。
7. 大 Value 拆分。
8. 慢查询分析。

---

## 26. 简历描述建议

第四阶段完成后，可以在简历中这样描述：

```text
在结算文件分布式生成平台中引入 Redis，针对会员文件列表、文件元数据和下载链路进行高并发优化。通过 Redis 缓存文件列表和元数据，降低 MySQL 查询压力；通过短期下载 Token 控制文件下载授权；通过 Redis Lua 实现会员级和 IP 级限流，防止集中下载和异常请求冲击系统；通过 Redisson 分布式锁控制文件发布、任务重投和缓存回源，保证多实例环境下关键操作的互斥执行。
```

项目亮点可以写：

```text
1. 设计统一 Redis Key 规范，覆盖文件列表缓存、文件元数据缓存、下载 Token、限流和分布式锁。
2. 使用 Redis 缓存会员文件列表和文件元数据，降低高频查询场景下的数据库压力。
3. 设计短期下载 Token，绑定 fileNo、memberId 和过期时间，提升文件下载安全性。
4. 使用 Redis Lua 实现会员级和 IP 级限流，超限请求返回 429，保护下载服务和对象存储。
5. 使用 Redisson 分布式锁控制文件发布和任务重投，避免多实例环境下重复操作。
6. 设计缓存一致性策略，在文件状态变更、发布、撤销和重发时删除相关缓存。
7. 设计 Redis 故障降级策略，保证缓存异常时核心查询能力可降级运行。
```

---

## 27. 面试可讲问题

### 27.1 为什么文件列表要做 Redis 缓存？

结算文件发布后，会员系统会集中轮询查询文件列表，如果每次都查 MySQL，会导致数据库压力较大。文件列表在短时间内变化频率不高，适合缓存。使用 Redis 缓存后，大量重复查询可以直接命中缓存，降低 MySQL QPS，提高接口响应速度。

### 27.2 文件元数据缓存如何保证一致性？

采用更新数据库后删除缓存的策略。文件状态发生变化，例如生成、发布、撤销、重发时，先更新 MySQL，再删除相关 Redis 缓存。下一次查询缓存未命中时重新从数据库加载。这样可以避免直接更新缓存带来的复杂一致性问题。

### 27.3 为什么下载要引入 Token？

如果直接暴露下载接口，下载地址可能被长期保存或转发。下载 Token 可以控制下载授权的有效期，并绑定 fileNo、memberId 和过期时间。下载时再次校验 Token 和文件归属，防止越权下载。

### 27.4 会员级限流和 IP 级限流有什么区别？

会员级限流用于限制某个业务主体的下载频率，防止单个会员系统异常请求。IP 级限流用于限制某个客户端来源的请求频率，防止脚本或异常客户端刷接口。两者组合可以同时从业务维度和网络维度保护系统。

### 27.5 为什么使用 Redis Lua 实现限流？

限流需要保证计数和设置过期时间的原子性。Redis Lua 脚本可以在 Redis 服务端一次性执行 INCR、PEXPIRE 和阈值判断，避免并发场景下计数不准确。第一版使用固定窗口限流，简单、高效、容易实现。

### 27.6 分布式锁用在哪些地方？

主要用于多实例环境下需要互斥执行的业务动作，例如同一结算日期文件发布、同一任务人工重投、缓存击穿时数据库回源等。通过 Redisson 的 RLock 可以保证同一时刻只有一个实例执行关键逻辑。

### 27.7 Redis 故障时系统怎么办？

不同场景采用不同降级策略。文件列表和文件元数据缓存失败时，可以降级查询 MySQL，保证查询可用。下载 Token 依赖 Redis 保证安全，如果 Redis 不可用，则下载服务可以返回暂时不可用，避免绕过安全校验。限流失败时可以配置 fail-open 或 fail-close，根据业务对可用性和保护性的要求选择。

---

## 28. 总结

第四阶段通过引入 Redis，让系统从“可分布式生成文件”进一步升级为“可承载高并发查询和下载”的系统。

完成本阶段后，系统具备以下能力：

1. 文件列表缓存。
2. 文件元数据缓存。
3. 下载 Token。
4. 会员级限流。
5. IP 级限流。
6. 分布式锁。
7. 缓存一致性处理。
8. Redis 故障降级。
9. 多实例下关键操作互斥。
10. 高并发查询和下载保护能力。

这一阶段是项目从“分布式任务系统”向“高并发服务系统”演进的关键阶段。

后续结合一致性补偿、监控、压测和告警后，项目将具备更完整的大型 Java 后端系统特征。
