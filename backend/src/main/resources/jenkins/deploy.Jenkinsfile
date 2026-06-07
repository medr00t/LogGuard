pipeline {
    agent any

    parameters {
        string(name: 'DEPLOYMENT_ID',  defaultValue: '')
        string(name: 'REPO_URL',       defaultValue: '')
        string(name: 'BRANCH',         defaultValue: 'main')
        string(name: 'TRIGGERED_BY',   defaultValue: 'unknown')
        string(name: 'LOGGUARD_URL',   defaultValue: 'http://backend:8080')
        string(name: 'SERVICE_TOKEN',  defaultValue: '')
    }

    stages {

        stage('Clone') {
            steps {
                script {
                    lgStatus('CLONING', "Cloning ${params.REPO_URL} @ ${params.BRANCH}")
                    def workDir = "/tmp/deployments/${params.DEPLOYMENT_ID}"
                    sh "mkdir -p '${workDir}' && rm -rf '${workDir}'/* '${workDir}'/.git 2>/dev/null || true"
                    sh "git clone --branch '${params.BRANCH}' --depth 1 '${params.REPO_URL}' '${workDir}'"
                    lgLog('INFO', 'Clone complete')
                }
            }
        }

        stage('Build') {
            steps {
                script {
                    lgStatus('BUILDING', 'Detecting project type and building...')
                    def workDir = "/tmp/deployments/${params.DEPLOYMENT_ID}"
                    dir(workDir) {
                        if (fileExists('package.json')) {
                            def pkg = readFile('package.json')
                            def hasBuild = pkg.contains('"build"')
                            def hasStart = pkg.contains('"start"')
                            if (hasBuild || hasStart) {
                                lgLog('INFO', 'Detected Node.js project — running npm install')
                                // npm_config_node_gyp forces npm to use the globally installed
                                // node-gyp (v10+, Python-3-compatible) instead of the ancient
                                // node-gyp 3.x that ships with legacy packages like node-sass@4.x
                                sh '''
                                    export npm_config_node_gyp=$(npm root -g)/node-gyp/bin/node-gyp.js
                                    export npm_config_python=python3
                                    npm install --legacy-peer-deps
                                '''
                                if (hasBuild) {
                                    lgLog('INFO', 'Running npm run build')
                                    sh 'CI=false npm run build'
                                }
                            } else {
                                lgLog('INFO', 'package.json found but no build/start scripts — treated as static site')
                            }
                        } else if (fileExists('pom.xml')) {
                            lgLog('INFO', 'Detected Maven project — running mvn clean install')
                            def mvnCmd = fileExists('mvnw') ? './mvnw' : 'mvn'
                            sh "${mvnCmd} clean install -DskipTests"
                        } else if (fileExists('build.gradle') || fileExists('build.gradle.kts')) {
                            lgLog('INFO', 'Detected Gradle project — running gradle build')
                            def gradleCmd = fileExists('gradlew') ? './gradlew' : 'gradle'
                            sh "${gradleCmd} build -x test"
                        } else if (fileExists('index.html')) {
                            lgLog('INFO', 'Detected static site (HTML/CSS/JS) — no build step needed')
                        } else {
                            lgLog('WARN', 'No recognized project type — skipping build')
                        }
                    }
                    lgLog('INFO', 'Build stage complete')
                }
            }
        }

        stage('Security Scan') {
            steps {
                script {
                    def workDir = "/tmp/deployments/${params.DEPLOYMENT_ID}"
                    lgLog('INFO', 'Running Trivy vulnerability scan...')
                    try {
                        def output = sh(
                            script: "trivy fs --exit-code 0 --severity HIGH,CRITICAL --no-progress '${workDir}' 2>&1 | tail -30",
                            returnStdout: true
                        ).trim()
                        lgLog('INFO', output ?: 'No HIGH/CRITICAL vulnerabilities found')
                    } catch (e) {
                        lgLog('WARN', "Trivy scan skipped: ${e.getMessage()}")
                    }
                }
            }
        }

        stage('Code Quality') {
            steps {
                script {
                    def workDir = "/tmp/deployments/${params.DEPLOYMENT_ID}"
                    dir(workDir) {
                        if (fileExists('pom.xml')) {
                            lgLog('INFO', 'Running SonarQube analysis...')
                            def mvnCmd = fileExists('mvnw') ? './mvnw' : 'mvn'
                            try {
                                sh """${mvnCmd} sonar:sonar \
                                    -Dsonar.projectKey=deploy-${params.DEPLOYMENT_ID} \
                                    -Dsonar.projectName='LogGuard Deploy ${params.DEPLOYMENT_ID}' \
                                    -Dsonar.host.url=http://sonarqube:9000 \
                                    -Dsonar.login=admin \
                                    -Dsonar.password=admin \
                                    -DskipTests"""
                                lgLog('INFO', "SonarQube analysis done — http://localhost:9000/dashboard?id=deploy-${params.DEPLOYMENT_ID}")
                            } catch (e) {
                                lgLog('WARN', "SonarQube analysis skipped: ${e.getMessage()}")
                            }
                        } else {
                            lgLog('INFO', 'SonarQube: skipped (not a Maven project)')
                        }
                    }
                }
            }
        }

        stage('Hand off to Runtime') {
            steps {
                script {
                    lgLog('INFO', "Build #${env.BUILD_NUMBER} complete — signalling LogGuard to start application")
                    lgNotifyBuilt(env.BUILD_NUMBER)
                }
            }
        }
    }

    post {
        failure {
            script {
                lgStatus('FAILED', "Pipeline failed — view Jenkins console: http://localhost:8090/job/logguard-deploy/${env.BUILD_NUMBER}/console")
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────

def lgPost(Map data) {
    def msgEscaped = (data.message ?: '').replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ')
    def statusField = data.status ? '"status":"' + data.status + '",' : ''
    def buildField  = data.buildNumber ? '"buildNumber":"' + data.buildNumber + '",' : ''
    def payload = '{"event":"' + data.event + '",' + statusField + buildField + '"level":"' + (data.level ?: 'INFO') + '","message":"' + msgEscaped + '"}'
    writeFile file: '/tmp/lgpayload.json', text: payload
    sh "curl -sf -X POST -H 'Content-Type: application/json' -H 'X-Service-Token: ${params.SERVICE_TOKEN}' --data-binary @/tmp/lgpayload.json '${params.LOGGUARD_URL}/api/deployments/${params.DEPLOYMENT_ID}/callback' || true"
}

def lgLog(String level, String msg) {
    lgPost([event: 'LOG', level: level, message: msg])
}

def lgStatus(String status, String msg) {
    lgPost([event: 'STATUS', status: status, level: 'INFO', message: msg])
}

def lgNotifyBuilt(String buildNum) {
    lgPost([event: 'BUILT', buildNumber: buildNum, level: 'INFO', message: 'Jenkins build complete'])
    // This call must succeed (no || true) — it triggers the backend runtime
    def payload = '{"event":"BUILT","buildNumber":"' + buildNum + '","serviceToken":"' + params.SERVICE_TOKEN + '"}'
    writeFile file: '/tmp/lgbuilt.json', text: payload
    sh "curl -f -X POST -H 'Content-Type: application/json' -H 'X-Service-Token: ${params.SERVICE_TOKEN}' --data-binary @/tmp/lgbuilt.json '${params.LOGGUARD_URL}/api/deployments/${params.DEPLOYMENT_ID}/callback'"
}
