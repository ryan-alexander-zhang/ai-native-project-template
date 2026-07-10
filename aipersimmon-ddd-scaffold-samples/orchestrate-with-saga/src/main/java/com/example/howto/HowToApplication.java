package com.example.howto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the how-to. The saga starter auto-configures the in-process deadline
 * scheduler because this application provides a {@code DeadlineHandler} bean
 * ({@link OrderFulfilment}).
 */
@SpringBootApplication
public class HowToApplication {

    public static void main(String[] args) {
        SpringApplication.run(HowToApplication.class, args);
    }
}
