# AGENTS.md

## 1. 项目概述

本项目是一个基于 Spring Boot 的 Java 后端服务。

主要职责包括：

* 提供 RESTful API 接口
* 处理核心业务逻辑
* 访问数据库并完成数据持久化
* 对接第三方系统或内部服务
* 提供统一的异常处理、日志记录、参数校验和权限控制能力

在修改本项目代码时，请优先保证：

1. 业务正确性
2. 代码可读性
3. 系统稳定性
4. 可测试性
5. 与现有代码风格保持一致

---

## 2. 技术栈

默认技术栈如下，具体以项目实际依赖为准：

* Java：JDK 8 / 11 / 17
* Spring Boot
* Spring MVC
* Spring Validation
* Spring Transaction
* MyBatis / MyBatis-Plus / JPA
* MySQL / Oracle / PostgreSQL / 其他关系型数据库
* Maven / Gradle
* Lombok
* Jackson
* JUnit 5 / JUnit 4
* Mockito
* Redis，可选
* Swagger / Knife4j / OpenAPI，可选

---

## 3. 项目目录结构约定

推荐目录结构如下：

```text
src/main/java/com/example/project
├── ProjectApplication.java
├── config              # 配置类
├── controller          # 接口层
├── service             # 业务接口
├── service/impl        # 业务实现
├── mapper              # MyBatis Mapper / DAO
├── repository          # JPA Repository，可选
├── entity              # 数据库实体
├── domain              # 领域对象，可选
├── dto                 # 请求 / 响应 DTO
├── vo                  # 前端展示对象
├── bo                  # 业务中间对象
├── converter           # 对象转换器
├── enums               # 枚举
├── exception           # 自定义异常
├── handler             # 全局异常处理器等
├── util                # 工具类
├── constant            # 常量
└── task                # 定时任务，可选
```

资源文件目录：

```text
src/main/resources
├── application.yml
├── application-dev.yml
├── application-test.yml
├── application-prod.yml
├── mapper              # MyBatis XML
├── sql                 # 初始化 SQL 或脚本
└── logback-spring.xml  # 日志配置
```

测试目录：

```text
src/test/java/com/example/project
├── controller
├── service
├── mapper
└── integration
```

---

## 4. Agent 工作原则

当你作为 AI Agent 修改本项目时，请遵守以下原则：

### 4.1 先理解，再修改

在修改代码前，请先阅读相关代码，包括：

* Controller
* Service
* Mapper / Repository
* Entity / DTO / VO
* 配置类
* 单元测试或集成测试
* 数据库表结构或 Mapper XML

不要只根据文件名猜测业务逻辑。

### 4.2 最小化修改

优先采用最小改动方案。

除非用户明确要求重构，否则不要大规模调整项目结构、类名、包名或公共方法签名。

### 4.3 保持风格一致

请遵循项目已有风格，包括：

* 命名风格
* 分层方式
* 异常处理方式
* 日志格式
* 返回结果格式
* Mapper 写法
* DTO / VO 使用方式
* 注解使用习惯

不要引入与项目整体风格冲突的新模式。

### 4.4 不要随意引入新依赖

除非确有必要，不要新增第三方依赖。

如果必须新增依赖，请说明：

* 为什么需要该依赖
* 是否有现有依赖可以替代
* 对项目体积、安全性、兼容性的影响

### 4.5 不要破坏兼容性

修改接口时要注意：

* 不要随意删除字段
* 不要随意修改字段含义
* 不要随意修改接口路径
* 不要随意修改 HTTP Method
* 不要随意修改响应结构
* 不要随意修改数据库字段类型

如需变更，需要说明兼容性影响。

---

## 5. 编码规范

### 5.1 Java 代码规范

* 类名使用大驼峰，例如：`UserService`
* 方法名、变量名使用小驼峰，例如：`queryUserList`
* 常量使用大写下划线，例如：`MAX_RETRY_COUNT`
* 避免无意义命名，例如：`data`、`list1`、`temp`
* 方法职责要单一，避免超长方法
* 避免重复代码，必要时抽取私有方法
* 不要在业务代码中直接打印 `System.out.println`
* 使用日志框架记录日志

推荐：

```java
private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
```

或使用 Lombok：

```java
@Slf4j
@Service
public class UserServiceImpl implements UserService {
}
```

### 5.2 Controller 规范

Controller 只负责：

* 接收请求
* 参数校验
* 调用 Service
* 返回结果

