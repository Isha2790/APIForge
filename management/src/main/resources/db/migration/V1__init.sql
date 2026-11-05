CREATE TABLE users (id UUID PRIMARY KEY, email VARCHAR(255) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE organizations (id UUID PRIMARY KEY, name VARCHAR(150) NOT NULL, owner_id UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE memberships (user_id UUID NOT NULL REFERENCES users(id), organization_id UUID NOT NULL REFERENCES organizations(id), role VARCHAR(30) NOT NULL, PRIMARY KEY(user_id, organization_id));
CREATE TABLE managed_apis (id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), name VARCHAR(150) NOT NULL, upstream_url VARCHAR(1000) NOT NULL, requests_per_minute INTEGER NOT NULL CHECK(requests_per_minute BETWEEN 1 AND 100000), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE INDEX idx_managed_apis_org ON managed_apis(organization_id);
CREATE TABLE api_keys (id UUID PRIMARY KEY, api_id UUID NOT NULL REFERENCES managed_apis(id) ON DELETE CASCADE, prefix VARCHAR(16) NOT NULL, key_hash VARCHAR(64) NOT NULL UNIQUE, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE INDEX idx_api_keys_api ON api_keys(api_id);
