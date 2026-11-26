package com.sj03.reservation.engine;

import com.sj03.reservation.model.Allocation;
import com.sj03.reservation.model.Berth;
import com.sj03.reservation.model.BerthType;
import com.sj03.reservation.model.Booking;
import com.sj03.reservation.model.Passenger;
import com.sj03.reservation.model.PassengerStatus;
import com.sj03.reservation.model.RacPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Segment-aware berth scoring, family coach grouping, RAC pair planning, and fit-aware promotion logic.
 */
final class AllocationPlanner {

  private AllocationPlanner() {}

  record BerthAssignment(Passenger passenger, Berth berth) {}

  static final class PlannedRacGroup {
    final String classCode;
    final Berth berth;
    final RacPair existingPair;
    final List<Passenger> passengers = new ArrayList<>();
    long segmentMask;

    PlannedRacGroup(String classCode, Berth berth, RacPair existingPair, long initialMask) {
      this.classCode = classCode;
      this.berth = berth;
      this.existingPair = existingPair;
      this.segmentMask = initialMask;
    }

    int existingMemberCount() {
      return existingPair == null ? 0 : existingPair.members().size();
    }
  }

  /** Scores a candidate berth for CNF allocation, preserving SIDE_LOWER berths for RAC when alternatives exist. */
  static int scoreBerth(Berth berth, Passenger passenger, String familyCoach) {
    BerthType preferred = passenger.berthPreference();
    int score = 0;
    if (passenger.isPriorityLower()) {
      if (berth.type() == BerthType.LOWER && (preferred == null || preferred == BerthType.LOWER)) {
        score += 1000;
      } else if (preferred != null && berth.type() == preferred) {
        score += 800;
      } else if (berth.type() == BerthType.LOWER) {
        score += 600;
      }
    } else if (preferred != null && berth.type() == preferred) {
      score += 500;
    }
    if (familyCoach != null && berth.coachId().equals(familyCoach)) {
      score += 200;
    }
    if (berth.type() == BerthType.SIDE_LOWER && preferred != BerthType.SIDE_LOWER) {
      score -= 100;
    }
    return score;
  }

  static List<Berth> candidateBerths(
      List<Berth> classBerths,
      Passenger passenger,
      long segmentMask,
      String coachFilter,
      String familyCoach,
      Allocation ignoredAllocation) {
    List<Berth> fitting = new ArrayList<>();
    for (Berth berth : classBerths) {
      if (coachFilter != null && !berth.coachId().equals(coachFilter)) {
        continue;
      }
      boolean fits = ignoredAllocation != null && berth.id().equals(passenger.berthId())
          ? berth.fitsIgnoring(segmentMask, ignoredAllocation)
          : berth.fits(segmentMask);
      if (fits) {
        fitting.add(berth);
      }
    }
    fitting.sort(Comparator
        .comparingInt((Berth b) -> scoreBerth(b, passenger, familyCoach))
        .reversed()
        .thenComparing(Berth::id));
    return fitting;
  }

  record PlannedRacResult(List<PlannedRacGroup> groups) {}

