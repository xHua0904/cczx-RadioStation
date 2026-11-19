# cczx-RadioStation 项目原理与部署手册

> 长春中学新媒体中心 · 校园广播投稿系统
>
> - 文档版本：v1.0（2026-11-19）
> - 适用程序：`cczx-RadioStation` 1.0.0（Kotlin 2.1 / Spring Boot 3.5 / JVM 21+）
> - 编写依据：仓库内 `README.md`、`PROJECT.md`、`compose.yml`、`Dockerfile`、`Caddyfile`、`.env.example`、`pom.xml` 与 `src/main` 全部源码逐文件核对。

## 阅读说明

- **第一部分 项目原理**：说明系统怎样跑起来、数据怎样流动、账号与密码怎样校验。想"看懂项目"看这一部分。
- **第二部分 部署文档**：从零安装到上线验收，覆盖 Windows 本机、内网、Linux 服务器、Docker 四种形态，并详细说明**如何添加账户**。
- **第三部分 注意事项与排错**：上线前必须逐条确认的清单、安全红线、已知限制、故障对照表。

文中标注"**实测差异**"的条目，是文档描述与当前代码行为不一致之处，已按代码实际行为书写，部署时以本文为准。

**最快上手路径（本机 5 分钟）**

```powershell
# 1. 确认 JDK ≥ 21
java -version
# 2. 构建（首次需联网）
.\mvnw.cmd -B '-DskipTests' package
# 3. 启动（必须在项目根目录）
java -jar target/cczx-RadioStation.jar
# 4. 浏览器打开 http://127.0.0.1:8000/ ，后台 http://127.0.0.1:8000/admin
#    默认账号 新媒体中心 / cczx123456 → 首次登录强制改密
# 5. 加账号（另开一个终端）
java -jar target/cczx-RadioStation.jar --add-account="网络部"
```

详细步骤见第 14 节（Windows）、第 16 节（Linux 生产）、第 17 节（Docker），账号管理见第 19 节。

---

# 第一部分 · 项目原理

## 1. 系统是什么

学生通过网页提交 1–100 字的稿件；新媒体中心（编辑/管理员）登录后台完成**审批 → 排期 → 标记播出 → 发布公告**；网页与 Android App 共用同一套 REST API。

它**不是**一个通用 CMS，而是一套单一业务的闭环工具，边界很清晰：

| 角色 | 从哪里进入 | 能做什么 | 是否需要账号 |
| --- | --- | --- | --- |
| 学生 / 投稿人 | `http://<host>:8000/` 投稿页 | 提交稿件、查看公告栏、看到自己的稿件编号 | 不需要 |
| 编辑（editor） | `/admin` | 审批、退稿、排期、标记播出、评分备注、手动登记、发布/撤回消息、查看操作记录 | 需要 |
| 管理员（admin） | `/admin` | 编辑全部能力 + 查看联系方式 + 导出 CSV/Excel + 删除稿件/消息 + 查看服务器详情 | 需要 |
| Android App | REST API | 调用 `/api/submissions` 等接口提交稿件 | 不需要（投稿接口公开） |

**业务闭环：**

```text
【学生】打开投稿页 → 填姓名/班级/内容（选填联系方式）
          │
          │ POST /api/submissions
          ▼
     系统校验 → 生成编号 GB261119001 → 状态=待审核 → 返回编号
          │
          ▼
【新媒体中心】登录 /admin
          │
          ├─ 稿件审批（待审核列表）
          │    ├─ 通过   → 已通过（记录审稿人）
          │    ├─ 退回   → 已退稿（记录审稿人）
          │    └─ 重新提交审批 → 待审核
          │
          ├─ 已审批稿件
          │    ├─ 添加到日程（日期 + 时间）→ 排期人
          │    ├─ 播出日历：待播出 ⇄ 已播出
          │    └─ 移出日程 → 回到"已审批稿件"页
          │
          └─ 消息发布（公告 / 投稿计划）→ 出现在投稿页公告栏
```

**明确的"不做"**（这些是设计选择，不是缺陷）：

- 网页没有注册入口，HTTP API 也没有注册接口；**账号只能通过命令行添加**（见 19 节）。
- 不提供自动更新、后台版本提示、`--update` / `--check-update`；升级靠停机替换 jar。
- 不提供学生自助查询进度：`/api/submissions/track` 现在固定返回 `410`（"公开稿件进度查询已关闭"）。
- 不存附件、图片、音频；稿件正文是纯文本，1–100 字。
- 不会自动改写任何 `.md` 文档（有专门的测试守护这一条）。

## 2. 技术栈与运行时

| 层面 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin 2.1.21 | 根包名 `com.cczxcsc`，入口 `com.cczxcsc.ApplicationKt` |
| 框架 | Spring Boot 3.5.7（Web + JDBC） | 内嵌 Tomcat，无 JPA、无模板引擎 |
| JVM | Java 21+ | `pom.xml` 中 `java.version=21`；21 LTS 最稳妥，更高版本同样可用 |
| 数据库 | SQLite（默认）/ PostgreSQL 17 | SQLite 由代码内幂等迁移，PostgreSQL 由 Flyway V1–V5 管理 |
| 数据访问 | `JdbcTemplate` + 手写 SQL | 无 ORM，全部参数化查询 |
| 前端 | 原生 HTML + CSS + JavaScript | 无构建步骤、无框架、无 CDN；由 Spring Boot 直接托管静态资源 |
| 导出 | Apache POI 5.4.1 | 生成 `.xlsx`（双工作表）；CSV 手写并做公式注入转义 |
| 构建 | Maven（仓库自带 `mvnw` 包装器） | 产物 `target/cczx-RadioStation.jar`（Spring Boot 可执行 jar） |
| 容器 | Dockerfile 多阶段构建 + Compose | 基础镜像 `eclipse-temurin:21-jre` |

运行形态：单个进程、单个端口（默认 8000）、无外部缓存、无消息队列、无定时任务。

## 3. 分层架构与结构图

### 3.1 分层架构图

```text
【客户端层】
  ├─ 浏览器：index.html（投稿页）、admin.html（后台单页）
  └─ Android App：Retrofit 调 REST API（JSON，字段 snake_case）
        │
        │  HTTP / HTTPS
        ▼
【接入层】生产必选
  └─ Caddy / Nginx / 云负载均衡
       · 终结 TLS、请求体限制 64KB
       · 重新生成 X-Forwarded-For / X-Forwarded-Proto / X-Forwarded-Host
        │
        │  BIND_ADDRESS:8000（默认仅 127.0.0.1）
        ▼
【表现层】src/main/resources/static/
  ├─ index.html  + js/submit.js            投稿表单、公告栏、成功编号
  ├─ admin.html  + js/admin-workspace.js   登录 / 四模块工作台 / 日历 / 对话框
  └─ css/style.css + css/workspace.css     公共样式、后台样式
        ▼
【Web 层】Web.kt
  ├─ RequestLimits（Filter）     安全响应头、CSP、HSTS、HTTPS 强制、限流、请求体上限
  ├─ ClientIdentity              可信代理 CIDR → 真实客户端 IP
  ├─ WebConfig（Interceptor）    会话校验、角色校验、首次改密闸门、写入操作人
  ├─ PageController              /admin → forward:/admin.html
  ├─ ApiController               全部 REST 路由
  └─ ApiErrors                   ApiError → {"error": ...} 统一错误体
        ▼
【业务层】
  ├─ SubmissionService    投稿创建/校验、列表筛选、审批、排期、批量、统计、导出取数
  ├─ PublicationService   公告与投稿计划的发布、撤回、恢复、删除
  ├─ AuthService          初始化、添加账号、登录、会话、改密、重置、角色
  ├─ AuditService         操作记录写入与分页查询（与业务同事务）
  ├─ ExportService        CSV / XLSX 生成
  ├─ HealthService        /api/health 数据库探活（5 秒缓存）
  ├─ ServerInfoService    IP、JVM、内存、数据库延迟、运行时长
  ├─ LegacyImportService  旧库 JSON 导入（要求目标库为空）
  ├─ DemoService          --demo-data 生成 20 条演示稿件
  ├─ PasswordVault        AES-GCM 加解密"可查询密码"（密钥独立目录）
  └─ SecurityPolicy       WindowLimiter 限流器 + ClientIdentity
        ▼
【数据访问层】
  ├─ SubmissionRepository   submissions / counters 的 SQL
  ├─ DatabaseInitializer    SQLite 幂等迁移 + VACUUM INTO 升级前备份
  └─ Flyway（仅 PostgreSQL） db/postgres/V1…V5
        ▼
【存储】
  ├─ SQLite      ./data/broadcast.db（WAL，连接池 1，busy_timeout 15s）
  ├─ PostgreSQL  radio_submissions（Hikari 连接池 10）
  └─ 密钥        ./secrets/admin-password.key（0600，AES-256-GCM 主密钥）
```

### 3.2 目录结构

```text
cczx-RadioStation/
├─ pom.xml                          Maven 配置（groupId=com.cczxcsc）
├─ mvnw / mvnw.cmd / .mvn/maven.ps1  Maven 包装器（仓库固定 .tools/repository 为本地仓库）
├─ .env.example                     环境变量模板（含 PostgreSQL 与 HTTPS 配置）
├─ compose.yml                      Docker Compose（postgres / app / caddy 三段）
├─ Dockerfile                       多阶段构建（Maven 构建 → JRE 运行，非 root 用户 10001）
├─ Caddyfile                        HTTPS 反代与请求体限制
├─ README.md / PROJECT.md           手动维护的项目说明
├─ AGENTS.md                        开发协作规则
├─ src/main/kotlin/com/cczxcsc/     后端源码（18 个 .kt 文件，见 3.3）
├─ src/main/resources/
│  ├─ application.yml               主配置（端口、数据源、会话、限流等）
│  ├─ application-postgres.yml      postgres Profile 覆盖项
│  ├─ db/sqlite/V1,V3,V4*.sql       SQLite 表结构脚本（由代码顺序执行）
│  ├─ db/postgres/V1…V5*.sql        PostgreSQL Flyway 迁移
│  └─ static/                       前端页面、样式、脚本、logo、favicon
├─ src/test/kotlin/com/cczxcsc/     单元/接口/迁移/安全/文档测试
├─ tests/                           前端、浏览器、密码 CLI、离线检查脚本
├─ tools/                           cleanup.ps1、run-local-tests.ps1、export_legacy.py
├─ data/                            正式 SQLite 数据与自动备份（不提交 Git）
├─ secrets/                         密码查询密钥（不提交 Git，0600）
├─ target/                          构建产物与测试临时目录
├─ .tools/                          Maven 与依赖缓存（离线构建用）
└─ legacy/                          旧 Python 代码归档（仅本地，不提交）
```

