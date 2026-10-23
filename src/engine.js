const QUEUED = new Set(["RAC", "WL"]);
const money = value => Math.round(value);
const hoursUntil = (departure, at) => (Date.parse(departure) - Date.parse(at)) / 3_600_000;
const overlaps = (left, right) => left.some(segment => right.includes(segment));

export class ReservationEngine {
  constructor(train) {
    this.validateTrain(train);
    this.train = train;
    this.stationIndex = new Map(train.route.map((station, index) => [station.name, index]));
    this.berths = new Map();
    this.bookings = new Map();
    this.passengerIds = new Set();
    this.queues = Object.fromEntries(Object.keys(train.classes).map(code => [code, { rac: [], wl: [] }]));
    this.racPairs = Object.fromEntries(Object.keys(train.classes).map(code => [code, []]));
    this.events = [];
    this.chartPrepared = false;
    this.lastCommandAt = null;

    for (const [classCode, spec] of Object.entries(train.classes)) {
      for (const coach of spec.coaches) {
        coach.berths.forEach((type, index) => {
          const id = `${coach.id}-${String(index + 1).padStart(2, "02")}`;
          this.berths.set(id, { id, coach: coach.id, classCode, type, allocations: [], occupancy: new Uint8Array(train.route.length - 1) });
        });
      }
    }
  }

  validateTrain(train) {
    if (!train || !Array.isArray(train.route) || train.route.length !== 12) throw new Error("Train definition must contain exactly 12 route stations");
    if (Number.isNaN(Date.parse(train.departure))) throw new Error("Train departure must be a valid ISO timestamp");
    const names = new Set();
    let priorDistance = -1;
    for (const station of train.route) {
      if (!station.name || names.has(station.name) || !Number.isFinite(station.distance) || station.distance <= priorDistance) throw new Error("Route needs unique station names and strictly increasing distances");
      names.add(station.name);
      priorDistance = station.distance;
    }
    if (!train.classes || !Object.keys(train.classes).length) throw new Error("Train definition must have at least one class");
    const coachIds = new Set();
    for (const [code, spec] of Object.entries(train.classes)) {
      if (!spec.coaches?.length || !spec.quotas || !Object.keys(spec.quotas).length) throw new Error(`Class ${code} needs coaches and quota counts`);
      if (!Number.isFinite(spec.ratePerKm) || spec.ratePerKm <= 0 || !Number.isFinite(spec.reservationFee) || spec.reservationFee < 0) throw new Error(`Class ${code} needs a valid fare rate and reservation fee`);
      if (spec.fareSlabs) {
        let previousUpper = 0;
        spec.fareSlabs.forEach((slab, index) => {
          if (!Number.isFinite(slab.ratePerKm) || slab.ratePerKm <= 0 || (slab.upToKm !== null && (!Number.isFinite(slab.upToKm) || slab.upToKm <= previousUpper))) throw new Error(`Invalid distance fare slab for ${code}`);
          if (slab.upToKm === null && index !== spec.fareSlabs.length - 1) throw new Error(`Unbounded fare slab must be last for ${code}`);
          if (slab.upToKm !== null) previousUpper = slab.upToKm;
        });
        if (spec.fareSlabs.at(-1)?.upToKm !== null) throw new Error(`Distance fare slabs for ${code} need a final unbounded slab`);
      }
      for (const [quota, count] of Object.entries(spec.quotas)) if (!Number.isInteger(count) || count < 0) throw new Error(`Invalid ${quota} quota count for ${code}`);
      for (const coach of spec.coaches) {
        if (!coach.id || coachIds.has(coach.id) || !coach.berths?.length) throw new Error(`Invalid or duplicate coach ${coach.id}`);
        if (coach.berths.some(type => !["LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER"].includes(type))) throw new Error(`Unknown berth type in coach ${coach.id}`);
        coachIds.add(coach.id);
      }
    }
  }

  execute(command) {
    if (!command || typeof command !== "object" || !command.type) throw new Error("Command requires a type");
    if (typeof command.at !== "string" || !/(Z|[+-]\d{2}:\d{2})$/.test(command.at) || Number.isNaN(Date.parse(command.at))) {
      throw new Error("Every command requires a valid ISO timestamp with timezone");
    }
    if (this.lastCommandAt && Date.parse(command.at) < Date.parse(this.lastCommandAt)) throw new Error("Commands must be in chronological order");
    const handlers = { book: () => this.book(command), cancel: () => this.cancel(command), status: () => this.status(command), chart: () => this.prepareChart(command) };
    if (!handlers[command.type]) throw new Error(`Unknown command type: ${command.type}`);
    const event = handlers[command.type]();
    this.lastCommandAt = command.at;
    this.events.push(event);
    return event;
  }

