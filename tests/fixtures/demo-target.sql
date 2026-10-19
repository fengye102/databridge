CREATE SCHEMA IF NOT EXISTS business;

CREATE TABLE IF NOT EXISTS business.demo_items (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    amount NUMERIC(10,2),
    payload JSONB,
    observed_at TIMESTAMP(3)
);
