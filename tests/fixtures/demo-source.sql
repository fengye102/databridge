CREATE TABLE IF NOT EXISTS demo_items (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    amount DECIMAL(10,2),
    payload JSON,
    observed_at DATETIME(3)
);

INSERT IGNORE INTO demo_items(id, name, amount, payload, observed_at) VALUES
    (1, '样本 A', 12.50, '{"region":"east","count":1}', '2026-10-19 10:00:00.123'),
    (2, '样本 B', 7.00, '{"region":"west","count":2}', '2026-10-19 10:00:01.000'),
    (3, '样本 C', NULL, NULL, NULL);
