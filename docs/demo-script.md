# Five-minute demo recording script

This script is a ready-to-record walkthrough for the PS-003 submission. Record the terminal and narration, then add the hosted video URL to the submission README.

## 0:00–0:40 — Project overview

Show the repository root and explain that the engine is a Node.js 20+ command-file application for one train, with a 12-station route, three classes, quotas, segment-aware berth inventory, RAC, waitlist, cancellation, fares, and chart preparation.

## 0:40–1:20 — Data-driven configuration

Open `data/train.json`. Point out station distances, coach/berth layouts, quota limits, fare slabs, reservation fees, minimum deductions, and departure time. Explain that the sample values are configurable because official Week 2 sample values were not supplied.

## 1:20–2:00 — Run the automated tests

Run `npm test`. Show the passing test count. Briefly call out tests for adjacent journey reuse, the exact waitlist limit, partial senior-ticket repricing, RAC pair cancellation, fit-aware promotion, chart quota release, and an under-five passenger.

## 2:00–3:40 — Run the command-file demo

Run `npm start`. Scroll through booking events showing confirmed, RAC, and waitlisted states; a cancellation and ordered promotion; senior and Tatkal fares; a child under five with no berth/fare; and a status lookup. Explain that each JSON line is an auditable event snapshot.

## 3:40–4:30 — Explain segment allocation and chart

Open `docs/reservation-flow.svg` or `docs/data-flow.md`. Explain that each berth stores occupancy by route edge `[origin, destination)`, so adjacent journeys can reuse a berth while overlapping journeys cannot. Show the final chart event and point out chart-time RAC promotion, unused quota release, and remaining-WL refunds.

## 4:30–5:00 — Design and submission artifacts

Show `docs/design.md`, the BR-01 through BR-12 registry, `docs/decision-log.md`, and `output/pdf/ps-003-design.pdf`. End by noting the hidden evaluator files and schedule were not provided, and paste the hosted video link into the README after upload.

## Recording checklist

- Use a clean terminal at the repository root and a readable font size.
- Run `npm test` and `npm start` from a fresh terminal session.
- Keep personal paths, credentials, and unrelated windows out of the recording.
- Upload the recording to the submission's approved video host and replace the README's demo-video note with the shareable URL.

