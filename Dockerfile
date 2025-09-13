# Build stage
FROM eclipse-temurin:21-jdk-alpine as builder
WORKDIR /app

# Copy gradle files first to cache dependencies
COPY gradle gradle/
COPY gradlew build.gradle settings.gradle ./
RUN ./gradlew --version

# Copy source code and build
COPY src src/
COPY bin bin/

# Build argument for profile selection
ARG PROFILE=streamable
RUN ./gradlew clean build -Pprofile=${PROFILE} -Pversion=0.0.1-SNAPSHOT

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Create data directory for H2 database
RUN mkdir -p /app/data && chmod 777 /app/data

# Environment variables
ENV APP_NAME=todoapp \
    APP_VERSION=0.0.1-SNAPSHOT \
    SPRING_PROFILES_ACTIVE=streamable \
    SERVER_PORT=8080

# Copy the built jar from builder stage
COPY --from=builder /app/build/libs/${APP_NAME}_${SPRING_PROFILES_ACTIVE}-${APP_VERSION}.jar app.jar

# Create volume for H2 database persistence
VOLUME /app/data

# Expose the application port
EXPOSE 8080

# Health check using curl (matches the healthcheck in docker-compose)
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
