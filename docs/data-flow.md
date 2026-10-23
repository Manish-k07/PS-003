# Reservation Engine Data Flow

```mermaid
flowchart TD
    Train[Train definition JSON\nroute, coaches, berths, quotas, rates] --> CLI[Command-line runner]
    Commands[Command file JSON\nbook, cancel, status, chart] --> CLI
    CLI --> Init[Initialize ReservationEngine]
    Train --> Init
    Init --> Index[Build station index and berth segment sets]
    Index --> Engine[In-memory reservation state]
    CLI --> Dispatch[Process commands in file order]
    Dispatch --> Engine

    Engine --> Book{Book command}
    Book --> Validate[Validate route, class, quota, passengers, time]
    Validate --> Segments[Map journey to inter-station segments]
    Segments --> Find[Find compatible berths and enforce quota capacity]
    Find --> CNF[Confirmed allocation]
    Find --> RAC[RAC queue and side-lower pairs]
    Find --> WL[Waitlist queue within class limit]
    CNF --> Fare[Calculate fare and concessions]
    RAC --> Fare
    WL --> Fare
    Fare --> Snapshot[Passenger status snapshot]

    Engine --> Cancel{Cancel command}
    Cancel --> Refund[Calculate refund and reprice remaining ticket]
    Refund --> Release[Release passenger's occupied segments]
    Release --> Promote[Promote earliest segment-compatible RAC, then WL]
    Promote --> Snapshot

    Engine --> Status{Status command}
    Status --> Snapshot
    Engine --> Chart{Chart command}
    Chart --> ChartGate[Check 4-hour chart window]
    ChartGate --> Finalize[Assign vacant berths and auto-cancel remaining WL]
    Finalize --> CoachChart[Build coach and berth chart]
    Finalize --> Snapshot

    Snapshot --> Events[Emit deterministic JSON event]
    CoachChart --> Events
    Events --> Output[CLI stdout: event stream and final chart]
```

The engine uses route-edge sets per berth: a journey from station index `i` to `j` occupies edges `[i, j)`. Allocation, cancellation, and promotion all consult this same segment model, so partial-route bookings can reuse a berth when their occupied edges do not overlap.

