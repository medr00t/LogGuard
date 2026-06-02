package com.logguard.backend;

import com.logguard.backend.dto.LoginRequest;
import com.logguard.backend.dto.LoginResponse;
import com.logguard.backend.dto.LogEntryRequest;
import com.logguard.backend.dto.LogEntryResponse;
import com.logguard.backend.model.Alert;
import com.logguard.backend.model.LogLevel;
import com.logguard.backend.model.Severity;
import com.logguard.backend.repository.AlertRepository;
import com.logguard.backend.repository.AnomalyRepository;
import com.logguard.backend.repository.LogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LogGuardIntegrationTest {

    @Autowired private TestRestTemplate http;
    @Autowired private AlertRepository alertRepository;
    @Autowired private AnomalyRepository anomalyRepository;
    @Autowired private LogEntryRepository logEntryRepository;

    @MockBean private RestTemplate restTemplate;

    private HttpHeaders auth;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        anomalyRepository.deleteAll();
        logEntryRepository.deleteAll();

        // AI engine returns nothing by default
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of());

        LoginRequest login = new LoginRequest();
        login.setUsername("admin");
        login.setPassword("admin123");
        String token = http.postForEntity("/api/auth/login", login, LoginResponse.class)
                .getBody().getToken();

        auth = new HttpHeaders();
        auth.setBearerAuth(token);
        auth.setContentType(MediaType.APPLICATION_JSON);
    }

    // ─── Rule-based detectors ─────────────────────────────────

    @Test
    @DisplayName("CRITICAL keyword 'OutOfMemoryError' → KEYWORD_MATCH alert")
    void keywordMatch_OutOfMemoryError_triggersCriticalAlert() {
        postLog(LogLevel.ERROR, "jvm-service", "java.lang.OutOfMemoryError: Java heap space");

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(alerts.get(0).getMessage()).contains("KEYWORD_MATCH");
    }

    @Test
    @DisplayName("HIGH keyword 'NullPointerException' → KEYWORD_MATCH alert")
    void keywordMatch_NullPointerException_triggersHighAlert() {
        postLog(LogLevel.ERROR, "user-service", "NullPointerException in UserController line 42");

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(alerts.get(0).getMessage()).contains("KEYWORD_MATCH");
    }

    @Test
    @DisplayName("10 ERRORs from same source in 5 min → ERROR_RATE_SPIKE alert")
    void errorRateSpike_10ErrorsFromSameSource_triggersHighAlert() {
        for (int i = 0; i < 10; i++) {
            postLog(LogLevel.ERROR, "payment-service", "Payment rejected by gateway, code=" + i);
        }

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).isNotEmpty();
        assertThat(alerts).anyMatch(a -> a.getMessage().contains("ERROR_RATE_SPIKE"));
        assertThat(alerts).anyMatch(a -> a.getSeverity() == Severity.HIGH);
    }

    @Test
    @DisplayName("5 identical messages in 5 min → REPEATED_MESSAGE alert")
    void repeatedMessage_sameMessageFiveTimes_triggersMediumAlert() {
        String msg = "Background sync job failed to acquire lock";
        for (int i = 0; i < 5; i++) {
            postLog(LogLevel.WARN, "scheduler", msg);
        }

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getMessage()).contains("REPEATED_MESSAGE");
        assertThat(alerts.get(0).getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    // ─── AI engine detectors ──────────────────────────────────

    @Test
    @DisplayName("AI engine detects SIMILAR_MESSAGE → MEDIUM alert")
    void aiEngine_similarMessage_triggersMediumAlert() {
        when(restTemplate.postForObject(contains("/analyze/similar"), any(), eq(Map.class)))
                .thenReturn(Map.of("anomaly", Map.of(
                        "type", "SIMILAR_MESSAGE",
                        "severity", "MEDIUM",
                        "description", "Found 5 semantically similar messages from 'cache-service' (max cosine similarity: 0.91)")));

        postLog(LogLevel.WARN, "cache-service", "Cache miss for key user_session_abc");

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getMessage()).contains("SIMILAR_MESSAGE");
        assertThat(alerts.get(0).getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("AI engine detects CASCADE_FAILURE → CRITICAL alert")
    void aiEngine_cascadeFailure_triggersCriticalAlert() {
        when(restTemplate.postForObject(contains("/analyze/cascade"), any(), eq(Map.class)))
                .thenReturn(Map.of("anomaly", Map.of(
                        "type", "CASCADE_FAILURE",
                        "severity", "CRITICAL",
                        "description", "Cascading failure across 4 services: api-gateway, database, order-service, user-service")));

        postLog(LogLevel.ERROR, "order-service", "Failed to process order: upstream unavailable");

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getMessage()).contains("CASCADE_FAILURE");
        assertThat(alerts.get(0).getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("AI engine detects PREDICTIVE_ALERT → HIGH alert")
    void aiEngine_predictiveAlert_triggersHighAlert() {
        when(restTemplate.postForObject(contains("/analyze/predict"), any(), eq(Map.class)))
                .thenReturn(Map.of("anomaly", Map.of(
                        "type", "PREDICTIVE_ALERT",
                        "severity", "HIGH",
                        "description", "Error rate from 'analytics-service' predicted to reach 12.5/min")));

        postLog(LogLevel.ERROR, "analytics-service", "Slow query detected in aggregation pipeline");

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getMessage()).contains("PREDICTIVE_ALERT");
        assertThat(alerts.get(0).getSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("Normal INFO log → no alert created")
    void normalLog_noAnomalyTrigger_createsNoAlert() {
        postLog(LogLevel.INFO, "web-service", "GET /api/users 200 OK in 45ms");

        assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("AI engine unreachable → log still ingested, no crash")
    void aiEngineDown_logStillIngested_noAlertButNoException() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused: ai-engine:5000"));

        ResponseEntity<LogEntryResponse> response = postLog(
                LogLevel.WARN, "resilient-service", "Retrying failed operation");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(logEntryRepository.findAll()).hasSize(1);
        assertThat(alertRepository.findAll()).isEmpty();
    }

    // ─── Helper ──────────────────────────────────────────────

    private ResponseEntity<LogEntryResponse> postLog(LogLevel level, String source, String message) {
        LogEntryRequest req = new LogEntryRequest();
        req.setLevel(level);
        req.setSource(source);
        req.setMessage(message);
        return http.exchange("/api/logs", HttpMethod.POST,
                new HttpEntity<>(req, auth), LogEntryResponse.class);
    }
}
