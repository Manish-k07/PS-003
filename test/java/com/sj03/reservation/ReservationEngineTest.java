package com.sj03.reservation;

import com.sj03.reservation.engine.ReservationEngine;
import com.sj03.reservation.json.JsonParser;
import com.sj03.reservation.json.JsonWriter;
import com.sj03.reservation.model.Allocation;
import com.sj03.reservation.model.Berth;
import com.sj03.reservation.model.Booking;
import com.sj03.reservation.model.Passenger;
import com.sj03.reservation.model.PassengerStatus;
import com.sj03.reservation.model.Station;
import com.sj03.reservation.model.TrainDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Dependency-free Java 17+ test suite covering BR-01..BR-12, all 7 edge cases, prototype bug fixes,
 * and a 120-booking + 25-cancellation 6-day simulation.
 */
public final class ReservationEngineTest {
  private static int passed = 0;
  private static int failed = 0;

  private static Map<String, Object> baseTrainMap;

  public static void main(String[] args) throws Exception {
    String trainJson = Files.readString(Path.of("data/train.json"), StandardCharsets.UTF_8);
    baseTrainMap = JsonParser.parseObject(trainJson);

    run("adjacent journeys reuse a berth while overlapping journeys do not (BR-01)",
        ReservationEngineTest::testAdjacentSegmentReuse);
    run("eligible senior gets lower berth priority before younger passenger (BR-02)",
        ReservationEngineTest::testLowerBerthPriority);
    run("CNF allocation preserves SIDE_LOWER berths for RAC when other berths exist (BR-02/BR-07)",
        ReservationEngineTest::testSideLowerPreservedForRac);
    run("child under 12 and adult guardian stay in the same coach and never split (BR-03)",
        ReservationEngineTest::testFamilyCoachAndNoSplit);
    run("two adults and two children split cleanly across two coaches when single coach lacks 4 berths (BR-03)",
        ReservationEngineTest::testTwoAdultsTwoChildrenBalanced);
    run("LADIES quota rejects male passenger and unused converts at chart (BR-04)",
        ReservationEngineTest::testLadiesQuota);
    run("SENIOR quota requires 60+ M / 58+ F and partial cancellation reverts companion to GENERAL (BR-05/BR-12)",
        ReservationEngineTest::testSeniorQuotaAndPartialReprice);
    run("TATKAL enforces 24h window, 30% premium, no senior concession, zero CNF refund (BR-06)",
        ReservationEngineTest::testTatkalRulesAndSeniorOnTatkal);
    run("AC 200 km minimum distance, slabs, reservation fee, and 5% GST on AC only (BR-10)",
        ReservationEngineTest::testFareSlabsAndAcMinimum);
    run("cancellation refund bands, minimum deduction, and RAC/WL Rs 60 up to 30m before departure (BR-11)",
        ReservationEngineTest::testRefundBands);
    run("child under 5 has NO_BERTH, zero fare, blocks last-adult cancel, cancels if guardian stays WL",
        ReservationEngineTest::testChildUnderFive);
    run("booking from last station or backward journey is rejected",
        ReservationEngineTest::testLastStationRejected);
    run("waitlist limit accepts exact capacity and rejects excess atomically (BR-08)",
        ReservationEngineTest::testExactWaitlistLimitAtomic);
    run("RAC pair where one cancels keeps survivor RAC and admits WL passenger into freed slot",
        ReservationEngineTest::testRacPairOneCancelsPromotesWl);
    run("open RAC pair checks CNF segment conflicts on the same SIDE_LOWER berth",
        ReservationEngineTest::testRacPairDoesNotConflictWithCnfOnSameBerth);
    run("fit-aware promotion skips long WL trip and promotes later shorter trip (BR-09)",
        ReservationEngineTest::testFitAwarePromotionSkipsLongTrip);
    run("chart quota math prevents double counting RAC promotions and promotes solo/family RAC",
        ReservationEngineTest::testChartQuotaMathAndFamilyRac);
    run("chart auto-cancels remaining WL with full refund and enforces 4h time gate",
        ReservationEngineTest::testChartAutoCancelAndTimeGate);
    run("chronological timestamp ordering rejects out-of-order commands",
        ReservationEngineTest::testChronologicalEnforcement);
    run("sample data/commands.json executes cleanly end-to-end",
        ReservationEngineTest::testSampleCommandsEndToEnd);
    run("senior partial cancel + companion cancel/chart WL keeps refunds bounded by paidFare (BR-12)",
        ReservationEngineTest::testSeniorPartialCancelRefundBounds);
    run("multi-child family RAC/WL never splits child from adult across coach or status (BR-03)",
        ReservationEngineTest::testMultiChildFamilyRacAndBalancedWl);
    run("disjoint RAC pair upgrades at chart and post-chart cancel respects merged quota",
        ReservationEngineTest::testDisjointRacChartAndPostChartCancel);
    run("sub-second Tatkal gate, preference ordering, and deterministic chart coach order",
        ReservationEngineTest::testSubSecondTatkalAndDeterministicOrder);
    run("120-booking and 25-cancellation 6-day simulation preserves all reservation invariants",
        ReservationEngineTest::testSixDayScaleSimulation);

    System.out.println("Tests passed: " + passed + ", failed: " + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  @FunctionalInterface
  interface TestCase {
    void execute() throws Exception;
  }

  private static void run(String name, TestCase test) {
    try {
      test.execute();
      passed++;
      System.out.println("[PASS] " + name);
    } catch (Throwable t) {
      failed++;
      System.err.println("[FAIL] " + name + " -> " + t.getMessage());
      t.printStackTrace(System.err);
    }
  }

  @SuppressWarnings("unchecked")
  private static ReservationEngine defaultEngine() {
    Map<String, Object> copy = deepCopyMap(baseTrainMap);
    Map<String, Object> classes = (Map<String, Object>) copy.get("classes");
    Map<String, Object> sl = (Map<String, Object>) classes.get("SL");
    ((Map<String, Object>) sl.get("quotas")).put("GENERAL", 10L);
    return new ReservationEngine(TrainDefinition.fromMap(copy));
  }

  @SuppressWarnings("unchecked")
  private static ReservationEngine engineWithSl(List<String> berths, Map<String, Integer> quotas) {
    Map<String, Object> copy = deepCopyMap(baseTrainMap);
    Map<String, Object> classes = (Map<String, Object>) copy.get("classes");
    Map<String, Object> sl = (Map<String, Object>) classes.get("SL");
    Map<String, Object> coach = new LinkedHashMap<>();
    coach.put("id", "T1");
    coach.put("berths", new ArrayList<>(berths));
    sl.put("coaches", List.of(coach));
    Map<String, Object> qMap = new LinkedHashMap<>();
    quotas.forEach((k, v) -> qMap.put(k, Long.valueOf(v)));
    sl.put("quotas", qMap);
    return new ReservationEngine(TrainDefinition.fromMap(copy));
  }

  private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
    return JsonParser.parseObject(JsonWriter.stringify(source));
  }

  private static Map<String, Object> passenger(String id, int age, String gender, String pref) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", id);
    p.put("name", id);
    p.put("age", (long) age);
    p.put("gender", gender);
    if (pref != null) {
      p.put("berthPreference", pref);
    }
    return p;
  }

