package com.acme.samples.s3.ordering;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Ordering microservice (own deployable, own schema). COLA-layered by package. */
@SpringBootApplication
@MapperScan(basePackages = "com.acme.samples.s3.ordering", annotationClass = Mapper.class)
@EnableScheduling
public class OrderingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderingServiceApplication.class, args);
    }
}
