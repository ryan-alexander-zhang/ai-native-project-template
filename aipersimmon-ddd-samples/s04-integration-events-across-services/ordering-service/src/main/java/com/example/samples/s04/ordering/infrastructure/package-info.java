/**
 * The adapters. Note what is <em>absent</em>: no publisher, no Kafka template, no outbox writer. The
 * framework's own adapters do that, and this package would look identical without a broker.
 */
package com.example.samples.s04.ordering.infrastructure;
