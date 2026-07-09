-- Seed data. Idempotent (ON CONFLICT DO NOTHING) so it can run on every startup
-- via spring.sql.init without duplicating rows. Catalog is read-only and
-- tolerant of staleness, so these availability figures are a nominal snapshot,
-- not live inventory (Inventory owns the authoritative seat counts).

INSERT INTO products (train_id, origin, destination, departure) VALUES
    ('TR-101', 'Turin', 'Milan',    '2026-07-15T08:30:00Z'),
    ('TR-205', 'Rome',  'Florence', '2026-07-15T09:00:00Z'),
    ('TR-330', 'Milan', 'Venice',   '2026-07-16T07:45:00Z')
ON CONFLICT (train_id) DO NOTHING;

INSERT INTO seat_classes (train_id, seat_class, price, currency, available) VALUES
    ('TR-101', 'standard', 25.00, 'EUR', 42),
    ('TR-101', 'business', 45.50, 'EUR', 8),
    ('TR-205', 'standard', 19.90, 'EUR', 55),
    ('TR-205', 'business', 39.00, 'EUR', 10),
    ('TR-330', 'standard', 22.50, 'EUR', 30),
    ('TR-330', 'business', 41.00, 'EUR', 6)
ON CONFLICT (train_id, seat_class) DO NOTHING;
