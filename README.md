# cczx-RadioStation

校园广播投稿系统，后端使用 Kotlin + Spring Boot，默认 SQLite，也支持 PostgreSQL。公开项目、API、Android 和部署说明集中在本文件。默认管理员密码为 `cczx123456`，使用 `--show-password` 查询当前密码。应用不会生成或改写 `.md` 文件。


## 后台账号与操作人

- 默认账号：`新媒体中心`；每个新账号的默认密码：`cczx123456`。
- 登录必须同时提供账号和密码。网页不提供注册或添加账号的入口，HTTP API 也不提供注册接口；所有后台账号拥有稿件与消息管理权限。
- 账号名称为 1–40 个字符，允许中文，不允许控制字符。重复添加会报错并保留原密码。
- 每个账号可在后台修改自己的密码，修改或重置只注销该账号的会话，其他账号不受影响。

在项目根目录、使用与网站相同的数据库配置执行下列命令。命令完成后退出，不启动网页服务：

```powershell
# 添加账号，初始密码统一为 cczx123456
java -jar target/cczx-RadioStation.jar --add-account="网络部"
# 查看已有账号
java -jar target/cczx-RadioStation.jar --list-accounts
# 查询指定账号的当前密码；省略 --account 时查询「新媒体中心」
java -jar target/cczx-RadioStation.jar --show-password --account="网络部"
# 重置指定账号为 cczx123456；省略 --account 时重置「新媒体中心」
java -jar target/cczx-RadioStation.jar --reset-password --account="网络部"
```

审稿、批量审批、全部审核、排期、切换播出状态、修改评分或备注、手动登记、删除稿件以及公告/投稿计划的发布、撤回、恢复和删除均记录实际操作账号。后台顶部显示当前账号；稿件列表、详情、日历显示审稿人、排期人和最近操作人；公告栏显示实际发布者。控制台的「操作记录」支持翻页，删除内容后仍保留操作历史。操作人从登录会话获取，客户端填写的操作人字段无效。

稿件接口新增 `reviewed_by`、`scheduled_by`、`updated_by`；消息接口的 `publisher` 为发布账号，并新增 `updated_by`、`updated_at`。CSV/Excel 导出增加审稿人、排期人、最近操作人。认证后的 `GET /api/admin/me` 返回 `username`；`GET /api/admin/audit?page=1` 返回 `items`、`total`、`page`、`pages`，支持 `target_type=submission` 或 `announcement` 及 `target_id` 筛选，每页 20 条。

登录示例：`{"username":"新媒体中心","password":"cczx123456"}`。登录成功返回 `token`、`expires_at`、`username` 和 `initial_password`。后台改密仅操作会话所属账号，传入其他账号名称不会修改其他人的密码。

升级使用 SQLite 幂等迁移 / PostgreSQL Flyway V4。旧共用密码归入「新媒体中心」；旧默认密码 `admin` 升级为 `cczx123456`，已经修改过的密码保留，旧会话注销。旧投稿和公告保留，无法确定的历史操作人留空。

投稿页和后台页的页尾均左对齐显示「长春中学新媒体中心」（较大字号）、「by网络部x长春中学科创社」，最后一行的 GitHub 图标链接到 https://github.com/xHua0904/cczx-RadioStation。

本文仅手动维护。本次按功能需求更新文档，网站运行、添加账号、改密、查询和重置均不会自动创建或改写 `.md`，文档只列默认密码。


## 直接运行 jar（推荐）

已有构建好的 jar 时，不需要启动 IDEA，只需安装 JDK 21 或更高版本。在项目根目录打开 PowerShell，运行：

```powershell
java -jar target/cczx-RadioStation.jar
```

请从项目根目录执行，以继续使用现有 `data/broadcast.db`。启动后访问 `http://127.0.0.1:8000/`，停止按 `Ctrl+C`。直接运行 jar 不会自动读取 `.env`，数据库配置应设置为进程环境变量；需要自动读取 `.env` 时使用后面的 PowerShell 代码。

## 在 IDEA 中构建或调试

