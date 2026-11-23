package ru.dreamjournal.deployment;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Startup readiness for a non-HTTP worker; does not probe subsequent Telegram connectivity. */
@Component
@ConditionalOnProperty(prefix = "deployment", name = "readiness-enabled", havingValue = "true")
public class DeploymentReadiness {

    private static final Logger log = LoggerFactory.getLogger(DeploymentReadiness.class);
    private final Path file;

    public DeploymentReadiness(DeploymentProperties properties) {
        this.file = properties.readinessFile();
        if (file == null || !file.isAbsolute()) {
            throw new IllegalArgumentException("Deployment readiness file must be an absolute path");
        }
    }

    @PostConstruct
    public void reset() throws IOException {
        Files.deleteIfExists(file);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ready\n");
        log.info("Application startup completed; deployment readiness marker created");
    }

    @PreDestroy
    public void stopped() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not remove deployment readiness marker ({})", exception.getClass().getSimpleName());
        }
    }
}
