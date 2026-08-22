package com.buzi.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BuziApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuziApplication.class, args);
    }

}
