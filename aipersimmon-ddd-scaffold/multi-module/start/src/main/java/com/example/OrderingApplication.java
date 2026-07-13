package com.example;

import com.aipersimmon.ddd.web.error.ProblemTypeCatalog;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Boots the ordering context, wiring every layer into one deployable. */
@SpringBootApplication
public class OrderingApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderingApplication.class, args);
    }

    /** Registers the ordering error catalogue so domain codes resolve to problem types. */
    @Bean
    ProblemTypeCatalog orderingProblemTypeCatalog() {
        return () -> List.of(OrderingProblemType.values());
    }
}
