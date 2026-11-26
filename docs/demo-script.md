# Five-minute demo recording script

This script is a ready-to-record walkthrough for the PS-003 submission. Record the terminal and narration, then add the hosted video URL to the submission README.

## 0:00–0:40 — Project overview

Show the repository root and explain that the primary engine is a zero-dependency Java 17+ command-file application (with the original Node.js 20+ prototype alongside it) for one train, with a 12-station route, three classes, quotas, segment-aware berth inventory, RAC, waitlist, cancellation, fares, and chart preparation.

## 0:40–1:20 — Data-driven configuration

Open `data/train.json`. Point out station distances, coach/berth layouts, quota limits, fare slabs, reservation fees, minimum deductions, and departure time. Explain that the sample values are configurable because official Week 2 sample values were not supplied.

## 1:20–2:00 — Build and run the automated tests

Compile with `javac --release 17` and run `java -cp "out/main;out/test" com.sj03.reservation.ReservationEngineTest`. Show the passing test count. Briefly call out tests for adjacent journey reuse, the exact waitlist limit, partial senior-ticket repricing, RAC pair cancellation, fit-aware promotion, chart quota release, an under-five passenger, and the 120-booking / 25-cancellation 6-day simulation.

## 2:00–3:40 — Run the command-file demo

Run `java -cp out/main com.sj03.reservation.Main data/train.json data/commands.json`. Scroll through booking events showing confirmed, RAC, and waitlisted states; a cancellation and ordered promotion; senior and Tatkal fares; a child under five with no berth/fare; and a status lookup. Explain that each JSON line is an auditable event snapshot.

## 3:40–4:30 — Explain segment allocation and chart

Open `docs/reservation-flow.svg` or `docs/data-flow.md`. Explain that each berth stores a 64-bit occupied route-edge mask `[origin, destination)` plus `edgeOccupancy` counters and an active `Allocation` ledger, so adjacent journeys reuse a berth in O(1) bitwise check time while overlapping journeys cannot. Show the final chart event and point out chart-time RAC promotion, unused quota release, and remaining-WL refunds.

## 4:30–5:00 — Design and submission artifacts

Show `docs/design.md`, the BR-01 through BR-12 registry, `docs/decision-log.md`, and `output/pdf/ps-003-design.pdf`. End by noting the hidden evaluator files and schedule were not provided, and paste the hosted video link into the README after upload.

## Recording checklist

- Use a clean terminal at the repository root and a readable font size.
- Compile and run `ReservationEngineTest` and `Main` from a fresh terminal session.
- Keep personal paths, credentials, and unrelated windows out of the recording.
- Upload the recording to the submission's approved video host and replace the README's demo-video note with the shareable URL.
