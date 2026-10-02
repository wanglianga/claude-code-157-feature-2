# 医院陪护证申请核验进出记录退证服务 (escort-pass-service)

住院患者陪护全生命周期服务：家属 **申请** → 护士站 **审批** → 签发电子陪护证并 **同步门禁/病区扫码点/安保巡查** →
进出 **扫码核验与异常记录** → 陪护人 **替换（含夜间发烧临时替换、无人陪护巡查与看护提醒）/冻结/违规处理** → **退证归档**。
申请、审批、进出、替换、违规、退证全部归档在**同一条陪护记录（escort_case）**上，
每一次替换、冻结、退证都会通知病区"患者当下由谁照护"。

技术栈：Kotlin + Spring Boot 3.2 + Spring Data JPA + Flyway + PostgreSQL 15 + Kafka + Maven。
接口文档（纯后端服务）：启动后访问 **Swagger UI `http://localhost:8080/swagger-ui.html`**，OpenAPI JSON：`/v3/api-docs`。

## 1. 业务能力

| 环节 | 说明 | 接口 |
|---|---|---|
| 申请 | 住院号、病区/床位、陪护人身份与联系方式、健康承诺、核酸/传染病筛查、陪护期限、夜间陪护需求 | `POST /api/applications` |
| 审批 | 护士站按病区探视限制、床位陪护上限、夜间陪护专项审批、筛查有效期审批；通过即发证 | `POST /api/applications/{no}/decision` |
| 发证同步 | 电子证含二维码 token，状态变化同步 GATE / WARD_SCAN / SECURITY_PATROL 三类下游，`permit_sync` 留同步状态 | 自动 |
| 进出核验 | 记录扫码时间、门禁位置、方向；检测超允许时段、未登记换人、多证共用、病区封闭、外出限制 | `POST /api/access/scan` |
| 替换 | 陪护人临时生病、亲属争抢陪护资格、排班调整；旧证 REPLACED、新证签发，同记录留痕 | `POST /api/replacements` |
| 临时替换 | 原陪护夜间发烧已离院：旧证即时停用，患者进入**短时无人陪护**（护士站可见并安排巡查）；新陪护到院核验**身份/健康承诺/亲属关系**后到位交接、记录交接时间 | `POST /api/replacements/temporary` → `/api/replacements/{id}/arrival`（可 `/cancel`） |
| 看护提醒 | 无人陪护间隙护士站临时创建**进食/翻身/夜间观察**提醒并巡查；新陪护到位后提醒自动转为**家属已接手**并保留交接时间 | `POST /api/care-reminders`、`GET /api/wards/{ward}/care-reminders` |
| 病区管理 | 封闭管理（批量冻结/解封）、患者转科、护士限制外出、押金争议登记；**无人陪护患者巡查视图** | `POST /api/wards/...`、`GET /api/wards/{ward}/unattended-patients` |
| 违规 | 安保发现冒用（自动吊销证件）、夜间异常、多证共用、过期未退等；可确认/驳回处理 | `POST /api/violations` |
| 退证 | 正常退证/过期自动作废（定时任务）/违规吊销；门禁即时失效 | `POST /api/permits/{no}/return` |
| 归档 | 单条陪护记录聚合申请、证件、进出、替换、违规、完整事件流 | `GET /api/cases/{caseNo}/archive` |
| 看板 | 病区在院陪护人数、今日出入、夜间异常、违规频次、过期未退、退证平均耗时 | `GET /api/wards/{ward}/dashboard` |
| 病区通知 | 每次替换/冻结/退证/转科都推送病区通知，明确当前照护人 | `GET /api/wards/{ward}/notices` |

陪护证**不只是门禁凭证**：它联动病区感染控制（封闭即冻结）、护士巡查（外出限制/夜间异常）、
家属沟通（病区通知/押金争议）和患者实际照护状态（`current_escort_*` 快照）。

## 2. 本地启动

### 2.1 环境要求
- JDK 17、Maven 3.8+
- PostgreSQL 15（本仓库自带免 sudo 的本地实例脚本）
- Kafka 可选：默认 `ESCORT_KAFKA_ENABLED=false`，门禁同步走本地模拟；设为 true 时走真实 Kafka
- 或使用 Docker（一键启动应用+数据库，见 2.4）

### 2.2 启动步骤

