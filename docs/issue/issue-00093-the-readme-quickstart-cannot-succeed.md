---
id: issue-00093-the-readme-quickstart-cannot-succeed
type: issue
status: resolved
parent: report-00002-scaffold-ddd-review
---

# README 的 quickstart 三处都错，照着敲一定失败

## 问题（现状，file:line 为证）

- **等级：High（这是脚手架的第一印象，也是"能不能被采纳"的第一道门。三个错误互相独立，逐个修好前两个仍然跑不通）**。
- `README.md:33-37` 给的两条命令：

```bash
curl -i -X POST localhost:8080/orders -H 'content-type: application/json' \
  -d '{"customerId":"CUST-1","lines":[{"sku":"SKU-1","quantity":2,...}]}'
curl localhost:8080/orders/<id>
```

三处独立缺陷：

**A. 端口错，而且错到了一个会有响应的端口上。**
`application.yml:2-3` → `server.port: 8090`。
`8080` 被 compose 里的 kafka-ui 占用（`compose.yaml:47-48`），
而 `application.yml:1` 的第一行注释恰恰就是解释这件事的：
"Tomcat runs on 8090: kafka-ui in compose.yaml publishes host port 8080."
⇒ 用户不会得到"连接被拒绝"这种一眼可辨的错误，而是得到 kafka-ui 的 HTML 响应。

**B. 缺租户头。**
`application.yml:150-153` → `tenancy.enabled: true` + `missing-policy: REJECT`。
无 `X-Tenant-Id` 的请求在租户过滤器处就被 400，根本到不了控制器。
`application.yml:141-143` 的注释明确说这是**刻意的生产姿态**——设计没错，README 没跟上。

**C. 补上头也未必对，因为种子数据只在 `__root__` 下。**
`V1__aggregates.sql:57-58` 播种的 `CUST-1` 落在 `tenant_id` 列默认值 `__root__`；
`V2:28-32` 把 `customers` / `stocks` 的主键改成了 `(tenant_id, id)` / `(tenant_id, sku)`。
⇒ 只有 `-H 'X-Tenant-Id: __root__'` 能命中种子；换任何别的租户值都会得到
404 `ordering.customer-not-found`。

- 反证据：scaffold 自己的 8 个测试类全都在 `@BeforeEach` 里手工补种自己的租户数据
  （`ExceptionContractTest.java:52-66`、`OrderIdempotencyTest.java:74-88`、
  `SelfCancelTest.java:56-67`、`ConcurrentApprovalTest.java:99-112`、
  `TwoTenantAcceptanceTest.java:60-77`、`AggregateIdIsTimeOrderedTest.java:50-65`、
  `OrderListPagingTest.java:65-88`），有几个还在注释里写了
  "The Flyway seed lives under `__root__`; this tenant needs its own copy"——
  **这件事被反复写进测试注释，唯独没写进 README。**

## 根因（第一性）

1. **观察 vs 期望**：期望"README 的命令是被执行过的"；
   实际"它是被**写**过的，而验证由测试承担，测试走的是另一条路径"。
2. **最小机制**：三处配置（端口、租户策略、种子租户）各自变更于不同时期，
   而 quickstart 是一段**纯文本**，不参与编译、不参与测试、不参与任何 CI 检查。
   改端口的人、开租户的人、加复合主键的人，都没有理由回看它。
3. **真根因**：这个项目对"文档可能漂"这件事有很强的意识，并为此建了机制——
   README 的「能力→示例→**验证测试**」表（`:66-87`）把每条断言都绑到了一个测试上，
   而且那张表**逐行核对下来只有一行是错的**（见
   [[issue-00078-six-places-still-describe-the-repositories-as-in-memory]]）。
   机制是有效的。quickstart 恰恰是**唯一没有被纳入这个机制**的一段——
   它给的不是"某能力由某测试验证"，而是"你现在应该敲什么"，没有对应的测试形态。
4. **排除的伪根因**：不是配置写错了。8090、REJECT、`__root__` 种子**三者都是对的选择**，
   各自都有充分注释。错的只是 README 没有随它们更新。

## 复现（test-first）

让 quickstart 变成可执行的——这是本 issue 唯一值得留下的东西：

