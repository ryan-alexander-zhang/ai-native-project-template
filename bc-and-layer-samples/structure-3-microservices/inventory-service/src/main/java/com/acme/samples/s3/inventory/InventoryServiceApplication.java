package com.acme.samples.s3.inventory;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Inventory microservice (own deployable, own schema). COLA-layered by package. */
@SpringBootApplication
@MapperScan(basePackages = "com.acme.samples.s3.inventory", annotationClass = Mapper.class)
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
