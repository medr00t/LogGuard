package com.logguard.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class InfraHealthService {

    private final RestTemplate http;
    private final JdbcTemplate jdbc;

    @Value("${jenkins.url}")
    private String jenkinsUrl;

    @Value("${ai.engine.url}")
    private String aiEngineUrl;

    public InfraHealthService(RestTemplateBuilder builder, JdbcTemplate jdbc) {
        this.http = builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .build();
        this.jdbc = jdbc;
    }

    public Map<String, Object> checkAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend",    service("http://localhost:8080/api/health"));
        result.put("database",   database());
        result.put("jenkins",    service(jenkinsUrl + "/login"));
        result.put("sonarqube",  service("http://sonarqube:9000/api/system/status"));
        result.put("prometheus", service("http://prometheus:9090/-/ready"));
        result.put("grafana",    service("http://grafana:3000/api/health"));
        result.put("aiEngine",   service(aiEngineUrl + "/health"));
        return result;
    }

    private String service(String url) {
        try {
            http.getForEntity(url, String.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
