# Design Document — SJ03-PS-003

## Goal

Build a deterministic, command-file driven reservation engine in Java 17+ (alongside the original Node.js 20+ prototype) for one train on a fixed 12-station route. Train layout, class capacities, quotas, fare slabs, reservation fees, and cancellation minimums are configured in `data/train.json`. Commands execute in chronological timestamp order and each command emits a deterministic JSON event with passenger status snapshots.

## Assumptions and configuration

- Command timestamps are ISO-8601 with an explicit timezone offset (or `Z`) and must be chronological.
- Tatkal opens 24 hours before the configured departure; requests earlier than that window are rejected.
- Quota counts are confirmed passenger limits tracked per class and quota (`GENERAL`, `LADIES`, `SENIOR`, `TATKAL`) and do not create extra physical berths.
- RAC capacity is `max(1, floor(berths × 10%))` passengers per class. Waitlist capacity uses `max(1, floor(berths × 30%))` passengers per class.
- A passenger under 12 must be booked with an adult aged 18 or older. A passenger under five receives `NO_BERTH`, pays zero fare, and remains attached to that adult ticket.
- Senior quota concession applies to active passengers on the `SENIOR` ticket while at least one qualifying senior (`M >= 60` or `F >= 58`) remains (`40%` off base fare for men, `50%` for women). If the last qualifying senior is cancelled, remaining passengers revert to `GENERAL` fare.
- Confirmed refund percentage is subject to the class `minimumDeduction`: refund is fare minus `max(minimumDeduction, round(fare * (1 - refundRate)))`, and cumulative booking refunds are bounded by `totalPaid`.

## Domain model

- **TrainDefinition**: 12 ordered stations with strictly increasing cumulative distances, departure instant, classes, coaches, berth layouts, quotas, fare slabs, reservation fees, and minimum deductions.
- **Berth**: physical berth ID (`<coach>-<01..>`), coach ID, class code, `BerthType`, a 64-bit `occupiedMask`, an `int[] edgeOccupancy` array over route edges, and an active `Allocation` ledger.
- **Trip**: half-open station edge interval `[start, end)` with precomputed `long segmentMask`.
- **Booking**: booking ID, class/quota, journey `Trip`, timestamp, ordered passengers, `totalPaid`, `currentFare`, `refunded`, and cancellation status.
- **Passenger**: ID, name, age, gender, optional berth preference, current `PassengerStatus` (`CNF`, `RAC`, `WL`, `NO_BERTH`, `CANCELLED`), berth/coach or `RacPair`, fare, and refund.
- **RacPair**: one `SIDE_LOWER` berth shared by up to two RAC passengers whose `Allocation` occupies the union of both members' segment masks.
- **Queues**: stable FIFO insertion-order RAC and WL queues per class.

## Segment availability data structure and complexity

A journey from route station index `i` to `j` occupies half-open route edges `[i, j)`, encoded as a 64-bit `long segmentMask = ((1L << (j - i)) - 1L) << i`. Because endpoints are not occupied edges, `A→B` and `B→C` have disjoint masks (`(maskAB & maskBC) == 0L`) and legally reuse the same physical berth.

Each `Berth` pairs this 64-bit `occupiedMask` with an `int[] edgeOccupancy` counter array (`R = 11` edges) and an active `List<Allocation>` ledger:
- **Availability check on one berth**: `(berth.occupiedMask() & trip.segmentMask()) == 0L` runs in **O(1)** time.
- **Class-wide berth search across B berths**: **O(B)** bitwise checks (plus **O(B log B)** sorting when ranking preferences).
- **Open RAC-pair conflict check**: `berth.fitsIgnoring(candidateMask, pair.allocation())` verifies that joining an open `RacPair` does not overlap with any non-RAC `CNF` allocation sharing non-overlapping segments of the same `SIDE_LOWER` berth, in **O(A_berth)** (`<= 11` segments on one berth).
- **Allocation / release / RAC mask recomputation**: **O(L)** over `L = j - i <= 11` edges.
- **Space complexity**: **O(B)** bitmasks + **O(B·R + A)** counters and active allocations.

## Booking and family allocation

Before mutating inventory, `ReservationEngine` validates stations, route order, class, quota, 1–6 passenger count, unique IDs, age/gender, `LADIES`/`SENIOR` eligibility, Tatkal 24h window, and under-12 adult accompaniment.

Confirmed (`CNF`) allocation in `AllocationPlanner` respects quota limits and physical segment fit:
1. Lower berths are prioritized for men aged `60+` and women aged `45+`; explicit preferences are honored best-effort in priority/booking order.
2. When selecting `CNF` berths, `SIDE_LOWER` berths receive a score penalty (unless explicitly requested) so `SIDE_LOWER` inventory stays available for RAC pairs as long as regular berths exist.
3. Multi-passenger bookings try placing the whole party in the highest-scoring single coach first. If that fails, children (`5..11`) are balanced across adults (`18+`) into family units and each unit is placed together in one coach or kept together in the RAC/WL path rather than splitting an under-12 child from their adult guardian.

Remaining `PENDING` passengers are planned atomically into `RAC` (joining open `RacPair` slots that have no non-RAC berth conflicts, or allocating free `SIDE_LOWER` berths up to the 10% limit) and then `WL` (up to the 30% limit). If admitting the waitlist portion would exceed the class WL limit, temporary `CNF` allocations are rolled back and the booking is rejected atomically.

## Cancellation and promotion

Full or partial cancellation validates passenger IDs and rejects any partial cancellation that would leave an under-12 child without an adult `18+`. Confirmed allocations release only their own segment masks; cancelling one member of an RAC pair recalculates the pair's union mask while leaving the surviving member intact on the `SIDE_LOWER` berth (`RAC` rather than premature self-promotion before chart), opening the second RAC slot for waitlisted passengers.

