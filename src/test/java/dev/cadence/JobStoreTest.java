package dev.cadence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class JobStoreTest {
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
  static JobStore store;

  @BeforeAll
  static void setup() {
    var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__jobs.sql")).execute(ds);
    store = new JobStore(new JdbcTemplate(ds));
  }

  @BeforeEach
  void clear() {
    store.db.update("DELETE FROM jobs");
  }

  UUID job(long interval, int max) {
    return store.create(new CreateJob("hello", 5, Instant.now().minusSeconds(1), interval, max));
  }

  @Test
  void competingWorkersHaveOneOwner() throws Exception {
    UUID id = job(0, 3);
    var pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < 24; i++) calls.add(() -> store.claim(id, UUID.randomUUID()));
      int winners = 0;
      for (var result : pool.invokeAll(calls)) if (result.get()) winners++;
      assertEquals(1, winners);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void expiredOwnerCannotRenewOrComplete() {
    UUID id = job(0, 3), old = UUID.randomUUID(), fresh = UUID.randomUUID();
    assertTrue(store.claim(id, old));
    store.db.update("UPDATE jobs SET lease_until=now()-interval '1 second' WHERE id=?", id);
    assertFalse(store.heartbeat(id, old));
    assertFalse(store.finish(id, old));
    store.recover();
    store.db.update("UPDATE jobs SET run_at=now()-interval '1 second' WHERE id=?", id);
    assertTrue(store.claim(id, fresh));
    assertFalse(store.finish(id, old));
    assertTrue(store.finish(id, fresh));
  }

  @Test
  void retriesStopAtLimit() {
    UUID id = job(0, 2);
    for (int i = 0; i < 2; i++) {
      UUID token = UUID.randomUUID();
      assertTrue(store.claim(id, token));
      store.fail(id, token, "test");
      store.db.update("UPDATE jobs SET run_at=now()-interval '1 second' WHERE id=?", id);
    }
    assertEquals("FAILED", store.get(id).get("status"));
    assertFalse(store.claim(id, UUID.randomUUID()));
  }

  @Test
  void recurrenceResetsAttemptsAndSchedulesNextRun() {
    UUID id = job(30, 2), token = UUID.randomUUID();
    assertTrue(store.claim(id, token));
    assertTrue(store.finish(id, token));
    var row = store.get(id);
    assertEquals("PENDING", row.get("status"));
    assertEquals(0, row.get("attempts"));
    assertEquals(1L, row.get("completed_runs"));
    assertFalse(store.claim(id, UUID.randomUUID()));
  }

  @Test
  void delayedAndCancelledJobsCannotBeClaimed() {
    UUID id = store.create(new CreateJob("hello", 1, Instant.now().plusSeconds(60), 0, 2));
    assertFalse(store.claim(id, UUID.randomUUID()));
    assertTrue(store.cancel(id));
    assertFalse(store.cancel(id));
  }

  @Test
  void crashedWorkerExhaustsFinalAttempt() {
    UUID id = job(0, 1), token = UUID.randomUUID();
    assertTrue(store.claim(id, token));
    store.db.update("UPDATE jobs SET lease_until=now()-interval '1 second' WHERE id=?", id);
    store.recover();
    assertEquals("FAILED", store.get(id).get("status"));
  }
}
