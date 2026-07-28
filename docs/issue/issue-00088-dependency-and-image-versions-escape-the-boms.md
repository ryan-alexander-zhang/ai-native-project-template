---
id: issue-00088-dependency-and-image-versions-escape-the-boms
type: issue
role: main
status: resolved
parent: report-00002-scaffold-ddd-review
---

# 两处版本逃出了 BOM 与镜像固定：mybatis-plus 硬编码两遍，kafka-ui 用 latest

## 问题（现状，file:line 为证）

- **等级：Low（今天一致；但项目把"版本不会漂"当作明确的设计目标，这两处是它的缺口）**。

**A. `mybatis-plus-core` 的版本被硬编码了两次**

  - 根 pom **已经**导入了 `mybatis-plus-bom`（`pom.xml:91-97`），
    注释还写明了理由："MyBatis-Plus is not managed by spring-boot-dependencies; import its own BOM."
  - 但两个 infrastructure 模块又各自写死了一遍：

```xml
<!-- ordering-infrastructure/pom.xml:60-64 与 inventory-infrastructure/pom.xml:49-53 -->
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-core</artifactId>
  <version>3.5.15</version>          <!-- 与根 pom 的 ${mybatis-plus.version} 重复 -->
</dependency>
```

  - 两处注释都写着 "Version pinned — the scaffold does not manage MyBatis-Plus (plan-00007)"，
    但**根 pom 现在确实管理它了**（`pom.xml:46` 定义 `mybatis-plus.version`，`:91-97` 导入 BOM）——
    注释停留在 BOM 引入之前。
  - 后果：升级 `mybatis-plus.version` 时，`start` 拿到新版、两个 infrastructure 模块仍编译在旧版上，
    且**不会有任何警告**。

**B. compose 里一个镜像用 `latest`**

  - `start/compose.yaml:47` —— `image: provectuslabs/kafka-ui:latest`
  - 同文件其它镜像全部固定：`postgres:18.1`(`:13`)、`bitnamilegacy/kafka:3.7.1-debian-12-r9`(`:29`)、
    `clickhouse/clickhouse-server:25.5.6`(`:57,92`)、`signoz/signoz:v0.117.1`(`:147`)、
    `signoz/signoz-otel-collector:v0.144.2`(`:127,175`)。
  - 与项目自述的原则相悖：`TestInfrastructure.java:19-23` 明确说
    "which owns the image pins — so this sample cannot drift from the versions the library tests against,
    and there is no version number to maintain here at all"。
    这个原则在测试侧被贯彻了，在本地 compose 侧漏了一个。
  - kafka-ui 还占着 8080——正是 README quickstart 误用的那个端口（见评审 B1）。

## 根因（第一性）

1. **观察 vs 期望**：期望"每个版本号只有一处权威声明"；实际"有一处权威声明 + 两处副本 + 一处放弃声明"。
2. **最小机制**：Maven 里子模块的 `<version>` **覆盖** `dependencyManagement`，且不告警；
   Docker 的 `latest` 每次 `pull` 都可能不同，也不告警。两者都是"静默生效"。
3. **真根因**：这三处都是**在 BOM/固定策略确立之前**写下的，
   而确立策略时没有回头清理既有副本。注释是证据——
   它们说的"scaffold 不管理 MyBatis-Plus"在当时为真，现在为假。
   策略的引入没有伴随一条能发现违例的检查，于是旧写法留在原地且看起来仍然合理。
4. **排除的伪根因**：不是 `mybatis-plus-core` 不该被直接依赖——
   infrastructure 模块用到 `BaseMapper` / `@TableName`，声明依赖是对的；
   错的只是同时声明了版本。

## 复现（test-first）

```java
@Test
void noModuleOverridesAVersionTheBomAlreadyManages() throws Exception {
  for (Path pom : modulePoms()) {
    String xml = Files.readString(pom);
    assertFalse(xml.contains("<groupId>com.baomidou</groupId>") && xml.contains("<version>"),
        pom + " —— MyBatis-Plus 版本由根 pom 的 mybatis-plus-bom 管理，子模块不应再写 <version>");
  }
}

@Test
void noComposeImageFloatsOnLatest() throws IOException {
  String compose = Files.readString(Path.of("compose.yaml"));
  assertFalse(compose.contains(":latest"), "compose 镜像必须固定版本，否则本地环境会漂");
}
```

两条都会立刻变红。

## 修复

1. 删掉两个 infrastructure pom 里的 `<version>3.5.15</version>`——BOM 会接管。
   同时改掉那两条已经过期的注释（"the scaffold does not manage MyBatis-Plus"）。
2. `provectuslabs/kafka-ui:latest` → 固定一个版本。
3. 顺带考虑：kafka-ui 是纯开发便利组件，把它放进一个 compose profile
   （如 `--profile tools`，与 SigNoz 的 `observability` profile 同构），
   这样默认 `up` 不会占用 8080，README 的端口困惑也少一个来源。

## 验证结果

已修。

- 两个 infrastructure pom 的 `<version>3.5.15</version>` 删除，交回根 pom 的 `mybatis-plus-bom`；
  两条已过期的注释（"the scaffold does not manage MyBatis-Plus"）一并改写为说明**为什么这里不写版本**。
- `provectuslabs/kafka-ui:latest` → `v0.7.2`，并按修复第 3 条移进 compose 的 `tools` profile。
  副作用是 8080 默认不再被监听——这正好消除了
  [[issue-00093-the-readme-quickstart-cannot-succeed]] 里"打错端口却拿到 HTML 响应"的那条歧路。
- 验证：`mvn -o compile` 通过（BOM 解析正常，两个模块编译到同一 MyBatis-Plus 版本线）。
- 未做：复现一节那两条防再犯的结构断言。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00067-test-support-covers-every-store-except-the-transport]]（镜像固定权收归 test-support 的那一轮）
