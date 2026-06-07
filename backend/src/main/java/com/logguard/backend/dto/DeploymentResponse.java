package com.logguard.backend.dto;

import com.logguard.backend.model.DeploymentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DeploymentResponse {
    private Long id;
    private String repoUrl;
    private String branch;
    private DeploymentStatus status;
    private Integer assignedPort;
    private String triggeredBy;
    private String jenkinsUrl;
    private LocalDateTime createdAt;
}
