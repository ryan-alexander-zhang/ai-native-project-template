/**
 * The published language. One event, and its only consumer is a company that has never heard of this
 * package.
 *
 * <p>It lives here because the library's ArchUnit rules require {@code IntegrationEvent}s to, and the
 * rule turns out to be right for a reason that has nothing to do with brokers: this record is the one
 * class in the service whose shape is pinned by something outside it. A backlog of rows written last
 * week will be deserialized into it tomorrow, so it changes by the rules of S21, not by refactoring.
 */
package com.example.samples.s07.payments.api;
