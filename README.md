# Cadence

A distributed job scheduler MVP built with Java 21, Spring Boot, Redis, PostgreSQL, and Docker.

## Run

```sh
docker compose up --build -d --scale worker=2
curl http://localhost:8080/actuator/health
curl -X POST http://localhost:8080/api/jobs -H 'Content-Type: application/json' -d '{"payload":"hello from Cadence","priority":5,"intervalSeconds":0,"maxAttempts":3}'
curl http://localhost:8080/api/jobs
```

Use PowerShell's `Invoke-RestMethod` on Windows:

```powershell
$job = Invoke-RestMethod http://localhost:8080/api/jobs -Method Post -ContentType 'application/json' -Body '{"payload":"hello","priority":5,"intervalSeconds":0,"maxAttempts":3}'
Invoke-RestMethod "http://localhost:8080/api/jobs/$($job.id)"
```

## API

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/jobs` | Create a job; returns 201 and Location |
| GET | `/api/jobs` | Latest 100 jobs |
| GET | `/api/jobs/{id}` | Job state, attempt count, lease, and last error |
| POST | `/api/jobs/{id}/cancel` | Cancel a pending job; running/terminal jobs return 409 |
| GET | `/actuator/health` | Database and Redis health |

Creation fields: `payload` (required, 1–10,000 characters), `priority` (0–9, higher first), `runAt` (optional ISO UTC timestamp, defaults to now), `intervalSeconds` (0 for one-shot; positive for fixed delay after successful completion), `maxAttempts` (required, 1–20, includes initial attempt). Example delayed recurring job:

```json
{"payload":"hello","priority":9,"runAt":"2027-01-01T00:00:00Z","intervalSeconds":60,"maxAttempts":3}
```

Built-in handlers: ordinary text logs successfully; `sleep:20000` simulates a 20-second task; `fail:demo` deliberately fails to demonstrate retries. Replace the demo handler in `Worker.run` with business handlers. Arbitrary shell commands and remote URLs are not executed.

## Scheduling and ownership

1. PostgreSQL stores jobs durably. Flyway creates the schema on startup.
2. Every worker reconciles up to 1,000 due pending jobs into a Redis sorted set every second. Higher priority gets a lower score; equal-priority ordering is unspecified. Priority is best effort within the reconciliation batch, and running jobs are not preempted.
3. Redis atomically pops a queue entry. A conditional PostgreSQL update grants one owner a UUID lease token and increments attempts. Duplicate or stale Redis entries cannot grant another concurrent valid lease.
4. Four execution slots per worker run jobs. A separate scheduler renews active leases every 3 seconds; leases expire after 15 seconds using database time.
5. Completion/failure requires the current, unexpired lease token. Failed jobs retry with exponential delays of 2, 4, 8 seconds up to 300 seconds. Exhausted attempts become FAILED.
6. Expired leases follow the same retry policy. Redis loss or a crash between popping and claiming is repaired by the next database reconciliation. PostgreSQL is the source of truth.
7. Recurring jobs schedule their next run after success and reset their attempt count. Exhausted recurring jobs stop in FAILED. Missed intervals are not replayed.

**Delivery is at least once.** Conditional claims and lease tokens prevent competing valid owners and reject stale completion. A paused or partitioned worker can still perform side effects after its lease expires. Real handlers must use an idempotency key (job ID plus recurring run number) or downstream fencing/transactions. This MVP does not promise exactly-once external effects.

## Development and verification

Install Maven 3.9+ and Java 21. Docker must be running for integration tests.

```sh
mvn verify
docker compose up -d postgres redis
mvn spring-boot:run
```

Tests use a real PostgreSQL container: concurrent claims, expired leases, stale completion, retries, recurrence, cancellation, and crash exhaustion. CI runs these on GitHub Actions. Worker queue tests also check priority scores and reconciliation behavior. Set `WORKER_ENABLED=false` for API-only processes. Set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `REDIS_HOST`, and `REDIS_PORT` to override local defaults.

## Scope

This is a local-development MVP. Compose binds infrastructure and the API to localhost and uses demo credentials. Add authentication, authorization, TLS, secrets management, durable attempt history, retention, rate limits, production monitoring, and handler isolation before exposing it publicly. Recurrence is fixed delay; cron/time-zone scheduling and dependency graphs are out of scope. Redis queues are disposable and rebuild from PostgreSQL; PostgreSQL uses a persistent Docker volume. `docker compose down` retains that volume.

Infrastructure uses host ports 55432 (PostgreSQL) and 56379 (Redis) to avoid common local conflicts. Run the API smoke checks with `powershell -File scripts/smoke.ps1` after Compose is healthy.
