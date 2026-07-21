---
id: plan-00009-code-quality-gates-implementation
type: plan
role: main
status: open
parent: design-00007-code-quality-gates
---

# 代码质量门禁落地计划

落地 [[design-00007-code-quality-gates]]：domain/pure-tier 强制 90% 覆盖 + 90% 变异，全仓库 Spotless/PMD/CPD/SpotBugs。
**无 provider parent、纯 BOM**（D1 修订，见 §P0/§P8）。分阶段推进，每阶段可独立 PR、可回滚，避免一次性炸构建
（下游脚手架/样例在 HEAD 已有 RED 债，见 [[downstream-scaffolds-migration-debt]]）。

## 原则

- 每阶段先 **report-only** 拿基线，再 ratchet 到门禁；门禁绑 `verify`（`mvn test` 内环不受影响）。
- 库与脚手架**都不继承 opinionated parent、版本靠 BOM**；质量 `pluginManagement` **各项目自声明**，规则单一来源在
  `aipersimmon-ddd-quality-config`（挂到 PMD/SpotBugs 插件 classpath，非普通依赖）。
- 每步跑对应 reactor 的 `mvn verify` 自证，diff 保持窄。

## 任务

### P0 — 基础设施骨架
- [x] 新增 `aipersimmon-ddd-quality-config`（resource-only）：`pmd-ruleset.xml`、`spotbugs-exclude.xml`，进 parent `<modules>`（**不进 BOM**——它是插件 classpath 产物，非普通依赖）。
- [x] `aipersimmon-ddd-parent` 增质量插件 `pluginManagement`（版本 + 引用 quality-config 规则），供库内 opt-in。
- ~~曾新增 `aipersimmon-ddd-build`（provider parent，extends spring-boot-starter-parent）~~ → **已删除**（2026-07-21）：违背
  "绝不继承 opinionated parent、版本靠 BOM"原则；改为无 provider、各项目自声明（见 design-00007 §四 / D1 修订）。
- 验收：库 reactor `validate`/`install` 绿。

### P1 — Spotless（首个门禁；范围=仅库，脚手架/样例延到 P8）
- [x] Spotless 3.6.0 + google-java-format 1.35.0（GOOGLE）写入 parent `pluginManagement`，`check` 绑 `verify`。
- [x] 库全模块 `mvn spotless:apply` 一次（467 文件，单独 commit `fff1fdc`）。脚手架/样例的 apply 随 P8 provider 采用一起做。
- [x] 库 parent `<build><plugins>` 激活 `spotless:check @ verify`（core `verify` 已验证触发）。
- [x] 填 `DEVELOPMENT.md` 的命令 + 写入"提交前跑完整 `spotless:apply`，勿用 `-DspotlessFiles`"坑。
- 验收：库 `spotless:check` 全绿；core `verify` 触发 check 且 BUILD SUCCESS。（脚手架/样例格式门禁 P8。）

### P2 — JaCoCo report-only（库 pure tier；脚手架 domain 延到 P8）
- [x] `prepare-agent`@test + `report`@verify，作用于库 pure tier（core/application/integration/cqrs 各加 5 行 opt-in）。
- [x] 收集真实基线（见下）。脚手架 `*-domain` 随 P8 一起接。
- 验收：报告生成、基线可读、不 fail。`mvn verify` 四模块 BUILD SUCCESS。

### P3 — PMD + CPD report-only（库全仓库）
- [x] ruleset 已在 quality-config（复杂度/NPath/方法体量/GodClass/TooManyMethods + CPD）。
- [x] parent `<build><plugins>` 绑 `check`+`cpd-check` @ verify，`failOnViolation=false`（report-only）。
- 验收：库全模块出 PMD/CPD 报告；process-manager `verify` 有违规仍 BUILD SUCCESS。

### P4 — SpotBugs report-only（库全仓库）
- [x] spotbugs-maven-plugin + exclude filter（quality-config），parent `<build><plugins>` 绑 `check` @ verify，`failOnError=false`。
- 验收：库 26 模块出 spotbugsXml 报告，不 fail。

> **JDK 坑（重要）**：PMD 7.x / SpotBugs 无法解析 Java 26（class major 70）字节码。本机 `mvn` 默认跑在 Homebrew
> JDK 26，直接跑会报 `Unsupported class file major version 70`。**必须用 JDK 21 跑质量构建**（对齐 CI 的 temurin 21）：
> `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`。CI 已是 21，不受影响；后续可考虑加
> maven-toolchains 强制 21。

### P5 — 覆盖率 ratchet 到 90%（库 pure tier）
- [x] JaCoCo `check`（`LINE`/`BRANCH`/`METHOD` ≥0.90）逐模块 opt-in（core/application/integration/cqrs 各自 `<build><plugins>` 声明 check execution）。
- [x] 补测试到达标（见下基线）。
- 验收：四模块联合 `verify` BUILD SUCCESS（JDK 21）。脚手架 `*-domain` 的门禁随 P8 落地。

### P6 — 变异 ratchet 到 90%（库 pure tier）
- [x] PIT + pitest-junit5-plugin：`mutationThreshold`/`testStrengthThreshold`/`coverageThreshold` 三项 ≥90（D3），逐模块 `mutationCoverage@verify`。
- [x] 补测试杀变异体到达标。
- 验收：四模块 PIT 三阈值全过。

> 说明：check + PIT 的 execution 目前**逐模块**声明（4 份），因为门禁只对达标模块开；四者稳定后可上移到父 `pluginManagement` 统一。脚手架 `*-domain` 的 90%/90% 随 P8 provider 采用一起接。
> 质量构建须用 JDK 21（PMD/SpotBugs/PIT 不支持 Java 26 字节码）。

