package com.example.samples.s26.catalog.application;

/** One row of the best-sellers list: a sku, its name, and how much of it went. */
public record TopSeller(String sku, String name, long soldRecently) {}