### 3.3 模块职责对照表

| 文件 | 职责 | 关键点 |
| --- | --- | --- |
| `Application.kt` | 启动类 + 命令行分发 | `ApplicationRunner` 按顺序执行管理命令；命令模式追加 `--spring.main.web-application-type=none` 并关闭上下文 |
| `Web.kt` | 过滤器、拦截器、全部路由、错误处理 | 安全头、限流、请求体上限、角色校验、首次改密闸门 |
| `AuthService.kt` | 账号与会话核心 | PBKDF2 校验、token 哈希入库、绝对/空闲双超时、角色、改密、重置 |
| `Passwords.kt` | 口令工具 | 默认口令常量、PBKDF2-HMAC-SHA256 60 万轮、SHA-256 token 摘要 |
| `PasswordVault.kt` | 可查询密码的加解密 | AES/GCM/NoPadding，12 字节随机 nonce，密钥独立目录 + 权限收紧 + 旧位置迁移 |
| `SecurityPolicy.kt` | 限流与真实 IP | 固定窗口计数；只有来自可信 CIDR 的对端才能采信 `X-Forwarded-*` |
| `DatabaseInitializer.kt` | SQLite 建表与升级 | 幂等；升级前 `VACUUM INTO` 生成 `broadcast-before-kotlin-*.db` |
| `SubmissionRepository.kt` | 稿件 SQL | 编号原子生成、筛选条件拼装（全部参数化）、分页 |
| `SubmissionService.kt` | 业务规则 | 校验、状态机、排期约束、批量、统计、导出取数上限 |
| `PublicationService.kt` | 公告 | 发布者取自会话，撤回/恢复/删除写审计 |
| `ExportService.kt` | 导出 | CSV 带 BOM + 公式注入防护；XLSX 双表 + 冻结首行 + 自动筛选 |
| `AuditService.kt` | 审计 | 与业务写入同事务，失败不留"成功"记录 |
| `HealthService.kt` | 健康检查 | 5 秒内共享一次 `SELECT 1`，避免打满 SQLite 单连接池 |
| `ServerInfoService.kt` | 服务器信息 | 网卡枚举优先局域网 IPv4，不依赖外部服务 |
| `LegacyImportService.kt` | 旧数据导入 | 仅允许导入空库；恢复编号计数；注销旧会话 |
| `DemoService.kt` | 演示数据 | 20 条，备注固定"演示数据" |
| `Model.kt` | 数据模型与校验 | 8 个栏目、4 种状态、字段长度规则、`Asia/Shanghai` 时区 |
| `Weeks.kt` | 投稿周次 | ISO 周键 `YYYY-Www`，开放当前周 + 之后 7 周 |

### 3.4 一次请求的完整处理链

```text
浏览器 fetch("/api/...", {headers: {Authorization: Bearer <token>}})
   │
   ▼ 内嵌 Tomcat（默认 127.0.0.1:8000；容器内 0.0.0.0:8000）
RequestLimits.doFilterInternal()
   ① 写入安全响应头：X-Content-Type-Options / X-Frame-Options=DENY /
      Referrer-Policy=no-referrer / Permissions-Policy /
      Content-Security-Policy（script-src 'self'，禁内联脚本）
   ② 拒绝带 % 或 ; 的 /api/ 路径（防路径混淆）
   ③ ClientIdentity.resolve()：校验对端是否在 TRUSTED_PROXY_CIDRS 内，
      是则从右向左解析 X-Forwarded-For，得到真实 client_ip
   ④ HTTPS 判定：request.isSecure 或 X-Forwarded-Proto=https → 加 HSTS
   ⑤ REQUIRE_HTTPS=true 且非 HTTPS 且非 /api/health → 426
   ⑥ /api/ 路径加 Cache-Control: no-store
   ⑦ 分组限流（见 5.6）；超限 → 429 + Retry-After: 60
   ⑧ 写请求（POST/PATCH/PUT）读入请求体并套上限（登录 4KB / 后台 64KB / 其他 16KB），
      超限 → 413；并发写体上限 32，占满 → 429
   │
   ▼
WebConfig 拦截器（仅 /api/admin/**，排除 /api/admin/login）
   ① auth.session(Authorization) → 查 sessions 表 + 校验过期/空闲 → 401
   ② initial_password=1 → 除 me/password/logout 外一律 403 PASSWORD_CHANGE_REQUIRED
   ③ 角色不是 admin 且访问 export/delete/deleteMessage/server → 403
   ④ 把 operator（账号名）、role 写入 request attribute
   │
   ▼
ApiController（业务方法取 operator，客户端自填的操作人字段无效）
   │
   ▼
Service（@Transactional / TransactionTemplate）
   │
   ▼
JdbcTemplate → SQLite / PostgreSQL
   │
   ▼
ApiErrors 统一异常映射：ApiError → 对应状态码；参数错误 → 400；其余 → 500
```

### 3.5 前端原理

- **无框架、无打包**：`index.html`、`admin.html` 直接引用 `js/*.js`、`css/*.css`，由 Spring Boot 静态资源处理器输出。改样式后刷新浏览器即可，不需要 npm。
- **后台是"单页 + 四个面板"**：控制台（01）、稿件审批（02）、已审批稿件（03）、消息发布（04），通过 `hidden` 切换 `section`，不重新加载页面。
- **登录态存在 `sessionStorage.gb_token`**：关闭标签页即丢失；请求统一带 `Authorization: Bearer`。因为不用 Cookie，所以服务端没有 CSRF 问题，但**必须靠 CSP 与文本渲染防 XSS**。
- **错误处理**：`401` 自动回到登录页；`code === "PASSWORD_CHANGE_REQUIRED"` 自动跳到改密页；其余弹 toast。
- **公告正文按纯文本插入**，不做 HTML 渲染。
- **日历**读取 `/api/admin/schedule` 的 `items`，按 `broadcast_date` 分组、`broadcast_time` 排序，支持跨月跨年与闰年。

## 4. 数据模型

### 4.1 实体关系图

```text
submissions（稿件，主表）
  · 主键 id（自增）；code 唯一编号
  · 内容字段：author 姓名、class_name 班级、contact 联系方式、category 栏目、
             title 标题、content 正文（1–100 字）、period 周次
  · 流程字段：status 状态、score 评分 0–100、note 审核备注、
             broadcast_date 播出日期、broadcast_time 播出时间、
             reviewed_by 审稿人、scheduled_by 排期人、updated_by 最近操作人
  · 来源字段：source（在线投稿 / 手动登记）、created_at、updated_at

counters（每日编号计数）          1 天的序号 → submissions.code
  · day（YYYY-MM-DD，主键）、n

accounts（账号）  1 ──── N  sessions（会话）
  · username（主键）、password_hash（PBKDF2）
  · password_encrypted（AES-GCM，供本机查询）
  · initial_password（0/1）、role（admin/editor）、created_at

sessions（会话）
  · token（主键 = SHA-256(token)，不存明文）
  · username（→ accounts.username）
  · created_at、expires_at（绝对过期）、last_seen_at（空闲判断）

announcements（公告 / 投稿计划）
  · id（主键）、title、content、kind（公告 | 投稿计划）、period
  · publisher（发布账号）、published（0=已撤回，1=已发布）
  · created_at、published_at、updated_by、updated_at

audit_log（操作记录，只增不删）
  · id、username（操作人）、action（动作）、created_at
  · target_type（submission / announcement）、target_id（业务主键）、target_label

settings（旧版兼容键）             schema_versions（SQLite 版本记录）
  · key / value                     · version / applied_at
```

关系说明：

- `accounts.username` ↔ `sessions.username`：一个账号多个会话，改密/重置/改角色会删除该账号全部会话。
- `audit_log.target_id` 是**弱关联**（文本），所以删除稿件或公告后历史记录仍然完整。
- `submissions` 与 `announcements` 都存"账号名字符串"，不设外键；删账号不会破坏业务数据。

### 4.2 表说明

| 表 | 用途 | 关键约束 |
| --- | --- | --- |
| `submissions` | 稿件主表 | `code` 唯一；`status ∈ {待审核,已通过,已播出,已退稿}`；`score` 0–100 或 NULL |
| `counters` | 每日编号计数器 | 主键 `day`，用 `INSERT ... ON CONFLICT ... RETURNING` 原子自增 |
| `accounts` | 后台账号 | 主键 `username`；`role ∈ {admin, editor}`；`initial_password=1` 表示下次登录必须改密 |
| `sessions` | 登录会话 | 主键是 token 的 SHA-256 摘要，**不存明文 token** |
| `audit_log` | 操作记录 | 只增不改；删除稿件后记录仍在 |
| `announcements` | 公告与投稿计划 | `published=0` 即"已撤回" |
| `settings` | 旧版残留键 | 升级时被清理（`admin_hash` 等） |
| `schema_versions` | SQLite 版本记录 | 记录 1–5，用于判断是否已升级 |

### 4.3 迁移机制

**SQLite（默认）**——没有 Flyway，迁移写在 `DatabaseInitializer` 里：

1. 检测 `submissions` 是否已存在、缺哪些列、`schema_versions` 是否已有 5。
2. 若需要升级：先用 `VACUUM INTO` 生成 `data/broadcast-before-kotlin-<时间戳>.db`（会包含 WAL 中已提交的数据），再升级。
3. 执行 `V1/V3/V4` 脚本，逐列 `ALTER TABLE ADD COLUMN`，补齐 `period`、`role`、`updated_by`、`last_seen_at` 等。
4. 结构性变更后**主动清空 sessions**（旧 token 全部失效）。
5. 全部包在一个事务里，幂等，可重复执行。

**PostgreSQL**——标准 Flyway，`spring.flyway.locations=classpath:db/postgres`，按 V1→V5 顺序执行，已在 `schema_history` 记录的不再执行。V5 会新增 `role`、`last_seen_at` 并清空会话。

> 注意：`SPRING_PROFILES_ACTIVE=postgres` 才会启用 Flyway；SQLite 下 `spring.flyway.enabled=false`。

### 4.4 稿件编号生成原理

```text
code = "GB" + yyMMdd + 三位序号
例：2026-11-19 的第 1 篇 → GB261119001
```

- 日期取 `Asia/Shanghai` 的 `created_at` 前 10 位。
- 序号来自 `counters` 表的原子自增：`INSERT ... ON CONFLICT(day) DO UPDATE SET n=n+1 RETURNING n`，**多请求、多实例都不会撞号**。
- 超过 999 篇时自然变成 4 位（`padStart(3,'0')` 只补足最小宽度）。

## 5. 认证、会话与权限

### 5.1 登录时序

