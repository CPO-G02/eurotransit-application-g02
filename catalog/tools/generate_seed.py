#!/usr/bin/env python3
"""
Deterministic generator for Catalog's seed data (data.sql).

This is a DEV-TIME tool, not part of the service runtime. It emits a static,
idempotent data.sql to stdout; the application keeps loading that file at
startup via spring.sql.init.mode=always, exactly as before. The runtime data
lifecycle is unchanged — only the *volume* of the committed seed grows.

Usage:
    python3 catalog/tools/generate_seed.py > catalog/src/main/resources/data.sql

Determinism: given the same START_DATE the output is byte-for-byte identical
(seeded PRNG, no wall-clock reads), so regenerating produces a clean diff.
To refresh the 14-day departure window, bump START_DATE and regenerate; note
that the train_id encodes the date, so a new window inserts a new set of rows
rather than updating the old ones (ON CONFLICT DO NOTHING). For a clean state,
recreate the catalog-db.
"""

import random
from datetime import date, timedelta

# Generation date. The departure window is [START_DATE, START_DATE + DAYS).
# Kept as an explicit constant (not date.today()) so the committed data.sql is
# reproducible regardless of when the script is run.
START_DATE = date(2026, 7, 10)
DAYS = 14

CURRENCY = "EUR"
# business fare = standard * this factor (then per-run jitter).
BUSINESS_FACTOR = 1.8

# City name -> 3-letter code used inside train_id.
CITIES = {
    "Torino": "TRN",
    "Milano": "MIL",
    "Roma": "ROM",
    "Napoli": "NAP",
    "Firenze": "FIR",
    "Bologna": "BOL",
    "Venezia": "VEN",
    "Genova": "GEN",
    "Verona": "VER",
    "Bari": "BAR",
    "Palermo": "PAL",
    "Bergamo": "BGO",
}

# Hub-based network: Milano and Roma are the hubs with the most connections;
# the other cities hang mostly off the hubs, with a couple of non-hub links.
# Each entry is an undirected city pair with a plausible base standard fare (EUR);
# both travel directions are generated from it.
ROUTES = [
    # Milano hub
    ("Milano", "Roma", 55.0),
    ("Milano", "Torino", 20.0),
    ("Milano", "Napoli", 78.0),
    ("Milano", "Venezia", 32.0),
    ("Milano", "Genova", 24.0),
    ("Milano", "Bologna", 27.0),
    ("Milano", "Bergamo", 12.0),
    # Roma hub
    ("Roma", "Napoli", 26.0),
    ("Roma", "Firenze", 38.0),
    ("Roma", "Bari", 52.0),
    ("Roma", "Palermo", 95.0),
    # Non-hub link
    ("Verona", "Venezia", 14.0),
]

# Departure slots: (hour, minute, demand_multiplier). Peak commuter slots
# (morning / evening) cost a bit more; midday is cheaper.
SLOTS_4 = [
    (7, 0, 1.12),   # morning
    (12, 30, 0.95),  # midday
    (16, 0, 1.00),  # afternoon
    (19, 30, 1.15),  # evening
]
# 3-slot routes skip the midday run.
SLOTS_3 = [SLOTS_4[0], SLOTS_4[2], SLOTS_4[3]]

# Busier corridors get 4 daily runs; the rest get 3.
FOUR_SLOT_PAIRS = {
    ("Milano", "Roma"),
    ("Milano", "Torino"),
    ("Roma", "Napoli"),
    ("Milano", "Bologna"),
}


def rng_for(*parts):
    """Stable per-key PRNG so every value is reproducible across runs."""
    return random.Random("|".join(str(p) for p in parts))


def slots_for(origin, destination):
    key = (origin, destination)
    key_rev = (destination, origin)
    if key in FOUR_SLOT_PAIRS or key_rev in FOUR_SLOT_PAIRS:
        return SLOTS_4
    return SLOTS_3


def directional_routes():
    """Expand each undirected pair into both travel directions."""
    for a, b, fare in ROUTES:
        yield (a, b, fare)
        yield (b, a, fare)


