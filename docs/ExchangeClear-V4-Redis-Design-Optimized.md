# ExchangeClear 第四阶段：引入 Redis 高并发增强详细设计文档（优化版）

## 1. 文档说明

本文档用于指导 **ExchangeClear 金融交易所结算文件生成与发布平台** 第四阶段的开发。

本版本是对原第四阶段 Redis 设计文档的修正版，核心修正点如下：

1. 删除文件发布流程设计。
2. 删除 `PUBLISHED` 状态相关设计。
3. 删除文件发布锁、发布接口、发布后缓存删除等内容。
4. 当前阶段仍以 `GENERATED` 文件作为可查询、可下载文件状态。
5. 分布式锁只用于任务重投、Worker 执行保护、缓存回源保护等当前已有业务场景。

截至第四阶段开始前，项目已经完成：

1. 第一阶段：单体闭环。
2. 第二阶段：引入 RocketMQ。
3. 第三阶段：拆分微服务。

当前项目已有核心链路：

```text
任务创建
    ↓
RocketMQ 投递文件生成任务
    ↓
Worker 消费任务
    ↓
生成结算文件
    ↓
上传 MinIO
    ↓
写入文件元数据
    ↓
文件状态为 GENERATED
    ↓
会员查询文件列表
    ↓
会员下载文件
```

因此，第四阶段 Redis 的设计必须围绕当前已有能力展开。

---

## 2. 当前阶段定位

### 2.1 阶段名称

**V4：Redis 高并发增强阶段**

### 2.2 阶段目标

第四阶段的核心目标是：

> 在已有微服务架构和 RocketMQ 异步文件生成能力基础上，引入 Redis，提升文件列表查询、文件元数据查询、下载鉴权、接口限流和多实例协作能力。

本阶段重点解决以下问题：

1. 文件列表高频查询反复访问 MySQL。
2. 文件元数据高频查询反复访问 MySQL。
3. 下载接口缺少短期授权机制。
4. 单个会员可能高频请求下载接口。
5. 单个 IP 可能高频请求下载接口。
6. 多实例部署下，任务重投、缓存回源、Worker 执行存在并发竞争。
7. Redis 异常时，需要有清晰的降级策略。

---

## 3. 本阶段目标与非目标

### 3.1 功能目标

1. 实现文件列表缓存。
2. 实现文件元数据缓存。
3. 实现下载 Token。
4. 实现会员级限流。
5. 实现 IP 级限流。
6. 实现任务重投分布式锁。
7. 实现 Worker 执行分布式锁，可选。
8. 实现缓存回源分布式锁。
9. 实现缓存删除和缓存一致性策略。
10. 实现 Redis 故障降级策略。

### 3.2 非目标

本阶段不做以下内容：

1. 不做文件发布流程。
2. 不新增 `PUBLISHED` 状态。
3. 不做文件撤销。
4. 不做文件重发版本管理。
5. 不做发布分布式锁。
6. 不做发布后缓存刷新。
7. 不做完整下载审计。
8. 不做 Redis Cluster。
9. 不做 Redis Sentinel。
10. 不做多级缓存。
11. 不做本地缓存 Caffeine。
12. 不做完整风控系统。

文件发布、撤销、重发、版本管理建议放到后续阶段单独设计。

---

## 4. 当前文件状态约定

### 4.1 当前已有状态

当前项目文件生成成功后，文件状态为：

```text
GENERATED
```

当前阶段约定：

```text
GENERATED = 已生成，可查询，可下载
```

也就是说，第四阶段下载接口只需要判断：

```text
文件状态 == GENERATED
```

即可允许下载。

### 4.2 暂不引入 PUBLISHED

本阶段不引入：

```text
PUBLISHED = 已发布
```

原因：

1. 前面几个阶段没有设计文件发布流程。
2. 当前系统没有发布接口。
3. 当前文件生成成功后已经可以查询和下载。
4. 过早引入 `PUBLISHED` 会造成流程不一致。
5. 发布、撤销、重发应作为后续独立阶段设计。

---

## 5. Redis 引入后的总体架构

### 5.1 架构图

```text
                          ┌────────────────────┐
                          │     会员系统/用户端  │
                          └─────────┬──────────┘
                                    │
                                    ▼
                          ┌────────────────────┐
                          │       Gateway       │
                          │   统一入口/IP透传    │
                          └─────────┬──────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
┌────────────────────┐ ┌────────────────────┐ ┌────────────────────┐
│   task-service      │ │   file-service      │ │ download-service    │
│   任务服务           │ │   文件元数据服务      │ │ 文件下载服务         │
└─────────┬──────────┘ └─────────┬──────────┘ └─────────┬──────────┘
          │                      │                      │
          │ 任务重投锁             │ 文件列表/元数据缓存       │ Token/限流
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 ▼
                         ┌────────────────┐
                         │     Redis       │
                         │ 缓存/Token/限流/锁│
                         └───────┬────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
     ┌────────────────┐ ┌────────────────┐ ┌────────────────┐
     │     MySQL       │ │     MinIO       │ │    RocketMQ     │
     │ 任务/文件元数据   │ │  文件对象存储    │ │  文件生成任务     │
     └────────────────┘ └────────────────┘ └────────────────┘
```

### 5.2 服务职责变化

| 服务 | 第四阶段新增职责 |
|---|---|
| gateway | 透传客户端真实 IP、会员标识 |
| file-service | 文件列表缓存、文件元数据缓存、缓存删除 |
| download-service | 下载 Token、Token 校验、会员限流、IP 限流 |
| task-service | 任务重投分布式锁 |
| worker-service | Worker 执行锁，可选；生成成功后删除相关缓存 |
| common 模块 | Redis Key 工具类、通用限流组件、Token DTO |

---

## 6. Redis 使用场景总览

