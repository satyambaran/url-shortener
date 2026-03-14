# Multi-stage Dockerfile for Spring Boot (Maven)

# Builder: build the fat JAR
FROM maven:3.9.4-eclipse-temurin-17 as builder
WORKDIR /workspace
COPY pom.xml mvnw* ./
COPY .mvn .mvn
COPY src ./src
# Use Maven to build the project (adjust profiles / flags as needed)
RUN mvn -B -DskipTests package

# Runtime: minimal JRE
FROM eclipse-temurin:17-jre-jammy
ARG JAR_FILE=target/*.jar
WORKDIR /app
COPY --from=builder /workspace/target/*.jar app.jar
EXPOSE 8080
# Healthcheck - readiness endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 CMD curl -f http://localhost:8080/health/ready || exit 1

# Tuned JVM defaults (adjust for environment)
ENTRYPOINT ["java", "-Xms512m", "-Xmx1024m", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
