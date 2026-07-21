---
id: issue-00045-web-handler-maps-unknown-route-to-500
type: issue
role: main
status: resolved
parent: design-00003-exception-model
---

# Web 全局异常处理把"未知路由/错误方法"兜底成 500,应为 404/405;健康探针因此不可用

## 问题(现状,file:line 为证)

- **等级:框架真实缺陷**(库侧 `aipersimmon-ddd-web-spring`,影响所有基于该 advice 的应用)。
- 现象:**任何未映射路径或错误方法都返回 500**(实测,应用启动于 8090):
  - `GET /nope` → **500**
  - `GET /totally-unmapped-path` → **500**(body 为 `about:blank` ProblemDetail,`status:500`)
  - `GET /orders`(集合仅映射 `POST`,GET 应为 405)→ **500**
  - `GET /actuator/health` → **500**
- 服务端真实原因(`--logging.level.org.springframework.web=DEBUG` 抓取):
  ```
  Using @ExceptionHandler com.aipersimmon.ddd.web.spring.AipersimmonDddWebExceptionHandler#handleUnexpected(Exception)
  Resolved [org.springframework.web.servlet.resource.NoResourceFoundException: No static resource actuator/health.]
  Writing [ProblemDetail ... status=500 ...]
  ```
  即未知 URL 落到静态资源处理器抛 `NoResourceFoundException`,被 advice 的**兜底 `Exception` 分支**吞成 500。
- 处理器只识别 JDK 的 `NoSuchElementException` 为 404,未处理 Spring 的路由级异常:
  ```java
  // AipersimmonDddWebExceptionHandler.java
  :93  @ExceptionHandler(NoSuchElementException.class)          // → 404
  :94  public ProblemDetail handleNotFound(...) { ... }
  :98  @ExceptionHandler(Exception.class)                       // 兜底:一切其它异常 → 500
  :101 return factory.simple(HttpStatus.INTERNAL_SERVER_ERROR, null, List.of());
  ```
  缺 `NoResourceFoundException`(→404)、`HttpRequestMethodNotSupportedException`(→405)、
  `HttpMediaTypeNotSupportedException`(→415)等的映射;且该类是普通 `@RestControllerAdvice`,
  **未继承 `ResponseEntityExceptionHandler`**(否则这些协议级异常会有正确默认状态)。其 Javadoc 第 33 行虽称
  "not-found to 404",实际只覆盖 `NoSuchElementException`,不覆盖框架路由级 not-found。
- 次生问题:`spring-boot-starter-actuator` **不在类路径**
  (`mvn -pl start dependency:tree -Dincludes=org.springframework.boot:spring-boot-starter-actuator` 空结果),
  故 `/actuator/**` 根本无映射——`/actuator/health` 的 500 正是"未知路由→500"的一个实例。
  结果:**liveness/readiness/health 探针完全不可用**,在 k8s 等编排下会直接导致就绪/存活探测失败。

## 根因(第一性)

1. **观察 vs 期望**:design-00003 把 `NOT_FOUND→404` 列为一等 Problem Family
   (`design-00003-exception-model.md:322-330`,含 `EntityNotFoundException`/`NoSuchElementException`→404、
   兜底 `Exception`→500);最基本的 404——"路由不存在"——却被实现映射成 500,与设计意图相悖。
2. **最小机制**:`handleUnexpected(Exception)` 是 catch-all,拦截了本应各有语义状态的 Spring 协议级异常
   (`NoResourceFoundException`/`HttpRequestMethodNotSupportedException` 等),统一压成 500。
3. **真根因**:advice 只枚举了业务/校验/JDK-not-found 异常,**遗漏了框架路由层异常的状态映射**,
   又未借 `ResponseEntityExceptionHandler` 提供的默认映射,导致"未知 URL/错误方法/缺失端点"全部退化为 500。
4. 独立但叠加的配置缺口:未引入 actuator starter,使健康探针缺失;叠加根因 3,症状表现为误导性的 500 而非 404。

## 复现

```bash
cd aipersimmon-ddd-scaffold/multi-module/start
docker compose -f compose.yaml up -d db kafka
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.docker.compose.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/ordering \
  --spring.datasource.username=postgres --spring.datasource.password=postgres \
  --spring.kafka.bootstrap-servers=localhost:9092 --otel.sdk.disabled=true"
curl -s -o /dev/null -w "%{http_code}\n" localhost:8090/nope             # 期望 404,实得 500
curl -s -o /dev/null -w "%{http_code}\n" localhost:8090/orders           # 期望 405,实得 500(集合仅 POST)
curl -s -o /dev/null -w "%{http_code}\n" localhost:8090/actuator/health  # 期望 200/404,实得 500
```

## 建议修复(未实施)

**澄清:不需要继承 `ResponseEntityExceptionHandler`。** `@RestControllerAdvice` + `@ExceptionHandler`
本就能处理这些路由级异常;当前之所以落到 500,只是因为 advice 里对 `NoResourceFoundException` /
`HttpRequestMethodNotSupportedException` **没有更具体的处理器**,`ExceptionHandlerExceptionResolver`
按"异常类型最近匹配"只能选中兜底的 `@ExceptionHandler(Exception.class)`。继承基类只是一种**便利**,不是必需。

