package com.example.howto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the how-to; the outbox and inbox starters auto-configure themselves. */
@SpringBootApplication
public class HowToApplication {

    public static void main(String[] args) {
        SpringApplication.run(HowToApplication.class, args);
    }
}