```bash
# 1. 初始化并启动本地 PostgreSQL（端口 55432，库 escort/用户 escort/密码 escort）
./scripts/local-db.sh init          # 首次；reset 用 ./scripts/local-db.sh recreate

# 2. 构建
mvn -q -DskipTests package

# 3. 运行（默认连 localhost:55432，无需 Kafka）
java -jar target/escort-pass-service-1.0.0.jar
# 服务监听 http://localhost:8080，Flyway 自动建表并写入 3 个病区策略
```

健康检查：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`
接口文档：浏览器打开 `http://localhost:8080/swagger-ui.html`（OpenAPI JSON：`http://localhost:8080/v3/api-docs`）

连接真实 Kafka（可选）：

```bash
ESCORT_KAFKA_ENABLED=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 java -jar target/escort-pass-service-1.0.0.jar
```

### 2.4 Docker 一键启动（应用 + PostgreSQL，无需本机 JDK/数据库）

```bash
docker compose up --build        # 启动 postgres 与 app, Flyway 自动迁移, 服务在 http://localhost:8080
curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

只启动容器化依赖（本机已用 `mvn`/`java` 运行应用时）：

```bash
docker compose up -d postgres            # 仅 postgres:55432
docker compose --profile kafka up -d     # 如需真实 Kafka:9092
```

### 2.3 预置病区（测试账号/策略，V1 迁移写入）

| 病区 | 名称 | 允许探视 | 每患者陪护上限 | 夜间陪护需审批 |
|---|---|---|---|---|
| W3A | 内科三病区A | 08:00–20:00 | 1 | 是 |
| W5B | 外科五病区B | 08:00–20:00 | 1 | 是 |
| ICU1 | 重症监护一区 | 10:00–16:00 | 1 | 是 |

系统内角色（接口字段中直接使用的工号，无需登录）：
- 护士站审批人：`NURSE01` / `NURSE02` / `NURSE-HEAD`
- 病区管理员（封闭管理/转科）：`WARD-MGR`
- 安保巡查：`SEC-GUARD-07`
- 家属/陪护人：请求体中的姓名、身份证（6–24 位字母数字）、手机号

## 3. 端到端冒烟脚本

应用启动后执行（申请→审批→发证同步→进出→夜间异常→生病替换→病区通知→看板→退证→归档→关闭；
随后第 12–17 步演示**临时替换**：无人陪护→巡查提醒→到位核验交接→提醒转家属接手）：

```bash
./scripts/smoke.sh
```

## 4. 主要接口示例

```bash
# 家属申请
curl -s -X POST localhost:8080/api/applications -H 'Content-Type: application/json' -d '{
  "patientInpatientNo":"IP001","wardCode":"W3A","bedNo":"09-2",
  "escortName":"周敏","escortIdNo":"IDSMOKE01","escortPhone":"13811112222",
  "relationToPatient":"女儿","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-10-01T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-10T00:00:00Z",
  "needNightCare":false}'

# 护士站审批（needNightCare=true 时必须 approveNightCare=true）
curl -s -X POST localhost:8080/api/applications/EA-XXXXXXXX/decision -H 'Content-Type: application/json' \
  -d '{"approved":true,"decidedBy":"NURSE01","approveNightCare":false,"note":"病情需要"}'

# 扫码（token 为陪护证号 permitNo 或 qrToken；scannedIdNo 留空=持证人本人）
curl -s -X POST localhost:8080/api/access/scan -H 'Content-Type: application/json' \
  -d '{"token":"EP-XXXXXXXX","scannedIdNo":null,"direction":"IN","gateLocation":"W3A-东门闸机","scannedAt":"2026-09-27T02:00:00Z"}'

# 陪护人生病替换（autoApprove=true 护士站即时审批出新证）
curl -s -X POST localhost:8080/api/replacements -H 'Content-Type: application/json' -d '{
  "oldPermitNo":"EP-XXXXXXXX","reason":"ESCORT_SICK","detail":"发烧由家属接替",
  "escortName":"周母","escortIdNo":"IDSMOKENEW","escortPhone":"13833334444",
  "healthCommitment":true,"screeningType":"NAT","screeningValidUntil":"2026-10-01T00:00:00Z",
  "periodEnd":"2026-10-10T00:00:00Z","autoApprove":true,"decidedBy":"NURSE01"}'