  /** Distributes under-12 children evenly across adults (18+) on a candidate list. */
  static List<List<Passenger>> balanceFamilyGroups(List<Passenger> candidates) {
    List<Passenger> children = new ArrayList<>();
    List<Passenger> adults = new ArrayList<>();
    for (Passenger p : candidates) {
      if (p.isInfant()) {
        continue;
      }
      if (p.isChild()) {
        children.add(p);
      } else if (p.isAdult()) {
        adults.add(p);
      }
    }
    if (children.isEmpty()) {
      return List.of();
    }
    if (adults.isEmpty()) {
      List<List<Passenger>> childSingles = new ArrayList<>(children.size());
      for (Passenger child : children) {
        childSingles.add(List.of(child));
      }
      return childSingles;
    }

    Map<String, List<Passenger>> byAdult = new LinkedHashMap<>();
    Map<String, Integer> assignedCount = new HashMap<>();
    for (Passenger a : adults) {
      assignedCount.put(a.id(), 0);
    }
    for (Passenger child : children) {
      Passenger chosenAdult = adults.stream()
          .min(Comparator.comparingInt((Passenger a) -> assignedCount.get(a.id()))
              .thenComparingInt(Passenger::order))
          .orElse(null);
      if (chosenAdult == null) {
        continue;
      }
      assignedCount.put(chosenAdult.id(), assignedCount.get(chosenAdult.id()) + 1);
      byAdult.computeIfAbsent(chosenAdult.id(), k -> {
        List<Passenger> list = new ArrayList<>();
        list.add(chosenAdult);
        return list;
      }).add(child);
    }
    List<List<Passenger>> orderedGroups = new ArrayList<>(byAdult.values());
    orderedGroups.sort(Comparator
        .comparing((List<Passenger> g) -> g.stream().anyMatch(Passenger::isPriorityLower)).reversed()
        .thenComparingInt(g -> g.stream().mapToInt(Passenger::order).min().orElse(0)));
    return orderedGroups;
  }

  /** Attempts to confirm all passengers in a single coach without splitting them. */
  static List<BerthAssignment> planInCoach(
      List<Passenger> passengers,
      List<Berth> classBerths,
      String coachId,
      int remainingQuota,
      boolean allowOwnRacSelfUpgrade) {
    if (passengers.isEmpty() || passengers.size() > remainingQuota) {
      return null;
    }
    List<Passenger> ordered = new ArrayList<>(passengers);
    ordered.sort(Comparator
        .comparing(Passenger::isPriorityLower).reversed()
        .thenComparing((Passenger p) -> p.berthPreference() != null, Comparator.reverseOrder())
        .thenComparingInt(Passenger::order));

    Map<RacPair, Integer> remainingInPair = new HashMap<>();
    if (allowOwnRacSelfUpgrade) {
      for (Passenger p : ordered) {
        if (p.racPair() != null) {
          remainingInPair.putIfAbsent(p.racPair(), p.racPair().members().size());
        }
      }
    }

    Set<String> picked = new HashSet<>();
    List<BerthAssignment> plan = new ArrayList<>(ordered.size());
    for (Passenger p : ordered) {
      RacPair pair = p.racPair();
      Allocation ignored = null;
      if (allowOwnRacSelfUpgrade && pair != null
          && !picked.contains(pair.berthId())
          && remainingInPair.getOrDefault(pair, Integer.MAX_VALUE) == 1) {
        ignored = pair.allocation();
      }
      List<Berth> options = candidateBerths(
          classBerths, p, p.trip().segmentMask(), coachId, coachId, ignored);
      Berth chosen = null;
      for (Berth option : options) {
        if (!picked.contains(option.id())) {
          chosen = option;
          break;
        }
      }
      if (chosen == null) {
        return null;
      }
      picked.add(chosen.id());
      if (allowOwnRacSelfUpgrade && pair != null && !chosen.id().equals(pair.berthId())) {
        remainingInPair.computeIfPresent(pair, (k, v) -> v - 1);
      }
      plan.add(new BerthAssignment(p, chosen));
    }
    return plan;
  }

  /** Selects the highest-scoring single-coach plan across candidate coaches. */
  static List<BerthAssignment> bestCoachPlan(
      List<Passenger> passengers,
      List<Berth> classBerths,
      List<String> coachChoices,
      int remainingQuota,
      boolean allowOwnRacSelfUpgrade) {
    List<BerthAssignment> bestPlan = null;
    int bestScore = Integer.MIN_VALUE;
    for (String coachId : coachChoices) {
      List<BerthAssignment> plan = planInCoach(
          passengers, classBerths, coachId, remainingQuota, allowOwnRacSelfUpgrade);
      if (plan != null) {
        int totalScore = scorePlan(plan, coachId);
        if (bestPlan == null || totalScore > bestScore) {
          bestPlan = plan;
          bestScore = totalScore;
        }
      }
    }
    return bestPlan;
  }

