---
id: report-00003-ddd-library-review-2026-07-29
type: report
role: main
status: active
parent: report-00001-ddd-framework-review
---

对 `aipersimmon-ddd/` 库树（47 个模块，约 2.8 万行 main 代码，725 个 Java 文件）的第二次全量评审：
是否忠实于 DDD、是否生产可用、有哪些 Bug 与性能问题、哪些地方该重构或重写。

评审基线：分支 `lang/java/ddd`，`7951ef6`。方法：七路并行精读，每路覆盖一个模块族的全部 main + test
源码与 SQL 资源，逐条要求 `file:line` 证据与可复现的失败场景，禁止臆测；核心结论由主控回读代码抽查核实。

判定基准：经典 DDD（Evans / Vernon）+ `docs/decision` 既有 19 条决策。前提：尚未上线，**不计成本可以重写，
不考虑兼容性**——所以本报告的建议优先"结构上做对"，而不是"最小改动绕过"。

---

## 0. 总体判断

**DDD 符合度：高。** 战术层比多数自研 DDD 框架忠实：纯净层（core / cqrs / integration / application /
tenancy / observability / web / 各组件契约）经字节码规则验证零 Spring 依赖；聚合按标识相等且 `equals`
被 `final` 锁死；事件在聚合内部记录；元数据不进命令载荷；ORM 注解只落在 DO 行对象上，`restoreVersion`
为 protected 只对重建工厂开放；`Invariant`（断言式、强制 ErrorCode）与 `Specification`（决策式、可组合）
的切分有水准。

**生产可用性：尚不可用（评审时）。** 核心正确性扎实，**短板在运维层与降级路径**。缺陷高度收敛于两个系统性主题，
不是散落的技术债——**两个主题现已全部收口**（§3 的五项发布阻断项均已完成），剩下的是第 6 项起的结构性工作：

1. **租户体系全线 fail-open。** `orElse(Tenants.ROOT.value())` 散布 14 处 / 9 个模块，
   `MissingTenantException` 是死代码，`MissingTenantPolicy` 只在 HTTP 边缘被咨询。丢绑定即静默读写
   `__root__` 哨兵桶。→ 已修，见 `issue-00099`。
2. **静默降级。** 反复出现"文档承诺 A、代码行为 B、且无任何日志"：无事务管理器时命令裸跑；消费方自带
   `MybatisPlusInterceptor` 时乐观锁整体消失；Flyway 组件默认全建表却在 bundle pom 里写着"打包不等于建表"。
   同一份代码对缺失 `IdGenerator` 是启动即失败的——风险姿态自相矛盾。
   → **已修**：`issue-00107`（四处断言/守卫）与 `issue-00106`（Flyway 契约）。姿态统一到 `IdGenerator` 那一侧。

**核实为扎实、不要动的部分：** outbox 的原子写入 / 诚实的 at-least-once / 按聚合有序的回退 `NOT EXISTS`
方案（比多数生产级 outbox 更讲究）/ 死信迁移原子且存储故障能自愈 / `issue-00044` 修复验证无误 /
两后端 SQL 逐条比对无漂移；乐观锁协议（版本 0 → insert，0 行 → 拒绝且不发事件、不进版本）；
UUIDv7 六个铸造点一致且 fail-loud；process-manager 的精确一次推进、确定性 effect/deadline id、
deadline 代际栅栏、租约 fencing；operation-log 的 outcome×completion 模型与跨库幂等收敛。

---

## 1. Critical（5 条，均已回读代码核实）

