---
id: issue-00074-one-config-file-with-development-values-only
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 单一 application.yml 全是开发值：没有 profile、没有外部化数据源、没有探针分组

## 问题（现状，file:line 为证）

- **等级：Medium（应用能跑但不能部署；一个复制本 scaffold 的项目要自己重做全部环境化工作，而这本该是脚手架最该给的东西之一）**。
- `start/src/main/resources/` 下**只有一个** `application.yml`，无 `application-dev.yml` /
  `application-prod.yml`，全文无 `spring.config.activate.on-profile`。里面的值全部是本地开发值：

  | 位置 | 值 | 为什么不能进生产 |
  |---|---|---|
  | `:15-17` | `docker.compose.lifecycle-management: start-only` | 生产没有 docker-compose |
  | `:18-19` | `kafka.bootstrap-servers: localhost:9092` | 硬编码 localhost |
  | `:46-49` | `springdoc.swagger-ui.enabled: ${SWAGGER_UI_ENABLED:true}` | 默认开放交互式 UI（有 env 兜底，是全文唯一做了外部化的一处） |
  | `:42-44` | `payment-timeout: PT2M` | 注释自称 "a development number" |
  | `:187-190` | `otel.exporter.otlp.endpoint: http://localhost:44317` | 硬编码 localhost |
  | `:201-205` | `logging.structured.ecs.service.environment: local` | 字面写死 `local` |

- **完全没有 `spring.datasource.*`**（`grep datasource application.yml` 无命中）。
  数据源目前只能由 `spring-boot-docker-compose` 从运行中的 compose 服务推导出来
  （`start/pom.xml:139-144`），或由测试里的 `@ServiceConnection` 提供。
  也就是说，**这个应用没有任何一条可以脱离 docker-compose 启动的路径**。
- Actuator 在依赖里（`start/pom.xml:156-159`，注释还专门解释了为什么需要它），
  但配置里没有 `management.endpoint.health.probes.enabled`，
  也没有 `management.endpoints.web.exposure.include`。
  ⇒ K8s 的 `/actuator/health/liveness` 与 `/readiness` 分组探针**不可用**，
  只有一个合并的 `/actuator/health`。`ExceptionContractTest.healthEndpointIsReachableAndUp`（`:175-183`）
  验证的正是这个合并端点，所以缺口测不出来。
- 无 `server.shutdown: graceful`，无连接池配置（`spring.datasource.hikari.*`），
  无 `spring.jackson` 时区/序列化约定。

## 根因（第一性）

1. **观察 vs 期望**：期望"脚手架给出**至少两套**环境形态，让人知道哪些值是环境相关的"；
   实际"给出一套本地形态，环境相关性只存在于注释里"。
2. **最小机制**：Spring Boot 的 profile 机制需要**显式**拆分文件或 `on-profile` 段；
   不拆就意味着所有值对所有环境生效。配置里没有任何一处做了这个拆分。
3. **真根因**：`application.yml` 承担了两个冲突的职责——
   它既是**运行配置**，又是这个项目最重要的一篇**教学文档**
   （全文 205 行里注释占了一大半，逐项解释 outbox 租约算术、inbox 保留期、
   tenancy missing-policy 的取舍，质量很高）。
   拆 profile 会把这些注释切碎，所以一直没拆。
   但代价是：**读者无法从文件里区分"这是必须理解的决策"与"这是我的本地地址"**。
4. **排除的伪根因**：不是作者不懂环境化——`SWAGGER_UI_ENABLED` 那一处
   （`:36-37` 的注释明确说"a production profile can expose the contract without the UI"）
   证明这件事被想过；只是想过一处，没有推广成结构。

## 复现（test-first）

```java
@Test
void theApplicationCanStartWithoutDockerCompose() {
  // prod profile 下不得依赖 compose 推导数据源：只给 SPRING_DATASOURCE_URL 等环境变量应能启动
  new SpringApplicationBuilder(OrderingApplication.class)
      .profiles("prod")
      .properties("spring.datasource.url=" + postgres.getJdbcUrl(), ...)
      .run().close();                        // 当前：无 prod profile，且 compose 支持仍生效
}

@Test
void livenessAndReadinessProbesAreMapped() throws Exception {
  mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());   // 当前 404
}
```

## 修复

1. **拆三层**，保留注释的教学价值：
   - `application.yml` —— 与环境无关的**决策**（outbox 租约算术、inbox 保留期、
     tenancy 策略、flyway components、routing）。今天那些高质量注释留在这里。
   - `application-dev.yml` —— compose lifecycle、localhost 端点、swagger on、
     `payment-timeout: PT2M`、`environment: local`、`db/dev` 种子 location。
   - `application-prod.yml` —— 全部端点走环境变量、swagger off、探针打开、优雅停机。
