FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

# Build tools needed by the deployment worker
RUN apk add --no-cache nodejs npm git maven python3 make g++ \
    && ln -sf python3 /usr/bin/python

COPY target/backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]