# Command File Reference

The runner accepts a train JSON path followed by a command JSON path. Dates are ISO-8601 with timezone offsets and command timestamps must be chronological. Station values are exact route names.

- `book`: `{ "type":"book", "id":"B1", "at":"...", "class":"SL", "from":"Central", "to":"Riverside", "quota":"GENERAL", "passengers":[{"id":"P1","name":"Asha","age":32,"gender":"F","berthPreference":"LOWER"}] }`
- `cancel`: `{ "type":"cancel", "at":"...", "bookingId":"B1", "passengerIds":["P1"] }`. Omit `passengerIds` to cancel all active passengers.
- `status`: `{ "type":"status", "at":"...", "bookingId":"B1" }`
- `chart`: `{ "type":"chart", "at":"..." }`

Train classes may define a `fareSlabs` array of `{ "upToKm": number | null, "ratePerKm": number }` in increasing distance order. The first matching slab applies; the final slab uses `null` for no upper limit. `minimumDeduction` is class-specific.

Output is one JSON object per command. Booking and status events include every passenger's current status, fare, refund, coach, and berth/RAC pair. Cancellation and chart events also include ordered promotion logs. Chart output includes the final coach list, remaining waitlist, released quota counts, and post-chart waitlist refunds. IDs must be unique across bookings/passengers.

The included 13-command example intentionally exercises large parties, confirmed capacity, RAC sharing, waitlist promotion, partial cancellation, a child under five, a senior ticket, Tatkal, status lookup, and chart preparation.