```text
浏览器
  │  POST /api/admin/login  {"username": "...", "password": "..."}
  ▼
ApiController ──► AuthService
                    │
                    │  ① 四层限流：global 180/min、ip 60/min、ip+账号 12/min、账号 30/min
                    │  ② 查 accounts；用户不存在时用假哈希继续比对（防时序探测）
                    │  ③ PBKDF2 校验 → 失败则 401，并写"登录失败"审计
                    │  ④ 判断是否需要改密：initial_password=1 / 等于默认密码 / 强度不足
                    │  ⑤ 旧哈希轮数 < 60 万 → 用新轮数重算并写回（自动升级）
                    │  ⑥ 生成 32 字节随机 token（Base64URL）
                    │  ⑦ sessions 存 SHA-256(token) 与 expires_at，顺带清理过期会话
                    │  ⑧ 写"登录成功"审计
                    ▼
返回 {ok, token, expires_at, username, role, initial_password}
```

登录失败会写一条 `未登录 / 登录失败` 审计，并统一返回 `401 账号或密码不正确`（不区分"账号不存在"和"密码错误"）。

### 5.2 口令存储

| 用途 | 算法 | 位置 |
| --- | --- | --- |
| 登录校验 | PBKDF2-HMAC-SHA256，60 万轮，16 字节随机盐，256 位输出 | `accounts.password_hash`，格式 `pbkdf2_sha256$轮数$盐$摘要` |
| 本机查询 | AES-256-GCM（12 字节 nonce + 密文 + 认证标签） | `accounts.password_encrypted`；主密钥 `secrets/admin-password.key` |
| 会话 token | 32 字节 `SecureRandom` → Base64URL；库中只存 SHA-256 十六进制 | `sessions.token` |

比较全部使用 `MessageDigest.isEqual`（常量时间）。

### 5.3 会话规则

| 项目 | 默认值 | 配置项 | 行为 |
| --- | --- | --- | --- |
| 绝对有效期 | **7200 秒（2 小时）** | `app.session-max-age-seconds` | 到期即失效，重新登录 |
| 空闲超时 | **1800 秒（30 分钟）** | `app.session-idle-seconds` | 超过无操作即失效 |
| `last_seen_at` 刷新 | 最多每 60 秒写一次 | — | 降低数据库写压力 |
| 登录成功 | 顺带清理已过期会话 | — | `DELETE FROM sessions WHERE expires_at < now` |
| 改密 / 重置 / 改角色 | 注销**该账号全部会话** | — | 其他账号不受影响 |
| 退出 | 只删当前 token | — | — |

> **实测差异**：`PROJECT.md` 写"登录有效期为 12 小时"，但 `application.yml` 实际是 7200 秒（2 小时）+ 空闲 30 分钟。以配置为准；想改长可在环境变量里覆盖 Spring 配置（如 `APP_SESSION_MAX_AGE_SECONDS`），或在 `application.yml` 中调整。

### 5.4 角色权限矩阵

| 能力 | admin | editor |
| --- | --- | --- |
| 查看稿件列表 / 日历 | ✅（含联系方式） | ✅（`contact` 被置空） |
| 审批通过 / 退稿 / 重新提交 | ✅ | ✅ |
| 安排日程 / 标记已播出 / 移出日程 | ✅ | ✅ |
| 修改评分与备注 | ✅ | ✅ |
| 手动登记稿件 | ✅ | ✅ |
| 发布 / 撤回 / 恢复公告 | ✅ | ✅ |
| 查看操作记录 | ✅ | ✅ |
| 修改自己的密码 | ✅ | ✅ |
| 导出 CSV / Excel | ✅ | ❌ 403 |
| 删除稿件 | ✅ | ❌ 403 |
| 删除消息 | ✅ | ❌ 403 |
| 查看服务器详情（`/api/admin/server`） | ✅ | ❌ 403 |
| 添加账号、重置密码、改角色 | 只能命令行 | 只能命令行 |

> 编辑登录后能看到"控制台"，但"服务器状态"卡片会因 403 显示不可用，这是预期行为。

### 5.5 首次改密闸门

满足任一条件，登录返回 `initial_password: true`，前端进入强制改密页：

1. 账号的 `initial_password = 1`（新账号、被重置过的账号）；
2. 输入的密码等于默认密码 `cczx123456`；
3. 密码不满足强度策略（少于 10 位、或缺少字母/数字）。

此时除 `GET /api/admin/me`、`POST /api/admin/password`、`POST /api/admin/logout` 外，所有后台接口返回：

```json
403 {"error":"请先修改初始密码","code":"PASSWORD_CHANGE_REQUIRED"}
```

新密码规则：**10–64 位，必须同时包含字母和数字，不能等于默认密码，不能与原密码相同**。

### 5.6 限流与请求体上限

固定时间窗（60 秒）计数，分"每 IP"和"全局"两层：

| 分组 | 每 IP/分钟 | 全局/分钟 | 备注 |
| --- | --- | --- | --- |
| 登录 | 60 | 180 | 另有 ip+账号 12/分钟、账号 30/分钟 |
| 投稿 `POST /api/submissions` | 20（`SUBMISSIONS_PER_MINUTE` 可调 1–1000） | 300 | 防刷稿 |
| `/api/health` | 60 | 600 | 健康探测 |
| `/api/submissions/track` | 12 | 120 | 已固定返回 410 |
| `/api/admin/*` | 500 | 2000 | — |
| 其他 `/api/*` | 120 | 900 | — |

请求体上限：登录 4KB、后台接口 64KB、其他接口 16KB；超限 `413`。并发读体信号量 32，占满 `429`。请求头上限 16KB，Tomcat 最多 50 线程 / 200 连接 / 50 排队 / 10 秒连接超时。

### 5.7 操作审计

写入时机：登录成功、登录失败、手动登记、审核通过、退稿、重新提交审批、安排日程、移出日程、切换播出状态、修改评分或备注、删除稿件、发布/撤回/恢复/删除消息、修改密码、设置角色。

原则：

- 操作人来自**已验证会话**，客户端请求里填的操作人字段会被忽略；
- 审计与业务写在同一事务，业务失败不会留下"成功"记录；
- 记录只增不删，稿件删除后历史仍在；
- `GET /api/admin/audit?page=1` 每页 20 条，支持 `target_type`（`submission`/`announcement`）与 `target_id` 过滤。

## 6. "密码可查询"的原理

这是本项目最容易被误读的设计，单独说明。

```text
登录时（安全路径）
  用户输入密码 ──► PBKDF2(60万轮, 随机盐) ──► 与 password_hash 常量时间比对

本机查询时（运维路径）
  --show-password
        │
        ▼
  读 secrets/admin-password.key（32 字节 AES 密钥，0600）
        │
        ▼
  AES-256-GCM 解密 password_encrypted ──► 得到明文
        │
        ▼
  再用 PBKDF2 校验一遍（防止密文被篡改/密钥错配）──► 打印
```

关键点：

1. **网页和 HTTP API 永远拿不到明文**，也没有"查询密码"接口；只有本机命令行能查。
2. `secrets/` 与 `data/` 必须**分开存放、分开备份、分别加密**。只拿到数据库拿不到明文；两个都拿到才能解密。
3. 密钥文件权限自动收紧为 `0600`，所在目录 `0700`（POSIX 系统）。
4. **旧密钥迁移**：老版本把密钥放在 `data/admin-password.key`；新版启动时复制到 `secrets/`、校验一致后删除旧文件。**因此升级前必须停掉所有旧实例**，否则旧实例会发现密钥"消失"。
5. 查不出来的情况：数据库来自老版本、只有哈希没有密文，或密钥丢失。此时命令报"当前密码无法查询"，用 `--reset-password` 恢复默认值。
6. 文档（`.md`）只记录默认值 `cczx123456`，永远不随实际密码变化——查询、重置、后台改密都不会改写文档。

## 7. 稿件状态机与业务规则

### 7.1 状态机

```text
主流程
  待审核 ──通过──► 已通过 ──标记播出──► 已播出
  待审核 ──退稿──► 已退稿

可逆操作（不丢日期、时间与备注）
  已播出 ──切换回"已通过"──► 已通过        （播出日历里可来回切）
  已通过 / 已播出 / 已退稿 ──重新提交审批──► 待审核
```

- **只有"已通过 / 已播出"可以带日程**；把状态改回待审核或退稿时，日期与时间会被自动清空。
- **播出时间必须先有日期**，否则 400。
- 日历里切换"待播出 / 已播出"只改状态，**保留日期、时间和备注**。
- 评分为 0–100 整数或 `null`；备注最多 300 字。
- 单篇 `PATCH /api/admin/submissions/{id}` 允许在 4 种状态间切换（不满足日程条件时自动清空日程）；**批量接口才按 7.2 的来源状态做限制**，这个差异在前端表现为"批量按钮的可用状态"。

### 7.2 排期约束

| 操作 | 前置状态 | 结果 |
| --- | --- | --- |
| 通过（approve） | 待审核 | 已通过，记录 `reviewed_by` |
| 退稿（reject） | 待审核 | 已退稿，记录 `reviewed_by` |
| 安排日程（schedule） | 已通过 | 写入日期/时间，记录 `scheduled_by`（批量接口要求当前为"已通过"） |
| 标记已播出（played） | 已通过 | 已播出 |
| 移出日程（clear_schedule） | 已通过/已播出 | 清空日期与时间 |
| 重新提交审批（reopen） | 已通过/已播出/已退稿 | 待审核 |

批量操作上限 1000 篇；批量通过/退稿使用 `WHERE status='待审核'` 乐观锁，冲突返回 `409`，提示刷新列表。

### 7.3 "全部审核"的风险控制

1. 前端先 `GET /api/admin/review-summary` 拿到**待审数量与 ID 快照**并弹风险确认框；
2. 用户取消 → 不发送任何请求；
3. 用户确认 → `POST /api/admin/submissions/approve-all`，body 为 `{"ids":[...],"confirmed":true}`；
4. 确认期间新提交的稿件不在快照内，**仍是待审核**；
5. 快照内若已被他人审批 → `409`，需刷新后重新确认。

### 7.4 投稿字段规则

| 字段 | 规则 |
| --- | --- |
| 姓名 `author` | 必填，去首尾空白后 2–20 字 |
| 班级 `class_name` | 必填，2–30 字 |
| 投稿内容 `content` | 必填，1–100 字（`\r\n` 统一为 `\n`，过滤控制字符） |
| 联系方式 `contact` | 选填，≤50 字 |
| 栏目 `category` | 8 选 1，缺省"其他"：校园新闻、美文朗读、点歌祝福、失物招领、活动通知、人物访谈、英语角、其他 |
| 标题 `title` | 选填 ≤60 字；留空则取正文第一行前 60 字 |
| 周次 `period` | 留空取当前 ISO 周；公开投稿只允许当前周 + 之后 7 周 |
| 状态 `status` | 公开投稿**强制**"待审核"；仅后台手动登记可指定 |

