---
id: issue-00095-a-partial-reactor-build-silently-tests-stale-siblings
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# `mvn -pl start` 会静默地拿 ~/.m2 里的陈旧兄弟构件跑测试，症状指向完全错误的方向

## 问题（现状，可复现）

- **等级：Medium（不是代码缺陷，是这个 17 模块 reactor 的开发者陷阱。它的症状极具误导性——
  会让人相信一个存在的 REST 端点不存在，并据此去改根本没坏的代码）**。
- 触发条件：在 `multi-module` 下执行 `mvn test -pl start`（**不带 `-am`**），
  而本地仓库里的兄弟构件比工作区旧。
- 实测（本次评审中真实发生）：

```
$ mvn -o test -pl start -Dtest=ExceptionContractTest
  ExceptionContractTest.aMissingQueryParameterRenders400NotFallback500  expected:<400> but was:<405>
  ReviewFlowTest.aRestrictedOrder...  expected:<CONFIRMED> but was:<FULFILMENT_IN_PROGRESS>（30s 超时）

$ mvn -o test -pl start -am -Dtest=ExceptionContractTest,ReviewFlowTest
  Tests run: 11, Failures: 0, Errors: 0   BUILD SUCCESS
```

- 根据在这里：

```
$ ls -la ~/.m2/repository/com/example/ordering-adapter/0.0.1-SNAPSHOT/*.jar
  -rw-r--r--  11407  Jul 26 12:18  ordering-adapter-0.0.1-SNAPSHOT.jar     ← 两天前

$ javap -p (该 jar 里的 OrderController)
  place(PlaceOrderRequest)
  approveReview(String)
  get(String)
  ← 只有三个端点；list(...) 与 cancel(...) 不在里面
```

  用一个探针把运行时的映射表打出来，证据完全吻合：

```
@@@ {POST [/orders]}                        -> OrderController#place
@@@ {POST [/orders/{id}/approve-review]}    -> OrderController#approveReview
@@@ {GET  [/orders/{id}]}                   -> OrderController#get
    ← 没有 GET /orders，也没有 POST /orders/{id}/cancel
GET /orders?customerId=CUST-1 -> 405 HttpRequestMethodNotSupportedException
```

- 于是 `GET /orders`（无论带不带参数）落到"路径存在、方法不支持"，返回 **405**——
  而测试期望的是"参数缺失"的 **400**。两个状态码差得很远，指向的诊断方向也完全相反：
  一个让人怀疑异常处理链，另一个的真相是**端点压根没注册**。
- `ReviewFlowTest` 同理：`ordering-application` / `ordering-process-mybatis-plus`
  也是陈旧版本，审核通过后的级联行为停留在两天前的实现上。

## 根因（第一性）

1. **观察 vs 期望**：期望"在工作区跑测试，测的是工作区的代码"；
   实际"测的是工作区的 `start` + 本地仓库快照里的其余 16 个模块"。
2. **最小机制**：`-pl` 把 reactor 缩小到列出的模块。被排除的模块**不参与构建**，
   于是它们的坐标按普通依赖解析——从 `~/.m2` 取已安装的 `0.0.1-SNAPSHOT`。
   Maven 认为这是完全正常的，**不打印任何警告**：从它的角度看，依赖解析成功了。
3. **真根因**：SNAPSHOT 版本号在整个 reactor 里是**同一个字符串**，
   所以"工作区里的 ordering-adapter"与"两天前装进本地仓库的 ordering-adapter"
   在坐标上无法区分。版本号本应携带"这是哪一份代码"的信息，SNAPSHOT 恰恰放弃了这个信息。
   `-pl` 只是让这个既有的歧义暴露出来。
4. **为什么这个项目特别容易踩**：
   - 17 个模块，且 `start` 的测试要起十来对容器
     （[[issue-00092-each-test-context-starts-its-own-container-pair]]），
     所以**每个人都会想只跑一部分**——`-pl` 是最自然的做法；
   - `start` 依赖全部 16 个兄弟模块，命中面最大；
   - 症状落在 HTTP 状态码上，看起来像 web 层的问题，而真因在构建层。
5. **排除的伪根因**：不是 Maven 的 bug，也不是测试写错了。
   `mvn verify`（从根，全 reactor）从来都是对的；错的是"部分构建"这条捷径没有护栏。

## 复现（test-first）

这类问题没有可写的单元测试——它发生在构建层，不在代码层。可验证的复现步骤：

```bash
# 1. 让本地仓库落后于工作区
git stash                                  # 或改一处 ordering-adapter 的代码但不 install
mvn -o install -DskipTests                 # 把旧代码装进 ~/.m2
git stash pop

# 2. 部分构建 —— 测的是 ~/.m2 里的旧代码
mvn -o test -pl start -Dtest=ExceptionContractTest      # 红，且原因误导

# 3. 完整 reactor —— 测的是工作区
mvn -o test -pl start -am -Dtest=ExceptionContractTest  # 绿
```

## 修复

没有代码要改；要改的是**让这条捷径带上护栏**：

