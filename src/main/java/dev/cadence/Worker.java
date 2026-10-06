package dev.cadence;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cadence.worker.enabled", havingValue = "true", matchIfMissing = true)
public class Worker {
  private static final Logger log = LoggerFactory.getLogger(Worker.class);
  private final JobStore store;
  private final StringRedisTemplate redis;
  private final ExecutorService executor = Executors.newFixedThreadPool(4);
  private final ConcurrentMap<UUID, UUID> active = new ConcurrentHashMap<>();

  public Worker(JobStore store, StringRedisTemplate redis) {
    this.store = store;
    this.redis = redis;
  }

  @Scheduled(fixedDelay = 1000)
  public void dispatch() {
    store.recover();
    // Reconciliation makes a lost Redis entry recoverable without a dual-write transaction.
    for (var job : store.due())
      redis
          .opsForZSet()
          .add(
              "cadence:ready",
              job.get("id").toString(),
              -((Number) job.get("priority")).doubleValue());
  }

  @Scheduled(fixedDelay = 250)
  public void poll() {
    while (active.size() < 4) {
      var entry = redis.opsForZSet().popMin("cadence:ready");
      if (entry == null) return;
      UUID id = UUID.fromString(entry.getValue()), token = UUID.randomUUID();
      if (!store.claim(id, token)) continue;
      active.put(id, token);
      executor.submit(() -> run(id, token));
    }
  }

  @Scheduled(fixedDelay = 3000)
  public void heartbeat() {
    active.forEach(
        (id, token) -> {
          if (!store.heartbeat(id, token)) log.warn("Lease lost for {}", id);
        });
  }

  private void run(UUID id, UUID token) {
    try {
      String payload = (String) store.get(id).get("payload");
      // Safe built-in demo handler. Register real business handlers here.
      if (payload.startsWith("fail:")) throw new IllegalStateException("Requested demo failure");
      if (payload.startsWith("sleep:")) {
        long millis = Long.parseLong(payload.substring(6));
        if (millis < 0 || millis > 60000)
          throw new IllegalArgumentException("Sleep must be 0..60000 ms");
        Thread.sleep(millis);
      }
      log.info("Job {} token {} payload {}", id, token, payload);
      if (!store.finish(id, token)) log.warn("Completion rejected for stale lease {}", id);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      try {
        store.fail(id, token, e.getClass().getSimpleName() + ": " + e.getMessage());
      } catch (Exception failure) {
        log.error("Failure persistence unavailable; lease recovery will retry {}", id, failure);
      }
    } finally {
      active.remove(id, token);
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
