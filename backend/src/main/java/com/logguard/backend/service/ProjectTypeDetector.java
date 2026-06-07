package com.logguard.backend.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class ProjectTypeDetector {

    public record ProjectCommands(String buildCommand, String startCommand) {}

    public ProjectCommands detect(Path projectDir) {

        // Node.js — only use npm if the project actually has runnable scripts
        if (Files.exists(projectDir.resolve("package.json"))) {
            Path pkgJson = projectDir.resolve("package.json");
            boolean hasBuild = hasScript(pkgJson, "build");
            boolean hasStart = hasScript(pkgJson, "start");

            if (hasStart) {
                String buildCmd = hasBuild ? "npm install && npm run build" : "npm install";
                return new ProjectCommands(buildCmd, "npm start");
            }

            if (hasBuild) {
                // Build-only project (CRA, Vite, etc.) — output lands in build/ or dist/
                // detect() is called AFTER Jenkins has already built, so the dir exists
                String outDir = Files.exists(projectDir.resolve("build/index.html")) ? "build"
                             : Files.exists(projectDir.resolve("dist/index.html"))  ? "dist"
                             : ".";
                return new ProjectCommands(
                        "npm install && npm run build",
                        "python3 -m http.server ${PORT:-8000} --bind 0.0.0.0 --directory " + outDir
                );
            }
            // package.json but no runnable scripts → fall through to static detection
        }

        // Maven
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            String mvn = Files.exists(projectDir.resolve("mvnw")) ? "./mvnw" : "mvn";
            return new ProjectCommands(
                    mvn + " clean install -DskipTests",
                    "java -jar target/*.jar"
            );
        }

        // Gradle
        if (Files.exists(projectDir.resolve("build.gradle"))
                || Files.exists(projectDir.resolve("build.gradle.kts"))) {
            String gradle = Files.exists(projectDir.resolve("gradlew")) ? "./gradlew" : "gradle";
            return new ProjectCommands(
                    gradle + " build -x test",
                    "java -jar build/libs/*.jar"
            );
        }

        // Plain static site — any directory that has an index.html
        if (Files.exists(projectDir.resolve("index.html"))) {
            return new ProjectCommands(
                    "echo 'Static site — no build step needed'",
                    "python3 -m http.server ${PORT:-8000} --bind 0.0.0.0"
            );
        }

        return new ProjectCommands(
                "echo 'No recognized project type — skipping build'",
                "echo 'No start command configured'"
        );
    }

    private boolean hasScript(Path packageJson, String scriptName) {
        try {
            String content = Files.readString(packageJson);
            int scriptsIdx = content.indexOf("\"scripts\"");
            if (scriptsIdx == -1) return false;
            int braceOpen  = content.indexOf('{', scriptsIdx);
            int braceClose = content.indexOf('}', braceOpen);
            if (braceOpen == -1 || braceClose == -1) return false;
            return content.substring(braceOpen, braceClose).contains("\"" + scriptName + "\"");
        } catch (IOException e) {
            return false;
        }
    }
}
