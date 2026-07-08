package com.acme.samples.s2.start;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Structure 2 — composition root. The only Spring Boot application; it assembles
 * every bounded context's Maven modules into one deployable. Boundaries between
 * contexts and layers are enforced at compile time by the Maven module graph
 * (see the reactor POM); the ArchUnit test documents and backstops them.
 */
@SpringBootApplication(scanBasePackages = "com.acme.samples.s2")
@MapperScan(basePackages = "com.acme.samples.s2", annotationClass = Mapper.class)
@EnableScheduling
public class S2Application {
    public static void main(String[] args) {
        SpringApplication.run(S2Application.class, args);
    }
}