```java
/** README 的 quickstart 必须真的能跑。命令从 README 里解析出来，而不是在这里重抄一遍。 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestInfrastructure.class)
class ReadmeQuickstartTest {

  @Test
  void thePlaceOrderCommandInTheReadmeSucceeds() throws Exception {
    QuickstartCurl curl = QuickstartCurl.parseFrom(Path.of("../README.md"));  // 解析 ```bash 块

    ResponseEntity<String> placed = http.exchange(
        curl.path(), POST, new HttpEntity<>(curl.body(), curl.headers()), String.class);

    assertEquals(201, placed.getStatusCode().value(),
        "README 的 quickstart 跑不通：" + placed.getBody());
    assertNotNull(placed.getHeaders().getLocation());
  }
}
```

要点是**从 README 解析**而不是复制：复制出来的副本会和 README 一起漂，解析出来的不会。
端口由 `RANDOM_PORT` 提供（端口是唯一无法在测试里验证的一项，见下方修复第 4 条）。

## 修复

1. 端口 `8080` → `8090`（两条 curl 都要改）。
2. 加租户头：

```bash
curl -i -X POST localhost:8090/orders \
  -H 'content-type: application/json' \
  -H 'X-Tenant-Id: __root__' \
  -d '{"customerId":"CUST-1","lines":[{"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}'

curl -H 'X-Tenant-Id: __root__' localhost:8090/orders/<id>
```

3. 在两条命令下面补一句为什么，两行足够：
   *多租户默认开启且 `missing-policy=REJECT`，所以每个请求都要带 `X-Tenant-Id`；
   Flyway 的演示数据播在 `__root__` 下，所以这里用它。*
   这句话把 B 与 C 一次讲清，读者遇到自己的租户返回 404 时不会卡住。
4. 加上面那条 `ReadmeQuickstartTest`。它覆盖不了端口（测试用随机端口），
   所以再补一条便宜的断言：README 中出现的 `localhost:<port>` 必须等于
   `application.yml` 的 `server.port`——一条正则即可。

顺带（可选）：把 kafka-ui 移进一个 compose profile
（见 [[issue-00088-dependency-and-image-versions-escape-the-boms]] 的修复第 3 条），
8080 就不再有东西监听，将来同类错误会立刻表现为"连接被拒绝"而不是一个 HTML 页面。

## 验证结果

已修（第 4 条补齐后 resolved）。

- **已做（修复第 1、2、3 条）**：两条 curl 改为 8090、补 `-H 'X-Tenant-Id: __root__'`，
  并在上方用两句话说明为什么需要它（`missing-policy=REJECT`）以及为什么是 `__root__`
  （Flyway 演示数据播在该哨兵下，自己的租户需要自己的行）。
  Build and run 一节同时更正了另一处不实描述——SigNoz 并非随 `spring-boot:run` 启动，
  它在 `observability` profile 后面，需要 out-of-band 拉起。
- **顺带消除了一条歧路**：kafka-ui 已移进 compose 的 `tools` profile
  （[[issue-00088-dependency-and-image-versions-escape-the-boms]]），
  8080 默认不再被监听，将来打错端口会直接连接被拒，而不是拿到一个 HTML 页面。
- **已做（修复第 4 条，本 issue 因此 resolved）**：`ReadmeQuickstartTest` 落地，两条断言：
  - `theQuickstartPlacesAnOrderAndReadsItBack` —— **从 README 解析**（不复制）第一条 curl 的
    path / headers / body，真的发出去，要求 201 + `Location`，再用返回的 location 跑第二条 curl 要求 200。
    按修复第 4 条的要点：复制出来的副本会和 README 一起漂且照样通过，解析出来的不会。
  - `theReadmePortIsThePortTheApplicationBinds` —— README 里出现的每个 `localhost:<port>/`
    必须等于 `application.yml` 的 `server.port`（用 `YamlPropertySourceLoader` 读，
    不用正则去啃 YAML）。这补上了随机端口测不到的那一项。
- **C 的答案在此期间变了**：修复第 2、3 条当时写的是 `-H 'X-Tenant-Id: __root__'`，
  而那个 header 值是**必然被拒**的——见
  [[issue-00096-the-quickstart-curl-names-a-tenant-the-edge-rejects]]。
  quickstart 现在用 `demo`（种子同时播在 `__root__` 与 `demo` 两个租户下）。
  **这条测试如果早存在，issue-00096 当天就会被抓到**，这也正是本 issue 第 4 条的价值所在。
- 负向对照：把 README 改回 `__root__` + 8080，两条断言同时红，
  分别报出 `400 Bad Request` 与 `sends the reader to [localhost:8080/, localhost:8080/]`——
  正是本 issue A、C 两项原始缺陷。
- 验证：`mvn -o verify -pl start -am` 全绿，67 个测试 0 失败。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00072-demo-seed-data-ships-in-a-production-migration]]（种子数据的位置，与 C 同源）
- [[issue-00088-dependency-and-image-versions-escape-the-boms]]（kafka-ui 的 8080 占用）
- [[issue-00078-six-places-still-describe-the-repositories-as-in-memory]]（另一类文档漂移）
- [[decision-00018-multi-tenancy-boundaries]]（`__root__` 哨兵与 missing-policy）
