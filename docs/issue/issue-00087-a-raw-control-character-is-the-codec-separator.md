---
id: issue-00087-a-raw-control-character-is-the-codec-separator
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# 手写 codec 的分隔符是源码里一个裸的不可打印字节，且字段无转义

## 问题（现状，file:line 为证）

- **等级：Low（当前行为正确；但它是持久化格式的定义，一旦被破坏，已落库的流程状态就解不出来）**。
- `OrderFulfilmentCodecs.java:77` 声明分隔符：

```java
private static final String US = "<U+001F>";     // 引号之间是一个裸字节，不是这五个字符
```

  引号之间是**一个裸的 0x1F 字节**（unit separator），本文档无法原样转录它。
  已用 `od -c` 确认（`037` 即八进制的 0x1F）：

```
7   7   :       p r i v a t e   s t a t i c   f i n a l   S t r i n g   U S   =   " 037 " ;
```

  它在编辑器、`git diff`、代码评审页面里都不可见。
- 它决定了 `CancelOrder` 的持久化编码（`encodeCancel:148-173` / `decodeCancel:175-191`），
  而该 codec 的 javadoc 自己强调（`:69-72`）：

  > "**Changing an encoding is a wire change.** … Re-encoding an existing (type, version)
  > differently would leave already-persisted rows undecodable."

  也就是说，**这个不可见字节是一份 wire format 的一部分**。
  任何一次编码转换（UTF-8 → 其它）、复制粘贴、把源码过一遍不保守的格式化/清洗工具、
  或者有人"看不清就重打一遍引号内容"，都会静默改掉它。
- 第二个问题：字段值**不做转义**。`String.join(US, ...)` 直接拼
  `failureId` / `reasonCode` / `detail` 等（`:151-158`），
  而 `detail` 来自 inventory 的失败消息（`ReserveStockHandler.java:95` 传的
  `failure.getMessage()`），是一段自由文本。若它恰好含有 0x1F，
  `decodeCancel` 的 `text.split(US, -1)`（`:176`）会多切出字段，
  按位置读取（`fields[1]`…`fields[6]`）随即错位——
  轻则 `ArrayIndexOutOfBoundsException`，重则把错误的值装进证据 ref。
  概率极低，但这是一条**解码期**才会暴露的故障，那时原始事件早已不在手边。

## 根因（第一性）

1. **观察 vs 期望**：期望"持久化格式的定义在源码里是显式、可见、可评审的"；
   实际"它是一个视觉上等同于空字符串的字节"。
2. **最小机制**：Java 允许字符串字面量里直接包含任意非结构字符。
   转义写法 `"\u001F"` 与裸字节在语义上完全等价，编译器不区分；
   区别只在**人和工具能不能看见它**。
3. **真根因**：这个 codec 是为了避免在领域类型上加 Jackson 注解而手写的
   （理由充分，`:112-116` 论证得很好）。
   手写的代价被认知为"要自己维护编解码逻辑"（javadoc `:66-67` 说了这一点），
   但没有被认知为"要自己承担一份格式定义的**可维护性**"——
   包括它在源码里是否可读、字段是否需要转义、边界字符如何处理。
   Jackson 之所以不需要考虑这些，正是因为它替使用者做了。
4. **排除的伪根因**：不是"不该手写 codec"——该 javadoc 列出的四条手写理由
   （多态且类型必须无注解、加密、upcasting、外部强加的非 JSON 格式）都成立，
   本例属于第一条。问题只在于手写之后的细节。

## 复现（test-first）

```java
@Test
void theSeparatorIsWrittenAsAnEscapeNotARawByte() throws IOException {
  String src = Files.readString(Path.of(".../OrderFulfilmentCodecs.java"));
  assertFalse(src.contains(""),
      "持久化格式的分隔符必须写成 \\u001F 转义，否则它在源码里不可见、可被静默破坏");
}

@Test
void aDetailContainingTheSeparatorRoundTrips() {
  CancelOrder command = new CancelOrder("o-1", new CancellationReason.InventoryUnavailable(
      new ReservationFailureRef("f-1", new OrderId("o-1"), "code", "detailwith separator")));
  assertEquals(command, codec.decode(codec.encode(command)));   // 当前：字段错位
}
```

## 修复

1. **分隔符写成转义**：`private static final String US = "\u001F";`
   ——行为完全不变（同一个码点），只是在源码里变得可见、可 diff、可评审。
   这一条零风险，应当立刻做。
2. **字段转义**：编码时对每个字段做最小转义（例如把出现在字段值里的分隔符字节替换为一个转义序列，
   或改用长度前缀），解码时反向。
   或者更省事：既然 `CancelOrder` 的两个变体字段数固定（6 / 7），
   把 `split(US, -1)` 改为 `split(US, limit)` 并让**最后一个字段**（自由文本 `detail`）
   吸收剩余内容——一行改动即可消除绝大部分风险。
3. `OrderFulfilmentDefinitionTest` 补一条含分隔符的 round-trip 用例，
   把这条格式约束钉住。

注意：改**格式**（第 2 条）按该 codec 自己的规则是 wire change，
需要 bump `PayloadType` 版本（`:127` 的 `new PayloadType("ordering.fulfilment.cancel-order", 1)`）
并为 v1 保留解码器，直到没有旧行为止。
改**写法**（第 1 条）不是 wire change，可以单独先做。

## 验证结果

部分已修（保持 open）。

- **已做（修复第 1 条，零风险）**：`OrderFulfilmentCodecs` 的分隔符由裸 0x1F 字节改为
  `"\u001F"` 转义写法，行为完全不变（同一码点），但它在编辑器、diff 与代码评审里终于可见。
  字段 javadoc 说明了它是一份**持久化格式**的定义，以及为什么必须写成转义。
- **未做（修复第 2、3 条）**：自由文本字段（`ReservationFailureRef.detail`）仍未转义，
  含 0x1F 的 detail 仍会让 `decodeCancel` 按位置读取时错位。
  该改动会改变 wire format，按本 codec 自己的规则需要 bump `PayloadType` 版本并保留 v1 解码器，
  因此与"零风险速修"分开处理。javadoc 已就地标注这半仍然开着。
- 验证：`mvn -o compile`、`spotless:check` 通过；`OrderFulfilmentDefinitionTest` 18 条全绿。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[design-00004-durable-process-manager-runtime]]（payload codec 与版本契约）
- [[issue-00009-version-evolution-semantics]]
