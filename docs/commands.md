# Command File Reference

The runner accepts a train JSON path followed by a command JSON path. Dates are ISO-8601 with timezone offsets. Station values are exact route names.

- `book`: `{ "type":"book", "id":"B1", "at":"...", "class":"SL", "from":"Central", "to":"Riverside", "quota":"GENERAL", "passengers":[{"id":"P1","name":"Asha","age":32,"gender":"F","berthPreference":"LOWER"}] }`
- `cancel`: `{ "type":"cancel", "at":"...", "bookingId":"B1", "passengerIds":["P1"] }`. Omit `passengerIds` to cancel all active passengers.
- `status`: `{ "type":"status", "at":"...", "bookingId":"B1" }`
- `chart`: `{ "type":"chart", "at":"..." }`

Output is one JSON object per command, followed by chart JSON if charting was requested. IDs must be unique across bookings/passengers.
