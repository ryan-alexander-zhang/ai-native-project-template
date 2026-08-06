---
id: issue-00087-a-raw-control-character-is-the-codec-separator
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
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

已修（两半都修完）。

- **已做（修复第 1 条，零风险）**：`OrderFulfilmentCodecs` 的分隔符由裸 0x1F 字节改为
  `"\u001F"` 转义写法，行为完全不变（同一码点），但它在编辑器、diff 与代码评审里终于可见。
  字段 javadoc 说明了它是一份**持久化格式**的定义，以及为什么必须写成转义。
- **已做（修复第 2、3 条，本轮补上）**：`decodeCancel` 改为**按变体定界的 split**——
  先 `split(US, 3)` 取出 `orderId` / 判别符 / 其余，再按变体
  `split(US, 4)`（INVENTORY_UNAVAILABLE）或 `split(US, 5)`（PAYMENT_DECLINED）切开，
  **最后一个字段吸收剩余内容**。`detail` 恰好是它那个变体的最后一个字段，所以它后面没有可被挤位的东西。

  **一处必须纠正原稿的判断**：原稿在末尾写"改格式（第 2 条）按该 codec 自己的规则是 wire change，
  需要 bump `PayloadType` 版本并为 v1 保留解码器"。**这条对"转义"成立，对"定界 split"不成立。**
  定界 split **编码器一个字节都没动**：
  - 已落库的行，凡是此前能正确解出的，解出的结果完全相同；
  - 唯一改变含义的，是此前**解错**的那些行。

  所以**不需要 bump 版本，也不需要 v1 解码器**。两个方案修的是同一个缺陷，代价差一个数量级，
  原稿把它们混成了一条。选了便宜的那条，代价是引入一条约束：
  **自由文本字段必须是它那个变体的最后一个字段**——这条约束已写进 `US` 常量与 `decodeCancel` 的 javadoc。
  将来若新增第二个自由文本字段、或在 `detail` 之后再加字段，转义（连同版本 bump）就成了唯一选项。

  顺带把"字段数不足"从 `ArrayIndexOutOfBoundsException` 改成
  `ProcessSerializationException("malformed cancel-order payload")`——
  解码期的故障应当自报家门。

- **测试放在新建的 `OrderFulfilmentCodecsTest`，不是原稿说的 `OrderFulfilmentDefinitionTest`**：
  后者的 javadoc 明说自己测的是纯转移表，而 codec 是一份**持久化格式**，
  变更理由完全不同，混在一起会让两者都变模糊。5 条：两个变体各一条 round-trip、
  **detail 含分隔符**的 round-trip、字段不足、判别符未知。
  测试里的分隔符同样写成 `\u001F` 转义（理由与主文件一致：源码里看不见的输入不算规格说明）。

- **负向对照（实测，且结果比 issue 预测的更糟）**：把定界 split 改回 `split(US, -1)`：

```
aDetailContainingTheSeparatorRoundTripsInsteadOfShiftingTheFields
  expected: <... detail=asked 999<US>available 10]]>
  but was:  <... detail=asked 999]]>
```

  原稿预测的失败形态是"`ArrayIndexOutOfBoundsException`，或把错误的值装进证据 ref"。
  **实际是第三种：静默截断。** 多切出来的那一段落在 `fields[4]`，
  而 INVENTORY_UNAVAILABLE 只读到 `fields[3]`，于是它被无声丢弃——
  既不抛异常，也不装错值，只是证据里的失败原因少了半句。
  这比原稿设想的两种都更难发现。

- 验证：`mvn -o test -pl ordering/ordering-process-mybatis-plus -am` 29 条全绿
  （`OrderFulfilmentDefinitionTest` 24 + `OrderFulfilmentCodecsTest` 5）；`spotless` 通过。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[design-00004-durable-process-manager-runtime]]（payload codec 与版本契约）
- [[issue-00009-version-evolution-semantics]]