| # | 位置 | 缺陷 | 状态 |
|---|---|---|---|
| C1 | `IdempotencyFilter` + `AipersimmonDddWebAutoConfiguration:173` | 幂等过滤器注册在 `HIGHEST_PRECEDENCE+40`（Spring Security 链 order −100 **之前**），存储键仅 `tenant + key`，不含认证主体/方法/路径。攻击者带受害者的 `Idempotency-Key` 与租户值发任意请求即可取回其已存响应，全程绕过安全链 | **已修** `issue-00101` |
| C2 | `IdempotencyFilter:83-95` | "只执行一次"不成立：时序为 `find`(miss) → `doFilter`(副作用提交) → `saveIfAbsent`，无执行前 claim。两个并发首次请求都执行；`saveIfAbsent` 只决定谁的**响应**被存 → 重复扣款 | **已修** `issue-00101` |
| C3 | `FailedOperationLogInterceptor:89` | 以"存在活动事务"判定自己是嵌套子命令而让根去记录；但若最外层 `commandBus.send()` 本身在 `@Transactional` 内被调用（消费方极常见），根本不存在记录者 → 整条流程的失败审计被静默跳过，无日志、无 `failureRecordLost` 指标 | **已修** `issue-00102` |
| C4 | `ProcessOperations:80-118,180-200` | redrive 先提交 resume 事务，再在**任何事务之外**重放 parked 输入且无持久化待重放标记。注释声称 `handle()` never throws，实际可抛四类异常。崩溃/抛异常落在提交之后 → 实例停在 RUNNING、parked 输入永久丢失（broker 已 ack），全树无任何扫描去捡 | **已修** `issue-00103` |
| C5 | `DefaultProcessRuntime:426-540` + `ProcessOutcomeWriter` | 终态决策从不取消未决 deadline（含每次 start 都武装的 max-lifetime backstop）；claim 查询要求 `lifecycle IN ('RUNNING','COMPENSATING')` 故永不可领取，`cancelPending` 唯一调用点是操作员 cancel → 健康检查永久 DEGRADED、年龄单调增长、轮询扫描无界膨胀 | **已修** `issue-00104` |

---

## 2. Major（按模块族）

**Outbox / 事件管道**
- ~~崩溃即最长 60 分钟全线停摆~~ → **已修** `issue-00108`：互斥从调度移到行（`lease_owner`/`lease_token`/
  `lease_until`），relay 的 `@SchedulerLock` 撤掉，所有实例并发轮询各领互不相交的行；崩溃代价缩到
  "死节点手里那几行等一个租约（默认 PT5M）"，其余实例全程照常投递。**撤锁不是可选项**：只加行级 claim
  而留着 60 分钟的调度锁，崩溃后依然没有实例在轮询，压根走不到 claim 查询。有序性随之从批内记账改为
  "只有聚合队头可领"的存储谓词（活在单节点内存里的保证撑不住并发 poller），轮询自带"半个租约"的时间预算，
  于是 Kafka 启动守卫的算式里 `batch-size` 整项消失——这就是解开耦合的具体形态。顺带：投递吞吐现在随实例数扩展。
- ~~零 Micrometer 指标~~ → **已修** `issue-00110`：照 process-manager 的形状分家——push 钩子
  `OutboxObserver`（claim/dispatch 延迟、按 reason 打标的 `dead.lettered`、`mark.sent.failures`、`released`）
  + pull 读 `OutboxBacklog`（gauge `pending` / `oldest.pending.age`）。两条 gauge 出自**一次扫描**
  （端口只加一个 `pendingBacklog`），且「等待中」与 claim 的存活判据是同一条谓词。
  **「是否丢过消息」按事件告警**（`dead.lettered` counter，所有 reason 启动即注册好，仪表盘有 0 值曲线可告警），
  而不是看死信表深度——一条被重放的死信会让深度回落、告警随之消失。**刻意不加健康检查**：
  一个连不上 broker 的 relay 不是有病的实例，而本次评审的 C5 正是一个卡在 DEGRADED 的健康检查。
