CREATE TABLE analyses (
    id          BIGSERIAL PRIMARY KEY,
    keyword     TEXT NOT NULL,                 -- lowercased, trimmed
    result      JSONB NOT NULL,                -- full payload (build plan §8)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    fresh_until TIMESTAMPTZ NOT NULL           -- created_at + 24h
);
CREATE INDEX idx_analyses_kw ON analyses (keyword, created_at DESC);

CREATE TABLE search_log (
    id         BIGSERIAL PRIMARY KEY,
    keyword    TEXT NOT NULL,
    ip_hash    TEXT NOT NULL,                  -- sha256(ip + SALT env), never raw IP
    fresh      BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_budget ON search_log (ip_hash, created_at);
CREATE INDEX idx_log_day    ON search_log (created_at) WHERE fresh;
