---
id: plan-00009-code-quality-gates-implementation
type: plan
role: main
status: open
parent: design-00007-code-quality-gates
---

# 代码质量门禁落地计划

落地 [[design-00007-code-quality-gates]]：domain/pure-tier 强制 90% 覆盖 + 90% 变异，全仓库 Spotless/PMD/CPD/SpotBugs，
经 `aipersimmon-ddd-build` provider 下发给脚手架。分阶段推进，每阶段可独立 PR、可回滚，避免一次性炸构建
（下游脚手架/样例在 HEAD 已有 RED 债，见 [[downstream-scaffolds-migration-debt]]）。

## 原则

- 每阶段先 **report-only** 拿基线，再 ratchet 到门禁；门禁绑 `verify`（`mvn test` 内环不受影响）。
- 库（`aipersimmon-ddd/*`，不继承 spring-boot-parent）与脚手架（继承 `aipersimmon-ddd-build`）走**同一套规则**，
  规则单一来源在 `aipersimmon-ddd-build-tools`。
- 每步跑对应 reactor 的 `mvn verify` 自证，diff 保持窄。

## 任务

### P0 — 基础设施骨架
- [ ] 新增 `aipersimmon-ddd-build-tools`（resource-only）：`pmd-ruleset.xml`、`spotbugs-exclude.xml`（先空/宽松），进 parent `<modules>` 与 BOM。
- [ ] 新增 `aipersimmon-ddd-build`（packaging=pom，parent=spring-boot-starter-parent）：质量插件 `pluginManagement` + 导入 `aipersimmon-ddd-bom`。**暂不**被任何脚手架采用（P8 才切）。
- [ ] `aipersimmon-ddd-parent` 增质量插件 `pluginManagement`（版本 + 引用 build-tools 规则），供库内 opt-in。
- 验收：`mvn -f aipersimmon-ddd/pom.xml install` 绿；两个新模块产物存在。

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
- [x] ruleset 已在 build-tools（复杂度/NPath/方法体量/GodClass/TooManyMethods + CPD）。
- [x] parent `<build><plugins>` 绑 `check`+`cpd-check` @ verify，`failOnViolation=false`（report-only）。
- 验收：库全模块出 PMD/CPD 报告；process-manager `verify` 有违规仍 BUILD SUCCESS。

### P4 — SpotBugs report-only（库全仓库）
- [x] spotbugs-maven-plugin + exclude filter（build-tools），parent `<build><plugins>` 绑 `check` @ verify，`failOnError=false`。
- 验收：库 26 模块出 spotbugsXml 报告，不 fail。

> **JDK 坑（重要）**：PMD 7.x / SpotBugs 无法解析 Java 26（class major 70）字节码。本机 `mvn` 默认跑在 Homebrew
> JDK 26，直接跑会报 `Unsupported class file major version 70`。**必须用 JDK 21 跑质量构建**（对齐 CI 的 temurin 21）：
> `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`。CI 已是 21，不受影响；后续可考虑加
> maven-toolchains 强制 21。

### P5 — 覆盖率 ratchet 到 90%（domain + pure tier）
- [ ] JaCoCo `check`：`LINE`/`BRANCH`/`METHOD` 各 ≥0.90；补测试到达标。
- 验收：pure tier + 脚手架 domain `verify` 在 90% 门禁下绿。

### P6 — 变异 ratchet 到 90%（domain + pure tier）
- [ ] PIT + pitest-junit5-plugin：`mutationThreshold`/`testStrengthThreshold`/`coverageThreshold` 三项 ≥90（D3）；补测试到达标。
- 验收：pure tier + 脚手架 domain `verify` 在 PIT 三阈值下绿。

### P7 — test-support testkit
- [ ] 新增 `aipersimmon-ddd-test-support`：单例容器 + `withReuse(true)`（MySQL/PG/Kafka/Redis）Base 类/Extension、属性接线帮手、process-manager 测试 schema 集中。
- [ ] 现有 3 处 Testcontainers（PG/MySQL/Redis 测试）改用 testkit。
- 验收：三处迁移后测试仍绿，容器不再逐类重启。

### P8 — 脚手架采用 provider + archetype 烘焙
- [ ] 三脚手架 root `<parent>` 切到 `aipersimmon-ddd-build`；`*-domain` 加 5 行 opt-in。
- [ ] archetype 生成模板同步（`*-domain` 默认带 opt-in）。
- [ ] `ci.yml`：库 `install`→`verify`，脚手架 `test`→`verify`。
- 验收：CI 全绿；新生成项目 domain 默认带 90%/90% 门禁。

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

（P5/P6 执行时回填达标后的覆盖率与变异分数。）