- ~~吞吐上限约 100 msg/s~~ → **已修** `issue-00111`：一轮 poll 的代价从**往返之和**降到**一次往返**。
  「交出去」与「等回执」拆成两件事（`OutboxDispatcher.beginDispatch` → `InFlightDispatch`），relay 把整批
  交给传输再逐个等，确认下来的行**一条语句**记账（`markSent` 收 id 列表）。默认实现仍是同步 `dispatch`，
  自定义传输零改动；producer 自带的批处理这才第一次有东西可批。
  **报告要求的「按序等 + 首个失败 fail-fast」被证明既多余又有害**：队头 claim（第 7 项）已使一批 claim 出来的行
  两两不同 subject，批内根本没有需要保序的两条消息；而 fail-fast 会丢下已交给 broker 的 send 不等，凭空造重复。
  `sendTimeout` 起算点改为**交出去那一刻**，整批停摆只花一个 timeout，第 7 项的租约算式原样成立。
  追踪侧顺带补上 `Scope.detach()`（离开当前线程但不结束 span），否则一批重叠的 publish span 要么撒谎要么错乱。
- ~~**静默丢失**：outbox 行不存目的地，路由在派发时按当前注解决定~~ → **已修** `issue-00109`：
  目的地在**写入事务里**解析并落成 `destination` 列，派发读列不查表。于是主场景从「失败」变成
  「根本不会发生」——注解在版本升级里被删掉，已写入的行照样送到原目的地。新端口 `EventDestinations`
  （`ExternalizedRoutes` 实现之），`RoutingOutboxDispatcher` 因此不再需要路由表（构造参数 3→2）。
  relay 另加一条不变式：带目的地的行不得交给 `reachesExternalTargets()==false` 的 dispatcher，
  归为**瞬态**失败（传输缺失常是滚动发布的时间窗而非判决），耗尽尝试后进死信。
  **死信表同样加这一列**——`replay` 把行拷回 outbox，否则重放会把外发事件复活成本地投递，同一个 bug 换个入口。
- ~~DLT 固定源分区号~~ → **已修** `issue-00111`（与上一条同批）：改为不点名分区。
  **比本报告原先的判断窄一些**：Spring Kafka 自带 `verifyPartition=true` 会先问 broker 该分区在不在，
  常见情况救得回来——但救不了元数据拿不到的情况，而那恰恰就是 DLT 主题压根不存在的时候；
  且这个保险每条死信要付一次**阻塞式**元数据查询。不点名分区把这次查询也省了，
  而同聚合死信共位照旧（recoverer 抄源记录的 key，共位一直是 key 的功劳）。

**Process Manager**
- ~~`withRetry` 在加入外层事务时失效~~ / ~~并发首次 start 漏 `DuplicateKeyException` 映射~~ →
  **已修** `issue-00105`：joined 事务下只尝试一次并让冲突上抛（重试的前提是"失败只作废这一次尝试"，
  加入别人的事务时该前提不成立），两个 instance store 都补上唯一键映射。
- Effect claim 队头 `NOT EXISTS` 随实例历史线性变慢（索引 `(instance_id, seq)` 不含 status）；全局 `ORDER BY e.seq`
  系统性饿死长寿实例；四张表**全无保留/清理策略**——成本无界增长。
- SKIP LOCKED 的 deadline claim SQL 在两个真实数据库上零测试覆盖；MariaDB 被识别为 mysql 走 SKIP LOCKED，
  但 10.6 之前不支持 → 每轮语法错误、effect 永不投递且不 fail-fast。

**持久化（写路径核心是全框架最扎实的部分，以下是其降级路径）**
- ~~`MybatisPlusAggregateRepository` 用 `updateById`，MP 默认 `NOT_NULL` 策略把 null 列从 SET 剔除~~
  → **已修** `issue-00115`：改走 `update(entity, wrapper)`——wrapper 带被清空的列与 id 谓词，
  实体供其余列**并且**继续做乐观锁拦截器的钩子（已回读拦截器源码确认它对 `update(et, ew)` 与
  `updateById` 一视同仁），所以 `issue-00107` 那道版本见证断言一个字不用改。
  强制写哪些列**读 MP 自己的元数据**而不是只处理默认策略——两个边界咬的方向相反：
  配了 `ALWAYS` 的列再写一遍会生成 `SET c = ?, c = null`（MySQL 接受、**PostgreSQL 拒绝**），
  配了 `NEVER` 的列强制清空等于按框架臆断销毁数据。
  **必须打真库验证**：缺陷在生成的 SQL 里，本模块原有的 mock mapper 测试全程一直是绿的；
  负向对照（换回 `updateById`）按预期失败。脚手架对真实 PG/MySQL 78 组测试全绿。
