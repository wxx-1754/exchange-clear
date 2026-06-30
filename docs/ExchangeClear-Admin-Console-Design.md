# ExchangeClear 管理控制台详细设计方案

## 1. 文档说明

本文档指导 **ExchangeClear** 管理控制台（admin console）的设计与实现。目标是用一个统一的前端页面覆盖目前后台**结算文件主流程**的完整操作面，使运维/运营人员可以在界面上完成：

```text
任务创建 → 任务生成 → 文件元数据查询 → 文件发布 → 撤销/重发
   → 下载 Token 签发 → 文件下载 → 下载审计查询 → 下载/异常统计
```

本方案只做"管理页面"，不改动任何后端业务逻辑；所有操作均调用现有 `/api/**` 接口。

---

## 2. 设计目标

1. 覆盖后台主流程的查看与操作，不遗漏现有 `/api` 接口能力。
2. 操作有二次确认，危险操作（撤销/重发/批量发送）记录原因与操作人。
3. 状态用色块/标签直观呈现任务状态机与文件状态机。
4. 列表支持筛选 + 分页，审计页支持多条件组合查询。
5. 统一接入 Gateway，前端只面对一个域名，不直连各服务。
6. 体验与代码结构契合现有 Java 微服务体系，便于后续扩展。

### 2.1 不做范围

1. 不做用户登录鉴权与 RBAC（与后端一致，后续统一补；危险操作靠二次确认兜底）。
2. 不做实时大屏监控（统计为按需查询，非推送）。
3. 不做文件内容预览编辑（仅元数据 + 下载）。
4. 不做会员自助端（会员侧下载入口属另一条线）。

---

## 3. 后端接口面（管理台消费的接口）

管理台只消费 `/api/**` 公共接口，**不调用** `/internal/**` 服务间接口。所有响应统一为 `Result<T> = {code, message, data}`。

### 3.1 任务（settle-task-service，经 Gateway）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/tasks/create` | 创建结算任务（settleDate + fileType + version） |
| GET | `/api/tasks` | 任务列表（settleDate / status 可选筛选） |
| POST | `/api/tasks/{taskNo}/generate` | 触发单个任务生成 |
| POST | `/api/tasks/generate-batch` | 按日期+类型批量生成 |
| POST | `/api/tasks/send-batch` | 按日期+类型批量投递（可按 status） |
| POST | `/api/tasks/{taskNo}/resend` | 单任务重发 |
| POST | `/api/tasks/{taskNo}/generate-sync` | 同步生成（可选） |

任务状态机：`INIT 待投递 → SENT 已投递 → GENERATING 生成中 → GENERATED 已生成`，异常分支 `FAILED 生成失败` / `SEND_FAILED 消息发送失败`。

### 3.2 文件（settle-file-service）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/files` | 文件列表（settleDate / memberId / fileType 筛选） |
| GET | `/api/files/{fileNo}` | 文件详情 |
| GET | `/api/files/{fileNo}/checksum` | 校验和信息 |
| POST | `/api/files/publish` | 发布文件（settleDate + fileType + version + operator） |
| GET | `/api/files/publish-batches` | 发布批次列表 |
| POST | `/api/files/{fileNo}/revoke` | 撤销（operator + reason 必填） |
| POST | `/api/files/{fileNo}/reissue` | 重发（operator + reason 必填） |
| GET | `/api/files/{fileNo}/status-logs` | 状态变更日志 |

文件状态机：`GENERATED 已生成待发布 → PUBLISHED 已发布可下载 → REVOKED 已撤销 / REISSUED 已被新版本替代`，异常 `FAILED 生成失败`。

### 3.3 下载（settle-download-service）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/download/token` | 签发下载 Token（header `X-Member-Id` 必填 + body fileNo） |
| GET | `/api/download/files/{fileNo}?token=` | 流式下载文件（返回字节流） |

下载为两步：先 `POST /token` 拿到 token，再 `GET /files/{fileNo}?token=...` 触发浏览器下载。

### 3.4 审计与统计（settle-audit-service）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/audits/downloads` | 审计列表（8 筛选 + 分页） |
| GET | `/api/audits/downloads/{auditNo}` | 审计详情 |
| GET | `/api/audits/files/{fileNo}/downloads` | 某文件下载记录 |
| GET | `/api/audits/members/{memberId}/downloads` | 某会员下载记录 |
| GET | `/api/audits/stats/file-download` | 文件维度下载统计 |
| GET | `/api/audits/stats/member-download` | 会员维度下载统计 |
| GET | `/api/audits/stats/abnormal` | 异常下载统计 + TOP IP/会员 |

---

## 4. 总体架构