def build_runs():
    products = []       # (train_id, origin, destination, departure_iso)
    seat_classes = []   # (train_id, seat_class, price, currency, available)

    for origin, destination, base_fare in directional_routes():
        o_code = CITIES[origin]
        d_code = CITIES[destination]
        slots = slots_for(origin, destination)

        # Pick one day on which this route runs low on seats, so low-availability
        # examples are spread across many different routes (demo sold-out cases).
        # The last slot of the day on that date is the sold-out run.
        low_day = rng_for("lowday", origin, destination).randrange(DAYS)
        low_hour, low_minute, _ = slots[-1]

        for day in range(DAYS):
            departure_date = START_DATE + timedelta(days=day)
            for hour, minute, demand in slots:
                r = rng_for(origin, destination, day, hour, minute)

                # Small deterministic jitter so times aren't all identical.
                jitter_min = r.randrange(0, 26)
                dep_h = hour
                dep_m = minute + jitter_min
                dep_h += dep_m // 60
                dep_m = dep_m % 60

                train_id = (
                    f"TR-{o_code}-{d_code}-"
                    f"{departure_date.strftime('%Y%m%d')}-{dep_h:02d}{dep_m:02d}"
                )
                departure_iso = (
                    f"{departure_date.isoformat()}T{dep_h:02d}:{dep_m:02d}:00Z"
                )
                products.append((train_id, origin, destination, departure_iso))

                # Prices: base * demand multiplier * +-3% per-run jitter.
                price_jitter = 1.0 + r.uniform(-0.03, 0.03)
                std_price = round(base_fare * demand * price_jitter, 2)
                bus_price = round(
                    std_price * BUSINESS_FACTOR * (1.0 + r.uniform(-0.03, 0.03)), 2
                )

                if day == low_day and hour == low_hour and minute == low_minute:
                    # Sold-out-ish: single-digit availability on this run.
                    std_avail = rng_for("lowstd", train_id).randint(3, 9)
                    bus_avail = rng_for("lowbus", train_id).randint(1, 6)
                else:
                    std_avail = r.randint(25, 90)
                    bus_avail = r.randint(6, 24)

                seat_classes.append(
                    (train_id, "standard", std_price, CURRENCY, std_avail)
                )
                seat_classes.append(
                    (train_id, "business", bus_price, CURRENCY, bus_avail)
                )

    return products, seat_classes


HEADER = """\
-- Seed data for Catalog (catalog-db). GENERATED FILE — do not edit by hand.
-- Regenerate with: python3 catalog/tools/generate_seed.py > catalog/src/main/resources/data.sql
--
-- Idempotent (ON CONFLICT DO NOTHING) so it can run on every startup via
-- spring.sql.init without duplicating rows. Catalog is read-only and tolerant
-- of staleness, so these `available` figures are a nominal snapshot for
-- display/UX only. They are NEVER decremented here: Inventory owns the
-- authoritative seat counts (inventory-db `seats` table) and is the only place
-- reservations happen.
--
-- A handful of runs across different routes are seeded with single-digit
-- availability to make sold-out / low-availability demo scenarios plausible.
-- This does NOT drive the seat concurrency test (10 concurrent requests / N
-- seats): that number is defined by the inventory-db seed, added in the
-- \"Implement Inventory POST /reserve\" task. If the two ever diverge,
-- inventory-db wins.
--
-- Departure window: {start} .. {end} (14 days). Dates are frozen at generation
-- time; bump START_DATE in the generator and regenerate to refresh the window.\
"""


def sql_str(value):
    return "'" + str(value).replace("'", "''") + "'"


def emit(products, seat_classes):
    end = START_DATE + timedelta(days=DAYS - 1)
    out = [HEADER.format(start=START_DATE.isoformat(), end=end.isoformat()), ""]

    out.append("INSERT INTO products (train_id, origin, destination, departure) VALUES")
    rows = [
        f"    ({sql_str(t)}, {sql_str(o)}, {sql_str(d)}, {sql_str(dep)})"
        for (t, o, d, dep) in products
    ]
    out.append(",\n".join(rows))
    out.append("ON CONFLICT (train_id) DO NOTHING;")
    out.append("")

    out.append(
        "INSERT INTO seat_classes (train_id, seat_class, price, currency, available) VALUES"
    )
    rows = [
        f"    ({sql_str(t)}, {sql_str(c)}, {p:.2f}, {sql_str(cur)}, {a})"
        for (t, c, p, cur, a) in seat_classes
    ]
    out.append(",\n".join(rows))
    out.append("ON CONFLICT (train_id, seat_class) DO NOTHING;")
    out.append("")
    return "\n".join(out)


def main():
    products, seat_classes = build_runs()
    print(emit(products, seat_classes))


if __name__ == "__main__":
    main()
