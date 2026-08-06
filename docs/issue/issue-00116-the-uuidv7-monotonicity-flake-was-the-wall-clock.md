---
id: issue-00116-the-uuidv7-monotonicity-flake-was-the-wall-clock
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# UUIDv7 单调性 flake：是墙钟，不是并发，也不是 JUG 的 bug

## 症状

`Uuidv7IdGeneratorTest.isStrictlyMonotonicWithinAndAcrossMilliseconds` 在一次全量构建里失败一次
（`019fae21-69e5-…` 出现在 `019fae21-69e6-…` **之后**，嵌入时间戳**倒退 1ms**），
隔离重跑 5/5 通过。——这是"测试在**赛跑**而不是在**断言**"的典型形状。

## 定位：读源码，然后确定性复现

`TimeBasedEpochGenerator.construct(rawTimestamp)`（JUG 5.1.0）：

```java
if (rawTimestamp == _lastTimestamp) {
    // 同一毫秒内：把上一个值的 10 字节熵 +1 —— 严格递增
} else {
    _lastTimestamp = rawTimestamp;
    _random.nextBytes(_lastEntropy);   // 换新时间戳：重新抽熵
}
```

`else` 分支对 `rawTimestamp` 的**任何**变化都成立，**包括变小**。
而 `UUIDClock.systemTimeClock().currentTimeMillis()` 就是 `System.currentTimeMillis()`——
NTP 校正、虚拟机挂起后恢复，都可能让它倒退。

**确定性复现**（scratchpad，把时钟往回拨 1ms）：

```
before step: 0199c82c-c000-7ce0-959a-52229d23f46c
after  step: 0199c82c-bfff-76c2-b697-8898fab7c4e4
旧测试的断言 (after > before) 成立吗？ false
```

与线上观察到的失败**形状完全一致**。

**结论：不是并发问题**（生成器内部有 `ReentrantLock`），**不是 JUG 的 bug**——
任何基于墙钟的生成器都无法承诺全局单调。**是测试过度声称**：
它对 10 万个 id 断言了一个库从未承诺、框架也不需要的性质。

## 判断：这个"缺陷"不值得修，值得写清楚

时钟倒退时 id 会倒序。代价是什么？

**只有索引局部性，一瞬间的。** 已核实**框架里没有任何地方按 id 排序**：
outbox 按 `created_at` 与表自身的自增标识列排序，process-manager 按 `seq`，
deadline 查询里的 id 只用作**决定性平局打破**（`ORDER BY due_at, deadline_id`）。
唯一性也不依赖时钟——它来自熵，而熵在时间戳**任何**变化时都会重抽。

所以没有做"钳位"（不让时间戳倒退）：那会让 id 携带一个不是真实时间的时间戳，
且是在重新实现 JUG 已经做过的取舍。而 `decision-00019` 选择 UUIDv7 的理由从头到尾就是
**写放大 / 索引局部性**，不是排序保证。

**需要单调作为保证的应用，需要的是序列，不是时钟**——这句话写进了 javadoc。

## 落地

- `Uuidv7IdGenerator` 增加一个**包私有**构造（接受 `UUIDClock`），让测试把时间当输入而不是跟它赛跑。
  公开构造不变。
- javadoc 增一段：**排序跟随墙钟，包括倒退**，连同"为什么不值得防"的完整理由。
- 测试拆成三条各自为真的断言 + 一条真正要紧的：
  1. `aBurstInsideOneMillisecondStaysStrictlyOrdered`——固定时钟，10 万次**全部**走同毫秒计数器路径；
  2. `anIdMintedInALaterMillisecondSortsAfterAnEarlierOne`；
  3. `aClockThatStepsBackwardsMintsIdsThatSortEarlier`——**断言它，而不是回避它**；
  4. `aClockThatStepsBackwardsStillMintsDistinctIds`——时钟乱走时真正要紧的那条。

**顺带**：新的 burst 测试比它替换掉的那个**覆盖更强**——时钟不动，10 万次迭代**每一次**都走
同毫秒计数器路径（旧测试只有在跑赢时钟时才偶然走到）。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§4 遗留观察）
- 选 UUIDv7 的理由是写放大而非排序保证：[[decision-00019-time-ordered-uuidv7-identifiers]]
- id SPI 与铸造点：[[issue-00053-id-generator-silently-degrades-to-uuidv4]]
