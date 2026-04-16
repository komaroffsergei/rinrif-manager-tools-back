package ru.reinform.rinrif.managertools.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.reinform.rinrif.managertools.model.ApiModels.HealthResponse;

import java.time.format.DateTimeFormatter;
import java.util.Date;

@RestController
public class HealthController {
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(DateTimeFormatter.ISO_INSTANT.format(new Date().toInstant()));
    }
}