  trip(from, to) {
    const start = this.stationIndex.get(from), end = this.stationIndex.get(to);
    if (start === undefined || end === undefined || start >= end) throw new Error(`Invalid journey ${from} → ${to}`);
    return { start, end, segments: Array.from({ length: end - start }, (_, index) => start + index) };
  }

  classBerths(classCode) { return [...this.berths.values()].filter(berth => berth.classCode === classCode); }
  berthCount(classCode) { return this.classBerths(classCode).length; }
  capacity(classCode, kind) {
    const count = this.berthCount(classCode), percent = kind === "rac" ? 0.1 : 0.3;
    return count ? Math.max(1, Math.floor(count * percent)) : 0;
  }
  isPriority(passenger) { return (passenger.gender === "M" && passenger.age >= 60) || (passenger.gender === "F" && passenger.age >= 45); }
  eligibleSenior(passenger) { return (passenger.gender === "M" && passenger.age >= 60) || (passenger.gender === "F" && passenger.age >= 58); }
  isInfant(passenger) { return passenger.age < 5; }
  isChild(passenger) { return passenger.age < 12; }
  isActive(passenger) { return passenger.status !== "CANCELLED"; }
  activeInQueue(classCode, type) { return this.queues[classCode][type].filter(passenger => passenger.status === type.toUpperCase()); }
  activeInAll(status) { return Object.values(this.queues).flatMap(queue => queue[status.toLowerCase()]).filter(passenger => passenger.status === status); }

  validateQuota(passengers, quota) {
    if (quota === "LADIES" && passengers.some(passenger => passenger.gender !== "F")) throw new Error("LADIES quota requires an all-female booking");
    if (quota === "SENIOR" && !passengers.some(passenger => this.eligibleSenior(passenger))) throw new Error("SENIOR quota requires a man aged 60+ or woman aged 58+");
    if (passengers.some(passenger => !["M", "F"].includes(passenger.gender))) throw new Error("Passenger gender must be M or F");
    const children = passengers.filter(passenger => this.isChild(passenger));
    if (children.length && !passengers.some(passenger => passenger.age >= 18)) throw new Error("Every passenger under 12 must travel with an adult aged 18 or older");
  }

  book(command) {
    if (this.chartPrepared) throw new Error("Bookings are closed after chart preparation");
    if (!command.id || this.bookings.has(command.id)) throw new Error(`Missing or duplicate booking id ${command.id ?? ""}`);
    const spec = this.train.classes[command.class];
    if (!spec) throw new Error(`Unknown class ${command.class}`);
    if (!Object.hasOwn(spec.quotas, command.quota)) throw new Error(`Unknown quota ${command.quota}`);
    if (!Array.isArray(command.passengers) || command.passengers.length < 1 || command.passengers.length > 6) throw new Error("A booking must include 1–6 passengers");
    const trip = this.trip(command.from, command.to);
    if (hoursUntil(this.train.departure, command.at) < 0) throw new Error("Bookings are closed after departure");
    if (command.quota === "TATKAL" && hoursUntil(this.train.departure, command.at) > 24) throw new Error("TATKAL opens 24 hours before departure");
    for (const passenger of command.passengers) {
      if (!passenger.id || this.passengerIds.has(passenger.id)) throw new Error(`Missing or duplicate passenger id ${passenger.id ?? ""}`);
      if (!Number.isInteger(passenger.age) || passenger.age < 0) throw new Error(`Invalid age for ${passenger.id}`);
    }
    this.validateQuota(command.passengers, command.quota);

    const passengers = command.passengers.map((passenger, order) => ({
      ...passenger,
      bookingId: command.id,
      order,
      from: command.from,
      to: command.to,
      classCode: command.class,
      quota: command.quota,
      status: this.isInfant(passenger) ? "NO_BERTH" : "PENDING",
      berthId: null,
      coachId: null,
      racPair: null,
      fare: 0,
      refund: 0
    }));
    const booking = {
      id: command.id, classCode: command.class, quota: command.quota, trip,
      from: command.from, to: command.to, at: command.at, passengers,
      totalPaid: 0, refunded: 0, currentFare: 0, canceled: false
    };

    const quotaSlots = Math.max(0, spec.quotas[command.quota] - this.confirmedQuotaCount(command.class, command.quota));
    const confirmed = this.allocateConfirmed(booking, passengers.filter(passenger => passenger.status === "PENDING"), quotaSlots);
    const pending = passengers.filter(passenger => passenger.status === "PENDING");
    const racPlan = this.planRac(booking, pending);
    const racPeople = new Set(racPlan.flatMap(plan => plan.passengers));
    const waitlisted = pending.filter(passenger => !racPeople.has(passenger));
    const wlRoom = this.capacity(command.class, "wl") - this.activeInQueue(command.class, "wl").length;
    if (waitlisted.length > wlRoom) {
      for (const passenger of confirmed) this.releaseConfirmed(passenger);
      throw new Error(`Waitlist limit exceeded for ${command.class}; booking rejected atomically`);
    }

    this.applyRacPlan(command.class, racPlan);
    waitlisted.forEach(passenger => { passenger.status = "WL"; this.queues[command.class].wl.push(passenger); });
    this.reprice(booking);
    booking.totalPaid = passengers.reduce((sum, passenger) => sum + passenger.fare, 0);
    booking.currentFare = booking.totalPaid;
    this.bookings.set(command.id, booking);
    passengers.forEach(passenger => this.passengerIds.add(passenger.id));
    return { type: "booked", bookingId: command.id, class: command.class, quota: command.quota, fare: booking.totalPaid, passengers: this.passengerSnapshot(booking), promotions: [] };
  }

