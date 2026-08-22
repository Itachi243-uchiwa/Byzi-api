package com.byzi.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ByziApplication {

    public static void main(String[] args) {
        SpringApplication.run(ByziApplication.class, args);
    }

}
