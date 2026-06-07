package com.logguard.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeploymentRequest {

    @NotBlank(message = "repoUrl is required")
    private String repoUrl;

    private String branch = "main";
}
