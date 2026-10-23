# Design Document — SJ03-PS-003

## Goal

Build a deterministic, command-file driven reservation engine for one train on a fixed 12-station route. Train layout, class capacities, quotas, fare slabs, reservation fees, and cancellation minimums are data in `data/train.json`. Commands execute in timestamp order and each command emits a JSON result with passenger status snapshots.

## Assumptions and configuration

- Command timestamps are ISO-8601 with an explicit timezone and must be chronological.
- Tatkal opens exactly 24 hours before the configured departure; requests earlier than that window are rejected.
- Quota counts are passenger limits on confirmed allocation, tracked by class and quota. They do not create extra physical berths.
- RAC capacity is `max(1, floor(berths × 10%))` passengers per class. Waitlist capacity uses `max(1, floor(berths × 30%))` passengers per class. The minimum of one keeps small example layouts usable; the sample layout contains multiple coaches per class.
- A passenger under 12 must be booked with an adult aged 18 or older. A passenger under five has `NO_BERTH`, pays no fare, and remains associated with that adult ticket.
- Senior quota concession applies to every passenger on the SENIOR ticket while at least one qualifying senior remains. Men receive 40% off base fare and women 50%; if the last qualifying senior is cancelled, remaining passengers revert to GENERAL fare. This ticket-level interpretation makes the partial-cancellation rule observable.
- Confirmed refund percentage is treated as the refund rate, subject to the class minimum deduction: refund is fare minus the larger of the minimum deduction or the unreimbursed percentage. Sample minimum deductions and fare slabs are configurable examples because exact values were not supplied.

## Domain model

- **Train**: ordered stations with cumulative distances, departure timestamp, classes, coaches, berth layouts, quotas, fare slabs, and class refund minimums.
- **Berth**: physical berth ID, coach, class, berth type, a per-route-edge occupancy counter, and an allocation ledger.
- **Booking**: booking ID, class/quota, journey, timestamps, passengers, amount paid, current repriced fare, refunds, and cancellation state.
- **Passenger**: passenger ID, name, age, gender, berth preference, current status, coach/berth or RAC pair, fare, and refund.
- **RAC pair**: one side-lower berth shared by up to two passengers. Its occupied segments are the union of both journeys.
- **Queues**: stable insertion-order RAC and WL queues for each class. Inactive entries remain in the log but are filtered by current status.

## Segment availability data structure and complexity

A journey from route station index `i` to `j` uses edge indices `[i, j)`. Each berth stores a `Uint8Array` with one counter per route edge and a ledger of its active allocations. A candidate trip fits when each edge counter is zero. A confirmed passenger increments each occupied edge; cancellation decrements those edges. A RAC pair contributes one allocation whose edges are the union of its members' trips. This permits `A→B` and `B→C` to reuse one berth because their edge sets do not overlap.

For B berths and L edges in a candidate journey, a full berth search is O(B·L); checking one berth is O(L). Adding, releasing, or changing an allocation is O(L). Occupancy storage is O(B·R), where R is 11 route edges; the allocation ledger adds O(A·R) in the worst case for A active allocations. Since the route is fixed, R is small and bounded. The counter plus ledger design makes overlap checks fast while retaining enough detail for safe partial release and audit logs.

## Booking and family allocation

The engine validates stations, route order, class, quota, passenger count, IDs, age/gender, quota eligibility, Tatkal window, and child/adult grouping before accepting a booking. Confirmed allocation uses quota capacity and physical segment availability. Lower berths are considered first for men aged 60+ and women aged 45+; other preferences are best effort. The engine first tries to place the complete party in one coach. If that fails, each under-12 child and adult guardian are allocated together in one coach; if no compatible allocation exists, the child/guardian group stays in the same waiting path rather than being split. Remaining adults are allocated in priority and booking order.

After confirmed allocation, the booking is planned for RAC up to the passenger limit. RAC requests use a free segment-compatible side-lower berth or an open pair slot. Two RAC passengers may share one pair even when their journey segments overlap; that pair blocks a confirmed allocation on every edge used by either passenger. Remaining passengers enter WL if the class limit allows. A booking that would exceed WL capacity is rejected atomically and any temporary confirmed allocations are released.

## Cancellation and promotion

Full or partial cancellation validates the target passengers and prevents a partial cancellation from leaving an under-12 child without an adult. Confirmed allocations release only their own edge intervals; a RAC cancellation updates the shared pair without disturbing its other passenger. The engine then scans RAC in queue order for the earliest passenger whose journey fits a full berth, skipping passengers that do not fit. It next scans WL in order for passengers who can fit an available RAC pair or side-lower berth. Every promotion records passenger ID, old/new state, berth or pair, timestamp, and reason.

On partial cancellation, the remaining active ticket is repriced. The original paid amount and cumulative refunds remain in the booking ledger; `currentFare` exposes the fare for the remaining active passengers.

## Fare and refund model

Base fare is the configured rate-per-km slab multiplied by chargeable distance. AC classes use a 200 km minimum. Add the class reservation fee after quota adjustment and apply 5% GST to AC totals. Tatkal adds 30% to base fare and disables all concessions. A SENIOR ticket with an active qualifying senior receives the ticket-level gender concession described above. Children under five pay nothing.

