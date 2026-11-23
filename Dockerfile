# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 journal \
    && useradd --uid 10001 --gid journal --home-dir /app --shell /usr/sbin/nologin journal
WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/build/libs/*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