  confirmedQuotaCount(classCode, quota) {
    return [...this.bookings.values()].filter(booking => booking.classCode === classCode && booking.quota === quota)
      .flatMap(booking => booking.passengers).filter(passenger => passenger.status === "CNF").length;
  }

  passengerSegments(passenger) { return this.trip(passenger.from, passenger.to).segments; }
  berthFits(berth, trip, ignorePassenger = null) {
    const ignoredPair = ignorePassenger?.racPair;
    const ignoredHere = ignoredPair?.berthId === berth.id && ignoredPair.members.includes(ignorePassenger);
    const remainingPairSegments = ignoredHere
      ? new Set(ignoredPair.members.filter(member => member !== ignorePassenger).flatMap(member => this.passengerSegments(member)))
      : null;
    return trip.segments.every(segment => {
      let count = berth.occupancy[segment] ?? 0;
      if (ignoredHere && ignoredPair.segments.includes(segment) && !remainingPairSegments.has(segment)) count -= 1;
      return count === 0;
    });
  }

  berthScore(berth, passenger, familyCoach = null) {
    const preferred = String(passenger.berthPreference || "").toUpperCase();
    let score = 0;
    if (this.isPriority(passenger) && berth.type === "LOWER") score += 100;
    else if (!this.isPriority(passenger) && preferred && berth.type === preferred) score += 50;
    if (familyCoach && berth.coach === familyCoach) score += 25;
    return score;
  }
  optionsFor(passenger, classCode, trip, coach = null, familyCoach = null) {
    return this.classBerths(classCode).filter(berth => (!coach || berth.coach === coach) && this.berthFits(berth, trip))
      .sort((a, b) => this.berthScore(b, passenger, familyCoach) - this.berthScore(a, passenger, familyCoach) || a.id.localeCompare(b.id));
  }
  addConfirmed(passenger, berth) {
    const allocation = { type: "CNF", members: [passenger], segments: this.passengerSegments(passenger) };
    berth.allocations.push(allocation);
    for (const segment of allocation.segments) berth.occupancy[segment] += 1;
    passenger.status = "CNF";
    passenger.berthId = berth.id;
    passenger.coachId = berth.coach;
    passenger.allocation = allocation;
  }

  planInCoach(passengers, booking, coachId, remainingQuota) {
    if (passengers.length > remainingQuota) return null;
    const picked = new Set(), plan = [];
    const ordered = [...passengers].sort((a, b) => Number(this.isPriority(b)) - Number(this.isPriority(a)) || a.order - b.order);
    for (const passenger of ordered) {
      const berth = this.optionsFor(passenger, booking.classCode, booking.trip, coachId).find(option => !picked.has(option.id));
      if (!berth) return null;
      picked.add(berth.id);
      plan.push({ passenger, berth });
    }
    return plan;
  }

