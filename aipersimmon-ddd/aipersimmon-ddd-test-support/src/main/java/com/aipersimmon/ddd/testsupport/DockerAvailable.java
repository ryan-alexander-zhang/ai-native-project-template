package com.aipersimmon.ddd.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * Docker-availability guard for container tests. Reference it from JUnit's {@code @EnabledIf} by
 * fully-qualified method name so a container-less build skips the test instead of failing:
 *
 * <pre>{@code
 * @EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
 * }</pre>
 */
public final class DockerAvailable {

  private DockerAvailable() {}

  /** Whether a usable Docker environment is reachable. */
  public static boolean dockerAvailable() {
    return DockerClientFactory.instance().isDockerAvailable();
  }
}
