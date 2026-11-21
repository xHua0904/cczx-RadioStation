# cczx-RadioStation

长春中学新媒体中心 · 校园广播投稿系统。

学生在线提交 1–100 字稿件；新媒体中心在后台完成审批、排期、播出与公告发布。后端 Kotlin + Spring Boot，前端原生 HTML/CSS/JavaScript，单 jar 交付，默认 SQLite，可切换 PostgreSQL。

| 入口 | 地址 |
| --- | --- |
| 投稿页 | `http://<主机>/` |
| 管理后台 | `http://<主机>/admin` |
| 制作人员名单 | `http://<主机>/credits.html` |
| 健康检查 | `http://<主机>/api/health` |

> 默认监听所有网卡的 **HTTP 80** 端口。首次部署没有任何账号，必须先执行 `--add-account` 创建账号，否则打开后台无法登录，页面也不会提示原因。

## 目录

- [1. 项目定位](#1-项目定位)
- [2. 技术栈](#2-技术栈)
- [3. 架构分层](#3-架构分层)
- [4. 领域模型](#4-领域模型)
- [5. 认证与账号](#5-认证与账号)
- [6. 功能地图](#6-功能地图)
- [7. 快速开始](#7-快速开始)
- [8. 部署指南](#8-部署指南)
- [9. 命令与查询参考](#9-命令与查询参考)
- [10. 运维与升级](#10-运维与升级)
- [11. 注意事项](#11-注意事项)
- [12. 已知限制](#12-已知限制)
- [13. 目录结构](#13-目录结构)
- [14. 验证与测试](#14-验证与测试)
- [15. 制作人员名单](#15-制作人员名单)

## 1. 项目定位

一套单一业务的闭环工具，不是通用 CMS。三类使用方，边界清晰：

| 使用方 | 入口 | 能力 | 账号 |
| --- | --- | --- | --- |
| 学生 / 投稿人 | 投稿页 | 提交稿件、查看公告栏、拿到编号 | 不需要 |
| 新媒体中心 | 管理后台 | 审批、退稿、排期、标记播出、备注、手动登记、导出、删除、发布公告、查看操作日志与开发者日志 | 需要 |
| 外部程序 | REST API | 投稿、查公告、按编号+姓名查进度 | 不需要 |

**所有后台账号权限完全相同**（统一管理员），没有角色分级。

### 1.1 业务闭环

```mermaid
flowchart LR
    subgraph SUB1["投稿入口"]
        S1["学生填写表单"] --> S2["字段与班级校验"]
        S2 --> S3["生成编号"]
        S3 --> S4["入库为待审核"]
    end
    subgraph SUB2["内容审核"]
        R1["待审核列表"] --> R2{"是否采用"}
        R2 -->|"通过"| R3["已通过"]
        R2 -->|"退回"| R4["已退稿"]
        R4 --> R5["重新提交审批"] --> R1
    end
    subgraph SUB3["排期与播出"]
        P1["已通过稿件"] --> P2["设置播出日期与时间"]
        P2 --> P3["播出日历"]
        P3 --> P4["标记已播出"]
        P4 --> P5["可切回已通过"]
    end
    subgraph SUB4["公告发布"]
        M1["撰写公告"] --> M2["选择署名为当前账号或新媒体中心"]
        M2 --> M3["投稿页公告栏"]
    end
    S4 --> R1
    R3 --> P1
```

### 1.2 关键设计取舍

| 选择 | 原因 | 代价 |
| --- | --- | --- |
| 不提供网页注册 | 账号代表管理权限，必须线下可控 | 只能用命令行加账号 |
| 单一级别管理员 | 校园场景人数少，避免权限模型复杂化 | 加账号等于给全套能力 |
| 默认 SQLite | 零外部依赖，一台机器即可运行 | 单写入者，不适合多实例并发写 |
| 原生前端 | 无构建链、无 npm、改完即生效 | 无组件化，页面逻辑靠手写 |
| 不内置 TLS | 证书由部署方掌握 | 公网必须自备反向代理 |
| HTTP 80 直出 | 校园内网访问路径最短 | 80 是特权端口，且不能当 HTTPS 用 |

## 2. 技术栈

| 层面 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin 2.1.21 | 根包 `com.cczxcsc`，入口 `com.cczxcsc.ApplicationKt` |
| 框架 | Spring Boot 3.5.7 | Web + JDBC，内嵌 Tomcat，无 JPA、无模板引擎 |
| JVM | Java 21+ | `pom.xml` 的 `java.version=21`；生产建议 21 LTS |
| 数据库 | SQLite（默认）/ PostgreSQL | SQLite 由代码内幂等迁移，PostgreSQL 由 Flyway V1–V4 |
| 数据访问 | `JdbcTemplate` + 手写 SQL | 全部参数化 |
| 导出 | Apache POI 5.4.1 | XLSX 双工作表；CSV 手写并做公式注入转义 |
| 前端 | 原生 HTML/CSS/JS | 无框架、无打包、由 jar 内静态资源托管 |
| 日志 | Logback 文件滚动 + Tomcat 访问日志 | 应用日志与访问日志分目录，见 10.4 |
| 构建 | Maven（自带 `mvnw`） | 产物 `target/cczx-RadioStation.jar` |
| 容器 | Dockerfile 多阶段 + Compose | 运行镜像 `eclipse-temurin:21-jre`，非 root 用户 `10001` |

## 3. 架构分层

代码采用**领域层 / 应用层 / 适配层**三层模型，依赖方向单向：适配层 → 应用层 → 领域层，领域层不反向依赖外层。

> 当前源码在单一包 `com.cczxcsc` 下按职责分文件，三层是**逻辑分层**而非物理分包；下表给出文件到层的映射，后续要拆包可直接按此迁移。

### 3.1 分层结构

```mermaid
flowchart TB
    subgraph IN["适配层 · 入站"]
        UI["静态前端<br/>index.html admin.html credits.html"]
        WEB["Web.kt<br/>ApiController 路由<br/>WebConfig 拦截器<br/>RequestLimits 过滤器<br/>ApiErrors 错误映射"]
    end
    subgraph APP["应用层"]
        SS["SubmissionService<br/>稿件用例编排"]
        AS["AuthService<br/>账号与会话"]
        PS["PublicationService<br/>公告发布"]
        AUS["AuditService<br/>操作记录"]
        EX["ExportService<br/>CSV 与 XLSX"]
        LS["ServerLogService<br/>应用日志尾读"]
        LI["LegacyImportService<br/>旧数据导入"]
        DM["DemoService<br/>演示数据"]
        SI["ServerInfoService<br/>运行状态"]
    end
    subgraph DOM["领域层"]
        MD["Model.kt<br/>Submission 与校验规则"]
        WK["Weeks.kt<br/>投稿周次"]
        PW["Passwords.kt<br/>密码策略与哈希"]
        SP["SubmissionPolicy.kt<br/>投稿准入策略"]
    end
    subgraph OUT["适配层 · 出站"]
        REPO["SubmissionRepository<br/>JdbcTemplate 与 SQL"]
        VAULT["PasswordVault<br/>AES-GCM 密钥文件"]
        DBI["DatabaseInitializer<br/>SQLite 迁移"]
        LOGS["logs<br/>springboot.log 与 tomcat 访问日志"]
    end
    DB[("SQLite 或 PostgreSQL")]

    UI --> WEB
    WEB --> SS
    WEB --> AS
    WEB --> PS
    WEB --> AUS
    WEB --> EX
    WEB --> SI
    WEB --> LS
    WEB --> SP
    SS --> MD
    SS --> WK
    AS --> PW
    AS --> VAULT
    AS --> REPO
    SS --> REPO
    PS --> REPO
    AUS --> REPO
    EX --> MD
    LS --> LOGS
    REPO --> DB
    DBI --> DB
```

### 3.2 文件与层映射

| 层 | 文件 | 职责 | 依赖 |
| --- | --- | --- | --- |
| 领域层 | `Model.kt` | `Submission` 结构、栏目与状态集合、班级范围、字段校验、`ApiError` | 仅 `java.time` |
| 领域层 | `Weeks.kt` | ISO 周次值对象、开放周次、周次解析 | 仅 `java.time` |
| 领域层 | `Passwords.kt` | 默认密码、密码合法性、PBKDF2 哈希与校验、token 生成与摘要 | 仅 JDK 加密库 |
| 领域层 | `SubmissionPolicy.kt` | 投稿准入：每来源每分钟额度、可信代理下的真实来源解析 | Servlet API（读取请求来源的折中） |
| 应用层 | `SubmissionService.kt` | 创建、列表、审批、排期、批量、统计、导出取数、进度查询 | 领域层 + 仓储 |
| 应用层 | `AuthService.kt` | 账号增删、登录、会话、改密、重置、密码查询、账号审计 | 领域层 + 仓储 + 密钥库 |
| 应用层 | `PublicationService.kt` | 公告发布、撤回、恢复、删除与署名 | 领域层 + 仓储 |
| 应用层 | `AuditService.kt` | 操作记录写入与多条件分页查询 | 仓储 |
| 应用层 | `ExportService.kt` | CSV / XLSX 生成 | 领域模型 |
| 应用层 | `ServerLogService.kt` | 只读应用日志末尾若干行 | 日志文件 |
| 应用层 | `LegacyImportService.kt` | 旧 JSON 导入 | 仓储 + 账号 |
| 应用层 | `DemoService.kt` | 20 条演示稿件 | 稿件用例 |
| 应用层 | `ServerInfoService.kt` | IP、JVM、内存、数据库延迟、运行时长 | 仓储 |
| 适配层 | `Web.kt` | 路由、拦截器、过滤器、错误映射、断连处理 | 应用层 |
| 适配层 | `SubmissionRepository.kt` | 稿件与计数 SQL、筛选拼装、分页 | `JdbcTemplate` |
| 适配层 | `PasswordVault.kt` | 明文密码的 AES-256-GCM 加解密与密钥文件 | 文件系统 |
| 适配层 | `DatabaseInitializer.kt` | SQLite 建表、补列、升级前备份 | JDBC |
| 适配层 | `Application.kt` | 启动入口、管理命令分发、`--help` | Spring Boot |
| 适配层 | `static/` | 投稿页、后台、名单页、样式与脚本 | 浏览器 |

依赖规则：

- 领域层不引用 Spring，也不做数据库访问，只表达"什么算合法、什么状态能怎么变"。
- 应用层负责编排与事务边界，可以调用仓储、密钥库和日志文件，但不解析 HTTP。
- 适配层负责协议转换：HTTP 入站、SQL 出站、文件密钥、日志文件。

### 3.3 一次后台请求的生命周期

```mermaid
sequenceDiagram
    autonumber
    participant B as 浏览器
    participant F as RequestLimits
    participant I as WebConfig
    participant C as ApiController
    participant A as AuthService
    participant S as 应用服务
    participant D as 领域规则
    participant R as 仓储

    B->>F: 请求携带 Bearer token
    F->>F: 写安全响应头与 no-store
    alt 公开投稿
        F->>D: SubmissionPolicy 校验来源额度
        D-->>F: 放行或 429 与 Retry-After
    end
    F->>F: 读取请求体并限制在 512KB
    F->>I: 交给拦截器
    I->>A: 用 token 摘要查会话并校验过期
    A-->>I: 当前账号
    I->>I: request 属性写入 operator
    I->>C: 放行
    C->>S: 调用用例并传入 operator
    S->>D: 校验字段与状态迁移
    S->>R: 读写数据
    R-->>S: 领域对象
    S->>S: 同事务写操作记录
    S-->>C: 结果
    C-->>B: JSON 响应
```

## 4. 领域模型

### 4.1 数据模型

```mermaid
erDiagram
    ACCOUNTS ||--o{ SESSIONS : "拥有会话"

    ACCOUNTS {
        string username PK
        string password_hash
        string password_encrypted
        int initial_password
        string created_at
    }
    SESSIONS {
        string token PK
        string username
        string created_at
        string expires_at
    }
    SUBMISSIONS {
        int id PK
        string code
        string author
        string class_name
        string contact
        string category
        string title
        string content
        string status
        string period
        string broadcast_date
        string broadcast_time
        string note
        string source
        string reviewed_by
        string scheduled_by
        string updated_by
        string created_at
        string updated_at
    }
    ANNOUNCEMENTS {
        int id PK
        string title
        string content
        string publisher
        int published
        string created_at
        string published_at
        string updated_by
        string updated_at
    }
    AUDIT_LOG {
        int id PK
        string username
        string action
        string target_type
        string target_id
        string target_label
        string created_at
    }
    COUNTERS {
        string day PK
        int n
    }
    SETTINGS {
        string key PK
        string value
    }
```

说明：

- `sessions.token` 存的是 token 的 SHA-256 摘要，不存明文。
- `audit_log.target_id` 是文本弱引用，删除稿件或公告后历史仍完整。
- `counters` 按天记录已用序号，用于生成编号。
- `submissions` 与 `announcements` 保存账号名字符串，不设外键；删账号不影响业务数据。
- 数据库仍保留 `submissions.score`、`announcements.kind/period` 等历史列，程序不再读写它们，也不做删除迁移。

### 4.2 稿件状态机

```mermaid
stateDiagram-v2
    state "待审核" as PENDING
    state "已通过" as PASSED
    state "已播出" as AIRED
    state "已退稿" as REJECTED

    [*] --> PENDING: 学生投稿或后台登记
    PENDING --> PASSED: 审核通过
    PENDING --> REJECTED: 退稿
    REJECTED --> PENDING: 重新提交审批
    PASSED --> AIRED: 标记已播出
    AIRED --> PASSED: 切换回已通过
    PASSED --> PENDING: 重新提交审批
    AIRED --> PENDING: 重新提交审批
    PASSED --> [*]: 删除稿件
```

规则：

- 只有 `已通过` / `已播出` 可以带播出日期与时间。
- 状态改回 `待审核` 或 `已退稿` 时，日期与时间自动清空。
- 设置播出时间前必须先有日期，否则 400。
- 日历里切换 `待播出 / 已播出` 只改状态，保留日期、时间与备注。
- 批量接口按来源状态校验；单篇 PATCH 允许在四种状态间切换（不满足排期条件时清空日程）。
- 评分功能已取消：PATCH 请求携带 `score` 直接返回 400，整次修改不写入。

### 4.3 编号与周次

- 编号格式 `GB + yyMMdd + 三位序号`，例：`2026-11-19` 的第 1 篇为 `GB261119001`。
- 序号来自 `counters` 的原子自增：`INSERT ... ON CONFLICT(day) DO UPDATE SET n=n+1 RETURNING n`，多实例不会撞号。
- 超过 999 篇自然进位为 4 位。
- 周次格式 `YYYY-Www`（ISO 周），公开投稿只能选择当前周及之后 7 周。
- 时间统一按 `Asia/Shanghai` 计算，与服务器时区无关。

### 4.4 字段规则

| 字段 | 规则 |
| --- | --- |
| 姓名 `author` | 必填，2–20 字 |
| 班级 `class_name` | 公开投稿必选下拉：高一 1–11 班、高二 1–9 班，共 20 个；后台手动登记可填 2–30 字班级或部门 |
| 投稿内容 `content` | 必填，1–100 字，换行统一，过滤控制字符 |
| 联系方式 `contact` | 选填，最多 50 字 |
| 栏目 `category` | 八选一：校园新闻、美文朗读、点歌祝福、失物招领、活动通知、人物访谈、英语角、其他 |
| 标题 `title` | 选填，最多 60 字；留空取正文首行前 60 字 |
| 审核备注 `note` | 最多 300 字 |
| 播出日期 | `YYYY-MM-DD` |
| 播出时间 | `HH:mm` |
| 公告标题 | 1–80 字 |
| 公告正文 | 1–2000 字 |

班级兼容规则：

- 公开投稿的班级由服务端校验，只接受上述 20 个班级。
- 旧客户端提交的 `高一一班`、`高二九班` 会转换为 `高一1班`、`高二9班` 后保存。
- 历史数据不改写，仍按原样展示与统计。

### 4.5 投稿准入策略

```mermaid
flowchart TD
    REQ["POST /api/submissions"] --> CLIENT["解析来源地址"]
    CLIENT --> TRUST{"对端是否在可信代理列表"}
    TRUST -->|"是"| FWD["从右向左取 X-Forwarded-For 可信跳"]
    TRUST -->|"否"| PEER["直接使用对端地址"]
    FWD --> LIMIT["该来源最近 60 秒计数"]
    PEER --> LIMIT
    LIMIT --> CHECK{"已达到每分钟额度"}
    CHECK -->|"是"| BLOCK["429 返回 retry_after 与 Retry-After"]
    CHECK -->|"否"| PASS["交由应用层创建稿件"]
```

- 额度默认 20 次/分钟/来源，`SUBMISSIONS_PER_MINUTE` 调整，必须大于 0。
- 无效投稿也计入额度；只作用于公开投稿接口，后台手动登记不占用。
- `TRUSTED_PROXY_CIDRS` 为空时，所有请求按直连对端地址计数。

### 4.6 公告模型

公告只有一种类型，固定为"公告"：

- 提交字段只有 `title`、`content`、`publish_as`，不再提交类型与期次。
- `publish_as` 支持 `current_account`（默认）与 `media_center`；其他值返回 400。
- 使用机构署名不需要存在名为"新媒体中心"的账号。
- 公开与后台列表都不再返回 `kind`、`period`；历史投稿计划内容继续按公告展示。
- 操作记录始终保存实际登录账号，公开署名只是展示层选择。

## 5. 认证与账号

### 5.1 登录流程

```mermaid
sequenceDiagram
    autonumber
    participant B as 浏览器
    participant C as ApiController
    participant A as AuthService
    participant P as Passwords
    participant D as 数据库

    B->>C: POST /api/admin/login 账号与密码
    C->>A: login 密码 来源地址 账号
    A->>A: 统计该来源 10 分钟内的失败次数
    alt 失败次数达到 8 次
        A-->>B: 429 请 10 分钟后再试
    end
    A->>D: 按账号查询
    D-->>A: password_hash
    A->>P: PBKDF2-HMAC-SHA256 校验
    alt 账号不存在或密码错误
        A->>A: 记录一次失败
        A-->>B: 401 账号或密码不正确
    end
    A->>P: 生成 32 字节随机 token
    A->>D: sessions 写入摘要与 12 小时过期时间
    A->>D: 写操作记录 登录后台
    A-->>B: token expires_at role=admin
```

要点：

- 账号不存在与密码错误返回同一句 `账号或密码不正确`，不泄漏账号是否存在。
- 密码超过 64 位直接 400，不做哈希。
- 同一来源 10 分钟内失败 8 次后锁定 10 分钟（内存计数，重启清零）。
- 会话固定 12 小时，**没有空闲超时**。
- 改密、重置、删除账号都会注销该账号的全部会话。
- 登录、退出、改密都会写入操作记录。

### 5.2 密码存储

| 用途 | 算法 | 位置 |
| --- | --- | --- |
| 登录校验 | PBKDF2-HMAC-SHA256，12 万轮，16 字节随机盐 | `accounts.password_hash` |
| 本机可查询 | AES-256-GCM，12 字节随机 nonce | `accounts.password_encrypted` |
| 密钥 | 32 字节随机数 | `DATA_DIR/admin-password.key` |
| 会话令牌 | 随机 32 字节 Base64URL，库中只存 SHA-256 | `sessions.token` |

- 密码合法性：等于默认密码，或长度 6–64 位。**没有复杂度要求**。
- 删掉密钥文件不影响登录，但 `--show-password` 会失败，且不会自动重建密钥；此时用 `--reset-password` 恢复。
- 数据库与密钥同在一个数据目录，备份时要一起取走并按敏感文件保管。

### 5.3 账号生命周期

```mermaid
flowchart LR
    ADD["--add-account=账号"] --> INIT["初始密码 cczx123456"]
    INIT --> LOGIN["首次登录"]
    LOGIN --> CHANGE["后台设置里改密"]
    CHANGE --> KICK["该账号会话全部注销"]
    KICK --> LOGIN
    INIT --> FORGOT["忘记密码"]
    FORGOT --> RESET["--reset-password 恢复默认"]
    RESET --> INIT
    INIT --> DEL["--delete-account 删除"]
    DEL --> KEEP["投稿 公告 操作记录保留"]
```

- 账号名去掉首尾空白后 1–40 字符，允许中文，不允许控制字符。
- 重复添加会报错并保留原密码。
- 删除账号只删账号与会话，不删它经手的业务数据。
- 删除最后一个账号后仍可投稿，后台需重新添加账号才能登录。
- 网页与 HTTP API 都没有注册、改角色、删账号入口，只能命令行。

## 6. 功能地图

### 6.1 投稿页

- 姓名、班级下拉（20 个班级）、必填正文（1–100 字）、选填联系方式。
- 提交成功展示编号，可直接再投一篇。
- 右侧公告栏按时间展示已发布公告，显示发布者。
- 页尾"制作人员名单"跳转 `/credits.html`。

### 6.2 管理后台

| 模块 | 内容 |
| --- | --- |
| 01 控制台 | 投稿总数、本周新增、待审批、采用率、已播出、近 8 周趋势、班级排行、近期播出、服务器指标 |
| 02 稿件审批 | 待审列表、搜索、分页、全选、通过/退回、手动登记、全部审核、单篇操作日志 |
| 03 已审批稿件 | 已审批列表、已安排列表、播出日历；设置日期时间、标记已播出、移出日程、重新提交审批、备注、删除 |
| 04 公告发布 | 发布公告、选择署名、查看历史、撤回、恢复、删除 |
| 05 操作日志 | 按关键词、操作对象、起止日期查询全部历史，分页查看 |
| 06 开发者工具 | 读取应用日志末尾，显示行数 100/200/500，支持刷新与复制，按级别高亮 |

- 顶部显示当前账号，可返回投稿页、修改密码、退出。
- 列表、详情、日历显示审稿人、排期人与最近操作账号。
- 确认弹窗同样显示当前操作账号；在线投稿标注"投稿人：姓名"。

### 6.3 操作日志

- 记录范围：投稿、审核、退稿、备注、排期、移出日程、状态切换、删除稿件、公告发布/撤回/恢复/删除、登录、退出、改密，以及命令行的账号增删、查询、重置、导出稿件与旧数据导入。
- 操作人来源：后台会话取真实账号；在线投稿记为"投稿人：姓名"；命令行记为"命令行：操作系统用户"。
- 不记录密码、密钥与会话 token。
- 查询参数见 9.4，每页 20 条。
- 业务写入与记录共用事务，失败操作不留下"成功"记录。

### 6.4 开发者工具

- 读取 Spring Boot 应用日志末尾，允许 20–500 行，单次最多读取 256 KiB。
- 只读取配置指定的日志文件，不接受客户端传入路径。
- 日志按行首级别着色：TRACE 灰、DEBUG 蓝、INFO 绿、WARN 橙、ERROR 红、FATAL 粉；堆栈继承所属级别。
- 日志按纯文本插入页面，内容中的 HTML 不会执行。
- 所有后台账号都能查看，没有独立的开发者权限。

### 6.5 导出

- CSV：UTF-8 带 BOM，全字段加引号，首字符为 `= + - @` 时加 `'` 前缀防公式注入。
- XLSX：工作表"投稿明细"（冻结首行、自动筛选）+ "统计"。
- 十五列：编号、投稿时间、投稿人、班级/部门、标题、投稿内容、联系方式、状态、播出日期、播出时间、审核备注、来源、审稿人、排期人、最近操作人。
- 不再导出评分列；导出动作本身会写入操作记录。

### 6.6 制作人员名单

- 投稿页与后台页尾都有入口，指向 `/credits.html`。
- 按前端、后端、运维、指导教师四类展示；桌面双列、手机单列。
- 名单是静态页面，修改后需重新打包。

## 7. 快速开始

### 7.1 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 21 或更高（推荐 21 LTS） |
| 内存 | 512MB 起 |
| 磁盘 | 1GB 起 |
| 端口 | 80（Linux 下为特权端口，见 8.3） |
| 网络 | 首次构建需访问 Maven 仓库 |

### 7.2 构建

```powershell
# Windows
.\mvnw.cmd -B '-DskipTests' package
```

```bash
# Linux / macOS
./mvnw -B -DskipTests package
```

产物：`target/cczx-RadioStation.jar`。

### 7.3 首次创建账号

**这一步不能跳过**，否则后台无法登录。

```bash
java -jar target/cczx-RadioStation.jar --add-account="网络部"
```

输出：

```text
已添加账号：网络部；默认密码：cczx123456
```

继续添加或核对：

```bash
java -jar target/cczx-RadioStation.jar --list-accounts
java -jar target/cczx-RadioStation.jar --add-account="科创社"
```

### 7.4 启动

```bash
# 必须在项目根目录执行，data 与 logs 相对工作目录解析
java -jar target/cczx-RadioStation.jar
```

看到 `Tomcat started on port 80` 后访问：

- 投稿页 `http://127.0.0.1/`
- 后台 `http://127.0.0.1/admin`
- 健康检查 `http://127.0.0.1/api/health`

按 `Ctrl+C` 停止。

### 7.5 查看全部命令

```bash
java -jar target/cczx-RadioStation.jar --help
```

## 8. 部署指南

### 8.1 部署形态

```mermaid
flowchart LR
    U1["学生 手机或电脑"] --> RP
    U2["新媒体中心"] --> RP
    subgraph EDGE["边缘"]
        RP["可选反向代理<br/>Nginx 或 Caddy<br/>TLS 与请求体限制"]
    end
    subgraph APPZ["应用"]
        J["java -jar<br/>Spring Boot 监听 80"]
    end
    subgraph STORE["存储"]
        SQ["SQLite<br/>data/broadcast.db"]
        PG["PostgreSQL<br/>radio_submissions"]
        LG["logs<br/>应用日志与访问日志"]
    end
    RP -->|"HTTP 80"| J
    J --> SQ
    J --> PG
    J --> LG
```

应用自带 HTTP 80 监听，能够直接对外提供页面。公网需要 HTTPS 时，在应用前加反向代理并把上游指向 80。

> 80 端口通常被系统自带 Web 服务占用。部署前先确认没有其他进程监听 80，或改用 `PORT=8080` 再交给反向代理。

### 8.2 Windows 本机或内网

```powershell
$env:DATA_DIR = 'C:\radio\data'
$env:LOG_DIR = 'C:\radio\logs'
java -jar target\cczx-RadioStation.jar
```

- 同网段的手机或电脑访问 `http://<电脑局域网IP>/`。
- 首次需要在防火墙放行 TCP 80（管理员 PowerShell）：

```powershell
New-NetFirewallRule -DisplayName "cczx-RadioStation 80" `
  -Direction Inbound -Protocol TCP -LocalPort 80 -Action Allow -Profile Private
```

- 只想本机访问时加 `--server.address=127.0.0.1`；没有管理员权限时改用 `--server.port=8080`。

### 8.3 Linux 服务器

两种运行方式选一种即可：临时后台运行用 nohup（8.3.4），长期常驻用 systemd（8.3.5）。不要同时启动，否则会争抢 80 端口。

#### 8.3.1 准备目录与用户

```bash
sudo useradd --system --home /opt/cczx-RadioStation --shell /usr/sbin/nologin radio
sudo mkdir -p /opt/cczx-RadioStation/{data,target,logs}
sudo chown -R radio:radio /opt/cczx-RadioStation
sudo chmod 700 /opt/cczx-RadioStation/data
```

#### 8.3.2 上传程序

上传 jar 后设置所有者：

```bash
sudo chown radio:radio /opt/cczx-RadioStation/target/cczx-RadioStation.jar
```

#### 8.3.3 环境变量

两种方式共用同一份配置。文件 `/etc/cczx-radio.env`（权限 600）：

```dotenv
PORT=80
DATA_DIR=/opt/cczx-RadioStation/data
LOG_DIR=/opt/cczx-RadioStation/logs
SERVER_ADDRESS=127.0.0.1
SUBMISSIONS_PER_MINUTE=20
TRUSTED_PROXY_CIDRS=127.0.0.1/32
```

> `SERVER_ADDRESS=127.0.0.1` 表示只允许本机访问，适合前面有反向代理的情况。要直接对局域网提供服务时删掉这一行，让应用监听所有网卡。

> 80 是特权端口。用 root 启动最省事；用普通用户运行需要 `CAP_NET_BIND_SERVICE`，systemd 的写法见 8.3.5。

#### 8.3.4 方式一 nohup 后台运行

适合临时演示、没有 systemd 权限，或只想先跑起来的场景。nohup 让进程忽略挂断信号，退出 SSH 后继续运行。

如果程序正占着前台终端，先按 Ctrl+C 停止，确认 80 已释放再启动：

```bash
cd /opt/cczx-RadioStation
mkdir -p logs
set -a; . /etc/cczx-radio.env; set +a
nohup java -jar target/cczx-RadioStation.jar > nohup.out 2>&1 < /dev/null &
echo $!
```

| 片段 | 作用 |
| --- | --- |
| `cd /opt/cczx-RadioStation` | 固定工作目录，`data/`、`logs/` 与 `nohup.out` 都落在这里 |
| `set -a; . /etc/cczx-radio.env; set +a` | nohup 不读 systemd 的 `EnvironmentFile`，环境变量必须在当前 shell 导出 |
| `> nohup.out 2>&1` | 控制台输出写入文件；应用日志另有 `logs/springboot.log` |
| `< /dev/null` | 不占用终端输入 |
| `&` | 放到后台执行 |
| `echo $!` | 打印进程号，便于停止 |

命令立即返回提示符就表示已转入后台。此时直接 `exit` 退出 SSH，服务仍在 80 端口运行。

确认是否正常：

```bash
sleep 3
curl -fsS http://127.0.0.1/api/health
ss -lntp | grep ':80'
tail -n 100 -f logs/springboot.log
```

按 Ctrl+C 只会停止 tail，不会停止服务。

停止与重启：

```bash
pgrep -af 'cczx-RadioStation.jar'    # 先确认进程号，避免误杀
kill <PID>

# 需要重启时重新执行完整启动命令
cd /opt/cczx-RadioStation
set -a; . /etc/cczx-radio.env; set +a
nohup java -jar target/cczx-RadioStation.jar > nohup.out 2>&1 < /dev/null &
```

如果当前用 root 启动，之后改用 systemd（运行用户是 `radio`）前，要先把数据与日志目录属主改回来，否则新进程写不了数据库和日志：

```bash
sudo chown -R radio:radio /opt/cczx-RadioStation/data /opt/cczx-RadioStation/logs
```

nohup 的局限：

- 服务器重启后不会自动拉起。
- 进程崩溃后不会自动重启。
- `nohup.out` 与 `logs/` 会持续增长，需要定期清理或配置 logrotate。

需要开机自启与崩溃自动重启时，用下面的 systemd 方式。

#### 8.3.5 方式二 systemd 常驻（推荐）

systemd 单元 `/etc/systemd/system/cczx-radio.service`：

```ini
[Unit]
Description=cczx-RadioStation
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=radio
Group=radio
WorkingDirectory=/opt/cczx-RadioStation
EnvironmentFile=/etc/cczx-radio.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/cczx-RadioStation/target/cczx-RadioStation.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/cczx-RadioStation/data /opt/cczx-RadioStation/logs
# 允许普通用户绑定 80 端口
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now cczx-radio
sudo systemctl status cczx-radio --no-pager
```

说明：

- `AmbientCapabilities` 只在 `User=radio` 时需要；用 root 运行可删掉这两行。
- 若把 `PORT` 改成 8080，可去掉这两行，由反向代理对外提供 80/443。
- 修改单元后必须 `daemon-reload` 再重启。

### 8.4 反向代理与 HTTPS

Nginx 示例（应用已占用 80 时，应用改用 8080，Nginx 独占 80/443）：

```nginx
server {
    listen 80;
    server_name radio.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name radio.example.com;

    ssl_certificate     /etc/letsencrypt/live/radio.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/radio.example.com/privkey.pem;

    client_max_body_size 1m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
```

```bash
PORT=8080
TRUSTED_PROXY_CIDRS=127.0.0.1/32
```

Caddy 示例：

```caddyfile
radio.example.com {
    encode gzip
    request_body {
        max_size 1MB
    }
    reverse_proxy 127.0.0.1:8080 {
        header_up X-Forwarded-For {remote_host}
    }
}
```

代理相关注意：

- 应用只提供 HTTP。`https://服务器IP:80/` 一定失败，HTTPS 必须由代理终结。
- `TRUSTED_PROXY_CIDRS` 必须包含代理的来源地址，否则所有投稿共用代理这一个来源计数。
- 代理应**覆盖**而不是追加 `X-Forwarded-For`，避免客户端伪造来源。
- 原先把上游写成 `127.0.0.1:8000` 的配置，升级后要改成实际的 80 或 8080。

### 8.5 Docker Compose

Compose 提供 `postgres` 与 `app` 两个服务，`app` 属于 `full` profile；应用以非 root 用户运行，并通过 `sysctl` 放宽低端口绑定。

```bash
cp .env.example .env
```

编辑 `.env`：

```dotenv
SPRING_PROFILES_ACTIVE=postgres
DB_URL=jdbc:postgresql://localhost:5432/radio_submissions
DB_USER=radio
DB_PASSWORD=换成一个强密码
PORT=80
LOG_DIR=./logs
SUBMISSIONS_PER_MINUTE=20
TRUSTED_PROXY_CIDRS=
```

启动前先准备日志目录（容器内用户 UID 10001）：

```bash
sudo install -d -o 10001 -g 10001 ./logs
```

```bash
# 只起数据库
docker compose up -d postgres

# 数据库与应用一起
docker compose --profile full up -d --build
```

说明：

- 端口映射为 `"${PORT}:${PORT}"`，默认 80:80。
- 数据卷：`postgres-data`、`app-data`（容器内 `/app/data`，含数据库文件与密码查询密钥）。
- 日志：`./logs` 绑定到容器 `/app/logs`，包含 `springboot.log` 与 `tomcat/access.*.log`。
- 容器内执行管理命令：

```bash
docker compose exec app java -jar /app/app.jar --add-account="网络部"
docker compose exec app java -jar /app/app.jar --list-accounts
```

- 也可以用一次性容器：`docker compose run --rm app --add-account="网络部"`。
- 更新时 `docker compose down` **不要加 `-v`**，否则数据卷会被删除。

### 8.6 切换到 PostgreSQL

1. 建库建号：

```sql
CREATE USER radio WITH PASSWORD '强密码';
CREATE DATABASE radio_submissions OWNER radio;
```

2. 配置 `SPRING_PROFILES_ACTIVE=postgres` 与 `DB_URL` / `DB_USER` / `DB_PASSWORD`。
3. 首次启动自动执行 Flyway V1–V4 建表。
4. 多实例部署时，所有实例要能读到**同一份** `DATA_DIR/admin-password.key`，否则密码查询结果不一致。

### 8.7 数据与日志目录

```text
部署目录/
├── data/
│   ├── broadcast.db                SQLite 数据库
│   ├── broadcast.db-wal            预写日志
│   ├── broadcast.db-shm            共享内存
│   ├── broadcast-before-kotlin-*.db  升级前自动备份
│   └── admin-password.key          密码查询密钥
├── logs/
│   ├── springboot.log              应用日志
│   ├── springboot.YYYY-MM-DD.N.log.gz  归档
│   └── tomcat/
│       └── access.YYYY-MM-DD.log   HTTP 访问日志
└── nohup.out                       仅 nohup 方式产生
```

`DATA_DIR` 决定数据库与密钥位置，`LOG_DIR` 决定日志位置；两者都支持绝对路径。

## 9. 命令与查询参考

### 9.1 命令行

所有命令都在项目根目录执行，使用与网站相同的 `DATA_DIR` 与数据库配置。命令执行完即退出，不启动网站。

| 命令 | 作用 | 说明 |
| --- | --- | --- |
| `--help` | 打印用法 | 不启动 Spring，直接输出 |
| `--add-account=账号` | 新建账号 | 默认密码 `cczx123456`，重复添加报错 |
| `--delete-account=账号` | 删除账号 | 注销该账号会话，保留业务数据 |
| `--list-accounts` | 列出账号 | 每行 `账号<TAB>admin`，空库提示暂无账号 |
| `--show-password --account=账号` | 查询当前密码 | 必须指定账号；依赖密钥文件 |
| `--reset-password --account=账号` | 重置为默认密码 | 必须指定账号；注销会话 |
| `--demo-data` | 写入 20 条演示稿件 | 备注标记为"演示数据"，不创建账号 |
| `--import-legacy=路径` | 导入旧 JSON | 目标库必须没有投稿 |
| 无参数 | 启动网站 | 默认 80 端口 |

参数约束：

- 一次只能指定一个管理命令，混用直接报错退出。
- `--account` 只能与 `--show-password` 或 `--reset-password` 一起使用。
- 没有默认账号，所以 `--show-password` / `--reset-password` 不带 `--account` 会报错 `没有默认账号，请使用 --account=账号 指定目标账号`。
- 命令行操作会写入操作记录，操作人记为 `命令行：操作系统用户`。

示例：

```bash
# 新建两个账号
java -jar target/cczx-RadioStation.jar --add-account="网络部"
java -jar target/cczx-RadioStation.jar --add-account="科创社"

# 查看与查密码
java -jar target/cczx-RadioStation.jar --list-accounts
java -jar target/cczx-RadioStation.jar --show-password --account="网络部"

# 忘记密码
java -jar target/cczx-RadioStation.jar --reset-password --account="网络部"

# 人员离开
java -jar target/cczx-RadioStation.jar --delete-account="网络部"
```

### 9.2 环境变量

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `PORT` | `80` | HTTP 端口 |
| `DATA_DIR` | `./data` | 数据库、WAL、备份与密钥目录 |
| `LOG_DIR` | `./logs` | 应用日志与访问日志目录 |
| `SPRING_PROFILES_ACTIVE` | 空 | 设为 `postgres` 切换数据库 |
| `DB_URL` | `jdbc:sqlite:<DATA_DIR>/broadcast.db` | 连接串 |
| `DB_USER` / `DB_PASSWORD` | 空 | PostgreSQL 凭据 |
| `SUBMISSIONS_PER_MINUTE` | `20` | 单来源每分钟公开投稿额度，必须大于 0 |
| `TRUSTED_PROXY_CIDRS` | 空 | 可信代理 IP 或 CIDR，逗号分隔 |
| `SERVER_ADDRESS` | 未设置 | Spring 标准配置，设为 `127.0.0.1` 可只监听本机 |

优先级：命令行参数 > 环境变量 > `application.yml`。

> `java -jar` **不会读取 `.env`**。`.env` 只在 Docker Compose 做变量替换时生效；本机运行时请用环境变量或命令行参数。

### 9.3 API 一览

请求与响应均为 JSON，字段使用 `snake_case`。除登录与公开接口外，都需要 `Authorization: Bearer <token>`。

| 方法 | 路径 | 鉴权 | 用途 |
| --- | --- | --- | --- |
| GET | `/api/health` | 否 | 数据库连通性检查 |
| GET | `/api/meta` | 否 | 栏目、状态、班级、开放周次、总数、本周数 |
| POST | `/api/submissions` | 否 | 提交稿件 |
| GET | `/api/announcements` | 否 | 已发布公告 |
| GET | `/api/submissions/track?code=&author=` | 否 | 按编号与姓名查进度 |
| POST | `/api/submissions/track` | 否 | 同上，参数放请求体 |
| POST | `/api/admin/login` | 否 | 登录，返回 token |
| GET | `/api/admin/me` | 是 | 当前账号与角色 |
| POST | `/api/admin/logout` | 是 | 注销当前 token |
| POST | `/api/admin/password` | 是 | 修改自己的密码 |
| GET | `/api/admin/submissions` | 是 | 稿件列表 |
| POST | `/api/admin/submissions` | 是 | 手动登记 |
| PATCH | `/api/admin/submissions/{id}` | 是 | 审批、备注、日程、状态 |
| DELETE | `/api/admin/submissions/{id}` | 是 | 删除稿件 |
| GET | `/api/admin/review-summary` | 是 | 待审数量与 ID 快照 |
| POST | `/api/admin/submissions/batch` | 是 | 批量操作 |
| POST | `/api/admin/submissions/approve-all` | 是 | 全部审核 |
| GET | `/api/admin/schedule` | 是 | 已排期稿件 |
| GET | `/api/admin/announcements` | 是 | 全部公告 |
| POST | `/api/admin/announcements` | 是 | 发布公告 |
| PATCH | `/api/admin/announcements/{id}` | 是 | 撤回或恢复 |
| DELETE | `/api/admin/announcements/{id}` | 是 | 删除公告 |
| GET | `/api/admin/stats` | 是 | 投稿统计 |
| GET | `/api/admin/server` | 是 | 服务器与运行状态 |
| GET | `/api/admin/audit` | 是 | 操作记录 |
| GET | `/api/admin/logs?lines=200` | 是 | 应用日志末尾 |
| GET | `/api/admin/export?format=csv` | 是 | 导出 CSV |
| GET | `/api/admin/export?format=xlsx` | 是 | 导出 XLSX |

错误响应统一为 `{"error":"说明"}`，常见状态码：400 参数错误、401 未登录或密码错误、404 不存在、409 状态冲突、413 请求体过大、429 请求过于频繁、503 日志不可读。

### 9.4 查询参数

稿件列表与导出共用筛选：

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `phase` | `pending` / `reviewed` | 待审 / 已审 |
| `schedule` | `unscheduled` / `scheduled` | 未排期 / 已排期（含已通过、已播出） |
| `q` | 文本 | 编号、姓名、班级、标题、内容模糊匹配 |
| `status` | 四种状态 | 精确过滤 |
| `category` | 八个栏目 | 精确过滤 |
| `start` / `end` | `YYYY-MM-DD` | 按投稿日期范围 |
| `period` | `YYYY-Www` | 按周次 |
| `page` | 正整数 | 每页 15 条 |

返回结构：`items`、`total`、`page`、`pages`、`page_size`、`counts`。

操作记录查询（`/api/admin/audit`）：

| 参数 | 说明 |
| --- | --- |
| `page` | 每页 20 条 |
| `target_type` | `submission` / `announcement` / `account` / `export` / `import` |
| `target_id` | 业务主键 |
| `username` | 操作账号精确匹配 |
| `action` | 动作精确匹配，如 `审核通过` |
| `q` | 账号、动作、标题模糊匹配，最多 100 字 |
| `start_date` / `end_date` | `YYYY-MM-DD`，含结束当天 |

应用日志查询（`/api/admin/logs`）：

| 参数 | 说明 |
| --- | --- |
| `lines` | 20–500，默认 200 |

返回 `file`、`lines`、`updated_at`、`limit`；日志文件不存在时返回空列表，不可读时返回 503。

### 9.5 curl 示例

```bash
# 健康检查
curl -fsS http://127.0.0.1/api/health

# 登录并取出 token
TOKEN=$(curl -s -X POST http://127.0.0.1/api/admin/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"网络部","password":"cczx123456"}' | python -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 待审列表
curl -s "http://127.0.0.1/api/admin/submissions?phase=pending&page=1" \
  -H "Authorization: Bearer $TOKEN"

# 审核通过并排期
curl -s -X PATCH http://127.0.0.1/api/admin/submissions/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"已通过","broadcast_date":"2026-12-01","broadcast_time":"12:30"}'

# 发布公告并以新媒体中心署名
curl -s -X POST http://127.0.0.1/api/admin/announcements \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"校园公告","content":"欢迎分享校园见闻。","publish_as":"media_center"}'

# 查看应用日志末尾 200 行
curl -s "http://127.0.0.1/api/admin/logs?lines=200" \
  -H "Authorization: Bearer $TOKEN"

# 按编号与姓名查进度
curl -s "http://127.0.0.1/api/submissions/track?code=GB261119001&author=张同学"

# 学生投稿
curl -s -X POST http://127.0.0.1/api/submissions \
  -H 'Content-Type: application/json' \
  -d '{"author":"张同学","class_name":"高一1班","content":"记录校园生活，分享身边的温暖。"}'
```

## 10. 运维与升级

### 10.1 备份

需要备份的是数据目录，日志按需保留：

- SQLite：`broadcast.db`、`broadcast.db-wal`、`broadcast.db-shm`、`admin-password.key`。
- PostgreSQL：用 `pg_dump`，同时单独保存一份 `admin-password.key`。
- 配置：systemd 单元、环境变量文件、反向代理配置。
- 日志：不参与恢复，但排查故障前应先保留当前 `logs/`。

```bash
# 停服后整目录打包，最稳
sudo systemctl stop cczx-radio
sudo tar -C /opt/cczx-RadioStation/data -czf /var/backups/cczx-$(date +%F_%H%M).tar.gz .
sudo systemctl start cczx-radio
```

```bash
# PostgreSQL
pg_dump -U radio -Fc radio_submissions > /var/backups/radio-$(date +%F).dump
```

> 备份里同时包含数据库与密钥，等于包含可解密的密码。请加密保存，不要放公开目录。

### 10.2 恢复

```bash
sudo systemctl stop cczx-radio
sudo mv /opt/cczx-RadioStation/data /opt/cczx-RadioStation/data.broken-$(date +%F_%H%M)
sudo mkdir -p /opt/cczx-RadioStation/data
sudo tar -C /opt/cczx-RadioStation/data -xzf /var/backups/cczx-2026-11-20_1200.tar.gz
sudo chown -R radio:radio /opt/cczx-RadioStation/data
sudo chmod 700 /opt/cczx-RadioStation/data
sudo systemctl start cczx-radio
```

SQLite 必须整目录替换，不能只覆盖 `.db` 而留下新的 `-wal`。

### 10.3 升级流程

```mermaid
flowchart TD
    A["开发机构建新 jar"] --> B["记录 SHA256"]
    B --> C["上传为 jar.new"]
    C --> D{"SHA256 一致"}
    D -->|"否"| C
    D -->|"是"| E["暂停公网入口"]
    E --> F["停止服务"]
    F --> G["备份数据目录或数据库"]
    G --> H{"备份成功"}
    H -->|"否"| I["恢复原服务并排查"]
    H -->|"是"| J["替换 jar 并启动"]
    J --> K["检查 health 登录 投稿 审批 日志"]
    K --> L{"验收通过"}
    L -->|"是"| M["恢复公网入口"]
    L -->|"否"| N["回滚旧 jar 与备份"]
```

```bash
sudo systemctl stop cczx-radio
sudo cp -a /opt/cczx-RadioStation/target/cczx-RadioStation.jar /var/backups/cczx-jar-$(date +%F_%H%M)
sudo chown --reference=/opt/cczx-RadioStation/target/cczx-RadioStation.jar \
             /opt/cczx-RadioStation/target/cczx-RadioStation.jar.new
sudo mv -f /opt/cczx-RadioStation/target/cczx-RadioStation.jar.new \
           /opt/cczx-RadioStation/target/cczx-RadioStation.jar
sudo systemctl start cczx-radio
curl -fsS http://127.0.0.1/api/health
```

回滚只需换回旧 jar，数据保持不动；若本次升级涉及表结构且已写入新数据，回滚前先另存数据。

### 10.4 日志与健康检查

日志产生与读取：

```mermaid
flowchart LR
    APP["应用事件"] --> F1["logs/springboot.log"]
    HTTP["HTTP 访问"] --> F2["logs/tomcat/access.YYYY-MM-DD.log"]
    F1 --> R["按日期与 10MB 滚动为 .gz"]
    R --> K["保留 30 天 归档上限 1GB"]
    F1 --> API["GET /api/admin/logs"]
    API --> UI["后台 开发者工具 分级着色"]
```

| 项目 | 配置 |
| --- | --- |
| 应用日志 | `${LOG_DIR}/springboot.log` |
| 滚动规则 | 单文件 10MB 或按日期，归档名 `springboot.YYYY-MM-DD.N.log.gz` |
| 保留策略 | 30 天，归档总量上限 1GB，启动时清理过期历史 |
| 访问日志 | `${LOG_DIR}/tomcat/access.YYYY-MM-DD.log`，保留 30 天 |
| 访问日志格式 | `%t %a "%m %U %H" %s %b %D`，不含请求体、Authorization 与查询参数 |

查看方式：

```bash
# systemd 方式
sudo journalctl -u cczx-radio -f
sudo journalctl -u cczx-radio -n 100 --no-pager

# nohup 方式
tail -n 100 -f /opt/cczx-RadioStation/logs/springboot.log
tail -f /opt/cczx-RadioStation/nohup.out

# 健康检查
curl -fsS http://127.0.0.1/api/health
```

停止与重启：systemd 用 `systemctl stop|restart cczx-radio`；nohup 方式见 8.3.4。

`/api/health` 返回 200 表示数据库可读；数据库异常时返回 500。

### 10.5 常见启动错误

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| `Invalid character found in method name [0x16 0x03 ...]` | TLS 握手指到了 HTTP 端口 | 正常现象，公网探测；改用 `https://` 域名走代理 |
| `RTSP/1.0` 或陌生 HTTP 方法 | 公网扫描 | 正常现象，可在代理层封禁或限流 |
| `ClientAbortException: Broken pipe` | 客户端或代理中途断开 | 已识别为断连，不再写 JSON 响应，不会误报 500 |
| `No converter ... preset Content-Type 'image/png'` | 断连后仍尝试写错误响应 | 已修复；升级到当前版本即可 |
| `WARNING: A restricted method in java.lang.System` | JDK 24+ 的 native access 警告 | 兼容性提示，生产建议 Java 21 LTS |
| 启动后 80 端口被占用 | 系统里已有 Web 服务 | 停用冲突服务，或改 `PORT` 交给反向代理 |

## 11. 注意事项

### 11.1 上线前必做

1. **先建账号**：空库启动后没有任何账号，后台登录必然失败。
2. **立刻改默认密码**：新账号初始密码是 `cczx123456`，且系统不强制改密。
3. **确认 80 端口可用**：系统自带 Web 服务常占用 80，冲突时改 `PORT`。
4. **处理特权端口**：Linux 下普通用户需 `CAP_NET_BIND_SERVICE`，或改用高位端口 + 反向代理。
5. **清理旧配置**：曾经设置过 `PORT=8000` 的部署，要删除或改成 80，并同步防火墙与代理上游。
6. **备份数据目录**：数据库和密钥在一起，丢了密钥就查不回已改的密码。
7. **确认来源计数可用**：用反向代理时配 `TRUSTED_PROXY_CIDRS`，否则投稿限流会变成全站共享额度。

### 11.2 端口与访问

- 默认监听所有网卡的 HTTP 80，`PORT` 或 `--server.port` 可覆盖。
- 80 只提供 HTTP：`https://服务器IP:80/` 一定失败，证书与 HTTPS 由代理负责。
- 公网部署建议让应用监听 8080，由反向代理独占 80/443，这样也能避免端口冲突。
- 防火墙与云安全组只放行实际使用的端口（80 或 443），不要额外暴露数据库端口。

### 11.3 安全

| 事项 | 现状 | 建议 |
| --- | --- | --- |
| 密码强度 | 仅 6–64 位，无复杂度要求 | 用 16 位以上随机密码，不要在多个系统复用 |
| 传输加密 | 应用不带 TLS | 反向代理终结 HTTPS |
| 安全响应头 | 仅 `X-Content-Type-Options`，接口 `no-store` | 在代理层补 CSP、HSTS、X-Frame-Options |
| 登录爆破 | 同来源 10 分钟 8 次失败后锁定 | 代理层再加一层限流 |
| 会话 | 12 小时固定，无空闲超时 | 共用电脑用完主动退出 |
| 权限 | 所有后台账号都是管理员，且都能读应用日志 | 只给可信的人开号，离职立即删除 |
| 日志内容 | 不记录密码、密钥、token；访问日志不含请求体与查询参数 | 仍可能含异常堆栈与内部路径，按内部资料管理 |
| 导出与导入 | 操作会留痕，但文件本身不含防护 | 导出文件与旧数据 JSON 含联系方式或密码哈希，用完删除 |

- `data/`、`logs/` 与 `.env` 已在 `.gitignore` 中排除，不要手工提交。
- 公告的 `publisher` 可以选择显示为"新媒体中心"，但操作记录里始终是真实账号。
- CSV 导出已做公式注入转义；打开来源不明的表格仍要谨慎。

### 11.4 数据与运行

- 工作目录决定默认 `./data` 与 `./logs` 的位置，换目录等于换数据库与换日志。
- 时间统一按 `Asia/Shanghai` 记录，服务器时区不影响投稿编号与播出时间。
- SQLite 连接池固定为 1，写入串行；多实例或高并发写请切 PostgreSQL。
- 公开投稿限流是内存计数，重启清零，多实例各自计数。
- 请求体上限 512KB，超出返回 413。
- 导出与批量操作**没有条数上限**，超大范围导出会占用内存与响应时间，建议先按日期筛选。
- 静态资源打包在 jar 内，改前端必须重新构建。
- 日志目录需要写权限；Docker 挂载宿主机 `./logs` 时先 `chown 10001:10001`。

### 11.5 功能变更提示

- **评分已取消**：界面、公开进度接口、导出都不再有评分；旧请求带 `score` 会整次拒绝。
- **公告已统一**：不再有"投稿计划"类型，历史数据仍按公告展示。
- **班级已受控**：公开投稿只接受 20 个班级，旧写法会被规范化；后台手动登记不受限。
- **操作日志扩容**：登录、退出、改密、命令行操作、导出、导入都会留痕，历史无法补录。
- 旧数据库的 `role`、`score`、`kind`、`period` 列保留但不再作为业务依据。

## 12. 已知限制

| 限制 | 说明 |
| --- | --- |
| 无网页注册 | 账号只能命令行创建 |
| 无权限分级 | 所有后台账号权限相同 |
| 不强制改密 | `initial_password` 只是数据库标记，前后端都不拦截 |
| 无内置 HTTPS | 依赖反向代理 |
| 无空闲会话超时 | 固定 12 小时 |
| 无附件与富文本 | 稿件正文为纯文本，1–100 字 |
| 无自助找回密码 | 需管理员在服务器执行 `--reset-password` |
| 日志只读末尾 | 最多 500 行、256 KiB，不支持下载与全文检索 |
| 无操作记录导出 | 只能在后台分页查看 |
| 无自动更新 | 手动替换 jar |
| 进度查询字段有限 | 按编号+姓名查，不返回正文与联系方式 |

## 13. 目录结构

```text
cczx-RadioStation/
├── pom.xml                          Maven 配置
├── mvnw / mvnw.cmd / .mvn/          Maven 包装器
├── compose.yml                      PostgreSQL 与应用的容器编排
├── Dockerfile                       多阶段构建，非 root 运行
├── .env.example                     环境变量模板
├── src/main/kotlin/com/cczxcsc/
│   ├── Application.kt               启动入口与管理命令
│   ├── Web.kt                       路由、拦截器、过滤器、错误映射
│   ├── Model.kt                     领域模型与字段校验
│   ├── Weeks.kt                     投稿周次
│   ├── Passwords.kt                 密码策略与哈希
│   ├── SubmissionPolicy.kt          投稿准入策略
│   ├── SubmissionService.kt         稿件用例
│   ├── SubmissionRepository.kt      稿件 SQL
│   ├── AuthService.kt               账号与会话
│   ├── PasswordVault.kt             密码查询密钥
│   ├── PublicationService.kt        公告发布
│   ├── AuditService.kt              操作记录
│   ├── ExportService.kt             CSV 与 XLSX
│   ├── ServerInfoService.kt         运行状态
│   ├── ServerLogService.kt          应用日志尾读
│   ├── LegacyImportService.kt       旧数据导入
│   ├── DemoService.kt               演示数据
│   └── DatabaseInitializer.kt       SQLite 迁移
├── src/main/resources/
│   ├── application.yml              主配置
│   ├── application-postgres.yml     PostgreSQL 覆盖项
│   ├── db/sqlite/                   SQLite 建表脚本
│   ├── db/postgres/                 Flyway V1–V4
│   └── static/
│       ├── index.html               投稿页
│       ├── admin.html               管理后台
│       ├── credits.html             制作人员名单
│       ├── css/                     样式
│       └── js/                      投稿、后台、日志分级脚本
├── src/test/kotlin/com/cczxcsc/     单元、接口、策略、日志与迁移测试
├── tests/                           浏览器与命令行检查脚本
├── tools/                           清理、离线测试与旧数据导出
├── data/                            运行期生成，不提交
└── logs/                            运行期生成，不提交
```

## 14. 验证与测试

```bash
# Kotlin 测试
./mvnw test

# 前端与脚本检查
node --test tests/submit.test.cjs
node --test tests/log-levels.test.cjs
node tests/password-cli.cjs
node tests/browser-check.cjs
python tests/offline_checks.py
```

覆盖范围：账号增删与独立改密、无默认账号、密码查询与重置、投稿准入限流与来源隔离、班级校验与规范化、评分字段拒绝、公告署名、状态迁移与排期、CSV/XLSX 导出、操作记录筛选、日志尾读与级别识别、旧库升级与导入、SQLite 迁移幂等。

清理测试产物：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/cleanup.ps1
```

`-ResetData` 会删除 `data/`，仅在明确要清空数据时使用。