本系统的返回体不是 Spring 原生 `ProblemDetail`,而是项目自有的 `ApiError` 模型,统一经
`ProblemDetailFactory` → `ProblemDetailMapper` 产出:标准成员 `type/title/status/detail/instance` +
扩展属性 `code` / `requestId` / `traceId` / `errors`(`ProblemDetailMapper.java:30-41`;requestId/traceId
来自 `RequestIdFilter` 写入的 MDC)。任何修法都必须让新增的 404/405/… 走同一 `factory`,否则 shape 分叉。

**方案 A(首选,与现体系一致、低风险)** —— 在现有 advice 上补显式 `@ExceptionHandler`,各自 `return factory.simple(status, detail, List.of())`:
- `NoResourceFoundException` → 404
- `HttpRequestMethodNotSupportedException` → 405
- `HttpMediaTypeNotSupportedException` → 415、`HttpMediaTypeNotAcceptableException` → 406、
  `HttpMessageNotReadableException`(请求体畸形/JSON 解析失败) → 400
- 具体处理器精确匹配,天然胜过兜底 `Exception`;`handleUnexpected` 从此只兜真正未知的异常。
- 代价:需**逐个枚举**协议级异常,漏项仍会掉回 500(但实际集合有限,如上)。

**方案 B(更"标准/完整",但需与本体系适配)** —— 继承 `ResponseEntityExceptionHandler`,一次性获得整族标准 MVC 异常的处理器。**但它并不开箱契合本体系**:
- 基类的处理器产出的是**原生 `ProblemDetail`**(`type=about:blank` + title/status/detail/instance),
  **不带** `code`/`requestId`/`traceId`/`errors` —— 与全站 `ApiError` shape 不一致。
- 要一致就**仍需手动**:覆写唯一的漏斗方法 `handleExceptionInternal(ex, body, headers, status, request)`
  (或 `createResponseEntity`),把 body 重建为经 `ProblemDetailFactory`/`ApiError` 的形状。好处是只此**一处**覆写,而非逐异常。
- 另需注意**歧义处理器**:基类已声明 `MethodArgumentNotValidException`/`HandlerMethodValidationException`
  的处理器,而本 advice 也有(产出自定义 `errors` 数组);继承后必须把它们改成 `@Override` 基类方法,
  否则同一异常类型两个处理器会导致启动期 `Ambiguous @ExceptionHandler`。
- 结论:B 是教科书式的全族覆盖,面向未来更省心;但因本项目刻意采用了自有 `ApiError` shape 与普通 advice,
  采用 B 等于"1 处 body 覆写 + 把现有校验处理器重构为覆写",churn 与行为漂移风险都更高。

> 取舍:仅为关闭 404/405 缺口 → 选 A(改动小、与现有 `ProblemDetail`+`factory` 风格一致)。
> 若团队想要协议层异常全族统一语义与 body、且愿意做上述适配 → B 更完整,但**不是零成本、也不是"继承即好"**。

**其余两步(与 A/B 无关):**
1. **引入 actuator**(样例/起步侧):加 `spring-boot-starter-actuator`,暴露 health/liveness/readiness;
   确认端点在补齐映射后返回正常(不再被兜底成 500)。
2. **加回归测试**:`ExceptionContractTest` 增加"未知路径→404""错误方法→405""(引入 actuator 后)health→200"三条断言。

## 修复(已实施 · 方案 A)

1. **库侧补齐路由级异常映射** —— `AipersimmonDddWebExceptionHandler`(`aipersimmon-ddd-web-spring`)在兜底
   `handleUnexpected(Exception)` 之前新增 5 个具体 `@ExceptionHandler`,各自 `return factory.simple(status, …)`
   走同一 `ProblemDetailFactory`/`ApiError`,shape 与全站一致:
   - `NoResourceFoundException` → **404**(detail 中性:"No endpoint matched the request.",不暴露"static resource"机制);
   - `HttpRequestMethodNotSupportedException` → **405**;
   - `HttpMediaTypeNotSupportedException` → **415**;
   - `HttpMediaTypeNotAcceptableException` → **406**;
   - `HttpMessageNotReadableException` → **400**(detail 中性:"Malformed request body.",不泄露解析器内部)。
   具体处理器精确匹配,天然胜过 `Exception` 兜底;类 Javadoc 同步补充这一族映射说明。未继承 `ResponseEntityExceptionHandler`。
2. **样例引入 actuator** —— `start/pom.xml` 加 `spring-boot-starter-actuator`,`/actuator/health` 等探针端点上线。
3. **回归测试** —— `ExceptionContractTest` 新增三条:`unknownPathRenders404NotFallback500`(未知路径→404 + `application/problem+json`)、
   `wrongMethodRenders405NotFallback500`(`GET /orders`→405)、`healthEndpointIsReachableAndUp`(`/actuator/health`→200 且 `status:UP`)。

## 验证结果

- 库侧 `aipersimmon-ddd-web-spring` `mvn install`:18 tests,0 失败,已安装 `0.1.0-SNAPSHOT`。
- 样例全反应堆 `mvn test`(20 模块,Testcontainers 真实 PG+Kafka):**BUILD SUCCESS**。
- `ExceptionContractTest`:**9 tests,0 失败**(原 6 + 新 3);三条新断言经真实 `DispatcherServlet` 走通了
  当初触发 500 的 `NoResourceFoundException`/`HttpRequestMethodNotSupportedException` 路径,现分别为 404/405,健康探针 200 UP。

## 关联

- [[design-00003-exception-model]](NOT_FOUND→404 一等族;本条为其未落地)
- [[record-00001-multi-module-ddd-integration-verification]](F-2 完整证据)
