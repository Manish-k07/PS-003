package com.sj03.reservation.engine;

import com.sj03.reservation.model.Allocation;
import com.sj03.reservation.model.Berth;
import com.sj03.reservation.model.BerthType;
import com.sj03.reservation.model.Booking;
import com.sj03.reservation.model.ClassSpec;
import com.sj03.reservation.model.CoachSpec;
import com.sj03.reservation.model.Passenger;
import com.sj03.reservation.model.PassengerStatus;
import com.sj03.reservation.model.PromotionRecord;
import com.sj03.reservation.model.RacPair;
import com.sj03.reservation.model.Station;
import com.sj03.reservation.model.TrainDefinition;
import com.sj03.reservation.model.Trip;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, command-driven railway reservation engine implementing BR-01 through BR-12.
 */
public final class ReservationEngine {
  private final TrainDefinition train;
  private final Map<String, Integer> stationIndex = new HashMap<>();
  private final Map<String, Berth> berths = new LinkedHashMap<>();
  private final Map<String, List<Berth>> classBerths = new LinkedHashMap<>();
  private final Map<String, List<String>> classCoaches = new LinkedHashMap<>();
  private final Map<String, Booking> bookings = new LinkedHashMap<>();
  private final Set<String> passengerIds = new HashSet<>();
  private final Map<String, List<Passenger>> racQueues = new LinkedHashMap<>();
  private final Map<String, List<Passenger>> wlQueues = new LinkedHashMap<>();
  private final Map<String, List<RacPair>> racPairs = new LinkedHashMap<>();
  private final List<Map<String, Object>> events = new ArrayList<>();

  private boolean chartPrepared = false;
  private Instant lastCommandAt = null;
  private int racPairSequence = 0;

  public ReservationEngine(TrainDefinition train) {
    if (train == null) {
      throw new IllegalArgumentException("Train definition cannot be null");
    }
    this.train = train;
    List<Station> route = train.route();
    for (int i = 0; i < route.size(); i++) {
      stationIndex.put(route.get(i).name(), i);
    }
    int edgeCount = route.size() - 1;
    for (Map.Entry<String, ClassSpec> entry : train.classes().entrySet()) {
      String code = entry.getKey();
      ClassSpec spec = entry.getValue();
      List<Berth> berthsForClass = new ArrayList<>();
      List<String> coachesForClass = new ArrayList<>();
      for (CoachSpec coach : spec.coaches()) {
        coachesForClass.add(coach.id());
        List<BerthType> layouts = coach.berths();
        for (int i = 0; i < layouts.size(); i++) {
          String berthId = coach.id() + "-" + String.format("%02d", i + 1);
          Berth berth = new Berth(berthId, coach.id(), code, layouts.get(i), edgeCount);
          berths.put(berthId, berth);
          berthsForClass.add(berth);
        }
      }
      classBerths.put(code, List.copyOf(berthsForClass));
      classCoaches.put(code, List.copyOf(coachesForClass));
      racQueues.put(code, new ArrayList<>());
      wlQueues.put(code, new ArrayList<>());
      racPairs.put(code, new ArrayList<>());
    }
  }

  public TrainDefinition train() {
    return train;
  }

  public Map<String, Booking> bookings() {
    return Collections.unmodifiableMap(bookings);
  }

  public Map<String, Berth> berths() {
    return Collections.unmodifiableMap(berths);
  }

  public boolean isChartPrepared() {
    return chartPrepared;
  }

  public List<Map<String, Object>> events() {
    return Collections.unmodifiableList(events);
  }

