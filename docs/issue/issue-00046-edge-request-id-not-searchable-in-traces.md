---
id: issue-00046-edge-request-id-not-searchable-in-traces
type: issue
role: main
status: resolved
parent: design-00005-observability-and-distributed-tracing
---

# 边缘 request id 未上报到 trace,SigNoz 里按 X-Request-Id 搜不到

## 问题(现状,file:line 为证)

- **等级:可观测性真实缺口**(库侧 observability-otel starter)。应用对外发一个 `X-Request-Id`
  (调用方会拿它报障),却无法用它在 trace 后端定位到对应链路。
- 实测(OTel 开启,导出到 SigNoz):`POST /orders` 这条 server span 的全部属性只有标准 HTTP semconv
  (`http.request.method` / `http.route` / `url.*` / `network.*` / `user_agent.original`);**没有任何 request id 属性**,
  且全 trace 内无任一属性值等于响应里的 `requestId`。→ SigNoz 里按 `X-Request-Id` 搜零命中。
- 根因:`RequestIdFilter`(`aipersimmon-ddd-web-spring`,`REQUEST_ID_MDC_KEY="requestId"`)把 requestId 放进
  MDC + 响应头,但 **web-spring 不依赖 OTel**,写不了 span;OTel-aware 的 observability-otel starter 里,
  `TracingCommandInterceptor` 只在 command span 打了**消息层** `correlation.id`/`message.id`/`command.type`
  (与 HTTP 边缘 request id 是两回事),**没人把 requestId 打到 server span**。

## 根因(第一性)

- **可检索性是硬约束**:trace 后端按 trace id + span 属性建索引。一个既不是 trace id、又不在任何 span 上的
  id,天生搜不到。既然本应用刻意对外暴露一个**独立、且可由客户端传入**的 `X-Request-Id`,那它**必须**作为 span
  属性上报,才谈得上"按它查"。这是关联 id(Correlation-ID)模式的标准要求,不是可选优化。
- 现状只做了另一半:响应体带 `traceId`(可直接按 trace 查);但调用方手里往往只留了 `X-Request-Id`,拿它却搜不到。

## 复现

OTel 开启导出到 SigNoz,`curl -H 'X-Request-Id: abc' -XPOST /orders …`;在 SigNoz(或其 ClickHouse
`signoz_traces.distributed_signoz_index_v3`)按 `attributes_string['request.id']='abc'` 查 → 修复前零命中。

## 修复(已实施)

- 在 observability-otel starter 的 `TraceIdMdcFilter`(已在 OTel server span 作用域内、且晚于 RequestIdFilter
  运行)增加:读已解析的 `MDC["requestId"]`,以 span 属性 **`request.id`** 打到当前 server span。
  - 键取 `request.id`(标量、可检索),**刻意区别于**消息层 `correlation.id`。
  - **读 MDC 的已解析值**(而非只捕获入站 header):这样**客户端传入**与**服务端生成**两种情况都覆盖——
    纯 header 捕获(`http.request.header.x-request-id`)会漏掉服务端生成的常见情形。
  - 写在 root server span 上即可满足"按 id 搜到 trace → 钻取全部 span";未来要跨服务/异步传播可再上 baggage。
- 单测:`TraceIdMdcFilterTest#stampsTheEdgeRequestIdOnTheActiveSpanSoTheTraceIsSearchableByIt`
  (InMemorySpanExporter 断言 span 带 `request.id`)。

## 验证结果

- observability-otel starter `mvn install`:8 tests 全绿(含新增)。
- 真实启动导出 SigNoz,按 `request.id` 检索命中对应 trace:
  - 客户端传入 `rid-verify-38d801ac…` → trace `2ddb0e38dc0488e4e6ad6fd1ba8eabe2`;
  - 服务端生成 `4fe92345-8556-4999-96ad-65aed5ba0e31` → trace `b7c5af77d46c5cc5c262578c7945ed9b`。
  - 即"按 X-Request-Id 搜 → 拿 trace id → 全局钻取"闭环成立。

## 关联

- [[design-00005-observability-and-distributed-tracing]](边缘 id ↔ trace 关联)
- [[record-00001-multi-module-ddd-integration-verification]](第 5 项 可观测性,含本条证据)
- 后续(未做):日志作为遥测信号进 SigNoz 并带 trace_id/requestId(observability starter 接 OTel logback appender;样例补 console pattern)—— 见 record 第 2 类日志讨论。
