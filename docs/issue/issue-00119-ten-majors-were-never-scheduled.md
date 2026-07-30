---
id: issue-00119-ten-majors-were-never-scheduled
type: issue
role: main
status: open
parent: report-00003-ddd-library-review-2026-07-29
---

# §2 的 Major 有 10 条从来没有被排过期

## 症状：报告有两份清单，而只有一份被执行

`report-00003` 里 **§2 Major**（发现了什么，按模块族罗列，无优先级标记）与
**§3 建议的修复顺序**（13 项，分「发布阻断 1-5」与「紧接其后 6-13」）**不是同一份清单**。

§3 挑走了 5 个 critical、整个 outbox 家族、架构层四项、process-manager 的 `withRetry` 一项。
剩下的——pm 的性能/保留/方言、CQRS 那三条、Web 那五条——**一条都没进去**。

而后续所有工作的单位一直是 §3 的编号。所以这 10 条不是被评估后搁置的，
**是从来没有人给它们排过期**。

## §3 从未交代被它漏掉的发现去哪了

通篇没有一句"以下若干条刻意延后，理由是 X"。分组标题「发布阻断」+「紧接其后」
**暗示**其余更靠后——但暗示不是决定，尤其当被暗示掉的东西里包含
"每轮轮询语法错误、effect 永不投递、且不 fail-fast"这种。

**一份评审如果既列发现又列顺序，就必须交代顺序没覆盖的发现去哪了。** 它没交代。
这是本条 issue 要修的第一件事。

## 而后续小结把这个缺陷放大了

收尾时的多处小结与记忆写着"报告 13 项全部处理完毕、§2/§4 遗留项也已清空"。
前半句对；**后半句不对**——它指的其实是 §2 里唯一标着"仍然开着"的那条
（MP `updateById`，见 [[issue-00115-clearing-a-field-never-reached-the-database]]），
却写成了整个 §2。那句话之后，这 10 条在协作里就不可见了，直到被专门问起才重新查出来。

## 逐条实测（全部回读代码核实，非文档结论）

| # | 条目 | 实测 | 证据 |
|---|---|---|---|
| 1 | pm：effect claim 随实例历史线性变慢 | **开着** | `JdbcProcessDialect:63` 的 `NOT EXISTS` 过滤 `b.status`，而索引是 `(instance_id, seq)` **不含 status** |
| 2 | pm：全局 `ORDER BY` 饿死长寿实例 | **开着** | 同上 `:73` `ORDER BY e.seq` 是**全局**排序；`seq` 每实例递增，故长寿实例的 seq 永远排在新实例之后 |
| 3 | pm：四张表零保留策略 | ~~开着~~ **已修** `issue-00122` | 按实例整体删除（删转移留实例会造出 runtime 拒绝回答的状态）；未投递的效果与 DEAD 工作都留下；默认关闭；V5 三方言索引 |
| 4 | pm：SKIP LOCKED claim 零真库覆盖 | **半陈旧** | effect claim 现有 `EffectRelayPostgresConcurrencyTest` / `EffectRelayMysqlConcurrencyTest`；**deadline claim 仍只跑 H2**——而 H2 走 `AtomicUpdateProcessDialect`，根本不是这条 SQL |
| 5 | pm：MariaDB 误判走 SKIP LOCKED | ~~开着~~ **已修** `issue-00120` | 查来历后发现**没有人声明过支持它**：全树只有三行别名，无迁移、无测试、无容器、无决策。三处一并删除并落到 fail-fast |
| 6 | CQRS：handler 构造注入 CommandBus 触发循环依赖 | ~~开着~~ **已修** `issue-00124` | handler/拦截器改首次派发时解析；**fail-fast 未交出**——bus 实现 `SmartInitializingSingleton`，所有单例造完后强制建一次索引，重复 handler 仍是启动失败 |
| 7 | CQRS：`domainEvents()` 承诺快照实为活视图 | ~~开着~~ **已修** `issue-00121` | 只改成快照会把 CME 换成静默丢事件，故新增 `drainDomainEvents()`（取走并清空一步完成）+ 对同聚合回写的拒绝 |
| 8 | CQRS：命令失败只 DEBUG | ~~开着~~ **已修** `issue-00121` | 业务拒绝 INFO 无栈、技术故障 WARN 带栈，判据用框架自己的 `DomainException`/`ApplicationException`，按类型匹配 |
| 9 | Web：5xx 冻结整个 TTL | **其实已修** | `IdempotencyFilter:189` ≥500 → `abandonQuietly`，是 [[issue-00101-idempotency-records-instead-of-claiming]] 顺带做掉的。**报告没划掉，是文档滞后** |
| 10 | Web：`JdbcRateLimiter` 窗口边界竞态 | ~~开着~~ **已修** `issue-00123` | 扫描留两个窗口余量（活着的计数器不再在删除范围内）+ 读取容忍零行并以本次增量作答 |
| 11 | Web：web-store 无清理 + 无 `expires_at` 索引 | **半修** | 索引 V3 已补（同样是 `issue-00101` 顺带）；**清理仍只在同一个 key 再次到来时触发**（`JdbcIdempotencyStore:54`），一次性 key 的行永不回收 |
| 12 | Web：兜底 500 不记日志 | ~~开着~~ **已修** `issue-00121` | 先 `log.error(..., ex)` 再作答；响应仍不透露内部 |
| 13 | Web：`ReplayProtectionFilter` 全量 + 认证前无上限缓冲 | **开着（有限定）** | 注册无 urlPatterns；`CachedBodyRequestWrapper:24` 是裸 `readAllBytes()`。**限定**：它是 opt-in 的（`replay.enabled=true` **且**存在 `RequestSignatureVerifier` bean），只在启用时才咬人——报告没写这个限定 |
| 14 | 架构：`-outbox` 契约模块带实现类 | **未改，但已被显式接受** | 白名单收了 slf4j + jackson-core；`DefaultFailureClassifier` / `LoggingOutboxDispatcher` / `RetryBackoff` / `DeadLetters` 仍在契约模块里 |

