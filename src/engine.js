const ACTIVE = new Set(["CNF", "RAC", "WL"]);
const money = n => Math.round(n);
const hoursBetween = (later, earlier) => (Date.parse(later) - Date.parse(earlier)) / 3600000;

export class ReservationEngine {
  constructor(train) {
    this.train = train;
    this.stationIndex = new Map(train.route.map((s, i) => [s.name, i]));
    this.berths = new Map();
    this.bookings = new Map();
    this.passengerIds = new Set();
    this.queues = Object.fromEntries(Object.keys(train.classes).map(c => [c, { rac: [], wl: [] }]));
    this.events = [];
    this.chartPrepared = false;
    for (const [classCode, spec] of Object.entries(train.classes)) {
      for (const coach of spec.coaches) coach.berths.forEach((type, i) => {
        const id = `${coach.id}-${String(i + 1).padStart(2, "02")}`;
        this.berths.set(id, { id, coach: coach.id, classCode, type, occupied: new Set(), confirmed: [] });
      });
    }
  }

  execute(command) {
    if (!command || !command.type || !command.at || Number.isNaN(Date.parse(command.at))) throw new Error("Every command requires a valid ISO timestamp and type");
    const result = ({ book: () => this.book(command), cancel: () => this.cancel(command), status: () => this.status(command), chart: () => this.prepareChart(command) })[command.type];
    if (!result) throw new Error(`Unknown command type: ${command.type}`);
    const event = result();
    this.events.push(event);
    return event;
  }

  trip(from, to) {
    const start = this.stationIndex.get(from), end = this.stationIndex.get(to);
    if (start === undefined || end === undefined || start >= end) throw new Error(`Invalid journey ${from} → ${to}`);
    return { start, end, segments: Array.from({ length: end - start }, (_, i) => start + i) };
  }

  berthCount(classCode) { return [...this.berths.values()].filter(b => b.classCode === classCode).length; }
  capacity(classCode, kind) {
    const count = this.berthCount(classCode);
    const pct = kind === "rac" ? 0.1 : 0.3;
    return count ? Math.max(1, Math.floor(count * pct)) : 0;
  }
  berthFits(berth, trip) { return trip.segments.every(s => !berth.occupied.has(s)); }
  isPriority(p) { return (p.gender === "M" && p.age >= 60) || (p.gender === "F" && p.age >= 45); }
  eligibleForQuota(passengers, quota) {
    if (quota === "LADIES" && passengers.some(p => p.gender !== "F")) throw new Error("LADIES quota requires an all-female booking");
    if (quota === "SENIOR" && !passengers.some(p => (p.gender === "M" && p.age >= 60) || (p.gender === "F" && p.age >= 58))) throw new Error("SENIOR quota requires a qualifying senior passenger");
  }

  book(c) {
    if (this.chartPrepared) throw new Error("Bookings are closed after chart preparation");
    if (this.bookings.has(c.id)) throw new Error(`Duplicate booking id ${c.id}`);
    const spec = this.train.classes[c.class];
    if (!spec) throw new Error(`Unknown class ${c.class}`);
    if (!Object.hasOwn(spec.quotas, c.quota)) throw new Error(`Unknown quota ${c.quota}`);
    if (!Array.isArray(c.passengers) || c.passengers.length < 1 || c.passengers.length > 6) throw new Error("A booking must include 1–6 passengers");
    const trip = this.trip(c.from, c.to);
    if (c.quota === "TATKAL" && hoursBetween(this.train.departure, c.at) > 24) throw new Error("TATKAL opens 24 hours before departure");
    this.eligibleForQuota(c.passengers, c.quota);
    for (const p of c.passengers) {
      if (!p.id || this.passengerIds.has(p.id)) throw new Error(`Missing or duplicate passenger id ${p.id}`);
      if (!Number.isFinite(p.age) || p.age < 0) throw new Error(`Invalid age for ${p.id}`);
    }
    const passengers = c.passengers.map((p, order) => ({ ...p, bookingId: c.id, order, from: c.from, to: c.to, classCode: c.class, quota: c.quota,
      status: p.age < 5 ? "NO_BERTH" : "PENDING", berthId: null, racPair: null, fare: 0, refund: 0 }));
    const booking = { id: c.id, classCode: c.class, quota: c.quota, trip, from: c.from, to: c.to, at: c.at, passengers, canceled: false, paid: 0, refunded: 0 };
    const allocated = this.allocateConfirmed(booking, passengers.filter(p => p.status === "PENDING"));
    const outstanding = passengers.filter(p => p.status === "PENDING");
    const availableRac = this.capacity(c.class, "rac") - this.activeInQueue(c.class, "rac").length;
    const toRac = outstanding.slice(0, Math.max(0, availableRac));
    const toWl = outstanding.slice(toRac.length);
    const availableWl = this.capacity(c.class, "wl") - this.activeInQueue(c.class, "wl").length;
    if (toWl.length > availableWl) {
      for (const p of allocated) this.releasePassenger(p);
      throw new Error(`Waitlist limit exceeded for ${c.class}; booking rejected atomically`);
    }
    this.assignRac(booking, toRac);
    toWl.forEach(p => { p.status = "WL"; this.queues[c.class].wl.push(p); });
    passengers.filter(p => p.status === "NO_BERTH").forEach(p => {});
    this.priceBooking(booking);
    booking.paid = passengers.reduce((n, p) => n + p.fare, 0);
    this.bookings.set(c.id, booking);
    passengers.forEach(p => this.passengerIds.add(p.id));
    return { type: "booked", bookingId: c.id, class: c.class, quota: c.quota, fare: booking.paid, passengers: this.passengerSnapshot(booking), promotions: [] };
  }