Pre-chart cancellation triggers ordered promotion (`RAC → CNF`, then `WL → RAC`):
- **RAC → CNF**: scans the class RAC queue in insertion order for passengers within their quota's remaining capacity whose segments fit a vacant full berth (excluding their own active `RacPair` berth), skipping candidates whose journey does not fit and respecting under-12 guardian co-location.
- **WL → RAC**: scans waitlist units in insertion order for the earliest segment-compatible unit that fits an open `RacPair` (conflict-checked against any `CNF` allocations on that berth) or a vacant `SIDE_LOWER` berth. If the RAC queue is empty and no `SIDE_LOWER` berth is free, quota-eligible WL units may claim a vacant full berth directly so cancelled berths are not stranded.
- On partial cancellation, `FareCalculator.reprice` updates active passenger fares and `currentFare`.

## Fare and refund model

Base fare equals the configured distance-slab `ratePerKm` multiplied by chargeable distance (`max(200, rawDistance)` km for AC classes `3A` and `2A`). Tatkal multiplies base fare by `1.3` and disables all concessions; active `SENIOR` tickets multiply base fare by `0.6` (male) or `0.5` (female). Reservation fee (`Rs 20` SL, `Rs 40` 3A, `Rs 60` 2A) is added, `5%` GST is applied to AC totals, and totals are rounded `HALF_UP` to nearest integer rupees. Children under five pay zero.

Confirmed cancellation refunds use `75%` (`>= 48h`), `50%` (`12–48h`), `25%` (`4–12h`), and `0` (`< 4h` or post-chart), with class `minimumDeduction` enforced. Confirmed `TATKAL` cancellation yields zero refund. `RAC`/`WL` cancellations deduct `Rs 60` up to 30 minutes before departure (`0` thereafter). Remaining `WL` passengers after chart are auto-cancelled with full fare refunds.

## Chart preparation

Chart preparation is accepted from 4 hours before departure until departure (`0 <= seconds <= 14400`):
1. Snapshot unfilled quota counts per class (`LADIES`, `SENIOR`, `TATKAL`) and release them into the `GENERAL` pool (`availableSlots` bounded by `totalClassQuota - totalActiveCnf` so RAC promotions and WL confirmations share one consistent pool without double counting).
2. Promote segment-compatible `RAC` passengers to `CNF` (allowing solo `RAC` members or co-located family pairs to upgrade onto their `SIDE_LOWER` berth when no regular berth remains).
3. Confirm compatible `GENERAL` waitlist units in FIFO order from `availableSlots` (followed by any remaining waitlist units if slots remain).
4. Auto-cancel any remaining `WL` passengers with full fare refund, cancel orphaned `NO_BERTH` infants whose guardians did not clear the chart, and emit the complete coach chart, final waitlist, and promotion log.

## Business rules registry

- **BR-01 — Segment availability:** a berth is available for `A→B` only if every edge in `[A, B)` is unoccupied; `A→B` and `B→C` share endpoints legally on one berth.
- **BR-02 — Lower berth priority:** men `60+` and women `45+` receive `LOWER` berth priority first; other preferences are best-effort in booking order, while `SIDE_LOWER` berths are deprioritized for `CNF` to preserve RAC inventory.
- **BR-03 — Family allocation:** parties are placed in the highest-scoring single coach where possible; an under-12 child is never split from an adult `18+` across allocation, cancellation, or promotion paths.
- **BR-04 — Ladies quota:** `LADIES` accepts all-female tickets only; unfilled `LADIES` quota converts to `GENERAL` at chart.
- **BR-05 — Senior quota/concession:** `SENIOR` requires at least one man `60+` or woman `58+`; active tickets receive `40%` (men) / `50%` (women) concession on base fare.
- **BR-06 — Tatkal:** opens 24h before departure, adds `30%` base fare premium, allows no concessions, and grants zero refund on confirmed cancellation.
- **BR-07 — RAC:** two RAC passengers share one `SIDE_LOWER` berth; RAC capacity is `10%` of class berths (min 1); RAC passengers receive full berths at chart if vacancies permit.
- **BR-08 — Waitlist:** WL capacity is `30%` of class berths (min 1); exact limit succeeds and bookings beyond fail atomically; post-chart WL auto-cancels with full refund.
- **BR-09 — Promotion order:** earliest segment-compatible `RAC → CNF` (skipping non-fitting trips), then earliest compatible `WL → RAC`, with every promotion logged.
- **BR-10 — Fare:** distance-slab rate × chargeable distance (`200 km` AC min), reservation fee (`20`/`40`/`60`), and `5%` GST on AC only.
- **BR-11 — Refund:** confirmed bands (`48h+` 75%, `12–48h` 50%, `4–12h` 25%, `<4h`/post-chart nil) with class minimum deduction; `RAC`/`WL` flat `Rs 60` deduction up to 30m before departure.
- **BR-12 — Partial cancellation repricing:** recompute `SENIOR` concessions after each cancellation; losing the last qualifying senior reverts remaining passengers to `GENERAL` fare.

## Verification coverage

The Java 17+ test suite (`test/java/com/sj03/reservation/ReservationEngineTest.java`) verifies 25 comprehensive test cases covering BR-01..BR-12, all 7 explicit edge cases, RAC/quota/chart fixes, end-to-end execution of `data/commands.json`, and a deterministic 120-booking + 25-cancellation 6-day simulation checking segment, quota, capacity, and refund invariants. The original 18-test Node.js suite (`npm test`) remains green alongside it.
