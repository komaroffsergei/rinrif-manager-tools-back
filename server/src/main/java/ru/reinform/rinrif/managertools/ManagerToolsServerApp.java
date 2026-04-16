package ru.reinform.rinrif.managertools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableCaching
@SpringBootApplication
public class ManagerToolsServerApp {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{ManagerToolsServerApp.class}, args);
    }
}