1. **README 写清楚**（最重要）。Build and run 一节给出的每条部分构建命令，
   都要说明它是否需要 `-am`：
   - 三个 `*-domain` 模块只依赖库、不依赖兄弟模块，`-pl` 单独跑是**安全**的；
   - 任何包含 `start` 或某个 `*-adapter` / `*-application` 的部分构建，
     **必须带 `-am`**，否则测的是本地仓库里的快照。
2. **给一条推荐命令**，让人不必记规则：
   `mvn -o test -pl start -am` —— 带 `-am` 永远正确，代价只是多编译几个小模块（秒级）。
3. **可选的强护栏**：在 `start` 的测试里加一条断言，比较某个兄弟模块 class 的
   `getProtectionDomain().getCodeSource().getLocation()`——
   若它指向 `~/.m2/repository` 而不是 `target/classes`，就立刻失败并说明原因。
   这会把一次误导性的 405 变成一句"你在测陈旧构件"。
4. **更根本但更重**：CI 上只允许全 reactor 构建；本地用
   `mvn -o install -DskipTests` 作为切分支后的固定动作。这是约定，不是机制。

第 1、2 条应当立刻做；第 3 条是唯一能真正防住的，值得做。

## 验证结果

已修（三条修复全部落地）。

- **已做（修复第 1、2 条）**：README 的 Build and run 一节现在逐条标注了每个部分构建是否需要 `-am`——
  三个 `*-domain` 模块只依赖库，`-pl` 单独跑安全；凡涉及 `start` 的一律带 `-am`，
  并写明了不带会发生什么以及症状为什么具有误导性。`spring-boot:run` 那条也补上了 `-am`。
- **已做（修复第 3 条，本轮补上，issue 关闭）**：`SiblingModuleFreshnessTest`（`start` 测试）。

  **实现与原提议不同，且这个差异是关键**。原稿说"比较 `CodeSource` 是否指向 `target/classes`"。
  照做会在**全 reactor `mvn verify`** 上假红：`package` 之后，依赖模块提供给下游的是
  `target/*.jar` 而不是 `target/classes`，一条"必须是 `target/classes`"的断言会把正确的构建判红。

  实际的判据落在**"从哪里加载"**而不是"用什么命令构建"：
  - 类读自**目录** → 一定是刚编译出来的产物，无论 Maven 还是 IDE 的输出目录，放行；
  - 类读自 **jar** → 只有当这个 jar 位于某个模块的 `target/` 下才可信（全 reactor `package` 的产物）；
  - 其余的 jar 就是仓库构件 → 红。

  这样三种情形都对：`-pl start -am`（目录）绿、根上 `mvn verify`（reactor 的 target jar）绿、
  `-pl start` 不带 `-am`（`~/.m2` 的 jar）红。IDE 里跑也不会假红。

  类的来源用 ArchUnit 的 `JavaClass.getSource().getUri()` 取，不用反射——
  `ArchitectureTest` 已经在用 ArchUnit 扫 `com.example`，而且它按包扫描，
  **新增模块自动纳入**，不需要维护一份"每个模块挑一个代表类"的清单。

  报错信息按 jar 聚合（一个陈旧模块报一行，不是它每个类报一行），并附一句
  "加 `-am` 重跑；在此之前看到的失败都不可信"。

- **负向对照（实测）**：`mvn -o install -DskipTests` 装好兄弟模块后，
  `mvn -o test -pl start -Dtest=SiblingModuleFreshnessTest`（**不带 `-am`**）红，列出 10 个模块：

```
these classes came from the local Maven repository, not from this working copy, so
the tests below would have run against whatever was installed there last:
  jar:file:/Users/.../.m2/repository/com/example/ordering-adapter/0.0.1-SNAPSHOT/ordering-adapter-0.0.1-SNAPSHOT.jar
    e.g. com.example.ordering.adapter.package-info
  ... (10 个模块)
Re-run with -am (mvn -o test -pl start -am), which builds the sibling modules from source first.
Any failures you saw before adding -am are suspect.
```

  带 `-am` 同一条测试绿。这正是当初那次误诊本应看到的东西。

本 issue 的来历：评审执行者用 `mvn -pl start`（未带 `-am`）跑测试，得到两条失败
（`ExceptionContractTest` 期望 400 得到 405、`ReviewFlowTest` 30 秒超时），
一度据此判断"multi-module 在 HEAD 上是红的"。带 `-am` 重跑后
`start` 全量 53 条 + 各模块单测**全绿，BUILD SUCCESS**——HEAD 是绿的，
那两条失败完全由 `~/.m2` 里两天前的陈旧构件造成。

这次误诊本身就是本 issue 最好的论据：一个刚刚逐行读完全部源文件的人仍然被引向了错误结论，
并且几乎要去修一段没有坏的代码。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00092-each-test-context-starts-its-own-container-pair]]（测试慢正是"只跑一部分"的动机来源，两者叠加才构成这个陷阱）
- [[issue-00088-dependency-and-image-versions-escape-the-boms]]（另一类"版本坐标不携带足够信息"的问题）
