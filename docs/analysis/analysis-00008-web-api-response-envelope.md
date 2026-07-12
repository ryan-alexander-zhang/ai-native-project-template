---
id: analysis-00008-web-api-response-envelope
type: analysis
role: main
status: active
parent:
---

# Web 层封装怎么做：`ApiResponse` / `ApiRequest` 该不该有——15+ 大厂 HTTP 接口约定对比

`aipersimmon-ddd` 现在缺 Web(interface/adapter)层的封装:没有统一的 `ApiResponse`、
`ApiRequest`、错误响应体、分页约定、请求追踪/幂等约定。本文**先不下最终决策**(决策归
`docs/decision/`),只做两件事:①**比较分析**——盘点 15+ 大厂与两份 IETF/跨语言标准的
HTTP 接口约定,每条主张都带**一手证据 URL**;②**差距分析**——对照 `aipersimmon-ddd` 构件库现状,
指出该补什么、补在哪一层(承接 [[analysis-00006-ddd-building-blocks-library]] 的"纯/脏分离")。

配套阅读:[[analysis-00006-ddd-building-blocks-library]](构件库按 Layer×可插拔性切分)、
[[analysis-00004-bounded-context-module-structure]](三种拓扑)、
[[analysis-00005-structure-2-event-flow-and-cqrs]](CQRS 命令/查询侧)、
[[analysis-00002-domain-vs-integration-events]](两类事件区分)。

## 结论先行

> **别造一个通用的 `{code, message, data}` 成功信封 + 恒返 200 的"中式包裹",
> 也别造品牌私有的错误体。成功响应"直接返回资源 + 正确 HTTP 状态码",
> 错误响应统一走 RFC 9457 Problem Details(Spring 原生 `ProblemDetail`)。
> Web 封装做成一个可选的 `aipersimmon-ddd-web-spring` starter(脏),
> 契约(错误码目录、分页值对象)沉到一个 framework-free 的薄模块(纯)——与 `-cqrs`/`-cqrs-spring` 同构。**

四条依据,后文逐一给证据:

- **数人头:15 家里 12 家不套通用成功信封**——直接返资源、用 HTTP 状态码表达结果。
  Google、Microsoft、AWS、OpenAI、Stripe、GitHub、PayPal、Twilio、Zalando、Shopify、Adyen、Atlassian
  都是这一路;只有 Slack(`{ok}`)与中国部分厂商(飞书/腾讯云)套通用信封并**恒返 HTTP 200**。
- **错误格式正在向一个标准收敛**:RFC 7807 已被 **RFC 9457** 取代,Zalando **强制**要求它,
  Spring 6 / Spring Boot 3 提供原生 `ProblemDetail` 一等支持。
  与其发明第 16 种私有错误体,不如把这套标准封装好、映射好。
- **"恒返 200 + 业务 code"会掏空 HTTP 语义**:腾讯云、飞书明确"只要被处理就返 200"。
  这让缓存、网关、重试、监控全部失灵——对一个强调边界清晰、可审计的模板是负资产。
- **落位天然对齐 [[analysis-00006-ddd-building-blocks-library]] 的纯/脏切分**:HTTP 是 interface 层关注点,
  绝不能污染 framework-free 的 domain;所以"信封/异常映射/追踪/幂等"= Spring starter(脏、可选),
  "错误码目录 + 分页值对象"= 薄契约(纯)。

一句话:**封装的重点不是"包一层 data",而是"把 RFC 9457 + 分页 + traceId + 幂等键这套横切,
在 interface 层一次做对、可复用、可版本化"。**

---

## 一、把问题拆成八个维度

"Web 层封装"不是单一问题。下面大厂对比先按①~⑥展开(§二~§六),安全/横切三维
⑦⑧在 §六B/§六C 补齐——这也正是 `aipersimmon-ddd` 需要逐条决策的清单:

| 维度 | 具体问题 |
| --- | --- |
| ① 成功信封 | 成功响应是否套 `{code, message, data}` 这类通用外壳?还是直接返资源? |
| ② 错误格式 | 错误体的确切字段;是否用 RFC 7807/9457 Problem Details? |
| ③ HTTP 状态码语义 | 用真实 4xx/5xx,还是"恒返 200、把状态塞进 body 的 code"? |
| ④ 分页 | 游标(cursor)还是偏移(offset)?字段名/Link 头? |
| ⑤ 字段命名 | camelCase 还是 snake_case?是否强制? |
| ⑥ 版本 & 追踪 & 幂等 | 版本放 URL/Header?RequestId/traceId?Idempotency-Key? |
| ⑦ 安全·防重放 | 是否有签名+时间戳+nonce 的防重放?时间窗多大?(与幂等区分,见 §六B) |
| ⑧ 限流·CORS·鉴权错误 | 限流用 `429`+`Retry-After`+`RateLimit-*`?CORS 头?401/403 是否也走 Problem Details?(见 §六C) |

---

## 二、15+ 大厂 + 2 份标准:总览大表(每格附证据)

> 阅读提示:**"成功信封"这一列是全文关键**。"无"= 直接返资源(资源本身就是 body);
> "list 有壳"= 仅列表响应有个轻量分页壳(如 `{object:"list", data, has_more}`),单资源仍直返。

