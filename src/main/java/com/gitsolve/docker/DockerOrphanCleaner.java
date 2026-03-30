package com.gitsolve.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Removes orphaned build containers on startup.
 *
 * Containers are labeled {@code gitsolve=build-env} at creation time.
 * Under normal operation they are removed by {@link DockerBuildEnvironment#close()}
 * via try-with-resources. If the JVM is killed mid-run, those containers are left
 * behind. This cleaner sweeps them on every application startup.
 */
@Component
public class DockerOrphanCleaner {

    private static final Logger log = LoggerFactory.getLogger(DockerOrphanCleaner.class);

    private final DockerClient dockerClient;

    public DockerOrphanCleaner(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanOrphanedContainers() {
        List<Container> orphans;
        try {
            orphans = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of("gitsolve", "build-env"))
                    .exec();
        } catch (Exception e) {
            log.warn("[DockerCleanup] Could not list containers: {}", e.getMessage());
            return;
        }

        if (orphans.isEmpty()) {
            log.debug("[DockerCleanup] No orphaned build containers found");
            return;
        }

        log.info("[DockerCleanup] Found {} orphaned build container(s) — removing", orphans.size());
        for (Container container : orphans) {
            String shortId = container.getId().substring(0, 12);
            try {
                dockerClient.removeContainerCmd(container.getId())
                        .withRemoveVolumes(true)
                        .withForce(true)
                        .exec();
                log.info("[DockerCleanup] Removed orphaned container {}", shortId);
            } catch (Exception e) {
                log.warn("[DockerCleanup] Failed to remove container {}: {}", shortId, e.getMessage());
            }
        }
    }
}
