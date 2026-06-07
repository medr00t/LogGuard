package com.logguard.backend.config;

import com.logguard.backend.model.AppUser;
import com.logguard.backend.model.Role;
import com.logguard.backend.repository.UserRepository;
import com.logguard.backend.service.JenkinsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JenkinsService jenkinsService;

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin123";

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername(ADMIN_USER).isEmpty()) {
            userRepository.save(AppUser.builder()
                    .username(ADMIN_USER)
                    .password(passwordEncoder.encode(ADMIN_PASS))
                    .role(Role.ADMIN)
                    .build());
        }
        // Sync admin to Jenkins so the same credentials work at http://localhost:8090
        // JenkinsService.initialize() runs concurrently in a virtual thread — createUser
        // is idempotent and will retry gracefully if Jenkins isn't ready yet.
        Thread.ofVirtual().start(() -> jenkinsService.createUser(ADMIN_USER, ADMIN_PASS));
    }
}
