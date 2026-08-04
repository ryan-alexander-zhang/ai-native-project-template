package com.example.samples.s10;

import com.aipersimmon.ddd.testsupport.ContainerImages;
import com.example.samples.s10.banking.AccountServiceApplication;
import com.example.samples.s10.points.PointsServiceApplication;
import java.util.List;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One coordinator, two databases, two applications — started once and shared.
 *
 * <p><strong>What this harness is faithful about.</strong> The two services talk over real HTTP through real
 * Tomcats, so the {@code TX_XID} header genuinely has to be written by one and read by the other; the two
 * databases are separate PostgreSQL instances, so the two branches genuinely belong to different resources;
 * and the transaction coordinator is the real {@code apache/seata-server} image rather than a stub.
 *
 * <p><strong>What it is not faithful about, stated rather than glossed.</strong> Both applications run in one
 * JVM, so they share Seata's client singletons — the TM and RM netty clients are per-JVM, keyed by the first
 * application id to ask for them. The harness therefore gives both contexts the <em>same</em> application id,
 * which is a lie a deployment does not tell. What it hides: anything that depends on the coordinator
 * distinguishing the two applications, and any failure mode involving one process dying while the other
 * lives. What it still proves: the header, the branches, the undo logs, the locks, the rollbacks and the TCC
 * phases — which is everything the sample claims.
 *
 * <p>The alternative was two JVMs started by the build. It would buy the process boundary and cost a
 * fragile lifecycle, and the properties that would newly be testable are the ones a sample cannot assert
 * anyway (kill -9 timing).
 */
final class TwoServiceWorld {

  static final String TENANT = "acme";
  static final int ACCOUNT_PORT = 18104;
  static final int POINTS_PORT = 18105;

  private static final PostgreSQLContainer<?> ACCOUNT_DB =
      new PostgreSQLContainer<>(ContainerImages.POSTGRES).withInitScript("undo_log.sql");
  private static final PostgreSQLContainer<?> POINTS_DB =
      new PostgreSQLContainer<>(ContainerImages.POSTGRES).withInitScript("undo_log.sql");

  /**
   * The transaction coordinator. Bound to fixed host ports because the clients address it by the literal
   * {@code 127.0.0.1:8091} in their configuration, exactly as a deployment addresses a service name — Seata's
   * file registry has no discovery to redirect a mapped port.
   */
  private static final GenericContainer<?> SEATA =
      new GenericContainer<>(DockerImageName.parse("apache/seata-server:2.6.0.jdk21"))
          .withEnv("SEATA_IP", "127.0.0.1")
          .waitingFor(Wait.forLogMessage(".*Server started, service listen port.*\\n", 1))
          .withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("seata-server")));

  private static ConfigurableApplicationContext accountApp;
  private static ConfigurableApplicationContext pointsApp;
  private static JdbcTemplate accountJdbc;
  private static JdbcTemplate pointsJdbc;

  private TwoServiceWorld() {}

  static synchronized void start() {
    if (accountApp != null) {
      return;
    }
    SEATA.setPortBindings(List.of("8091:8091", "7091:7091"));
    SEATA.start();
    ACCOUNT_DB.start();
    POINTS_DB.start();

    // Each service's own builder, so the harness cannot drift from main() on the base package or the
    // configuration resource name. Overrides go in as command-line arguments rather than as
    // SpringApplicationBuilder.properties(...): those land in defaultProperties, which sits *below* the
    // service's own yaml in precedence — so an override passed that way is read, ignored, and impossible to
    // notice. This was found the same way.
    pointsApp =
        PointsServiceApplication.application()
            .run(
                argsFor(
                    POINTS_DB,
                    "--server.port=" + POINTS_PORT,
                    // One application id for both, for the reason in this class's javadoc.
                    "--seata.application-id=s10-tests"));

    accountApp =
        AccountServiceApplication.application()
            .run(
                argsFor(
                    ACCOUNT_DB,
                    "--server.port=" + ACCOUNT_PORT,
                    "--banking.points-service-url=http://localhost:" + POINTS_PORT,
                    "--seata.application-id=s10-tests"));

    accountJdbc = accountApp.getBean(JdbcTemplate.class);
    pointsJdbc = pointsApp.getBean(JdbcTemplate.class);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  accountApp.close();
                  pointsApp.close();
                }));
  }

  private static String[] argsFor(PostgreSQLContainer<?> container, String... extra) {
    List<String> args =
        new java.util.ArrayList<>(
            List.of(
                "--spring.datasource.url=" + container.getJdbcUrl(),
                "--spring.datasource.username=" + container.getUsername(),
                "--spring.datasource.password=" + container.getPassword()));
    args.addAll(List.of(extra));
    return args.toArray(String[]::new);
  }

  static JdbcTemplate accountJdbc() {
    return accountJdbc;
  }

  static JdbcTemplate pointsJdbc() {
    return pointsJdbc;
  }

  static String accountUrl(String path) {
    return "http://localhost:" + ACCOUNT_PORT + path;
  }

  static String pointsUrl(String path) {
    return "http://localhost:" + POINTS_PORT + path;
  }
}