  allocateConfirmed(booking, candidates) {
    const ordered = [...candidates].sort((a, b) => Number(this.isPriority(b)) - Number(this.isPriority(a)) || a.order - b.order);
    const allocated = [];
    const classBerths = [...this.berths.values()].filter(b => b.classCode === booking.classCode);
    const quotaRemaining = this.train.classes[booking.classCode].quotas[booking.quota];
    const occupiedQuota = [...this.bookings.values()].filter(b => b.classCode === booking.classCode && b.quota === booking.quota)
      .flatMap(b => b.passengers).filter(p => p.status === "CNF").length;
    const quotaSlots = Math.max(0, quotaRemaining - occupiedQuota);
    for (const p of ordered) {
      if (allocated.length >= quotaSlots) continue;
      let options = classBerths.filter(b => this.berthFits(b, booking.trip));
      const preferred = String(p.berthPreference || "").toUpperCase();
      if (preferred) options.sort((a, b) => Number(b.type === preferred) - Number(a.type === preferred));
      if (this.isPriority(p)) options.sort((a, b) => Number(b.type === "LOWER") - Number(a.type === "LOWER"));
      // Prefer a coach already used by this booking, keeping family groups together where feasible.
      const familyCoaches = new Set(allocated.map(a => this.berths.get(a.berthId)?.coach));
      options.sort((a, b) => Number(familyCoaches.has(b.coach)) - Number(familyCoaches.has(a.coach)));
      const berth = options[0];
      if (!berth) continue;
      p.status = "CNF"; p.berthId = berth.id; berth.confirmed.push(p); booking.trip.segments.forEach(s => berth.occupied.add(s)); allocated.push(p);
    }
    return allocated;
  }

  assignRac(booking, people) {
    for (let i = 0; i < people.length; i += 1) {
      const p = people[i]; p.status = "RAC";
      const partner = this.activeInQueue(booking.classCode, "rac").find(q => q.racPair && q.racPair.open && q.racPair.members.every(member => {
        const used = this.trip(member.from, member.to).segments;
        return booking.trip.segments.every(s => !used.includes(s));
      }));
      if (partner) { p.racPair = partner.racPair; p.racPair.members.push(p); booking.trip.segments.forEach(s => p.racPair.occupied.add(s)); }
      else {
        const berth = [...this.berths.values()].find(b => b.classCode === booking.classCode && b.type === "SIDE_LOWER" && this.berthFits(b, booking.trip));
        const pair = { id: `RAC-${booking.classCode}-${booking.id}-${p.id}`, berthId: berth?.id ?? null, members: [p], occupied: new Set(booking.trip.segments), open: true };
        p.racPair = pair;
        if (berth) booking.trip.segments.forEach(s => berth.occupied.add(s));
      }
      this.queues[booking.classCode].rac.push(p);
    }
  }