### P7 — test-support testkit
- [x] 新增 `aipersimmon-ddd-test-support`（按 Testcontainers + Spring Boot 最佳实践）：
  - Spring Boot 测试用 `@ServiceConnection` 的 `@TestConfiguration`（`RedisServiceConnection` 需 `name="redis"`，`PostgresServiceConnection`/`MySqlServiceConnection`）——取代手写 `@DynamicPropertySource`。
  - 非 Spring 的裸 JDBC 测试用 singleton-container pattern（`SharedContainers`，`withReuse(true)`）+ `TestDataSources`（反射加载驱动，消除样板）+ `DockerAvailable` 守卫。
  - `ContainerImages` 统一镜像版本：**Postgres 对齐 compose 的 `postgres:18.1`**、MySQL 8.0、Redis 7-alpine。
- [x] 3 处迁移：PG 并发、MySQL 并发、MySQL deadline（→ SharedContainers+TestDataSources）、Redis（→ `@Import(RedisServiceConnection)`）。
- 验收：JDK 21 下真实容器 BUILD SUCCESS；MySQL 单例复用生效（同模块两测试共享一容器，第二个 0.7s 不再重启）。
- 注：process-manager 测试 schema 仍由各测试的 `ResourceDatabasePopulator` 加载（集中到 testkit 会引入对 process-manager-jdbc 的反向依赖，且牵涉 schema-copies 债，留作后续）。

### P8 — 脚手架 BOM-only + domain 门禁（仅 multi-module）
范围收窄到 **multi-module**（modulith/microservice 暂不动）。**不使用 provider parent**（D1 修订）。
- [x] **P8a 结构改造**：multi-module root 去掉 `spring-boot-starter-parent`，改为无 parent、纯 BOM
  （import `spring-boot-dependencies` + `mybatis-plus-bom` + `aipersimmon-ddd-bom`），显式补 `maven.compiler.release=21`
  / `-parameters` / UTF-8 / `spring-boot-maven-plugin` repackage 绑定。子模块仍以 `multi-module` 为 reactor parent。
  验收：`mvn -DskipTests package` 全 20 模块 BUILD SUCCESS，`start` repackage 出可运行 jar。✅
- [x] **P8b-1 结构接线**：multi-module root 自声明 Spotless + 质量 pluginManagement（引用 quality-config 规则）；激活
  Spotless check + PMD/CPD + SpotBugs（report-only）；`spotless:apply` 全脚手架（155 文件）。
- [x] **P8b-2 domain 门禁 + 补测试**：三个 `*-domain` 各加 5 行 opt-in + 补测试到 90%/90%：
  - payment-domain：JaCoCo 90% ✅ / PIT 6/6 100%
  - inventory-domain：JaCoCo 90% ✅ / PIT 25/26 96%
  - ordering-domain：JaCoCo 90% ✅ / PIT 88/89 99%（唯一存活为 equivalent mutant：approveReview 显式 guard 后的冗余 check）
- [x] `ci.yml`：multi-module 步 `test`→`verify`（跑全套门禁）；modulith/microservice 仍 `test`（P8 范围仅 multi-module）。
- [x] archetype 同步：multi-module 脚手架即 archetype 源，改动随之生效，无独立生成步骤。
- 验收：multi-module `verify` 门禁全绿。

## 落地基线记录

### P2 覆盖率基线（2026-07-21，pure tier，report-only）

| 模块 | LINE | BRANCH | METHOD | 备注 |
| --- | --- | --- | --- | --- |
| aipersimmon-ddd-core | 79.5% | 100% | 76.2% | 6 测试 |
| aipersimmon-ddd-integration | 60.0% | 66.7% | 41.7% | 11 测试 |
| aipersimmon-ddd-cqrs | 53.3% | 50.0% | 62.5% | 7 测试 |
| aipersimmon-ddd-application | 0% | 0% | 0% | **10 主类、0 测试** |

结论：pure tier 全部低于 90% 门禁，`application` 完全无测试。**P5（覆盖率）/ P6（变异）到 90% 需要大量补测试**，
是本计划最重的一步，需单独安排。P3/P4（PMD/CPD/SpotBugs report-only）可先行，不依赖补测试。

### P3/P4 静态分析基线（2026-07-21，库全仓库，report-only，JDK 21）

| 工具 | 总数 | 集中点 |
| --- | --- | --- |
| PMD（复杂度/设计） | 36 | process-manager-jdbc 17、其 starter 7、web-spring 4、messaging-kafka 3、archunit 2 |
| CPD（重复块） | 2 | — |
| SpotBugs（字节码缺陷） | 90 | process-manager-jdbc 30、web-spring 15、starter 8、outbox-jdbc 6、messaging-kafka 5 |

转 gate 前需先消化这些（或按规则调阈值/加 exclude）。缺陷集中在 jdbc/web 基础设施层，pure tier 基本干净。

### P5/P6 达标结果（2026-07-21，库 pure tier，JDK 21，门禁已开）

| 模块 | JaCoCo（L/B/M ≥90%） | PIT 变异 | 备注 |
| --- | --- | --- | --- |
| aipersimmon-ddd-core | ✅ | 11/11 杀灭，strength 100% | +3 测试类，强化 TransitionsTest 杀 builder 变异体 |
| aipersimmon-ddd-integration | ✅ | 14/15，strength 93% | +catalog/异常测试，补 envelope 分支 |
| aipersimmon-ddd-cqrs | ✅ | 4/4，strength 100% | +CommandContext/defaults；修正 UnitOfWork 重载假测试 |
| aipersimmon-ddd-application | ✅ | 4/4，strength 100% | 从零补齐异常 + DomainEvents/IntegrationEvents 默认方法 |

四模块联合 `verify` BUILD SUCCESS。annotation/marker interface 类无可变异代码，不计入。
