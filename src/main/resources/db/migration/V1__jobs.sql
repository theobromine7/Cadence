CREATE TABLE jobs (
 id UUID PRIMARY KEY, payload TEXT NOT NULL, priority INT NOT NULL CHECK (priority BETWEEN 0 AND 9),
 status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 run_at TIMESTAMPTZ NOT NULL, interval_seconds BIGINT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL,
 lease_token UUID, lease_until TIMESTAMPTZ, last_error TEXT,
 completed_runs BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX jobs_due ON jobs (run_at, priority DESC) WHERE status = 'PENDING';
CREATE INDEX jobs_expired ON jobs (lease_until) WHERE status = 'RUNNING';