## 8. 公告、统计、导出与健康检查

**公告/投稿计划**：标题 1–80 字、正文 1–2000 字、类型为"公告"或"投稿计划"；`publisher` 取自会话；撤回 = `published=0`（前台不再展示），恢复 = `published=1`。

**统计**（`/api/admin/stats`）：总数、本周、待审、采用数、已播出、采用率（保留一位小数）、按状态/栏目/班级分布、近 8 周趋势。班级榜取前 8。

**导出**：

- CSV：UTF-8 带 BOM（Excel 打开不乱码），每个单元格加引号，首字符为 `= + - @` 时前缀 `'` 防公式注入。
- XLSX：工作表"投稿明细"（冻结首行 + 自动筛选）+ "统计"。
- 16 列：编号、投稿时间、投稿人、班级/部门、标题、投稿内容、联系方式、状态、评分、播出日期、播出时间、审核备注、来源、审稿人、排期人、最近操作人。
- **单次最多导出 5000 篇**，超出返回 413，请缩小日期/筛选范围。

**健康检查**：`GET /api/health` → `200 {"ok":true,"time":...}` 或 `503 {"ok":false,...}`；数据库探活结果缓存 5 秒；这是唯一**豁免 HTTPS 强制**的接口，供本机与容器健康检查使用。

## 9. 安全设计一览

| 风险 | 措施 | 位置 |
| --- | --- | --- |
| 弱口令/默认口令 | 首次强制改密；新密码 10–64 位含字母数字；禁用默认值 | `AuthService` |
| 口令泄露 | PBKDF2 60 万轮加盐；库中无明文 | `Passwords` |
| 数据库泄露后反推密码 | 明文用 AES-GCM 另存，密钥独立目录、独立权限 | `PasswordVault` |
| 会话劫持 | 随机 32 字节 token；库中只存摘要；绝对+空闲双超时；改密即注销 | `AuthService` |
| 暴力破解 | 四层登录限流 + 失败审计 | `AuthService` |
| 刷稿/打接口 | 分接口限流 + 请求体上限 + 并发信号量 | `Web.kt` |
| XSS | 严格 CSP（无内联脚本、无外部源）、前端纯文本渲染、Jackson 转义 | `Web.kt` |
| 点击劫持 | `X-Frame-Options: DENY` + CSP `frame-ancestors 'none'` | `Web.kt` |
| 传输降级 | `REQUIRE_HTTPS=true` 时非 HTTPS 一律 426；HTTPS 时下发 HSTS | `Web.kt` |
| 伪造客户端 IP 绕过限流 | 仅信任 `TRUSTED_PROXY_CIDRS` 内的对端，并从右向左解析转发链 | `SecurityPolicy` |
| SQL 注入 | 全部参数化查询；排序列与筛选值白名单校验 | `SubmissionRepository` |
| CSV 公式注入 | 危险首字符加 `'` 前缀 | `ExportService` |
| 越权 | 角色拦截器 + 编辑者联系方式置空 | `WebConfig` |
| 路径混淆 | `/api/` 路径含 `%` 或 `;` 直接 400 | `Web.kt` |
| 大包攻击 | 代理层 64KB + 应用层 4/16/64KB + 16KB 请求头 | `Caddyfile` / `Web.kt` |
| 操作留痕 | 审计与业务同事务，操作人不可伪造 | `AuditService` |

## 10. 命令行管理命令的原理

所有管理命令都复用同一个 Spring 上下文，只是把 Web 关掉：

```text
java -jar target/cczx-RadioStation.jar --add-account="网络部"
        │
        ▼ main() 检测到 --add-account
① 先创建 DATA_DIR（默认 ./data），避免 SQLite 首次连接失败
② 追加 --spring.main.web-application-type=none（不监听端口）
   --show-password 额外追加 --spring.main.banner-mode=off 和 --logging.level.root=ERROR
③ 启动上下文 → DatabaseInitializer 建表/迁移 → ApplicationRunner 执行命令
④ 打印结果 → context.close() → 进程退出（退出码 0）
```

参数校验规则（写错会直接报错退出）：

- **一次只能指定一个管理命令**：`import-legacy` / `reset-password` / `show-password` / `demo-data` / `add-account` / `list-accounts` / `set-role`。
- `--account` 只能与 `--show-password` 或 `--reset-password` 同用。
- `--role` 只能与 `--add-account` 或 `--set-role` 同用；`--set-role` 必须带 `--role`。
- 所有带值参数不允许为空。
- 输出统一为 UTF-8（支持中文账号与密码）。

## 11. 配置项全表

### 11.1 环境变量（推荐方式）

| 变量 | 默认值 | 作用 | 注意 |
| --- | --- | --- | --- |
| `PORT` | `8000` | HTTP 监听端口 | 改端口后反代与健康检查同步改 |
| `BIND_ADDRESS` | `127.0.0.1` | 监听地址 | **局域网/手机访问必须设为 `0.0.0.0`**；Docker 镜像已内置 `0.0.0.0` |
| `DATA_DIR` | `./data` | SQLite 数据与自动备份目录 | 换目录等于换数据库 |
| `SECRETS_DIR` | `./secrets` | 密码查询密钥目录 | 多实例（PostgreSQL）必须共享同一份 |
| `SPRING_PROFILES_ACTIVE` | 空 | 设为 `postgres` 切库 | 切库同时要配下面三项 |
| `DB_URL` | `jdbc:sqlite:${DATA_DIR}/broadcast.db` | JDBC 连接串 | PostgreSQL 示例见下 |
| `DB_USER` / `DB_PASSWORD` | 空 | PostgreSQL 账号密码 | 通过环境变量或凭据文件传，别写进命令历史 |
| `BROADCAST_ADMIN_PASSWORD` | `cczx123456` | **仅首次初始化**默认账号的口令 | 必须满足 ≥10 位含字母数字，否则启动直接失败 |
| `REQUIRE_HTTPS` | `false` | 强制 HTTPS | 代理未透传 `X-Forwarded-Proto` 时会 426 |
| `TRUSTED_PROXY_CIDRS` | 空 | 可信代理 CIDR，逗号分隔 | 只有这些来源的 `X-Forwarded-*` 才被采信 |
| `SUBMISSIONS_PER_MINUTE` | `20` | 单 IP 每分钟投稿上限 | 有效范围 1–1000 |

### 11.2 配置文件里的非环境变量项

`src/main/resources/application.yml` 中还有：连接池大小（SQLite 1 / PG 10）、`busy_timeout=15000`、Tomcat 线程与连接数、会话 7200/1800 秒、Jackson `SNAKE_CASE`、Tomcat `forward-headers-strategy=none`（刻意不用 Spring 内置转发头处理，改由 `ClientIdentity` 自己判断）。

### 11.3 配置优先级

命令行参数 > 环境变量 > `application-{profile}.yml` > `application.yml`。

> **重要**：直接 `java -jar` **不会自动读取 `.env`**。`.env` 只有 README 里那段 PowerShell 启动脚本会解析成进程环境变量；Docker Compose 会读取 `.env` 用于变量替换（但容器内拿到的仍是 compose 传进去的 `environment`）。

---

# 第二部分 · 部署文档

## 12. 选择部署形态

```text
① 本机 / 内网试用（Windows）
   浏览器/手机 ──HTTP:8000──► java -jar ──► SQLite ./data

② 生产（Linux + systemd + 反向代理）★推荐
   学生电脑/手机 ──HTTPS:443──► Caddy/Nginx ──HTTP:127.0.0.1:8000──► java -jar
        Caddy/Nginx 侧：自动证书、请求体 64KB、重写转发头
        java 侧：data/（业务数据） + secrets/（密码查询密钥）

③ 生产（Docker Compose，含 TLS）
   公网 ──443──► caddy 容器(172.30.16.2) ──8000──► app 容器 ──5432──► postgres 容器
        app 容器挂载卷：app-data、app-secrets

④ 生产（Docker Compose，仅数据库）
   本机 java -jar ──5432──► postgres 容器
```

| 场景 | 推荐形态 | 理由 |
| --- | --- | --- |
| 老师本机演示、临时试用 | ① | 一条命令，零依赖 |
| 校园内网长期使用（不接公网） | ② 去掉反代，`BIND_ADDRESS=0.0.0.0` | 简单、可控 |
| 公网域名 + HTTPS | ② 或 ③ | 必须有可信 TLS 终结 |
| 需要多实例/更高并发 | ②/③ + PostgreSQL | SQLite 单写入者 |

## 13. 环境要求

| 项目 | 最低 | 建议 |
| --- | --- | --- |
| JDK | 21 | 21 LTS（Temurin） |
| 内存 | 512 MB | 1 GB 以上（`-Xmx512m` 够用） |
| 磁盘 | 1 GB | 5 GB（含备份与日志） |
| 数据库 | SQLite（内置） | 公网/多实例用 PostgreSQL 17 |
| 网络 | 出网 443（首次构建拉依赖） | 生产可离线构建后只传 jar |
| 端口 | 8000（应用） | 80/443（反代）、5432（仅本机） |

检查 Java：

```powershell
java -version          # Windows
```

```bash
java -version          # Linux
```

## 14. 部署方式 A：Windows 本机 / 内网

### 14.1 首次构建

在项目根目录（必须含 `pom.xml`）打开 PowerShell：

```powershell
.\mvnw.cmd -B '-DskipTests' package
```

- 首次会下载 Maven 与依赖（仓库自带 `.tools/`，已缓存时可加 `-o` 离线构建）。
- 产物：`target/cczx-RadioStation.jar`（约 60 MB，Spring Boot 可执行 jar）。

### 14.2 启动

```powershell
java -jar target/cczx-RadioStation.jar
```

- **必须从项目根目录执行**，否则会连到 `./data` 下的另一个（空）数据库。
- 首次启动会自动建库、建默认账号 `新媒体中心`，初始密码 `cczx123456`。
- 按 `Ctrl+C` 停止。

访问：

| 页面 | 地址 |
| --- | --- |
| 投稿页 | `http://127.0.0.1:8000/` |
| 管理后台 | `http://127.0.0.1:8000/admin` |

### 14.3 让手机/同网段电脑访问（关键）

默认只监听回环地址，**手机一定连不上**。需要显式改监听地址：

```powershell
$env:BIND_ADDRESS = '0.0.0.0'
java -jar target/cczx-RadioStation.jar
```

再放行防火墙（管理员 PowerShell）：

