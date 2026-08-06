---
id: issue-00100-a-scheduled-purge-steals-the-lock-from-its-own-test
type: issue
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# `OutboxCleanupTest` 间歇失败：被测方法的定时首跑抢走 ShedLock 锁，直调被静默跳过

## 问题（现状，file:line 为证）

- **等级：Low（测试缺陷，不影响产品代码；但它会随机挡住整个 `mvn verify`）**。
- 症状：全量 `mvn verify` 偶发
  `OutboxCleanupTest.removesSentRowsPastRetentionButKeepsRecentAndUnsent`
  失败于 `a sent row past retention is removed ==> expected: <0> but was: <1>`。
  单跑该模块、单跑该测试、以及在原始 HEAD 的对照 worktree 上均**不复现**。
- 该测试注入 `OutboxCleanup` bean 并直接调用 `cleanup.purge()`（旧 `OutboxCleanupTest.java:32,90`）。
- 而 `OutboxCleanup.purge()` 同时挂着两个注解（`OutboxCleanup.java:36-41`）：

```java
@Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.cleanup.poll-delay-ms:3600000}")
@SchedulerLock(name = "...-outbox-cleanup", lockAtMostFor = "PT10M")
public void purge() { ... }
```

## 根因（第一性）

1. **观察 vs 期望**：期望"调用 `purge()` 就会执行那条 DELETE";
   实际"调用 `purge()` 有时什么都不做，且不报错、不返回任何信号"。
2. **最小机制**：两件事叠加。
   - `@Scheduled(fixedDelay=...)` 的**首次执行发生在上下文就绪后立刻**，不是等一个 delay 之后。
     所以测试类的上下文一起来，调度线程就已经在跑 `purge()` 了（该测试设了
     `cleanup.enabled=true`，poll-delay 是默认 1 小时，但首跑与之无关）。
   - 注入的是 Spring 代理，所以测试的直调也走 ShedLock 切面。**ShedLock 拿不到锁时的行为是直接返回、
     不执行、void 方法上无任何痕迹**。
   两者相撞的窗口很窄（一次 DELETE 的时间），于是表现为低频随机失败。
3. **真根因**：`purge()` 把"业务动作"和"谁有资格跑它"焊在同一个方法上。这对生产是对的
   （多实例只让一个跑），但它使这个方法**不再是可直接调用的**——调用者无法区分
   "跑完了，删了 0 行" 与 "根本没跑"。测试恰好需要前者的语义，却拿到了后者。
4. **排除的伪根因**：不是时区/`Timestamp` 绑定问题（`old-sent` 是 1 小时前，
   cutoff 是 1 秒前，任何一致的时区下都远远落在窗口外）；不是测试间数据串扰
   （`@BeforeEach` 清空全表）；不是我在 `issue-00099` 期间的改动引入的
   （改动未触及 `aipersimmon-ddd-outbox-jdbc` 任何一行，`git diff` 为空）——
   但也**不是**"原始 HEAD 就会失败"，原始 HEAD 的对照跑是绿的，只是没撞上那个窗口。

## 复现（test-first）

无法稳定复现的竞态，记录用过的最强验证：

- 同一份代码上，全量 `mvn verify` 两次失败于此、三次通过；模块单跑 3/3 通过；
  原始 HEAD 的独立 worktree 全量 verify 通过一次。
- 机制侧的确定性证据：`@Scheduled` 的首跑是即时的，且 ShedLock 在拿不到锁时静默返回——
  两条都可从 Spring / ShedLock 的语义直接得出，不依赖复现。

## 修复

测试改为直接构造被测协作者，而不是走注入的代理：

```java
private OutboxCleanup cleanup() {
  return new OutboxCleanup(jdbc, Clock.systemUTC(), 1);
}
...
cleanup().purge();
```

- 锁从"这条 DELETE 匹配哪些行"这个问题里移走了——这才是该测试的主题。
- 后台那次调度跑无论是否发生都不会改变断言：它只可能删掉本测试本就断言应被删除的那一行，
  `recent-sent`（1 秒保留期内）与 `unsent`（`sent = FALSE`）都不在它的谓词里。

**未做的选择及原因**：没有把 `@SchedulerLock` 从 `purge()` 上拿掉（生产需要它），
也没有为测试关闭调度（`@Scheduled` 首跑即时，关不掉窗口，只能整体禁用调度，
那会连带影响同模块其他缓存上下文）。

## 验证结果

- 全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL 与全部质量门禁）：BUILD SUCCESS。
- 断言语义未变（仍是三行的保留/删除判定），只是不再可能作用在一次没发生的 purge 上。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§4 遗留观察由本 issue 结掉）
- 同类风险：同模块其余 `@Scheduled` + `@SchedulerLock` 的方法若被测试直调，都有同一陷阱。
