---
id: issue-00128-the-scaffold-kept-two-fallbacks-the-library-had-deleted
type: issue
role: main
status: resolved
---

# 脚手架留着两处库里已经删掉的兜底

不属 `report-00003`，是 2026-07-30 脚手架对齐核查里查出来的，见
[[issue-00119-ten-majors-were-never-scheduled]] 收尾时列的遗留。

## 症状

`issue-00099` 把库里 14 处 `orElse(Tenants.ROOT)` 全删了，收口到 `TenantContext.effective()`。
脚手架里还剩两处手写的：

| 位置 | 写法 |
|---|---|
| `MyBatisPaymentOperations:78` | `TenantContext.current().orElse(Tenants.ROOT).value()` |
| `RuntimeOrderFulfilmentProcess:125` | `TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value())` |

而 `TenantContext` 的 javadoc 把规矩写得很明白：

> "Infrastructure that stamps or filters a tenant column reads `effective()` rather than `current()`,
> so the 'what if nothing is bound' decision is made once, here, from the deployment's tenancy mode —
> **never re-decided per call site**."

**这两处正是在 per call site 地重新决定它。**

## 后果不是"多一行脏数据"

脚手架 `tenancy.enabled: true`，所以 `effective()` 在没有绑定时**抛** `MissingTenantException`。
这两处则默默写进 `__root__`：**全应用其余每一处租户写入都会拒绝，唯独这两处会落到共享桶里。**

两处里流程那一处更糟：**开流程时盖的租户，就是此后每一次推进查找实例所用的租户。**
以哨兵起的流程，真正的租户**再也推进不了**——不是一行标错，是一条流程从此失联。

支付那一处的连带影响也不只是写：`find` 用同一个 `tenant()`，
所以查不到真租户已经记下的那一行，会把一个**已经处理过的**操作重新授权一遍。

## 那条注释自己也过期了

支付那处的注释写着"which is what the command bus itself falls back to"——
用"和命令总线一致"来给自己背书。

**`RegistryCommandBus:129` 早就改成 `effective()` 了**，这句话已经不成立。
改完之后它才重新成立，而且理由变成了同一个。

## 先确认这两行真的会被跑到

改完 `mvn verify` 是绿的——但**绿本身什么都不证明，除非这两行真的执行了**。
所以先把两处换成无条件抛异常再跑一遍：

- 支付那处命中 **7 次**
- `factContext` 命中 **96 次**
- 十来个验收测试变红（含 `TwoTenantAcceptanceTest`）

**第一次探针跑其实是无效的**：抛异常让 `TenantContext` 这个 import 没人用了，
spotless 在跑到验收测试之前就把构建打回了，于是"0 命中"看着像"没覆盖"，实际是"没跑到"。
关掉 lint 重跑才拿到上面的数字。**又一次：先确认你的对照真的执行了。**

## 测试钉住两个方向

差异只在"未绑定的线程"上显现，验收层面造不出来（边缘总会绑），所以在 payment-infrastructure
写了针对性单测：多租开启且无绑定 → 拒绝且**一行都不许到表**；租户关闭 → 哨兵仍是正确答案。
**同一个调用，相反的结果，由部署决定而不是由这个类决定。**

负向对照：改回 `current().orElse(ROOT)` → 2 条红。

## 关联

- 库里做同一件事的那一轮：[[issue-00099-tenant-isolation-fails-open-below-the-edge]]
- 发现它的那次对齐核查记在：[[issue-00119-ten-majors-were-never-scheduled]]
- 样例不是设计权威，但它是别人抄的第一份代码——这正是它值得修的理由