1. 使用 `File → Open` 打开本项目的 `pom.xml`，作为 Maven 项目导入。
2. 在 `Project Structure → Project` 将 SDK 设为 JDK 21 或更高版本，语言级别设为 21；Maven Runner 的 JRE 使用项目 SDK。
3. 在 Maven 面板执行 `Reload All Maven Projects`，然后运行 Maven 目标 `-DskipTests package`。
4. 构建结果是 `target/cczx-RadioStation.jar`。也可打开 `src/main/kotlin/com/cczxcsc/Application.kt`，运行 `com.cczxcsc.ApplicationKt` 调试，运行配置的工作目录设为项目根目录。

已有本地缓存时，可在 IDEA Terminal 执行 `.\mvnw.cmd -o -B '-DskipTests' package`，使用项目内 Maven 缓存；首次构建需联网下载依赖。若直接使用 IDEA 的 Maven 面板离线构建，将 Maven 的 Local repository 指向项目内 `.tools/repository`。

## 从源码自动构建并启动（可选）

安装 JDK 21 或更高版本，在项目根目录打开 PowerShell，复制以下整段代码执行。代码会读取 `.env`，在源码更新时自动构建，然后启动服务；无需单独的启动脚本文件。

```powershell
$ErrorActionPreference = 'Stop'
$taskRoot = (Get-Location).Path
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'pom.xml'))) {
    throw '请先进入 cczx-RadioStation 项目根目录。'
}

# 默认启动网站；查询密码时改为 @('--show-password')，恢复 cczx123456 时改为 @('--reset-password')。
$taskAppArgs = @()

if (Test-Path -LiteralPath '.env') {
    foreach ($taskLine in Get-Content -LiteralPath '.env' -Encoding UTF8) {
        if ($taskLine -match '^\s*(DB_URL|DB_USER|DB_PASSWORD|SPRING_PROFILES_ACTIVE|PORT|DATA_DIR)\s*=(.*)$') {
            $taskKey = $Matches[1]
            $taskValue = $Matches[2].Trim()
            if ($taskValue.Length -ge 2 -and (($taskValue.StartsWith('"') -and $taskValue.EndsWith('"')) -or ($taskValue.StartsWith("'") -and $taskValue.EndsWith("'")))) {
                $taskValue = $taskValue.Substring(1, $taskValue.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($taskKey, $taskValue, 'Process')
        }
    }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw '请安装 JDK 21 或更高版本，并将 java 加入 PATH。'
}
$taskJar = Join-Path $taskRoot 'target/cczx-RadioStation.jar'
$taskSources = @(Get-Item -LiteralPath 'pom.xml') + @(Get-ChildItem -LiteralPath 'src/main' -File -Recurse)
$taskNeedsBuild = -not (Test-Path -LiteralPath $taskJar)
if (-not $taskNeedsBuild) {
    $taskJarTime = (Get-Item -LiteralPath $taskJar).LastWriteTimeUtc
    $taskNeedsBuild = @($taskSources | Where-Object { $_.LastWriteTimeUtc -gt $taskJarTime }).Count -gt 0
}
if ($taskNeedsBuild) {
    & (Join-Path $taskRoot 'mvnw.cmd') '-B' '-DskipTests' 'package'
    if ($LASTEXITCODE -ne 0) { throw '构建失败，请查看上方错误。' }
}

$taskPort = if ($env:PORT) { $env:PORT } else { '8000' }
if ($taskAppArgs.Count -eq 0) {
    Write-Host "投稿页：http://127.0.0.1:$taskPort/"
    Write-Host "新媒体中心：http://127.0.0.1:$taskPort/admin"
    Write-Host '手机访问时使用电脑局域网 IP；停止服务按 Ctrl+C。'
}
& java '-jar' $taskJar @taskAppArgs
if ($LASTEXITCODE -ne 0) { throw '应用退出时发生错误，请查看上方日志。' }
```

默认管理员密码为 `cczx123456`，查询当前密码可将 `$taskAppArgs` 改为 `@('--show-password')`。应用不创建或改写任何 `.md`。其他命令可将上述 `$taskAppArgs` 改为 `@('--demo-data')` 或 `@('--import-legacy=data/legacy-export.json')`；这些命令执行完成后退出。