| 主体 | ① 成功信封 | ② 错误体字段 | ③ HTTP 状态 | ④ 分页 | ⑤ 命名 | ⑥ 版本 / 追踪 / 幂等 |
| --- | --- | --- | --- | --- | --- | --- |
| **Google** (AIP) | 无(资源直返;list 用类型化消息) | `error:{code,message,status,details[]}`(`google.rpc.Status`,含必填 `ErrorInfo`);**非** 7807 | 真实 4xx/5xx | cursor:`page_size`/`page_token`/`next_page_token` | proto snake→JSON camelCase | URL 路径 `v1`(仅大版本)/ `request_id`(UUID4,MUST 幂等) |
| **Microsoft** (REST Guidelines) | 无;集合套 `value` 数组(OData) | `error:{code,message,target,details,innererror}`;**非** 7807 | 真实 4xx/5xx | `@odata.nextLink`+`$top`/`$skip` | camelCase(强制) | `api-version`(查询参/路径)/ `x-ms-request-id` `x-ms-client-request-id` / `Repeatability-*` |
| **AWS** (Smithy 协议) | 无(operation 输出直返) | JSON:`__type`+`message`;XML:`Error>Code/Message`+`RequestId`;**非** 7807 | 真实 4xx/5xx | cursor:`nextToken`/`NextToken`/`Marker` | 不统一(老 PascalCase / 新 camelCase) | 日期版本号 / `x-amzn-RequestId` / `@idempotencyToken`(`ClientToken`) |
| **OpenAI** | 无;list 用 `{object:"list",data,has_more}` | `error:{message,type,param,code}` | 真实 4xx/5xx | cursor:`after`/`before`/`limit`/`has_more` | snake_case | 日期版本(`openai-version` 头)/ `x-request-id` |
| **Stripe** | 无;list 用 `{object:"list",url,has_more,data}` | `error:{type,code,message,param,doc_url,...}`(`type` 必填枚举) | 真实 4xx/5xx | cursor:`starting_after`/`ending_before`/`limit`/`has_more` | snake_case | 日期版本(`Stripe-Version` 头)/ **`Idempotency-Key`** 头(≤255,~24h) |
| **Twilio** | 无;list 内嵌 `page`/`page_size`/`*_page_uri` | 扁平 `{code,message,more_info,status}`(**不**包 `error` 键) | 真实 4xx/5xx | 页码:`page`/`page_size`/`next_page_uri` | snake_case | 日期/版本在 URL 路径 / 无标准幂等键 |
| **GitHub** | 无(资源直返) | `{message,documentation_url,errors:[{resource,field,code}]}` | 真实 4xx/5xx | **Link 头**(`page`/`per_page`;部分 `before`/`after`) | snake_case | `X-GitHub-Api-Version`(日期头)/ 响应 `X-Github-Request-Id` |
| **PayPal** (API Style Guide) | 无(资源直返;明确禁止 2xx 返错误体) | `{name,message,debug_id,information_link,details:[{field,value,location,issue}],links}` | 真实 4xx/5xx | offset:`page`/`page_size`+HATEOAS links | snake_case(MUST;头用 Camel-Hyphen) | URL 路径 `/v1/`(MUST)/ **`PayPal-Request-Id`**(转账幂等键) |
| **Atlassian** | 无;非资源响应用 `Status` 实体 | Jira:`{errorMessages[],errors{},status}`;Mktplace:`{errors:[{message,code,path}]}` | 真实 4xx/5xx | offset `startAt`/`maxResults`/`total`/`isLast`;新端点 `nextPageToken` | 混乱(旧连字符 / Cloud camelCase) | URL 路径(`/rest/api/3/`)/ 无标准 |
| **Zalando** (RESTful Guidelines) | 无(MUST 返顶层 JSON object) | **强制 RFC 9457** `{type,title,status,detail,instance}`,`application/problem+json` | 真实 4xx/5xx | **首选 cursor**,`self/first/prev/next/last/items/query` | **snake_case(强制,禁 camelCase)** | **媒体类型版本;禁 URL 版本** / `X-Flow-ID`(MUST)/ `Idempotency-Key`(MAY) |
| **Shopify** | REST 用资源名作键 `{product:{...}}`;GraphQL `{data,errors,extensions}` | REST 多态 `errors`(串/数组/字段键对象);GraphQL `errors[]`+`userErrors[]` | 真实 4xx/5xx | REST **Link 头**+`page_info`(cursor);GraphQL connections | REST snake_case / GraphQL camelCase | 日期路径 `/admin/api/YYYY-MM/`(季度更新)/ 未公开 |
| **Adyen** | 无(资源直返) | `ServiceError{status,errorCode,message,errorType,pspReference}` | 真实 4xx/5xx | 多为单资源;Management API offset `pageSize`/`pageNumber` | camelCase | URL 路径 `/vN/`(每 API 独立)/ **`idempotency-key`** 头(≤64,7 天) |
| **Slack** (Web API) | **有:`{ok:boolean, ...}`**(载荷平铺在 `ok` 旁,无 `data` 包) | `{ok:false, error:"snake_code"}` | *(RPC 风格,依赖 `ok` 而非 HTTP 码)* | cursor:`cursor`/`limit`→`response_metadata.next_cursor` | snake_case | 无 URL/头版本(靠新方法族)/ 429+`Retry-After` |
| **阿里云** (OpenAPI) | 无(扁平错误体) | `{RequestId,HostId,Code,Message,Recommend}`(`Code` 为点分**字符串**) | **真实 4xx/5xx** | 依服务 | PascalCase | / **`RequestId`** 通用追踪 |
| **腾讯云** (API 3.0) | **有:`{Response:{...}}`** 顶层壳 | `Response.Error:{Code,Message}`+`Response.RequestId` | **恒返 200**(签名失败也 200) | 依服务 | PascalCase | / **`RequestId`** 每请求必返 |
| **飞书/字节** (开放平台) | **有:`{code,msg,data}`**(`code` 整型,`0`=成功) | `{code,msg}`(`code!=0` 即失败) | **恒返 200**(业务错在 `code`) | 依接口 | snake_case | / `logid`(X-Tt-Logid 头) |
| **华为云** | 无(资源直返;错误体独立) | `{error_code,error_msg,request_id}`(`error_code` 点分字符串) | **真实 4xx/5xx** | 依服务 | snake_case | / **`request_id`** / `X-request-id` |
| *标准* **RFC 7807→9457** | —(只管错误) | `{type,title,status,detail,instance}`+扩展成员,`application/problem+json` | HTTP 状态为主信号,`status` 仅副本 | — | — | `instance`=该次发生的 URI(≈ RequestId) |
| *标准* **JSON:API** | `{data | errors, meta, links}`(`data`/`errors` 不共存) | `errors:[{id,status,code,title,detail,source{pointer},links,meta}]` | HTTP 状态为主;每错误另带字符串 `status`+应用 `code` | 由 `links`/`meta` 约定 | — | 每错误 `id` 作追踪 |