  allocateConfirmed(booking, candidates, quotaSlots) {
    if (!candidates.length || quotaSlots <= 0) return [];
    let remaining = quotaSlots;
    const allocated = [];
    const seatCandidates = candidates.filter(passenger => !this.isInfant(passenger));
    // First try to seat the whole booking in one coach, which is the best family outcome.
    if (seatCandidates.length <= remaining) {
      for (const coach of this.classBerths(booking.classCode).map(berth => berth.coach).filter((id, index, all) => all.indexOf(id) === index)) {
        const plan = this.planInCoach(seatCandidates, booking, coach, remaining);
        if (plan) {
          for (const item of plan) { this.addConfirmed(item.passenger, item.berth); allocated.push(item.passenger); }
          return allocated;
        }
      }
    }

    // Reserve each under-12 child with an adult. If the pair cannot be seated in one coach,
    // neither is split; remaining unrelated adults can still be assigned independently.
    const children = seatCandidates.filter(passenger => this.isChild(passenger));
    const adults = seatCandidates.filter(passenger => passenger.age >= 18);
    const familyGroups = new Map();
    const childAdult = new Map();
    for (const child of children) {
      const adult = adults.find(candidate => ![...(familyGroups.get(candidate.id) || [])].includes(child));
      if (!adult) continue;
      childAdult.set(child.id, adult);
      if (!familyGroups.has(adult.id)) familyGroups.set(adult.id, [adult]);
      familyGroups.get(adult.id).push(child);
    }
    const grouped = new Set();
    const orderedGroups = [...familyGroups.values()].sort((a, b) => Number(b.some(this.isPriority.bind(this))) - Number(a.some(this.isPriority.bind(this))) || Math.min(...a.map(p => p.order)) - Math.min(...b.map(p => p.order)));
    for (const group of orderedGroups) {
      group.forEach(passenger => grouped.add(passenger.id));
      if (group.length > remaining) continue;
      let plan = null;
      for (const coach of this.classBerths(booking.classCode).map(berth => berth.coach).filter((id, index, all) => all.indexOf(id) === index)) {
        plan = this.planInCoach(group, booking, coach, remaining);
        if (plan) break;
      }
      if (plan) {
        for (const item of plan) { this.addConfirmed(item.passenger, item.berth); allocated.push(item.passenger); remaining -= 1; }
      }
    }

    const individual = seatCandidates.filter(passenger => !grouped.has(passenger.id))
      .sort((a, b) => Number(this.isPriority(b)) - Number(this.isPriority(a)) || a.order - b.order);
    for (const passenger of individual) {
      if (remaining <= 0) break;
      const berth = this.optionsFor(passenger, booking.classCode, booking.trip)[0];
      if (berth) { this.addConfirmed(passenger, berth); allocated.push(passenger); remaining -= 1; }
    }
    return allocated;
  }

  makeRacPlanForGroup(booking, people, virtualPairs, createdPairs) {
    if (!people.length || people.length > 2) return null;
    const child = people.find(person => this.isChild(person));
    const familyCoach = child && !people.some(person => person.age >= 18) ? this.relatedAdultCoach(child) : null;
    let pair = virtualPairs.find(candidate => candidate.classCode === booking.classCode && candidate.members.length + candidate.reserved < 2 &&
      (!familyCoach || this.berths.get(candidate.berthId)?.coach === familyCoach));
    if (!pair) {
      const candidateBerth = this.classBerths(booking.classCode).find(berth => berth.type === "SIDE_LOWER" && (!familyCoach || berth.coach === familyCoach) &&
        !virtualPairs.some(candidate => !candidate.existing && candidate.berthId === berth.id && overlaps(candidate.segments, people.flatMap(person => this.trip(person.from, person.to).segments))) &&
        people.every(person => this.berthFits(berth, this.trip(person.from, person.to))));
      if (!candidateBerth) return null;
      pair = { classCode: booking.classCode, berthId: candidateBerth.id, members: [], reserved: 0, existing: false, bookingId: booking.id, segments: [] };
      virtualPairs.push(pair);
      createdPairs.push(pair);
    }
    if (pair.members.length + pair.reserved + people.length > 2) return null;
    pair.reserved += people.length;
    pair.segments = [...new Set([...pair.segments, ...people.flatMap(person => this.trip(person.from, person.to).segments)])];
    return { pair, passengers: people };
  }

  planRac(booking, pending) {
    const classCode = booking.classCode, racRemaining = this.capacity(classCode, "rac") - this.activeInQueue(classCode, "rac").length;
    if (racRemaining <= 0) return [];
    const virtualPairs = this.racPairs[classCode].filter(pair => pair.open).map(pair => ({
      classCode, berthId: pair.berthId, members: [...pair.members], reserved: 0, existing: true, pair, segments: [...pair.segments]
    }));
    const createdPairs = [], plan = [], assigned = new Set();
    const children = pending.filter(passenger => this.isChild(passenger));
    const adults = pending.filter(passenger => passenger.age >= 18);
    const familyGroups = children.length && adults.length ? [[adults[0], ...children]] : [];
    familyGroups.sort((a, b) => Math.min(...a.map(p => p.order)) - Math.min(...b.map(p => p.order)));
    const handledFamily = new Set(familyGroups.flatMap(group => group.map(passenger => passenger.id)));
    let seatsRemaining = racRemaining;
    for (const group of familyGroups) {
      if (group.length > seatsRemaining) continue;
      const groupPlan = this.makeRacPlanForGroup(booking, group, virtualPairs, createdPairs);
      if (!groupPlan) continue;
      plan.push(groupPlan); group.forEach(passenger => assigned.add(passenger.id)); seatsRemaining -= group.length;
    }
    for (const passenger of [...pending].sort((a, b) => a.order - b.order)) {
      if (assigned.has(passenger.id) || seatsRemaining <= 0) continue;
      if (handledFamily.has(passenger.id)) continue;
      const groupPlan = this.makeRacPlanForGroup(booking, [passenger], virtualPairs, createdPairs);
      if (!groupPlan) continue;
      plan.push(groupPlan); assigned.add(passenger.id); seatsRemaining -= 1;
    }
    return plan;
  }

