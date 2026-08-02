---
id: issue-00155-the-scaffold-never-says-what-you-may-leave-out
type: issue
role: main
status: resolved
---

# scaffold 从不说"哪些可以不要"（P1，教学）

2026-08-02 第四轮评审发现（scaffold 教学面最大的照抄危害）。

## 现象

三步流程、18 个模块；payment 用了完整五模块层栈，装着 4 个领域类、零聚合。统一性是
archetype 的本意，`README.md` 也说了 "show each building block… not a complete product"，
但树内**没有任何一段**回答反方向的问题：一个更轻的上下文可以收起哪些东西。README 的
"declared debts" 一节证明这棵树知道怎么声明取舍——缺的正是对称的那一节。`DOCS.md` 把
选型指引委托给库文档（`CHOOSING-MODULES.md`），而生成的工程不带那份文档。

中级团队把 scaffold 当唯一参照抄的时候，会给不配拥有全家桶的上下文复制全套
bus + outbox + inbox + process manager + tenancy + operation log。

## 修复要求

`README.md` 加一节（与 declared-debts 同样的诚实口吻），至少覆盖：

- **payment 展示的是一个上下文的最小形态**：领域层可以只是 policy + sealed 决策，不需要
  制造聚合；
- 哪些模块在什么条件下整个不要（无跨部署事件→不要 kafka；无长流程→不要 -process；
  纯读投影→连库都不要，指回 `CHOOSING-MODULES.md` 的裸 starter 分支）；
- 五模块层栈什么时候可以合并（引用库文档的 light 路径，若无则说明合并的判据与代价）；
- 一句点破：模块数是发布粒度不是美德，三个 BC 是为了演示三种形态，不是"每个服务至少
  三个 BC"。

## 解决记录（2026-08-02）

README 新增 "Copying this: what you may leave out" 一节（紧接 "Not demonstrated here, on
purpose"，同一口吻）。六条减法 + 一条不许减的：payment 是地板（政策+决策就是领域层，为了
像 DDD 而制造聚合是在无生命周期处添加生命周期）；无第二部署方→去 Kafka（handler 代码两种
模式完全一致，正是可以后加的原因）；无跨上下文长流程→去 -process；无表→裸 starter；五模块
是发布粒度，规则关心的是边不是模块数；三个 BC 是三种形态的光谱不是最低配置。不许减：
外发事件下的 outbox、可重复投递处的幂等消费、ArchUnit 门禁。
