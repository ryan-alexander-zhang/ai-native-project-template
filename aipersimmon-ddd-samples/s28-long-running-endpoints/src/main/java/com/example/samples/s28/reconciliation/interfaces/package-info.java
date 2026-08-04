/**
 * The edges: two REST resources and one timer.
 *
 * <p>{@code ExportController} holds the asynchronous contract and, on a separate path, the synchronous export it is
 * an alternative to. {@code ImportController} holds the resumable upload. {@code ExportWorkerScheduler} is the only
 * thing that makes work happen without anybody asking, and it is off in tests so that every test drives one run.
 */
package com.example.samples.s28.reconciliation.interfaces;
