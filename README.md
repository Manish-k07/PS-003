# SJ03-PS-003 — Railway Reservation Engine

Java 17+ (primary, zero external dependencies) and Node.js 20+ (prototype) implementations of the PS-003 command-file driven railway reservation engine. It models one train over a fixed 12-station route, coach/berth inventory by inter-station segment, class quotas (`GENERAL`, `LADIES`, `SENIOR`, `TATKAL`), confirmed/RAC/waitlist states, partial and full cancellations with ordered promotions, distance-slab fares/refunds, and final chart preparation.

## Build & Run (Java 17+)

Compile with JDK 17+ (`javac --release 17`):

```powershell
New-Item -ItemType Directory -Force -Path out/main, out/test | Out-Null
javac --release 17 -encoding UTF-8 -d out/main (Get-ChildItem -Recurse -Filter *.java src/main/java).FullName
javac --release 17 -encoding UTF-8 -cp out/main -d out/test (Get-ChildItem -Recurse -Filter *.java test/java).FullName
```

Run the CLI against `data/train.json` and `data/commands.json`:

```powershell
java -cp out/main com.sj03.reservation.Main data/train.json data/commands.json
```

Run the Java verification suite (25 tests covering BR-01..BR-12, all 7 edge cases, and a 120-booking / 25-cancellation 6-day simulation):

```powershell
java -cp "out/main;out/test" com.sj03.reservation.ReservationEngineTest
```

## Run (Node.js 20+ Prototype)

```sh
npm start
# or run the Node test suite
npm test
```

## Command file format

The command file is a JSON array processed sequentially in chronological ISO-8601 timestamp order. Supported commands: `book`, `cancel`, `status`, `chart`. Errors on a single command are emitted as `{"type":"error",...}` without mutating state so subsequent commands continue cleanly.

```json
{"type":"book","id":"B001","at":"2026-11-01T09:00:00+05:30","class":"SL","from":"Central","to":"Riverside","quota":"GENERAL","passengers":[{"id":"P001","name":"Asha","age":32,"gender":"F","berthPreference":"LOWER"}]}
```

## Segment availability data structure & complexity

Each `Berth` stores:
1. A 64-bit `long occupiedMask` representing occupied route edges `[i, j)` for a journey from station index `i` to `j` (up to 64 route edges; 11 edges for the 12-station route).
2. An `int[] edgeOccupancy` array of length `R = 11` and an auditable `List<Allocation>` ledger tracking individual `CNF` and shared `RAC` pair allocations.

Because `[i, j)` is half-open, adjacent trips `A→B` and `B→C` have disjoint edge bitmasks and legally share one berth. Checking whether a berth fits a journey bitmask is **O(1)** via `(berth.occupiedMask() & trip.segmentMask()) == 0L`, searching all `B` berths in a class is **O(B)** bitwise checks, and adding/releasing an allocation updates `edgeOccupancy` and `occupiedMask` in **O(L)** (bounded by `R = 11`). Storage is **O(B·R + A)** across `B` berths and `A` active allocations.

## Project layout

- `src/main/java/com/sj03/reservation/` — Java 17+ reservation engine, CLI (`Main.java`), JSON codec (`json/`), domain models (`model/`), and planners/calculators (`engine/`)
- `test/java/com/sj03/reservation/ReservationEngineTest.java` — Java 17+ test suite (25 tests + 120-booking/25-cancellation 6-day simulation)
- `src/engine.js`, `src/cli.js`, `test/engine.test.js` — original Node.js 20+ prototype and tests
- `data/train.json` — 12-station sample route, coach layouts, quotas, and fare card
- `data/commands.json` — runnable 13-command chronological demonstration
- `docs/design.md`, `docs/decision-log.md`, `docs/commands.md`, `docs/data-flow.md`, `docs/reservation-flow.svg` — architecture, BR-01..BR-12 registry, and diagrams
- `output/pdf/ps-003-design.pdf` — generated design PDF (`npm run build:design-pdf`)