  /**
   * Allocates CNF berths respecting quota limits, whole-party coach preference, and BR-03
   * child-with-adult non-splitting invariant.
   */
  static List<Passenger> allocateConfirmed(
      Booking booking,
      List<Passenger> candidates,
      int quotaSlots,
      List<Berth> classBerths,
      List<String> coachOrder,
      java.util.function.BiConsumer<Passenger, Berth> confirmApplier) {
    if (candidates.isEmpty() || quotaSlots <= 0) {
      return List.of();
    }
    List<Passenger> seatCandidates = new ArrayList<>();
    for (Passenger p : candidates) {
      if (!p.isInfant()) {
        seatCandidates.add(p);
      }
    }
    if (seatCandidates.isEmpty()) {
      return List.of();
    }

    List<Passenger> allocated = new ArrayList<>();
    // For multi-passenger bookings, try placing the entire booking in one coach, picking the highest-scoring coach.
    if (seatCandidates.size() > 1 && seatCandidates.size() <= quotaSlots) {
      List<BerthAssignment> bestPlan = bestCoachPlan(seatCandidates, classBerths, coachOrder, quotaSlots, false);
      if (bestPlan != null) {
        for (BerthAssignment item : bestPlan) {
          confirmApplier.accept(item.passenger(), item.berth());
          allocated.add(item.passenger());
        }
        return allocated;
      }
    }

    // Group each child (5..11) with an adult guardian (18+) balanced across adults on the booking.
    List<List<Passenger>> orderedGroups = balanceFamilyGroups(seatCandidates);

    Set<String> groupedIds = new HashSet<>();
    int remaining = quotaSlots;
    for (List<Passenger> group : orderedGroups) {
      for (Passenger member : group) {
        groupedIds.add(member.id());
      }
      if (group.size() > remaining) {
        continue;
      }
      List<BerthAssignment> bestGroupPlan = bestCoachPlan(group, classBerths, coachOrder, remaining, false);
      if (bestGroupPlan != null) {
        for (BerthAssignment item : bestGroupPlan) {
          confirmApplier.accept(item.passenger(), item.berth());
          allocated.add(item.passenger());
          remaining--;
        }
      }
    }

    List<Passenger> individuals = new ArrayList<>();
    for (Passenger p : seatCandidates) {
      if (!groupedIds.contains(p.id())) {
        individuals.add(p);
      }
    }
    individuals.sort(Comparator
        .comparing(Passenger::isPriorityLower).reversed()
        .thenComparing((Passenger p) -> p.berthPreference() != null, Comparator.reverseOrder())
        .thenComparingInt(Passenger::order));

    for (Passenger p : individuals) {
      if (remaining <= 0) {
        break;
      }
      List<Berth> options = candidateBerths(classBerths, p, p.trip().segmentMask(), null, null, null);
      if (!options.isEmpty()) {
        confirmApplier.accept(p, options.get(0));
        allocated.add(p);
        remaining--;
      }
    }
    return allocated;
  }

  private static int scorePlan(List<BerthAssignment> plan, String coachId) {
    int total = 0;
    for (BerthAssignment item : plan) {
      total += scoreBerth(item.berth(), item.passenger(), coachId);
    }
    return total;
  }

