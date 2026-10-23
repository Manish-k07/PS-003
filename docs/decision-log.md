# Decision Log

1. **Node.js ES modules and built-in APIs.** No runtime dependencies; easy to run in a clean Node 20 environment.
2. **Set of occupied segment indices per berth.** Explicitly captures partial journey overlap and is simple to audit on an 11-edge route.
3. **Passenger-level state under booking-level commands.** Partial cancellation and per-passenger concession are represented directly.
4. **Atomic waitlist admission.** A booking that would exceed class WL capacity is rejected without leaving partial passengers or inventory marks.
5. **Deterministic fit-aware promotion.** Queue order is stable, but a candidate that cannot use the released segment is skipped so later shorter journeys can progress.
6. **Data-driven train, quotas, fare rates and deductions.** Evaluation data can replace JSON without changing allocation logic.
7. **Deterministic JSON event stream.** Status snapshots and promotion logs make command-by-command grading reproducible.
8. **Configurable assumptions for underspecified details.** Minimum deductions and quota capacities are in train data; age, chart and Tatkal timing assumptions are documented in the design document.
