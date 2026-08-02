---
id: issue-00152-a-parked-input-suspension-is-invisible-to-every-gauge
type: issue
role: main
status: open
---

# parked-input 挂起对健康检查与全部指标不可见（P1）

2026-08-02 第四轮评审发现（process-manager）。

## 现象

`ParkedInputWorker` 重放期把实例以 source=`PARKED_INPUT` 挂起（`ParkedInputWorker.java:171`），
其 javadoc（:50 附近）承诺它 "shows up in the suspended-instance SLI"。但：

- `ProcessManagerMeterBinder.java:24` 只为 `{"EFFECT", "DEADLINE"}` 注册挂起 gauge；
- `ProcessManagerHealthIndicator.java:40-45` 只看 dead 计数 / stuck 计数 / pending age；
- `JdbcProcessInstanceStore.countStuck`（:242-247）排除 `SUSPENDED`。

一个在 parked-input 重放期中毒的实例，等着操作员 redrive 时：health 读 UP、所有 gauge 读 0。
javadoc 承诺的 SLI 不存在。

## 修复要求

1. `ProcessManagerMeterBinder` 的挂起 gauge 覆盖全部挂起来源（枚举驱动，不再手列两个——
   新增来源时不该再出现同类盲区）。
2. `ProcessManagerHealthIndicator` 把挂起实例计入（阈值语义与 dead/stuck 对齐，挂起≠有病
   但挂起堆积=需要人）。
3. 对照 `ParkedInputWorker` javadoc 的承诺逐句核实，承诺与机器行为二选一改齐。
