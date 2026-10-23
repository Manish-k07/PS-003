# Design Document — SJ03-PS-003

## Goal and assumptions

Implement a deterministic, command-file driven reservation engine for a single train on a 12-station route. The train definition is data, not code. Each booking has 1–6 passengers and creates individual passenger statuses under one booking ID. Monetary values are integer rupees; calculations round to the nearest rupee. Time inputs must include a UTC offset. A chart is prepared when a `chart` command is received at or after departure minus four hours.

The provided brief does not fully define quota inventory counts, age rules for children, minimum cancellation deductions, Tatkal opening calendar semantics, or chart-time quota release ordering. These are configurable where practical and otherwise documented here: minimum deductions come from each class's `minimumDeduction`; child is age under 12 for family grouping, under 5 has no berth/fare; Tatkal opens at departure minus 24 hours; chart converts unused LADIES and SENIOR inventory to GENERAL. Quota seats are capacity reservations, not extra physical berths, so a quota cannot consume more confirmed berths than physically exist.

## Domain model

- **Train**: ordered stations with cumulative distance, departure timestamp, coaches, class layouts, quota counts, and fare rates.
- **Berth**: physical berth ID, coach, class, type, and occupied route edges.
- **Booking**: requested class, quota, origin/destination, ordered passenger records, timestamps, fare/refund ledger, and audit events.
- **Passenger**: age/gender/preferences and current `CNF`, `RAC`, `WL`, or `CANCELLED` status, plus berth/RAC pair reference.
- **Wait queues**: per class and quota, stable insertion order. Promotion scans for the earliest passenger whose segment fits.

## Segment availability data structure and complexity

For route station indices `i` and `j`, a journey uses inter-station edges `i, i+1, …, j-1`. Each berth stores the set of currently occupied edge indices. A candidate fits if none of those edge indices is in the set. Assignment adds each index; release deletes each index. Shared endpoint stations do not imply shared edges, so `A→B` and `B→C` may reuse a berth.

For B berths and a journey of L edges, finding an available berth is O(B·L), assignment/release is O(L), and memory is O(B·R + A·R) in the direct representation, where R is route edges and A is active allocations. R is 11 for this route. A bit mask offers constant-time overlap checks for a fixed route, but the set makes per-segment occupancy easy to debug and explain.

## Allocation and promotion

Bookings are processed in command order. Eligible confirmed requests are allocated first by passenger order, with eligible lower berths prioritized for passengers qualifying under BR-02. If the confirmed capacity/quota path is unavailable, eligible passengers enter RAC up to the configured 10% class cap; remaining passengers enter WL up to the 30% class cap. The caps use floor of the configured class berth count, with a minimum of one when the percentage is positive and class has berths. A booking exceeding WL capacity fails atomically.

RAC uses a side-lower berth shared by two passengers; two people in a pair may have different journey segments if both fit the berth without overlapping a confirmed assignment. Cancelling one RAC passenger leaves the other paired record intact and frees/reconciles the RAC slot. On cancellation, promotions repeatedly scan RAC in queue order for a segment-compatible full berth, then WL in queue order for a RAC slot. A shorter later journey can therefore take a newly available berth if earlier candidates do not fit. Promotion decisions are logged with source, target, berth, and reason.

At chart time, eligible remaining RAC passengers receive vacant full berths. Remaining WL passengers are auto-cancelled with full refund. Unused LADIES/SENIOR quota inventory is released to GENERAL before chart allocation. Charting is rejected before departure minus four hours.

## Family / preference rules

Lower berth preference is considered for men aged 60+ and women aged 45+ before other lower-preferring passengers; remaining ties retain booking order. Family members are allocated within one coach when possible. Children under 12 are grouped with at least one adult from the same booking; this implementation tries a common coach for the whole booking and falls back to preserving child/adult coach pairing before general best effort. Children under five do not consume a berth and are not charged. They remain visible in output with `NO_BERTH` status.

## Fare and refund model

Base fare = class rate per km × chargeable journey distance. AC classes apply a 200 km minimum. Add the class reservation fee, apply configured quota premium or eligible concession to base fare, then add 5% GST for AC. Tatkal adds a 30% premium to base fare and disables concessions. Children under five pay zero. Senior concession is 40% for men and 50% for women on base fare; for mixed tickets, calculate passenger-level base components and sum. If a partial cancellation removes the only qualifying senior passenger, remaining passengers are repriced as GENERAL without concession.

Confirmed refund percentage is selected by hours before departure: 75% at 48h+, 50% from 12h to <48h, 25% from 4h to <12h, and zero after chart. The applicable class minimum deduction is subtracted from the refundable amount. RAC/WL cancellations at least 30 minutes before departure refund fare less Rs 60. Confirmed Tatkal cancellation refunds zero. Waitlist auto-cancellation after chart refunds full paid amount.

## Business rules registry

- **BR-01 — Segment availability:** a berth is free only if all route edges in the requested interval are free. Different passengers may use non-overlapping edges; adjacent journeys sharing only a station are compatible.
- **BR-02 — Lower berth priority:** passengers aged 60+ (men) or 45+ (women) are considered first for lower berths; other preferences are best effort in booking order.
- **BR-03 — Family allocation:** allocate a booking in one coach when possible; never separate a child under 12 from the adult group when a compatible berth arrangement exists.
- **BR-04 — Ladies quota:** LADIES bookings must contain only female passengers; unused quota is released to GENERAL at chart.
- **BR-05 — Senior quota/concession:** SENIOR booking must include a man aged 60+ or woman aged 58+; eligible passengers receive 40%/50% base-fare concession unless BR-06 applies.
- **BR-06 — Tatkal:** opens 24 hours before departure, adds 30% premium, gives no concession, and confirmed Tatkal cancellations have no refund.
- **BR-07 — RAC:** two RAC passengers share one side-lower berth; RAC capacity is 10% of class berths.
- **BR-08 — Waitlist:** WL capacity is 30% of class berths; the exact limit is accepted, excess bookings fail; post-chart WL is auto-cancelled with full refund.
- **BR-09 — Promotion order:** earliest eligible RAC promotes to a compatible vacant berth; if it cannot fit, scan later RAC entries; then promote eligible WL to RAC by the same fit-aware order.
- **BR-10 — Fare:** class rate × chargeable distance, with 200 km AC minimum; reservation fee SL Rs 20, 3A Rs 40, 2A Rs 60; AC GST is 5%.
- **BR-11 — Refund:** confirmed refund bands and RAC/WL flat Rs 60 deduction apply as specified; after-chart confirmed cancellation refund is nil.
- **BR-12 — Partial cancellation repricing:** recalculate ticket concession eligibility after each partial cancellation; if the only senior leaves, remaining passengers pay GENERAL fare.

## Command and output contract

Commands are JSON objects with `type` and ISO timestamp `at`. `book` uses `id`, `class`, `from`, `to`, `quota`, and `passengers[]`; `cancel` uses `bookingId`, optional `passengerIds[]`; `status` uses `bookingId`; `chart` prepares and prints the chart. Each command emits one JSON event with resulting passenger statuses. A final `chart` output includes coach/berth assignments and unresolved waitlist.

## Validation and known limits

Input validation rejects unknown stations/classes/quotas, invalid route order, invalid passenger counts, duplicate IDs, LADIES gender violations, invalid SENIOR eligibility, and out-of-window Tatkal requests. The implementation models one train only; multi-train passenger ledger and dynamic occupancy pricing are stretch goals. Refund schedules, quotas and rates are externally configurable through JSON.
