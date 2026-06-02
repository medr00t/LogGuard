package com.logguard.backend.service;

import com.logguard.backend.model.*;
import com.logguard.backend.repository.LogEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private final LogEntryRepository logEntryRepository;
    private final RestTemplate restTemplate;

    @Value("${ai.engine.url}")
    private String aiEngineUrl;

    private static final Set<String> CRITICAL_KEYWORDS = Set.of(
            "OutOfMemoryError", "OOM", "StackOverflowError"
    );
    private static final Set<String> HIGH_KEYWORDS = Set.of(
            "NullPointerException", "Connection refused", "timed out", "timeout",
            "FATAL", "deadlock", "disk full"
    );

    private static final int ERROR_RATE_THRESHOLD = 10;
    private static final int ERROR_RATE_WINDOW_MINUTES = 5;
    private static final int REPEATED_MSG_THRESHOLD = 5;
    private static final int REPEATED_MSG_WINDOW_MINUTES = 5;
    private static final int AI_WINDOW_MINUTES = 10;

    public Optional<Anomaly> analyze(LogEntry log) {
        Optional<Anomaly> keyword = checkKeywordMatch(log);
        if (keyword.isPresent()) return keyword;

        Optional<Anomaly> rateSpike = checkErrorRateSpike(log);
        if (rateSpike.isPresent()) return rateSpike;

        Optional<Anomaly> repeated = checkRepeatedMessage(log);
        if (repeated.isPresent()) return repeated;

        List<LogEntry> recentLogs = logEntryRepository
                .findByTimestampAfterOrderByTimestampDesc(
                        log.getTimestamp().minusMinutes(AI_WINDOW_MINUTES));
        return callAiEngine(log, recentLogs);
    }

    // ─── Rule-based checks ────────────────────────────────────

    private Optional<Anomaly> checkKeywordMatch(LogEntry log) {
        String msg = log.getMessage();
        for (String kw : CRITICAL_KEYWORDS) {
            if (msg.contains(kw)) {
                return Optional.of(build(AnomalyType.KEYWORD_MATCH, Severity.CRITICAL,
                        "Critical keyword detected: " + kw, log));
            }
        }
        for (String kw : HIGH_KEYWORDS) {
            if (msg.toLowerCase().contains(kw.toLowerCase())) {
                return Optional.of(build(AnomalyType.KEYWORD_MATCH, Severity.HIGH,
                        "High-severity keyword detected: " + kw, log));
            }
        }
        return Optional.empty();
    }

    private Optional<Anomaly> checkErrorRateSpike(LogEntry log) {
        if (log.getLevel() != LogLevel.ERROR && log.getLevel() != LogLevel.FATAL) {
            return Optional.empty();
        }
        LocalDateTime windowStart = log.getTimestamp().minusMinutes(ERROR_RATE_WINDOW_MINUTES);
        List<LogEntry> recent = logEntryRepository.findBySourceAndLevelInAndTimestampAfter(
                log.getSource(), List.of(LogLevel.ERROR, LogLevel.FATAL), windowStart);

        if (recent.size() >= ERROR_RATE_THRESHOLD) {
            return Optional.of(build(AnomalyType.ERROR_RATE_SPIKE, Severity.HIGH,
                    String.format("Error rate spike: %d errors from '%s' in the last %d minutes",
                            recent.size(), log.getSource(), ERROR_RATE_WINDOW_MINUTES),
                    log));
        }
        return Optional.empty();
    }

    private Optional<Anomaly> checkRepeatedMessage(LogEntry log) {
        LocalDateTime windowStart = log.getTimestamp().minusMinutes(REPEATED_MSG_WINDOW_MINUTES);
        List<LogEntry> repeated = logEntryRepository.findByMessageAndTimestampAfter(
                log.getMessage(), windowStart);

        if (repeated.size() >= REPEATED_MSG_THRESHOLD) {
            return Optional.of(build(AnomalyType.REPEATED_MESSAGE, Severity.MEDIUM,
                    String.format("Repeated message %d times in %d minutes: %s",
                            repeated.size(), REPEATED_MSG_WINDOW_MINUTES, truncate(log.getMessage(), 100)),
                    log));
        }
        return Optional.empty();
    }

    // ─── AI engine call ───────────────────────────────────────

    public Optional<Anomaly> callAiEngine(LogEntry entry, List<LogEntry> recentLogs) {
        try {
            List<Map<String, String>> logDtos = recentLogs.stream()
                    .map(e -> Map.of(
                            "source", e.getSource(),
                            "level", e.getLevel().name(),
                            "message", e.getMessage(),
                            "timestamp", e.getTimestamp().toString()))
                    .toList();

            // 1. Similar message (TF-IDF)
            Map<String, Object> similarPayload = Map.of(
                    "message", entry.getMessage(),
                    "source", entry.getSource(),
                    "recent_logs", logDtos);
            Optional<Anomaly> similar = callEndpoint("/analyze/similar", similarPayload, entry);
            if (similar.isPresent()) return similar;

            // 2. Predictive alert (linear regression)
            List<Integer> errorCounts = computeErrorCountsPerMinute(entry, recentLogs);
            Map<String, Object> predictPayload = Map.of(
                    "source", entry.getSource(),
                    "error_counts_per_minute", errorCounts);
            Optional<Anomaly> predictive = callEndpoint("/analyze/predict", predictPayload, entry);
            if (predictive.isPresent()) return predictive;

            // 3. Cascade failure (source correlation)
            Map<String, Object> cascadePayload = Map.of(
                    "source", entry.getSource(),
                    "timestamp", entry.getTimestamp().toString(),
                    "recent_logs", logDtos);
            return callEndpoint("/analyze/cascade", cascadePayload, entry);

        } catch (Exception e) {
            log.warn("AI engine call failed (skipping): {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Anomaly> callEndpoint(String path, Map<String, Object> payload, LogEntry entry) {
        try {
            Map<String, Object> response = restTemplate.postForObject(
                    aiEngineUrl + path, payload, Map.class);
            if (response == null || response.get("anomaly") == null) {
                return Optional.empty();
            }
            Map<String, String> data = (Map<String, String>) response.get("anomaly");
            return Optional.of(build(
                    AnomalyType.valueOf(data.get("type")),
                    Severity.valueOf(data.get("severity")),
                    data.get("description"),
                    entry));
        } catch (Exception e) {
            log.warn("AI endpoint {} failed: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private List<Integer> computeErrorCountsPerMinute(LogEntry entry, List<LogEntry> recentLogs) {
        int window = AI_WINDOW_MINUTES;
        int[] counts = new int[window];
        LocalDateTime now = entry.getTimestamp();

        for (LogEntry e : recentLogs) {
            if (!e.getSource().equals(entry.getSource())) continue;
            if (e.getLevel() != LogLevel.ERROR && e.getLevel() != LogLevel.FATAL) continue;
            long minutesAgo = ChronoUnit.MINUTES.between(e.getTimestamp(), now);
            if (minutesAgo >= 0 && minutesAgo < window) {
                counts[(int) minutesAgo]++;
            }
        }

        // oldest → newest
        List<Integer> result = new ArrayList<>(window);
        for (int i = window - 1; i >= 0; i--) {
            result.add(counts[i]);
        }
        return result;
    }

    // ─── Shared helpers ───────────────────────────────────────

    private Anomaly build(AnomalyType type, Severity severity, String description, LogEntry trigger) {
        return Anomaly.builder()
                .detectedAt(LocalDateTime.now())
                .type(type)
                .severity(severity)
                .description(description)
                .triggerLog(trigger)
                .build();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
