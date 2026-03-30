package com.gitsolve.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of active SSE connections.
 *
 * Each browser tab gets its own SseEmitter keyed by a generated client ID.
 * The orchestrator calls broadcast() after each issue is analysed —
 * the event is pushed to all connected clients immediately.
 *
 * Thread-safe: ConcurrentHashMap + synchronized broadcast loop.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);
    private static final long   SSE_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong            idSeq    = new AtomicLong();

    /**
     * Creates and registers a new SSE emitter for an incoming client connection.
     * Removes itself on completion/timeout/error so dead connections don't accumulate.
     */
    public SseEmitter register() {
        long id = idSeq.incrementAndGet();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(id, emitter);

        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(()    -> emitters.remove(id));
        emitter.onError(e     -> emitters.remove(id));

        log.debug("SSE: client {} connected ({} total)", id, emitters.size());
        return emitter;
    }

    /**
     * Broadcasts an event to all connected clients.
     * Dead emitters are removed on send failure.
     *
     * @param eventName  SSE event name (e.g. "issue-analysed", "run-complete")
     * @param jsonData   JSON string payload
     */
    public void broadcast(String eventName, String jsonData) {
        if (emitters.isEmpty()) return;
        log.debug("SSE: broadcasting '{}' to {} client(s)", eventName, emitters.size());

        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(jsonData));
            } catch (IOException e) {
                log.debug("SSE: client {} disconnected — removing", id);
                emitter.completeWithError(e);
                emitters.remove(id);
            }
        });
    }

    public int connectedCount() {
        return emitters.size();
    }
}