---

## 架构与目录

后端使用 Kotlin 2.1、Spring Boot 3.5、JVM 21+；默认 SQLite，可切换 PostgreSQL。前端使用 HTML、CSS 和 JavaScript。代码包名为 `com.cczxcsc`，入口为 `com.cczxcsc.ApplicationKt`。

```text
PROJECT.md                                  工作目录手动维护的项目说明；应用不会改写
README.md                                   完整公开说明与可复制的启动代码
AGENTS.md                                   开发协作规则
pom.xml                                     Maven 配置，groupId=com.cczxcsc
src/main/kotlin/com/cczxcsc/                 接口、业务、认证、数据库与导出
src/main/resources/static/                  投稿页与管理后台
src/main/resources/db/                      SQLite 初始化与 PostgreSQL 迁移
src/test/kotlin/com/cczxcsc/                 Kotlin 与数据库接口测试
tests/                                      前端、浏览器与离线检查
tools/                                      测试运行器、清理和旧数据导出工具
legacy/                                     原 Python 代码归档，仅本地可选，不提交 Git
compose.yml / Dockerfile                    容器部署配置
data/                                       正式 SQLite 数据与备份
target/                                     构建结果与临时测试产物
.tools/                                     Maven 及依赖缓存
```

Maven 坐标为 `com.cczxcsc:cczx-RadioStation:1.0.0`。源码按接口、业务服务、数据访问分层；Android 客户端调用 HTTP API，不运行 Spring Boot，也不直接连接数据库。

## 启动与配置

启动方法见本文前面的 jar、IDEA 和 PowerShell 说明。也可在 IntelliJ IDEA 中打开 `pom.xml`，运行 `com.cczxcsc.ApplicationKt`。

### jar 启动（无需 IDEA）

已有 jar 时无需启动 IDEA，在项目根目录执行 `java -jar target/cczx-RadioStation.jar`。IDEA 中选择 JDK 21+、重新加载 Maven，并执行 `-DskipTests package`，即可生成可执行 jar。项目顶层目录名为 `cczx-RadioStation`，工作目录设为项目根目录。

```powershell
.\mvnw.cmd -B '-DskipTests' package
java -jar target/cczx-RadioStation.jar
```

已有 jar 时直接执行上面的 Java 命令即可，无需重新构建。启动后访问 `http://127.0.0.1:8000/`，后台为 `http://127.0.0.1:8000/admin`，按 `Ctrl+C` 停止。请从项目根目录运行，以使用该目录下的正式数据库；更新源码后需重新打包，再启动新 jar。

首次构建需要下载 Maven 与依赖，缓存齐全后可加 `-o` 离线构建。README 中的启动代码会在源码有更新时重新打包，并读取根目录 `.env`；直接运行 Java 或 IDE 时应将配置放入进程环境变量。

| 环境变量 | 用途 |
| --- | --- |
| `PORT` | HTTP 端口，默认 `8000` |
| `DATA_DIR` | SQLite 数据及备份目录，默认 `./data` |
| `SPRING_PROFILES_ACTIVE=postgres` | 使用 PostgreSQL |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 数据库连接配置 |

```powershell
# 查询当前管理员密码，打印后退出；必须使用网站相同的数据库和 DATA_DIR
java -jar target/cczx-RadioStation.jar --show-password
# 恢复默认密码 cczx123456，注销当前账号的全部登录会话，然后退出
java -jar target/cczx-RadioStation.jar --reset-password
# 添加 20 条带「演示数据」审核备注的稿件，然后退出
java -jar target/cczx-RadioStation.jar --demo-data
```

## 密码查询与默认值

新数据库默认密码为 `cczx123456`。新建账号统一使用默认密码，不覆盖已有账号密码；后台改密后重启仍使用新密码。`--reset-password` 固定恢复为 `cczx123456`。

```powershell
# 本机查询当前密码，查询完成后退出，不启动 HTTP 服务
java -jar target/cczx-RadioStation.jar --show-password
```

