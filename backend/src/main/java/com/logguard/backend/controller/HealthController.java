package com.logguard.backend.controller;

import com.logguard.backend.service.InfraHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final InfraHealthService infraHealth;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "LogGuard Backend");
    }

    @GetMapping("/health/services")
    public Map<String, Object> services() {
        return infraHealth.checkAll();
    }
}