```powershell
New-NetFirewallRule -DisplayName "cczx-RadioStation 8000" `
  -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

查看本机局域网 IPv4：

```powershell
ipconfig | Select-String 'IPv4'
```

手机与电脑连同一个 Wi-Fi、同一网段，访问 `http://192.168.x.x:8000/`（冒号是半角）。

排查看这里：第 30 节的故障表。

### 14.4 开机自启（可选）

Windows 没有官方服务安装脚本，任选其一：

- **任务计划程序**：触发器"登录时/开机时"，操作 `java.exe`，参数 `-jar C:\cczx\cczx-RadioStation.jar`，起始于 `C:\cczx\cczx-RadioStation`。
- **NSSM / WinSW**：包装成 Windows 服务，注意工作目录与 `DATA_DIR` 要与手工运行一致。

两种方式都必须显式配置 `BIND_ADDRESS`、`DATA_DIR`、`SECRETS_DIR` 环境变量。

## 15. 部署方式 B：IDEA 开发调试

1. `File → Open` 选择 `pom.xml`，作为 Maven 项目导入。
2. `Project Structure → Project`：SDK 选 JDK 21+，语言级别 21；Maven Runner 的 JRE 用同一 SDK。
3. Maven 面板 `Reload All Maven Projects`，执行 `-DskipTests package`。
4. 调试：运行 `com.cczxcsc.ApplicationKt`，**运行配置的工作目录设为项目根目录**。
5. 离线构建：IDEA Terminal 执行 `.\mvnw.cmd -o -B '-DskipTests' package`；若用 IDEA Maven 面板离线构建，把 Local repository 指向项目内 `.tools/repository`。

## 16. 部署方式 C：Linux 服务器（systemd + 反向代理，生产推荐）

以下示例假设：部署目录 `/opt/cczx-RadioStation`，运行用户 `radio`，服务名 `cczx-radio.service`，域名 `radio.example.com`。请按实际替换。

### 16.1 准备用户与目录

```bash
sudo useradd --system --home /opt/cczx-RadioStation --shell /usr/sbin/nologin radio
sudo mkdir -p /opt/cczx-RadioStation/{target,data,secrets}
sudo chown -R radio:radio /opt/cczx-RadioStation
sudo chmod 700 /opt/cczx-RadioStation/secrets
```

### 16.2 上传程序

在开发机构建后，只上传 jar（**不要**上传开发机的 `data/`、`secrets/`、`.env`）：

```bash
# 开发机
sha256sum target/cczx-RadioStation.jar
scp target/cczx-RadioStation.jar user@server:/opt/cczx-RadioStation/target/
```

服务器上核对 SHA256 一致后，设置所有者：

```bash
sudo chown radio:radio /opt/cczx-RadioStation/target/cczx-RadioStation.jar
sudo chmod 640 /opt/cczx-RadioStation/target/cczx-RadioStation.jar
```

### 16.3 环境变量文件

```bash
sudo tee /etc/cczx-radio.env >/dev/null <<'EOF'
PORT=8000
BIND_ADDRESS=127.0.0.1
DATA_DIR=/opt/cczx-RadioStation/data
SECRETS_DIR=/opt/cczx-RadioStation/secrets
REQUIRE_HTTPS=true
# 反向代理与应用同机时填 127.0.0.1/32；跨机填代理服务器 IP/CIDR
TRUSTED_PROXY_CIDRS=127.0.0.1/32
# 首次初始化才生效，留空则用默认密码并要求首登改密
BROADCAST_ADMIN_PASSWORD=
EOF
sudo chmod 600 /etc/cczx-radio.env
sudo chown root:root /etc/cczx-radio.env
```

使用 PostgreSQL 时追加：

```bash
SPRING_PROFILES_ACTIVE=postgres
DB_URL=jdbc:postgresql://127.0.0.1:5432/radio_submissions
DB_USER=radio
DB_PASSWORD=你的强密码
```

### 16.4 systemd 单元

```ini
# /etc/systemd/system/cczx-radio.service
[Unit]
Description=cczx-RadioStation（校园广播投稿系统）
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
ReadWritePaths=/opt/cczx-RadioStation/data /opt/cczx-RadioStation/secrets

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now cczx-radio.service
sudo systemctl status cczx-radio.service --no-pager
sudo journalctl -u cczx-radio.service -n 100 --no-pager
```

> `WorkingDirectory` 必须正确：SQLite 用相对路径，工作目录错了就是另一个空库。

### 16.5 反向代理

**方案一：Caddy（仓库自带 `Caddyfile`，自动签证书）**

```bash
sudo cp Caddyfile /etc/caddy/Caddyfile
# 将 {$BROADCAST_DOMAIN} 换成真实域名，或设置环境变量 BROADCAST_DOMAIN
sudo systemctl reload caddy
```

Caddy 已做三件关键事：限制请求体 64KB、**重新生成** `X-Forwarded-For/Proto/Host`、删除客户端自带的 `Forwarded` 头。

**方案二：Nginx**

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

    client_max_body_size 64k;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        # 覆盖而不是追加，避免客户端伪造左端地址
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_read_timeout 60s;
    }
}
```

要点：

- `X-Forwarded-Proto https` 是应用判断"这条请求是 HTTPS"的唯一依据；漏了就会一直 `426`。
- `TRUSTED_PROXY_CIDRS` 必须覆盖代理的真实来源地址，否则真实 IP 会退化成代理地址（所有人都共用一个限流桶）。
- **不要把 8000 直接暴露到公网**；`BIND_ADDRESS=127.0.0.1` 就是为了强制走代理。

**FRP / 内网穿透**：纯 TCP 隧道不产生可信的 HTTP 转发头，不能当作 HTTPS 代理。正确做法是让 TLS 在应用同机的 Caddy/Nginx 终结，FRP 只转发到本机 443；或将 `REQUIRE_HTTPS` 置 `false`（仅限内网）。

### 16.6 防火墙

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
# 不要放行 8000
sudo ufw status
```

### 16.7 验证

```bash
curl -fsS http://127.0.0.1:8000/api/health
curl -fsS https://radio.example.com/api/health
```

## 17. 部署方式 D：Docker Compose

### 17.1 组成

| 服务 | 镜像 | Profile | 端口 | 卷 |
| --- | --- | --- | --- | --- |
| `postgres` | `postgres:17-alpine` | 始终 | `127.0.0.1:5432` | `postgres-data` |
| `app` | 本地 `Dockerfile` 构建 | `full` / `https` | `127.0.0.1:8000` | `app-data`、`app-secrets` |
| `caddy` | `caddy:2-alpine` | `https` | `80/443/443udp` | `caddy-data`、`caddy-config` |

网络固定 `172.30.16.0/24`，caddy 固定 `172.30.16.2`，与 compose 里 `TRUSTED_PROXY_CIDRS` 对应。

### 17.2 步骤

```powershell
Copy-Item .env.example .env
```

编辑 `.env`：

```dotenv
SPRING_PROFILES_ACTIVE=postgres
DB_URL=jdbc:postgresql://localhost:5432/radio_submissions
DB_USER=radio
DB_PASSWORD=换成强密码
PORT=8000
BROADCAST_DOMAIN=radio.example.com
REQUIRE_HTTPS=true
TRUSTED_PROXY_CIDRS=172.30.16.2/32
BROADCAST_ADMIN_PASSWORD=
```

启动：

```powershell
# 完整 HTTPS 栈：postgres + app + caddy
docker compose --profile https up -d --build

# 仅数据库（应用在本机用 java -jar 跑）
docker compose up -d postgres

# postgres + app，不含 TLS
docker compose --profile full up -d --build
```

查看状态与日志：

```powershell
docker compose ps
docker compose logs -f app
```

### 17.3 容器内管理命令

```powershell
# 查询默认管理员密码
docker compose exec app java -jar /app/app.jar --show-password

# 添加账号
docker compose exec app java -jar /app/app.jar --add-account="网络部"

# 列出账号
docker compose exec app java -jar /app/app.jar --list-accounts

# 或用一次性容器（推荐，不影响正在运行的应用）
docker compose run --rm app --add-account="网络部"
```

容器内已内置 `DATA_DIR=/app/data`、`SECRETS_DIR=/app/secrets`、`BIND_ADDRESS=0.0.0.0`，且 `DB_*` 由 compose 注入，因此命令会自动连同一个数据库。

### 17.4 数据卷与备份

```powershell
docker compose exec postgres pg_dump -U radio radio_submissions > backup.sql
docker run --rm -v cczx-radiostation_app-data:/data -v ${PWD}:/backup alpine tar czf /backup/app-data.tar.gz -C /data .
docker run --rm -v cczx-radiostation_app-secrets:/secrets -v ${PWD}:/backup alpine tar czf /backup/app-secrets.tar.gz -C /secrets .
```

> 卷名前缀是 Compose 项目名（默认取目录名并转为小写），所以目录 `cczx-RadioStation` 对应 `cczx-radiostation_*`。用 `docker volume ls` 确认真实卷名后再执行。

### 17.5 Docker 的两个坑

1. **`--profile full` 会 426**：compose 里 `REQUIRE_HTTPS: ${REQUIRE_HTTPS:-true}`，而 full 模式没有 caddy 提供 `X-Forwarded-Proto`，浏览器直接访问 `http://127.0.0.1:8000` 会收到 `426 公网模式仅允许 HTTPS`。本机联调请在 `.env` 里显式设 `REQUIRE_HTTPS=false`。
2. **`docker compose down -v` 会删卷**：升级/切换 profile 时只能用 `down`，**绝不能加 `-v`**，否则 `postgres-data`、`app-data`、`app-secrets` 全部消失。

## 18. 数据库选择与切换

### 18.1 SQLite（默认，零配置）

- 文件：`${DATA_DIR}/broadcast.db`（默认 `./data/broadcast.db`），另有 `-wal`、`-shm`。
- 连接池固定 **1**，`PRAGMA busy_timeout=15000`，启动时 `journal_mode=WAL`。
- 适合：单机、单实例、日均几百到几千投稿。
- 不适合：多实例同时写、需要高并发写入。

### 18.2 PostgreSQL

1. 准备库与账号：

```sql
CREATE USER radio WITH PASSWORD '强密码';
CREATE DATABASE radio_submissions OWNER radio;
```

2. 配置环境变量（`SPRING_PROFILES_ACTIVE=postgres` + `DB_URL/DB_USER/DB_PASSWORD`）。
3. 首次启动自动执行 Flyway V1–V5 建表。
4. 多实例部署时，**所有实例必须共享同一个 `secrets/admin-password.key`**，否则 `--show-password` 与改密记录的密文互不兼容。