Controller 不应包含复杂业务逻辑。

示例：

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/query")
    public Result<List<UserVO>> queryUsers(@Valid @RequestBody UserQueryDTO queryDTO) {
        return Result.success(userService.queryUsers(queryDTO));
    }
}
```

### 5.3 Service 规范

Service 负责核心业务逻辑。

要求：

* 业务逻辑集中在 Service 层
* 涉及多个数据库操作时，需要考虑事务
* 不要在 Service 中返回数据库 Entity 给前端
* 复杂逻辑应拆分为清晰的私有方法

示例：

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public List<UserVO> queryUsers(UserQueryDTO queryDTO) {
        List<UserEntity> users = userMapper.selectByCondition(queryDTO);
        return users.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    private UserVO convertToVO(UserEntity entity) {
        UserVO vo = new UserVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        return vo;
    }
}
```

### 5.4 Mapper / DAO 规范

Mapper 只负责数据库访问。

要求：

* SQL 尽量清晰
* 避免 `select *`
* 大表查询必须带条件
* 分页查询必须使用分页参数
* 批量操作注意数据量
* 动态 SQL 注意空值判断
* 不要在 Mapper 中写业务逻辑

MyBatis XML 示例：

```xml
<select id="selectByCondition" resultType="com.example.project.entity.UserEntity">
    SELECT
        id,
        username,
        status,
        create_time
    FROM t_user
    WHERE deleted = 0
    <if test="username != null and username != ''">
        AND username LIKE CONCAT('%', #{username}, '%')
    </if>
    ORDER BY create_time DESC
</select>
```

---

## 6. 接口设计规范

### 6.1 请求对象

请求参数应使用 DTO 承载。

示例：

```java
@Data
public class UserQueryDTO {

    private String username;

    private Integer status;

    private Integer pageNum = 1;

    private Integer pageSize = 20;
}
```

### 6.2 响应对象

返回给前端的数据应使用 VO。

示例：

```java
@Data
public class UserVO {

    private Long id;

    private String username;

    private String statusName;

    private LocalDateTime createTime;
}
```

### 6.3 统一返回结构

如果项目已有统一返回结构，请复用现有结构。

示例：

```java
@Data
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

    public static <T> Result<T> fail(String message) {
        Result<T> result = new Result<>();
        result.setCode(-1);
        result.setMessage(message);
        return result;
    }
}
```

---

## 7. 参数校验规范

优先使用 Bean Validation。

示例：

```java
@Data
public class CreateUserDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
```

Controller 中使用：

```java
@PostMapping("/create")
public Result<Void> createUser(@Valid @RequestBody CreateUserDTO createUserDTO) {
    userService.createUser(createUserDTO);
    return Result.success(null);
}
```

不要只依赖前端校验，后端必须进行必要校验。

---

## 8. 异常处理规范

项目应使用统一异常处理。

### 8.1 自定义业务异常

```java
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String message) {
        super(message);
        this.code = "-1";
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

### 8.2 全局异常处理

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return Result.fail(message);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail("系统异常，请联系管理员");
    }
}
```

业务代码中不要直接返回异常堆栈给前端。

---

## 9. 事务规范

涉及多个写操作时，需要使用事务。

示例：

```java
@Transactional(rollbackFor = Exception.class)
public void createOrder(CreateOrderDTO createOrderDTO) {
    // 1. 创建订单
    // 2. 扣减库存
    // 3. 写入流水
}
```

注意：

* 默认只对 RuntimeException 回滚
* 推荐显式指定 `rollbackFor = Exception.class`
* 避免在同类内部方法调用中依赖 `@Transactional`
* 事务方法不应执行耗时过长的远程调用
* 大批量处理要避免长事务

---

## 10. 日志规范

日志应能帮助定位问题。

推荐记录：

* 关键业务入口
* 重要参数
* 外部接口调用结果
* 异常信息
* 任务开始和结束
* 批处理进度

示例：

```java
log.info("开始创建用户, username={}", createUserDTO.getUsername());

try {
    userService.createUser(createUserDTO);
} catch (Exception e) {
    log.error("创建用户失败, username={}", createUserDTO.getUsername(), e);
    throw e;
}
```

注意：

* 不要记录密码、身份证号、银行卡号、Token 等敏感信息
* 不要在循环中打印大量日志
* 异常日志必须带堆栈
* 日志信息应包含关键业务标识

---

## 11. 数据库规范

### 11.1 查询规范