（14 行对应 §2 的 10 个条目——其中几条原文把多个缺陷写在一句里，这里拆开以便逐条排期。）

**汇总：11 条真开着，1 条已修但报告没划，2 条半修/陈旧。**

## 判断：这条线划得不对，而且原因不是判断

不是所有条目都该早做，但**至少三条与已做的项同级**：

- **MariaDB（#5）** ——启动即可判定的配置事故，每轮 claim 语法错误、effect 永不投递、**且不 fail-fast**。
  这与第 7 项修的"崩溃即 60 分钟停摆"是同一类：**投递整体停摆**。而且这条更糟——它不会自愈，也没有信号。
- **pm 零保留（#3）** ——成本无界增长。outbox 的 cleanup 是第 6 项顺手带的，
  pm 的同一个问题**一次都没被提起**。
  （**已修，`issue-00122`**。）
- **`domainEvents()`（#7）** ——这**就是** §0 点名的第二个系统性主题"文档承诺 A、代码行为 B"。
  那个主题号称已收口，而这条一直在名单上没动。
  （**已修，`issue-00121`**，连同 #8 #12 一起——三条是同一个毛病。）

**真正的原因大概率是惯性而非优先级**：第 6 项抽出 `outbox-engine` 之后，第 7、8、9、10 全落在 outbox 上，
注意力就留在那一带了；pm 的等价问题（保留、claim 性能、方言）没人再提。

而 `issue-00115` 恰恰证明这里没有原则——它同样不在 §3，只是因为收尾时被挑出来、用户同意了，就做了。
**是注意力，不是优先级。**

## 排期（本条 issue 的产出）

按"代价 ÷ 风险"排，每一条独立可交付；完成后各自开 issue 并回链本条。

| 序 | 条目 | 规模 | 为什么排这里 |
|---|---|---|---|
| ~~1~~ | ~~MariaDB 方言（#5）~~ **已完成** [[issue-00120-mariadb-was-support-nobody-had-declared]] | — | **动手前查来历，把修法整个换掉了**：不是"支持得不好"，是**从来没有人声明过支持**。版本探测那条因此划掉——三处别名一并删除，落到已有的 fail-fast |
| ~~2~~ | ~~`domainEvents()` 真快照（#7）、兜底 500 记日志（#12）、命令失败 DEBUG→WARN（#8）~~ **已完成** [[issue-00121-three-promises-that-did-not-match-their-behaviour]] | — | **"真快照"这个说法本身是错的**：只复制会把 CME 换成静默丢事件，真正的原语是 drain（取走并清空一步完成）。PIT 在测试写好前先把构建打回了 |
| ~~3~~ | ~~pm 四表保留策略（#3）~~ **已完成** [[issue-00122-the-four-process-tables-grew-forever]] | — | 顺带找出一个真问题：`ORDER BY updated_at` 没有平局打破，配上批量上限会**饿死平局后面的实例**——与报告给 effect claim 提的那条同形 |
| ~~4~~ | ~~`JdbcRateLimiter` 竞态（#10）~~ **已完成** [[issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in]] | — | **负向对照暴露了我自己的一条测试是空的**：它挂钩在 `query(...)`，而旧代码走 `queryForObject(...)`，于是对着它要排除的那个实现绿着通过 |
| ~~5~~ | ~~CommandBus 循环依赖（#6）~~ **已完成** [[issue-00124-the-rules-pointed-at-a-door-the-wiring-had-nailed-shut]] | — | **规则指着一扇门，装配把它钉死了**：禁止 handler 依赖 handler 的那条规则，给出的替代路径正是注入 bus。先复现再修；负向对照差点因 spotless 重排而失效 |
| 6 | `ReplayProtectionFilter` 缓冲上限 + 路径白名单（#13）、web-store 清理任务（#11） | 中 | 都是 opt-in 才咬人，但缓冲那条是 DoS 面 |
| 7 | deadline claim 的 PG/MySQL 覆盖（#4） | 中 | 现在只跑 H2，而 H2 根本不走那条 SQL |
| 8 | effect claim 索引与全局排序（#1 #2） | 大 | 需要重新设计 claim 谓词与索引，且要有真库的量化证据才好判断收益 |
| — | #9 / #14 | — | #9 已修，只需在报告上划掉；#14 已显式接受，白名单与理由都在，不再作为待办 |

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 全部条目 + §3 顺序的覆盖缺口）
- 顺带修好但报告未划掉的那条，与 web-store 索引：[[issue-00101-idempotency-records-instead-of-claiming]]
- 证明"是注意力不是优先级"的对照：[[issue-00115-clearing-a-field-never-reached-the-database]]
- §2 中已完成的最后一块（覆盖率）：[[issue-00117-the-advance-itself-had-no-tests]]、
  [[issue-00118-the-recovery-paths-had-no-tests]]
- 子：[[issue-00120-mariadb-was-support-nobody-had-declared]]（排期第 1 档，**已完成**）、
  [[issue-00121-three-promises-that-did-not-match-their-behaviour]]（排期第 2 档，**已完成**）、
  [[issue-00122-the-four-process-tables-grew-forever]]（排期第 3 档，**已完成**）、
  [[issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in]]（排期第 4 档，**已完成**）、
  [[issue-00124-the-rules-pointed-at-a-door-the-wiring-had-nailed-shut]]（排期第 5 档，**已完成**）
