package com.example.howto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the how-to; the command bus, the outbox, and the event publisher auto-configure. */
@SpringBootApplication
public class HowToApplication {

    public static void main(String[] args) {
        SpringApplication.run(HowToApplication.class, args);
    }
}
