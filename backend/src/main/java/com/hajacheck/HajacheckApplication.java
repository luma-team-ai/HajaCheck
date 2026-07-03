package com.hajacheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class HajacheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(HajacheckApplication.class, args);
    }
}