| 场景 | 所属服务 | Redis 能力 | 是否必须 |
|---|---|---|---|
| 文件列表缓存 | file-service | String JSON | 必须 |
| 文件元数据缓存 | file-service / download-service | String JSON | 必须 |
| 下载 Token | download-service | String + TTL | 必须 |
| 会员级限流 | download-service | Lua / Counter | 必须 |
| IP 级限流 | download-service | Lua / Counter | 必须 |
| 任务重投锁 | task-service | Redisson Lock | 必须 |
| 缓存回源锁 | file-service | Redisson Lock | 建议 |
| Worker 执行锁 | worker-service | Redisson Lock | 可选 |
| 批量发送任务锁 | task-service | Redisson Lock | 可选 |

---

## 7. Redis Key 规范

### 7.1 命名原则

统一格式：

```text
exchange-clear:{业务域}:{类型}:{业务标识}
```

原则：

1. 所有 Key 必须以 `exchange-clear` 开头。
2. 使用小写英文。
3. 使用冒号分隔层级。
4. 结算日期统一使用 `yyyyMMdd`。
5. 所有缓存 Key 必须设置 TTL。
6. Token Key 必须设置 TTL。
7. 锁 Key 必须设置自动释放时间。
8. 不允许在 Key 中放入中文。

### 7.2 Key 总览

| Key | 类型 | 说明 | TTL |
|---|---|---|---|
| exchange-clear:file:list:{settleDate}:{memberId} | String(JSON) | 会员文件列表缓存 | 10 分钟 |
| exchange-clear:file:meta:{fileNo} | String(JSON) | 文件元数据缓存 | 30 分钟 |
| exchange-clear:download:token:{token} | String(JSON) | 下载 Token | 5 分钟 |
| exchange-clear:limit:member:{memberId} | String | 会员级限流计数 | 1 秒 |
| exchange-clear:limit:ip:{ip} | String | IP 级限流计数 | 1 秒 |
| exchange-clear:lock:task:resend:{taskNo} | Lock | 任务重投锁 | 30 秒 |
| exchange-clear:lock:worker:{taskNo} | Lock | Worker 执行锁 | 10 分钟 |
| exchange-clear:lock:cache:file-list:{settleDate}:{memberId} | Lock | 文件列表缓存回源锁 | 10 秒 |
| exchange-clear:lock:cache:file-meta:{fileNo} | Lock | 文件元数据缓存回源锁 | 10 秒 |
| exchange-clear:lock:task:send-batch:{settleDate}:{fileType} | Lock | 批量发送任务锁，可选 | 60 秒 |

### 7.3 文件列表缓存 Key

格式：

```text
exchange-clear:file:list:{settleDate}:{memberId}
```

示例：

```text
exchange-clear:file:list:20260626:0001
```

Value 示例：

```json
[
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
    "downloadCount": 12
  }
]
```

TTL：

```text
10 分钟
```

### 7.4 文件元数据缓存 Key

格式：

```text
exchange-clear:file:meta:{fileNo}
```

示例：

```text
exchange-clear:file:meta:FILE202606260001
```

Value 示例：

```json
{
  "fileNo": "FILE202606260001",
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

TTL：

```text
30 分钟
```

### 7.5 下载 Token Key

格式：

```text
exchange-clear:download:token:{token}
```

示例：

```text
exchange-clear:download:token:4de8c87f9f93406cbbe1d2d8b0a1faaa
```

Value 示例：

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

TTL：

```text
5 分钟
```

---

## 8. 技术依赖设计

### 8.1 Maven 依赖

需要接入 Redis 的服务包括：

1. file-service。
2. download-service。
3. task-service。
4. worker-service，可选。
5. gateway，可选。

依赖：

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

### 8.2 Redis 配置

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
```

### 8.3 Redisson 配置

```yaml
redisson:
  single-server-config:
    address: redis://localhost:6379
    database: 0
```

或者使用 `redisson.yml`：

```yaml
singleServerConfig:
  address: "redis://localhost:6379"
  database: 0
  connectionMinimumIdleSize: 8
  connectionPoolSize: 32
threads: 8
nettyThreads: 16
```

### 8.4 Docker Compose 增加 Redis

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

## 9. 文件列表缓存设计

### 9.1 适用接口

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

所属服务：

```text
file-service
```

### 9.2 查询流程

```text
请求查询文件列表
    ↓
构造 Key：exchange-clear:file:list:{settleDate}:{memberId}
    ↓
查询 Redis
    ↓
缓存命中？
    ├── 是：反序列化返回
    └── 否：
        ↓
        获取缓存回源锁
        ↓
        再次查询 Redis
        ↓
        仍未命中则查询 MySQL
        ↓
        写入 Redis
        ↓
        返回结果
```

### 9.3 缓存内容

只缓存当前阶段可查询的文件列表，状态通常是：

```text
GENERATED
```

如果后续引入文件发布，则可改为只缓存 `PUBLISHED` 文件，但本阶段不做。

### 9.4 伪代码

