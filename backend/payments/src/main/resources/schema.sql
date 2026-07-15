-- One row per gateway decision, AUTHORIZED and DECLINED alike: declined rows
-- feed dashboards/logs (contract §2.4 note) and the table is what the future
-- idempotency task will return existing transactions from.
-- Level 2 dedup (contract §3.2), keyed on the order_id. Only gateway-derived
-- decisions get a row: a circuit_breaker_open decline is not a decision, and
-- memoising it would pin a transient outage onto the order.
CREATE TABLE IF NOT EXISTS processed_requests (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    transaction_id  VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transactions (
    id             BIGSERIAL     PRIMARY KEY,
    transaction_id VARCHAR(64)   NOT NULL UNIQUE,
    order_id       VARCHAR(64)   NOT NULL,
    user_id        VARCHAR(64)   NOT NULL,
    amount         NUMERIC(10,2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    status         VARCHAR(16)   NOT NULL CHECK (status IN ('AUTHORIZED', 'DECLINED')),
    reason         VARCHAR(64),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