- ~~消费方自带 `MybatisPlusInterceptor` 时乐观锁拦截器整体消失~~ → **已修** `issue-00107`：
  按预想的三行办——拦截器会把自增版本写回实体，故 `updateById` 成功后断言 `version == expected + 1`；
  该断言顺带覆盖"行对象漏标 `@Version`"这条同样静默的路径。
- ~~Flyway 组件默认 apply-all 与两个 bundle pom 宣称的"打包不等于建表"直接矛盾~~ →
  **已修** `issue-00106`：用户定为「空 = 什么都不建」（opt-in）。代码是四份文档里的孤例；
  而 `issue-00103` 把 schema 探针改成列级之后，漏配的代价从"运行期第一次写库才报错"
  变成"启动即失败并报出迁移路径"，缺省值的安全性因此翻转。

**CQRS / 核心**
- handler 构造注入 `CommandBus` 会启动循环依赖（`AipersimmonDddCqrsAutoConfiguration:67-73` 在工厂方法内
  `handlers.stream().toList()` 提前实例化全部 handler），而这正是框架文档推荐的子命令派发写法。
- `domainEvents()` javadoc 承诺快照，实际返回活视图 → 同步监听器回写同一聚合时 `ConcurrentModificationException`。
- ~~无 `PlatformTransactionManager` 时 `UnitOfWork` 与事务拦截器静默 back-off~~ → **已修** `issue-00107`：
  默认拒绝启动 + FailureAnalyzer 报告，`aipersimmon.ddd.cqrs.transaction.required=false` 是显式逃生舱（每次启动 WARN）。
- 命令失败只 DEBUG 记录，默认 INFO 生产配置下失败命令一行日志都没有。

**Web / 可观测性**
- 错误响应被存下并在整个 TTL 内重放（5xx 冻结 24 小时，客户端重试永远拿到失败）。
- `JdbcRateLimiter` 窗口边界竞态抛 `EmptyResultDataAccessException` → 间歇 500。
- web-store JDBC 两张表无清理路径且无 `expires_at` 索引 → 无界增长（一次性 key 的"改写时顺带清理"永不触发）。
- 兜底 500 处理器不记日志（`AipersimmonDddWebExceptionHandler:198-202`）→ 生产 NPE 无栈可查。
- `ReplayProtectionFilter` 默认作用于全部请求且认证前无上限缓冲请求体（探针被 401 打死 + 内存 DoS 面）。

**架构层**
- ~~outbox 家族没有 engine 层~~ → **已修** `decision-00020`：新增 `aipersimmon-ddd-outbox-engine`
  承载 writer/relay/调度/清理与共享装配，两个后端只剩 `OutboxStore` 适配器 + 死信 + ShedLock provider
  （各约 90 行）。被收拢的正是那三条来自独立 issue 的判断（按聚合顺序、mark-sent 不计重试预算、
  死信搬移失败不计尝试）。**未消除**的一处重复已写在端口 javadoc 上：`findDue` 的顺序谓词仍是两份 SQL。