  applyRacPlan(classCode, plan) {
    const created = new Map();
    for (const group of plan) {
      let pair = group.pair.pair ?? created.get(group.pair);
      if (!pair) {
        const berth = this.berths.get(group.pair.berthId);
        pair = { id: `RAC-${classCode}-${group.passengers[0].bookingId}-${group.passengers[0].id}`, classCode, berthId: berth.id, members: [], segments: [], open: true };
        pair.allocation = { type: "RAC", members: pair.members, segments: [] };
        berth.allocations.push(pair.allocation);
        this.racPairs[classCode].push(pair);
        created.set(group.pair, pair);
      }
      for (const passenger of group.passengers) {
        passenger.status = "RAC";
        passenger.racPair = pair;
        passenger.berthId = pair.berthId;
        passenger.coachId = this.berths.get(pair.berthId).coach;
        pair.members.push(passenger);
        this.queues[classCode].rac.push(passenger);
      }
      this.refreshRacPair(pair);
    }
  }

  refreshRacPair(pair) {
    const berth = this.berths.get(pair.berthId);
    const oldSegments = new Set(pair.allocation.segments);
    const newSegments = new Set(pair.members.flatMap(passenger => this.passengerSegments(passenger)));
    for (const segment of oldSegments) if (!newSegments.has(segment)) berth.occupancy[segment] -= 1;
    for (const segment of newSegments) if (!oldSegments.has(segment)) berth.occupancy[segment] += 1;
    pair.segments = [...newSegments];
    pair.allocation.members = pair.members;
    pair.allocation.segments = pair.segments;
    pair.open = pair.members.length > 0 && pair.members.length < 2;
  }

  detachRac(passenger) {
    const pair = passenger.racPair;
    if (!pair) return;
    pair.members = pair.members.filter(member => member !== passenger);
    passenger.racPair = null;
    passenger.berthId = null;
    passenger.coachId = null;
    if (!pair.members.length) {
      const berth = this.berths.get(pair.berthId);
      berth.allocations = berth.allocations.filter(allocation => allocation !== pair.allocation);
      for (const segment of pair.allocation.segments) berth.occupancy[segment] -= 1;
      pair.open = false;
      this.racPairs[pair.classCode] = this.racPairs[pair.classCode].filter(candidate => candidate !== pair);
    } else this.refreshRacPair(pair);
  }

  releaseConfirmed(passenger) {
    if (passenger.allocation && passenger.berthId) {
      const berth = this.berths.get(passenger.berthId);
      berth.allocations = berth.allocations.filter(allocation => allocation !== passenger.allocation);
      for (const segment of passenger.allocation.segments) berth.occupancy[segment] -= 1;
    }
    passenger.allocation = null;
    passenger.berthId = null;
    passenger.coachId = null;
  }

  rateForDistance(spec, distance) {
    if (Array.isArray(spec.fareSlabs) && spec.fareSlabs.length) {
      const slab = spec.fareSlabs.find(item => item.upToKm === null || distance <= item.upToKm) ?? spec.fareSlabs.at(-1);
      return slab.ratePerKm;
    }
    return spec.ratePerKm;
  }

  reprice(booking) {
    const spec = this.train.classes[booking.classCode];
    const rawDistance = this.train.route[booking.trip.end].distance - this.train.route[booking.trip.start].distance;
    const ac = ["3A", "2A"].includes(booking.classCode);
    const distance = ac ? Math.max(200, rawDistance) : rawDistance;
    const rate = this.rateForDistance(spec, distance);
    const hasSenior = booking.passengers.some(passenger => this.isActive(passenger) && this.eligibleSenior(passenger));
    let currentFare = 0;
    for (const passenger of booking.passengers) {
      if (!this.isActive(passenger)) continue;
      if (this.isInfant(passenger)) { passenger.fare = 0; continue; }
      let base = rate * distance;
      if (booking.quota === "TATKAL") base *= 1.3;
      else if (booking.quota === "SENIOR" && hasSenior) base *= passenger.gender === "F" ? 0.5 : 0.6;
      let amount = base + spec.reservationFee;
      if (ac) amount *= 1.05;
      passenger.fare = money(amount);
      currentFare += passenger.fare;
    }
    booking.currentFare = currentFare;
    return currentFare;
  }

