package com.aipersimmon.ddd.outbox;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Finds the application's {@link IntegrationEvent} implementations on the classpath, so a single
 * scan definition drives everything keyed off "the events this application knows": the {@link
 * com.aipersimmon.ddd.integration.IntegrationEventCatalog} (inbound {@code (type, version)} → local
 * class) and the transport's reach/topic map (which events are {@code @Externalized}, and to
 * where).
 *
 * <p>Scans the application's own packages ({@link AutoConfigurationPackages}) plus any listed in
 * {@code aipersimmon.ddd.integration.scan-packages} (comma-separated). The latter is needed when
 * integration events live outside the application's package — for example a shared {@code
 * contracts} module two microservices depend on — since those are not covered by the
 * auto-configuration packages.
 */
public final class IntegrationEventScanner {

  private IntegrationEventScanner() {}

  /**
   * @param beanFactory the context's bean factory, for the auto-configuration packages
   * @param scanPackages the raw {@code aipersimmon.ddd.integration.scan-packages} value
   *     (comma-separated, may be blank)
   * @return the concrete {@link IntegrationEvent} classes found, in a stable insertion order
   */
  public static Set<Class<? extends IntegrationEvent>> scan(
      BeanFactory beanFactory, String scanPackages) {
    Set<String> packages = new LinkedHashSet<>();
    if (AutoConfigurationPackages.has(beanFactory)) {
      packages.addAll(AutoConfigurationPackages.get(beanFactory));
    }
    for (String pkg : scanPackages.split(",")) {
      String trimmed = pkg.trim();
      if (!trimmed.isEmpty()) {
        packages.add(trimmed);
      }
    }
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(IntegrationEvent.class));
    Set<Class<? extends IntegrationEvent>> classes = new LinkedHashSet<>();
    for (String pkg : packages) {
      for (BeanDefinition def : scanner.findCandidateComponents(pkg)) {
        try {
          Class<?> c = Class.forName(def.getBeanClassName());
          if (IntegrationEvent.class.isAssignableFrom(c) && !c.isInterface()) {
            classes.add(c.asSubclass(IntegrationEvent.class));
          }
        } catch (ClassNotFoundException ignored) {
          // skip a candidate that cannot be loaded
        }
      }
    }
    return classes;
  }
}