  private static Map<String, Object> bookCmd(
      String id, String at, String classCode, String from, String to, String quota, List<Map<String, Object>> pax) {
    Map<String, Object> cmd = new LinkedHashMap<>();
    cmd.put("type", "book");
    cmd.put("id", id);
    cmd.put("at", at);
    cmd.put("class", classCode);
    cmd.put("from", from);
    cmd.put("to", to);
    cmd.put("quota", quota);
    cmd.put("passengers", pax);
    return cmd;
  }

  private static Map<String, Object> cancelCmd(String at, String bookingId, List<String> paxIds) {
    Map<String, Object> cmd = new LinkedHashMap<>();
    cmd.put("type", "cancel");
    cmd.put("at", at);
    cmd.put("bookingId", bookingId);
    if (paxIds != null) {
      cmd.put("passengerIds", paxIds);
    }
    return cmd;
  }

  private static void assertEquals(Object expected, Object actual, String message) {
    if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
      throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }

  private static void assertTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static void assertThrows(Runnable action, String expectedSubstring) {
    try {
      action.run();
    } catch (Exception e) {
      if (expectedSubstring == null || e.getMessage().contains(expectedSubstring)) {
        return;
      }
      throw new AssertionError("Expected exception containing '" + expectedSubstring + "' but got: " + e.getMessage());
    }
    throw new AssertionError("Expected exception containing '" + expectedSubstring + "' but none was thrown");
  }

  private static void testAdjacentSegmentReuse() {
    ReservationEngine e = defaultEngine();
    String at = "2026-11-01T09:00:00+05:30";
    e.execute(bookCmd("B1", at, "SL", "Central", "Northgate", "GENERAL", List.of(passenger("P1", 30, "M", null))));
    e.execute(bookCmd("B2", at, "SL", "Northgate", "Lakeview", "GENERAL", List.of(passenger("P2", 30, "M", null))));
    Passenger p1 = e.bookings().get("B1").passengers().get(0);
    Passenger p2 = e.bookings().get("B2").passengers().get(0);
    assertEquals(p1.berthId(), p2.berthId(), "A->B and B->C must reuse same berth");

    e.execute(bookCmd("B3", at, "SL", "Central", "Lakeview", "GENERAL", List.of(passenger("P3", 30, "M", null))));
    Passenger p3 = e.bookings().get("B3").passengers().get(0);
    assertTrue(!p3.berthId().equals(p1.berthId()), "Overlapping A->C must not share berth with A->B");
  }

