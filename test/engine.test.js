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
const engineWithSL = (berths, quotas) => {
  const config = structuredClone(train);
  config.classes.SL.coaches = [{ id: "T1", berths }];
  config.classes.SL.quotas = quotas;
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

test("the engine assigns an eligible senior to a lower berth before another passenger", () => {
  const e = engineWithSL(["UPPER", "LOWER"], { GENERAL: 2, LADIES: 0, SENIOR: 0, TATKAL: 0 });
  e.execute({ ...booking("PREF", "Central", "Lakeview"), passengers: [
    passenger("OLDER", { age: 60, gender: "M" }), passenger("YOUNGER", { age: 30, gender: "M" })
  ] });
  const [older, younger] = e.bookings.get("PREF").passengers;
  assert.equal(older.berthId, "T1-02");
  assert.equal(younger.berthId, "T1-01");
});

test("a child under 12 and adult guardian are allocated in the same coach", () => {
  const e = engine();
  e.execute({ ...booking("FAMILY", "Central", "Lakeview"), passengers: [
    passenger("ADULT", { age: 35 }), passenger("CHILD", { age: 10 })
  ] });
  const [adult, child] = e.bookings.get("FAMILY").passengers;
  assert.equal(adult.status, "CNF");
  assert.equal(child.status, "CNF");
  assert.equal(adult.coachId, child.coachId);
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
  assert.equal(e.bookings.get("T1").passengers[0].fare, 77);
});

test("distance fare slabs select the configured rate for the chargeable distance", () => {
  const e = engine();
  e.execute(booking("SLAB", "Central", "Lakeview"));
  assert.equal(e.bookings.get("SLAB").passengers[0].fare, 64);
});

test("confirmed Tatkal cancellation has no refund", () => {
  const e = engine();
  e.execute(booking("T-CANCEL", "Central", "Lakeview", passenger("T-P", { age: 62 }), { quota: "TATKAL", at: "2026-11-07T10:00:00+05:30" }));
  const before = e.bookings.get("T-CANCEL").passengers[0].fare;
  const result = e.execute({ type: "cancel", bookingId: "T-CANCEL", at: "2026-11-07T12:00:00+05:30" });
  assert.equal(before > 0, true);
  assert.equal(result.refunded, 0);
});

test("child under five has no berth and no fare", () => {
  const e = engine();
  e.execute({ ...booking("B1", "Central", "Lakeview", passenger("A1", { age: 36 })), passengers: [passenger("A1", { age: 36 }), passenger("C1", { age: 4 })] });
  const [adult, child] = e.bookings.get("B1").passengers;
  assert.equal(adult.status, "CNF");
  assert.equal(child.status, "NO_BERTH");
  assert.equal(child.fare, 0);
  assert.throws(() => e.execute({ type: "cancel", bookingId: "B1", passengerIds: ["A1"], at: "2026-11-01T10:00:00+05:30" }), /last adult/);
  e.execute({ type: "cancel", bookingId: "B1", passengerIds: ["C1"], at: "2026-11-01T10:00:00+05:30" });
  assert.equal(child.status, "CANCELLED");
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
  const bookingCount = e.bookings.size;
  assert.throws(() => e.execute(booking("W-OVER", "Central", "Riverside", passenger("WP-OVER"))), /Waitlist limit/);
  assert.equal(e.bookings.size, bookingCount);
  assert.equal(e.activeInQueue("SL", "wl").length, wlLimit);
});

test("partial SENIOR cancellation reprices remaining passengers at the GENERAL fare", () => {
  const e = engine();
  const command = { type: "book", id: "S-PART", at, class: "3A", from: "Central", to: "Riverside", quota: "SENIOR", passengers: [
    passenger("S-WOMAN", { name: "Senior", age: 58, gender: "F" }),
    passenger("S-MAN", { name: "Companion", age: 55, gender: "M" })
  ] };
  e.execute(command);
  const bookingState = e.bookings.get("S-PART");
  const generalFare = Math.round((1.1 * 740 + 40) * 1.05);
  assert.ok(bookingState.passengers[1].fare < generalFare);
  e.execute({ type: "cancel", bookingId: "S-PART", passengerIds: ["S-WOMAN"], at: "2026-11-07T10:00:00+05:30" });
  assert.equal(bookingState.passengers[1].fare, generalFare);
});

test("canceling one RAC member promotes the remaining passenger onto the freed side-lower berth", () => {
  const e = engineWithSL([...Array(18).fill("LOWER"), "SIDE_LOWER", "SIDE_LOWER"], { GENERAL: 18, LADIES: 1, SENIOR: 0, TATKAL: 0 });
  for (let i = 0; i < 18; i += 1) e.execute(booking(`F${i}`, "Central", "Riverside", passenger(`FP${i}`)));
  e.execute(booking("LADY", "Central", "Riverside", passenger("LADY-P", { gender: "F" }), { quota: "LADIES" }));
  e.execute({ ...booking("PAIR", "Central", "Riverside"), passengers: [passenger("R1"), passenger("R2")] });
  const pair = e.bookings.get("PAIR").passengers[0].racPair;
  assert.equal(pair.members.length, 2);
  e.execute({ type: "cancel", bookingId: "PAIR", passengerIds: ["R1"], at: "2026-11-07T07:00:00+05:30" });
  assert.equal(e.bookings.get("PAIR").passengers[1].status, "CNF");
  assert.equal(e.bookings.get("PAIR").passengers[1].berthId, "T1-20");
  assert.equal(pair.members.length, 0);
  assert.equal(pair.open, false);
});

test("promotion skips a long waitlisted trip and admits a later shorter trip to the released side-lower berth", () => {
  const e = engineWithSL([...Array(9).fill("LOWER"), "SIDE_LOWER"], { GENERAL: 11, LADIES: 0, SENIOR: 0, TATKAL: 0 });
  for (let i = 0; i < 9; i += 1) e.execute(booking(`FULL${i}`, "Central", "Riverside", passenger(`FULL-P${i}`)));
  e.execute(booking("EARLY-SEG", "Central", "Hilltown", passenger("P-EARLY")));
  e.execute(booking("LATE-SEG", "Greenfield", "Riverside", passenger("P-LATE")));
  e.execute(booking("WL-LONG", "Central", "Riverside", passenger("P-WL-LONG")));
  e.execute(booking("WL-SHORT", "Central", "Hilltown", passenger("P-WL-SHORT")));
  const result = e.execute({ type: "cancel", bookingId: "EARLY-SEG", at: "2026-11-07T07:00:00+05:30" });
  assert.equal(e.bookings.get("WL-LONG").passengers[0].status, "WL");
  assert.equal(e.bookings.get("WL-SHORT").passengers[0].status, "RAC");
  assert.ok(result.promotions.some(promotion => promotion.passengerId === "P-WL-SHORT" && promotion.to === "RAC"));
});

test("chart converts unused quotas to GENERAL and confirms a compatible general waitlist passenger", () => {
  const e = engineWithSL(["LOWER", "LOWER", "LOWER", "SIDE_LOWER", "SIDE_LOWER"], { GENERAL: 2, LADIES: 1, SENIOR: 1, TATKAL: 1 });
  e.execute(booking("G1", "Central", "Riverside", passenger("G-P1")));
  e.execute(booking("G2", "Central", "Riverside", passenger("G-P2")));
  e.execute(booking("R1", "Central", "Riverside", passenger("R-P1")));
  e.execute(booking("W1", "Central", "Riverside", passenger("W-P1")));
  const result = e.execute({ type: "chart", at: "2026-11-08T04:00:00+05:30" });
  assert.equal(e.bookings.get("W1").passengers[0].status, "CNF");
  assert.equal(result.releasedQuota.SL.releasedToGeneral, 3);
});

test("chart auto-cancels any waitlist passenger left after quota release and refunds the full fare", () => {
  const e = engineWithSL(["LOWER", "LOWER", "SIDE_LOWER"], { GENERAL: 2, LADIES: 0, SENIOR: 0, TATKAL: 0 });
  e.execute(booking("C1", "Central", "Riverside", passenger("C-P1")));
  e.execute(booking("C2", "Central", "Riverside", passenger("C-P2")));
  e.execute(booking("C3", "Central", "Riverside", passenger("C-P3")));
  e.execute(booking("C4", "Central", "Riverside", passenger("C-P4")));
  const fare = e.bookings.get("C4").passengers[0].fare;
  const result = e.execute({ type: "chart", at: "2026-11-08T04:00:00+05:30" });
  assert.equal(e.bookings.get("C4").passengers[0].status, "CANCELLED");
  assert.equal(e.bookings.get("C4").passengers[0].refund, fare);
  assert.equal(result.autoCancelledWaitlist[0].id, "C-P4");
});

test("chart cancels a no-berth infant if its only adult guardian remains waitlisted", () => {
  const e = engineWithSL(["LOWER", "LOWER", "SIDE_LOWER"], { GENERAL: 2, LADIES: 0, SENIOR: 0, TATKAL: 0 });
  e.execute(booking("I1", "Central", "Riverside", passenger("I-P1")));
  e.execute(booking("I2", "Central", "Riverside", passenger("I-P2")));
  e.execute(booking("I3", "Central", "Riverside", passenger("I-P3")));
  e.execute({ ...booking("I4", "Central", "Riverside", passenger("I-ADULT")), passengers: [passenger("I-ADULT", { age: 32 }), passenger("I-INFANT", { age: 3, gender: "F" })] });
  e.execute({ type: "chart", at: "2026-11-08T04:00:00+05:30" });
  const [adult, infant] = e.bookings.get("I4").passengers;
  assert.equal(adult.status, "CANCELLED");
  assert.equal(infant.status, "CANCELLED");
  assert.equal(infant.refund, 0);
});

test("commands with timestamps earlier than prior successful commands are rejected", () => {
  const e = engine();
  e.execute(booking("TIME-1", "Central", "Lakeview", passenger("TIME-P1"), { at: "2026-11-01T10:00:00+05:30" }));
  assert.throws(() => e.execute({ type: "status", bookingId: "TIME-1", at }), /chronological order/);
});