# —— 陪护人临时替换（原陪护夜间发烧，旧人已离院、新人未到院）——
# 第1步：家属申请临时替换（旧证即时停用，患者进入短时无人陪护；relationToPatient 必填，健康承诺必须确认）
curl -s -X POST localhost:8080/api/replacements/temporary -H 'Content-Type: application/json' -d '{
  "oldPermitNo":"EP-XXXXXXXX","reason":"ESCORT_SICK","detail":"夜间发烧39度由妹妹接替",
  "escortName":"周兰","escortIdNo":"IDNEW0002","escortPhone":"13833334444",
  "relationToPatient":"妹妹","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-10-20T00:00:00Z","periodEnd":"2026-10-20T00:00:00Z"}'

# 护士站查看无人陪护患者并安排巡查
curl -s localhost:8080/api/wards/W3A/unattended-patients

# 无人陪护间隙临时创建看护提醒（FEEDING 进食 / TURNING 翻身 / NIGHT_WATCH 夜间观察）
curl -s -X POST localhost:8080/api/care-reminders -H 'Content-Type: application/json' -d '{
  "caseNo":"EC-XXXXXXXX","type":"NIGHT_WATCH","note":"每小时观察生命体征","createdBy":"NURSE01"}'
# 护士执行一次巡查照护（新陪护未到，提醒保持跟进）
curl -s -X POST localhost:8080/api/care-reminders/1/nurse-done -H 'Content-Type: application/json' \
  -d '{"operator":"NURSE01","note":"已巡视，生命体征平稳"}'

# 第2步：新陪护到院，护士站核验身份/健康承诺/亲属关系（三项均须 true），通过即发证并记录交接时间
curl -s -X POST localhost:8080/api/replacements/1/arrival -H 'Content-Type: application/json' -d '{
  "operator":"NURSE01","identityVerified":true,"healthCommitmentAccepted":true,"relationVerified":true,
  "note":"人证一致，无发热，确系患者妹妹"}'
# 若新陪护无法到院：POST /api/replacements/1/cancel （患者维持无人陪护，护士站继续巡查并可重新登记亲属）

# 病区封闭 / 解封（自动冻结/恢复该病区陪护证）
curl -s -X POST localhost:8080/api/wards/W3A/lockdown -H 'Content-Type: application/json' \
  -d '{"lockdown":true,"reason":"院感事件封闭48小时","operator":"WARD-MGR"}'

# 患者转科
curl -s -X POST localhost:8080/api/wards/cases/EC-XXXXXXXX/transfer -H 'Content-Type: application/json' \
  -d '{"toWardCode":"W5B","bedNo":"08-1","operator":"NURSE01","freezePermit":true}'

# 护士限制外出 / 安保上报冒用（冒用自动吊销）
curl -s -X POST localhost:8080/api/wards/permits/EP-XXXXXXXX/exit-restriction -H 'Content-Type: application/json' \
  -d '{"restricted":true,"reason":"患者病情不稳定需陪护在场","operator":"NURSE01"}'
curl -s -X POST localhost:8080/api/violations -H 'Content-Type: application/json' \
  -d '{"permitNo":"EP-XXXXXXXX","type":"SECURITY_IMPERSONATION","detail":"人证不符","actualUserIdNo":"IDFAKE","reportedBy":"SEC-GUARD-07"}'

# 退证 / 过期扫描 / 关闭记录
curl -s -X POST localhost:8080/api/permits/EP-XXXXXXXX/return -H 'Content-Type: application/json' \
  -d '{"returnedBy":"周母","channel":"WARD_DESK","note":"患者出院"}'
curl -s -X POST localhost:8080/api/permits/maintenance/expire-scan
curl -s -X POST localhost:8080/api/cases/EC-XXXXXXXX/close -H 'Content-Type: application/json' -d '{"operator":"NURSE01"}'

