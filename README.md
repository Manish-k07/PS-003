# SJ03-PS-003 — Railway Reservation Engine

Node.js 20+ implementation of the PS-003 command-file driven reservation engine. It models one train over a fixed route, seat inventory by inter-station segment, class quotas, confirmed/RAC/waitlist states, cancellations and promotions, fares/refunds, and chart preparation.

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

Each physical berth has an occupancy bitset represented by a `Set` of route segment indices. A trip from station index `i` to `j` occupies edges `[i, j)`. Two trips conflict exactly when their edge intervals intersect. This makes adjacent trips `A→B` and `B→C` legal on one berth. Availability lookup for a candidate is O(L) where L is journey length in segments; assigning or releasing is O(L); each candidate berth check is O(1) per occupied edge. With at most B berths and R route edges, a full search is O(B·L), bounded by O(B·R). The small, fixed route makes this clear and predictable; a 12-bit mask per berth could optimize it further, but the set representation is simpler to inspect in a decision log.

See [docs/design.md](docs/design.md) for the complete model and BR-01…BR-12 mapping, [docs/decision-log.md](docs/decision-log.md) for implementation decisions, and [docs/commands.md](docs/commands.md) for input/output details.

## Project layout

- `src/engine.js` — inventory, quota, allocation, cancellation, promotion, fare/refund and chart rules
- `src/cli.js` — command-file runner and stable JSON output
- `data/train.json` — 12-station sample route, coach layouts and fare card
- `data/commands.json` — runnable demonstration
- `test/engine.test.js` — Node built-in test suite covering key edge cases

## Scope notes

This repository is a self-contained student project and does not create/publish a remote Git repository or send email. No release dates or sample hidden evaluation files have been supplied. The demo video link is therefore a submission-time item.