命令必须从项目根目录执行，并使用与网站相同的数据库配置和 `DATA_DIR`。新数据库尚未创建时，查询会先初始化默认账号。正常启动不会打印密码；管理 API 不提供密码查询接口。

登录校验保存哈希；本机查询使用数据库中的加密记录和 `data/admin-password.key`。备份数据库时一并保留此密钥，PostgreSQL 多实例须共享该密钥。只有旧哈希或密钥丢失时，无法反查未知的旧密码，可用 `--reset-password` 恢复默认值。文档始终记录默认值，不随当前密码变化。

## 页面功能

- 投稿页：姓名、班级、必填稿件内容、选填联系方式；不再选择投稿期次。
- 公告栏：按块显示公告和投稿计划、日期，显示实际账号发布者。
- 控制台：服务器 IP、JVM、内存、数据库延迟、投稿统计、班级排行与近期播出安排。
- 稿件审批：待审列表、搜索、分页、右侧选中操作、手动登记和 CSV/Excel 导出。全部审核先提示风险，取消不提交；确认只处理提示前的稿件快照，新投稿继续待审。
- 已审批稿件：标题下切换「已审批稿件 / 已安排稿件 / 播出日历」。已审批页面展示未排期的已审核稿件；已安排页面展示有播出日期的已通过、已播出稿件。添加或移出日程后自动归入对应页面，分别支持搜索、分页、筛选和导出。日历选中日期后按时间展示当天稿件，每篇可切换「待播出 / 已播出」并保存，保留日期、时间和备注，支持跨月浏览和返回今天。
- 消息发布：公告与投稿计划的发布、撤回、恢复、删除；后台顶部可返回投稿页。

姓名 2–20 字、班级 2–30 字、稿件去除首尾空白后 1–100 字、联系方式选填且最多 50 字。新稿件默认待审核；已通过的稿件可安排播出日程。

## 手机与局域网访问

电脑和手机连接同一路由器的普通网络。使用电脑的局域网 IPv4，例如 `http://192.168.0.100:8000/`，冒号必须是半角 `:`。手机上的 `127.0.0.1` 指手机自身。

地址可在控制台或 `ipconfig` 中查看；电脑 IP 可能随网络变化。公网部署使用域名和 HTTPS。

若手机无法打开，先检查电脑本机是否能打开上述局域网地址，再检查手机是否用了移动网络、访客 Wi-Fi、VPN，路由器是否启用了客户端隔离，以及 Windows 防火墙是否允许应用的 TCP 端口。监听地址可用 `netstat -ano -p tcp` 检查。

## REST API