- ~~BOM 继承 parent~~ → **已修** `issue-00112`：去掉 `<parent>`，被管理坐标 **1626 → 72**（probe 工程实测）。
  留下的三处第三方再导出有明确判据——**本库自己的代码在别的版本上就是不工作**（OTel core 线不对则 starter
  启动即 `NoClassDefFoundError`；springdoc/swagger 无人管理且注解 jar 是 `provided` 不传递）——
  与"Jackson 恰好是 2.19.4"性质不同。代价是四个版本号写两遍，用 `BomExportsOnlyItsOwnModulesTest`
  三条断言锁住（无 parent、白名单外无第三方坐标、字面量与 parent 属性一致）。
  消费方零改动：脚手架本就自己 import Boot/MP 的 BOM，那条"aipersimmon BOM 排在 Boot BOM 之前"的注释
  现在**只**为 OTel 服务，不再顺带决定 1600 个坐标的归属。
  **本报告这条的受害者判断需要更正**：不是"压过对方选的 Boot 版本"泛指所有消费方——Maven 里继承来的
  dependencyManagement 优先于 import 的 BOM，所以用 `spring-boot-starter-parent` 的应用从未受影响；
  真正被压掉的是**靠 import BOM 管版本**的应用（先 import 者赢，而本库要求自己排在 Boot BOM 之前），
  也就是本库推荐、脚手架采用的那种装配。实测见 issue。
  顺带修了 README 快速上手：那段示例的 `mybatis-plus-spring-boot3-starter` 没写版本号，靠的正是这次堵掉的泄漏。
- ~~两个 engine 零直接测试且在所有门禁之外~~ → **部分已修** `issue-00113`：
  `-outbox-engine` 补内存 store + 39 例 + 门禁（运行期包 90% line / 80% branch + PIT）；
  `-process-manager-engine` 补 24 例（重试排期 / 积压读 / 持久化约定）+ 同级门禁。
  **PIT 立刻赚回成本**：第一次跑就找出三条行覆盖率称为"已覆盖"的未测路径，其中一条是第 10 项几天前刚写的。
  PIT 阈值定 85 而非 90 并在 pom 写明理由——剩下的变异体是日志守卫、延迟算术、换条路径落到同一处的私有 helper，
  为杀它们写的断言抬高数字而什么也不保护。
  **仍未覆盖且已在 pom 里点名**：`-process-manager-engine` 的 store 支撑部分约 1300 行
  （推理同时跨四个 store 端口，需要四个 honor claim 语义的内存实现）——这是本项剩下的一半。
- ~~库自称契约模块无框架依赖，但无人检查~~ → **已修** `issue-00113`：
  `ContractModulesCarryNoFrameworkTest` 按**字节码**跨 reactor 检查 11 个契约模块
  （pom 说声明了什么，字节码说实际够到了什么，落到消费方 classpath 上的是后者）。
  用白名单而非禁用名单，且第二条断言防"空规则恒真"。
- ~~core 内部违反自己的"一个概念一个名字"~~ → **已修** `issue-00114`：判据是**留承重的一边**，
  两个案例方向相反正说明它是判据而非偏好。建筑块角色留**注解**（它是唯一覆盖全部六个角色的一套；
  两个接口只是局部影子，且 core 外零使用、仓储基类都绑 `AbstractAggregateRoot`）；
  领域事件留**接口**（`registerEvent(DomainEvent)` 与 `List<DomainEvent>` 里注解出现不了）。
  消费方迁移成本为 0——被删的三个类型库外使用次数都是 0。
- ~~6 个模块编译依赖 `-id-spring-boot-starter` 却零 import~~ → **已修** `issue-00114`：
  改为 `runtime`（它们编译针对的 `IdGenerator` SPI 在 **core**；需要的是 bean 在运行时存在，
  否则 id 静默退回 `UUID.randomUUID()`）。已实测 `runtime` 照样传递到消费方 classpath。
  两个 outbox 后端那条直接删除——engine 已经带着它。
