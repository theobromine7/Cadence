package dev.cadence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobStore {
  final JdbcTemplate db;

  public JobStore(JdbcTemplate db) {
    this.db = db;
  }

  public UUID create(CreateJob r) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO jobs(id,payload,priority,run_at,interval_seconds,max_attempts) VALUES"
            + " (?,?,?,?,?,?)",
        id,
        r.payload(),
        r.priority(),
        Timestamp.from(r.runAt() == null ? Instant.now() : r.runAt()),
        r.intervalSeconds(),
        r.maxAttempts());
    return id;
  }

  public List<Map<String, Object>> list() {
    return db.queryForList("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 100");
  }

  public Map<String, Object> get(UUID id) {
    return db.queryForMap("SELECT * FROM jobs WHERE id=?", id);
  }

  public List<Map<String, Object>> due() {
    return db.queryForList(
        "SELECT id,priority FROM jobs WHERE status='PENDING' AND run_at<=now() ORDER BY priority"
            + " DESC,run_at LIMIT 1000");
  }

  public boolean claim(UUID id, UUID token) {
    return db.update(
            "UPDATE jobs SET"
                + " status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=now()+interval"
                + " '15 seconds' WHERE id=? AND status='PENDING' AND run_at<=now()",
            token,
            id)
        == 1;
  }

  public boolean heartbeat(UUID id, UUID token) {
    return db.update(
            "UPDATE jobs SET lease_until=now()+interval '15 seconds' WHERE id=? AND lease_token=?"
                + " AND status='RUNNING' AND lease_until>now()",
            id,
            token)
        == 1;
  }

  public boolean finish(UUID id, UUID token) {
    return db.update(
            "UPDATE jobs SET status=CASE WHEN interval_seconds>0 THEN 'PENDING' ELSE 'SUCCEEDED'"
                + " END,run_at=CASE WHEN interval_seconds>0 THEN now()+interval_seconds*interval '1"
                + " second' ELSE run_at END,attempts=CASE WHEN interval_seconds>0 THEN 0 ELSE"
                + " attempts"
                + " END,completed_runs=completed_runs+1,last_error=NULL,lease_token=NULL,lease_until=NULL"
                + " WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>now()",
            id,
            token)
        == 1;
  }

  public void fail(UUID id, UUID token, String error) {
    db.update(
        "UPDATE jobs SET status=CASE WHEN attempts>=max_attempts THEN 'FAILED' ELSE 'PENDING'"
            + " END,run_at=now()+least(300,power(2,attempts))*interval '1"
            + " second',last_error=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?"
            + " AND status='RUNNING' AND lease_until>now()",
        error,
        id,
        token);
  }

  public void recover() {
    db.update(
        "UPDATE jobs SET status=CASE WHEN attempts>=max_attempts THEN 'FAILED' ELSE 'PENDING'"
            + " END,run_at=now()+least(300,power(2,attempts))*interval '1"
            + " second',last_error='Worker heartbeat expired',lease_token=NULL,lease_until=NULL"
            + " WHERE status='RUNNING' AND lease_until<=now()");
  }

  public boolean cancel(UUID id) {
    return db.update("UPDATE jobs SET status='CANCELLED' WHERE id=? AND status='PENDING'", id) == 1;
  }
}