* 禁止无条件查询大表
* 禁止生产环境接口一次性返回海量数据
* 大数据量查询必须分页
* 查询字段应明确列出
* 高频查询字段应考虑索引
* 排序字段应考虑索引
* 模糊查询应评估性能影响

### 11.2 写入规范

* 批量插入应控制批次大小
* 更新和删除必须带明确条件
* 逻辑删除优先于物理删除，具体以项目规范为准
* 涉及金额、数量等字段时，应使用 BigDecimal

### 11.3 SQL 性能

编写 SQL 时注意：

* 避免不必要的子查询
* 避免函数作用在索引列上
* 避免隐式类型转换
* 避免大范围 `LIKE '%xxx%'`
* 避免过深分页
* 避免 N+1 查询
* 对复杂 SQL 应使用执行计划分析

---

## 12. 缓存规范

如果项目使用 Redis 或本地缓存，请遵守：

* 缓存 Key 要有明确前缀
* 设置合理过期时间
* 避免缓存穿透、击穿、雪崩
* 修改数据时要考虑缓存一致性
* 不要缓存强实时数据，除非业务允许

Key 示例：

```text
project:user:detail:{userId}
project:user:list:{conditionHash}
```

---

## 13. 远程调用规范

调用第三方服务或内部服务时：

* 必须设置超时时间
* 必须处理异常
* 必须记录关键日志
* 必要时增加重试机制
* 不要在事务中执行不必要的远程调用
* 对批量远程调用要考虑并发控制

示例：

```java
try {
    RemoteUserDTO remoteUser = remoteUserClient.getUser(userId);
    log.info("远程查询用户成功, userId={}", userId);
    return remoteUser;
} catch (Exception e) {
    log.error("远程查询用户失败, userId={}", userId, e);
    throw new BusinessException("远程服务调用失败");
}
```

---

## 14. 定时任务规范

如果项目存在定时任务：

* 任务入口要记录开始和结束日志
* 任务异常不能被静默吞掉
* 长任务应记录进度
* 分布式部署时要考虑重复执行问题
* 任务执行时间应避开业务高峰
* 任务参数应可配置

示例：

```java
@Scheduled(cron = "${task.user-sync.cron}")
public void syncUserTask() {
    log.info("用户同步任务开始");
    try {
        userSyncService.syncUsers();
    } catch (Exception e) {
        log.error("用户同步任务异常", e);
    }
    log.info("用户同步任务结束");
}
```

---

## 15. 批处理规范

对于大数据量处理：

* 不要一次性加载全部数据到内存
* 优先使用分页、游标、流式读取
* 写文件或写库时应分批处理
* 控制线程池大小
* 控制队列长度
* 处理失败时要支持重试或补偿
* 必须记录处理总数、成功数、失败数

推荐批次大小根据实际情况配置，例如：

```yaml
batch:
  chunk-size: 1000
  thread-pool-size: 8
```

---

## 16. 并发与线程池规范

使用线程池时：

* 不要直接使用 `new Thread`
* 不要使用无界队列处理大任务
* 线程池参数应可配置
* 异步任务要处理异常
* 应用关闭时要优雅关闭线程池
* 不要在高并发场景中使用共享可变对象

示例：

```java
@Configuration
public class ThreadPoolConfig {

    @Bean("bizExecutor")
    public Executor bizExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("biz-executor-");
        executor.initialize();
        return executor;
    }
}
```

---

## 17. 安全规范

修改代码时必须注意：

* 不要硬编码密码、Token、密钥
* 不要把敏感配置提交到代码仓库
* 接口必须进行权限校验，具体以项目现有框架为准
* 用户输入不能直接拼接 SQL
* 文件上传必须校验类型、大小和路径
* 防止路径穿越
* 防止 SQL 注入
* 防止越权访问
* 日志中不要打印敏感信息

错误示例：

```java
String sql = "SELECT * FROM t_user WHERE name = '" + name + "'";
```

正确示例：

```java
SELECT id, username FROM t_user WHERE name = #{name}
```

---

## 18. 测试规范

新增或修改核心逻辑时，应补充测试。

优先测试：

* Service 核心业务逻辑
* Mapper SQL
* 参数校验
* 异常分支
* 边界条件
* 批处理逻辑
* 金额计算逻辑

单元测试示例：

