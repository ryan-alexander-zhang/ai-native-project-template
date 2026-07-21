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

### P2 — JaCoCo report-only（domain + pure tier）
- [ ] `prepare-agent`@test + `report`@verify，作用于库 pure tier（core/application/integration/cqrs）+ 脚手架 `*-domain`（5 行 opt-in）。
- [ ] 收集真实基线（行/分支/方法），记录到本 plan。
- 验收：报告生成，基线可读；暂不 fail。

### P3 — PMD + CPD report-only（全仓库）
- [ ] ruleset 落 build-tools：认知/圈复杂度、NPath、方法体量、坏味道 + CPD。
- [ ] `check` 配 report-only（`failOnViolation=false`），阈值调到当前能过。
- 验收：全仓库 `verify` 出 PMD/CPD 报告，不 fail。

### P4 — SpotBugs report-only（全仓库）
- [ ] spotbugs-maven-plugin + exclude filter（build-tools），report-only。
- 验收：报告生成，不 fail。

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

（P2/P5/P6 执行时回填真实覆盖率与变异分数。）
