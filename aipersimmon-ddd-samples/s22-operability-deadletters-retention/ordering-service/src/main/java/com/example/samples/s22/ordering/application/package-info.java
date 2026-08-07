/**
 * Commands, and the operations surface.
 *
 * <p>The unusual member of this package is {@link
 * com.example.samples.s22.ordering.application.Quarantine}: an application service whose collaborators
 * are two framework ports rather than a domain repository. It lives here rather than in {@code
 * adapter} because "which dead letters exist and may this one be replayed" is a decision, and a
 * controller that talked to {@code DeadLetterStore} directly would put that decision in a place with
 * no way to state it.
 */
package com.example.samples.s22.ordering.application;
