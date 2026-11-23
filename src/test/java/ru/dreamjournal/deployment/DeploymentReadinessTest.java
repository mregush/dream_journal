package ru.dreamjournal.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeploymentReadinessTest {

    @TempDir
    Path directory;

    @Test
    void markerExistsOnlyAfterReadinessAndIsRemovedOnShutdown() throws Exception {
        Path marker = directory.resolve("status/ready");
        var readiness = new DeploymentReadiness(new DeploymentProperties(true, marker));
        readiness.reset();
        assertThat(marker).doesNotExist();

        readiness.ready();
        assertThat(marker).hasContent("ready\n");

        readiness.stopped();
        assertThat(marker).doesNotExist();
    }

    @Test
    void startupClearsAStaleMarker() throws Exception {
        Path marker = directory.resolve("ready");
        Files.writeString(marker, "previous process");

        new DeploymentReadiness(new DeploymentProperties(true, marker)).reset();

        assertThat(marker).doesNotExist();
    }

    @Test
    void relativeMarkerPathsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DeploymentReadiness(new DeploymentProperties(true, Path.of("ready"))));
    }
}