```java
public List<FileDTO> listFiles(LocalDate settleDate, String memberId) {
    String key = RedisKeys.fileList(settleDate, memberId);

    String value = redisTemplate.opsForValue().get(key);
    if (StringUtils.hasText(value)) {
        return JsonUtils.toList(value, FileDTO.class);
    }

    RLock lock = redissonClient.getLock(RedisKeys.fileListCacheLock(settleDate, memberId));

    try {
        boolean locked = lock.tryLock(100, 10, TimeUnit.SECONDS);
        if (locked) {
            value = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(value)) {
                return JsonUtils.toList(value, FileDTO.class);
            }

            List<FileDTO> files = fileMapper.selectBySettleDateAndMemberId(settleDate, memberId);
            Duration ttl = files.isEmpty() ? Duration.ofMinutes(1) : Duration.ofMinutes(10);
            redisTemplate.opsForValue().set(key, JsonUtils.toJson(files), ttl);
            return files;
        }

        Thread.sleep(50);
        value = redisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(value)) {
            return JsonUtils.toList(value, FileDTO.class);
        }

        return fileMapper.selectBySettleDateAndMemberId(settleDate, memberId);

    } catch (Exception e) {
        return fileMapper.selectBySettleDateAndMemberId(settleDate, memberId);
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 9.5 空列表缓存

如果某会员某日没有文件，也需要缓存空列表，避免缓存穿透。

空列表：

```json
[]
```

TTL：

```text
1 分钟
```

### 9.6 缓存删除时机

当前阶段涉及文件列表变化的场景主要是：

1. Worker 生成文件成功。
2. 任务重投后重新生成文件成功。
3. 文件元数据被人工修正。
4. 文件被删除，测试环境可选。

删除 Key：

```text
exchange-clear:file:list:{settleDate}:{memberId}
```

推荐策略：

```text
先更新 MySQL，再删除 Redis 缓存
```

---

## 10. 文件元数据缓存设计

### 10.1 适用接口

```http
GET /api/files/{fileNo}
GET /api/files/{fileNo}/checksum
POST /api/download/token
GET /api/download/files/{fileNo}
```

所属服务：

```text
file-service
download-service
```

### 10.2 查询流程

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

### 10.3 伪代码

```java
public FileDTO getFileMeta(String fileNo) {
    String key = RedisKeys.fileMeta(fileNo);

    String value = redisTemplate.opsForValue().get(key);
    if (StringUtils.hasText(value)) {
        if ("NULL".equals(value)) {
            return null;
        }
        return JsonUtils.toObject(value, FileDTO.class);
    }

    RLock lock = redissonClient.getLock(RedisKeys.fileMetaCacheLock(fileNo));

    try {
        boolean locked = lock.tryLock(100, 10, TimeUnit.SECONDS);
        if (locked) {
            value = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(value)) {
                if ("NULL".equals(value)) {
                    return null;
                }
                return JsonUtils.toObject(value, FileDTO.class);
            }

            FileDTO file = fileMapper.selectByFileNo(fileNo);
            if (file == null) {
                redisTemplate.opsForValue().set(key, "NULL", Duration.ofMinutes(1));
                return null;
            }

            redisTemplate.opsForValue().set(key, JsonUtils.toJson(file), Duration.ofMinutes(30));
            return file;
        }

        return fileMapper.selectByFileNo(fileNo);

    } catch (Exception e) {
        return fileMapper.selectByFileNo(fileNo);
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 10.4 NULL 值缓存

如果 `fileNo` 不存在，可以缓存特殊值：

```text
NULL
```

TTL：

```text
1 分钟
```

目的：

```text
防止不存在的 fileNo 被恶意反复请求导致数据库压力升高。
```

### 10.5 缓存删除时机

文件元数据缓存需要在以下场景删除：

1. Worker 生成成功后，如果已存在旧缓存，删除。
2. 文件元数据人工修正后，删除。
3. 任务重投并重新生成文件后，删除。
4. 文件记录删除后，删除。

删除 Key：

```text
exchange-clear:file:meta:{fileNo}
```

注意：

`download_count` 不建议作为强一致缓存字段。下载次数频繁变化，如果每次下载都删除元数据缓存，会导致缓存频繁失效。

建议：

1. 文件元数据缓存可以不要求 download_count 实时准确。
2. 管理端查询实时下载次数时可以走数据库。
3. 后续可将下载次数异步统计。

---

## 11. 下载 Token 设计

### 11.1 为什么需要下载 Token

如果下载接口只依赖：

```http
GET /api/files/{fileNo}/download
```

存在以下问题：

1. `fileNo` 泄露后可能被反复尝试。
2. 下载链接无法控制有效期。
3. 难以绑定会员身份。
4. 难以防止跨会员越权访问。
5. 难以在下载前做限流和授权控制。

引入下载 Token 后，下载变成两步：

```text
第一步：申请下载 Token
第二步：携带 Token 下载文件
```

### 11.2 下载流程

```text
会员请求创建下载 Token
    ↓
解析 memberId 和 clientIp
    ↓
IP 级限流
    ↓
会员级限流
    ↓
查询文件元数据
    ↓
校验文件存在
    ↓
校验文件状态为 GENERATED
    ↓
校验文件所属会员 == 当前会员
    ↓
生成随机 Token
    ↓
写入 Redis，TTL 5 分钟
    ↓
返回 Token

会员携带 Token 请求下载
    ↓
解析 fileNo、token、memberId、clientIp
    ↓
校验 Token 是否存在
    ↓
校验 Token 是否过期
    ↓
校验 token.fileNo == 请求 fileNo
    ↓
校验 token.memberId == 当前会员
    ↓
再次查询文件元数据
    ↓
再次校验文件所属会员和状态
    ↓
从 MinIO 读取文件流
    ↓
返回文件
```

### 11.3 创建 Token 接口

```http
POST /api/download/token
```

请求头：

```http
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

请求体：

```json
{
  "fileNo": "FILE202606260001"
}
```

响应：

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

### 11.4 使用 Token 下载接口

```http
GET /api/download/files/{fileNo}?token=4de8c87f9f93406cbbe1d2d8b0a1faaa
```

请求头：

```http
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

### 11.5 Token 数据结构

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

### 11.6 Token 生成方式

推荐：

```java
UUID.randomUUID().toString().replace("-", "")
```

更安全方式：

```java
SecureRandom 生成 32 字节随机数，再进行 Base64Url 编码
```

不要使用：

```text
memberId + fileNo
```

这种可预测方式。

### 11.7 Token TTL

默认：

```text
5 分钟
```

配置：

```yaml
exchange-clear:
  download:
    token-expire-seconds: 300
```

### 11.8 Token 是否一次性使用

本阶段建议：

```text
默认不是一次性 Token，有效期内可重复下载。
```

原因：

1. 文件可能较大。
2. 下载失败后需要重试。
3. 后续支持断点续传时，需要 Token 在有效期内可复用。

后续可通过配置支持一次性 Token。

### 11.9 Token 校验规则

下载时必须校验：

1. Token 存在。
2. Token 未过期。
3. Token 中 `fileNo` 与请求 `fileNo` 一致。
4. Token 中 `memberId` 与当前 `memberId` 一致。
5. 文件所属 `memberId` 与当前 `memberId` 一致。
6. 文件状态为 `GENERATED`。

可选校验：

1. Token 中 `clientIp` 与当前 IP 一致。
2. User-Agent 一致。

IP 绑定建议配置化，因为企业网络或代理环境可能导致 IP 变化。

---

## 12. 会员级限流设计

### 12.1 限流目标

防止某个会员系统异常高频请求下载相关接口。

### 12.2 限流接口

本阶段建议对以下接口做会员级限流：

1. `POST /api/download/token`
2. `GET /api/download/files/{fileNo}`

文件列表查询接口是否限流可以后续再加。

### 12.3 限流规则

默认：

```text
单会员每秒最多 20 次下载相关请求
```

配置：

```yaml
exchange-clear:
  limit:
    member:
      enabled: true
      permits-per-second: 20
```

### 12.4 Redis Key

```text
exchange-clear:limit:member:{memberId}
```

示例：

```text
exchange-clear:limit:member:0001
```

### 12.5 Lua 固定窗口限流脚本

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

### 12.6 Java 调用示例

```java
public boolean tryAcquireMember(String memberId) {
    String key = RedisKeys.memberLimit(memberId);

    Long result = redisTemplate.execute(
        limitScript,
        Collections.singletonList(key),
        String.valueOf(memberLimit),
        String.valueOf(1000)
    );

    return result != null && result == 1L;
}
```

### 12.7 限流失败响应

HTTP 状态码：

```http
429 Too Many Requests
```

响应：

```json
{
  "code": 429001,
  "message": "会员下载请求过于频繁，请稍后重试",
  "data": null
}
```

---

## 13. IP 级限流设计

### 13.1 限流目标

防止同一个 IP 高频刷接口。

### 13.2 限流接口

本阶段建议对以下接口做 IP 级限流：

1. `POST /api/download/token`
2. `GET /api/download/files/{fileNo}`

### 13.3 限流规则

默认：

```text
单 IP 每秒最多 50 次下载相关请求
```

配置：

```yaml
exchange-clear:
  limit:
    ip:
      enabled: true
      permits-per-second: 50
```

### 13.4 Redis Key

```text
exchange-clear:limit:ip:{ip}
```

示例：

```text
exchange-clear:limit:ip:192.168.1.10
```

### 13.5 IP 获取方式

由 Gateway 统一解析客户端 IP，并透传给后端服务。

后端优先读取：

```http
X-Client-IP
```

Gateway 解析优先级：

```text
1. X-Forwarded-For 第一个 IP
2. X-Real-IP
3. remoteAddress
```

### 13.6 IP 限流失败响应

```json
{
  "code": 429002,
  "message": "当前 IP 请求过于频繁，请稍后重试",
  "data": null
}
```

### 13.7 限流执行顺序

推荐顺序：

```text
1. IP 级限流
2. 会员级限流
3. 查询文件元数据
4. 权限校验
5. Token 创建或文件下载
```

原因：

1. IP 限流可以最早拦截无效请求。
2. 会员限流可以保护业务维度。
3. 权限校验和文件查询可能涉及 RPC 或数据库，应放在限流之后。

---

## 14. 分布式锁设计

### 14.1 当前阶段需要的锁

本阶段不涉及文件发布，因此不设计文件发布锁。

当前阶段需要的锁包括：

1. 任务重投锁。
2. Worker 执行锁，可选。
3. 文件列表缓存回源锁。
4. 文件元数据缓存回源锁。
5. 批量发送任务锁，可选。

### 14.2 任务重投锁

#### Key

```text
exchange-clear:lock:task:resend:{taskNo}
```

#### 所属服务

```text
task-service
```

#### 使用接口

```http
POST /api/tasks/{taskNo}/resend
```

#### 目的

防止多个用户或多个实例同时重投同一个任务，导致重复发送 MQ。

#### 流程

```text
请求重投任务
    ↓
获取 taskNo 级别分布式锁
    ↓
获取失败：返回任务正在重投
    ↓
获取成功：
        查询任务状态
        判断是否允许重投
        发送 MQ
        更新任务状态为 SENT
    ↓
释放锁
```

#### Redisson 示例

```java
public void resendTask(String taskNo) {
    RLock lock = redissonClient.getLock(RedisKeys.taskResendLock(taskNo));

    boolean locked = false;
    try {
        locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new BizException("任务正在重投，请勿重复操作");
        }

        doResend(taskNo);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BizException("获取任务重投锁失败");
    } finally {
        if (locked && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### 14.3 Worker 执行锁

#### Key

```text
exchange-clear:lock:worker:{taskNo}
```

#### 所属服务

```text
worker-service
```

#### 是否必须

```text
可选
```

#### 说明

第二阶段已经通过数据库条件更新保证任务幂等：

```sql
UPDATE settle_file_task
SET status = 'GENERATING'
WHERE task_no = ?
  AND status IN ('INIT', 'SENT', 'FAILED');
```

这个设计已经可以避免绝大多数重复消费导致的重复生成。

因此 Worker 执行锁是增强保护：

1. Redis 正常时减少重复竞争。
2. Redis 异常时仍依赖数据库状态机保证幂等。
3. 不能把 Worker 幂等完全依赖 Redis 锁。

推荐原则：

```text
数据库状态机是最终幂等保障，Redis 锁只是辅助。
```

### 14.4 缓存回源锁

#### 文件列表缓存回源锁

```text
exchange-clear:lock:cache:file-list:{settleDate}:{memberId}
```

#### 文件元数据缓存回源锁

```text
exchange-clear:lock:cache:file-meta:{fileNo}
```

#### 目的

防止缓存失效时大量请求同时访问 MySQL。

### 14.5 批量任务发送锁

#### Key

```text
exchange-clear:lock:task:send-batch:{settleDate}:{fileType}
```

#### 所属服务

```text
task-service
```

#### 是否必须

```text
可选
```

#### 使用场景

如果提供批量发送 MQ 接口：

```http
POST /api/tasks/send-batch
```

可以加锁防止同一批任务被多个实例同时批量发送。

---

## 15. Gateway 改造设计

### 15.1 目标

Gateway 在本阶段主要负责：

1. 解析客户端真实 IP。
2. 透传 `X-Client-IP`。
3. 透传 `X-Member-Id`。
4. 后续可扩展为网关级限流。

本阶段限流主要放在 download-service。

### 15.2 IP 透传流程

```text
用户请求
    ↓
Gateway
    ↓
解析 X-Forwarded-For / X-Real-IP / remoteAddress
    ↓
设置 X-Client-IP
    ↓
转发到 download-service
```

### 15.3 请求头规范

| Header | 说明 |
|---|---|
| X-Member-Id | 当前会员编号，测试阶段可手动传入 |
| X-Client-IP | Gateway 解析后的客户端 IP |
| X-Request-Id | 请求链路 ID，可选 |

---

## 16. 缓存一致性设计

### 16.1 当前阶段会引起缓存变化的场景

由于本阶段没有文件发布流程，因此缓存一致性只关注当前已有动作：

1. Worker 生成文件成功。
2. 任务重投后重新生成成功。
3. 文件元数据人工修正。
4. 文件记录删除，测试环境可选。
5. 文件下载次数更新。

### 16.2 Worker 生成成功后删除缓存

Worker 成功写入 `settle_file` 后，需要删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
```

如果能拿到 `fileNo`，也可以删除：

```text
exchange-clear:file:meta:{fileNo}
```

但新生成的 `fileNo` 通常还没有被缓存，因此删除文件列表缓存最关键。

流程：

```text
Worker 生成文件成功
    ↓
上传 MinIO
    ↓
写入 settle_file
    ↓
更新任务状态 GENERATED
    ↓
删除 file:list:{settleDate}:{memberId}
```

### 16.3 文件元数据修正后删除缓存

如果有管理接口修正文件元数据，例如修正文件大小、MD5、路径等：

```text
更新 MySQL
    ↓
删除 file:meta:{fileNo}
    ↓
删除 file:list:{settleDate}:{memberId}
```

### 16.4 任务重投重新生成后删除缓存

任务重投后如果重新生成文件，可能覆盖同版本文件或重新写入元数据。

完成后需要删除：

```text
exchange-clear:file:list:{settleDate}:{memberId}
exchange-clear:file:meta:{fileNo}
```

### 16.5 下载次数更新不强制删缓存

下载次数 `download_count` 会频繁变化。

不建议每次下载后删除文件元数据缓存，否则会造成缓存频繁失效。

建议：

1. 下载次数更新数据库。
2. 缓存中的 `downloadCount` 允许短时间不准确。
3. 如果需要实时下载次数，管理端直接查数据库。
4. 后续可以将下载次数异步统计到 Redis Counter，再定时回写。

---

## 17. Redis 故障降级设计

### 17.1 文件列表缓存故障

如果 Redis 查询失败：

```text
记录日志
    ↓
降级查询 MySQL
    ↓
返回结果
```

策略：

```text
Fail Open
```

即 Redis 缓存失败不影响文件列表查询。

### 17.2 文件元数据缓存故障

如果 Redis 查询失败：

```text
记录日志
    ↓
降级查询 MySQL
    ↓
返回结果
```

策略：

```text
Fail Open
```

### 17.3 下载 Token 故障

如果 Redis 不可用，Token 无法创建或校验。

推荐策略：

```text
Fail Close
```

即下载 Token 依赖 Redis 安全能力。如果 Redis 不可用，下载接口返回服务暂不可用。

原因：

1. Token 是下载授权凭证。
2. Redis 不可用时绕过 Token 会降低安全性。
3. 下载可以短时间不可用，但不能越权。

响应：

```json
{
  "code": 503001,
  "message": "下载服务暂时不可用，请稍后重试",
  "data": null
}
```

### 17.4 限流 Redis 故障

限流故障有两种策略：

#### 策略一：Fail Open

```text
Redis 限流失败时放行请求。
```

优点：

```text
可用性更好。
```

缺点：

```text
Redis 故障期间失去限流保护。
```

#### 策略二：Fail Close

```text
Redis 限流失败时拒绝请求。
```

优点：

```text
保护后端系统。
```

缺点：

```text
可能误伤正常请求。
```

本阶段推荐配置化：

```yaml
exchange-clear:
  limit:
    fail-open: true
```

默认：

```text
Fail Open
```

### 17.5 分布式锁故障

如果 Redis 不可用，分布式锁无法获取。

不同场景策略：

| 场景 | 策略 |
|---|---|
| 任务重投锁失败 | 拒绝重投，提示稍后重试 |
| Worker 执行锁失败 | 可降级到数据库状态机幂等 |
| 缓存回源锁失败 | 直接查数据库 |
| 批量任务发送锁失败 | 拒绝批量操作 |

---

## 18. 安全设计

### 18.1 防越权下载

创建 Token 时校验：

```text
当前 memberId == 文件所属 memberId
```

下载时再次校验：

```text
1. token 存在
2. token.fileNo == 请求 fileNo
3. token.memberId == 当前 memberId
4. 文件所属 memberId == 当前 memberId
5. 文件状态 == GENERATED
```

注意：

不能只校验 Token，也不能只校验 `fileNo`，必须同时校验会员归属。

### 18.2 Token 防猜测

Token 必须使用随机字符串。

建议长度：

```text
至少 128 bit 随机性
```

推荐：

```java
UUID.randomUUID().toString().replace("-", "")
```

更强：

```java
SecureRandom
```

### 18.3 Token 绑定 IP

是否绑定 IP 建议配置化：

```yaml
exchange-clear:
  download:
    bind-ip: false
```

默认不强制绑定 IP，避免企业代理、NAT、网关转发导致误判。

如果开启 IP 绑定，则校验：

```text
token.clientIp == 当前 X-Client-IP
```

---

## 19. 接口调整设计

### 19.1 文件列表接口

```http
GET /api/files?settleDate=2026-06-26&memberId=0001
```

第四阶段调整：

```text
优先查询 Redis 文件列表缓存。
```

### 19.2 文件详情接口

```http
GET /api/files/{fileNo}
```

第四阶段调整：

```text
优先查询 Redis 文件元数据缓存。
```

### 19.3 文件校验接口

```http
GET /api/files/{fileNo}/checksum
```

第四阶段调整：

```text
优先使用文件元数据缓存中的 fileMd5 和 fileSize。
```

### 19.4 创建下载 Token 接口

```http
POST /api/download/token
```

新增接口。

### 19.5 Token 下载接口

```http
GET /api/download/files/{fileNo}?token={token}
```

新增或替换原下载接口。

建议：

1. 原始下载接口保留为内部调试接口，默认关闭。
2. 对外下载必须使用 Token 下载接口。

---

## 20. 配置项设计

```yaml
exchange-clear:
  redis:
    key-prefix: exchange-clear

  cache:
    file-list:
      ttl-seconds: 600
      null-ttl-seconds: 60
    file-meta:
      ttl-seconds: 1800
      null-ttl-seconds: 60

  download:
    token-expire-seconds: 300
    one-time-token: false
    bind-ip: false
    allowed-statuses:
      - GENERATED

  limit:
    fail-open: true
    member:
      enabled: true
      permits-per-second: 20
    ip:
      enabled: true
      permits-per-second: 50

  lock:
    task-resend:
      wait-seconds: 3
      lease-seconds: 30
    worker:
      enabled: false
      wait-seconds: 1
      lease-seconds: 600
    cache:
      wait-millis: 100
      lease-seconds: 10
```

---

## 21. 代码结构建议

### 21.1 common 模块

```text
exchange-common
├── redis
│   ├── RedisKeys.java
│   └── RedisJsonUtils.java
├── limit
│   ├── RateLimitService.java
│   └── RedisLuaRateLimitService.java
├── lock
│   └── DistributedLockTemplate.java
└── dto
    └── DownloadTokenPayload.java
```

### 21.2 file-service

```text
file-service
├── cache
│   ├── FileListCacheService.java
│   └── FileMetaCacheService.java
├── service
│   └── FileService.java
└── controller
    └── FileController.java
```

### 21.3 download-service

```text
download-service
├── token
│   ├── DownloadTokenService.java
│   └── DownloadTokenPayload.java
├── limit
│   └── DownloadLimitService.java
├── service
│   └── DownloadService.java
└── controller
    └── DownloadController.java
```

### 21.4 task-service

```text
task-service
├── lock
│   └── TaskResendLockService.java
└── service
    └── TaskService.java
```

### 21.5 worker-service

```text
worker-service
├── lock
│   └── WorkerLockService.java
└── service
    └── FileGenerateWorker.java
```

---

## 22. 测试方案

### 22.1 文件列表缓存测试

步骤：

```text
1. 查询某会员文件列表
2. 第一次请求查询 MySQL 并写入 Redis
3. 第二次请求命中 Redis
4. 删除 Redis Key
5. 再次请求重新查询 MySQL
```

验收：

```text
Redis 中存在 exchange-clear:file:list:{settleDate}:{memberId}
```

### 22.2 文件元数据缓存测试

步骤：

```text
1. 查询文件详情
2. 第一次请求查询 MySQL
3. 第二次请求命中 Redis
4. 修改文件元数据
5. 删除缓存
6. 再次查询加载新数据
```

### 22.3 下载 Token 测试

步骤：

```text
1. 调用 /api/download/token 创建 Token
2. 查看 Redis 中 Token Key
3. 使用正确 Token 下载文件
4. 使用错误 Token 下载文件
5. 使用错误 memberId 下载文件
6. 等待 Token 过期后再次下载
```

预期：

```text
只有正确且未过期的 Token 可以下载。
```

### 22.4 会员级限流测试

步骤：

```text
1. 将会员级限流设置为每秒 5 次
2. 使用同一个 memberId 连续请求创建 Token 接口
3. 超过 5 次后返回 429
```

### 22.5 IP 级限流测试

步骤：

```text
1. 将 IP 级限流设置为每秒 10 次
2. 使用同一个 X-Client-IP 连续请求
3. 超过 10 次后返回 429
```

### 22.6 任务重投锁测试

步骤：

```text
1. 启动两个 task-service 实例
2. 同时调用 /api/tasks/{taskNo}/resend
3. 观察是否只有一个请求成功发送 MQ
```

### 22.7 缓存回源锁测试

步骤：

```text
1. 删除某会员文件列表缓存
2. 使用 JMeter 并发请求该文件列表
3. 观察数据库查询次数
4. 验证只有少量请求回源数据库
```

---

## 23. 压测方案

### 23.1 文件列表查询压测

场景：

```text
会员数量：400
每会员文件数：5
并发用户：500
持续时间：5 分钟
```

对比：

1. 未启用 Redis。
2. 启用 Redis。

指标：

1. 平均响应时间。
2. P95 响应时间。
3. MySQL QPS。
4. Redis QPS。
5. 缓存命中率。

目标：

```text
缓存命中率 >= 90%
MySQL QPS 明显下降
P95 响应时间明显下降
```

### 23.2 下载 Token 压测

场景：

```text
并发用户：300
Token 创建接口
持续时间：5 分钟
```

指标：

1. Token 创建成功数。
2. 会员限流拒绝数。
3. IP 限流拒绝数。
4. Redis QPS。
5. download-service 响应时间。

### 23.3 限流压测

场景：

```text
同一 memberId 每秒发起 100 次请求
会员限流阈值：20 次/秒
```

预期：

```text
每秒约 20 次成功，其余返回 429。
```

---

## 24. 验收标准

### 24.1 功能验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | Redis 可以通过 Docker Compose 启动 | 必须 |
| 2 | file-service 可以连接 Redis | 必须 |
| 3 | download-service 可以连接 Redis | 必须 |
| 4 | 文件列表支持 Redis 缓存 | 必须 |
| 5 | 文件元数据支持 Redis 缓存 | 必须 |
| 6 | 缓存未命中可以回源 MySQL | 必须 |
| 7 | 文件生成成功后可以删除文件列表缓存 | 必须 |
| 8 | 可以创建下载 Token | 必须 |
| 9 | 下载时必须校验 Token | 必须 |
| 10 | 错误 Token 不能下载 | 必须 |
| 11 | 过期 Token 不能下载 | 必须 |
| 12 | 非文件所属会员不能下载 | 必须 |
| 13 | 文件状态为 GENERATED 时允许下载 | 必须 |
| 14 | 支持会员级限流 | 必须 |
| 15 | 支持 IP 级限流 | 必须 |
| 16 | 超过限流阈值返回 429 | 必须 |
| 17 | 支持任务重投分布式锁 | 必须 |
| 18 | 支持缓存回源锁 | 建议 |
| 19 | Worker 执行锁可配置开启 | 可选 |

### 24.2 技术验收

| 编号 | 验收项 | 是否必须 |
|---|---|---|
| 1 | Redis Key 命名规范统一 | 必须 |
| 2 | 所有缓存 Key 设置 TTL | 必须 |
| 3 | Token Key 设置 TTL | 必须 |
| 4 | 限流逻辑原子执行 | 必须 |
| 5 | 分布式锁使用 tryLock | 必须 |
| 6 | 分布式锁在 finally 中释放 | 必须 |
| 7 | Redis 异常有降级策略 | 必须 |
| 8 | Token 使用随机字符串 | 必须 |
| 9 | Token 绑定 fileNo 和 memberId | 必须 |
| 10 | 下载前二次校验文件归属 | 必须 |
| 11 | 不依赖 Redis 锁作为 Worker 唯一幂等保障 | 必须 |
| 12 | 文档中不出现文件发布必做流程 | 必须 |

---

## 25. 开发计划

### 25.1 Day 1：接入 Redis 和 Redisson

完成：

1. Docker Compose 增加 Redis。
2. file-service 接入 Redis。
3. download-service 接入 Redis。
4. task-service 接入 Redisson。
5. 编写 RedisKeys 工具类。
6. 验证 Redis 读写。

### 25.2 Day 2：文件列表缓存

完成：

1. 实现 FileListCacheService。
2. 文件列表查询优先走 Redis。
3. 缓存未命中回源 MySQL。
4. 写入 Redis。
5. 空列表缓存。
6. Worker 生成成功后删除文件列表缓存。

### 25.3 Day 3：文件元数据缓存

完成：

1. 实现 FileMetaCacheService。
2. 文件详情查询优先走 Redis。
3. checksum 接口复用元数据缓存。
4. download-service 获取文件元数据时优先走缓存。
5. 实现 NULL 值缓存。

### 25.4 Day 4：下载 Token

完成：

1. 实现 DownloadTokenService。
2. 实现 Token 创建接口。
3. 实现 Token Redis 存储。
4. 实现 Token 校验。
5. 下载接口强制校验 Token。
6. 文件状态校验使用 GENERATED。

### 25.5 Day 5：会员级和 IP 级限流

完成：

1. 编写 Redis Lua 限流脚本。
2. 实现会员级限流。
3. 实现 IP 级限流。
4. 超限返回 429。
5. 增加配置项控制限流开关和阈值。

### 25.6 Day 6：分布式锁

完成：

1. 实现任务重投锁。
2. 实现缓存回源锁。
3. Worker 执行锁可选实现。
4. 多实例并发测试。

### 25.7 Day 7：测试和文档

完成：

1. 完成 Redis Key 文档。
2. 完成接口测试。
3. 完成限流测试。
4. 完成 Token 测试。
5. 完成缓存命中测试。
6. 完成 README 更新。

---

## 26. 演示流程

### 26.1 启动环境

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

### 26.2 查询文件列表缓存

```http
GET http://localhost:9000/api/files?settleDate=2026-06-26&memberId=0001
```

Redis 查看：

```bash
GET exchange-clear:file:list:20260626:0001
```

### 26.3 查询文件元数据缓存

```http
GET http://localhost:9000/api/files/FILE202606260001
```

Redis 查看：

```bash
GET exchange-clear:file:meta:FILE202606260001
```

### 26.4 创建下载 Token

```http
POST http://localhost:9000/api/download/token
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

请求体：

```json
{
  "fileNo": "FILE202606260001"
}
```

Redis 查看：

```bash
KEYS exchange-clear:download:token:*
```

### 26.5 使用 Token 下载

```http
GET http://localhost:9000/api/download/files/FILE202606260001?token=4de8c87f9f93406cbbe1d2d8b0a1faaa
X-Member-Id: 0001
X-Client-IP: 192.168.1.10
```

### 26.6 测试限流

```bash
for i in {1..100}; do
  curl -H "X-Member-Id: 0001" \
       -H "X-Client-IP: 192.168.1.10" \
       -X POST \
       -H "Content-Type: application/json" \
       -d '{"fileNo":"FILE202606260001"}' \
       http://localhost:9000/api/download/token
done
```

预期：

```text
部分请求返回 429。
```

---

## 27. 后续演进方向

### 27.1 第五阶段建议：文件发布、重发与一致性补偿

后续可以单独设计文件发布阶段：

1. 引入 `PUBLISHED` 状态。
2. 文件生成后状态为 `GENERATED`。
3. 管理员或系统触发发布。
4. 校验某结算日期下文件是否全部生成。
5. 批量将文件状态更新为 `PUBLISHED`。
6. 只有 `PUBLISHED` 文件允许会员下载。
7. 支持撤销 `REVOKED`。
8. 支持重发 `REISSUED`。
9. 引入文件发布分布式锁。
10. 发布、撤销、重发后删除 Redis 缓存。
11. 引入本地消息表。
12. 引入对账补偿任务。

### 27.2 第六阶段建议：监控和压测

后续可以接入：

1. Prometheus。
2. Grafana。
3. JMeter。
4. Redis 缓存命中率监控。
5. Redis 限流拒绝数监控。
6. 下载 QPS 监控。
7. Worker 生成耗时监控。
8. RocketMQ 积压监控。

---

## 28. 简历描述建议

第四阶段完成后，可以在简历中这样描述：

```text
在结算文件分布式生成平台中引入 Redis，对会员文件列表查询、文件元数据查询和下载链路进行高并发优化。通过 Redis 缓存文件列表和文件元数据，降低 MySQL 查询压力；通过短期下载 Token 绑定 fileNo、memberId 和过期时间，提升文件下载安全性；通过 Redis Lua 实现会员级和 IP 级限流，防止异常下载请求冲击系统；通过 Redisson 分布式锁控制任务重投和缓存回源，保证多实例环境下关键操作的互斥执行。
```

项目亮点：

```text
1. 设计统一 Redis Key 规范，覆盖文件列表缓存、文件元数据缓存、下载 Token、限流和分布式锁。
2. 使用 Redis 缓存会员文件列表和文件元数据，降低高频查询场景下的数据库压力。
3. 设计短期下载 Token，绑定 fileNo、memberId 和过期时间，防止越权下载。
4. 使用 Redis Lua 实现会员级和 IP 级限流，超限请求返回 429。
5. 使用 Redisson 分布式锁控制任务重投和缓存回源，避免多实例重复操作。
6. 设计缓存一致性策略，在 Worker 生成文件成功、文件元数据修正、任务重投重新生成后删除相关缓存。
7. 设计 Redis 故障降级策略，保证缓存异常时查询可降级运行，下载 Token 异常时安全失败。
```

---

## 29. 面试可讲问题

### 29.1 为什么要缓存文件列表？

结算文件生成后，会员系统可能高频查询自己某个结算日的文件列表。如果每次都查询 MySQL，会造成数据库压力。文件列表在短时间内变化频率不高，适合缓存。通过 Redis 缓存可以降低 MySQL QPS，提高响应速度。

### 29.2 文件元数据缓存如何保证一致性？

采用“先更新数据库，再删除缓存”的策略。当前阶段文件元数据变化主要发生在 Worker 生成文件成功、任务重投重新生成、元数据人工修正等场景。完成数据库更新后删除对应缓存，下次查询缓存未命中时再从数据库加载。

### 29.3 为什么下载要加 Token？

下载 Token 可以把“查询文件”和“真正下载文件”分成两个步骤。创建 Token 时完成会员权限校验、文件状态校验和限流，下载时校验 Token 中的 fileNo、memberId、过期时间，避免 fileNo 泄露后被长期反复下载。

### 29.4 当前阶段为什么允许 GENERATED 下载？

因为前面阶段还没有设计文件发布流程，当前项目中文件生成成功后状态就是 GENERATED，并且已有查询和下载能力。因此第四阶段仍然以 GENERATED 作为允许下载状态。PUBLISHED、撤销、重发等状态应放到后续文件发布阶段设计。

### 29.5 会员级限流和 IP 级限流有什么区别？

会员级限流从业务主体维度限制请求频率，防止某个会员系统异常刷接口。IP 级限流从网络来源维度限制请求频率，防止单个客户端或脚本刷接口。两个维度组合可以更好地保护下载服务。

### 29.6 为什么用 Redis Lua 做限流？

限流需要保证计数和设置过期时间的原子性。Redis Lua 脚本可以在 Redis 服务端一次性执行 INCR、PEXPIRE 和阈值判断，避免并发场景下计数不准确。第一版固定窗口限流实现简单，性能高，适合作为项目起步方案。

### 29.7 分布式锁用在哪些地方？

当前阶段主要用于任务重投、缓存回源和可选的 Worker 执行保护。例如多个 task-service 实例同时重投同一个 taskNo 时，通过分布式锁保证只有一个实例发送 MQ。缓存回源锁用于防止热点文件列表缓存失效时大量请求同时打到 MySQL。

### 29.8 Redis 故障怎么办？

文件列表和文件元数据缓存失败时，降级查询 MySQL，保证查询可用。下载 Token 依赖 Redis 做安全校验，如果 Redis 不可用，下载服务返回暂时不可用。限流失败可以配置 fail-open 或 fail-close，第一版默认 fail-open，提高可用性。

---

## 30. 总结

第四阶段不引入文件发布流程，而是在当前已有的 `GENERATED` 文件查询和下载基础上引入 Redis，完成高并发增强。

本阶段完成后，系统具备：

1. 文件列表缓存能力。
2. 文件元数据缓存能力。
3. 下载 Token 授权能力。
4. 会员级限流能力。
5. IP 级限流能力。
6. 任务重投分布式锁能力。
7. 缓存回源保护能力。
8. Redis 故障降级能力。
9. 多实例环境下关键操作互斥能力。

这一阶段的重点是：

```text
在不改变当前业务主流程的前提下，用 Redis 提升查询性能、下载安全性和接口抗压能力。
```

文件发布、`PUBLISHED` 状态、撤销、重发和发布锁，应放到后续阶段单独设计，避免当前阶段功能边界混乱。
