#!/usr/bin/env python3
"""
Deterministic generator for the EuroTransit seed data.

Shared source of truth for two services so their train_ids stay identical by
construction (no drift):
  --target=catalog   -> Catalog's data.sql   (products + seat_classes, display)
  --target=inventory -> Inventory's data.sql (seats, authoritative counts)

This is a DEV-TIME tool, not part of any service runtime. It emits a static,
idempotent data.sql to stdout; each application keeps loading that file at
startup via spring.sql.init.mode=always, exactly as before. The runtime data
lifecycle is unchanged — only the *volume* of the committed seed grows.

Usage:
    python3 tools/generate_seed.py --target=catalog   > catalog/src/main/resources/data.sql
    python3 tools/generate_seed.py --target=inventory > inventory/src/main/resources/data.sql

Determinism: given the same START_DATE the output is byte-for-byte identical
(seeded PRNG, no wall-clock reads), so regenerating produces a clean diff.
To refresh the 14-day departure window, bump START_DATE and regenerate; note
that the train_id encodes the date, so a new window inserts a new set of rows
rather than updating the old ones (ON CONFLICT DO NOTHING). For a clean state,
recreate the databases.
"""

import argparse
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

# Inventory seed size: how many of Catalog's DAYS of runs are made reservable.
# Was 1 (only START_DATE) - fine when the demo was actually run near
# START_DATE, but the frontend blocks selecting past departure dates, so once
# "today" moves past START_DATE, every train a real user can actually pick is
# outside Inventory's seed and always fails "insufficient seats". Matching the
# full Catalog window removes that expiry entirely. Confirmed live 2026-07-16:
# START_DATE (07-10) had already passed, so no selectable train was bookable.
INVENTORY_DAYS = DAYS

# The one run whose seat_class is pinned to a known-low authoritative count, so
# the seat concurrency test ("10 concurrent requests / N seats") has a
# deterministic target. Resolved to a REAL catalog run (same train_id) at emit
# time — the earliest run of this directional route on START_DATE.
CONCURRENCY_TEST_ROUTE = ("Milano", "Roma")
CONCURRENCY_TEST_SEAT_CLASS = "business"
CONCURRENCY_TEST_AVAILABLE = 5

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


def concurrency_test_train_id(products):
    """Resolve the pinned low-availability run to a real generated train_id:
    the earliest run of CONCURRENCY_TEST_ROUTE on START_DATE."""
    origin, destination = CONCURRENCY_TEST_ROUTE
    day0 = START_DATE.strftime("%Y%m%d")
    candidates = [
        t for (t, o, d, _dep) in products
        if o == origin and d == destination and f"-{day0}-" in t
    ]
    return min(candidates)


def build_seats(products, seat_classes):
    """Authoritative seat rows for Inventory: a coherent subset (first
    INVENTORY_DAYS of runs) of the exact catalog train_ids, with one run's
    seat_class pinned to the concurrency-test count."""
    horizon = {
        (START_DATE + timedelta(days=day)).strftime("%Y%m%d")
        for day in range(INVENTORY_DAYS)
    }
    pinned_train_id = concurrency_test_train_id(products)

    seats = []
    for (train_id, seat_class, _price, _currency, available) in seat_classes:
        # train_id encodes the departure date as ...-YYYYMMDD-HHMM.
        run_date = train_id.split("-")[-2]
        if run_date not in horizon:
            continue
        if train_id == pinned_train_id and seat_class == CONCURRENCY_TEST_SEAT_CLASS:
            available = CONCURRENCY_TEST_AVAILABLE
        seats.append((train_id, seat_class, available))
    return seats, pinned_train_id


HEADER = """\
-- Seed data for Catalog (catalog-db). GENERATED FILE — do not edit by hand.
-- Regenerate with: python3 tools/generate_seed.py --target=catalog > catalog/src/main/resources/data.sql
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


HEADER_INVENTORY = """\
-- Seed data for Inventory (inventory-db). GENERATED FILE — do not edit by hand.
-- Regenerate with: python3 tools/generate_seed.py --target=inventory > inventory/src/main/resources/data.sql
--
-- Idempotent (ON CONFLICT DO NOTHING) so it can run on every startup via
-- spring.sql.init without duplicating rows. These are the AUTHORITATIVE seat
-- counts (the ones actually decremented by POST /reserve) — as opposed to
-- Catalog's display-only snapshot. train_ids are a coherent subset of the real
-- Catalog runs (same generator), so every reservable train also appears in the
-- catalog listing.
--
-- Concurrency test target: {pinned} / {seat_class} is pinned to {available}
-- seats — this is the row the \"10 concurrent requests / {available} seats\"
-- test reserves against.\
"""


def sql_str(value):
    return "'" + str(value).replace("'", "''") + "'"


def emit_inventory(seats, pinned_train_id):
    header = HEADER_INVENTORY.format(
        pinned=pinned_train_id,
        seat_class=CONCURRENCY_TEST_SEAT_CLASS,
        available=CONCURRENCY_TEST_AVAILABLE,
    )
    out = [header, ""]
    out.append("INSERT INTO seats (train_id, seat_class, available) VALUES")
    rows = [
        f"    ({sql_str(t)}, {sql_str(c)}, {a})"
        for (t, c, a) in seats
    ]
    out.append(",\n".join(rows))
    out.append("ON CONFLICT (train_id, seat_class) DO NOTHING;")
    out.append("")
    return "\n".join(out)


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
    parser = argparse.ArgumentParser(description="Generate EuroTransit seed data.")
    parser.add_argument(
        "--target",
        choices=("catalog", "inventory"),
        default="catalog",
        help="Which service's data.sql to emit (default: catalog).",
    )
    args = parser.parse_args()

    products, seat_classes = build_runs()
    if args.target == "inventory":
        seats, pinned_train_id = build_seats(products, seat_classes)
        print(emit_inventory(seats, pinned_train_id))
    else:
        print(emit(products, seat_classes))


if __name__ == "__main__":
    main()
