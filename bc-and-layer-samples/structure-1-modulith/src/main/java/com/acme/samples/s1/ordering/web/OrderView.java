package com.acme.samples.s1.ordering.web;

public record OrderView(String orderId, String status, long totalMinor, String currency) {}
