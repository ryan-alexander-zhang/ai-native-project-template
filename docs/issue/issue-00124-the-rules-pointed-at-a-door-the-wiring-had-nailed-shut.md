---
id: issue-00124-the-rules-pointed-at-a-door-the-wiring-had-nailed-shut
type: issue
role: main
status: resolved
parent: issue-00119-ten-majors-were-never-scheduled
---

# 架构规则指向的那扇门，被装配钉死了

`issue-00119` 排期第 5 档。

## 症状

handler 用构造器注入 `CommandBus`，上下文起不来：

```
BeanCurrentlyInCreationException: Error creating bean with name 'outerHandler':
Requested bean is currently in creation
```

成因在 `AipersimmonDddCqrsAutoConfiguration` 的工厂方法**体内**：

```java
return new RegistryCommandBus(
    handlers.stream().toList(), ...);   // ← 在 commandBus 自己还没造完时，把每个 handler 都实例化了
```

`ObjectProvider` 本来是延迟的，但 `.stream().toList()` 在方法体里立刻求值。
于是 handler 向 Spring 要一个**造了一半**的 bus。

## 这不是一种冷僻写法——**是框架自己的规则推荐的那一种**

`CqrsRules.commandHandlersShouldNotDependOnOtherCommandHandlers` 禁止 handler 依赖 handler，
理由写着"一个 handler 调另一个，要么绕过被调方的拦截器链，**要么经由 bus 回来**"——
也就是说，**bus 就是它给出的那条替代路径**。

而 `sendAs` 规则的反面样例 `BadStagedDispatchHandler` 本身就是这么写的：

```java
public BadStagedDispatchHandler(CommandBus commandBus) { ... }
```

**规则指着一扇门，装配把它钉死了。**

## 修法：延迟解析，但不交出 fail-fast

handler 与拦截器改为**首次派发时**读取（`Supplier<List<...>>`，双检加 volatile 记忆化）。

**关键在于不能顺手把 fail-fast 丢掉**：原来"构造即建索引"顺带做了两件检查——
同一命令类型注册了两个 handler、handler 的泛型签名解析不出命令类型。
若只改成惰性，这两条就从**启动失败**退化为**第一次派发该命令时才炸**。

所以 `RegistryCommandBus` 实现 `SmartInitializingSingleton`：
在**所有单例都造完之后**强制建一次索引。同一段代码、同一批检查，
只是从"造 bus 时"挪到"上下文建完时"——**比原来晚，但仍在任何请求之前**，
而那正是 fail-fast 买到的东西。此时上下文已完整，不存在循环。

## 先复现，再修

先写测试拿到 `BeanCurrentlyInCreationException`，再动代码。负向对照：
工厂方法改回传已解析的 list，异常立刻回来（4 个 error）。

## 那条负向对照差点没算数

第一次做对照时，我的替换字符串是**修改前**的排版，而 spotless 在我提交前把那段重排过了。
于是 `str.replace` **一个字都没改**，测试对着**它本该打破的那份代码**绿着通过——
我差点就把这当成"对照通过了"。

**加了断言确认 revert 真的落地之后，对照才立刻变红。**
与 `issue-00123` 那条空测试是同一个教训的两个面：
**对照只有在你确认它真的破坏了东西时才算数。**

## 关联

- 父：[[issue-00119-ten-majors-were-never-scheduled]]（排期第 5 档）
- 指向这扇门的那条规则：`CqrsRules.commandHandlersShouldNotDependOnOtherCommandHandlers`
- 同一轮里另一条"对照暴露空测试"：[[issue-00123-the-rate-limiter-deleted-the-window-someone-was-counting-in]]
- 启动期校验与装配分离的既有形状：`ProcessManagerStartupValidator`