  /**
   * Plans RAC placement atomically without mutating live berths until WL capacity check succeeds.
   * Balances children across adults and keeps each adult+child family unit together in one coach.
   */
  static List<PlannedRacGroup> planRac(
      String classCode,
      List<Passenger> pending,
      int racRemaining,
      List<Berth> classBerths,
      List<String> coachOrder,
      List<RacPair> existingPairs,
      Map<String, Berth> berthById,
      Booking booking) {
    if (racRemaining <= 0 || pending.isEmpty()) {
      return List.of();
    }

    List<PlannedRacGroup> virtualPools = new ArrayList<>();
    for (RacPair pair : existingPairs) {
      if (pair.isOpen()) {
        virtualPools.add(new PlannedRacGroup(classCode, berthById.get(pair.berthId()), pair, pair.segmentMask()));
      }
    }

    List<List<Passenger>> familyUnits = balanceFamilyGroups(pending);
    Set<String> handledFamily = new HashSet<>();
    for (List<Passenger> group : familyUnits) {
      for (Passenger member : group) {
        handledFamily.add(member.id());
      }
    }

    List<PlannedRacGroup> plan = new ArrayList<>();
    Set<String> assigned = new HashSet<>();
    int seatsRemaining = racRemaining;

    for (List<Passenger> group : familyUnits) {
      if (group.size() > seatsRemaining) {
        continue;
      }
      if (group.size() <= 2) {
        String familyCoach = group.size() == 1 && group.get(0).isChild()
            ? relatedAdultCoach(booking, group.get(0))
            : null;
        if (group.size() == 1 && group.get(0).isChild() && familyCoach == null) {
          continue;
        }
        PlannedRacGroup target = findOrCreateRacSlot(
            classCode, group, virtualPools, classBerths, familyCoach);
        if (target != null) {
          if (!plan.contains(target)) {
            plan.add(target);
          }
          for (Passenger member : group) {
            assigned.add(member.id());
          }
          seatsRemaining -= group.size();
        }
      } else {
        // Multi-child single-adult unit (size > 2): place all pairs within one coach atomically.
        List<List<Passenger>> chunks = new ArrayList<>();
        for (int i = 0; i < group.size(); i += 2) {
          chunks.add(group.subList(i, Math.min(i + 2, group.size())));
        }
        for (String coachId : coachOrder) {
          List<PlannedRacGroup> snapshotPools = snapshotPools(virtualPools);
          List<PlannedRacGroup> groupTargets = new ArrayList<>();
          boolean allPlaced = true;
          for (List<Passenger> chunk : chunks) {
            PlannedRacGroup target = findOrCreateRacSlot(
                classCode, chunk, snapshotPools, classBerths, coachId);
            if (target == null) {
              allPlaced = false;
              break;
            }
            if (!groupTargets.contains(target)) {
              groupTargets.add(target);
            }
          }
          if (allPlaced) {
            commitPoolSnapshot(virtualPools, snapshotPools, plan, groupTargets);
            for (Passenger member : group) {
              assigned.add(member.id());
            }
            seatsRemaining -= group.size();
            break;
          }
        }
      }
    }

    List<Passenger> orderedPending = new ArrayList<>(pending);
    orderedPending.sort(Comparator.comparingInt(Passenger::order));
    for (Passenger p : orderedPending) {
      if (assigned.contains(p.id()) || seatsRemaining <= 0 || handledFamily.contains(p.id())) {
        continue;
      }
      PlannedRacGroup target = findOrCreateRacSlot(
          classCode, List.of(p), virtualPools, classBerths, null);
      if (target != null) {
        if (!plan.contains(target)) {
          plan.add(target);
        }
        assigned.add(p.id());
        seatsRemaining--;
      }
    }
    return plan;
  }

  private static List<PlannedRacGroup> snapshotPools(List<PlannedRacGroup> pools) {
    List<PlannedRacGroup> copy = new ArrayList<>(pools.size());
    for (PlannedRacGroup pool : pools) {
      PlannedRacGroup cloned = new PlannedRacGroup(pool.classCode, pool.berth, pool.existingPair, pool.segmentMask);
      cloned.passengers.addAll(pool.passengers);
      copy.add(cloned);
    }
    return copy;
  }

  private static void commitPoolSnapshot(
      List<PlannedRacGroup> targetPools,
      List<PlannedRacGroup> snapshotPools,
      List<PlannedRacGroup> plan,
      List<PlannedRacGroup> snapshotTargets) {
    for (int i = 0; i < targetPools.size(); i++) {
      PlannedRacGroup orig = targetPools.get(i);
      PlannedRacGroup snap = snapshotPools.get(i);
      orig.passengers.clear();
      orig.passengers.addAll(snap.passengers);
      orig.segmentMask = snap.segmentMask;
    }
    for (int i = targetPools.size(); i < snapshotPools.size(); i++) {
      PlannedRacGroup added = snapshotPools.get(i);
      targetPools.add(added);
      if (snapshotTargets.contains(added) && !plan.contains(added)) {
        plan.add(added);
      }
    }
    for (int i = 0; i < Math.min(targetPools.size(), snapshotPools.size()); i++) {
      if (snapshotTargets.contains(snapshotPools.get(i)) && !plan.contains(targetPools.get(i))) {
        plan.add(targetPools.get(i));
      }
    }
  }