### 18.3 从旧 SQLite 迁到 PostgreSQL

```powershell
# 只读导出旧库（包含密码哈希，导入后请删除导出文件）
python tools/export_legacy.py
# 以及 --database / --output 参数可自定义

# .env 已切到 PostgreSQL，且目标库必须为空
java -jar target/cczx-RadioStation.jar --import-legacy=data/legacy-export.json
```

导入行为：保留编号、正文、审核信息、评分、密码哈希；恢复每日编号计数；注销旧会话；导入完成后自动初始化默认账号。

> 目标库非空会直接失败（"仅允许导入到没有投稿数据的数据库"），这是保护措施，不是 bug。

## 19. 账号管理（重点：如何添加账户）

### 19.1 规则总览

| 项目 | 规则 |
| --- | --- |
| 添加方式 | **只能命令行** `--add-account=账号`；网页和 HTTP API 都没有注册入口 |
| 账号名 | 去首尾空白后 1–40 字，允许中文，**不允许控制字符** |
| 默认密码 | 每个新账号统一 `cczx123456` |
| 首次登录 | 强制改密（10–64 位，含字母和数字） |
| 重复添加 | 报错并**保留原密码**，不会覆盖 |
| 默认角色 | `editor`（编辑） |
| 管理员 | `--role=admin` 指定，或用 `--set-role` 后改 |
| 默认管理员 | `新媒体中心`，**不可被降级** |
| 删除账号 | **当前版本没有删除命令**（见 19.7） |
| 账号数量 | 无硬性上限 |

### 19.2 添加账号（各运行环境）

**前提**：命令必须使用**与网站相同的数据库配置**（`DATA_DIR` / `SECRETS_DIR` / `DB_*` / `SPRING_PROFILES_ACTIVE`），并在项目根目录执行。否则会给"另一个数据库"加账号，表现为后台登不上。

**① Windows 本机（SQLite 默认）**

```powershell
cd C:\Users\xHua\Desktop\Project\cczx-RadioStation
java -jar target/cczx-RadioStation.jar --add-account="网络部"
```

成功输出：

```text
已添加账号：网络部；角色：editor；默认密码：cczx123456，首次登录必须改密。
```

**② 添加管理员账号**

```powershell
java -jar target/cczx-RadioStation.jar --add-account="站长" --role=admin
```

**③ Linux / systemd 部署**

```bash
sudo -u radio bash -c '
  set -a; . /etc/cczx-radio.env; set +a
  cd /opt/cczx-RadioStation
  java -jar target/cczx-RadioStation.jar --add-account="网络部"
'
```

如果环境变量值里没有空格，也可以写成：

```bash
sudo -u radio env $(grep -v '^#' /etc/cczx-radio.env | xargs) \
  java -jar target/cczx-RadioStation.jar --add-account="网络部"
```

**④ Docker Compose**

```powershell
docker compose exec app java -jar /app/app.jar --add-account="网络部"
# 或
docker compose run --rm app --add-account="网络部"
```

**⑤ 自定义数据目录**

```powershell
$env:DATA_DIR = 'D:\radio\data'
$env:SECRETS_DIR = 'D:\radio\secrets'
java -jar target/cczx-RadioStation.jar --add-account="网络部"
```

### 19.3 查看与调整角色

```powershell
# 列出所有账号及其角色
java -jar target/cczx-RadioStation.jar --list-accounts
# 输出示例（制表符分隔）：
# 新媒体中心	admin
# 网络部	editor

# 把已有账号提升为管理员（会注销该账号的全部会话）
java -jar target/cczx-RadioStation.jar --set-role="网络部" --role=admin

# 降回编辑
java -jar target/cczx-RadioStation.jar --set-role="网络部" --role=editor
```

限制：`新媒体中心` 必须保持 `admin`，尝试降级会报错。

### 19.4 查询与重置密码

```powershell
# 查询默认管理员当前密码
java -jar target/cczx-RadioStation.jar --show-password

# 查询指定账号
java -jar target/cczx-RadioStation.jar --show-password --account="网络部"

# 重置指定账号为 cczx123456（注销该账号全部会话，下次登录强制改密）
java -jar target/cczx-RadioStation.jar --reset-password --account="网络部"

# 重置默认管理员
java -jar target/cczx-RadioStation.jar --reset-password
```

注意：

- 查询依赖 `secrets/admin-password.key` 与数据库密文**同时存在**。
- 旧库只有哈希、或密钥丢失时，查询会报"当前密码无法查询"，此时只能 `--reset-password`。
- 查询结果会打印到控制台，注意终端历史与录屏。

### 19.5 账号本人在后台改密

1. 登录 `/admin`；
2. 右上角 **设置**；
3. 填原密码、新密码（10–64 位，含字母和数字）、确认；
4. 保存后**该账号所有会话立即失效**，需重新登录；
5. 其他账号不受影响；文档中的默认密码不变。

### 19.6 推荐的加账号标准流程

```text
① 确认目标环境（生产 or 本机）与数据库配置
② 低峰期执行 --add-account（可不作停服；SQLite 写窗口很短，若报 database is locked 则停服再加）
③ --list-accounts 核对账号名与角色（注意中文、空格、全角字符）
④ 线下把「账号 + 初始密码」告知本人（不要发群、不要写进公开文档）
⑤ 本人首次登录 → 强制改密 → 记录到内部名单
⑥ 需要管理权限的，再执行 --set-role=账号 --role=admin
⑦ 离职/换人：先停用（改密或改角色），不要直接删库
```

### 19.7 关于删除账号

当前版本**没有删除账号的命令**（`Application.kt` 只注册了 7 个管理命令）。如果确实需要删除：

```bash
# 1) 停服并备份
sudo systemctl stop cczx-radio.service
cp -a /opt/cczx-RadioStation/data /var/backups/cczx-radio/data-$(date +%F)

# 2) 用 sqlite3 删除（PostgreSQL 用 psql 改语法）
sqlite3 /opt/cczx-RadioStation/data/broadcast.db \
  "DELETE FROM sessions WHERE username='网络部'; DELETE FROM accounts WHERE username='网络部';"

# 3) 重启
sudo systemctl start cczx-radio.service
```

注意：

- **不要删除 `audit_log`**，历史操作记录需要保留（其中 username 只是文本）。
- **不要删除 `新媒体中心`**，否则系统没有管理员。
- 删除账号不影响其经手过的稿件（稿件表只存账号名文本）。

### 19.8 账号命令速查

| 目标 | 命令 |
| --- | --- |
| 添加编辑 | `java -jar target/cczx-RadioStation.jar --add-account="网络部"` |
| 添加管理员 | `... --add-account="站长" --role=admin` |
| 列出账号 | `... --list-accounts` |
| 改角色 | `... --set-role="网络部" --role=admin` |
| 查密码 | `... --show-password --account="网络部"` |
| 重置密码 | `... --reset-password --account="网络部"` |
| 容器内添加 | `docker compose exec app java -jar /app/app.jar --add-account="网络部"` |

## 20. 日常运维

### 20.1 启动 / 停止 / 重启

| 形态 | 启动 | 停止 | 重启 |
| --- | --- | --- | --- |
| 手工 jar | `java -jar target/cczx-RadioStation.jar` | `Ctrl+C` | 先停再启 |
| systemd | `systemctl start cczx-radio` | `systemctl stop cczx-radio` | `systemctl restart cczx-radio` |
| Docker | `docker compose --profile https up -d` | `docker compose stop app` | `docker compose restart app` |

### 20.2 日志与健康检查

```bash
sudo journalctl -u cczx-radio -f                 # 实时
sudo journalctl -u cczx-radio --since '1 hour ago'
curl -fsS http://127.0.0.1:8000/api/health       # {"ok":true,...}
```

```powershell
docker compose logs -f app
```

健康检查返回 `503` 说明数据库不可用（SQLite 文件被锁/被删、PostgreSQL 连不上）。

### 20.3 备份

**备份三件套，缺一不可：**

```text
① 数据库    data/broadcast.db（含 -wal/-shm）  或 pg_dump 输出
② 密钥      secrets/admin-password.key          ← 丢了就查不了密码
③ 配置      /etc/cczx-radio.env / .env / systemd 单元 / Caddyfile
```

SQLite（**必须停服**）：

```bash
set -euo pipefail
ts=$(date +%Y%m%d_%H%M%S)
sudo install -d -m 700 /var/backups/cczx-radio /var/backups/cczx-radio-keys
# 数据库（含 WAL/SHM）
sudo tar -C /opt/cczx-RadioStation/data -cf "/var/backups/cczx-radio/data-$ts.tar" .
# 密钥（单独目录、单独权限）
sudo tar -C /opt/cczx-RadioStation/secrets -cf "/var/backups/cczx-radio-keys/keys-$ts.tar" .
echo "data-$ts.tar  keys-$ts.tar"    # 记下时间戳，恢复时要用
```

不停服的在线备份（SQLite 官方方式，一致性更好）：

```bash
sqlite3 /opt/cczx-RadioStation/data/broadcast.db ".backup '/var/backups/broadcast-$ts.db'"
```

PostgreSQL：

```bash
pg_dump -U radio -Fc radio_submissions > /var/backups/radio-$ts.dump
```

要求：数据库与密钥**分开存放、分开加密**，至少一份异地。CSV/Excel 导出只能用于查看，**不能替代数据库备份**（会丢账号、会话、公告、审计）。

### 20.4 恢复

SQLite（停服后整目录替换，注意 WAL）：

```bash
set -euo pipefail
ts=YYYYMMDD_HHMMSS    # 换成 20.3 实际输出的时间戳
sudo systemctl stop cczx-radio
sudo mv /opt/cczx-RadioStation/data "/opt/cczx-RadioStation/data.broken-$ts"
sudo mkdir -p /opt/cczx-RadioStation/data
sudo tar -C /opt/cczx-RadioStation/data -xf "/var/backups/cczx-radio/data-$ts.tar"
sudo chown -R radio:radio /opt/cczx-RadioStation/data
sudo chmod 700 /opt/cczx-RadioStation/secrets
sudo systemctl start cczx-radio
```

> 不能只覆盖 `.db` 而留下新的 `-wal`，那会把旧库和新 WAL 混在一起。

PostgreSQL：`pg_restore -U radio -d radio_submissions --clean /var/backups/radio-$ts.dump`，或恢复到独立库后切换 `DB_URL`。

### 20.5 升级（停机替换 jar）

**第 1 步：开发机构建并核对（Windows）**

```powershell
.\mvnw.cmd -B '-DskipTests' package
Get-FileHash target\cczx-RadioStation.jar -Algorithm SHA256
scp target/cczx-RadioStation.jar user@server:/opt/cczx-RadioStation/target/cczx-RadioStation.jar.new
```

