---
id: issue-00065-a-missing-request-parameter-is-reported-as-a-server-error
type: issue
status: resolved
parent: plan-00015-scaffold-depth-and-evaluability
---

# 少传一个查询参数返回 500：参数类失败不在 RFC 9457 的覆盖范围内

## 问题（现状，file:line 为证）

- **等级：Medium（把客户端错误报成服务端错误。调用方据此重试一个永远不会成功的请求，
  而运维侧看到的是一条 5xx 告警）**。
- 触发：`GET /orders?customerId=…` 落地后（plan-00015 F3），漏掉 `customerId` 直接得到 **500**，
  body 是 `about:blank` 的兜底问题文档。同理 `?size=many` 这类类型不匹配也是 500。
- `AipersimmonDddWebExceptionHandler` 覆盖了同一族里的其它情形，独独缺参数：
  - `:120` `NoResourceFoundException` → 404
  - `:126` `HttpRequestMethodNotSupportedException` → 405
  - `:132` / `:138` 媒体类型 → 415 / 406
  - `:147` `HttpMessageNotReadableException`（body 不可解析）→ 400
  - **没有** `MissingRequestValueException`（`MissingServletRequestParameterException`、
    `MissingRequestHeaderException`、`MissingPathVariableException` 的父类）
  - **没有** `MethodArgumentTypeMismatchException`
  - 于是两者都落到 `:152` 的 `@ExceptionHandler(Exception.class)` → 500。

## 根因（第一性）

1. **观察 vs 期望**：期望"请求本身有毛病 ⇒ 4xx"；实际"某几种请求毛病 ⇒ 5xx"。
2. **最小机制**：兜底 `@ExceptionHandler(Exception.class)` 按定义会接住任何没被更具体处理器认领的异常。
   Spring 把"缺参数/参数转不了型"表达为一个**普通异常**，它没被认领，所以被兜底接走，
   而兜底的语义是"我们这边出错了"。
3. **真根因**：issue-00045 那一轮补的是**路由层**的失败（路径、方法、媒体类型）与**请求体**的失败，
   参数绑定这一层被漏掉了。判据本应是"这个失败是客户端造成的还是服务端造成的"，
   实际用的判据是"它出现在我列举过的那几个位置吗"——枚举而非分类，所以必然漏。
4. **为什么一直没被发现**：在这次之前，样例的所有端点都只用 `@PathVariable`（必然存在，否则不匹配路由）
   和 `@RequestBody`（由 `HttpMessageNotReadableException` 覆盖）。**没有任何一个端点带查询参数**，
   于是这条路径从未被走过。F3 加的第一个列表端点就撞上了。
5. **排除的伪根因**：不是 `@Valid`/Bean Validation 没生效——`MethodArgumentNotValidException` 与
   `HandlerMethodValidationException` 都已被 `:75` / `:89` 处理；缺参数在校验之前就发生了，
   根本没走到校验。

## 复现（test-first）

`WebLayerTest`（库）新增两条，先红后绿：

```java
mvc.perform(get("/test/list").param("size", "10"))          // 缺 customerId
   .andExpect(status().isBadRequest());
mvc.perform(get("/test/list").param("customerId", "C").param("size", "many"))
   .andExpect(status().isBadRequest());
```

样例侧同样有一条端到端用例（`ExceptionContractTest#aMissingQueryParameterRenders400NotFallback500`）。

## 修复

在兜底之前加一个处理器，按"客户端造成的"归类，而不是按位置枚举：

```java
@ExceptionHandler({MissingRequestValueException.class, MethodArgumentTypeMismatchException.class})
public ProblemDetail handleBadRequestParameter(Exception ex) {
  return factory.simple(HttpStatus.BAD_REQUEST, ex.getMessage(), List.of());
}
```

用 `MissingRequestValueException` 这个父类，是为了同时覆盖 header / path variable / cookie /
matrix variable，而不是只补今天撞到的那一个子类——否则下一次仍会以同样的方式漏。
`ex.getMessage()` 照 405/415 的既有做法回显：它描述的是调用方的请求，不泄漏内部细节。

## 验证结果

已修。

- **先红**：样例的 `ExceptionContractTest` 在 F3 加上列表端点后立刻变红——
  `wrongMethodRenders405NotFallback500` 报 `Status expected:<405> but was:<500>`。
  该用例原本用 `GET /orders` 当"未映射的方法"，端点存在后它变成了"缺参数"，
  于是**一条已有测试意外地成了本缺陷的第一份复现**。它已改用 `DELETE /orders` 恢复原意图。
- **后绿**：库 `WebLayerTest` 两条新用例通过；样例
  `aMissingQueryParameterRenders400NotFallback500` 通过；两侧全量绿。

## 关联

- [[issue-00045]]（路由/方法/媒体类型/请求体的同族修复；本 issue 是它漏掉的一层）
- [[plan-00015-scaffold-depth-and-evaluability]]（F3 的列表端点是第一个带查询参数的端点）