  private static PlannedRacGroup findOrCreateRacSlot(
      String classCode,
      List<Passenger> people,
      List<PlannedRacGroup> virtualPools,
      List<Berth> classBerths,
      String familyCoach) {
    if (people.isEmpty() || people.size() > 2) {
      return null;
    }
    long combinedMask = 0L;
    for (Passenger p : people) {
      combinedMask |= p.trip().segmentMask();
    }

    for (PlannedRacGroup pool : virtualPools) {
      if (pool.existingMemberCount() + pool.passengers.size() + people.size() > 2) {
        continue;
      }
      if (familyCoach != null && !pool.berth.coachId().equals(familyCoach)) {
        continue;
      }
      Allocation ignored = pool.existingPair != null ? pool.existingPair.allocation() : null;
      if (!pool.berth.fitsIgnoring(combinedMask, ignored)) {
        continue;
      }
      if (conflictsWithVirtualNewPairs(pool.berth.id(), combinedMask, virtualPools, pool)) {
        continue;
      }
      pool.passengers.addAll(people);
      pool.segmentMask |= combinedMask;
      return pool;
    }

    for (Berth berth : classBerths) {
      if (berth.type() != BerthType.SIDE_LOWER) {
        continue;
      }
      if (familyCoach != null && !berth.coachId().equals(familyCoach)) {
        continue;
      }
      if (!berth.fits(combinedMask)) {
        continue;
      }
      if (conflictsWithVirtualNewPairs(berth.id(), combinedMask, virtualPools, null)) {
        continue;
      }
      PlannedRacGroup created = new PlannedRacGroup(classCode, berth, null, combinedMask);
      created.passengers.addAll(people);
      virtualPools.add(created);
      return created;
    }
    return null;
  }

  private static boolean conflictsWithVirtualNewPairs(
      String berthId, long mask, List<PlannedRacGroup> virtualPools, PlannedRacGroup self) {
    for (PlannedRacGroup pool : virtualPools) {
      if (pool == self || pool.existingPair != null) {
        continue;
      }
      if (pool.berth.id().equals(berthId) && (pool.segmentMask & mask) != 0L) {
        return true;
      }
    }
    return false;
  }

  /** Returns the coach of an active adult (18+) on the same booking if a child needs co-location. */
  static String relatedAdultCoach(Booking booking, Passenger passenger) {
    if (booking == null || !passenger.isChild()) {
      return null;
    }
    for (Passenger candidate : booking.passengers()) {
      if (candidate.isAdult() && !candidate.id().equals(passenger.id()) && candidate.isActive()
          && candidate.coachId() != null) {
        return candidate.coachId();
      }
    }
    return null;
  }

  /**
   * Checks whether promoting passenger alone from RAC to CNF respects BR-03 (never splitting an
   * under-12 child from their adult guardian across coaches or leaving child without guardian in coach).
   */
  static String requiredCoachForRacPromotion(Booking booking, Passenger passenger) {
    if (booking == null) {
      return null;
    }
    if (passenger.isChild()) {
      for (Passenger candidate : booking.passengers()) {
        if (candidate.isAdult() && candidate.isActive() && candidate.status() == PassengerStatus.CNF) {
          return candidate.coachId();
        }
      }
      // Guardian is not CNF yet -> child cannot promote ahead of guardian unless handled as a unit.
      return "__BLOCKED__";
    }
    if (passenger.isAdult()) {
      for (Passenger candidate : booking.passengers()) {
        if (candidate.isChild() && !candidate.isInfant() && candidate.isActive()
            && candidate.status() == PassengerStatus.RAC) {
          return candidate.coachId();
        }
      }
    }
    return null;
  }
}
