package com.logguard.backend.service;

import com.logguard.backend.dto.LogEntryRequest;
import com.logguard.backend.model.LogLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentLogStreamer {

    private final LogService logService;

    public void info(Long deploymentId, String message) {
        emit(deploymentId, LogLevel.INFO, message);
    }

    public void error(Long deploymentId, String message) {
        emit(deploymentId, LogLevel.ERROR, message);
    }

    private void emit(Long deploymentId, LogLevel level, String message) {
        if (message == null || message.isBlank()) return;
        String truncated = message.length() > 2000 ? message.substring(0, 1997) + "..." : message;
        LogEntryRequest req = new LogEntryRequest();
        req.setLevel(level);
        req.setSource("deployment-worker-" + deploymentId);
        req.setMessage(truncated);
        try {
            logService.ingest(req);
        } catch (Exception e) {
            log.warn("Failed to stream log for deployment {}: {}", deploymentId, e.getMessage());
        }
    }
}
