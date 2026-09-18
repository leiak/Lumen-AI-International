package com.lumen;

import com.lumen.extension.EnableExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.lumen")
@EnableExtension
@EnableAsync
public class LumenApplication {
    public static void main(String[] args) {
        SpringApplication.run(LumenApplication.class, args);
    }
}