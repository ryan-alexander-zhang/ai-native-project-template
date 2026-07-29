---
id: issue-00106-an-empty-flyway-component-list-created-every-table
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# `flyway.components` 为空时代码全建表，而四份文档承诺不建：往生产库写 DDL 的两种说法

## 问题（现状，file:line 为证）

- **等级：Major**，但它是全库唯一一条**代码与文档各自自成道理、必须由人定契约**的矛盾——
  所以它是评审里唯一被挂起等方向的条目。
- 代码只有一行（旧 `AipersimmonFlywayMigrator.java:82`）：

```java
private boolean isSelected(String component) {
  return properties.getComponents().isEmpty() || properties.getComponents().contains(component);
}
```

  `components` 为空 ⇒ `isEmpty()` 为真 ⇒ **每个扫到的组件都被选中**。而默认值就是空（`List.of()`）。

- 文档里五处表述，四处与代码相反：

| 出处 | 原文 | 与代码 |
|---|---|---|
| `AipersimmonFlywayProperties` javadoc | "Empty means apply every component discovered on the classpath." | 一致 |
| `CONFIGURATION.md` | "**Empty creates nothing.**" | 相反 |
| `CONFIGURATION.md` | "`enabled=true` … applies only what `components` lists, so `true` is safe." | 相反 |
| `CONFIGURATION.md` | "…leave `components` empty"（作为"自己管 schema"的做法） | 相反，且**危险** |
| `CHOOSING-MODULES.md:108` | "listing nothing creates nothing" | 相反 |
| `aipersimmon-ddd-starter-jdbc/pom.xml:24` | "Bundling is not enabling" | 相反 |
| `aipersimmon-ddd-starter-mybatis-plus/pom.xml:96` | "bundling the module does not silently create tables" | 相反 |

- 实际后果：有人只想用 outbox，于是加了 `aipersimmon-ddd-starter-jdbc`（bundle 里带 flyway starter），
  按文档不配 `components`，指向生产库启动 → **outbox / inbox / process-manager / operation-log /
  web-store 五个组件的表全建**（十几张）+ 五张 history 表；而 `baseline-on-migrate` 默认 `true`，
  他那个已有表的非空 schema 会先被 baseline，不会因"库不干净"而被拒。

## 根因（第一性）

1. **观察 vs 期望**：期望"打包一个 bundle 不等于授权它改我的 schema"；
   实际"把 jar 放上 classpath 就是授权"。
2. **最小机制**：`isEmpty()` 被当成"未表态 ⇒ 全都要"。而"未表态"的正确读法是**未授权**——
   在一个**建表**动作上，缺省值决定的是"沉默是否等于同意"。
3. **为什么这条必须由人定**：两个方向都能自圆其说（全建 = 零配置可跑；不建 = 显式授权），
   而代价落在不同的人身上。这不是"代码错了"，是**契约没定**，所以只在评审里挂着、没有自行修。
4. **为什么此前"不建"是危险的、现在不是**：`issue-00103` 把两个 schema validator 的探针
   从 `SELECT 1` 改成按列探测。在那之前，"该建没建"的症状是运行期第一次写库才报错
   （甚至只在后台 worker 的 poll 里每轮失败一次）；之后是**启动即失败**，并报出迁移路径与该配的键。
   缺省值的安全性因此翻转了——这也是为什么两件事该在同一批里做。

## 决策

**空 = 什么都不建（opt-in）。** 由用户在 2026-07-29 定下，理由：

- 往数据库写 DDL 是对外的、难以撤销的动作，该显式选择；
- 四份文档（含两个 bundle pom 与 `CHOOSING-MODULES.md`）已经这么承诺，
  使用者读到的就是这个契约，代码是那个孤例；
- 参考脚手架 `multi-module` 本来就显式列了五个组件——它从未依赖"空 = 全建"，
  说明作者写的时候按的也是这个读法；
- 漏配的代价现在是一条响亮的启动失败（见根因 4），而反方向的代价是**静默**的：
  生产 schema 凭空多出十几张表，没有任何一步会报错。

## 复现（test-first）

`AipersimmonFlywayAutoConfigurationTest`：

- `bundlingIsNotEnabling`（新增）：全部组件的迁移都在 classpath 上（正是 bundle 的形状），
  不配 `components` → 断言 `aipersimmon_outbox` / `aipersimmon_widget` 与它们的 history 表**都不存在**，
  且消费方自己的 Flyway 照常跑完（runner 没有短路它）。
- `appliesEachListedComponentIntoItsOwnHistoryTableAlongsideConsumerFlyway`（原
  `appliesComponentsAndCoexistsWithConsumerDefaultFlyway`）：原用例把"零配置全建"钉成了预期，
  现改为显式列出 `outbox,widget`，仍然覆盖"每个组件一张独立 history 表、不侵占消费方的默认表"。
- `aComponentThatShipsNoMigrationsIsSkippedWithoutFailingStartup`（新增）：拼错的名字只 WARN 并跳过。

## 修复

1. `isSelected` 去掉 `isEmpty() ||`。
2. 空列表时**早返回并 INFO 说明**：报出该 vendor 在 classpath 上发现了哪些组件、该配哪个键、
   或者自己拿 `classpath:aipersimmon/db/migration/<component>/<vendor>/V*.sql` 去跑。
   沉默会让人以为 runner 坏了。
3. 列了但 classpath 上没有该组件迁移的名字 → **WARN 并跳过**，不 fail：
   runner 分不清这是拼写错误还是分阶段上线，而"拼错一个字母就拒绝启动"是比警告更差的答案——
   真正拦住它的是该组件自己的 startup validator。
4. `AipersimmonFlywayProperties` 的类 javadoc 与 `components` javadoc 改成与其余文档一致
   （它此前是唯一说对了代码、却说错了契约的地方）。
5. `CONFIGURATION.md` 补一段 "Bundling is not enabling" + yaml 例子 + 漏配时的失败形态；
   把"自己管 schema 就把 components 留空"从**危险的建议**改成**正确的建议**（现在它真的什么都不建）。
6. 两个 bundle pom 的注释补上"empty by default"与漏配时由谁拦住。

## 验证结果

- 库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL 与全部质量门禁）：BUILD SUCCESS。
- 脚手架 `multi-module` 全量 `mvn verify`：BUILD SUCCESS——它本来就显式列了五个组件，行为不变，
  这本身就是"opt-in 不破坏既有正确用法"的证据。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 持久化第三条 Major，挂起待方向者）
- 起源：[[issue-00031-flyway-shared-schema-and-bundled-shedlock-table]]（每组件独立 history 表的取舍）
- 依赖：[[issue-00103-parked-input-replay-is-not-crash-safe]]（列级 schema 探测让 opt-in 的失败变响亮）