- **"47 个 pom 过度碎片化 / 收敛到 20"这条被驳回**（`issue-00114` §4）：48 个按角色是
  13 后端 + 12 装配 + 12 契约 + 4 工具 + 4 打包束 + 3 engine。砍到 20 只有两条路，
  **两条都被本次评审自己新加的门禁禁止**——合并 jdbc/mybatis 后端会让只用 JDBC 的应用背上 MyBatis-Plus
  （违反 design-00001 反复写下的"自选恰好一个存储 starter"）；合并契约与装配会让 Spring 进契约模块，
  而第 12 项新加的 `ContractModulesCarryNoFrameworkTest` 会按**字节码**让构建失败。
  且计数所描述的问题**4 个打包束早已解决**：消费方按 README 加一个依赖，从不需要认识另外 47 个。
  模块数是**发布粒度**，不是消费方的认知负担。唯一真候选是 `-inbox`（42 行）并入 `-integration`，
  收益 48→47、代价是一个已发布坐标消失——**留给用户决定**。
- ~~`ModuleNamingChecks` 用正则解析 pom~~ → **已修** `issue-00114`：改用 JDK 自带 DOM。
  核实后两条指控只有一条成立：`<dependencyManagement>` 其实已被 `replaceFirst` 处理，
  **注释掉的依赖那条是真的**（按文本读，被注释掉的 `<dependency>` 与活的一模一样），
  新增的这一例在旧实现上按预期失败。理由不只是正确性：一条会报告构建实际没做的事的规则，
  会教会人不再相信它。
  `aipersimmon-ddd-outbox` 这个"契约模块"已因此偷带 jackson + slf4j 还带着实现类。

---

## 3. 建议的修复顺序

**发布阻断（按序）**
1. 租户改为 fail-closed（`issue-00099`，**已完成**）
2. 幂等 SPI 重写为 claim 状态机 + 移到安全链之后 + 键含主体与请求指纹 + 5xx 不落库（`issue-00101`，**已完成**）
3. operation-log 失败路径：真 `REQUIRES_NEW` 记录 + 修两个默认分类器（`issue-00102`，**已完成**）
   （注：第 4 项原写作"把 `withRetry` 改 `REQUIRES_NEW`"，实施时否决了这个方向——
   `REQUIRES_NEW` 会毁掉推进与 Inbox 去重行的原子提交，见 `issue-00105`）
4. process-manager C4/C5，并修正 `withRetry` 的重试前提、给 instance store 补异常映射
   （`issue-00103` / `issue-00104` / `issue-00105`，**已完成**）
5. 把静默降级统统改成响亮失败（无 TM 拒绝启动、两个仓储基类加活动事务断言、MP 乐观锁版本回写断言、
   `OutboxWriter` 事务断言）——框架已为 `IdGenerator` 立了 fail-loud 先例，照它办
   （`issue-00107`，**已完成**；Flyway 那条先行完成于 `issue-00106`，因为它需要用户定契约、
   且依赖第 4 项的列级 schema 探测）

**紧接其后**
6. 抽出 `aipersimmon-ddd-outbox-engine`，让 inbox/outbox 与 process-manager 形状一致
   （`decision-00020`，**已完成**；第 7、8、10 项现在都只需改一处）
7. relay 换行级 claim，解开 60 分钟停摆与预算耦合（`issue-00108`，**已完成**。
   最终**没有**用 `FOR UPDATE SKIP LOCKED`：三条方言无关的语句（选队头候选 → 按 id 列表原子打租约 →
   按 token 读回）取代了每方言一份 SQL。理由就是本报告上面那条——process-manager 的 SKIP LOCKED claim
   在两个真实数据库上零覆盖、MariaDB 误判即每轮语法错误，方言化 claim 是负债而非资产；
   条件 UPDATE 在 H2 上可测，跑的就是生产那条路径）
8. 写入时持久化目的地到 outbox 行：路由消失从静默本地投递变成死信（`issue-00109`，**已完成**。
   实际收益比"变成死信"更强：目的地留在行上意味着注解消失后那些行**照样正确投出**，
   死信只留给"传输整个不在了"这种真配置错。第 7 项让所有实例并发轮询，也把"滚动发布期间按谁的表判"
   从概率事件变成常态，这一项因此更紧要)
