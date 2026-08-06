---
id: issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in
type: issue
status: resolved
parent: issue-00119-ten-majors-were-never-scheduled
---

# 限流器把别人正在计数的那个窗口删掉了

`issue-00119` 排期第 4 档。

## 症状：一个从未超限的调用者拿到 500

`tryAcquire` 每次都先扫一遍自己这个桶的旧窗口：

```sql
DELETE ... WHERE tenant_id = ? AND bucket_key = ? AND window_start < ?   -- ? = 调用者自己的窗口
```

同一个桶上相隔一毫秒的两个请求会落在**不同的窗口**——这对热点 key 是常态，不是边界情况：

| | A（窗口 W1） | B（窗口 W2） |
|---|---|---|
| 1 | DELETE `< W1`（无事发生） | |
| 2 | INSERT W1，count=1 | |
| 3 | | DELETE `< W2` → **删掉 A 的 W1 行** |
| 4 | | INSERT W2 |
| 5 | SELECT count WHERE window_start = W1 → **零行** | |

`queryForObject` 零行即抛 `EmptyResultDataAccessException`，一路冒到边缘 → **500**。
而 A 当时只用掉了配额的第 1 个。

**`count == null ? 1 : count` 这个守卫拦不住，也不可能拦住**：它拦的是 **null 值**，
而这里是**行不存在**。

## 修法两半

**一、扫描留两个窗口的余量**（`window_start < aligned - 2 × window`）。
于是任何还活着的计数器都不在删除范围内。**余量的代价是一行惰性数据，判断错的代价是一个 500。**
落后两个窗口以上的调用者，它计数的那个窗口早就过期了。

**同时必须保证这不会变成"从此不清理"**——所以另有一条测试钉住：桶被持续使用时，
旧窗口**仍然**会被丢掉，只是比原来晚。（冷掉的桶属于 `issue-00119` 第 6 档的 web-store 清理任务。）

**二、读取容忍零行**，并以"本次调用自己的那一次增量"作答。
因为保留任务或操作员仍可能在一次活跃请求中途清掉一行——
**一个把自己的计数器弄丢了的限流器，应该放行，而不是给一个本来没超限的调用者返回 500。**

## 负向对照暴露了我自己的测试是空的

把两半都改回旧实现，**只有一条测试变红**。

另一条（"计数器在增量与读取之间被扫掉"）挂的钩子是 `JdbcTemplate.query(...)`，
而**旧代码走的是 `queryForObject(...)`，根本不经过那个重载**——
于是它对着**自己存在的意义所要排除的那个实现**，绿着通过。

两个重载都挂上钩之后，它才真的复现出 `EmptyResultDataAccessException` 本身。

**这正是负向对照存在的理由**：一条只验证新代码的测试，看起来和一条真正的回归测试一模一样。

## 关联

- 父：[[issue-00119-ten-majors-were-never-scheduled]]（排期第 4 档）
- 冷桶的清理仍未做，属第 6 档（web-store 三张表无清理路径）
- 同一轮里另一次"顺手扫描造成的竞争"：[[issue-00122-the-four-process-tables-grew-forever]]
