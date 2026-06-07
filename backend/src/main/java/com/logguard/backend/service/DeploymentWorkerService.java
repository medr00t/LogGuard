package com.logguard.backend.service;

import com.logguard.backend.model.DeploymentStatus;
import com.logguard.backend.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Handles the CD (run) half of the pipeline.
 * Jenkins handles clone + build; this service starts and monitors the resulting process.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentWorkerService {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogStreamer logStreamer;
    private final ProjectTypeDetector typeDetector;

    // Called by DeploymentService when Jenkins reports BUILT
    @Async("deploymentExecutor")
    public void launchApp(Long id) {
        Path workDir = Path.of("/tmp/deployments", id.toString());

        int port = 3000 + id.intValue();
        savePort(id, port);
        setStatus(id, DeploymentStatus.DEPLOYING);

        ProjectTypeDetector.ProjectCommands cmds = typeDetector.detect(workDir);
        logStreamer.info(id, "Start command: " + cmds.startCommand());
        logStreamer.info(id, "Assigned port: " + port);

        try {
            launchAndMonitor(id, workDir, cmds.startCommand(), port);
        } catch (Exception e) {
            fail(id, "Launch error: " + e.getMessage());
        }
    }

    // ── Long-running process ──────────────────────────────────────

    private void launchAndMonitor(Long id, Path workDir, String command, int port) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                .directory(workDir.toFile());
        pb.environment().put("PORT",    String.valueOf(port));
        pb.environment().put("CI",      "false");
        pb.environment().put("BROWSER", "none");
        Process process = pb.start();

        Thread.ofVirtual().start(() ->
                pipeLines(process.getInputStream(), line -> logStreamer.info(id, line)));
        Thread.ofVirtual().start(() ->
                pipeLines(process.getErrorStream(), line -> logStreamer.error(id, line)));

        // 5-second startup probe
        boolean exitedEarly = process.waitFor(5, TimeUnit.SECONDS);
        if (exitedEarly && process.exitValue() != 0) {
            fail(id, "Process crashed at startup with exit code: " + process.exitValue());
            return;
        }

        setStatus(id, DeploymentStatus.SUCCESS);
        logStreamer.info(id, "App is live → http://localhost:" + port);

        process.onExit().thenAccept(p -> {
            if (p.exitValue() != 0) {
                logStreamer.error(id, "Deployed process exited unexpectedly with code: " + p.exitValue());
                setStatus(id, DeploymentStatus.FAILED);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void savePort(Long id, int port) {
        deploymentRepository.findById(id).ifPresent(d -> {
            d.setAssignedPort(port);
            deploymentRepository.save(d);
        });
    }

    private void setStatus(Long id, DeploymentStatus status) {
        deploymentRepository.findById(id).ifPresent(d -> {
            d.setStatus(status);
            deploymentRepository.save(d);
        });
    }

    private void fail(Long id, String reason) {
        logStreamer.error(id, reason);
        deploymentRepository.findById(id).ifPresent(d -> {
            d.setStatus(DeploymentStatus.FAILED);
            deploymentRepository.save(d);
        });
    }

    private void pipeLines(InputStream in, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) consumer.accept(line);
        } catch (IOException ignored) {}
    }
}
