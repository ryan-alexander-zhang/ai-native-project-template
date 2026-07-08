/**
 * Shared kernel / published language for structure-1: value objects and the
 * cross-context integration-event types. Marked OPEN so both bounded contexts
 * may depend on it without a Spring Modulith boundary violation. In structure-2
 * and structure-3 these types move into each context's own {@code *-api} module.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        displayName = "Shared Kernel")
package com.acme.samples.s1.shared;
