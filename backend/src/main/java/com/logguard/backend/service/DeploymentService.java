package com.logguard.backend.service;

import com.logguard.backend.dto.DeploymentCallbackRequest;
import com.logguard.backend.dto.DeploymentRequest;
import com.logguard.backend.dto.DeploymentResponse;
import com.logguard.backend.exception.ResourceNotFoundException;
import com.logguard.backend.model.Deployment;
import com.logguard.backend.model.DeploymentStatus;
import com.logguard.backend.model.LogLevel;
import com.logguard.backend.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentWorkerService workerService;
    private final DeploymentLogStreamer logStreamer;
    private final JenkinsService jenkinsService;

    @Value("${logguard.service.token}")
    private String serviceToken;

    // ── Trigger ───────────────────────────────────────────────────

    @Transactional
    public DeploymentResponse trigger(DeploymentRequest request, String username) {
        String branch = StringUtils.hasText(request.getBranch()) ? request.getBranch() : "main";

        Deployment deployment = Deployment.builder()
                .repoUrl(request.getRepoUrl())
                .branch(branch)
                .status(DeploymentStatus.PENDING)
                .triggeredBy(username)
                .build();
        deployment = deploymentRepository.save(deployment);

        final Long id          = deployment.getId();
        final String repoUrl   = deployment.getRepoUrl();
        final String finalBranch = deployment.getBranch();
        final String finalUser = username;

        // Trigger Jenkins AFTER this transaction commits so the row is visible
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                logStreamer.info(id, "Deployment queued — waiting for Jenkins pipeline to start");
                try {
                    jenkinsService.triggerBuild(id, repoUrl, finalBranch, finalUser);
                    logStreamer.info(id, "Jenkins pipeline triggered — view at http://localhost:8090/job/logguard-deploy/");
                } catch (Exception e) {
                    log.error("Failed to trigger Jenkins for deployment {}: {}", id, e.getMessage());
                    logStreamer.error(id, "Failed to reach Jenkins: " + e.getMessage());
                    deploymentRepository.findById(id).ifPresent(d -> {
                        d.setStatus(DeploymentStatus.FAILED);
                        deploymentRepository.save(d);
                    });
                }
            }
        });

        return toResponse(deployment);
    }

    // ── Jenkins callback ──────────────────────────────────────────

    @Transactional
    public void handleCallback(Long id, String token, DeploymentCallbackRequest body) {
        if (!serviceToken.equals(token)) {
            throw new AccessDeniedException("Invalid service token");
        }

        String event = body.getEvent();
        if (event == null) return;

        switch (event) {
            case "LOG" -> {
                boolean isError = "ERROR".equalsIgnoreCase(body.getLevel())
                        || "WARN".equalsIgnoreCase(body.getLevel());
                if (isError) logStreamer.error(id, body.getMessage());
                else         logStreamer.info(id, body.getMessage());
            }
            case "STATUS" -> {
                if (body.getStatus() != null) {
                    DeploymentStatus status = DeploymentStatus.valueOf(body.getStatus());
                    deploymentRepository.findById(id).ifPresent(d -> {
                        d.setStatus(status);
                        deploymentRepository.save(d);
                    });
                }
                if (body.getMessage() != null) logStreamer.info(id, body.getMessage());
            }
            case "BUILT" -> {
                // Store Jenkins console URL
                if (body.getBuildNumber() != null) {
                    final String jUrl = "http://localhost:8090/job/logguard-deploy/"
                            + body.getBuildNumber() + "/console";
                    deploymentRepository.findById(id).ifPresent(d -> {
                        d.setJenkinsUrl(jUrl);
                        deploymentRepository.save(d);
                    });
                    logStreamer.info(id, "Jenkins console: " + "http://localhost:8090/job/logguard-deploy/"
                            + body.getBuildNumber() + "/console");
                }
                // Fire the runtime (async) — Jenkins has finished building
                workerService.launchApp(id);
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DeploymentResponse getById(Long id) {
        return deploymentRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<DeploymentResponse> getAll() {
        return deploymentRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toResponse).toList();
    }

    private DeploymentResponse toResponse(Deployment d) {
        return DeploymentResponse.builder()
                .id(d.getId())
                .repoUrl(d.getRepoUrl())
                .branch(d.getBranch())
                .status(d.getStatus())
                .assignedPort(d.getAssignedPort())
                .triggeredBy(d.getTriggeredBy())
                .jenkinsUrl(d.getJenkinsUrl())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