**第 2 步：服务器停服、备份、替换、启动（Linux）**

```bash
set -euo pipefail
ts=$(date +%Y%m%d_%H%M%S)
root=/opt/cczx-RadioStation

# 核对新包，再停服
test -s "$root/target/cczx-RadioStation.jar.new"
sha256sum "$root/target/cczx-RadioStation.jar.new"   # 必须与开发机一致
sudo systemctl stop cczx-radio
sudo systemctl is-active --quiet cczx-radio && { echo '旧服务仍在运行'; exit 1; }

# 备份旧程序与配置（数据备份见 20.3）
sudo install -d -m 700 /var/backups/cczx-radio
sudo cp -a "$root/target/cczx-RadioStation.jar" "/var/backups/cczx-radio/program-$ts.jar"
sudo systemctl cat cczx-radio | sudo tee "/var/backups/cczx-radio/service-$ts.txt" >/dev/null

# 保持原所有者与权限后替换
sudo chown --reference="$root/target/cczx-RadioStation.jar" "$root/target/cczx-RadioStation.jar.new"
sudo chmod --reference="$root/target/cczx-RadioStation.jar" "$root/target/cczx-RadioStation.jar.new"
sudo mv -f "$root/target/cczx-RadioStation.jar.new" "$root/target/cczx-RadioStation.jar"
sudo systemctl start cczx-radio

# 验收
curl -fsS http://127.0.0.1:8000/api/health
sudo systemctl status cczx-radio --no-pager
sudo journalctl -u cczx-radio -n 100 --no-pager
```

然后人工检查：公网 HTTPS、后台登录、原有投稿、审批、排期、公告。

### 20.6 回滚

结构/密钥未变时：停服 → 换回旧 jar → 启动。**数据库保持现状**，避免丢掉维护期间新收的投稿。

```bash
sudo systemctl stop cczx-radio
sudo cp -a /var/backups/cczx-radio/program-YYYYMMDD_HHMMSS.jar \
           /opt/cczx-RadioStation/target/cczx-RadioStation.jar
sudo systemctl start cczx-radio
```

数据库或密钥已迁移时：**不能只换 jar**。需停掉所有实例，先另存新数据，再成组恢复同一时点的 旧 jar + 数据库 + 密钥 + 配置。

### 20.7 Docker 升级

```powershell
docker compose build app
docker compose stop app
docker compose exec postgres pg_dump -U radio radio_submissions > backup.sql
# 备份 app-data、app-secrets 卷
docker compose --profile https up -d --no-deps app
```

首次从旧网络配置切到 HTTPS 配置：备份后 `docker compose down`（**不带 `-v`**），再 `docker compose --profile https up -d --build`。之后网络不变可只 `up -d --no-deps app`。

## 21. 上线验收清单

```text
□ java -version ≥ 21，应用进程正常运行
□ /api/health 返回 200 且 ok=true（本机 + 公网各一次）
□ 投稿页可打开，提交一篇测试稿，拿到 GB 编号
□ /admin 可登录，顶部显示正确账号名
□ 首次登录被要求改密，且新密码 ≥10 位含字母数字
□ 普通编辑账号看不到联系方式、导出/删除/服务器详情被拒
□ 待审列表分页、搜索、单篇通过、退稿、批量操作正常
□ 添加日程 → 已安排列表 → 日历跨月 → 标记已播出，状态与时间正确
□ 发布一条公告 → 投稿页公告栏出现，发布者正确
□ 操作记录可翻页，能看到刚才的动作
□ CSV / Excel 导出可打开，中文不乱码，16 列表头正确
□ 手机同一 Wi-Fi 可访问（BIND_ADDRESS=0.0.0.0 + 防火墙放行）
□ 公网 HTTPS 证书有效，HTTP 自动跳转，无 426
□ 数据库、secrets、配置文件三份备份已完成并异地保存
□ 文档中默认密码未被改动，实际密码已线下记录
```

---

# 第三部分 · 注意事项与排错

## 22. 十条必须知道的红线

1. **账号只能命令行添加**，网页永远没有注册入口。加账号时数据库配置必须与线上一致。
2. **默认密码 `cczx123456` 只是初始值**，首次登录强制改密；文档永远只记录默认值，改密不会改文档。
3. **默认只监听 `127.0.0.1`**。手机/局域网访问必须显式 `BIND_ADDRESS=0.0.0.0`。
4. **直接 `java -jar` 不读 `.env`**，配置要通过进程环境变量或 systemd `EnvironmentFile` 传入。
5. **必须从项目根目录运行**，否则 `./data` 会指向另一个空库（表现为"数据全没了"）。
6. **`data/` 与 `secrets/` 必须分开备份、分开加密**；schema 与密钥布局变化时不能只回滚 jar。
7. **SQLite 备份必须停服或使用 `.backup`**，直接复制 `.db` 可能漏掉 WAL 中的已提交数据。
8. **`docker compose down` 不能加 `-v`**，否则数据卷全删。
9. **不要把 8000 端口直接暴露公网**；HTTPS 必须由可信反代终结并正确透传 `X-Forwarded-Proto`。
10. **`tools/cleanup.ps1 -ResetData` 会清空正式数据库**，只有明确要重置时才执行。

## 23. 安全注意事项

- **上线第一件事**：登录后台改掉默认密码；给每个使用者单独建账号，不要共用 `新媒体中心`。
- **`BROADCAST_ADMIN_PASSWORD` 只影响首次初始化**。账号已存在时改这个变量没有任何效果，别指望它来重置密码。
- 该变量必须满足 **≥10 位且同时含字母和数字**；设成 `123456` 会让首次启动直接失败（`首次初始化密码至少 10 位，必须包含字母和数字`）。留空则使用默认值并强制首登改密。
- **编辑角色也拥有审批与排期权**。只给需要的人开 `admin`；`admin` 才能导出（含联系方式）、删除、看服务器信息。
- 后台 token 存在 `sessionStorage`，**关闭标签页即失效**；不要在公用电脑上勾选"记住密码"（本系统也不提供）。
- 会话默认 **2 小时绝对过期 + 30 分钟空闲过期**，长时间挂后台需重新登录。
- 登录失败会限流（同 IP 每分钟 60 次、同 IP+账号 12 次）；被限流时页面提示"一分钟后再试"。
- **反向代理必须覆盖而不是追加 `X-Forwarded-For`**，Caddyfile 里用 `header_up ... {remote_host}`、Nginx 里用 `proxy_set_header X-Forwarded-For $remote_addr`。
- `TRUSTED_PROXY_CIDRS` 配错会导致所有人都算同一个 IP，限流误伤；配太宽会让客户端伪造 IP。
- **纯 TCP 的 FRP 不能当 HTTPS 代理**，它不产生可信的 `X-Forwarded-Proto`。
- 证书过期前续期；不要用"跳过证书校验"绕过 HTTPS 报错。
- 备份文件、`legacy-export.json` 里含密码哈希，导入完成后应删除。
- 服务器安全组只放行 80/443/22，**不要放行 8000、5432**。

## 24. 数据与密钥注意事项

```text
部署目录/
├─ data/                        ← 业务数据（必须备份）
│  ├─ broadcast.db
│  ├─ broadcast.db-wal
│  ├─ broadcast.db-shm
│  ├─ broadcast-before-kotlin-*.db   ← 升级前自动备份
│  └─ admin-password.key             ← 旧版位置，新版启动时迁移到 secrets/
├─ secrets/                     ← 密钥（丢失/重建后旧记录无法解密）
│  └─ admin-password.key
└─ .env / 环境变量               ← 配置
```

- 密钥目录权限应为 `700`，密钥文件 `600`；不要提交到 Git（`.gitignore` 已排除）。
- **密钥丢失 = 无法查询任何已改过的密码**（登录仍可用，因为登录走哈希）。只能 `--reset-password`。
- **升级到含密钥迁移的版本前，必须先停掉所有旧实例**：迁移逻辑是"复制到 `secrets/` → 校验一致 → 删除 `data/` 里的旧密钥"，旧实例还在跑会读不到密钥。
- PostgreSQL 多实例部署时，所有实例必须挂载**同一个** `secrets` 目录/卷。
- 存储扩容/迁移目录时，`DATA_DIR` 与 `SECRETS_DIR` 要一起搬，且权限随之调整。
- 每次升级前把 `data/` 与 `secrets/` 各做一份**同一时间点**的备份；`VACUUM INTO` 自动备份不能替代人工备份。
- 恢复旧备份会丢掉备份之后的新投稿；恢复前先把新数据另存。

## 25. 运行与配置注意事项

| 事项 | 说明 |
| --- | --- |
| 工作目录 | 必须项目根目录；systemd 用 `WorkingDirectory`，Docker 用 `/app` |
| 时区 | 代码固定 `Asia/Shanghai`，投稿时间、编号、周次都不受服务器时区影响；但日志时间跟随系统时区 |
| 端口冲突 | 默认 8000；改 `PORT` 后同步改反代、健康检查、防火墙 |
| 中文输出 | 命令行输出强制 UTF-8；Windows 旧控制台可能显示乱码，用 Windows Terminal 或 PowerShell 7 |
| 并发能力 | Tomcat 50 线程 / 200 连接；SQLite 单连接，写操作串行 |
| 磁盘 | `target/` 与备份会占空间；日志走 journald，注意轮转 |
| JDK 升级 | 21+ 均可；换 JDK 后重新构建，不要混用旧 class |
| 静态资源 | 改前端后**必须重新打包**（静态文件在 jar 内），或直接用 IDEA 运行源码调试 |
| 文档 | `.md` 手动维护，程序不会改写；改文档请手工编辑 |

## 26. Docker 注意事项

- `docker compose --profile full` 默认 `REQUIRE_HTTPS=true`，但没有 caddy → 页面 426。本机联调务必在 `.env` 设 `REQUIRE_HTTPS=false`。
- `docker compose down -v` 删除全部卷，**禁止**在升级流程中使用。
- `app` 容器以 UID `10001` 非 root 运行；挂载宿主机目录时注意属主，否则 `data/`、`secrets/` 写不进去。
- `app-secrets` 必须是独立卷；不要和 `app-data` 合并。
- 改了 `compose.yml` 里的子网，必须同步改 `TRUSTED_PROXY_CIDRS`（默认对应 caddy 的 `172.30.16.2`）。
- 容器内 `--show-password` 要 `docker compose exec app ...`，不能在宿主机直接对容器文件跑。
- 首次启动 PostgreSQL 需要等健康检查通过（compose 已配 `depends_on: condition: service_healthy`）。
- 本机 `docker compose up -d postgres` 只起数据库，端口只绑 `127.0.0.1:5432`。

