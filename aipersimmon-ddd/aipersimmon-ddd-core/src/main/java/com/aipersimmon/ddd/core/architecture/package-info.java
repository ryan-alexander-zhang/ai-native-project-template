/**
 * Layer stereotype annotations applied to a package's {@code package-info.java} to declare which
 * architectural layer the package belongs to — domain, application, infrastructure, or interface.
 * Architecture tests read them to enforce the allowed dependency directions: domain depends on
 * nothing outward, application depends on domain, and the infrastructure and interface layers
 * depend inward on application and domain.
 */
package com.aipersimmon.ddd.core.architecture;