**一手证据(主体 → 官方文档 URL):**

- Google:[AIP-131 资源直返](https://google.aip.dev/131)、[AIP-132 list](https://google.aip.dev/132)、[AIP-193 错误](https://google.aip.dev/193)、[AIP-158 分页](https://google.aip.dev/158)、[AIP-140 命名](https://google.aip.dev/140)、[AIP-185 版本](https://google.aip.dev/185)、[AIP-155 幂等](https://google.aip.dev/155)、[protobuf JSON 映射](https://protobuf.dev/programming-guides/json/)
- Microsoft:[api-guidelines(Graph)](https://github.com/microsoft/api-guidelines/blob/vNext/graph/GuidelinesGraph.md)、[Azure Guidelines](https://github.com/microsoft/api-guidelines/blob/vNext/azure/Guidelines.md)、[经典版(错误 §7.10)](https://github.com/microsoft/api-guidelines/blob/vNext/graph/Guidelines-deprecated.md)
- AWS:[awsJson1_1](https://smithy.io/2.0/aws/protocols/aws-json-1_1-protocol.html)、[restJson1](https://smithy.io/2.0/aws/protocols/aws-restjson1-protocol.html)、[query 协议](https://smithy.io/2.0/aws/protocols/aws-query-protocol.html)、[行为 traits(分页/幂等)](https://smithy.io/2.0/spec/behavior-traits.html)、[DynamoDB 错误](https://docs.amazonaws.cn/en_us/amazondynamodb/latest/developerguide/Programming.Errors.html)
- OpenAI:[API reference overview](https://developers.openai.com/api/reference/overview)、[错误示例(SDK issue)](https://github.com/openai/openai-python/issues/887)
- Stripe:[Errors](https://docs.stripe.com/api/errors)、[Pagination](https://docs.stripe.com/api/pagination)、[Idempotent requests](https://docs.stripe.com/api/idempotent_requests)、[Versioning](https://docs.stripe.com/api/versioning)
- Twilio:[响应格式](https://www.twilio.com/docs/usage/twilios-response)、[错误字典](https://www.twilio.com/docs/api/errors)
- GitHub:[分页](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)、[错误排查](https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api)、[入门(版本/请求 ID)](https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api)
- PayPal:[API Style Guide(镜像)](https://github.com/KuSh/api-standards/blob/master/api-style-guide.md)、[error.json/error_details.json](https://github.com/KuSh/api-standards/tree/master/v1/schema/json/draft-04)、[官网错误响应](https://developer.paypal.com/api/rest/responses/)
- Atlassian:[REST 设计指南 v1](https://developer.atlassian.com/server/framework/atlassian-sdk/atlassian-rest-api-design-guidelines-version-1/)、[Jira Cloud v3](https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro/)、[Marketplace v4](https://developer.atlassian.com/platform/marketplace/rest/v4/intro/)
- Zalando:[RESTful API Guidelines](https://opensource.zalando.com/restful-api-guidelines/)([#167 顶层 object](https://opensource.zalando.com/restful-api-guidelines/#167)、[#176 problem JSON](https://opensource.zalando.com/restful-api-guidelines/#176)、[#160 cursor](https://opensource.zalando.com/restful-api-guidelines/#160)、[#118 snake_case](https://opensource.zalando.com/restful-api-guidelines/#118)、[#114/#115 版本](https://opensource.zalando.com/restful-api-guidelines/#114)、[#233 X-Flow-ID](https://opensource.zalando.com/restful-api-guidelines/#233))
- Slack:[Web API 基础](https://docs.slack.dev/apis/web-api/)、[分页](https://docs.slack.dev/apis/web-api/pagination)、[速率限制](https://docs.slack.dev/apis/web-api/rate-limits)
- Shopify:[REST Admin](https://shopify.dev/docs/api/admin-rest)、[响应码](https://shopify.dev/docs/api/usage/response-codes)、[分页](https://shopify.dev/docs/api/usage/pagination-rest)、[版本](https://shopify.dev/docs/api/usage/versioning)
- Adyen:[错误码](https://docs.adyen.com/development-resources/error-codes)、[版本](https://docs.adyen.com/development-resources/versioning)、[幂等](https://docs.adyen.com/development-resources/api-idempotency)
- 阿里云:[OpenAPI 排错](https://www.alibabacloud.com/help/zh/openapi/troubleshooting)、[错误诊断](https://help.aliyun.com/zh/openapi/user-guide/api-error-diagnosis);阿里 Java 手册:[异常/Result 约定](https://www.pdai.tech/md/dev-spec/code-style/code-style-alibaba.html)
- 腾讯云:[API 3.0 响应结构(恒返 200)](https://cloud.tencent.com/document/api/551/15617)
- 飞书/字节:[通用错误码](https://open.feishu.cn/document/server-docs/api-call-guide/generic-error-code?lang=zh-CN)
- 华为云:[MPC 错误码](https://support.huaweicloud.com/api-mpc/mpc_04_0050.html)、[IAM 错误码](https://support.huaweicloud.com/api-iam5/ErrorCode.html)
- 标准:[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)(取代 [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807))、[JSON:API](https://jsonapi.org/format/)

---

## 三、维度①+③:成功信封 与 HTTP 状态码——分成两大流派

这是最需要拍板的一处。证据把世界清晰地分成两派:

### 流派 A:无信封 + HTTP 状态码为准(西方大厂 + IETF 标准的主流)

**做法**:成功就直接把资源当 body 返回,结果由 HTTP 状态码(200/201/204/4xx/5xx)表达;
列表最多加一个轻量分页壳。**15 家里 12 家如此**(Google/MS/AWS/OpenAI/Stripe/GitHub/PayPal/
Twilio/Zalando/Shopify/Adyen/Atlassian)。PayPal 甚至**明文禁止**在 2xx 里返错误体:

> "A server returning a status code in the `2xx` range MUST NOT return response following `error.json`."
> ——PayPal API Style Guide

**优点**:HTTP 缓存/条件请求、网关限流、客户端库的 `raise_for_status()`、监控按状态码告警——
全部按标准语义工作。**代价**:客户端要同时看状态码和 body,弱类型前端偶尔嫌"不统一"。

### 流派 B:通用信封 + 恒返 200(Slack + 中国部分厂商)

**做法**:所有响应套 `{ok}` 或 `{code, msg, data}`,且**无论成败都返 HTTP 200**,
真正的结果放 body 里的 `code`/`ok`。证据最硬的是腾讯云:

> "目前只要请求被服务端正常处理了,响应的 HTTP 状态码均为 200。"——腾讯云 API 3.0

飞书同理(`code=0` 成功,`code!=0` 也返 200)。Slack 是温和版:`{ok:boolean}` 但仍用 RPC 心智。

**为什么中式后端爱它**:前端一套 `if (res.code===0)` 拦截器走天下;网关/日志只需看一个 body 字段;
早期浏览器/代理对非 200 处理不一致的历史包袱。**代价(对本模板是硬伤)**:
- **掏空 HTTP 语义**:CDN/网关无法据状态码缓存或熔断;`404/409/422` 的语义丢失。
- **重试与幂等失真**:标准重试中间件依赖 5xx,恒 200 让它失效。
- **监控失真**:"HTTP 200 率"永远 100%,SRE 得改看 body。
- 与本模板"面向标准、可审计"的定位冲突。

> **`aipersimmon-ddd` 的取向:流派 A。** 一个强调边界清晰、可审计、面向标准的 DDD 模板,
> 应保住 HTTP 状态码语义。恒返 200 的信封不是"更统一",是"把标准协议降级成自定义 RPC"。

### 一个常见混淆:成功信封 ≠ 分页壳

反对"通用成功信封",**不等于**反对列表的分页壳。Stripe/OpenAI 的 `{object:"list", data, has_more}`
是**针对集合**的、承载分页游标的结构,单资源仍直返。这属于维度④,应当有(见 §六)。

---

## 四、维度②:错误格式——正在向 RFC 9457 收敛,别造第 16 种

错误体是各家分歧最大、但**标准化程度最高**的一处。把它们归类:

| 错误体流派 | 代表 | 形态 |
| --- | --- | --- |
| **RFC 7807/9457 Problem Details** | Zalando(强制)、Spring 原生 | `{type,title,status,detail,instance}`+扩展成员,`application/problem+json` |
| `error` 包裹的对象 | Google、Microsoft、OpenAI、Stripe | `{error:{code/type,message,...}}` |
| 扁平对象 | Twilio、华为云、阿里云、Adyen | `{code/errorCode, message, ...}` |
| 品牌私有富对象 | PayPal、GitHub | `{name/message, details/errors:[{field,issue/code}]}` |
| 跨语言资源规范 | JSON:API | `errors:[{id,status,code,title,detail,source{pointer}}]` |

**关键观察**:
- **RFC 7807 已被 RFC 9457 取代**(两者五个成员字段完全一致),它是**唯一有 IETF 背书、
  且被主流框架原生实现**的错误格式。Spring 6/Boot 3 的 `ProblemDetail` 就是它。
- 各家私有错误体的**共性**其实就是 Problem Details 的超集:一个机器可读的错误标识
  (`type`/`code`/`name`)、一个人类可读消息(`message`/`detail`)、一个可选的字段级明细数组
  (`details`/`errors`)、一个追踪锚(`instance`/`RequestId`/`debug_id`)。
- **字段级校验错误**几乎人人都有一个 `details[]`/`errors[]`,元素带 `field`/`pointer` + `issue`/`code`
  (PayPal、GitHub、Google `ErrorInfo`、JSON:API `source.pointer`、MS `innererror`)。
  Problem Details 的**扩展成员**机制正好容纳它(挂一个 `errors` 扩展数组即可)。

> **取向:统一走 RFC 9457 Problem Details**,用扩展成员承载①字段级校验明细、②`traceId`、
> ③本项目的领域错误码。这样既对齐标准、又不丢自家的错误目录能力,且 Spring 零成本支持。

---

## 五、差距分析:对照 `aipersimmon-ddd` 构件库现状

> 说明:仓库里的 `bc-and-layer-samples` / `*-scaffold-samples` 只是演示,**不作为任何设计参考**。
> 本节的基准是**构件库本身**([[analysis-00006-ddd-building-blocks-library]] §三 的模块清单),
> 结论只由 §二~§四 的大厂证据 + IETF 标准 + 本库的"纯/脏"硬约束推出。

关键事实——**构件库当前根本没有 interface(Web)层构件**。§三 of
[[analysis-00006-ddd-building-blocks-library]] 的清单里有 `-core`/`-application`/`-cqrs`/`-cqrs-spring`/
`-integration`/`-events-spring`/outbox·inbox·messaging/`-archunit`/`-bom`,**唯独没有任何一个负责
"入站 HTTP 适配"的模块**。所以"缺封装"是**整层缺失**,而不是"某个类没抽好"。

现有的两块**可复用抓手**(都在纯净层,可直接对接):`aipersimmon-ddd-core` 已有 `DomainException` 基类、
`aipersimmon-ddd-application` 已有 `ApplicationException` 基类——这正是异常→HTTP 映射的**上游锚点**。

**差距(即"缺的封装",按证据判断该不该补):**

| 缺什么 | 为什么(证据 / 原则) | 该不该补 / 补在哪 |
| --- | --- | --- |
| **异常 → RFC 9457 `ProblemDetail` 的中心映射** | 12/15 大厂用真实状态码;错误格式向 RFC 9457 收敛(§四);`DomainException`/`ApplicationException` 已在纯层,缺的只是到 HTTP 的映射策略 | ✅ 补:starter 里一个共享 `@RestControllerAdvice` + 错误码目录 |
| **错误码 / `ProblemType` 目录** | 各厂私有错误体的共性就是"机器码 + 人类消息 + 字段明细 + 追踪锚"(§四) | ✅ 补:目录抽象放**纯**契约模块;映射放 starter |
| **统一分页约定** | 新接口普遍偏 cursor,Zalando SHOULD avoid offset(§六④) | ✅ 补:`Page`/`Slice`/`Cursor` 值对象(纯)+ 序列化(starter) |
| **`traceId` / `RequestId` 贯穿** | RequestId 是云厂商通用共识,≈ RFC 9457 `instance`(§六⑥) | ✅ 补:starter 里的 filter + 写入 ProblemDetail 扩展成员 |
| **(可选)`Idempotency-Key`** | Stripe/PayPal/Adyen/Google/AWS 都有写操作幂等键(§六⑥) | ⚠️ 可选:与 [[analysis-00006-ddd-building-blocks-library]] 的 inbox 幂等联动 |
| ~~通用成功信封 `{code,message,data}`~~ | 15 家里 12 家不套;PayPal 明文禁止;恒返 200 掏空 HTTP 语义(§三) | ❌ **不补** |
| ~~通用 `ApiRequest` 外壳~~ | 无一家把请求套通用外壳;请求 DTO 本就该随用例走 | ❌ **不补** |

> 直白说:用户说"缺 `ApiResponse`/`ApiRequest`"——**构件库确实缺一整个 interface 层构件**;
> 但按证据,该补的是"RFC 9457 映射 + 错误码目录 + 分页 + traceId 这套横切的可复用 starter",
> **不是**通用 `{code,message,data}` 成功信封(`ApiResponse`)或通用请求外壳(`ApiRequest`)——后两者按大厂惯例不是最佳实践。

---

## 六、维度④⑤⑥速览:分页 / 命名 / 版本 / 追踪 / 幂等

- **④ 分页**:大厂新接口普遍偏**游标(cursor)**(Google/Stripe/OpenAI/AWS/Zalando/Slack/Shopify),
  offset 逐渐被视为反模式(Zalando #160 明说 SHOULD avoid offset)。GitHub/Shopify 用 **Link 头**。
  建议:提供一个 framework-free 的 `Page<T>`/`Slice<T>`(带 `items` + `nextCursor`)值对象,
  游标为主、offset 为兼容;序列化到响应放 starter。
- **⑤ 命名**:阵营分裂——snake_case(Zalando 强制、Stripe/GitHub/PayPal/OpenAI/华为/飞书) vs
  camelCase(Microsoft 强制、Google 的 JSON、Adyen)。**没有唯一正解**,重点是**在模板里定一个默认并强制一致**。
  Spring/Jackson 默认 camelCase;若无强诉求,顺 Jackson 默认 camelCase,用 `@JsonNaming` 全局可切。
- **⑥ 版本**:URL 路径(Google/PayPal/Adyen/Shopify) vs Header(GitHub/Stripe/MS/Zalando 的媒体类型)。
  路径版本最直观、对模板最友好(与 Shopify/Google 一致);Zalando 反 URL 版本是少数派。
- **⑥ 追踪**:**RequestId 是云厂商的通用共识**(阿里云/腾讯云/华为云/GitHub/MS/AWS 全有),
  等价于 RFC 9457 的 `instance`。建议 starter 生成/透传一个 `traceId`,写入日志 + ProblemDetail 扩展成员。
- **⑥ 幂等**:Stripe(`Idempotency-Key`)、PayPal(`PayPal-Request-Id`)、Adyen(`idempotency-key`)、
  Google(`request_id`)、AWS(`ClientToken`)都有客户端幂等键。建议**可选**,并与
  [[analysis-00006-ddd-building-blocks-library]] 的 inbox 去重、[[analysis-00005-structure-2-event-flow-and-cqrs]]
  的命令总线联动,避免重复造。**注意:幂等 ≠ 防重放,见 §六B。**

---

## 六B、安全维度:防重放攻击(replay)≠ 幂等(idempotency)

> 这是初版遗漏、必须补上的一维。**幂等键防不住重放攻击,防重放也不保证业务幂等**——两者关注点不同,不可互相替代。

- **幂等(可靠性)**:让**已授权的合法请求**重试只生效一次(同 `Idempotency-Key` 返首次结果)。防的是"网络抖动/客户端重发"。
- **防重放(安全)**:阻止攻击者**截获一个合法且已签名的请求后重复发送**。防的是"中间人重放"。手段是
  **签名 + 时间戳 + (可选)nonce**:服务端按**时间窗**拒绝过期请求,并对 nonce 去重。攻击者每次可换新幂等键,所以幂等键顶不上这层。

### 大厂证据:时间窗 + 签名为主流,nonce 去重是"更强档"

| 方案 | 时间戳头/参 | nonce | 精确时间窗 | 是否要求 nonce 去重 |
| --- | --- | --- | --- | --- |
| **AWS SigV4** | `x-amz-date` / `X-Amz-Date` | 无 | **15 分钟**(超出报 `RequestTimeTooSkewed`);预签名 `X-Amz-Expires` 最长 **7 天** | 否(无 nonce) |
| **腾讯云 TC3** | `X-TC-Timestamp`(UTC+0) | 无(旧 v1 有 `Nonce`) | **5 分钟(300s)** | 否 |
| **阿里云 ACS3** | `x-acs-date` / RPC `Timestamp` | `x-acs-signature-nonce` / `SignatureNonce`("防重放的唯一随机数") | **15 分钟** | **是**(要求每请求唯一) |
| **Slack**(验入站请求) | `X-Slack-Request-Timestamp`(+`X-Slack-Signature`) | 无 | **5 分钟(300s)** | 否 |
| **Stripe webhook** | `Stripe-Signature` 的 `t=`(+`v1=`) | 无 | **5 分钟(300s)** 库默认容差(禁设 0) | 否 |
| **OAuth 1.0**(RFC 5849 §3.3) | `oauth_timestamp` | `oauth_nonce` | 服务端自定(无固定值) | **是**(nonce 是主防线) |
| **RFC 9421** HTTP Message Signatures | `created` / `expires` | `nonce` | 由验证方策略定(§7.2.2 Signature Replay) | **是**(验证方须跟踪 nonce) |
| **GitHub webhook**(反面对照) | **无** | GUID `X-GitHub-Delivery`(消费端自跟踪,重投时复用) | **无时间窗** | 仅客户端侧 |
| **微信支付 v3** | `Wechatpay-Timestamp` | `Wechatpay-Nonce` | **5 分钟(300s)** | 否(以时间窗为防线) |

一手证据:[AWS SigV4 日期处理](https://docs.aws.amazon.com/general/latest/gr/sigv4-date-handling.html)、
[S3 15 分钟窗/`RequestTimeTooSkewed`](https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html);
[腾讯云 TC3(5 分钟)](https://www.tencentcloud.com/document/product/845/32207);
[阿里云 V3 结构与签名(`x-acs-signature-nonce`,15 分钟)](https://www.alibabacloud.com/help/en/sdk/product-overview/v3-request-structure-and-signature);
[Slack 验请求(5 分钟)](https://docs.slack.dev/authentication/verifying-requests-from-slack/);
[Stripe webhook 签名(5 分钟容差)](https://docs.stripe.com/webhooks/signature);
[OAuth 1.0 RFC 5849 §3.3](https://datatracker.ietf.org/doc/html/rfc5849#section-3.3);
[RFC 9421 §7.2.2 Signature Replay](https://datatracker.ietf.org/doc/html/rfc9421#section-7.2.2);
[GitHub webhook 最佳实践(用 `X-GitHub-Delivery` 防重放)](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks);
[微信支付 v3(5 分钟)](https://pay.weixin.qq.com/doc/v3/partner/4013059030)。

### 归纳(直接影响落位)

- **主流是"签名 + 时间戳 + 固定时间窗"**:业务/回调类多用 **5 分钟(Slack/Stripe/腾讯云/微信支付)**;
  基础设施签名多用 **15 分钟(AWS/阿里云)**。**真·单次 nonce 去重**由协议标准(OAuth 1.0、RFC 9421)与阿里云要求,
  但 AWS/腾讯/Slack/Stripe **仅靠时间窗**——即"窗内可重放"。
- **它是鉴权/网关层或 webhook 接收层的职责**,在请求进入业务逻辑**之前**校验签名、时钟偏差、(有则)nonce——
  **与应用层幂等分属不同层**,`aipersimmon-ddd` 落位需据此区分(见 §七与决策)。构件可作为 **opt-in** 提供,
  不假设人人有网关;开了也能与网关叠加(纵深防御)。

---

## 六C、限流 / CORS / 鉴权错误(补齐的三维)

这三项是 Web 层横切标配,初版分析未展开,一并补上证据。

### 限流:`429` + `Retry-After` + `RateLimit-*` 头

- **状态码 `429 Too Many Requests`** 由 **RFC 6585 §4** 定义;响应 MAY 带 `Retry-After`,且 429 不应被缓存。
  [RFC 6585](https://datatracker.ietf.org/doc/html/rfc6585)
- **`Retry-After`** 由 **RFC 9110 §10.2.3** 定义,两种形式:`delay-seconds`(如 `120`)或 HTTP-date。
  [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)
- **IETF 正在标准化 `RateLimit` / `RateLimit-Policy`** 结构化字段(draft-ietf-httpapi-ratelimit-headers,
  当前 -11 版收敛到这两个;附录 D 记录了历史上非标准的 `X-RateLimit-*`)。
  [draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/)
- 大厂现状(头名/状态码,注意大小写各异):**GitHub** `x-ratelimit-limit/remaining/reset/used`,超限 **403 或 429**,
  部分带 `retry-after`([docs](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api));
  **Slack** `429` + `Retry-After`([docs](https://docs.slack.dev/apis/web-api/rate-limits/));
  **Stripe** `429`,错误 `type=rate_limit_error`([docs](https://docs.stripe.com/rate-limits));
  **Twitter/X** `x-rate-limit-*`,`429`([docs](https://docs.x.com/x-api/fundamentals/rate-limits))。
- **结论**:限流出 `429` + `Retry-After`,响应头优先用 `RateLimit`/`RateLimit-Policy`(兼容旧 `X-RateLimit-*`),
  body 仍是 Problem Details。限流常在网关做,但应用侧可作细粒度兜底。

### CORS:浏览器强制,服务端只声明策略

- 权威规范是 **WHATWG Fetch Standard(CORS protocol)**,已取代旧 W3C CORS 建议。
  [Fetch](https://fetch.spec.whatwg.org/#http-cors-protocol) ;[MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS)
- 关键响应头:`Access-Control-Allow-Origin` / `-Allow-Methods` / `-Allow-Headers` / `-Allow-Credentials` /
  `-Max-Age` / `-Expose-Headers`;预检用 `OPTIONS`(带 `Access-Control-Request-Method/Headers`)。
- **CORS 由浏览器强制**:服务端只声明策略,不自行拦截;`Allow-Origin:*` 与 `Allow-Credentials:true` 叠加是常见安全坑。

### 鉴权错误:401/403 也应表达为 Problem Details

- **语义(RFC 9110)**:`401 Unauthorized` §15.5.2 = "缺少有效认证凭证"(未认证);
  `403 Forbidden` §15.5.4 = "服务器理解但拒绝授权"(已认证但无权)。
  [§15.5.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2)、[§15.5.4](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.4)
- Zalando 要求错误响应统一 problem+json,**不为鉴权错误开豁免**([指南](https://opensource.zalando.com/restful-api-guidelines/))。
- **Spring 的坑(重要)**:`spring.mvc.problemdetails.enabled` **只覆盖经 DispatcherServlet 的 MVC 异常**;
  Spring Security 的 401/403 在**过滤器链**里先发生,**不会**自动变 problem+json——需自定义
  `AuthenticationEntryPoint` / `AccessDeniedHandler`。
  [Spring 参考](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)、
  [spring-security#15549](https://github.com/spring-projects/spring-security/issues/15549)

---

## 七、落位建议:契约 + starter + 可插拔存储(mirror outbox)

严格承接 [[analysis-00006-ddd-building-blocks-library]] 的铁律——**HTTP 是 interface 层关注点,
绝不能污染 framework-free 的 domain**。§六~§六C 的横切能力里,**错误映射、traceId、分页、幂等、防重放、
限流**纳入本期,每项 **opt-in**;需要状态者(幂等 key / nonce / 限流计数)共用一套 SPI + 可换存储——
与 outbox `-outbox`+`-outbox-jdbc`/`-mybatis-plus` 完全同构。**CORS 用 Spring 原生 / 网关,不进构件;
401/403→Problem Details 列为未来项**(理由见下与 [[decision-00007-web-api-response-envelope]] §六)。拆成三档:

| 模块(建议名) | 层 / 性质 | 依赖 | 内容 |
| --- | --- | --- | --- |
| `aipersimmon-ddd-web`(纯契约) | interface 契约,**framework-free** | `-core`(极薄) | 错误码/`ProblemType` 目录;`Page<T>`/`Slice<T>`/`Cursor` 值对象;`ApiError` 语义模型(对齐 9457,不绑 Spring);**横切 SPI**:`IdempotencyStore`/`ReplayGuard`/`RateLimiter`/`RequestSignatureVerifier` |
| `aipersimmon-ddd-web-spring`(脏 starter) | interface 实现 | `-web` + Spring Web | `@RestControllerAdvice` 异常→`ProblemDetail`(含 429);`traceId` filter;分页序列化;i18n;各能力 filter + **开关** + 默认内存 SPI 实现 |
| `aipersimmon-ddd-web-store-redis` / `-web-store-jdbc`(可换后端) | infrastructure | `-web` + Redis / JDBC | 幂等/nonce/限流计数的 Redis(TTL、原子 INCR、令牌桶) 或 JDBC 实现;同 SPI 可互换 |

- **与既有 starter 对称**:正如 `-cqrs`+`-cqrs-spring`、outbox 的"契约 + 可换存储",
  `-web` 纯 + `-web-spring` 脏 + `-web-store-*` 可换,不破坏 [[analysis-00006-ddd-building-blocks-library]] 的纯净性硬约束。
- **装配确定性**:store 后端在 classpath → 用它;否则内存兜底(`@ConditionalOnMissingBean`)。内存实现仅单机/开发;
  多实例生产须显式引入一个 `-web-store-*`(与 outbox 存储选择同理)。
- **拓扑无关**:三种拓扑复用同一批 web 构件。**可选**:最小形态可完全不引、各 BC 自写。
- **三样明确不做**(反最佳实践,非成本问题):通用 `{code,message,data}` 成功信封、恒返 200 的中式包裹、
  通用 `ApiRequest` 外壳。若确有 BFF/前端聚合诉求,应做成**独立、opt-in 的适配器**,不得作为纯契约层默认。
- **两样列为未来项**(该做、非反最佳实践,只是不进本期):**CORS**(Spring 原生 `CorsConfigurationSource`/
  网关即可,构件不重复封装声明式配置)、**401/403→Problem Details**(需引 Spring Security,且 §六C 那处
  过滤器链的坑使其非平凡;将来作 `@ConditionalOnClass(spring-security)` 的条件化增量补齐)。
- 其余本期横切能力**一律提供**,只是默认关 + 可插拔(细节见 [[decision-00007-web-api-response-envelope]] §三/§六)。

---

## 八、遗留问题(交给 `docs/decision/` 定夺)

本分析不下最终决策,以下留给决策文档 + 后续 spec:

1. **字段命名默认值**:camelCase(顺 Jackson/Spring) vs snake_case(顺多数大厂)——二选一并全局强制。
2. **版本策略**:URL 路径 `/v1/`(推荐,对齐 Google/Shopify) vs Header——定一个默认。
3. **幂等键**是否进 v1,以及与 inbox([[analysis-00006-ddd-building-blocks-library]])的边界。
4. **模块命名**:`-web` / `-web-spring` vs `-interface` / `-rest-spring`——与既有 `-integration`(集成事件契约,
   见 [[analysis-00002-domain-vs-integration-events]])避免语义混淆。
5. **错误码目录**的组织:枚举 vs 常量 vs 配置化,以及 i18n。
6. **防重放攻击(§六B)的落位与范围**:签名请求的防重放归鉴权/网关层还是进构件?webhook 接收端的
   时间戳容差校验(推荐 5 分钟)是否做成 `-web-spring` 的可选 helper?与幂等键的边界如何在文档中明确区分?

---

## 附:证据可信度说明(诚实记录)

- 上述字段名与示例,凡标"verbatim"来源的(Stripe 列表壳与幂等 curl、Google AIP-193 错误、
  腾讯云/飞书响应体、RFC 9457 与 JSON:API 示例、Zalando 规则编号)均取自**官方一手文档**。
- 少数示例块为**依官方字段级文档重建**(Twilio 错误 JSON、Adyen `ServiceError` 示例、
  Shopify 字段键 422、阿里 `Result<T>` 具体字段)——**字段名可信,具体字符串值仅作示意**,
  产品化前应对实时响应核验。
- PayPal 官方 repo `paypal/api-standards` 已归档,`error.json` schema 引自可信镜像;字段与其线上 API 一致。
- Microsoft 有三份并存文档(经典/Azure/Graph),分页令牌拼写(`@odata.nextLink`/`nextLink`/`@nextLink`)
  与部分字段随文档而异,引用时需绑定目标平台。AWS 字段大小写**逐服务而异**,勿一概而论。
