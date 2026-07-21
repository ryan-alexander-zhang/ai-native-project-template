package com.example.ordering.adapter.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application root for the web-slice tests. The real
 * {@code @SpringBootApplication} lives in the start module, which this adapter does not depend on,
 * so {@code @WebMvcTest} needs a local one to bootstrap the MVC slice and to component-scan this
 * package so the controller under test is discovered (then filtered to just that controller by the
 * slice).
 */
@SpringBootApplication
class WebSliceTestApp {}