请求和响应为 JSON，字段使用 `snake_case`。除登录外，管理接口需要 `Authorization: Bearer <token>`。登录有效期为 12 小时，退出注销当前 token，改密与重置密码只注销该账号的全部会话。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/health` | 服务与数据库健康检查 |
| GET | `/api/meta` | 投稿状态、数量和兼容元数据 |
| POST | `/api/submissions` | 提交稿件，无需登录 |
| GET | `/api/announcements` | 已发布公告与投稿计划 |
| POST | `/api/submissions/track` | 旧查询 API，按编号和姓名查询 |
| GET | `/api/submissions/track?code=...&author=...` | 兼容旧查询，网页不提供入口 |
| GET | `/api/admin/me` | 当前登录账号 |
| GET | `/api/admin/audit` | 分页操作记录及对象筛选 |
| POST | `/api/admin/login` | 账号与密码登录，返回账号、token 与过期时间 |
| POST | `/api/admin/logout` | 注销当前 token |
| POST | `/api/admin/password` | 改密并注销会话，不修改文档 |
| GET | `/api/admin/server` | 服务器 IP、JVM、内存、数据库与运行时长 |
| GET | `/api/admin/stats` | 投稿统计 |
| GET / POST | `/api/admin/submissions` | 稿件列表 / 手动登记 |
| PATCH / DELETE | `/api/admin/submissions/{id}` | 审批、评分、备注、日程 / 删除 |
| GET | `/api/admin/review-summary` | 待审数量与 IDs 快照 |
| POST | `/api/admin/submissions/batch` | 批量操作所选稿件 |
| POST | `/api/admin/submissions/approve-all` | 明确确认后通过快照稿件 |
| GET | `/api/admin/schedule` | 已排期稿件及播出记录 |
| GET / POST | `/api/admin/announcements` | 管理消息 / 发布消息 |
| PATCH / DELETE | `/api/admin/announcements/{id}` | 撤回、恢复 / 删除 |
| GET | `/api/admin/export?format=csv` 或 `xlsx` | 导出稿件、审批和日程 |

投稿请求：

```json
{"author":"张同学","class_name":"高一一班","content":"记录校园生活，分享身边的温暖。","contact":""}
```

成功返回 `ok`、`code`、`created_at`。历史期次与歌曲字段仅保留旧 API、旧数据兼容性，新客户端无需使用。

列表参数为 `phase=pending` 或 `reviewed`、`schedule=unscheduled` 或 `scheduled`、`q`、`status`、`category`、`start`、`end`、`page`，每页 15 条；返回 `items`、`total`、`page`、`pages`、`page_size`、`counts`。未排期已审批页面使用 `phase=reviewed&schedule=unscheduled`；已安排页面使用 `phase=reviewed&schedule=scheduled`，包含已通过、已播出稿件。省略 `schedule` 时保持原列表兼容性。CSV/Excel 导出支持相同筛选参数。

登录请求为 `{"username":"新媒体中心","password":"cczx123456"}`；改密请求为 `{"old_password":"原密码","new_password":"新密码"}`，新密码须为 6–64 位。

批量排期请求：

```json
{"ids":[1,2],"action":"schedule","broadcast_date":"2026-12-01","broadcast_time":"12:30"}
```

`action` 支持 `approve`、`reject`、`schedule`、`played`、`clear_schedule`、`reopen`。通过和退稿只处理待审稿件，排期及标记播出只处理已通过稿件。日期为 `YYYY-MM-DD`，时间为 `HH:mm`，备注最多 300 字，评分为 0–100 的整数或 `null`。

全部审核先获取 `/api/admin/review-summary`，展示数量与风险。确认后发送同一份 IDs：`{"ids":[1,2],"confirmed":true}`；取消不发送 POST。快照内稿件已被他人审批时返回 409，请刷新后重新确认。

服务器接口返回 `server_ip`（首选 IPv4）和 `server_ips`（可用 IPv4 列表），优先列出局域网地址。日历读取 `/api/admin/schedule` 的 `items`，按 `broadcast_date` 分组，并按 `broadcast_time` 排序。日历状态切换调用 `PATCH /api/admin/submissions/{id}`，请求为 `{"status":"已播出"}` 或 `{"status":"已通过"}`；仅修改该稿件状态，原日程和备注保留。

消息请求：

```json
{"title":"投稿计划","content":"欢迎分享校园见闻。","kind":"投稿计划"}
```

标题 1–80 字，内容 1–2000 字，`kind` 为「公告」或「投稿计划」，无需指定期次。撤回为 `{"published":false}`，恢复为 `{"published":true}`。客户端将消息正文作为文本显示。

错误响应为 `{"error":"错误说明"}`，常见 HTTP 状态为 400、401、404、409、413、429；客户端应处理验证失败、登录过期和状态冲突。

## Android App

在 Android 项目中加入 Retrofit，并在协程中调用接口：

```kotlin
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-gson:2.11.0")
```

```kotlin
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class ManuscriptRequest(
    val author: String,
    @SerializedName("class_name") val className: String,
    val content: String,
    val contact: String = ""
)
data class SubmitResult(val ok: Boolean, val code: String)
interface RadioApi {
    @POST("api/submissions")
    suspend fun submit(@Body input: ManuscriptRequest): Response<SubmitResult>
}
val api = Retrofit.Builder()
    .baseUrl("https://你的广播站域名/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(RadioApi::class.java)
```

`AndroidManifest.xml` 添加 `<uses-permission android:name="android.permission.INTERNET" />`。检查 `Response.isSuccessful` 和 `errorBody()`。模拟器访问宿主机可用 `http://10.0.2.2:8000/`，真机使用电脑的局域网 IP；HTTP 调试需在调试构建中允许，正式部署使用 HTTPS。

## PostgreSQL 与容器部署

默认 SQLite 无需额外配置。使用 PostgreSQL 时复制 `.env.example` 为 `.env`，填写数据库密码：

```dotenv
SPRING_PROFILES_ACTIVE=postgres
DB_URL=jdbc:postgresql://localhost:5432/radio_submissions
DB_USER=radio
DB_PASSWORD=你的数据库密码
PORT=8000
```

```powershell
# 仅启动 PostgreSQL，再执行 README.md 中的启动代码
docker compose up -d postgres
# 或同时构建并启动应用与数据库
docker compose --profile full up -d --build
```

PostgreSQL 表结构由 Flyway 管理。容器中用 `docker compose exec app java -jar /app/app.jar --show-password` 查询当前管理员密码，应用不会生成文档。数据库卷为 `postgres-data`，密码查询密钥存放在 `app-data` 卷；本机 SQLite 数据目录默认为 `./data`。

迁移旧 SQLite 投稿到空的 PostgreSQL 数据库：

```powershell
python tools/export_legacy.py
# .env 已配置为 PostgreSQL；导入后退出
java -jar target/cczx-RadioStation.jar --import-legacy=data/legacy-export.json
```

导出只读访问旧库，导入保留编号、正文、审核信息、密码哈希，恢复编号计数，并注销旧会话。导出文件包含密码哈希，导入完成后应删除。旧库升级前会自动备份。

SQLite 备份请停止应用后复制数据库文件，或使用 SQLite 在线备份工具；未检查点的数据可能在 WAL 文件中。PostgreSQL 使用 `pg_dump` / `pg_restore`。CSV/Excel 用于查看稿件，不替代完整数据库备份。

## 验证与清理

依赖齐全时执行 `.\mvnw.cmd test`。缺少 Maven 测试执行器依赖时，可使用本地缓存及备用运行器：

```powershell
.\mvnw.cmd -o -B '-DskipTests' package
.\tools\run-local-tests.ps1 -SkipCompile
node --test tests/submit.test.cjs
node tests/browser-check.cjs
node tests/password-cli.cjs
python tests/offline_checks.py
```

Kotlin / SQLite 测试覆盖多账号默认密码、账号独立改密与重置、操作人不可伪造、审稿排期和消息操作记录、投稿、认证、并发编号、审批、日程、公告、CSV/Excel、旧库升级与导入、默认密码、改密、查询密钥缺失及手动文档保持不变。`password-cli.cjs` 验证真实 jar 添加账号、列出账号、重复账号保护、查询、重置、命令互斥与文档不变。浏览器测试启动独立 Java 服务与 Edge 配置，检查四模块、IP、风险确认、已审批与已安排列表分离、日历状态切换保留日程、公告文本展示、日历跨月跨年及闰年、手机布局；通过 `EDGE_PATH` 可指定 Edge。

2026-11-15 已通过 12 项 Kotlin/SQLite 测试、4 项投稿页交互测试，以及真实浏览器检查。Maven 的 `surefire-junit-platform:3.5.4` 未缓存，网络受限，因此使用 `tools/run-local-tests.ps1` 执行实际 JUnit 断言和 Spring 测试上下文。本机没有可用 Docker/PostgreSQL，PostgreSQL 集成测试及实际 Android App 联调尚未执行。

每次开发结束清理测试数据库、报告、截图和浏览器配置，保留源码、正式 jar、Maven 缓存和正式投稿：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/cleanup.ps1
```

只有明确要求清空正式数据时才执行下列命令。先停止应用，清空无法恢复：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/cleanup.ps1 -ResetData
java -jar target/cczx-RadioStation.jar --reset-password
```

`-ResetData` 仅清理项目内默认 `data/`，不处理外部 PostgreSQL 或自定义数据目录。