```text
浏览器 (Vue3 SPA)
    │  HTTP  /admin/**  与  /api/**
    ▼
exchange-gateway (Spring Cloud Gateway)
    ├── /admin/**   →  静态资源（前端构建产物，由 gateway 托管或独立 nginx）
    └── /api/**     →  按路由规则转发到各微服务
            ├── /api/tasks/**    → settle-task-service
            ├── /api/files/**    → settle-file-service
            ├── /api/download/** → settle-download-service
            └── /api/audits/**   → settle-audit-service
```

### 4.1 接入要点

1. Gateway 已有 `/api/download/**`、`/api/audits/**` 等路由；只需确认 `/api/tasks/**`、`/api/files/**` 路由存在（task/file 已在现有路由表内）。
2. 前端开发期用 Vite dev server `proxy` 把 `/api` 指向 Gateway；生产期构建产物由 Gateway 托管静态资源或独立 nginx。
3. 所有请求统一带 `X-Request-Id`（浏览器可由 axios 拦截器生成 UUID，与网关逻辑一致），便于审计链路追踪。
4. 统一响应拦截：`code !== 0` 弹 `message` 并 reject；`code === 0` 返回 `data`。

### 4.2 关键约定

- 日期格式：`settleDate` 用 ISO `yyyy-MM-dd`；审计 `startTime/endTime` 用 `yyyy-MM-dd HH:mm:ss`。前端用 Element Plus `el-date-picker`，提交前格式化。
- 分页：审计接口用 `pageNo/pageSize` 返回 `PageResult{total,records}`；task/file 列表返回裸 `List`（前端可前端分页或全量渲染）。
- 下载触发：`GET /api/download/files/{fileNo}?token=...` 是流式响应，前端用 `window.open` 或隐藏 `<a download>` 触发，避免 axios 拦截二进制。

---

## 5. 前端工程结构

```text
admin-console/
├── index.html
├── vite.config.ts            # dev proxy → gateway
├── package.json
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/index.ts        # 路由 + 菜单元数据
│   ├── layouts/AdminLayout.vue  # 侧边栏 + 顶栏 + 内容区
│   ├── api/
│   │   ├── request.ts         # axios 实例 + 拦截器（Result 解包、X-Request-Id）
│   │   ├── task.ts
│   │   ├── file.ts
│   │   ├── download.ts
│   │   └── audit.ts
│   ├── views/
│   │   ├── dashboard/         # 概览看板
│   │   ├── task/              # 任务管理（列表 + 创建 + 批量）
│   │   ├── file/              # 文件管理（列表 + 详情 + 生命周期）
│   │   ├── publish/           # 发布管理（发布 + 批次）
│   │   ├── download/          # 下载中心（签 Token + 下载）
│   │   ├── audit/             # 审计查询（列表 + 详情）
│   │   └── stats/             # 统计（文件/会员/异常）
│   ├── components/            # 通用：StatusTag、PageTable、ConfirmDialog
│   └── utils/
│       ├── format.ts          # 字节/时间/状态字典
│       └── enums.ts           # 任务/文件/下载状态字典
└── ...
```

### 5.1 侧边栏菜单（对应主流程顺序）

```text
1. 概览看板        Dashboard
2. 任务管理        Task        ← 主流程起点
3. 文件管理        File        ← 生成产物
4. 发布管理        Publish     ← 生命周期操作
5. 下载中心        Download    ← 对外下载入口
6. 审计查询        Audit       ← 可追溯
7. 下载统计        Stats       ← 可分析
```

---

## 6. 页面详细设计

### 6.1 概览看板 Dashboard `/admin/dashboard`

**目的**：一屏看清当天主流程状态。

**卡片区**（数据来源在括号）：
- 待生成任务数、生成失败任务数（`GET /api/tasks?settleDate=today&status=...`）
- 今日待发布文件数、已发布文件数（`GET /api/files?settleDate=today` 按状态聚合）
- 今日下载成功/失败次数（`GET /api/audits/stats/file-download?settleDate=today`）
- 异常下载概览（`GET /api/audits/stats/abnormal` 今日）

**快捷入口**：创建任务、批量生成、发布、异常统计。

> 说明：部分指标需前端基于列表接口聚合或多次调用；如后续后端补概览聚合接口更优，本期前端先聚合。

### 6.2 任务管理 Task `/admin/tasks`

**列表区**
- 筛选：`settleDate`（日期选择器）、`status`（下拉：INIT/SENT/GENERATING/GENERATED/FAILED/SEND_FAILED）
- 调用 `GET /api/tasks`
- 列：taskNo、settleDate、memberId、fileType、version、状态（StatusTag 配色见 7.1）、retryCount/maxRetryCount、lastSendTime、lastConsumeTime、errorMessage、操作
- 操作列：
  - `生成`（`POST /api/tasks/{taskNo}/generate`，需确认）
  - `重发`（`POST /api/tasks/{taskNo}/resend`，需确认）
  - `查看` → 抽屉展示完整字段 + errorMessage