  private static void testLowerBerthPriority() {
    ReservationEngine e = engineWithSl(
        List.of("UPPER", "LOWER"),
        Map.of("GENERAL", 2, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    e.execute(bookCmd("PREF", "2026-11-01T09:00:00+05:30", "SL", "Central", "Lakeview", "GENERAL",
        List.of(passenger("OLDER", 60, "M", null), passenger("YOUNGER", 30, "M", "LOWER"))));
    List<Passenger> pax = e.bookings().get("PREF").passengers();
    assertEquals("T1-02", pax.get(0).berthId(), "60+ male gets priority LOWER berth");
    assertEquals("T1-01", pax.get(1).berthId(), "Younger passenger gets remaining UPPER berth");
  }

  private static void testSideLowerPreservedForRac() {
    ReservationEngine e = new ReservationEngine(TrainDefinition.fromMap(baseTrainMap));
    List<Map<String, Object>> sixPax = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      sixPax.add(passenger("P" + i, 30, "M", null));
    }
    e.execute(bookCmd("B1", "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL", sixPax));
    for (Passenger p : e.bookings().get("B1").passengers()) {
      assertTrue(!e.berths().get(p.berthId()).type().name().equals("SIDE_LOWER"),
          "CNF should avoid SIDE_LOWER while regular berths remain in S1");
    }
  }

  @SuppressWarnings("unchecked")
  private static void testFamilyCoachAndNoSplit() {
    Map<String, Object> copy = deepCopyMap(baseTrainMap);
    Map<String, Object> sl = (Map<String, Object>) ((Map<String, Object>) copy.get("classes")).get("SL");
    // 20 berths (racCapacity = 2): 10 in T1 (fill 9 leaving T1-10 SIDE_LOWER), 10 in T2 (fill 9 leaving T2-10 LOWER).
    List<String> t1Berths = new ArrayList<>();
    for (int i = 0; i < 9; i++) t1Berths.add("LOWER");
    t1Berths.add("SIDE_LOWER");
    List<String> t2Berths = new ArrayList<>();
    for (int i = 0; i < 10; i++) t2Berths.add("LOWER");
    sl.put("coaches", List.of(
        Map.of("id", "T1", "berths", t1Berths),
        Map.of("id", "T2", "berths", t2Berths)));
    sl.put("quotas", Map.of("GENERAL", 20L, "LADIES", 0L, "SENIOR", 0L, "TATKAL", 0L));
    ReservationEngine splitCheck = new ReservationEngine(TrainDefinition.fromMap(copy));
    for (int i = 1; i <= 9; i++) {
      splitCheck.execute(bookCmd("FT1-" + i, "2026-11-01T08:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FT1P" + i, 30, "M", null))));
    }
    for (int i = 1; i <= 9; i++) {
      splitCheck.execute(bookCmd("FT2-" + i, "2026-11-01T08:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FT2P" + i, 30, "M", null))));
    }
    // T1 has 1 free berth (T1-10 SIDE_LOWER) and T2 has 1 free berth (T2-10 LOWER). No single coach has 2 free berths!
    // Adult + Child (age 8) must NOT be split across T1 and T2; they enter RAC together on T1-10.
    splitCheck.execute(bookCmd("FAM", "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("ADULT", 35, "M", null), passenger("CHILD", 8, "F", null))));
    Passenger adult = splitCheck.bookings().get("FAM").passengers().get(0);
    Passenger child = splitCheck.bookings().get("FAM").passengers().get(1);
    String diag = "adult=" + adult.status() + "@" + adult.berthId() + ", child=" + child.status() + "@" + child.berthId();
    assertEquals(PassengerStatus.RAC, adult.status(), "Adult must stay with child in RAC rather than split across coaches (" + diag + ")");
    assertEquals(PassengerStatus.RAC, child.status(), "Child must stay with adult in RAC (" + diag + ")");
    assertEquals(adult.coachId(), child.coachId(), "Adult and child must share the same coach (" + diag + ")");
  }

  @SuppressWarnings("unchecked")
  private static void testTwoAdultsTwoChildrenBalanced() {
    Map<String, Object> copy = deepCopyMap(baseTrainMap);
    Map<String, Object> sl = (Map<String, Object>) ((Map<String, Object>) copy.get("classes")).get("SL");
    sl.put("coaches", List.of(
        Map.of("id", "C1", "berths", List.of("LOWER", "UPPER")),
        Map.of("id", "C2", "berths", List.of("LOWER", "UPPER"))));
    sl.put("quotas", Map.of("GENERAL", 4L, "LADIES", 0L, "SENIOR", 0L, "TATKAL", 0L));
    ReservationEngine e = new ReservationEngine(TrainDefinition.fromMap(copy));
    e.execute(bookCmd("FAM4", "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(
            passenger("A1", 36, "M", null),
            passenger("A2", 34, "F", null),
            passenger("K1", 9, "M", null),
            passenger("K2", 7, "F", null))));
    List<Passenger> pax = e.bookings().get("FAM4").passengers();
    assertTrue(pax.stream().allMatch(p -> p.status() == PassengerStatus.CNF), "All 4 should confirm across C1 and C2");
    assertEquals(pax.get(0).coachId(), pax.get(2).coachId(), "A1 and K1 share coach");
    assertEquals(pax.get(1).coachId(), pax.get(3).coachId(), "A2 and K2 share coach");
  }

  private static void testLadiesQuota() {
    ReservationEngine e = defaultEngine();
    assertThrows(() -> e.execute(bookCmd("L1", "2026-11-01T09:00:00+05:30", "SL", "Central", "Lakeview", "LADIES",
        List.of(passenger("M1", 30, "M", null)))), "all-female");
  }

  private static void testSeniorQuotaAndPartialReprice() {
    ReservationEngine e = defaultEngine();
    assertThrows(() -> e.execute(bookCmd("S-BAD", "2026-11-01T09:00:00+05:30", "3A", "Central", "Riverside", "SENIOR",
        List.of(passenger("F57", 57, "F", null)))), "woman aged 58+");

    e.execute(bookCmd("S-PART", "2026-11-01T09:00:00+05:30", "3A", "Central", "Riverside", "SENIOR",
        List.of(passenger("S-WOMAN", 58, "F", null), passenger("S-MAN", 55, "M", null))));
    Booking b = e.bookings().get("S-PART");
    int generalFare = (int) Math.round((1.1 * 740 + 40) * 1.05);
    assertTrue(b.passengers().get(1).fare() < generalFare, "Companion gets 40% senior ticket discount initially");
    e.execute(cancelCmd("2026-11-07T10:00:00+05:30", "S-PART", List.of("S-WOMAN")));
    assertEquals(generalFare, b.passengers().get(1).fare(), "Companion reverts to GENERAL fare when senior cancels");
  }

  private static void testTatkalRulesAndSeniorOnTatkal() {
    ReservationEngine e = defaultEngine();
    assertThrows(() -> e.execute(bookCmd("T-EARLY", "2026-11-07T07:59:59+05:30", "SL", "Central", "Lakeview", "TATKAL",
        List.of(passenger("P1", 62, "M", null)))), "24 hours");

    e.execute(bookCmd("T1", "2026-11-07T08:00:00+05:30", "SL", "Central", "Lakeview", "TATKAL",
        List.of(passenger("TAT-SEN", 65, "M", null))));
    Passenger tat = e.bookings().get("T1").passengers().get(0);
    assertEquals(77, tat.fare(), "Senior on TATKAL pays 30% premium with zero concession");
    assertEquals("S1-01", tat.berthId(), "Senior on TATKAL still gets LOWER berth priority");

    Map<String, Object> res = e.execute(cancelCmd("2026-11-07T12:00:00+05:30", "T1", null));
    assertEquals(0, res.get("refunded"), "Confirmed TATKAL cancellation yields zero refund");
  }

  private static void testFareSlabsAndAcMinimum() {
    ReservationEngine e = defaultEngine();
    e.execute(bookCmd("SL-SHORT", "2026-11-01T09:00:00+05:30", "SL", "Central", "Lakeview", "GENERAL",
        List.of(passenger("P-SL", 30, "M", null))));
    assertEquals(64, e.bookings().get("SL-SHORT").passengers().get(0).fare(), "SL 110km fare");

    e.execute(bookCmd("3A-MIN", "2026-11-01T09:00:00+05:30", "3A", "Central", "Northgate", "GENERAL",
        List.of(passenger("P-3A", 30, "M", null))));
    assertEquals(294, e.bookings().get("3A-MIN").passengers().get(0).fare(), "3A 55km billed at 200km minimum + 5% GST");

    e.execute(bookCmd("2A-LONG", "2026-11-01T09:00:00+05:30", "2A", "Central", "Riverside", "GENERAL",
        List.of(passenger("P-2A", 30, "M", null))));
    assertEquals(1306, e.bookings().get("2A-LONG").passengers().get(0).fare(), "2A 740km fare with GST");
  }

  private static void testRefundBands() {
    ReservationEngine e = defaultEngine();
    for (int i = 1; i <= 4; i++) {
      e.execute(bookCmd("B" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("P" + i, 30, "M", null))));
    }
    assertEquals(209, e.execute(cancelCmd("2026-11-05T08:00:00+05:30", "B1", null)).get("refunded"), "48h+ refund");
    assertEquals(139, e.execute(cancelCmd("2026-11-07T08:00:00+05:30", "B2", null)).get("refunded"), "12-48h refund");
    assertEquals(70, e.execute(cancelCmd("2026-11-08T02:00:00+05:30", "B3", null)).get("refunded"), "4-12h refund");
    assertEquals(0, e.execute(cancelCmd("2026-11-08T05:00:00+05:30", "B4", null)).get("refunded"), "<4h refund");
  }

  private static void testChildUnderFive() {
    ReservationEngine e = defaultEngine();
    e.execute(bookCmd("INF", "2026-11-01T09:00:00+05:30", "SL", "Central", "Lakeview", "GENERAL",
        List.of(passenger("A1", 36, "M", null), passenger("C1", 4, "F", null))));
    List<Passenger> pax = e.bookings().get("INF").passengers();
    assertEquals(PassengerStatus.CNF, pax.get(0).status(), "Adult confirmed");
    assertEquals(PassengerStatus.NO_BERTH, pax.get(1).status(), "Under-5 gets NO_BERTH");
    assertEquals(0, pax.get(1).fare(), "Under-5 pays zero fare");

    assertThrows(() -> e.execute(cancelCmd("2026-11-01T10:00:00+05:30", "INF", List.of("A1"))), "last adult");

    ReservationEngine small = engineWithSl(
        List.of("LOWER", "LOWER", "SIDE_LOWER"),
        Map.of("GENERAL", 2, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    small.execute(bookCmd("C1", "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("P1", 30, "M", null))));
    small.execute(bookCmd("C2", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("P2", 30, "M", null))));
    small.execute(bookCmd("C3", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("P3", 30, "M", null))));
    small.execute(bookCmd("C4", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("P4", 32, "M", null), passenger("BABY", 3, "F", null))));
    small.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertEquals(PassengerStatus.CANCELLED, small.bookings().get("C4").passengers().get(0).status(), "WL guardian cancelled");
    assertEquals(PassengerStatus.CANCELLED, small.bookings().get("C4").passengers().get(1).status(), "Orphaned infant cancelled");
  }

  private static void testLastStationRejected() {
    ReservationEngine e = defaultEngine();
    assertThrows(() -> e.execute(bookCmd("LAST", "2026-11-01T09:00:00+05:30", "SL", "Riverside", "Central", "GENERAL",
        List.of(passenger("P1", 30, "M", null)))), "Invalid journey");
    assertThrows(() -> e.execute(bookCmd("SAME", "2026-11-01T09:00:00+05:30", "SL", "Riverside", "Riverside", "GENERAL",
        List.of(passenger("P2", 30, "M", null)))), "Invalid journey");
  }

  private static void testExactWaitlistLimitAtomic() {
    ReservationEngine e = defaultEngine();
    for (int i = 0; i < 10; i++) {
      e.execute(bookCmd("B" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("P" + i, 30, "M", null))));
    }
    int racLimit = e.racCapacity("SL");
    int wlLimit = e.wlCapacity("SL");
    for (int i = 0; i < racLimit + wlLimit; i++) {
      e.execute(bookCmd("W" + i, "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("WP" + i, 30, "M", null))));
    }
    assertEquals(wlLimit, e.activeInQueue("SL", PassengerStatus.WL).size(), "Exact WL limit accepted");
    int countBefore = e.bookings().size();
    assertThrows(() -> e.execute(bookCmd("W-OVER", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("WP-OVER", 30, "M", null)))), "Waitlist limit");
    assertEquals(countBefore, e.bookings().size(), "Over-limit booking rejected atomically");
  }

  private static void testRacPairOneCancelsPromotesWl() {
    List<String> berths = new ArrayList<>();
    for (int i = 0; i < 18; i++) berths.add("LOWER");
    berths.add("SIDE_LOWER");
    berths.add("SIDE_LOWER");
    ReservationEngine e = engineWithSl(berths, Map.of("GENERAL", 18, "LADIES", 1, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 18; i++) {
      e.execute(bookCmd("F" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FP" + i, 30, "M", null))));
    }
    e.execute(bookCmd("LADY", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "LADIES",
        List.of(passenger("LP1", 30, "F", null))));
    e.execute(bookCmd("PAIR", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("R1", 30, "M", null), passenger("R2", 31, "M", null))));
    e.execute(bookCmd("WL1", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("W1", 28, "M", null))));

    assertEquals(PassengerStatus.RAC, e.bookings().get("PAIR").passengers().get(0).status(), "R1 is RAC");
    assertEquals(PassengerStatus.RAC, e.bookings().get("PAIR").passengers().get(1).status(), "R2 is RAC");
    assertEquals(PassengerStatus.WL, e.bookings().get("WL1").passengers().get(0).status(), "W1 is WL");

    e.execute(cancelCmd("2026-11-07T07:00:00+05:30", "PAIR", List.of("R1")));
    assertEquals(PassengerStatus.RAC, e.bookings().get("PAIR").passengers().get(1).status(), "R2 remains RAC");
    assertEquals(PassengerStatus.RAC, e.bookings().get("WL1").passengers().get(0).status(), "W1 promotes WL->RAC");
    assertEquals(e.bookings().get("PAIR").passengers().get(1).racPair().id(),
        e.bookings().get("WL1").passengers().get(0).racPair().id(), "W1 joins R2's open RAC pair");
  }

  private static void testRacPairDoesNotConflictWithCnfOnSameBerth() {
    List<String> berths = new ArrayList<>();
    for (int i = 0; i < 9; i++) berths.add("LOWER");
    berths.add("SIDE_LOWER");
    ReservationEngine e2 = engineWithSl(berths, Map.of("GENERAL", 10, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 9; i++) {
      e2.execute(bookCmd("FULL" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FP" + i, 30, "M", null))));
    }
    e2.execute(bookCmd("CNF-LATE", "2026-11-01T09:01:00+05:30", "SL", "Greenfield", "Riverside", "GENERAL",
        List.of(passenger("P-LATE", 30, "M", null))));
    e2.execute(bookCmd("RAC1", "2026-11-01T09:02:00+05:30", "SL", "Central", "Hilltown", "GENERAL",
        List.of(passenger("R1", 30, "M", null))));
    assertEquals(PassengerStatus.RAC, e2.bookings().get("RAC1").passengers().get(0).status(), "R1 gets RAC on T1-10");

    e2.execute(bookCmd("W-LONG", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("WL-L", 30, "M", null))));
    assertEquals(PassengerStatus.WL, e2.bookings().get("W-LONG").passengers().get(0).status(),
        "W_LONG must go to WL instead of colliding with P-LATE on T1-10");
  }

  private static void testFitAwarePromotionSkipsLongTrip() {
    List<String> berths = new ArrayList<>();
    for (int i = 0; i < 9; i++) berths.add("LOWER");
    berths.add("SIDE_LOWER");
    ReservationEngine e = engineWithSl(berths, Map.of("GENERAL", 11, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 9; i++) {
      e.execute(bookCmd("FULL" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FP" + i, 30, "M", null))));
    }
    e.execute(bookCmd("EARLY", "2026-11-01T09:01:00+05:30", "SL", "Central", "Hilltown", "GENERAL",
        List.of(passenger("P-EARLY", 30, "M", null))));
    e.execute(bookCmd("LATE", "2026-11-01T09:02:00+05:30", "SL", "Greenfield", "Riverside", "GENERAL",
        List.of(passenger("P-LATE", 30, "M", null))));
    e.execute(bookCmd("WL-LONG", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("P-WL-LONG", 30, "M", null))));
    e.execute(bookCmd("WL-SHORT", "2026-11-01T09:04:00+05:30", "SL", "Central", "Hilltown", "GENERAL",
        List.of(passenger("P-WL-SHORT", 30, "M", null))));

    Map<String, Object> res = e.execute(cancelCmd("2026-11-07T07:00:00+05:30", "EARLY", null));
    assertEquals(PassengerStatus.WL, e.bookings().get("WL-LONG").passengers().get(0).status(), "Long WL skipped");
    assertEquals(PassengerStatus.RAC, e.bookings().get("WL-SHORT").passengers().get(0).status(), "Short WL promoted");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> promos = (List<Map<String, Object>>) res.get("promotions");
    assertTrue(promos.stream().anyMatch(p -> "P-WL-SHORT".equals(p.get("passengerId")) && "RAC".equals(p.get("to"))),
        "Promotion logged");
  }

  private static void testChartQuotaMathAndFamilyRac() {
    ReservationEngine e = engineWithSl(
        List.of("LOWER", "LOWER", "LOWER", "SIDE_LOWER", "SIDE_LOWER"),
        Map.of("GENERAL", 2, "LADIES", 1, "SENIOR", 1, "TATKAL", 1));
    e.execute(bookCmd("G1", "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("GP1", 30, "M", null))));
    e.execute(bookCmd("G2", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("GP2", 31, "M", null))));
    e.execute(bookCmd("R1", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("RP1", 32, "M", null))));
    e.execute(bookCmd("W1", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("WP1", 33, "M", null))));

    Map<String, Object> chartEvent = e.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertEquals(PassengerStatus.CNF, e.bookings().get("R1").passengers().get(0).status(), "RAC promotes at chart");
    assertEquals(PassengerStatus.CNF, e.bookings().get("W1").passengers().get(0).status(), "WL promotes after quota release");
    @SuppressWarnings("unchecked")
    Map<String, Object> released = (Map<String, Object>) ((Map<String, Object>) chartEvent.get("releasedQuota")).get("SL");
    assertEquals(3, released.get("releasedToGeneral"), "Exact unused non-GENERAL quota released");

    // Verify adult + child sharing an RAC pair both confirm at chart when 2 quota slots release (1 vacant berth + their own SIDE_LOWER):
    List<String> twentyBerths = new ArrayList<>();
    for (int i = 0; i < 18; i++) twentyBerths.add("LOWER");
    twentyBerths.add("SIDE_LOWER");
    twentyBerths.add("SIDE_LOWER");
    ReservationEngine fam = engineWithSl(twentyBerths, Map.of("GENERAL", 18, "LADIES", 2, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 18; i++) {
      fam.execute(bookCmd("G" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("GP" + i, 30, "M", null))));
    }
    fam.execute(bookCmd("FAM-RAC", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("FA", 35, "M", null), passenger("FC", 9, "F", null))));
    fam.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertEquals(PassengerStatus.CNF, fam.bookings().get("FAM-RAC").passengers().get(0).status(),
        "Family RAC adult promotes to CNF at chart");
    assertEquals(PassengerStatus.CNF, fam.bookings().get("FAM-RAC").passengers().get(1).status(),
        "Family RAC child promotes to CNF on freed SIDE_LOWER at chart");
  }

  private static void testChartAutoCancelAndTimeGate() {
    ReservationEngine early = defaultEngine();
    assertThrows(() -> early.execute(Map.of("type", "chart", "at", "2026-11-08T03:59:59+05:30")), "within four hours");

    ReservationEngine e = engineWithSl(
        List.of("LOWER", "LOWER", "SIDE_LOWER"),
        Map.of("GENERAL", 2, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    e.execute(bookCmd("C1", "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("CP1", 30, "M", null))));
    e.execute(bookCmd("C2", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("CP2", 30, "M", null))));
    e.execute(bookCmd("C3", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("CP3", 30, "M", null))));
    e.execute(bookCmd("C4", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("CP4", 30, "M", null))));
    int fare = e.bookings().get("C4").passengers().get(0).fare();
    Map<String, Object> chart = e.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertEquals(PassengerStatus.CANCELLED, e.bookings().get("C4").passengers().get(0).status(), "WL cancelled at chart");
    assertEquals(fare, e.bookings().get("C4").passengers().get(0).refund(), "Full fare refunded");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> autoCancelled = (List<Map<String, Object>>) chart.get("autoCancelledWaitlist");
    assertEquals(1, autoCancelled.size(), "One WL auto-cancelled");
  }

  private static void testChronologicalEnforcement() {
    ReservationEngine e = defaultEngine();
    e.execute(bookCmd("T1", "2026-11-01T10:00:00+05:30", "SL", "Central", "Lakeview", "GENERAL",
        List.of(passenger("P1", 30, "M", null))));
    assertThrows(() -> e.execute(Map.of("type", "status", "bookingId", "T1", "at", "2026-11-01T09:59:00+05:30")),
        "chronological order");
  }

  private static void testSampleCommandsEndToEnd() throws Exception {
    TrainDefinition train = TrainDefinition.fromMap(baseTrainMap);
    List<Object> commands = JsonParser.parseArray(
        Files.readString(Path.of("data/commands.json"), StandardCharsets.UTF_8));
    ReservationEngine e = new ReservationEngine(train);
    for (Object item : commands) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cmd = (Map<String, Object>) item;
      e.execute(cmd);
    }
    assertTrue(e.isChartPrepared(), "Chart must be prepared");
    assertEquals(PassengerStatus.CNF, e.bookings().get("B003").passengers().get(0).status(), "P013 promoted to CNF");
    assertEquals(PassengerStatus.CNF, e.bookings().get("B005").passengers().get(0).status(), "P015 promoted to CNF at chart");
    assertEquals(PassengerStatus.CNF, e.bookings().get("B006").passengers().get(0).status(), "P016 promoted to CNF at chart");
    assertEquals(PassengerStatus.CNF, e.bookings().get("B008").passengers().get(0).status(), "Vikram promoted to CNF at chart");
    assertEquals(PassengerStatus.NO_BERTH, e.bookings().get("B008").passengers().get(1).status(), "Anu stays NO_BERTH");
  }

  private static void testSeniorPartialCancelRefundBounds() {
    ReservationEngine e = defaultEngine();
    e.execute(bookCmd("S-BOTH", "2026-11-01T09:00:00+05:30", "3A", "Central", "Riverside", "SENIOR",
        List.of(passenger("SW", 58, "F", null), passenger("SM", 55, "M", null))));
    Booking b = e.bookings().get("S-BOTH");
    int paidBefore = b.totalPaid();
    e.execute(cancelCmd("2026-11-05T08:00:00+05:30", "S-BOTH", List.of("SW")));
    e.execute(cancelCmd("2026-11-05T09:00:00+05:30", "S-BOTH", List.of("SM")));
    assertTrue(b.refunded() < paidBefore, "Sequential CNF cancel must deduct cancellation fee and stay below totalPaid");
    assertEquals(768, b.refunded(), "75% of 469 (352) + 75% of paid 555 (416)");

    // Also test WL senior partial cancel followed by chart auto-cancel of companion:
    List<String> eight = List.of("LOWER", "LOWER", "LOWER", "LOWER", "LOWER", "LOWER", "LOWER", "SIDE_LOWER");
    ReservationEngine wlEngine = engineWithSl(eight, Map.of("GENERAL", 7, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 7; i++) {
      wlEngine.execute(bookCmd("F" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FP" + i, 30, "M", null))));
    }
    wlEngine.execute(bookCmd("R-FILL", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("RF1", 30, "M", null))));
    wlEngine.execute(bookCmd("SEN-WL", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "SENIOR",
        List.of(passenger("SWL", 58, "F", null), passenger("SML", 55, "M", null))));
    Booking senWl = wlEngine.bookings().get("SEN-WL");
    int wlTotalPaid = senWl.totalPaid();
    wlEngine.execute(cancelCmd("2026-11-05T09:00:00+05:30", "SEN-WL", List.of("SWL")));
    wlEngine.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertTrue(senWl.refunded() <= wlTotalPaid, "Auto-cancelled WL companion refund must not exceed totalPaid");
    verifyEngineInvariants(wlEngine);
  }

  @SuppressWarnings("unchecked")
  private static void testMultiChildFamilyRacAndBalancedWl() {
    // 1) 1 adult + 2 children with racRoom=2 must not split 2 to RAC and 1 child to WL.
    List<String> twenty = new ArrayList<>();
    for (int i = 0; i < 18; i++) twenty.add("LOWER");
    twenty.add("SIDE_LOWER");
    twenty.add("SIDE_LOWER");
    ReservationEngine e = engineWithSl(twenty, Map.of("GENERAL", 18, "LADIES", 2, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 18; i++) {
      e.execute(bookCmd("G" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("GP" + i, 30, "M", null))));
    }
    e.execute(bookCmd("FAM3", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("FA", 35, "M", null), passenger("FC1", 8, "F", null), passenger("FC2", 6, "M", null))));
    for (Passenger p : e.bookings().get("FAM3").passengers()) {
      assertEquals(PassengerStatus.WL, p.status(), "All 3 stay together in WL when racRoom=2");
    }

    // 2) 40 berths (racCapacity=4): S1 has 2 SIDE_LOWER, S2 has 2 SIDE_LOWER.
    // 1 adult + 2 children must all get RAC in S1 (same coach!), not split across S1 and S2.
    Map<String, Object> copy = deepCopyMap(baseTrainMap);
    Map<String, Object> sl = (Map<String, Object>) ((Map<String, Object>) copy.get("classes")).get("SL");
    List<String> cBerths = new ArrayList<>();
    for (int i = 0; i < 18; i++) cBerths.add("LOWER");
    cBerths.add("SIDE_LOWER");
    cBerths.add("SIDE_LOWER");
    sl.put("coaches", List.of(
        Map.of("id", "C1", "berths", new ArrayList<>(cBerths)),
        Map.of("id", "C2", "berths", new ArrayList<>(cBerths))));
    sl.put("quotas", Map.of("GENERAL", 36L, "LADIES", 4L, "SENIOR", 0L, "TATKAL", 0L));
    ReservationEngine e40 = new ReservationEngine(TrainDefinition.fromMap(copy));
    for (int i = 0; i < 36; i++) {
      e40.execute(bookCmd("C" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("CP" + i, 30, "M", null))));
    }
    // Fill 1 RAC slot in C1 so C1 has 3 RAC slots left, then book 1 adult + 2 children:
    e40.execute(bookCmd("R0", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("RP0", 30, "M", null))));
    e40.execute(bookCmd("FAM3-RAC", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("FA1", 35, "M", null), passenger("FC1", 8, "F", null), passenger("FC2", 6, "M", null))));
    List<Passenger> fam3 = e40.bookings().get("FAM3-RAC").passengers();
    assertTrue(fam3.stream().allMatch(p -> p.status() == PassengerStatus.RAC), "All 3 enter RAC");
    assertEquals(fam3.get(0).coachId(), fam3.get(1).coachId(), "Child 1 shares adult coach");
    assertEquals(fam3.get(0).coachId(), fam3.get(2).coachId(), "Child 2 shares adult coach");

    // 3) Balanced WL units: 2 adults + 2 children in WL promote as [A1, C1] when racRoom=2 opens.
    e.execute(bookCmd("FAM4-WL", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("A1", 35, "M", null), passenger("A2", 34, "F", null),
            passenger("C1", 8, "F", null), passenger("C2", 7, "M", null))));
    e.execute(cancelCmd("2026-11-02T09:00:00+05:30", "FAM3", null));
    // Now cancel 2 GENERAL CNF berths when racRoom=2 to verify balanced [A1, C1] / [A2, C2] handling:
    ReservationEngine wlPairEngine = engineWithSl(twenty, Map.of("GENERAL", 18, "LADIES", 2, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 18; i++) {
      wlPairEngine.execute(bookCmd("G" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("GP" + i, 30, "M", null))));
    }
    wlPairEngine.execute(bookCmd("R1", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("RP1", 30, "M", null))));
    wlPairEngine.execute(bookCmd("R2", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("RP2", 30, "M", null))));
    wlPairEngine.execute(bookCmd("FAM4", "2026-11-01T09:03:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("A1", 35, "M", null), passenger("A2", 34, "F", null),
            passenger("C1", 8, "F", null), passenger("C2", 7, "M", null))));
    wlPairEngine.execute(cancelCmd("2026-11-02T09:00:00+05:30", "G0", null));
    wlPairEngine.execute(cancelCmd("2026-11-02T09:01:00+05:30", "G1", null));
    List<Passenger> fam4 = wlPairEngine.bookings().get("FAM4").passengers();
    assertEquals(PassengerStatus.RAC, fam4.get(0).status(), "Balanced unit A1 promotes WL->RAC");
    assertEquals(PassengerStatus.RAC, fam4.get(2).status(), "Balanced unit C1 promotes WL->RAC with A1");
    assertEquals(PassengerStatus.WL, fam4.get(1).status(), "Second pair A2 waits in WL");
    assertEquals(PassengerStatus.WL, fam4.get(3).status(), "Second pair C2 stays with A2 in WL");
  }

  private static void testDisjointRacChartAndPostChartCancel() {
    List<String> berths = new ArrayList<>();
    for (int i = 0; i < 9; i++) berths.add("LOWER");
    berths.add("SIDE_LOWER");
    ReservationEngine e = engineWithSl(berths, Map.of("GENERAL", 11, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    for (int i = 0; i < 9; i++) {
      e.execute(bookCmd("F" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("FP" + i, 30, "M", null))));
    }
    e.execute(bookCmd("R1", "2026-11-01T09:01:00+05:30", "SL", "Central", "Hilltown", "GENERAL",
        List.of(passenger("RP1", 30, "M", null))));
    e.execute(bookCmd("R2", "2026-11-01T09:02:00+05:30", "SL", "Greenfield", "Riverside", "GENERAL",
        List.of(passenger("RP2", 30, "M", null))));
    e.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertEquals(PassengerStatus.CNF, e.bookings().get("R1").passengers().get(0).status(), "Disjoint R1 confirms at chart");
    assertEquals(PassengerStatus.CNF, e.bookings().get("R2").passengers().get(0).status(), "Disjoint R2 confirms at chart");
    verifyEngineInvariants(e);

    // Post-chart cancel with remaining RAC promotes within merged class quota:
    ReservationEngine post2 = engineWithSl(
        List.of("LOWER", "LOWER", "LOWER", "LOWER", "SIDE_LOWER"),
        Map.of("GENERAL", 3, "LADIES", 1, "SENIOR", 0, "TATKAL", 0));
    for (int i = 1; i <= 3; i++) {
      post2.execute(bookCmd("G" + i, "2026-11-01T09:00:00+05:30", "SL", "Central", "Riverside", "GENERAL",
          List.of(passenger("GP" + i, 30, "M", null))));
    }
    post2.execute(bookCmd("L1", "2026-11-01T09:01:00+05:30", "SL", "Central", "Riverside", "LADIES",
        List.of(passenger("LP1", 30, "F", null))));
    post2.execute(bookCmd("R1", "2026-11-01T09:02:00+05:30", "SL", "Central", "Riverside", "GENERAL",
        List.of(passenger("RP1", 30, "M", null))));
    post2.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertEquals(PassengerStatus.RAC, post2.bookings().get("R1").passengers().get(0).status(), "R1 stays RAC at full quota");
    Map<String, Object> postCancel = post2.execute(cancelCmd("2026-11-08T05:00:00+05:30", "G1", null));
    assertEquals(0, postCancel.get("refunded"), "Post-chart CNF cancel gives nil refund");
    assertEquals(PassengerStatus.CNF, post2.bookings().get("R1").passengers().get(0).status(), "R1 promotes post-chart using merged quota");
    verifyEngineInvariants(post2);
  }

  @SuppressWarnings("unchecked")
  private static void testSubSecondTatkalAndDeterministicOrder() {
    ReservationEngine e = defaultEngine();
    assertThrows(() -> e.execute(bookCmd("T-SUB", "2026-11-07T07:59:59.500+05:30", "SL", "Central", "Lakeview", "TATKAL",
        List.of(passenger("PS", 30, "M", null)))), "24 hours");

    ReservationEngine prefEngine = engineWithSl(
        List.of("LOWER", "UPPER"),
        Map.of("GENERAL", 2, "LADIES", 0, "SENIOR", 0, "TATKAL", 0));
    prefEngine.execute(bookCmd("P-ORD", "2026-11-01T09:00:00+05:30", "SL", "Central", "Lakeview", "GENERAL",
        List.of(passenger("NO-PREF", 30, "M", null), passenger("WANTS-LOWER", 31, "M", "LOWER"))));
    List<Passenger> pax = prefEngine.bookings().get("P-ORD").passengers();
    assertEquals("T1-02", pax.get(0).berthId(), "No-preference passenger yields LOWER to explicit preference");
    assertEquals("T1-01", pax.get(1).berthId(), "Explicit LOWER preference honored");

    ReservationEngine orderEngine = new ReservationEngine(TrainDefinition.fromMap(baseTrainMap));
    Map<String, Object> chartEvent = orderEngine.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    Map<String, Object> chart = (Map<String, Object>) chartEvent.get("chart");
    List<Map<String, Object>> coaches = (List<Map<String, Object>>) chart.get("coaches");
    List<String> coachNames = coaches.stream().map(c -> (String) c.get("coach")).toList();
    assertEquals(List.of("S1", "S2", "A1", "A2", "B1", "B2"), coachNames,
        "Coach order in chart must deterministically preserve train.json class order");
  }

  private static void testSixDayScaleSimulation() {
    TrainDefinition train = TrainDefinition.fromMap(baseTrainMap);
    ReservationEngine e = new ReservationEngine(train);
    List<Station> route = train.route();
    String[] classes = {"SL", "3A", "2A"};
    Random rng = new Random(20261108L);

    List<String> bookingIds = new ArrayList<>();
    int paxSeq = 1;
    long startEpoch = java.time.Instant.parse("2026-11-01T18:30:00Z").getEpochSecond();

    int cancellationsDone = 0;
    for (int i = 1; i <= 120; i++) {
      long atEpoch = startEpoch + (long) i * 4100L;
      String atIso = java.time.OffsetDateTime.ofInstant(
          java.time.Instant.ofEpochSecond(atEpoch), java.time.ZoneOffset.ofHoursMinutes(5, 30)).toString();

      String cls = classes[rng.nextInt(classes.length)];
      int fromIdx = rng.nextInt(route.size() - 1);
      int toIdx = fromIdx + 1 + rng.nextInt(route.size() - 1 - fromIdx);

      long hoursBefore = (train.departure().getEpochSecond() - atEpoch) / 3600L;
      String quota;
      int qRoll = rng.nextInt(10);
      if (hoursBefore <= 24 && qRoll >= 7) {
        quota = "TATKAL";
      } else if (qRoll == 5) {
        quota = "LADIES";
      } else if (qRoll == 6) {
        quota = "SENIOR";
      } else {
        quota = "GENERAL";
      }

      int partySize = 1 + rng.nextInt(3);
      List<Map<String, Object>> pax = new ArrayList<>();
      for (int k = 0; k < partySize; k++) {
        String pId = "SIM-P" + (paxSeq++);
        String gender = "LADIES".equals(quota) ? "F" : (rng.nextBoolean() ? "M" : "F");
        int age;
        if ("SENIOR".equals(quota) && k == 0) {
          age = "F".equals(gender) ? 60 : 62;
        } else if (k == partySize - 1 && partySize > 1 && rng.nextInt(5) == 0) {
          age = rng.nextBoolean() ? 3 : 8;
        } else {
          age = 20 + rng.nextInt(45);
        }
        pax.add(passenger(pId, age, gender, rng.nextInt(3) == 0 ? "LOWER" : null));
      }

      String bId = "SIM-B" + String.format("%03d", i);
      try {
        e.execute(bookCmd(bId, atIso, cls, route.get(fromIdx).name(), route.get(toIdx).name(), quota, pax));
        bookingIds.add(bId);
      } catch (IllegalArgumentException ignored) {
        // Expected when WL limit for a class is reached
      }

      if (cancellationsDone < 25 && i >= 12 && i % 4 == 0 && !bookingIds.isEmpty()) {
        List<String> activeBookings = bookingIds.stream()
            .filter(id -> e.bookings().get(id).passengers().stream().anyMatch(Passenger::isActive))
            .toList();
        if (!activeBookings.isEmpty()) {
          String targetBookingId = activeBookings.get(rng.nextInt(activeBookings.size()));
          Booking targetBooking = e.bookings().get(targetBookingId);
          List<Passenger> activeAdults = targetBooking.passengers().stream()
              .filter(p -> p.isActive() && p.isAdult())
              .toList();
          boolean hasActiveChild = targetBooking.passengers().stream()
              .anyMatch(p -> p.isActive() && p.isChild());
          List<String> cancelIds = null;
          if (hasActiveChild && activeAdults.size() <= 1) {
            cancelIds = null;
          } else {
            Passenger firstActive = targetBooking.passengers().stream()
                .filter(Passenger::isActive).findFirst().orElse(null);
            if (firstActive != null) {
              cancelIds = List.of(firstActive.id());
            }
          }
          String cancelAtIso = java.time.OffsetDateTime.ofInstant(
              java.time.Instant.ofEpochSecond(atEpoch + 60L), java.time.ZoneOffset.ofHoursMinutes(5, 30)).toString();
          e.execute(cancelCmd(cancelAtIso, targetBookingId, cancelIds));
          cancellationsDone++;
        }
      }
    }

    assertEquals(25, cancellationsDone, "Exactly 25 cancellations executed");
    verifyEngineInvariants(e);

    e.execute(Map.of("type", "chart", "at", "2026-11-08T04:00:00+05:30"));
    assertTrue(e.isChartPrepared(), "Chart prepared cleanly after 120 bookings + 25 cancellations");
    assertEquals(0, e.activeInQueue("SL", PassengerStatus.WL).size(), "No SL WL after chart");
    assertEquals(0, e.activeInQueue("3A", PassengerStatus.WL).size(), "No 3A WL after chart");
    assertEquals(0, e.activeInQueue("2A", PassengerStatus.WL).size(), "No 2A WL after chart");
    verifyEngineInvariants(e);
  }

  private static void verifyEngineInvariants(ReservationEngine e) {
    for (Berth b : e.berths().values()) {
      for (int edge = 0; edge < b.edgeOccupancy().length; edge++) {
        assertTrue(b.edgeOccupancy()[edge] <= 1,
            "Segment double-booked on berth " + b.id() + " edge " + edge);
      }
      for (Allocation a : b.allocations()) {
        if (a.type() == Allocation.Type.CNF) {
          assertEquals(1, a.members().size(), "CNF allocation has exactly 1 member");
        } else {
          assertTrue(a.members().size() >= 1 && a.members().size() <= 2,
              "RAC pair has 1..2 members");
        }
      }
    }
    for (String cls : e.train().classes().keySet()) {
      assertTrue(e.activeInQueue(cls, PassengerStatus.RAC).size() <= e.racCapacity(cls),
          "RAC limit exceeded for " + cls);
      assertTrue(e.activeInQueue(cls, PassengerStatus.WL).size() <= e.wlCapacity(cls),
          "WL limit exceeded for " + cls);
      if (!e.isChartPrepared()) {
        for (Map.Entry<String, Integer> qEntry : e.train().classes().get(cls).quotas().entrySet()) {
          long used = e.bookings().values().stream()
              .filter(b -> b.classCode().equals(cls) && b.quota().equals(qEntry.getKey()))
              .flatMap(b -> b.passengers().stream())
              .filter(p -> p.status() == PassengerStatus.CNF)
              .count();
          assertTrue(used <= qEntry.getValue(),
              "Pre-chart quota " + qEntry.getKey() + " exceeded for " + cls + ": " + used + " > " + qEntry.getValue());
        }
      } else {
        int totalQuota = e.train().classes().get(cls).quotas().values().stream().mapToInt(Integer::intValue).sum();
        long totalCnf = e.bookings().values().stream()
            .filter(b -> b.classCode().equals(cls))
            .flatMap(b -> b.passengers().stream())
            .filter(p -> p.status() == PassengerStatus.CNF)
            .count();
        assertTrue(totalCnf <= totalQuota,
            "Post-chart CNF count exceeded total class quota for " + cls);
      }
    }
    for (Booking b : e.bookings().values()) {
      assertTrue(b.refunded() <= b.totalPaid(),
          "Refunded exceeds totalPaid on booking " + b.id());
    }
  }
}