  activeInQueue(classCode, type) { return this.queues[classCode][type].filter(p => p.status === type.toUpperCase()); }

  priceBooking(booking) {
    const spec = this.train.classes[booking.classCode];
    const rawDistance = this.train.route[booking.trip.end].distance - this.train.route[booking.trip.start].distance;
    const distance = ["3A", "2A"].includes(booking.classCode) ? Math.max(200, rawDistance) : rawDistance;
    const ac = ["3A", "2A"].includes(booking.classCode);
    const eligibleSenior = booking.passengers.some(p => p.status !== "CANCELLED" && ((p.gender === "M" && p.age >= 60) || (p.gender === "F" && p.age >= 58)));
    for (const p of booking.passengers) {
      if (p.age < 5 || p.status === "CANCELLED") { p.fare = 0; continue; }
      const base = spec.ratePerKm * distance;
      const tatkal = booking.quota === "TATKAL";
      let amount = base;
      if (tatkal) amount *= 1.3;
      else if (booking.quota === "SENIOR" && eligibleSenior) amount *= p.gender === "F" && p.age >= 58 ? 0.5 : p.gender === "M" && p.age >= 60 ? 0.6 : 1;
      amount += spec.reservationFee;
      if (ac) amount *= 1.05;
      p.fare = money(amount);
    }
  }

  releasePassenger(p) {
    if (p.status === "CNF" && p.berthId) {
      const berth = this.berths.get(p.berthId); const trip = this.trip(p.from, p.to);
      berth.confirmed = berth.confirmed.filter(x => x !== p);
      for (const s of trip.segments) if (!berth.confirmed.some(x => this.trip(x.from, x.to).segments.includes(s))) berth.occupied.delete(s);
    }
    if (p.status === "RAC" && p.racPair) this.detachRac(p);
    p.berthId = null; p.racPair = null;
  }

  detachRac(p) {
    const pair = p.racPair;
    if (!pair) return;
    const removedSegments = new Set(this.trip(p.from, p.to).segments);
    pair.members = pair.members.filter(member => member !== p);
    for (const segment of removedSegments) {
      if (!pair.members.some(member => this.trip(member.from, member.to).segments.includes(segment))) {
        pair.occupied.delete(segment);
        if (pair.berthId) this.berths.get(pair.berthId).occupied.delete(segment);
      }
    }
    if (pair.members.length === 0) pair.open = false;
    p.racPair = null;
  }

  cancel(c) {
    const booking = this.bookings.get(c.bookingId);
    if (!booking) throw new Error(`Unknown booking ${c.bookingId}`);
    const chosen = c.passengerIds ? new Set(c.passengerIds) : null;
    const targets = booking.passengers.filter(p => ACTIVE.has(p.status) && (!chosen || chosen.has(p.id)));
    if (chosen && [...chosen].some(id => !booking.passengers.some(p => p.id === id && ACTIVE.has(p.status)))) throw new Error("Cancellation includes an unknown or inactive passenger");
    const promotions = [];
    for (const p of targets) {
      const oldStatus = p.status;
      let refund = 0;
      if (oldStatus === "WL" || oldStatus === "RAC") refund = hoursBetween(this.train.departure, c.at) >= 0.5 ? Math.max(0, p.fare - 60) : 0;
      else if (booking.quota !== "TATKAL" && !this.chartPrepared) {
        const h = hoursBetween(this.train.departure, c.at), pct = h >= 48 ? 0.75 : h >= 12 ? 0.5 : h >= 4 ? 0.25 : 0;
        const minimumDeduction = this.train.classes[booking.classCode].minimumDeduction;
        refund = Math.max(0, p.fare - Math.max(minimumDeduction, money(p.fare * (1 - pct))));
      }
      this.releasePassenger(p); p.status = "CANCELLED"; p.refund += refund; booking.refunded += refund;
      if (oldStatus === "CNF") promotions.push(...this.promote(booking.classCode, c.at));
    }
    this.priceBooking(booking);
    booking.paid = booking.passengers.reduce((sum, p) => sum + p.fare, 0);
    booking.canceled = booking.passengers.every(p => !ACTIVE.has(p.status) && p.status !== "NO_BERTH");
    return { type: "cancelled", bookingId: booking.id, refunded: targets.reduce((s, p) => s + p.refund, 0), passengers: this.passengerSnapshot(booking), promotions };
  }