  /** Executes a single parsed command map atomically and logs the resulting event. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> execute(Map<String, Object> command) {
    if (command == null || !(command.get("type") instanceof String type) || type.isBlank()) {
      throw new IllegalArgumentException("Command requires a type");
    }
    String atRaw = command.get("at") instanceof String s ? s : null;
    Instant at = TrainDefinition.parseIsoInstant(
        command.get("at"), "Every command requires a valid ISO timestamp with timezone");
    if (lastCommandAt != null && at.isBefore(lastCommandAt)) {
      throw new IllegalArgumentException("Commands must be in chronological order");
    }

    Map<String, Object> event = switch (type) {
      case "book" -> book(command, at, atRaw);
      case "cancel" -> cancel(command, at, atRaw);
      case "status" -> status(command);
      case "chart" -> prepareChart(at, atRaw);
      default -> throw new IllegalArgumentException("Unknown command type: " + type);
    };

    lastCommandAt = at;
    events.add(event);
    return event;
  }

  public Trip trip(String from, String to) {
    Integer start = stationIndex.get(from);
    Integer end = stationIndex.get(to);
    if (start == null || end == null || start >= end) {
      throw new IllegalArgumentException("Invalid journey " + from + " -> " + to);
    }
    return Trip.of(start, end);
  }

  public int berthCount(String classCode) {
    List<Berth> list = classBerths.get(classCode);
    return list == null ? 0 : list.size();
  }

  public int racCapacity(String classCode) {
    int count = berthCount(classCode);
    return count == 0 ? 0 : Math.max(1, (int) Math.floor(count * 0.10));
  }

  public int wlCapacity(String classCode) {
    int count = berthCount(classCode);
    return count == 0 ? 0 : Math.max(1, (int) Math.floor(count * 0.30));
  }

  public List<Passenger> activeInQueue(String classCode, PassengerStatus status) {
    List<Passenger> source = status == PassengerStatus.RAC ? racQueues.get(classCode) : wlQueues.get(classCode);
    if (source == null) {
      return List.of();
    }
    List<Passenger> active = new ArrayList<>();
    for (Passenger p : source) {
      if (p.status() == status) {
        active.add(p);
      }
    }
    return active;
  }

  private List<Passenger> activeInAll(PassengerStatus status) {
    List<Passenger> all = new ArrayList<>();
    for (String code : train.classes().keySet()) {
      all.addAll(activeInQueue(code, status));
    }
    return all;
  }

  private int confirmedQuotaCount(String classCode, String quota) {
    int count = 0;
    for (Booking b : bookings.values()) {
      if (b.classCode().equals(classCode) && b.quota().equals(quota)) {
        for (Passenger p : b.passengers()) {
          if (p.status() == PassengerStatus.CNF) {
            count++;
          }
        }
      }
    }
    return count;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> book(Map<String, Object> command, Instant at, String atRaw) {
    if (chartPrepared) {
      throw new IllegalArgumentException("Bookings are closed after chart preparation");
    }
    if (!(command.get("id") instanceof String bookingId) || bookingId.isBlank() || bookings.containsKey(bookingId)) {
      throw new IllegalArgumentException("Missing or duplicate booking id " + command.get("id"));
    }
    if (!(command.get("class") instanceof String classCode) || !train.classes().containsKey(classCode)) {
      throw new IllegalArgumentException("Unknown class " + command.get("class"));
    }
    ClassSpec spec = train.classes().get(classCode);
    if (!(command.get("quota") instanceof String quota) || !spec.quotas().containsKey(quota)) {
      throw new IllegalArgumentException("Unknown quota " + command.get("quota"));
    }
    if (!(command.get("passengers") instanceof List<?> rawPassengers)
        || rawPassengers.isEmpty() || rawPassengers.size() > 6) {
      throw new IllegalArgumentException("A booking must include 1-6 passengers");
    }
    String from = command.get("from") instanceof String s ? s : null;
    String to = command.get("to") instanceof String s ? s : null;
    Trip trip = trip(from, to);

    Duration untilDeparture = Duration.between(at, train.departure());
    if (untilDeparture.isNegative()) {
      throw new IllegalArgumentException("Bookings are closed after departure");
    }
    if ("TATKAL".equals(quota) && untilDeparture.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("TATKAL opens 24 hours before departure");
    }

    List<Passenger> passengers = new ArrayList<>(rawPassengers.size());
    Set<String> localPassengerIds = new HashSet<>();
    for (int order = 0; order < rawPassengers.size(); order++) {
      if (!(rawPassengers.get(order) instanceof Map<?, ?> rawP)) {
        throw new IllegalArgumentException("Passenger entry must be an object");
      }
      if (!(rawP.get("id") instanceof String pId) || pId.isBlank()
          || passengerIds.contains(pId) || !localPassengerIds.add(pId)) {
        throw new IllegalArgumentException("Missing or duplicate passenger id " + rawP.get("id"));
      }
      if (!(rawP.get("name") instanceof String pName) || pName.isBlank()) {
        throw new IllegalArgumentException("Missing passenger name for " + pId);
      }
      if (!(rawP.get("age") instanceof Number ageNum) || ageNum.doubleValue() != Math.floor(ageNum.doubleValue())
          || ageNum.intValue() < 0) {
        throw new IllegalArgumentException("Invalid age for " + pId);
      }
      int age = ageNum.intValue();
      String gender = rawP.get("gender") instanceof String g ? g : null;
      if (!"M".equals(gender) && !"F".equals(gender)) {
        throw new IllegalArgumentException("Passenger gender must be M or F");
      }
      BerthType pref = BerthType.parseOptionalPreference(
          rawP.get("berthPreference") instanceof String s ? s : null);
      PassengerStatus initialStatus = age < 5 ? PassengerStatus.NO_BERTH : PassengerStatus.PENDING;
      passengers.add(new Passenger(
          pId, bookingId, order, pName, age, gender, pref, from, to, trip, classCode, quota, initialStatus));
    }

    validateQuotaAndFamily(passengers, quota);

    Booking booking = new Booking(bookingId, classCode, quota, trip, from, to, atRaw, passengers);
    int quotaSlots = Math.max(0, spec.quotas().get(quota) - confirmedQuotaCount(classCode, quota));

    List<Passenger> pendingBeforeCnf = new ArrayList<>();
    for (Passenger p : passengers) {
      if (p.status() == PassengerStatus.PENDING) {
        pendingBeforeCnf.add(p);
      }
    }

    List<Passenger> confirmed = AllocationPlanner.allocateConfirmed(
        booking, pendingBeforeCnf, quotaSlots, classBerths.get(classCode), classCoaches.get(classCode),
        this::addConfirmed);

    List<Passenger> pendingAfterCnf = new ArrayList<>();
    for (Passenger p : passengers) {
      if (p.status() == PassengerStatus.PENDING) {
        pendingAfterCnf.add(p);
      }
    }

    int racRoom = racCapacity(classCode) - activeInQueue(classCode, PassengerStatus.RAC).size();
    List<AllocationPlanner.PlannedRacGroup> racPlan = AllocationPlanner.planRac(
        classCode, pendingAfterCnf, racRoom, classBerths.get(classCode), classCoaches.get(classCode),
        racPairs.get(classCode), berths, booking);

    Set<Passenger> racPeople = new HashSet<>();
    for (AllocationPlanner.PlannedRacGroup group : racPlan) {
      racPeople.addAll(group.passengers);
    }
    List<Passenger> waitlisted = new ArrayList<>();
    for (Passenger p : pendingAfterCnf) {
      if (!racPeople.contains(p)) {
        waitlisted.add(p);
      }
    }

    int wlRoom = wlCapacity(classCode) - activeInQueue(classCode, PassengerStatus.WL).size();
    if (waitlisted.size() > wlRoom) {
      for (Passenger p : confirmed) {
        releaseConfirmed(p);
      }
      throw new IllegalArgumentException(
          "Waitlist limit exceeded for " + classCode + "; booking rejected atomically");
    }

    applyRacPlan(classCode, racPlan);
    for (Passenger p : waitlisted) {
      p.setStatus(PassengerStatus.WL);
      wlQueues.get(classCode).add(p);
    }

    FareCalculator.reprice(train, booking);
    int totalPaid = 0;
    for (Passenger p : passengers) {
      p.setPaidFare(p.fare());
      totalPaid += p.fare();
    }
    booking.setTotalPaid(totalPaid);
    booking.setCurrentFare(totalPaid);
    bookings.put(bookingId, booking);
    for (Passenger p : passengers) {
      passengerIds.add(p.id());
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "booked");
    result.put("bookingId", bookingId);
    result.put("class", classCode);
    result.put("quota", quota);
    result.put("fare", booking.totalPaid());
    result.put("passengers", passengerSnapshots(booking));
    result.put("promotions", List.of());
    return result;
  }

  private void validateQuotaAndFamily(List<Passenger> passengers, String quota) {
    if ("LADIES".equals(quota) && passengers.stream().anyMatch(p -> !"F".equals(p.gender()))) {
      throw new IllegalArgumentException("LADIES quota requires an all-female booking");
    }
    if ("SENIOR".equals(quota) && passengers.stream().noneMatch(Passenger::isEligibleSenior)) {
      throw new IllegalArgumentException("SENIOR quota requires a man aged 60+ or woman aged 58+");
    }
    boolean hasChild = passengers.stream().anyMatch(Passenger::isChild);
    if (hasChild && passengers.stream().noneMatch(Passenger::isAdult)) {
      throw new IllegalArgumentException("Every passenger under 12 must travel with an adult aged 18 or older");
    }
  }

  private void addConfirmed(Passenger passenger, Berth berth) {
    Allocation allocation = new Allocation(Allocation.Type.CNF, List.of(passenger), passenger.trip().segmentMask());
    berth.addAllocation(allocation);
    passenger.setStatus(PassengerStatus.CNF);
    passenger.setBerthId(berth.id());
    passenger.setCoachId(berth.coachId());
    passenger.setAllocation(allocation);
  }

  private void releaseConfirmed(Passenger passenger) {
    if (passenger.allocation() != null && passenger.berthId() != null) {
      Berth berth = berths.get(passenger.berthId());
      if (berth != null) {
        berth.removeAllocation(passenger.allocation());
      }
    }
    passenger.setAllocation(null);
    passenger.setBerthId(null);
    passenger.setCoachId(null);
  }

  private void applyRacPlan(String classCode, List<AllocationPlanner.PlannedRacGroup> plan) {
    for (AllocationPlanner.PlannedRacGroup group : plan) {
      RacPair pair = group.existingPair;
      if (pair == null) {
        String pairId = "RAC-" + classCode + "-" + (++racPairSequence);
        pair = new RacPair(pairId, classCode, group.berth.id());
        Allocation allocation = new Allocation(Allocation.Type.RAC, List.of(), 0L);
        pair.setAllocation(allocation);
        group.berth.addAllocation(allocation);
        racPairs.get(classCode).add(pair);
      }
      for (Passenger p : group.passengers) {
        p.setStatus(PassengerStatus.RAC);
        p.setRacPair(pair);
        p.setBerthId(pair.berthId());
        p.setCoachId(berths.get(pair.berthId()).coachId());
        pair.members().add(p);
        racQueues.get(classCode).add(p);
      }
      refreshRacPair(pair);
    }
  }

  private void refreshRacPair(RacPair pair) {
    Berth berth = berths.get(pair.berthId());
    long newMask = 0L;
    for (Passenger member : pair.members()) {
      newMask |= member.trip().segmentMask();
    }
    berth.updateAllocationMask(pair.allocation(), newMask);
    pair.allocation().members().clear();
    pair.allocation().members().addAll(pair.members());
    pair.setSegmentMask(newMask);
    pair.setOpen(!pair.members().isEmpty() && pair.members().size() < 2);
  }

  private void detachRac(Passenger passenger) {
    RacPair pair = passenger.racPair();
    if (pair == null) {
      return;
    }
    pair.members().remove(passenger);
    passenger.setRacPair(null);
    passenger.setBerthId(null);
    passenger.setCoachId(null);
    if (pair.members().isEmpty()) {
      Berth berth = berths.get(pair.berthId());
      berth.removeAllocation(pair.allocation());
      pair.setSegmentMask(0L);
      pair.setOpen(false);
      racPairs.get(pair.classCode()).remove(pair);
    } else {
      refreshRacPair(pair);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> cancel(Map<String, Object> command, Instant at, String atRaw) {
    if (!(command.get("bookingId") instanceof String bookingId) || !bookings.containsKey(bookingId)) {
      throw new IllegalArgumentException("Unknown booking " + command.get("bookingId"));
    }
    Booking booking = bookings.get(bookingId);
    Set<String> selected = null;
    if (command.containsKey("passengerIds") && command.get("passengerIds") != null) {
      if (!(command.get("passengerIds") instanceof List<?> rawIds) || rawIds.isEmpty()) {
        throw new IllegalArgumentException("passengerIds must be a non-empty array when supplied");
      }
      selected = new HashSet<>();
      for (Object idObj : rawIds) {
        if (!(idObj instanceof String s)) {
          throw new IllegalArgumentException("passengerIds entries must be strings");
        }
        selected.add(s);
      }
    }

    List<Passenger> targets = new ArrayList<>();
    for (Passenger p : booking.passengers()) {
      if (p.isActive() && (selected == null || selected.contains(p.id()))) {
        targets.add(p);
      }
    }
    if (targets.isEmpty() || (selected != null && targets.size() != selected.size())) {
      throw new IllegalArgumentException("Cancellation includes an unknown or inactive passenger");
    }

    List<Passenger> remaining = new ArrayList<>();
    for (Passenger p : booking.passengers()) {
      if (p.isActive() && !targets.contains(p)) {
        remaining.add(p);
      }
    }
    boolean remainingHasChild = remaining.stream().anyMatch(Passenger::isChild);
    if (remainingHasChild && remaining.stream().noneMatch(Passenger::isAdult)) {
      throw new IllegalArgumentException("Cannot cancel the last adult while a child under 12 remains on the booking");
    }

    int refundBefore = booking.refunded();
    boolean releasedVacancy = false;

    for (Passenger p : targets) {
      PassengerStatus oldStatus = p.status();
      int refundAmount = FareCalculator.refundFor(train, p, booking, oldStatus, at, chartPrepared);
      p.addRefund(refundAmount);
      int delta = p.refund() - p.previousRefund();
      booking.addRefunded(delta);
      p.setPreviousRefund(p.refund());

      if (oldStatus == PassengerStatus.CNF) {
        releaseConfirmed(p);
        releasedVacancy = true;
      } else if (oldStatus == PassengerStatus.RAC) {
        detachRac(p);
        releasedVacancy = true;
      }
      p.setStatus(PassengerStatus.CANCELLED);
      p.setCancelledAt(atRaw);
    }

    FareCalculator.reprice(train, booking);
    booking.setCanceled(booking.passengers().stream().allMatch(p -> p.status() == PassengerStatus.CANCELLED));

    List<PromotionRecord> promotions = releasedVacancy
        ? promoteAfterCancellation(booking.classCode(), atRaw)
        : List.of();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "cancelled");
    result.put("bookingId", booking.id());
    result.put("refunded", booking.refunded() - refundBefore);
    result.put("currentFare", booking.currentFare());
    result.put("passengers", passengerSnapshots(booking));
    result.put("promotions", promotions.stream().map(PromotionRecord::toMap).toList());
    return result;
  }

  private int activeConfirmedCount(String classCode) {
    int count = 0;
    for (Booking b : bookings.values()) {
      if (b.classCode().equals(classCode)) {
        for (Passenger p : b.passengers()) {
          if (p.status() == PassengerStatus.CNF) {
            count++;
          }
        }
      }
    }
    return count;
  }

  private int remainingConfirmedCapacity(String classCode, String quota) {
    ClassSpec spec = train.classes().get(classCode);
    if (chartPrepared) {
      int totalClassQuota = spec.quotas().values().stream().mapToInt(Integer::intValue).sum();
      return Math.max(0, totalClassQuota - activeConfirmedCount(classCode));
    }
    return Math.max(0, spec.quotas().getOrDefault(quota, 0) - confirmedQuotaCount(classCode, quota));
  }

  private static Allocation ownRacAllocationIfDisjoint(Passenger p) {
    RacPair pair = p.racPair();
    if (pair == null) {
      return null;
    }
    long otherMask = 0L;
    for (Passenger member : pair.members()) {
      if (member != p) {
        otherMask |= member.trip().segmentMask();
      }
    }
    return (otherMask & p.trip().segmentMask()) == 0L ? pair.allocation() : null;
  }

  private List<PromotionRecord> promoteAfterCancellation(String classCode, String atRaw) {
    List<PromotionRecord> promotions = new ArrayList<>();

    // Phase 1: RAC -> CNF onto vacated full berths (pre-chart within passenger's quota and never
    // self-upgrading an open solo RAC pair; post-chart within merged class quota).
    List<Passenger> racSnapshot = activeInQueue(classCode, PassengerStatus.RAC);
    Set<String> promotedIds = new HashSet<>();
    for (Passenger p : racSnapshot) {
      if (promotedIds.contains(p.id()) || p.status() != PassengerStatus.RAC) {
        continue;
      }
      Booking b = bookings.get(p.bookingId());
      int remainingQuota = remainingConfirmedCapacity(classCode, b.quota());
      List<Passenger> familyRacUnit = racFamilyUnit(b, p);
      if (familyRacUnit != null) {
        if (familyRacUnit.size() <= remainingQuota
            && promoteRacFamilyUnit(classCode, b, familyRacUnit, atRaw, "earliest segment-compatible vacancy", chartPrepared, promotions)) {
          for (Passenger member : familyRacUnit) {
            promotedIds.add(member.id());
          }
        }
        continue;
      }
      if (remainingQuota <= 0) {
        continue;
      }
      String requiredCoach = AllocationPlanner.requiredCoachForRacPromotion(b, p);
      if ("__BLOCKED__".equals(requiredCoach)) {
        continue;
      }
      Allocation ignoredOwn = chartPrepared ? ownRacAllocationIfDisjoint(p) : null;
      List<Berth> options = AllocationPlanner.candidateBerths(
          classBerths.get(classCode), p, p.trip().segmentMask(), requiredCoach,
          AllocationPlanner.relatedAdultCoach(b, p), ignoredOwn);
      if (!options.isEmpty()) {
        Berth chosen = options.get(0);
        detachRac(p);
        addConfirmed(p, chosen);
        FareCalculator.reprice(train, b);
        promotedIds.add(p.id());
        promotions.add(new PromotionRecord(
            p.id(), "RAC", "CNF", chosen.id(), null, "earliest segment-compatible vacancy", atRaw));
      }
    }

    // Phase 2: WL -> RAC in queue order into open RAC pairs or vacant SIDE_LOWER berths.
    int racRoom = racCapacity(classCode) - activeInQueue(classCode, PassengerStatus.RAC).size();
    if (racRoom > 0) {
      for (List<Passenger> unit : waitlistUnits(classCode)) {
        if (unit.size() > racRoom) {
          continue;
        }
        Booking b = bookings.get(unit.get(0).bookingId());
        List<AllocationPlanner.PlannedRacGroup> racPlan = AllocationPlanner.planRac(
            classCode, unit, racRoom, classBerths.get(classCode), classCoaches.get(classCode),
            racPairs.get(classCode), berths, b);
        int plannedCount = racPlan.stream().mapToInt(g -> g.passengers.size()).sum();
        if (plannedCount != unit.size()) {
          continue;
        }
        applyRacPlan(classCode, racPlan);
        racRoom -= plannedCount;
        FareCalculator.reprice(train, b);
        for (Passenger p : unit) {
          promotions.add(new PromotionRecord(
              p.id(), "WL", "RAC", null, p.racPair().id(), "earliest compatible waitlisted passenger", atRaw));
        }
      }
    }

    // Phase 3: If RAC is completely empty and no SIDE_LOWER berth exists for WL->RAC, allow
    // quota-eligible WL units to claim vacant non-SIDE_LOWER berths directly so a cancelled berth is never stranded.
    if (activeInQueue(classCode, PassengerStatus.RAC).isEmpty()) {
      for (List<Passenger> unit : waitlistUnits(classCode)) {
        Booking b = bookings.get(unit.get(0).bookingId());
        int remainingQuota = remainingConfirmedCapacity(classCode, b.quota());
        if (unit.size() > remainingQuota) {
          continue;
        }
        String existingCoach = AllocationPlanner.relatedAdultCoach(b, unit.get(0));
        List<String> coachChoices = existingCoach != null ? List.of(existingCoach) : classCoaches.get(classCode);
        List<AllocationPlanner.BerthAssignment> plan = AllocationPlanner.bestCoachPlan(
            unit, classBerths.get(classCode), coachChoices, remainingQuota, false);
        if (plan == null) {
          continue;
        }
        for (AllocationPlanner.BerthAssignment item : plan) {
          addConfirmed(item.passenger(), item.berth());
          promotions.add(new PromotionRecord(
              item.passenger().id(), "WL", "CNF", item.berth().id(), null,
              "vacant berth with empty RAC queue", atRaw));
        }
        FareCalculator.reprice(train, b);
      }
    }

    return promotions;
  }

  private List<Passenger> racFamilyUnit(Booking booking, Passenger p) {
    if (booking == null) {
      return null;
    }
    boolean hasRacChild = booking.passengers().stream()
        .anyMatch(c -> c.isChild() && !c.isInfant() && c.status() == PassengerStatus.RAC);
    boolean hasRacAdult = booking.passengers().stream()
        .anyMatch(a -> a.isAdult() && a.status() == PassengerStatus.RAC);
    if (!hasRacChild || !hasRacAdult) {
      return null;
    }
    List<Passenger> unit = new ArrayList<>();
    for (Passenger member : booking.passengers()) {
      if (member.status() == PassengerStatus.RAC && (member.isAdult() || member.isChild())) {
        unit.add(member);
      }
    }
    return unit.size() > 1 ? unit : null;
  }

  private boolean promoteRacFamilyUnit(
      String classCode,
      Booking booking,
      List<Passenger> unit,
      String atRaw,
      String reason,
      boolean allowOwnRacSelfUpgrade,
      List<PromotionRecord> promotions) {
    List<AllocationPlanner.BerthAssignment> plan = AllocationPlanner.bestCoachPlan(
        unit, classBerths.get(classCode), classCoaches.get(classCode), unit.size(), allowOwnRacSelfUpgrade);
    if (plan != null) {
      for (AllocationPlanner.BerthAssignment item : plan) {
        detachRac(item.passenger());
        addConfirmed(item.passenger(), item.berth());
        promotions.add(new PromotionRecord(
            item.passenger().id(), "RAC", "CNF", item.berth().id(), null, reason, atRaw));
      }
      FareCalculator.reprice(train, booking);
      return true;
    }
    return false;
  }

  private List<List<Passenger>> waitlistUnits(String classCode) {
    List<Passenger> queue = activeInQueue(classCode, PassengerStatus.WL);
    Map<String, List<List<Passenger>>> bookingFamilyUnits = new HashMap<>();
    Set<String> used = new HashSet<>();
    List<List<Passenger>> units = new ArrayList<>();

    for (Passenger p : queue) {
      if (used.contains(p.id())) {
        continue;
      }
      Booking b = bookings.get(p.bookingId());
      List<List<Passenger>> balanced = bookingFamilyUnits.computeIfAbsent(b.id(), k -> {
        List<Passenger> wlMembers = new ArrayList<>();
        for (Passenger member : b.passengers()) {
          if (member.status() == PassengerStatus.WL && !member.isInfant()) {
            wlMembers.add(member);
          }
        }
        return AllocationPlanner.balanceFamilyGroups(wlMembers);
      });
      List<Passenger> matchedGroup = null;
      for (List<Passenger> group : balanced) {
        if (group.contains(p)) {
          matchedGroup = group;
          break;
        }
      }
      if (matchedGroup != null) {
        for (Passenger member : matchedGroup) {
          used.add(member.id());
        }
        units.add(matchedGroup);
      } else {
        used.add(p.id());
        units.add(List.of(p));
      }
    }
    return units;
  }

  private Map<String, Object> status(Map<String, Object> command) {
    if (!(command.get("bookingId") instanceof String bookingId) || !bookings.containsKey(bookingId)) {
      throw new IllegalArgumentException("Unknown booking " + command.get("bookingId"));
    }
    Booking b = bookings.get(bookingId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "status");
    result.put("bookingId", b.id());
    result.put("passengers", passengerSnapshots(b));
    result.put("totalPaid", b.totalPaid());
    result.put("currentFare", b.currentFare());
    result.put("refunded", b.refunded());
    return result;
  }

  private List<Map<String, Object>> passengerSnapshots(Booking booking) {
    List<Map<String, Object>> list = new ArrayList<>(booking.passengers().size());
    for (Passenger p : booking.passengers()) {
      Map<String, Object> snap = new LinkedHashMap<>();
      snap.put("id", p.id());
      snap.put("name", p.name());
      snap.put("status", p.status().name());
      snap.put("berthId", p.berthId());
      snap.put("coachId", p.coachId());
      snap.put("racPair", p.racPair() == null ? null : p.racPair().id());
      snap.put("fare", p.fare());
      snap.put("refund", p.refund());
      list.add(snap);
    }
    return list;
  }

  private Map<String, Object> prepareChart(Instant at, String atRaw) {
    Duration untilDeparture = Duration.between(at, train.departure());
    if (untilDeparture.compareTo(Duration.ofHours(4)) > 0) {
      throw new IllegalArgumentException("Chart preparation is allowed only within four hours of departure");
    }
    if (untilDeparture.isNegative()) {
      throw new IllegalArgumentException("Chart preparation must occur before departure");
    }
    if (chartPrepared) {
      throw new IllegalArgumentException("Chart already prepared");
    }

    List<PromotionRecord> promotions = new ArrayList<>();
    Map<String, Object> releasedQuota = new LinkedHashMap<>();

    for (Map.Entry<String, ClassSpec> entry : train.classes().entrySet()) {
      String classCode = entry.getKey();
      ClassSpec spec = entry.getValue();

      // Step 1: Snapshot unfilled quotas BEFORE chart promotions and compute total available slots.
      Map<String, Object> classRelease = new LinkedHashMap<>();
      int releasedToGeneral = 0;
      int unusedGeneral = 0;
      for (Map.Entry<String, Integer> qEntry : spec.quotas().entrySet()) {
        String quota = qEntry.getKey();
        int used = confirmedQuotaCount(classCode, quota);
        int unused = Math.max(0, qEntry.getValue() - used);
        classRelease.put(quota, unused);
        if ("GENERAL".equals(quota)) {
          unusedGeneral = unused;
        } else {
          releasedToGeneral += unused;
        }
      }
      classRelease.put("releasedToGeneral", releasedToGeneral);
      releasedQuota.put(classCode, classRelease);

      int totalClassQuota = spec.quotas().values().stream().mapToInt(Integer::intValue).sum();
      int totalActiveCnf = activeConfirmedCount(classCode);
      int availableSlots = Math.max(0, Math.min(unusedGeneral + releasedToGeneral, totalClassQuota - totalActiveCnf));

      // Step 2: Promote RAC -> CNF at chart (solo or segment-disjoint RAC members can upgrade onto their SIDE_LOWER berth).
      List<Passenger> racSnapshot = activeInQueue(classCode, PassengerStatus.RAC);
      Set<String> promotedRacIds = new HashSet<>();
      for (Passenger p : racSnapshot) {
        if (availableSlots <= 0) {
          break;
        }
        if (promotedRacIds.contains(p.id()) || p.status() != PassengerStatus.RAC) {
          continue;
        }
        Booking b = bookings.get(p.bookingId());
        List<Passenger> familyRacUnit = racFamilyUnit(b, p);
        if (familyRacUnit != null) {
          if (familyRacUnit.size() <= availableSlots
              && promoteRacFamilyUnit(classCode, b, familyRacUnit, null, "chart vacancy allocation", true, promotions)) {
            for (Passenger member : familyRacUnit) {
              promotedRacIds.add(member.id());
            }
            availableSlots -= familyRacUnit.size();
          }
          continue;
        }
        String requiredCoach = AllocationPlanner.requiredCoachForRacPromotion(b, p);
        if ("__BLOCKED__".equals(requiredCoach)) {
          continue;
        }
        Allocation ownUsableAllocation = ownRacAllocationIfDisjoint(p);
        List<Berth> options = AllocationPlanner.candidateBerths(
            classBerths.get(classCode), p, p.trip().segmentMask(), requiredCoach,
            AllocationPlanner.relatedAdultCoach(b, p), ownUsableAllocation);
        if (!options.isEmpty()) {
          Berth chosen = options.get(0);
          detachRac(p);
          addConfirmed(p, chosen);
          FareCalculator.reprice(train, b);
          promotedRacIds.add(p.id());
          availableSlots--;
          promotions.add(new PromotionRecord(
              p.id(), "RAC", "CNF", chosen.id(), null, "chart vacancy allocation", null));
        }
      }

      // Step 3: Allocate released quota slots to GENERAL waitlist first, then any remaining WL if slots persist.
      if (availableSlots > 0) {
        availableSlots = allocateChartWaitlist(classCode, availableSlots, true, promotions);
      }
      if (availableSlots > 0) {
        allocateChartWaitlist(classCode, availableSlots, false, promotions);
      }
    }

    this.chartPrepared = true;

    // Step 4: Auto-cancel any remaining WL passengers with full refund, and cancel orphaned NO_BERTH infants.
    List<Map<String, Object>> autoCancelledWaitlist = new ArrayList<>();
    for (Passenger p : activeInAll(PassengerStatus.WL)) {
      Booking b = bookings.get(p.bookingId());
      int refundBasis = p.paidFare() > 0 ? Math.min(p.fare(), p.paidFare()) : p.fare();
      int unrefundedPaid = Math.max(0, b.totalPaid() - b.refunded());
      int refund = Math.min(refundBasis, unrefundedPaid);
      p.setStatus(PassengerStatus.CANCELLED);
      p.setCancelledAt(atRaw);
      p.addRefund(refund);
      p.setPreviousRefund(p.refund());
      b.addRefunded(refund);

      Map<String, Object> record = new LinkedHashMap<>();
      record.put("id", p.id());
      record.put("bookingId", p.bookingId());
      record.put("from", p.from());
      record.put("to", p.to());
      record.put("refund", refund);
      autoCancelledWaitlist.add(record);

      promotions.add(new PromotionRecord(
          p.id(), "WL", "CANCELLED", null, null, "automatic full refund after chart", null));
    }

    for (Booking b : bookings.values()) {
      boolean hasTravelingAdult = b.passengers().stream()
          .anyMatch(p -> p.isActive() && p.isAdult());
      if (!hasTravelingAdult) {
        for (Passenger p : b.passengers()) {
          if (p.status() == PassengerStatus.NO_BERTH && p.isChild()) {
            p.setStatus(PassengerStatus.CANCELLED);
            p.setCancelledAt(atRaw);
            promotions.add(new PromotionRecord(
                p.id(), "NO_BERTH", "CANCELLED", null, null, "guardian ticket did not clear chart", null));
          }
        }
      }
      FareCalculator.reprice(train, b);
      b.setCanceled(b.passengers().stream().allMatch(p -> p.status() == PassengerStatus.CANCELLED));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "chart");
    result.put("releasedQuota", releasedQuota);
    result.put("promotions", promotions.stream().map(PromotionRecord::toMap).toList());
    result.put("autoCancelledWaitlist", autoCancelledWaitlist);
    result.put("chart", buildChart());
    return result;
  }

  private int allocateChartWaitlist(
      String classCode, int availableSlots, boolean generalOnly, List<PromotionRecord> promotions) {
    int remaining = availableSlots;
    for (List<Passenger> unit : waitlistUnits(classCode)) {
      if (remaining <= 0) {
        break;
      }
      if (generalOnly && unit.stream().anyMatch(p -> !"GENERAL".equals(p.quota()))) {
        continue;
      }
      if (unit.size() > remaining) {
        continue;
      }
      Booking b = bookings.get(unit.get(0).bookingId());
      String existingFamilyCoach = AllocationPlanner.relatedAdultCoach(b, unit.get(0));
      List<String> coachChoices = existingFamilyCoach != null
          ? List.of(existingFamilyCoach)
          : classCoaches.get(classCode);
      List<AllocationPlanner.BerthAssignment> plan = AllocationPlanner.bestCoachPlan(
          unit, classBerths.get(classCode), coachChoices, remaining, false);
      if (plan == null) {
        continue;
      }
      for (AllocationPlanner.BerthAssignment item : plan) {
        addConfirmed(item.passenger(), item.berth());
        promotions.add(new PromotionRecord(
            item.passenger().id(), "WL", "CNF", item.berth().id(), null,
            "GENERAL quota released at chart", null));
      }
      remaining -= plan.size();
      FareCalculator.reprice(train, b);
    }
    return remaining;
  }

  private Map<String, Object> buildChart() {
    List<Map<String, Object>> coachesList = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : classCoaches.entrySet()) {
      for (String coachId : entry.getValue()) {
        List<Map<String, Object>> berthsList = new ArrayList<>();
        for (Berth berth : classBerths.get(entry.getKey())) {
          if (!berth.coachId().equals(coachId)) {
            continue;
          }
          List<Map<String, Object>> confirmedList = new ArrayList<>();
          List<Map<String, Object>> racList = new ArrayList<>();
          for (Allocation alloc : berth.allocations()) {
            for (Passenger p : alloc.members()) {
              if (alloc.type() == Allocation.Type.CNF && p.status() == PassengerStatus.CNF) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id());
                m.put("name", p.name());
                m.put("from", p.from());
                m.put("to", p.to());
                confirmedList.add(m);
              } else if (alloc.type() == Allocation.Type.RAC && p.status() == PassengerStatus.RAC) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id());
                m.put("name", p.name());
                m.put("from", p.from());
                m.put("to", p.to());
                m.put("racPair", p.racPair() == null ? null : p.racPair().id());
                racList.add(m);
              }
            }
          }
          Map<String, Object> berthMap = new LinkedHashMap<>();
          berthMap.put("berthId", berth.id());
          berthMap.put("class", berth.classCode());
          berthMap.put("type", berth.type().name());
          berthMap.put("confirmed", confirmedList);
          berthMap.put("rac", racList);
          berthsList.add(berthMap);
        }
        Map<String, Object> coachMap = new LinkedHashMap<>();
        coachMap.put("coach", coachId);
        coachMap.put("berths", berthsList);
        coachesList.add(coachMap);
      }
    }

    List<Map<String, Object>> finalWaitlist = new ArrayList<>();
    for (Passenger p : activeInAll(PassengerStatus.WL)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", p.id());
      m.put("bookingId", p.bookingId());
      m.put("from", p.from());
      m.put("to", p.to());
      finalWaitlist.add(m);
    }

    List<Map<String, Object>> noBerthPassengers = new ArrayList<>();
    for (Booking b : bookings.values()) {
      for (Passenger p : b.passengers()) {
        if (p.status() == PassengerStatus.NO_BERTH) {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", p.id());
          m.put("bookingId", b.id());
          m.put("name", p.name());
          noBerthPassengers.add(m);
        }
      }
    }

    Map<String, Object> chartMap = new LinkedHashMap<>();
    chartMap.put("coaches", coachesList);
    chartMap.put("finalWaitlist", finalWaitlist);
    chartMap.put("noBerthPassengers", noBerthPassengers);
    return chartMap;
  }
}
