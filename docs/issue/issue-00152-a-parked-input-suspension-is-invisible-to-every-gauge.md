---
id: issue-00152-a-parked-input-suspension-is-invisible-to-every-gauge
type: issue
status: resolved
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

## 解决记录（2026-08-02）

三处修齐（engine 新增 5 例测试红先行，155 全绿；jdbc/mybatis 后端同 reactor 构建通过）：

- 新枚举 `engine.store.SuspensionSource`（EFFECT / DEADLINE / PARKED_INPUT）：store 端口保留
  String（那是列里存的东西），但 engine 内每个挂起写者经枚举命名来源，meter binder 的
  tag 集合从 `values()` 派生——**新挂起方式无法再隐身于 SLI**。三个字面量调用点
  （ProcessEffectRelay / ProcessDeadlineWorker / ParkedInputWorker）收口。
- meter binder 另注册 `source=OTHER` 兜底桶（总数减已知和，never negative）：旧版本/操作
  工具/手改写入的枚举外来源不再消失，且"按 source 求和=总数"的仪表盘不变量保住。
- `ProcessManagerHealthIndicator` 的 degraded 谓词加 `suspendedInstances() > 0`：挂起实例
  与 DEAD 行降级健康的理由相同——没有人为介入它不会动；effect/deadline 挂起本就伴随
  DEAD 行所以从前"碰巧"降级，parked-input 挂起只留实例，必须由挂起数自己称重。
- `ParkedInputWorker` javadoc 的承诺（"shows up in the suspended-instance SLI"）就此为真，
  无需改文；SigNoz README 里 `suspended.instances > 0` 的既有告警现在才真正覆盖
  parked-input。