**创建任务抽屉**
- 表单：settleDate（必填）、fileType（必填）、version（≥1，默认 1）
- 提交 `POST /api/tasks/create`，成功后刷新列表

**批量操作栏**
- 批量生成：settleDate + fileType → `POST /api/tasks/generate-batch`
- 批量投递：settleDate + fileType + status(默认 INIT) → `POST /api/tasks/send-batch`
- 结果用 `BatchGenerateResponse/BatchSendResponse` 弹窗展示成功/失败计数

### 6.3 文件管理 File `/admin/files`

**列表区**
- 筛选：settleDate、memberId、fileType
- 调用 `GET /api/files`
- 列：fileNo、settleDate、memberId、fileType、version、fileName、fileSize（格式化字节）、status（StatusTag）、downloadCount、操作
- 操作列：
  - `详情` → 抽屉：完整 FileVO + 校验和（`GET /api/files/{fileNo}/checksum`）+ 状态日志（`GET /api/files/{fileNo}/status-logs`）
  - `下载` → 跳转下载中心并预填 fileNo + memberId

**详情抽屉**
- 基本信息卡（fileNo/settleDate/memberId/fileType/version/fileName/fileSize/fileMd5/status/downloadCount）
- 校验和卡（fileMd5 等）
- 状态变更日志表格（beforeStatus→afterStatus、operationType、batchNo、operator、reason、createdAt）

### 6.4 发布管理 Publish `/admin/publish`

**发布操作卡**
- 表单：settleDate（必填）、fileType、version（≥1）、operator
- 提交 `POST /api/files/publish`，返回 PublishFileResponse 展示批次号与计数

**发布批次列表**
- 筛选：settleDate
- 调用 `GET /api/files/publish-batches`
- 列：batchNo、settleDate、fileType、version、status、totalCount/successCount/failedCount、operator、startTime/endTime、errorMessage

**文件级生命周期操作**（在文件管理详情或本页文件选择器内触发）
- `撤销`：弹框输入 operator + reason（必填）→ `POST /api/files/{fileNo}/revoke`
- `重发`：弹框输入 operator + reason（必填）→ `POST /api/files/{fileNo}/reissue`
- 两者均为危险操作，二次确认 + reason 必填校验

### 6.5 下载中心 Download `/admin/download`

**目的**：模拟会员下载链路，签发 Token 并触发下载，用于联调与排查。

**签发 Token 卡**
- 输入：memberId（必填，作为 `X-Member-Id` header）、fileNo（必填）
- 调用 `POST /api/download/token`，返回 token + 有效期
- 展示 token（可复制）

**下载触发**
- 拿到 token 后，构造 `GET /api/download/files/{fileNo}?token=...`
- 用隐藏 `<a href=... download>` 或 `window.open` 触发浏览器原生下载（流式响应，不经 axios）
- 失败时（token 失效/权限拒绝/限流）浏览器会收到错误响应体；可在新标签打开查看错误 JSON

> 注：该入口主要供运维验证下载链路与审计落库，非会员自助端。

### 6.6 审计查询 Audit `/admin/audits`

**主列表**（最复杂的查询页）
- 筛选：memberId、fileNo、settleDate、fileType、downloadStatus、clientIp、startTime、endTime、pageNo、pageSize
- 调用 `GET /api/audits/downloads`
- 列：auditNo、requestId、memberId、fileNo、fileType、settleDate、downloadStatus（StatusTag）、clientIp、costMs、createdAt、操作
- 操作：`详情` → 抽屉展示全部 20 个字段（含 failReason、userAgent、tokenDigest、start/end/bytes 等）
- 下载状态筛选用下拉，选项来自 `DownloadStatusEnum` 字典

**快捷视图**
- `按文件`：`GET /api/audits/files/{fileNo}/downloads`（从文件管理跳入时预填 fileNo）
- `按会员`：`GET /api/audits/members/{memberId}/downloads`

### 6.7 下载统计 Stats `/admin/stats`

三个 Tab：

**Tab1 文件下载统计**
- 筛选：settleDate、fileType、fileNo
- 调用 `GET /api/audits/stats/file-download`
- 表格：fileNo、successCount、failedCount、lastSuccessTime、lastClientIp

**Tab2 会员下载统计**
- 筛选：memberId、startTime、endTime
- 调用 `GET /api/audits/stats/member-download`
- 表格：memberId、totalCount、successCount、failedCount、lastDownloadTime

