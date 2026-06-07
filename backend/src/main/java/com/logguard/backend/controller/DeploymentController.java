package com.logguard.backend.controller;

import com.logguard.backend.dto.DeploymentCallbackRequest;
import com.logguard.backend.dto.DeploymentRequest;
import com.logguard.backend.dto.DeploymentResponse;
import com.logguard.backend.service.DeploymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeploymentResponse trigger(
            @Valid @RequestBody DeploymentRequest request,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        return deploymentService.trigger(request, username);
    }

    @GetMapping
    public List<DeploymentResponse> getAll() {
        return deploymentService.getAll();
    }

    @GetMapping("/{id}")
    public DeploymentResponse getById(@PathVariable Long id) {
        return deploymentService.getById(id);
    }

    /** Called by Jenkins pipeline to stream logs and status updates back. */
    @PostMapping("/{id}/callback")
    @ResponseStatus(HttpStatus.OK)
    public void callback(
            @PathVariable Long id,
            @RequestHeader("X-Service-Token") String token,
            @RequestBody DeploymentCallbackRequest body) {
        deploymentService.handleCallback(id, token, body);
    }
}