  refundFor(passenger, booking, oldStatus, at) {
    if (passenger.fare <= 0) return 0;
    if (oldStatus === "WL" || oldStatus === "RAC") return hoursUntil(this.train.departure, at) >= 0.5 ? Math.max(0, passenger.fare - 60) : 0;
    if (oldStatus !== "CNF" || booking.quota === "TATKAL" || this.chartPrepared) return 0;
    const hours = hoursUntil(this.train.departure, at);
    const refundRate = hours >= 48 ? 0.75 : hours >= 12 ? 0.5 : hours >= 4 ? 0.25 : 0;
    if (!refundRate) return 0;
    const classMinimum = this.train.classes[booking.classCode].minimumDeduction ?? 0;
    const deduction = Math.max(classMinimum, money(passenger.fare * (1 - refundRate)));
    return Math.max(0, passenger.fare - deduction);
  }

  cancel(command) {
    const booking = this.bookings.get(command.bookingId);
    if (!booking) throw new Error(`Unknown booking ${command.bookingId}`);
    if (command.passengerIds !== undefined && (!Array.isArray(command.passengerIds) || !command.passengerIds.length)) throw new Error("passengerIds must be a non-empty array when supplied");
    const selected = command.passengerIds ? new Set(command.passengerIds) : null;
    const targets = booking.passengers.filter(passenger => this.isActive(passenger) && (!selected || selected.has(passenger.id)));
    if (selected && [...selected].some(id => !targets.some(passenger => passenger.id === id))) throw new Error("Cancellation includes an unknown or inactive passenger");
    const remaining = booking.passengers.filter(passenger => this.isActive(passenger) && !targets.includes(passenger));
    for (const child of remaining.filter(passenger => this.isChild(passenger))) {
      if (!remaining.some(passenger => passenger.age >= 18)) throw new Error("Cannot cancel the last adult while a child under 12 remains on the booking");
    }
    const refundBefore = booking.refunded;
    const promotions = [];
    for (const passenger of targets) {
      const oldStatus = passenger.status;
      passenger.refund += this.refundFor(passenger, booking, oldStatus, command.at);
      booking.refunded += passenger.refund - (passenger.previousRefund ?? 0);
      passenger.previousRefund = passenger.refund;
      if (oldStatus === "CNF") this.releaseConfirmed(passenger);
      if (oldStatus === "RAC") this.detachRac(passenger);
      passenger.status = "CANCELLED";
      passenger.cancelledAt = command.at;
      if (oldStatus === "CNF" || oldStatus === "RAC") promotions.push(...this.promote(booking.classCode, command.at));
    }
    this.reprice(booking);
    booking.canceled = booking.passengers.every(passenger => passenger.status === "CANCELLED");
    return { type: "cancelled", bookingId: booking.id, refunded: booking.refunded - refundBefore, currentFare: booking.currentFare, passengers: this.passengerSnapshot(booking), promotions };
  }

  releasePassenger(passenger) {
    if (passenger.status === "CNF") this.releaseConfirmed(passenger);
    if (passenger.status === "RAC") this.detachRac(passenger);
  }

  findPromotionBerth(passenger) {
    const trip = this.trip(passenger.from, passenger.to);
    const options = this.classBerths(passenger.classCode).filter(berth => this.berthFits(berth, trip, passenger));
    const familyCoach = this.relatedAdultCoach(passenger);
    return options.sort((a, b) => this.berthScore(b, passenger, familyCoach) - this.berthScore(a, passenger, familyCoach) || a.id.localeCompare(b.id))[0] ?? null;
  }

  relatedAdultCoach(passenger) {
    if (!this.isChild(passenger)) return null;
    const booking = this.bookings.get(passenger.bookingId);
    return booking?.passengers.find(candidate => candidate.age >= 18 && candidate.id !== passenger.id && candidate.status !== "CANCELLED")?.coachId ?? null;
  }

  waitlistUnits(classCode) {
    const queue = this.activeInQueue(classCode, "wl"), used = new Set(), units = [];
    for (const passenger of queue) {
      if (used.has(passenger.id)) continue;
      const booking = this.bookings.get(passenger.bookingId);
      if (this.isChild(passenger) || passenger.age >= 18) {
        const adult = passenger.age >= 18 && passenger.status === "WL"
          ? passenger
          : booking.passengers.find(candidate => candidate.age >= 18 && candidate.status === "WL");
        const children = booking.passengers.filter(candidate => this.isChild(candidate) && candidate.status === "WL");
        if (adult && children.length && !used.has(adult.id)) {
          const group = [adult, ...children];
          group.forEach(member => used.add(member.id));
          units.push(group);
          continue;
        }
      }
      used.add(passenger.id); units.push([passenger]);
    }
    return units;
  }

