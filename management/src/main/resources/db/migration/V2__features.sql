-- Roles: OWNER manages everything, ADMIN manages APIs/keys and invites members, MEMBER is read-only.
ALTER TABLE memberships ADD CONSTRAINT chk_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'));
CREATE INDEX idx_memberships_org ON memberships(organization_id);

-- Invitations: the token is shown once to the inviter; only its SHA-256 is stored.
CREATE TABLE invitations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    invited_by UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_invitations_org ON invitations(organization_id);

-- Per-key limits, scopes, expiry and rotation grace period.
ALTER TABLE api_keys
    ADD COLUMN name VARCHAR(100) NOT NULL DEFAULT 'default',
    ADD COLUMN requests_per_minute INTEGER CHECK (requests_per_minute BETWEEN 1 AND 100000),
    ADD COLUMN daily_quota BIGINT NOT NULL DEFAULT 0 CHECK (daily_quota >= 0),
    ADD COLUMN monthly_quota BIGINT NOT NULL DEFAULT 0 CHECK (monthly_quota >= 0),
    ADD COLUMN allowed_methods VARCHAR(100) NOT NULL DEFAULT 'GET,POST,PUT,PATCH,DELETE,HEAD,OPTIONS',
    ADD COLUMN path_prefix VARCHAR(200) NOT NULL DEFAULT '/',
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN previous_key_hash VARCHAR(64),
    ADD COLUMN previous_expires_at TIMESTAMPTZ;
CREATE INDEX idx_api_keys_previous_hash ON api_keys(previous_key_hash) WHERE previous_key_hash IS NOT NULL;

-- Hourly roll-up written by the Redis Streams worker (no FKs: history outlives deleted keys/APIs).
CREATE TABLE usage_hourly (
    org_id UUID NOT NULL,
    api_id UUID NOT NULL,
    key_id UUID NOT NULL,
    bucket TIMESTAMPTZ NOT NULL,
    requests BIGINT NOT NULL DEFAULT 0,
    success BIGINT NOT NULL DEFAULT 0,
    client_errors BIGINT NOT NULL DEFAULT 0,
    server_errors BIGINT NOT NULL DEFAULT 0,
    rate_limited BIGINT NOT NULL DEFAULT 0,
    quota_exceeded BIGINT NOT NULL DEFAULT 0,
    scope_denied BIGINT NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT NOT NULL DEFAULT 0,
    latency_max_ms INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (key_id, bucket)
);
CREATE INDEX idx_usage_hourly_org_bucket ON usage_hourly(org_id, bucket);
