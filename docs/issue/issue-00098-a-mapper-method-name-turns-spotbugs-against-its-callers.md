---
id: issue-00098-a-mapper-method-name-turns-spotbugs-against-its-callers
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 给 mapper 加一个 `delete` 开头的方法，SpotBugs 就把它的**所有持有者**判红

## 问题（现状，可复现）

- **等级：Medium（`mvn verify` 是本 scaffold 对外承诺的质量门，而它在 `f350e03` 之后是红的；
  更要紧的是报错指向的类根本没被改过，误导性与
  [[issue-00095-a-partial-reactor-build-silently-tests-stale-siblings]] 同级）**。
- 由 [[issue-00097-the-payment-operation-log-has-no-cleanup]] 的实施引入。
  那次改动给 `PaymentOperationMapper` 加了一个方法：

```java
@Delete("DELETE FROM payment_operations WHERE recorded_at < #{cutoff}")
int deleteRecordedBefore(@Param("cutoff") Instant cutoff);
```

- 之后 `mvn -o clean verify` 在 `payment-infrastructure` 上失败，**两条** SpotBugs 报告：

```
Medium: new com.example.payment.infrastructure.MyBatisPaymentOperations(PaymentOperationMapper)
  may expose internal representation ... EI_EXPOSE_REP2  at MyBatisPaymentOperations.java:[41]
Medium: new com.example.payment.infrastructure.PaymentOperationCleanup(PaymentOperationMapper, Clock, long)
  may expose internal representation ... EI_EXPOSE_REP2  at PaymentOperationCleanup.java:[40]
```

- 第一条指向的 `MyBatisPaymentOperations` **本次一个字符都没改**，而且此前一直是绿的。
- 同一个 reactor 里 `MyBatisOrders` 同样把两个 mapper 存成字段，**不报**。

## 根因（第一性）

1. **观察 vs 期望**：期望"改 A 只可能让 A 变红"；
   实际"改 A 的**接口**，让所有把 A 存成字段的类一起变红"。
2. **最小机制**：`EI_EXPOSE_REP2` 的触发条件是"把一个**可变**类型的参数存进字段"。
   SpotBugs 判断一个类型可不可变靠 `MutableClasses` 的启发式，其中一条是
   **按方法名前缀猜 setter**：`set` / `add` / `put` / `remove` / `clear` / `insert` /
   **`delete`** / `append` / `replace` 等。
   `deleteRecordedBefore` 命中 `delete` ⇒ `PaymentOperationMapper` 被判为可变类型
   ⇒ **每一个持有它的类**都触发 `EI_EXPOSE_REP2`。
3. **真根因**：一个类型的"可变性"在这套启发式里是**从它的方法名推断的**，
   而方法名是接口作者的自由；于是接口上一次纯粹的命名选择，
   会跨类传播成别人构造器上的报告。因果方向与报告位置相反——
   报告在持有者身上，原因在被持有者的方法名上，两者之间没有任何提示。
   `MyBatisOrders` 不报，正是因为 `OrderMapper` 继承的 `BaseMapper` 方法名里
   恰好没有命中前缀的那一类。
4. **排除的伪根因**：不是 `MyBatisPaymentOperations` 有缺陷——它此前此后都完全一样。
   也不是"构造器注入本来就该报"——共享注入进来的协作者正是构造器注入的用意，
   库侧的 `spotbugs-exclude.xml` 已经为这一类写了整段理由并列了四十多个类。
   也**不是 pre-existing**：本轮一度这样判断过，因为隔离实验时只移走了新类、
   却留下了那个新方法，报告自然还在。

## 复现（test-first）

结构性缺陷，没有可写的单元测试；`mvn verify` 本身就是那条断言。可验证的步骤：

```bash
# 红
mvn -o clean verify -pl payment/payment-infrastructure -am -DskipTests
#   两条 EI_EXPOSE_REP2，其中一条指向未改动的 MyBatisPaymentOperations

# 只改方法名，其余一字不动
sed -i '' 's/deleteRecordedBefore/purgeRecordedBefore/g' <三个文件>

# 绿
mvn -o clean verify -pl payment/payment-infrastructure -am -DskipTests   # BUILD SUCCESS
```

**注意 `clean` 是必需的**：不带 `clean` 时增量编译会让整轮验证假绿，
同一轮里 [[issue-00085-ordering-carries-sku-as-a-bare-string]] 也踩到了这一点。

## 修复

`deleteRecordedBefore` → `purgeRecordedBefore`，并在 mapper 的 javadoc 里写明为什么。

**为什么不加 exclude**：库侧那份 filter 是给 `aipersimmon-ddd` 自己的类用的，
往里面塞 scaffold 的类名会污染所有下游使用者；给 scaffold 单开一份 filter 则要动
共享的 `spotbugs-maven-plugin` 配置，为一处误报改构建配置不划算。

**为什么这不算"迁就 linter 改名字"**：`purge` 与 `PaymentOperationCleanup.purge()` 同名同义，
读起来比 `delete...` 更贴切——这个名字独立成立。
但**只靠名字维持绿灯是有陷阱的**：下一个人把它改回 `delete*` 会得到一条指向别的类的报错。
所以 javadoc 里显式记了这条约束，否则这就是一颗埋好的雷。

## 验证结果

已修。`mvn -o clean verify`（全 reactor）通过。

**留给接手者的一条经验**：SpotBugs 的 `EI_EXPOSE_REP2` 出现在**构造器**上，
但原因可能在**参数类型的方法名**里。看到一条指向"我没改过的类"的 `EI_EXPOSE_REP2`，
先去看这一轮有没有给它持有的某个接口加过 `delete` / `insert` / `remove` / `add` 开头的方法。

## 关联

- [[issue-00097-the-payment-operation-log-has-no-cleanup]]（引入它的那次修复）
- [[issue-00085-ordering-carries-sku-as-a-bare-string]]（同一轮的另一处"不带 `clean` 就假绿"）
- [[report-00002-scaffold-ddd-review]]