**Tab3 异常下载统计**
- 筛选：startTime、endTime
- 调用 `GET /api/audits/stats/abnormal`
- 顶部指标卡：tokenInvalidCount / tokenExpiredCount / deniedCount / limitedCount / fileNotPublishedCount / fileRevokedCount / fileReissuedCount
- 下方两列排行：TOP IP（topIps：name+count）、TOP 会员（topMembers）

---

## 7. 通用设计

### 7.1 状态标签配色字典

任务状态 `StatusTag`：
| 状态 | 色调 |
|---|---|
| INIT | info(灰) |
| SENT / GENERATING | warning(黄) |
| GENERATED | success(绿) |
| FAILED / SEND_FAILED | danger(红) |

文件状态 `StatusTag`：
| 状态 | 色调 |
|---|---|
| GENERATED | info |
| PUBLISHED | success |
| REVOKED / REISSUED | danger |
| FAILED | warning |

下载状态 `StatusTag`：SUCCESS 成功 / TOKEN_* / DENIED / LIMITED 危险 / FAILED 警告。

### 7.2 请求层（`api/request.ts`）

- axios 实例，baseURL `/api`（dev proxy 到 gateway）。
- 请求拦截器：注入 `X-Request-Id`（缺失则生成 `REQ`+UUID，与网关一致）。
- 响应拦截器：`code === 0` 返回 `data`；否则 `ElMessage.error(message)` 并 reject。
- 超时与统一错误兜底（网络错误、429 限流提示）。

### 7.3 危险操作与确认

- 撤销、重发、批量发送、单任务生成/重发：均 `ElMessageBox.confirm`，危险操作（撤销/重发）需 reason 文本必填。
- 操作成功后 `ElMessage.success` 并刷新当前列表。

### 7.4 字段格式化工具

- `formatBytes`：fileSize/downloadBytes → KB/MB。
- `formatDateTime` / `formatDate`：LocalDateTime/LocalDate 展示。
- 状态字典：task/file/download 三套 code→desc 映射，供标签与筛选用。

---

## 8. 与现有后端的适配点（无需改后端逻辑，仅确认）

1. **Gateway 路由**：确认 `/api/tasks/**`、`/api/files/**` 路由已存在；新增前端静态资源托管方式（gateway `spring.web.resources` 或独立 nginx）。
2. **静态资源路由**：Gateway 默认 `discovery.locator` + 显式路由，需确保 `/admin/**` 不被当作服务路由转发，而是命中静态资源。
3. **CORS**：dev 期 Vite proxy 规避跨域；生产期同源（经 gateway）无跨域问题。
4. **下载流式接口**：`GET /api/download/files/{fileNo}` 返回字节流且依赖 `X-Member-Id` header，前端用原生链接触发（无法带自定义 header，故 memberId 仅在签 token 阶段使用；下载阶段只需 token + fileNo 在 URL）。

---

## 9. 实施计划

| 阶段 | 内容 | 产出 |
|---|---|---|
| P1 | 工程脚手架 + 布局 + 路由 + request 层 + 状态字典 | 可运行的空壳，菜单可点 |
| P2 | 任务管理（列表/创建/生成/重发/批量） | 主流程起点打通 |
| P3 | 文件管理（列表/详情/校验和/状态日志） | 产物可视化 |
| P4 | 发布管理（发布/批次/撤销/重发） | 生命周期闭环 |
| P5 | 下载中心（签 Token + 触发下载） | 下载链路可联调 |
| P6 | 审计查询（主列表 + 详情 + 按文件/会员） | 可追溯 |
| P7 | 统计（文件/会员/异常） + 概览看板 | 可分析 |
| P8 | 联调、静态资源托管接入 Gateway | 可部署 |

---

## 10. 验收要点

1. 七个菜单页均可访问且数据正确加载。
2. 任务创建→生成→文件发布→撤销/重发 主链路可全程在界面操作。
3. 下载中心可签发 Token 并成功触发文件下载。
4. 审计列表多条件筛选 + 分页正确，详情字段完整。
5. 三类统计页数据与后端 `/api/audits/stats/**` 一致。
6. 危险操作均有二次确认且 reason 必填。
7. 状态标签配色与状态机一致。
8. 经 Gateway 单一入口可访问，无跨域报错。

---

## 11. 后续演进

1. 接入登录鉴权与 RBAC，危险操作按角色放行。
2. 概览看板改用后端聚合接口，减少前端多次调用。
3. 审计/统计增加图表（ECharts）：下载趋势、异常 IP 排行柱状图。
4. 任务/文件列表支持服务端分页（需后端补 PageResult）。
5. 增加操作审计：管理台自身的发布/撤销/重发操作日志可视化。