9. 加 metrics SPI（挨着现有 tracer SPI，接缝已在）（`issue-00110`，**已完成**；无新配置项）
10. ~~流水线化 Kafka 腿~~ **（已完成，`issue-00111`）**——落地时否掉了「按序等 + fail-fast」这个前提已变的要求；顺带修掉 DLT 固定分区号
11. ~~BOM 去 parent~~ **（已完成，`issue-00112`）**——1626 → 72 条；再导出的判据定为「本库在别的版本上不工作」
12. ~~测试门禁反转~~ **（已完成，`issue-00113`）**——两个 engine 各带内存 store 与门禁（outbox 39 例 / pm 50 例）+ ArchUnit 字节码规则；`DefaultProcessRuntime` 与 `replay`/`operation` 仍在门外并在 pom 里点名
13. ~~core 二选一删掉一套建筑块词汇表；47 模块收敛到约 20~~ **（已完成，`issue-00114`）**——
    词汇表已删（建筑块留注解、领域事件留接口，判据是"留承重的一边"，消费方迁移成本为 0），
    id-starter 作用域改 `runtime`，`ModuleNamingChecks` 改 DOM 解析；
    **"收敛到 20"这条驳回**——两条可行路径都被本次评审自己新加的门禁禁止，理由见 issue §4

---

## 4. 遗留观察（不在上述条目内）

- ~~`OutboxCleanupTest` 间歇失败，成因未查明。~~ **已查明并修复**：`purge()` 同时挂
  `@Scheduled(fixedDelay)` 与 `@SchedulerLock`，而 `@Scheduled` 的首跑在上下文就绪后**立即**发生并持有锁；
  测试通过注入的代理直调同一方法时若撞上，ShedLock 拿不到锁便静默返回——断言于是作用在一次没发生的 purge 上。
  见 `issue-00100`。同类风险：任何 `@Scheduled + @SchedulerLock` 的方法被测试直调都有这个陷阱。

- **新发现的间歇失败（未修，不属于上述任何条目）**：`Uuidv7IdGeneratorTest
  .isStrictlyMonotonicWithinAndAcrossMilliseconds` 在第 7 项的全量 verify 中失败一次，
  同一断言隔离重跑 5 次全过。失败对是 `019fae21-69e5-…` 紧跟在 `019fae21-69e6-…` 之后——
  即嵌入时间戳**回退了 1 毫秒**，不是同毫秒内的次序问题。该用例断言 10 万次连续铸造严格递增，
  而 JUG `timeBasedEpochGenerator()` 在突发下会把内部时间戳推到墙钟之前，随后重读墙钟即可回退。
  影响面：UUIDv7 的卖点正是"可按 id 排序 = 按时间排序"，一次回退会让两行的相对顺序与写入顺序相反。
  需要判断的是改用带单调计数器的构造方式、还是把断言降级为"非严格递增"并说明代价；
  两者都要动 `decision-00019` / `design-00010` 的措辞。

## 关联

- 父：[[report-00001-ddd-framework-review]]（第一次库评审）
- 兄弟：[[report-00002-scaffold-ddd-review]]（脚手架评审，30 个 issue 已全部 resolved）
- 子：[[issue-00099-tenant-isolation-fails-open-below-the-edge]]、
  [[issue-00100-a-scheduled-purge-steals-the-lock-from-its-own-test]]、
  [[issue-00101-idempotency-records-instead-of-claiming]]、
  [[issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction]]、
  [[issue-00103-parked-input-replay-is-not-crash-safe]]、
  [[issue-00104-an-ended-instance-keeps-its-timers-forever]]、
  [[issue-00105-an-advance-conflict-inside-a-joined-transaction-cannot-be-retried]]、
  [[issue-00106-an-empty-flyway-component-list-created-every-table]]、
  [[issue-00107-silent-degradations-become-loud-failures]]、
  [[issue-00108-a-killed-relay-instance-stops-all-delivery]]、
  [[issue-00109-a-vanished-route-turned-an-externalized-event-local]]、
  [[issue-00110-the-outbox-had-no-metrics-at-all]]
