package com.logguard.backend.repository;

import com.logguard.backend.model.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {
    List<Deployment> findAllByOrderByCreatedAtDesc();
}
