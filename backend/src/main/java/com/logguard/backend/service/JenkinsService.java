package com.logguard.backend.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
@Slf4j
public class JenkinsService {

    private static final String JOB_NAME = "logguard-deploy";

    @Value("${jenkins.url}")
    private String jenkinsUrl;

    @Value("${jenkins.admin.user}")
    private String adminUser;

    @Value("${jenkins.admin.token}")
    private String adminToken;

    @Value("${logguard.service.token}")
    private String serviceToken;

    private final RestTemplate rest = new RestTemplate();

    // ── Startup: wait for Jenkins, then provision the job ────────

    @PostConstruct
    public void initialize() {
        Thread.ofVirtual().start(() -> {
            waitForJenkins();
            ensureJobExists();
        });
    }

    private void waitForJenkins() {
        log.info("[Jenkins] Waiting for Jenkins to be ready at {}...", jenkinsUrl);
        for (int i = 0; i < 60; i++) {
            try {
                ResponseEntity<String> r = rest.exchange(
                        jenkinsUrl + "/api/json",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders()),
                        String.class);
                if (r.getStatusCode().is2xxSuccessful()) {
                    log.info("[Jenkins] Jenkins is ready.");
                    return;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("[Jenkins] Jenkins did not become ready after 5 minutes — deployment pipelines may fail.");
    }

    // ── Job provisioning ─────────────────────────────────────────

    public void ensureJobExists() {
        try {
            String pipelineScript = loadPipelineScript();
            String configXml = buildJobConfigXml(pipelineScript);
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);

            // Check if job exists
            boolean exists = false;
            try {
                rest.exchange(jenkinsUrl + "/job/" + JOB_NAME + "/api/json",
                        HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
                exists = true;
            } catch (HttpClientErrorException.NotFound ignored) {}

            if (exists) {
                // Update the job config so Jenkinsfile changes take effect on restart
                rest.exchange(jenkinsUrl + "/job/" + JOB_NAME + "/config.xml",
                        HttpMethod.POST, new HttpEntity<>(configXml, headers), String.class);
                log.info("[Jenkins] Pipeline job '{}' config updated.", JOB_NAME);
            } else {
                rest.exchange(jenkinsUrl + "/createItem?name=" + JOB_NAME,
                        HttpMethod.POST, new HttpEntity<>(configXml, headers), String.class);
                log.info("[Jenkins] Pipeline job '{}' created.", JOB_NAME);
            }
        } catch (Exception e) {
            log.warn("[Jenkins] Could not provision pipeline job: {}", e.getMessage());
        }
    }

    // ── Trigger a build ──────────────────────────────────────────

    public void triggerBuild(Long deploymentId, String repoUrl, String branch, String triggeredBy) {
        try {
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("DEPLOYMENT_ID", String.valueOf(deploymentId));
            params.add("REPO_URL",      repoUrl);
            params.add("BRANCH",        branch);
            params.add("TRIGGERED_BY",  triggeredBy);
            params.add("LOGGUARD_URL",  "http://backend:8080");
            params.add("SERVICE_TOKEN", serviceToken);

            rest.exchange(
                    jenkinsUrl + "/job/" + JOB_NAME + "/buildWithParameters",
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    String.class);

            log.info("[Jenkins] Triggered build for deployment {} ({})", deploymentId, repoUrl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to trigger Jenkins build: " + e.getMessage(), e);
        }
    }

    // ── User management (same credentials as LogGuard) ───────────

    public void createUser(String username, String rawPassword) {
        try {
            if (userExists(username)) {
                log.debug("[Jenkins] User '{}' already exists.", username);
                return;
            }

            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("username",  username);
            form.add("password1", rawPassword);
            form.add("password2", rawPassword);
            form.add("fullname",  username);
            form.add("email",     username + "@logguard.local");
            form.add("Submit",    "Create Account");

            rest.exchange(
                    jenkinsUrl + "/securityRealm/createAccountByAdmin",
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    String.class);

            log.info("[Jenkins] Created Jenkins user '{}'.", username);
        } catch (Exception e) {
            log.warn("[Jenkins] Could not create user '{}': {}", username, e.getMessage());
        }
    }

    private boolean userExists(String username) {
        try {
            rest.exchange(
                    jenkinsUrl + "/user/" + username + "/api/json",
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    String.class);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        String creds = Base64.getEncoder().encodeToString(
                (adminUser + ":" + adminToken).getBytes(StandardCharsets.UTF_8));
        h.set("Authorization", "Basic " + creds);
        return h;
    }

    private String loadPipelineScript() throws Exception {
        try (var in = getClass().getResourceAsStream("/jenkins/deploy.Jenkinsfile")) {
            if (in == null) throw new IllegalStateException("deploy.Jenkinsfile not found in classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String buildJobConfigXml(String pipelineScript) {
        return """
                <?xml version='1.1' encoding='UTF-8'?>
                <flow-definition plugin="workflow-job">
                  <description>LogGuard automated user deployment pipeline</description>
                  <keepDependencies>false</keepDependencies>
                  <properties>
                    <hudson.model.ParametersDefinitionProperty>
                      <parameterDefinitions>
                        <hudson.model.StringParameterDefinition>
                          <name>DEPLOYMENT_ID</name><defaultValue/>
                        </hudson.model.StringParameterDefinition>
                        <hudson.model.StringParameterDefinition>
                          <name>REPO_URL</name><defaultValue/>
                        </hudson.model.StringParameterDefinition>
                        <hudson.model.StringParameterDefinition>
                          <name>BRANCH</name><defaultValue>main</defaultValue>
                        </hudson.model.StringParameterDefinition>
                        <hudson.model.StringParameterDefinition>
                          <name>TRIGGERED_BY</name><defaultValue>unknown</defaultValue>
                        </hudson.model.StringParameterDefinition>
                        <hudson.model.StringParameterDefinition>
                          <name>LOGGUARD_URL</name><defaultValue>http://backend:8080</defaultValue>
                        </hudson.model.StringParameterDefinition>
                        <hudson.model.StringParameterDefinition>
                          <name>SERVICE_TOKEN</name><defaultValue/>
                        </hudson.model.StringParameterDefinition>
                      </parameterDefinitions>
                    </hudson.model.ParametersDefinitionProperty>
                  </properties>
                  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
                    <script><![CDATA[%s]]></script>
                    <sandbox>true</sandbox>
                  </definition>
                  <triggers/>
                  <disabled>false</disabled>
                </flow-definition>
                """.formatted(pipelineScript);
    }
}