  assignRacGroup(classCode, people) {
    const child = people.find(passenger => this.isChild(passenger));
    const familyCoach = child && !people.some(passenger => passenger.age >= 18) ? this.relatedAdultCoach(child) : null;
    const pair = this.racPairs[classCode].find(candidate => candidate.open && candidate.members.length + people.length <= 2 &&
      (!familyCoach || this.berths.get(candidate.berthId)?.coach === familyCoach));
    let selectedPair = pair;
    if (!selectedPair) {
      if (people.length > 2) return null;
      const berth = this.classBerths(classCode).find(candidate => candidate.type === "SIDE_LOWER" && (!familyCoach || candidate.coach === familyCoach) && people.every(person => this.berthFits(candidate, this.trip(person.from, person.to))));
      if (!berth) return null;
      selectedPair = { id: `RAC-${classCode}-${people[0].bookingId}-${people[0].id}`, classCode, berthId: berth.id, members: [], segments: [], open: true };
      selectedPair.allocation = { type: "RAC", members: selectedPair.members, segments: [] };
      berth.allocations.push(selectedPair.allocation);
      this.racPairs[classCode].push(selectedPair);
    }
    for (const passenger of people) {
      passenger.status = "RAC"; passenger.racPair = selectedPair; passenger.berthId = selectedPair.berthId;
      passenger.coachId = this.berths.get(selectedPair.berthId).coach;
      selectedPair.members.push(passenger); this.queues[classCode].rac.push(passenger);
    }
    this.refreshRacPair(selectedPair);
    return selectedPair;
  }

  promote(classCode, at) {
    const promotions = [];
    for (const passenger of this.activeInQueue(classCode, "rac")) {
      const berth = this.findPromotionBerth(passenger);
      if (!berth) continue;
      this.detachRac(passenger);
      passenger.status = "CNF";
      this.addConfirmed(passenger, berth);
      const booking = this.bookings.get(passenger.bookingId);
      this.reprice(booking);
      promotions.push({ passengerId: passenger.id, from: "RAC", to: "CNF", berthId: berth.id, reason: "earliest segment-compatible vacancy", at });
    }
    let racRoom = this.capacity(classCode, "rac") - this.activeInQueue(classCode, "rac").length;
    for (const unit of this.waitlistUnits(classCode)) {
      if (unit.length > racRoom) continue;
      const pair = this.assignRacGroup(classCode, unit);
      if (!pair) continue;
      racRoom -= unit.length;
      for (const passenger of unit) {
        this.reprice(this.bookings.get(passenger.bookingId));
        promotions.push({ passengerId: passenger.id, from: "WL", to: "RAC", racPair: pair.id, reason: "earliest compatible waitlisted passenger", at });
      }
    }
    return promotions;
  }

  status(command) {
    const booking = this.bookings.get(command.bookingId);
    if (!booking) throw new Error(`Unknown booking ${command.bookingId}`);
    return { type: "status", bookingId: booking.id, passengers: this.passengerSnapshot(booking), totalPaid: booking.totalPaid, currentFare: booking.currentFare, refunded: booking.refunded };
  }

  passengerSnapshot(booking) {
    return booking.passengers.map(passenger => ({
      id: passenger.id, name: passenger.name, status: passenger.status,
      berthId: passenger.berthId, coachId: passenger.coachId,
      racPair: passenger.racPair?.id ?? null, fare: passenger.fare, refund: passenger.refund
    }));
  }

  allocateGeneralWaitlist(classCode, availableQuotaSlots, promotions) {
    let remaining = availableQuotaSlots;
    const coaches = [...new Set(this.classBerths(classCode).map(berth => berth.coach))];
    for (const unit of this.waitlistUnits(classCode)) {
      if (unit.some(passenger => passenger.quota !== "GENERAL") || unit.length > remaining) continue;
      const booking = this.bookings.get(unit[0].bookingId);
      const child = unit.find(passenger => this.isChild(passenger));
      const existingFamilyCoach = child ? this.relatedAdultCoach(child) : null;
      const coachOptions = existingFamilyCoach ? [existingFamilyCoach] : coaches;
      let allocation = null;
      for (const coach of coachOptions) {
        allocation = this.planInCoach(unit, booking, coach, remaining);
        if (allocation) break;
      }
      if (!allocation) continue;
      for (const item of allocation) {
        this.addConfirmed(item.passenger, item.berth);
        promotions.push({ passengerId: item.passenger.id, from: "WL", to: "CNF", berthId: item.berth.id, reason: "GENERAL quota released at chart" });
      }
      remaining -= allocation.length;
      this.reprice(booking);
    }
    return remaining;
  }

