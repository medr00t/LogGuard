package com.logguard.backend.dto;

import lombok.Data;

@Data
public class DeploymentCallbackRequest {
    /** LOG | STATUS | BUILT */
    private String event;
    /** populated when event=STATUS or event=BUILT */
    private String status;
    /** Jenkins build number, populated on BUILT */
    private String buildNumber;
    private String level;
    private String message;
}
