/**
 * The use case, the port it collaborates through, and the precheck that makes the remote call outside the
 * transaction.
 *
 * <p>The port lives here rather than in the domain because "what does another context think" is a
 * collaboration between use cases, not part of how an aggregate exists.
 */
package com.example.samples.s06.ordering.application;