  prepareChart(command) {
    if (hoursUntil(this.train.departure, command.at) > 4) throw new Error("Chart preparation is allowed only within four hours of departure");
    if (hoursUntil(this.train.departure, command.at) < 0) throw new Error("Chart preparation must occur before departure");
    if (this.chartPrepared) throw new Error("Chart already prepared");

    const promotions = [], releasedQuota = {};
    // RAC retains priority over newly released quota inventory.
    for (const passenger of this.activeInAll("RAC")) {
      const berth = this.findPromotionBerth(passenger);
      if (!berth) continue;
      this.detachRac(passenger);
      passenger.status = "CNF";
      this.addConfirmed(passenger, berth);
      this.reprice(this.bookings.get(passenger.bookingId));
      promotions.push({ passengerId: passenger.id, from: "RAC", to: "CNF", berthId: berth.id, reason: "chart vacancy allocation" });
    }

    for (const classCode of Object.keys(this.train.classes)) {
      const spec = this.train.classes[classCode];
      const usedByQuota = Object.fromEntries(Object.keys(spec.quotas).map(quota => [quota, this.confirmedQuotaCount(classCode, quota)]));
      const unused = Object.fromEntries(Object.entries(spec.quotas).map(([quota, count]) => [quota, Math.max(0, count - (usedByQuota[quota] ?? 0))]));
      const releasedToGeneral = Object.entries(unused).filter(([quota]) => quota !== "GENERAL").reduce((sum, [, count]) => sum + count, 0);
      releasedQuota[classCode] = { ...unused, releasedToGeneral };
      let generalSlots = Math.max(0, (spec.quotas.GENERAL ?? 0) - (usedByQuota.GENERAL ?? 0)) + releasedToGeneral;
      if (generalSlots > 0) generalSlots = this.allocateGeneralWaitlist(classCode, generalSlots, promotions);
    }

    this.chartPrepared = true;
    const autoCancelledWaitlist = [];
    for (const passenger of this.activeInAll("WL")) {
      const booking = this.bookings.get(passenger.bookingId);
      passenger.status = "CANCELLED";
      passenger.cancelledAt = command.at;
      passenger.refund += passenger.fare;
      booking.refunded += passenger.fare;
      autoCancelledWaitlist.push({ id: passenger.id, bookingId: passenger.bookingId, from: passenger.from, to: passenger.to, refund: passenger.fare });
      promotions.push({ passengerId: passenger.id, from: "WL", to: "CANCELLED", reason: "automatic full refund after chart" });
    }
    for (const booking of this.bookings.values()) {
      const hasTravelingAdult = booking.passengers.some(passenger => passenger.status !== "CANCELLED" && passenger.age >= 18);
      if (hasTravelingAdult) continue;
      for (const passenger of booking.passengers.filter(candidate => candidate.status === "NO_BERTH" && this.isChild(candidate))) {
        passenger.status = "CANCELLED";
        passenger.cancelledAt = command.at;
        promotions.push({ passengerId: passenger.id, from: "NO_BERTH", to: "CANCELLED", reason: "guardian ticket did not clear chart" });
      }
    }
    for (const booking of this.bookings.values()) this.reprice(booking);
    return { type: "chart", releasedQuota, promotions, autoCancelledWaitlist, chart: this.chart() };
  }

  chart() {
    const coaches = [...new Set([...this.berths.values()].map(berth => berth.coach))];
    return {
      coaches: coaches.map(coach => ({
        coach,
        berths: [...this.berths.values()].filter(berth => berth.coach === coach).map(berth => ({
          berthId: berth.id,
          class: berth.classCode,
          type: berth.type,
          confirmed: berth.allocations.filter(allocation => allocation.type === "CNF").flatMap(allocation => allocation.members.filter(passenger => passenger.status === "CNF").map(passenger => ({ id: passenger.id, name: passenger.name, from: passenger.from, to: passenger.to }))),
          rac: berth.allocations.filter(allocation => allocation.type === "RAC").flatMap(allocation => allocation.members.filter(passenger => passenger.status === "RAC").map(passenger => ({ id: passenger.id, name: passenger.name, from: passenger.from, to: passenger.to, racPair: passenger.racPair?.id })))
        }))
      })),
      finalWaitlist: this.activeInAll("WL").map(passenger => ({ id: passenger.id, bookingId: passenger.bookingId, from: passenger.from, to: passenger.to })),
      noBerthPassengers: [...this.bookings.values()].flatMap(booking => booking.passengers.filter(passenger => passenger.status === "NO_BERTH").map(passenger => ({ id: passenger.id, bookingId: booking.id, name: passenger.name })))
    };
  }
}