For confirmed cancellations, refund rates are 75% at 48 hours or more, 50% from 12 to under 48 hours, 25% from 4 to under 12 hours, and zero under 4 hours or after chart. The configured class minimum deduction is applied as described in Assumptions. Confirmed Tatkal cancellation refunds zero. RAC/WL cancellation at least 30 minutes before departure returns fare less Rs 60. Any WL passenger still waiting after chart is auto-cancelled with a full refund.

## Chart preparation

Chart preparation is accepted from departure minus four hours until departure. Segment-compatible RAC passengers receive vacant full berths first. Unused LADIES, SENIOR, and TATKAL confirmed-quota capacity is released to GENERAL. General waitlisted passengers are then assigned confirmed berths in order when both quota capacity and a compatible physical berth remain; remaining WL passengers are auto-cancelled with full fare refunds. Output includes coach/berth placement, any RAC pair that remains, final waitlist, auto-cancelled WL records, released quota counts, and no-berth passengers.

## Business rules registry

- **BR-01 — Segment availability:** a berth is available only if every edge in the requested journey interval is free. Different passengers may use non-overlapping edges; adjacent journeys sharing a station are compatible.
- **BR-02 — Lower berth priority:** men aged 60+ and women aged 45+ are considered first for lower berths. Other preferences are best effort in booking order.
- **BR-03 — Family allocation:** allocate a party in one coach where possible. Never separate an under-12 child from an adult aged 18+ in the booking allocation path.
- **BR-04 — Ladies quota:** LADIES bookings contain only female passengers; unused LADIES quota is released to GENERAL at chart.
- **BR-05 — Senior quota/concession:** SENIOR booking includes a man aged 60+ or woman aged 58+. While a qualifying senior remains on the ticket, base fare concessions are 40% for men and 50% for women.
- **BR-06 — Tatkal:** opens 24 hours before departure, adds 30% to base fare, provides no concession, and confirmed Tatkal cancellations have no refund.
- **BR-07 — RAC:** up to two RAC passengers share one side-lower pair; RAC passenger capacity is 10% of class berths, with a minimum one place for small layouts.
- **BR-08 — Waitlist:** WL passenger capacity is 30% of class berths, with a minimum one place for small layouts. The exact limit is accepted; requests beyond it fail. Post-chart WL is auto-cancelled with full refund.
- **BR-09 — Promotion order:** earliest segment-compatible RAC promotes to CNF; if it does not fit, scan later RAC entries. Then promote the earliest compatible WL passenger to RAC, preserving order among compatible candidates.
- **BR-10 — Fare:** class/distance-slab rate × chargeable distance, with a 200 km AC minimum; reservation fee SL Rs 20, 3A Rs 40, 2A Rs 60; AC GST is 5%.
- **BR-11 — Refund:** confirmed refund bands and configured class minimum deduction apply; RAC/WL refunds subtract Rs 60 up to 30 minutes before departure; after-chart confirmed cancellation is nil.
- **BR-12 — Partial cancellation repricing:** recompute SENIOR ticket concessions after each cancellation. If its only qualifying senior leaves, the remaining ticket is priced at GENERAL fare.

## Command and output contract

Commands are JSON objects with a command `type` and ISO timestamp `at`. `book` uses `id`, `class`, `from`, `to`, `quota`, and 1–6 `passengers`; `cancel` uses `bookingId` and optional `passengerIds`; `status` uses `bookingId`; `chart` prepares the final chart. The CLI emits one deterministic JSON object per command, with errors isolated to the failing command so later commands can continue.

## Validation and limits

The engine rejects malformed train definitions, unknown stations/classes/quotas, invalid route order, invalid passenger counts, duplicate IDs, invalid passenger age/gender, LADIES/SENIOR eligibility violations, Tatkal requests outside the window, and out-of-order commands. The stretch goals of multiple trains, shared passenger ledger, and demand-based dynamic fare pricing are not part of this single-train implementation.

## Status lifecycle and audit output

- **CNF** has a coach and berth ID and contributes an allocation to that berth's edge counters.
- **RAC** references a side-lower pair. The pair has capacity two and retains both passenger IDs and the union of their occupied edges.
- **WL** is retained in stable command order until promoted or chart auto-cancellation.
- **NO_BERTH** is the under-five child state. It has no fare or berth and follows the adult ticket at chart.
- **CANCELLED** is terminal and retains the passenger's refund record.

The command runner emits the event result after every successful operation. Booking/status responses include passenger status, berth/coach, RAC pair, fare, and refund. Cancellation responses include the refund delta and ordered promotions. A chart response includes release counts, final coach placement, auto-cancelled WL records, and any remaining WL/RAC details.

## Verification coverage

The built-in Node test suite covers adjacent journey reuse, senior lower berth preference, family coach grouping, Ladies quota validation, senior and Tatkal fares, distance slabs, child under five handling, last-station route rejection, exact waitlist capacity, partial senior-ticket repricing, RAC pair cancellation, fit-aware promotion to a side-lower pair, chart quota release, full WL refunds, infant guardian cancellation, and chronological command validation. Run it with `npm test`; run the command-file demonstration with `npm start`.

The sample fares, minimum deductions, coach counts, and quota counts are intentionally configuration data. Replace them with official sample values when those files arrive. The repository includes a command runner and editable diagram; the hidden evaluation dataset and a hosted five-minute demo video link are not available in the assignment brief yet.