## 27. 升级注意事项

- 现版本**不支持热更新/自动更新**，必须停机替换 jar 或重建镜像。
- 升级前记录 Git commit 与 jar 的 SHA256，并在服务器核对一致。
- 不要把开发机的 `data/`、`secrets/`、`.env` 上传到生产。
- 判断本次是否涉及**数据库脚本、表初始化、密钥位置**变化：
  - 不涉及 → 失败只需换回旧 jar；
  - 涉及 → 先在隔离环境用生产备份试升级，并准备同一时点的数据库 + 密钥备份。
- 第一次升级到含 V5 的版本会有这些一次性变化：保留投稿/账号/已设密码；注销旧会话；`新媒体中心` 保持 admin，其他已有账号变为 editor；默认或弱密码用户登录后必须改密；密钥从 `data/` 迁到 `secrets/`。
- V5 之后如需把其他账号恢复为管理员：停服后执行 `--set-role=账号 --role=admin`，再启动。
- SQLite 升级前会自动生成 `broadcast-before-kotlin-*.db`，但它**不是**完整备份策略，仍需人工备份。
- 升级完成后检查顺序：本机 `/api/health` → 公网 HTTPS → 后台登录 → 投稿 → 审批 → 排期 → 公告。

## 28. 功能边界与已知限制

| 限制 | 说明 / 应对 |
| --- | --- |
| 无网页注册 | 只能命令行加账号（19 节） |
| 无删除账号命令 | 需手工 SQL，且要先停服备份（19.7） |
| 无自助找回密码 | 编辑忘记密码 → 管理员 `--reset-password` |
| 无自动更新 | 停机替换 jar |
| 公开进度查询已关闭 | `/api/submissions/track` 固定 `410` |
| 编辑看不到联系方式 | 设计如此；需要时提升为 admin |
| 导出上限 5000 篇 | 缩小日期或筛选范围 |
| 批量操作上限 1000 篇 | 分批 |
| 会话 2 小时 / 空闲 30 分钟 | 长任务中途可能需重登 |
| 投稿正文 1–100 字 | 不能传附件、图片、音频 |
| 公告按纯文本显示 | 不支持 HTML/Markdown 渲染 |
| SQLite 单写入者 | 多实例请切 PostgreSQL |
| 无短信/邮件通知 | 编号由投稿成功页直接展示 |
| 无多语言 | 界面固定简体中文 |

## 29. 文档与实现差异（实测）

部署前请留意这几处"文档说的"和"代码做的"不一致：

| 项目 | 文档描述 | 实际实现 | 建议 |
| --- | --- | --- | --- |
| 登录有效期 | `PROJECT.md`：12 小时 | `application.yml`：7200 秒（2 小时）+ 空闲 1800 秒（30 分钟） | 以配置为准；需要更长自行调整 |
| 手机局域网访问 | `PROJECT.md`：直接访问 `http://192.168.x.x:8000/` | 默认 `BIND_ADDRESS=127.0.0.1`，**必须先设 `0.0.0.0`** | 部署时显式设置（14.3） |
| `docker compose --profile full` | `PROJECT.md`：可直接构建并启动 | compose 默认 `REQUIRE_HTTPS=true`，无 caddy 时全部 426 | `.env` 设 `REQUIRE_HTTPS=false` |
| 稿件进度查询 | `PROJECT.md` 列出 `track` API | 代码固定抛 `410`（功能已关闭） | 客户端不要依赖 |
| 管理员密码查询 | 文档写"查询密钥为 `./secrets/admin-password.key`" | 正确；但当前这份部署目录里 `data/admin-password.key` **尚未迁移**（还没有 `secrets/` 目录，说明新版尚未启动过） | 升级前停旧实例，先分别备份 `data/` 与旧密钥 |

## 30. 故障排查表

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 手机打不开页面，电脑本机能开 | 只监听 `127.0.0.1` | 设 `BIND_ADDRESS=0.0.0.0` 后重启 |
| 手机仍打不开 | Windows 防火墙未放行 8000；不同网段；访客 Wi-Fi；VPN；路由器客户端隔离 | 放行端口；连同一 Wi-Fi；关 VPN；关 AP 隔离 |
| 页面返回 `426` | `REQUIRE_HTTPS=true` 但请求非 HTTPS，或代理没传 `X-Forwarded-Proto` | 代理补头；内网联调改 `false` |
| 后台一直提示"请先修改初始密码" | `initial_password=1` 或输入的还是默认密码 | 在改密页设置 ≥10 位含字母数字的新密码 |
| 登录后很快掉线 | 空闲超 30 分钟或绝对超 2 小时 | 重新登录（属预期） |
| 登录返回 `429` | 触发登录限流 | 等 1 分钟（响应带 `Retry-After: 60`） |
| 提交返回 `429` | 单 IP 每分钟投稿超过 20 次 | 等待；或调 `SUBMISSIONS_PER_MINUTE` |
| 返回 `413` | 请求体超限（16/64KB）或导出超 5000 篇 | 缩小内容/筛选范围 |
| 返回 `409 部分稿件已被审批` | 别人先审了同一批 | 刷新列表重新选择 |
| 返回 `500`，日志有 `database is locked` | SQLite 并发写 | 稍后重试；或切换 PostgreSQL |
| `--show-password` 报"当前密码无法查询" | 旧库只有哈希，或 `secrets` 密钥丢失 | `--reset-password --account=账号` |
| 启动失败：`首次初始化密码至少 10 位…` | `BROADCAST_ADMIN_PASSWORD` 太弱 | 改成强密码或留空 |
| 启动后数据"全没了" | 工作目录 / `DATA_DIR` / `DB_URL` 与原来不同 | 回到原目录与原环境变量启动 |
| 后台"服务器状态"报错 | 当前是编辑角色 | 属预期；需要时提升为 admin |
| 导出中文乱码 | 用记事本直接打开 CSV | 用 Excel 打开（CSV 已带 BOM） |
| 改了密码，文档没变 | 文档手动维护 | 属预期，需要时手工改 |
| Docker 起来就 426 | `--profile full` + 默认 `REQUIRE_HTTPS=true` | `.env` 设 `REQUIRE_HTTPS=false` |
| Docker 升级后数据没了 | 执行了 `down -v` | 从备份卷恢复；以后只用 `down` |
| 反代后所有人看起来是同一个 IP | `TRUSTED_PROXY_CIDRS` 未覆盖代理 | 补上代理地址/CIDR |
| 日志里 `无法准备 Maven` | 首次构建需要联网下载 Maven | 联网重试，或安装 Maven / 设置 `MAVEN_HOME` |

## 31. 附录

### 31.1 命令速查

```powershell
# 构建
.\mvnw.cmd -B '-DskipTests' package
.\mvnw.cmd -o -B '-DskipTests' package          # 离线

# 运行
java -jar target/cczx-RadioStation.jar

# 账号
java -jar target/cczx-RadioStation.jar --add-account="网络部"
java -jar target/cczx-RadioStation.jar --add-account="站长" --role=admin
java -jar target/cczx-RadioStation.jar --list-accounts
java -jar target/cczx-RadioStation.jar --set-role="网络部" --role=admin
java -jar target/cczx-RadioStation.jar --show-password --account="网络部"
java -jar target/cczx-RadioStation.jar --reset-password --account="网络部"

# 数据
java -jar target/cczx-RadioStation.jar --demo-data
java -jar target/cczx-RadioStation.jar --import-legacy=data/legacy-export.json

# 测试
.\mvnw.cmd test
.\tools\run-local-tests.ps1 -SkipCompile
node --test tests/submit.test.cjs
node tests/browser-check.cjs
node tests/password-cli.cjs
python tests/offline_checks.py

# 清理（-ResetData 会清空正式数据）
powershell -NoProfile -ExecutionPolicy Bypass -File tools/cleanup.ps1
```

### 31.2 API 一览

| 方法 | 路径 | 鉴权 | 用途 |
| --- | --- | --- | --- |
| GET | `/api/health` | 否 | 健康检查（200/503） |
| GET | `/api/meta` | 否 | 栏目、状态、周次、总数 |
| POST | `/api/submissions` | 否 | 提交稿件 |
| GET | `/api/announcements` | 否 | 已发布公告与投稿计划 |
| POST/GET | `/api/submissions/track` | 否 | 已关闭，固定 410 |
| POST | `/api/admin/login` | 否 | 登录 |
| GET | `/api/admin/me` | 是 | 当前账号与角色 |
| POST | `/api/admin/logout` | 是 | 退出当前会话 |
| POST | `/api/admin/password` | 是 | 修改自己的密码 |
| GET | `/api/admin/audit` | 是 | 操作记录（每页 20） |
| GET | `/api/admin/server` | admin | 服务器信息 |
| GET | `/api/admin/stats` | 是 | 投稿统计 |
| GET/POST | `/api/admin/submissions` | 是 | 稿件列表 / 手动登记 |
| PATCH/DELETE | `/api/admin/submissions/{id}` | 是 / admin | 审批、排期、状态 / 删除 |
| GET | `/api/admin/review-summary` | 是 | 待审数量与 ID 快照 |
| POST | `/api/admin/submissions/batch` | 是 | 批量操作 |
| POST | `/api/admin/submissions/approve-all` | 是 | 全部审核（需 `confirmed`） |
| GET | `/api/admin/schedule` | 是 | 已排期稿件 |
| GET/POST | `/api/admin/announcements` | 是 | 消息列表 / 发布 |
| PATCH/DELETE | `/api/admin/announcements/{id}` | 是 / admin | 撤回恢复 / 删除 |
| GET | `/api/admin/export?format=csv\|xlsx` | admin | 导出 |

请求/响应字段为 `snake_case`；错误体统一 `{"error":"..."}`，限流时额外带 `Retry-After: 60`。

### 31.3 关键文件清单

| 文件 | 部署时是否必须 |
| --- | --- |
| `target/cczx-RadioStation.jar` | ✅ 程序本体 |
| `data/` | ✅ 数据（首次可空） |
| `secrets/admin-password.key` | ✅ 密码查询密钥（首次自动生成） |
| `.env` / systemd `EnvironmentFile` | ✅ 配置 |
| `Caddyfile` / Nginx 配置 | ✅ 公网 HTTPS |
| `compose.yml` / `Dockerfile` | 仅 Docker 部署 |
| `pom.xml` / `src/` / `mvnw` | 仅构建时需要 |
| `tests/` / `tools/` | 仅验证与维护 |

---

*本文根据仓库内文档与源码核对整理；程序行为以实际运行的代码为准。文档只记录默认密码 `cczx123456`，实际密码请用 `--show-password` 查询，不会随改密更新。*
