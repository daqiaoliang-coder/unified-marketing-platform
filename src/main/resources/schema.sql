CREATE TABLE IF NOT EXISTS platform_lock (
    id INTEGER PRIMARY KEY
);
MERGE INTO platform_lock KEY(id) VALUES (1);

CREATE TABLE IF NOT EXISTS promotions (
    id VARCHAR(100) PRIMARY KEY,
    payload CLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS resources (
    id VARCHAR(100) PRIMARY KEY,
    total BIGINT NOT NULL CHECK (total >= 0),
    available BIGINT NOT NULL CHECK (available >= 0),
    reserved BIGINT NOT NULL CHECK (reserved >= 0),
    consumed BIGINT NOT NULL CHECK (consumed >= 0),
    CHECK (total = available + reserved + consumed)
);

CREATE TABLE IF NOT EXISTS snapshots (
    transaction_id VARCHAR(100) PRIMARY KEY,
    cart_id VARCHAR(100) UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('TRYING', 'CONFIRMED', 'CANCELLED')),
    request_json CLOB,
    quote_json CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS order_events (
    event_id VARCHAR(100) PRIMARY KEY,
    transaction_id VARCHAR(100) NOT NULL REFERENCES snapshots(transaction_id),
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('PAID', 'CANCELLED')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS events_due ON order_events(status, next_attempt_at);

CREATE TABLE IF NOT EXISTS reward_grants (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(100) NOT NULL REFERENCES snapshots(transaction_id),
    order_id VARCHAR(100) NOT NULL,
    promotion_id VARCHAR(100) NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('COINS', 'GIFT')),
    reference_code VARCHAR(100) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    UNIQUE (transaction_id, order_id, promotion_id, kind)
);
