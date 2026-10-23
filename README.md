# SJ03-PS-003 — Railway Reservation Engine

Node.js 20+ implementation of the PS-003 command-file driven reservation engine. It models one train over a fixed 12-station route, coach/berth inventory by inter-station segment, class quotas, confirmed/RAC/waitlist states, partial cancellations and promotions, distance-slab fares/refunds, and chart preparation. It uses Node built-ins only.

## Run

```sh
npm start
# or provide files explicitly
node src/cli.js data/train.json data/commands.json
```

The CLI prints deterministic JSON events and a final coach chart. Booking and cancellation events include snapshots of passenger statuses so each transition can be audited. Input examples are in `data/`.

## Command file format

The command file is a JSON array. Commands are processed in order. A booking uses an ISO-8601 `at`, class, station names, quota and 1–6 passenger records; cancellation names a booking and passenger IDs (omit the list to cancel all). `chart` prepares the chart at its command timestamp.

```json
{"type":"book","id":"B001","at":"2026-11-01T09:00:00+05:30","class":"SL","from":"Central","to":"Riverside","quota":"GENERAL","passengers":[{"id":"P001","name":"Asha","age":32,"gender":"F","berthPreference":"LOWER"}]}
```

Supported command types: `book`, `cancel`, `status`, `chart`. Station names must exist on the configured route; origin must precede destination. The provided demo uses a compact command set; the engine accepts the full 120-booking/25-cancellation input format without code changes.

## Design overview

Each berth stores one occupancy counter per route edge plus an allocation ledger. A trip from station index `i` to `j` occupies edges `[i, j)`. Two trips conflict exactly when their edge intervals intersect. This makes adjacent trips `A→B` and `B→C` legal on one berth. Checking a berth and adding/releasing an allocation is O(L), where L is the journey length. Searching B berths is O(B·L). With 11 route edges, storage is O(B·R + A·R), where R is route edges and A is active allocations. See [docs/design.md](docs/design.md) for the explanation and [the poster-style flow diagram](docs/data-flow.md).

See [the design document](docs/design.md) for the full model and BR-01…BR-12 mapping, [the PDF design document](output/pdf/ps-003-design.pdf), [the decision log](docs/decision-log.md), and [the command reference](docs/commands.md).

## Project layout

- `src/engine.js` — inventory, quota, allocation, cancellation, promotion, fare/refund and chart rules
- `src/cli.js` — command-file runner and stable JSON output
- `data/train.json` — 12-station sample route, coach layouts and fare card
- `data/commands.json` — runnable demonstration with 13 chronological commands
- `docs/reservation-flow.svg` — editable poster-style flow diagram
- `docs/data-flow.md` — diagram page with explanatory notes
- `output/pdf/ps-003-design.pdf` — submission-ready design PDF
- `test/engine.test.js` — Node built-in test suite covering segment allocation, quotas, RAC/WL promotion, partial cancellation and charting

## Scope notes

The sample fare rates, quota counts, and minimum deductions are illustrative configuration values; replace them with official sample data when supplied. Hidden evaluation data and checkpoint dates were not included in the brief. A hosted demo video URL must be added to the repository after recording and uploading the demonstration.

