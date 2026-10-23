import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { ReservationEngine } from "../src/engine.js";

const train = JSON.parse(await readFile(new URL("../data/train.json", import.meta.url), "utf8"));
const engine = () => {
  const config = structuredClone(train);
  config.classes.SL.quotas.GENERAL = 10;
  return new ReservationEngine(config);
};
const at = "2026-11-01T09:00:00+05:30";
const passenger = (id, extra = {}) => ({ id, name: id, age: 30, gender: "M", ...extra });
const booking = (id, from, to, p = passenger(`P-${id}`), extra = {}) => ({ type: "book", id, at, class: "SL", from, to, quota: "GENERAL", passengers: [p], ...extra });

test("adjacent journeys can reuse a berth because station endpoints are not occupied segments", () => {
  const e = engine();
  e.execute(booking("B1", "Central", "Northgate"));
  e.execute(booking("B2", "Northgate", "Lakeview"));
  const a = e.bookings.get("B1").passengers[0], b = e.bookings.get("B2").passengers[0];
  assert.equal(a.berthId, b.berthId);
});

test("LADIES rejects a booking containing a male passenger", () => {
  const e = engine();
  assert.throws(() => e.execute(booking("B1", "Central", "Lakeview", passenger("P1"), { quota: "LADIES" })), /all-female/);
});

test("SENIOR concession eligibility and Tatkal no-concession premium are explicit", () => {
  const e = engine();
  e.execute(booking("S1", "Central", "Lakeview", passenger("SEN", { age: 62 }), { quota: "SENIOR" }));
  e.execute(booking("T1", "Central", "Lakeview", passenger("TAT", { age: 62 }), { quota: "TATKAL", at: "2026-11-07T10:00:00+05:30" }));
  assert.ok(e.bookings.get("S1").passengers[0].fare < e.bookings.get("T1").passengers[0].fare);
});

test("child under five has no berth and no fare", () => {
  const e = engine();
  e.execute(booking("B1", "Central", "Lakeview", passenger("C1", { age: 4 })));
  const p = e.bookings.get("B1").passengers[0];
  assert.equal(p.status, "NO_BERTH");
  assert.equal(p.fare, 0);
});

test("journey from the last station is rejected", () => {
  const e = engine();
  assert.throws(() => e.execute(booking("B1", "Riverside", "Central")), /Invalid journey/);
});

test("chart time gate is enforced", () => {
  const e = engine();
  assert.throws(() => e.execute({ type: "chart", at: "2026-11-08T03:59:00+05:30" }), /within four hours/);
});

test("waitlist limit accepts the exact capacity and rejects the next passenger", () => {
  const e = engine();
  // Occupy all physical segments, then force enough additional same-segment requests to reach the WL cap.
  for (let i = 0; i < 10; i += 1) e.execute(booking(`B${i}`, "Central", "Riverside", passenger(`P${i}`)));
  const wlLimit = e.capacity("SL", "wl");
  const racLimit = e.capacity("SL", "rac");
  for (let i = 0; i < racLimit + wlLimit; i += 1) e.execute(booking(`W${i}`, "Central", "Riverside", passenger(`WP${i}`)));
  assert.equal(e.activeInQueue("SL", "wl").length, wlLimit);
  assert.throws(() => e.execute(booking("W-OVER", "Central", "Riverside", passenger("WP-OVER"))), /Waitlist limit/);
});
