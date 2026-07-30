---
id: issue-00126-an-anonymous-caller-chose-the-allocation
type: issue
role: main
status: resolved
parent: issue-00119-ten-majors-were-never-scheduled
---

# 匿名调用者说了算的那次分配

`issue-00119` 排期第 6 档，对应 `report-00003` §2 的 #13 与 #11。

## #13：先跑的那几道检查，一个都不认人

`ReplayProtectionFilter` 的顺序其实是对的——先查头，再缓冲 body：

| 顺序 | 检查 | 攻击者的代价 |
|---|---|---|
| 1 | 签名头非空 | 打几个字符 |
| 2 | 时间戳可解析且在容差内 | 填当前时间 |
| 3 | nonce 非空（开了才查） | 打几个字符 |
| 4 | **`readAllBytes()`** | **由他决定** |
| 5 | 验签 | — |

**前三道没有一道能证明来者是谁。** 而签名恰恰覆盖 body，所以**必须**先把 body 收进内存
才谈得上验签——顺序换不了。于是"缓冲多少"就是一个匿名调用者的自由选择。

改为**边读边拒**：超过上限立刻抛，答 `413`。

**必须在读的过程中拒，不能读完再看 `length`**——那时候该防的那次分配已经发生了。
这条差别有专门的测试守着：一个只会数"实际被读走多少字节"的流，
读完全量再判断的实现在别的断言上全绿，只有它会红。

## 路径怎么限定：把匹配交给容器

过滤器注册时没有 urlPatterns，一开就是全站——健康检查也要签名，探针直接 401。

我**没有**照抄 `TenantResolutionFilter` 那套 `dispatchPath` 归一化
（它要处理 `;jsessionid`、`%2F`、`/../` ——因为容器最终匹配的路径与 `getRequestURI()` 不是一回事，
一个自己做匹配的排除表可能和容器的判断分道扬镳）。两个模块之间也没有能放这段代码的公共模块：
`aipersimmon-ddd-web` 是契约模块，`ContractModulesCarryNoFrameworkTest` 不许它碰 servlet API。

**更好的答案是让这个问题不出现**：这里要的是**包含**列表，而 servlet 本来就有这个原语。
`registration.setUrlPatterns(...)` 交给容器匹配，而容器匹配的**就是它将要分发的那个路径**——
没有第二个意见可以和它冲突。代价是只能用 servlet 模式（`/api/*`）而不是 Ant 模式（`/api/**`），
写进文档即可。

## #11：迁移里承诺过的那个作业，没人写

三个 store 都会删过期行——但**只删眼前这把 key，且只在这把 key 再次被用到时**。
而幂等键与 nonce 按定义**只用一次**，所以对绝大多数行来说那第二次访问永远不会来。

最能说明问题的是 V3 迁移里的注释：

> "A retention job needs to find expired rows across all keys, which without this index is a full
> scan of a table that only grows."

**索引已经为这个作业建好了，作业本身没写。**

### 这次默认开，和 pm 保留策略相反

`issue-00122` 把 pm 的保留默认关掉，理由是"删业务记录、留多久，是部署方的决定"。
这里**不是改了主意**：`expires_at` 是 store 自己写下的"这行已经死了"，
而且代码本来就在顺手删这种行。**把它按时删完不是一项策略，是把已经开始的事做完。**

### rate_limit 那张表没有 expires_at

计数器在窗口过去后就是死的，但"窗口多长"属于当时那条策略，行里没记。
所以这一张按配置的保留期删，**必须长于在用的最长窗口**。

设短了是可承受的：删掉一个活着的计数器只是重置该桶的配额，
而"计数器不见了"这件事在 `issue-00123` 之后是**放行**而不是 500。
何况热桶本来就靠 `JdbcRateLimiter` 每次调用自扫，这里剩下的只有冷桶。

### 没有批量上限

`DELETE ... LIMIT` 只有 MySQL 有；pm 用的"先选 id 再删"在这里也不成立——三张表都是复合主键。
每条 DELETE 都走它过滤的那一列上的索引，稳态下一轮只删掉一个间隔的过期量，很小。
唯一的大跑是**第一次在积压已久的库上打开它**，一次性，也正是轮询间隔默认放宽的原因。

V4 给 rate_limit 补 `window_start` 索引——主键以 `bucket_key` 打头，服务不了按窗口的扫描。

## 负向对照

三条，均断言 revert 落地后变红：

- 上限改回 `readAllBytes()` → 2 红，包括"没有把流读到底"那条
- `expires_at <= ?` 改成 `< ?` → 边界那条红
- rate_limit 的窗口谓词去掉 → **warm-bucket 被删**（期望 1 得 0）——
  它守的正是**删多了**这个方向

## 关联

- 父：[[issue-00119-ten-majors-were-never-scheduled]]（排期第 6 档）
- "冷桶的清理属于第 6 档"的欠条来自：[[issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in]]
- 默认开/关的判据与之相反的那一档：[[issue-00122-the-four-process-tables-grew-forever]]
- 没有照抄的那套路径归一化：`TenantResolutionFilter.dispatchPath`（[[issue-00099-tenant-isolation-fails-open-below-the-edge]]）
