package ru.dreamjournal.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("deployment")
public record DeploymentProperties(boolean readinessEnabled, Path readinessFile) {
}