```java
@SpringBootTest
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Test
    void shouldQueryUserList() {
        UserQueryDTO queryDTO = new UserQueryDTO();
        queryDTO.setUsername("test");

        List<UserVO> result = userService.queryUsers(queryDTO);

        assertNotNull(result);
    }
}
```

---

## 19. 构建与运行

### 19.1 Maven

编译：

```bash
mvn clean compile
```

运行测试：

```bash
mvn test
```

打包：

```bash
mvn clean package -DskipTests
```

启动：

```bash
java -jar target/project.jar
```

指定环境：

```bash
java -jar target/project.jar --spring.profiles.active=dev
```

### 19.2 Gradle

编译：

```bash
./gradlew build
```

运行测试：

```bash
./gradlew test
```

启动：

```bash
./gradlew bootRun
```

---

## 20. 配置规范

配置文件应按环境区分：

```text
application.yml
application-dev.yml
application-test.yml
application-prod.yml
```

敏感配置不应写死在配置文件中，应通过环境变量、配置中心或部署平台注入。

示例：

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

---

## 21. 提交代码前检查清单

提交前请确认：

* [ ] 代码可以正常编译
* [ ] 单元测试通过
* [ ] 没有无用 import
* [ ] 没有调试代码
* [ ] 没有 `System.out.println`
* [ ] 没有硬编码敏感信息
* [ ] 没有破坏接口兼容性
* [ ] SQL 已考虑性能
* [ ] 异常已正确处理
* [ ] 日志足够定位问题
* [ ] 新增配置已有默认值或说明
* [ ] 涉及数据库变更时已提供 SQL 脚本
* [ ] 涉及接口变更时已同步更新接口文档

---

## 22. Agent 输出要求

当 AI Agent 完成任务后，请输出以下内容：

1. 修改了哪些文件
2. 每个文件的核心变更点
3. 是否新增依赖
4. 是否涉及数据库变更
5. 是否涉及接口变更
6. 是否补充测试
7. 如何验证
8. 潜在风险或注意事项

示例输出：

```text
本次修改内容：

1. 修改 UserController
   - 新增用户查询接口
   - 增加参数校验

2. 修改 UserServiceImpl
   - 新增用户查询业务逻辑
   - 增加状态枚举转换

3. 修改 UserMapper.xml
   - 新增按条件查询用户 SQL

验证方式：
- 执行 mvn test
- 启动项目后调用 POST /api/users/query

风险说明：
- 当前查询支持用户名模糊匹配，大数据量下需要关注索引和查询性能。
```

---

## 23. 禁止事项

AI Agent 不应执行以下操作：

* 不要无确认删除大量代码
* 不要无确认修改公共接口协议
* 不要无确认修改数据库表结构
* 不要无确认引入重量级框架
* 不要无确认修改认证、鉴权、权限逻辑
* 不要无确认修改生产配置
* 不要提交真实密码、Token、密钥
* 不要忽略测试失败
* 不要用伪代码替代可运行代码
* 不要为了修复编译错误而删除核心业务逻辑

---

## 24. 业务开发建议流程

处理一个新需求时，建议按以下流程执行：

1. 阅读需求描述
2. 找到相关 Controller、Service、Mapper、Entity、DTO、VO
3. 梳理现有调用链路
4. 判断是否需要新增字段、接口、表结构或配置
5. 设计最小修改方案
6. 编写代码
7. 补充测试
8. 本地编译和运行
9. 输出修改说明和验证方式

处理 Bug 时，建议按以下流程执行：

1. 复现问题
2. 定位异常日志或错误现象
3. 找到相关代码路径
4. 分析根因
5. 设计最小修复方案
6. 修复代码
7. 增加回归测试
8. 说明根因、修复点和验证方式

---

## 25. 默认偏好

除非用户另有说明，默认采用以下偏好：

* 优先保持现有项目结构
* 优先使用项目已有工具类
* 优先使用项目已有统一返回结构
* 优先使用项目已有异常体系
* 优先使用项目已有日志规范
* 优先使用项目已有 Mapper 风格
* 优先提供可直接运行的代码
* 优先给出完整修改点，而不是只给片段
* 对涉及性能、事务、并发、安全的问题，要主动提示风险

---

## 26. 适用范围

本文件适用于：

* Spring Boot 单体应用
* Spring Boot 微服务
* Java 后端管理系统
* REST API 服务
* 批处理服务
* 定时任务服务
* 数据处理服务

如果项目存在更细粒度的模块级 `AGENTS.md`，则优先遵守距离当前修改文件最近的 `AGENTS.md`。