# 单条陪护记录完整归档 / 看板 / 病区通知
curl -s localhost:8080/api/cases/EC-XXXXXXXX/archive
curl -s 'localhost:8080/api/wards/W3A/dashboard?days=7'
curl -s 'localhost:8080/api/wards/W3A/notices?unreadOnly=true'
```

## 5. 测试与验证方式

```bash
mvn test        # 27 个集成测试, 使用 zonky 嵌入式 PostgreSQL + 嵌入式 Kafka, 无需任何外部依赖
```

测试覆盖：
- `EscortLifecycleTest`（7）：申请校验（健康承诺/筛查/夜间专项审批/上限）、审批发证三端同步、正常进出、退证门禁失效、驳回、无效证
- `ScanAnomalyTest`（5）：非允许时段进入拒绝并记夜间异常、批夜间陪护后可入但仍标记超时、人证不符判定未登记换人、2 小时时间窗多证共用、进出记录字段
- `SpecialScenariosTest`（9）：陪护人生病替换、亲属争抢裁决、病区封闭冻结/解封、患者转科双病区通知、安保冒用自动吊销、护士限制外出、过期未退自动作废、押金争议不影响证件、全环节同记录归档
- `TemporaryReplacementTest`（3）：临时替换全流程（旧证停用→患者无人陪护→护士站三类看护提醒→身份/健康承诺/亲属关系逐项核验→到位交接记录时间、无人陪护时长、提醒转家属接手）、健康承诺/亲属关系缺失被拒、取消后重新登记亲属接替
- `DashboardTest`（2）：在院陪护人数、出入次数、夜间异常、违规分类、退证平均耗时、待审批数、无人陪护患者数/待巡查提醒数
- `KafkaSyncTest`（1）：Kafka 模式下发证事件 → 模拟门禁/扫码点/安保消费 → `escort.access.ack` 回执 → `permit_sync` 三个目标全部 SYNCED

### 本次实际执行过的验证（2026-10-02）
1. `mvn test`：**27/27 全部通过**（zonky 嵌入式 PostgreSQL + 嵌入式 Kafka，无外部依赖）。
2. `./scripts/local-db.sh recreate` 启动本地 PostgreSQL 15 :55432，`mvn package && java -jar` 实际启动服务：
   `/actuator/health = UP`、`/swagger-ui/index.html` 返回 200、`/v3/api-docs` 可生成 OpenAPI 3 文档；
   Flyway 执行 V1/V2 迁移。
3. `./scripts/smoke.sh` 对运行中的服务跑通完整链路（含临时替换第 12–17 步）：
   临时替换申请后旧证 `REPLACED`、归档 `careStatus=UNATTENDED`、护士站 `unattended-patients` 可见原/新陪护信息；
   进食/翻身/夜间观察 3 条提醒创建，护士执行进食提醒为 `NURSE_DONE`；到位核验缺项被拒（400），三项核验通过后新证 `ACTIVE`、
   `careStatus=WITH_ESCORT`、记录 `newArrivedAt` 交接时间与 `gapMinutes` 无人陪护时长，3 条提醒全部 `FAMILY_TAKEN_OVER`
   （接手人/交接时间保留，进食提醒同时保留护士执行痕迹）；看板 `unattendedPatientCount=0`。
4. 容器化：已提供多阶段 `Dockerfile`（maven 构建 + temurin JRE 运行，含健康检查）与 `docker-compose.yml`
   （postgres + app，kafka 为可选 profile）。本工作区未安装 Docker 守护进程，镜像构建未在此环境执行；
   应用本身已通过上述 `java -jar` 实机启动与全链路冒烟验证。

## 6. 数据模型要点
- `escort_case`：聚合根，按患者本次住院一条；保存 `current_escort_*` 当前照护人快照，
  以及临时替换用 `care_status`（WITH_ESCORT/UNATTENDED）、`unattended_since`、`pending_replacement_id`。
- `escort_application` / `escort_permit`：申请（INITIAL/REPLACEMENT）与证件（ACTIVE/FROZEN/EXPIRED/RETURNED/REVOKED/REPLACED，乐观锁 version）。
- `replacement_record`：替换记录；临时替换额外保存身份/健康承诺/亲属关系三项核验结果，以及
  `old_left_at`（旧陪护离院）、`new_arrived_at`（新陪护到位交接时间）、`gap_minutes`（无人陪护时长）。
- `care_reminder`：无人陪护间隙的临时看护提醒（FEEDING/TURNING/NIGHT_WATCH；
  NURSE_PENDING→NURSE_DONE→FAMILY_TAKEN_OVER），家属接手时回填 `taken_over_at/taken_over_by`（=交接时间/接手人）。
- `scan_record`：每次进出，含 out_of_hours / escort_changed / multi_permit_shared / ward_lockdown 判定位。
- `violation_record` / `case_event` / `ward_notice` / `permit_sync`。
- Kafka 主题：`escort.permit.events`（发证/冻结/吊销等，key=permitNo）、`escort.access.ack`（下游同步回执）。

## 7. 脚本
- `scripts/local-db.sh init|start|stop|status|recreate`：本地免 sudo PostgreSQL 实例（数据目录 `.local-pg/`，已 gitignore）
- `scripts/smoke.sh`：对 http://localhost:8080 的端到端冒烟
