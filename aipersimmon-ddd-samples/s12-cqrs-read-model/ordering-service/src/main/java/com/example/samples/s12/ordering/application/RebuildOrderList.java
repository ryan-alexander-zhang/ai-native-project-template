package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;

/** Throw the projection away and build it again from the write model. */
public record RebuildOrderList() implements Command<Integer> {}