2. **补 datasource 骨架**（哪怕只在 prod profile 里）：

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:20}
```

3. **补探针与停机**：

```yaml
management:
  endpoint.health.probes.enabled: true
  endpoints.web.exposure.include: health,info,prometheus
server:
  shutdown: graceful
```

4. `README.md` 的 Build and run 一节加一句：默认是 `dev` profile；
   生产形态见 `application-prod.yml` 与它要求的环境变量清单。

与 [[issue-00072-demo-seed-data-ships-in-a-production-migration]] 一起做：
种子数据的 `db/dev` location 正好落在 dev profile 里。

## 验证结果

已修。按修复方案拆三层，与
[[issue-00072-demo-seed-data-ships-in-a-production-migration]] 同批。

- `application.yml` 只留**决策**（outbox 租约算术、inbox 保留期、tenancy 策略、flyway components、
  序列化器、routing），高质量注释原样保留；`application-dev.yml` 收 compose lifecycle、
  `localhost:9092`、swagger on、`PT2M`、`environment: local`、`db/dev` 种子 location；
  `application-prod.yml` 全部走环境变量。判据写在 base 文件抬头：
  **换个环境这个值还对吗**——对则留 base，不对则进 profile。
- `spring.profiles.default: dev`，所以 `spring-boot:run` 与全部测试行为不变，生产是显式 opt-in。
- datasource 骨架按方案落在 prod：`${DB_URL}` / `${DB_USER}` / `${DB_PASSWORD}` /
  `${KAFKA_BOOTSTRAP_SERVERS}` **一律不给默认值**——缺一个就启动失败，这正是想要的；
  可选项（`DB_POOL_MAX`、`PAYMENT_TIMEOUT`、`OTEL_EXPORTER_OTLP_ENDPOINT`、`DEPLOY_ENVIRONMENT`）
  给默认值并在文件抬头列清单。
- **探针与优雅停机放进了 base，不是方案说的 prod**。理由：
  "这个应用要不要暴露 K8s 探针"是决策不是环境值，且放 prod 会让探针只能在 prod profile 下被测到——
  scaffold 里一条测不到的配置等于没有。`ExceptionContractTest.livenessAndReadinessAreSeparateProbes`
  因此能在默认 profile 下跑。
- `management.endpoints.web.exposure.include` 定为 `health,info`，**没有** `prometheus`：
  本应用的 Micrometer 指标是经 OTel bridge 走 OTLP 推出去的，根本没有 scrape 端点，
  列一个不存在的端点是 cargo cult。
- prod 还翻转了 `idempotency.require-key: true` 与 `swagger-ui.enabled: false`，两处都在注释里给了理由。

**两处实施中才暴露的问题**，都是这个 test-first 测试抓的：

1. `spring.datasource.hikari.connection-timeout: 10s` **启动失败**——
   这些 key 直接绑到 HikariConfig 自己的 `long` 字段（毫秒），
   本文件其他地方通用的 Duration 写法（`PT30S` / `24h` / `1s`）在这里是
   `NumberFormatException: For input string: "10s"`。已改为 `10000` 并在注释里点明这个不一致。
2. `management.endpoint.health.group.readiness.include: readinessState,db` 与
   `probes.enabled` **耦合**：关掉 probes 会让 `readinessState` contributor 不存在，
   启动即 `NoSuchHealthContributorException`。这个失败方向是对的（好过 readiness 静默不含 readiness），
   但会绊到想试着关掉探针的读者，已在注释里写明。

`ProductionProfileBootTest` 落地了方案的第 1 条复现测试，且刻意**不用** `@ServiceConnection`：
容器取自 `SharedContainers`（非 Spring 托管），坐标以 `DB_URL` 等**prod profile 真正读的名字**注入。
用 `@ServiceConnection` 会更短但什么也证明不了——它自己就提供 DataSource，
`${DB_URL}` 占位符根本不会被解析，prod 文件写成空的测试也会绿。
不起 Kafka 容器（关掉 relay 与 consumer bridge），避免为一个配置解析测试再付一对容器（issue-00092）。

第 4 条 README 已写：新增「Configuration profiles」小节 + 三层对照表 + 必需环境变量清单。
实施该条时撞出 [[issue-00096-the-quickstart-curl-names-a-tenant-the-edge-rejects]]。

验证：`mvn -o test -pl start -am` 全绿，62 个测试 0 失败。
负向对照：移除探针配置后 `/actuator/health/readiness` 返回 **404**，与本 issue 复现段的预言一致。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00072-demo-seed-data-ships-in-a-production-migration]]（同一次 profile 拆分）
- [[issue-00045-web-handler-maps-unknown-route-to-500]]（actuator 进入 classpath 的由来）
