FROM jenkins/jenkins:lts

USER root

# Install Docker CLI (so Jenkins can build Docker images)
# Install Maven (so Jenkins can build Spring Boot)
RUN apt-get update && apt-get install -y \
    docker.io \
    maven \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Add jenkins user to docker group so it can run docker commands
RUN usermod -aG docker jenkins

# Stay as root so Docker socket access works on Windows/Docker Desktop
# (acceptable for local development and demo purposes)

# Pre-install useful Jenkins plugins
RUN jenkins-plugin-cli --plugins \
    git \
    workflow-aggregator \
    docker-workflow \
    sonar \
    junit \
    pipeline-stage-view \
    blueocean