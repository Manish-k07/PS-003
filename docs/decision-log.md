# Decision Log

1. **Node.js ES modules and built-in APIs.** Node 20+ supplies the runtime and test runner; no production dependencies are needed.
2. **Per-berth edge counters plus allocation ledger.** A `Uint8Array` supports O(L) segment availability checks and allocation updates. The ledger records which passenger or RAC pair owns each edge so partial cancellation decrements only the relevant segments.
3. **Passenger-level state inside booking-level commands.** Partial cancellation, per-passenger status, family constraints, concessions, and refunds are easier to audit when each passenger has a persistent state record.
4. **Quota counts limit confirmed allocations.** Quotas are class passenger counts, while physical berth availability remains independent. At chart, unused non-GENERAL quota counts are released to GENERAL.
5. **Atomic waitlist admission.** A booking that would exceed the WL cap is rejected without leaving passengers, queues, or berth allocations behind.
6. **RAC pair allocation.** Two passengers share one side-lower pair allocation. The pair occupies the union of its passengers' segments; cancelling or promoting one member recalculates the pair without losing the other member's edges.
7. **Fit-aware stable promotions.** Queue insertion order is preserved. Candidates that cannot use the freed journey interval are skipped so later, shorter trips can progress.
8. **Family placement.** Try one coach for the party first, then allocate each child/adult unit together. A minor ticket must include an adult aged 18+; partial cancellation cannot leave that child without an adult.
9. **Ticket-level SENIOR concession while eligible.** When at least one qualifying senior remains, apply 40% to each male passenger's base fare and 50% to each female passenger's base fare. This directly implements the stated rule that the ticket reverts to GENERAL fare when its only senior is removed.
10. **Data-driven distance slabs and deductions.** Each class configures fare slabs, reservation fee, and minimum confirmed-cancellation deduction. The sample rates are illustrative until official rates are supplied.
11. **24-hour Tatkal opening.** Interpret “one day before” as a rolling 24-hour window relative to train departure and document the assumption.
12. **Deterministic JSON event stream.** Each successful command includes resulting passenger statuses; cancellation and chart commands also include ordered promotion logs and chart data.
13. **Single train scope.** Multi-train ledgers and occupancy-based dynamic pricing remain the assignment's optional stretch goals.

