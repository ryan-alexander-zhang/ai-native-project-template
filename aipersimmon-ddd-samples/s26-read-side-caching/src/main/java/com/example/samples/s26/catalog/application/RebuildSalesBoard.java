package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import java.time.Duration;

/**
 * Recompute the projection from the sales facts.
 *
 * <p>The operation that distinguishes a projection from a cache, expressed as a command an operator can
 * actually invoke. A read model whose rebuild exists only in principle is a read model that will be
 * repaired by hand at three in the morning; S12 makes the same argument and reaches the same conclusion.
 */
public record RebuildSalesBoard(Duration window) implements Command<Integer> {}