  promote(classCode, at) {
    const logs = [], spec = this.train.classes[classCode];
    let changed = true;
    while (changed) {
      changed = false;
      const rac = this.activeInQueue(classCode, "rac");
      for (const p of rac) {
        const booking = this.bookings.get(p.bookingId), trip = this.trip(p.from, p.to);
        const berth = [...this.berths.values()].find(b => b.classCode === classCode && this.berthFits(b, trip));
        if (!berth) continue;
        this.detachRac(p);
        p.status = "CNF"; p.berthId = berth.id; berth.confirmed.push(p); trip.segments.forEach(s => berth.occupied.add(s));
        logs.push({ passengerId: p.id, from: "RAC", to: "CNF", berthId: berth.id, reason: "earliest segment-compatible vacancy" });
        this.priceBooking(booking); changed = true; break;
      }
      if (changed) continue;
      if (this.activeInQueue(classCode, "rac").length < this.capacity(classCode, "rac")) {
        const wl = this.activeInQueue(classCode, "wl");
        if (wl.length) {
          const p = wl[0], booking = this.bookings.get(p.bookingId);
          p.status = "RAC"; p.racPair = { id: `RAC-${classCode}-${p.id}`, berthId: null, members: [p], occupied: new Set(), open: true };
          this.queues[classCode].rac.push(p); logs.push({ passengerId: p.id, from: "WL", to: "RAC", reason: "earliest eligible waitlisted passenger" });
          this.priceBooking(booking); changed = true;
        }
      }
    }
    return logs;
  }

  status(c) {
    const booking = this.bookings.get(c.bookingId);
    if (!booking) throw new Error(`Unknown booking ${c.bookingId}`);
    return { type: "status", bookingId: booking.id, passengers: this.passengerSnapshot(booking), fare: booking.paid, refunded: booking.refunded };
  }

  passengerSnapshot(booking) {
    return booking.passengers.map(p => ({ id: p.id, name: p.name, status: p.status, berthId: p.berthId, racPair: p.racPair?.id ?? null, fare: p.fare, refund: p.refund }));
  }

  prepareChart(c) {
    if (hoursBetween(this.train.departure, c.at) > 4) throw new Error("Chart preparation is allowed only within four hours of departure");
    if (this.chartPrepared) throw new Error("Chart already prepared");
    this.chartPrepared = true;
    const promotions = [];
    for (const p of this.activeInAll("RAC")) {
      const booking = this.bookings.get(p.bookingId), trip = this.trip(p.from, p.to);
      const berth = [...this.berths.values()].find(b => b.classCode === p.classCode && this.berthFits(b, trip));
      if (berth) {
        this.detachRac(p);
        p.status = "CNF"; p.berthId = berth.id; berth.confirmed.push(p); trip.segments.forEach(s => berth.occupied.add(s));
        promotions.push({ passengerId: p.id, from: "RAC", to: "CNF", berthId: berth.id, reason: "chart vacancy allocation" }); this.priceBooking(booking);
      }
    }
    for (const p of this.activeInAll("WL")) {
      const booking = this.bookings.get(p.bookingId); p.status = "CANCELLED"; p.refund = p.fare; booking.refunded += p.fare;
      promotions.push({ passengerId: p.id, from: "WL", to: "CANCELLED", reason: "automatic full refund after chart" });
    }
    return { type: "chart", promotions, chart: this.chart() };
  }

  activeInAll(status) { return Object.values(this.queues).flatMap(q => q[status.toLowerCase()]).filter(p => p.status === status); }
  chart() {
    return { coaches: [...new Set([...this.berths.values()].map(b => b.coach))].map(coach => ({ coach, berths: [...this.berths.values()].filter(b => b.coach === coach).map(b => ({ berthId: b.id, type: b.type, passengers: b.confirmed.filter(p => p.status === "CNF").map(p => ({ id: p.id, name: p.name, from: p.from, to: p.to })) })) })),
      finalWaitlist: this.activeInAll("WL").map(p => ({ id: p.id, bookingId: p.bookingId, from: p.from, to: p.to })) };
  }
}
