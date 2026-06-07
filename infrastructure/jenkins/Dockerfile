FROM jenkins/jenkins:lts

USER root

RUN apt-get update && apt-get install -y \
    docker.io \
    maven \
    nodejs \
    npm \
    python3 \
    make \
    g++ \
    curl \
    apt-transport-https \
    ca-certificates \
    gnupg \
    && ln -sf python3 /usr/bin/python \
    && rm -rf /var/lib/apt/lists/*

# kubectl for optional Kubernetes deploys
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
    && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl \
    && rm kubectl

RUN usermod -aG docker jenkins

# Global node-gyp (v10+) supports Python 3 — overrides the ancient node-gyp 3.x
# bundled with legacy packages like node-sass@4.x
RUN npm install -g node-gyp

# Trivy — container/filesystem vulnerability scanner
RUN curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
    | sh -s -- -b /usr/local/bin

RUN jenkins-plugin-cli --plugins \
    git \
    workflow-aggregator \
    docker-workflow \
    sonar \
    junit \
    pipeline-stage-view \
    blueocean \
    credentials \
    credentials-binding

# Init scripts run on every Jenkins startup
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

EXPOSE 8080 50000
