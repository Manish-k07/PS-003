package com.sj03.reservation.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated train configuration parsed from train JSON definition.
 */
public record TrainDefinition(
    String trainId,
    String name,
    Instant departure,
    List<Station> route,
    Map<String, ClassSpec> classes) {

  /** Builds and strictly validates a TrainDefinition from a parsed JSON map. */
  @SuppressWarnings("unchecked")
  public static TrainDefinition fromMap(Map<String, Object> raw) {
    if (raw == null) {
      throw new IllegalArgumentException("Train definition cannot be null");
    }
    String trainId = asString(raw.get("trainId"), "SJ03");
    String name = asString(raw.get("name"), "Train");
    Instant departure = parseIsoInstant(raw.get("departure"), "Train departure must be a valid ISO timestamp");

    Object routeObj = raw.get("route");
    if (!(routeObj instanceof List<?> routeList) || routeList.size() != 12) {
      throw new IllegalArgumentException("Train definition must contain exactly 12 route stations");
    }

    List<Station> route = new ArrayList<>(routeList.size());
    Set<String> stationNames = new HashSet<>();
    double priorDistance = -1.0;
    for (Object item : routeList) {
      if (!(item instanceof Map<?, ?> stationMap)) {
        throw new IllegalArgumentException("Route station must be an object");
      }
      String stationName = asNonEmptyString(stationMap.get("name"), "Route station requires a non-empty name");
      double distance = asDouble(stationMap.get("distance"), "Route station distance must be numeric");
      if (!stationNames.add(stationName) || !Double.isFinite(distance) || distance <= priorDistance) {
        throw new IllegalArgumentException("Route needs unique station names and strictly increasing distances");
      }
      priorDistance = distance;
      route.add(new Station(stationName, distance));
    }

    Object classesObj = raw.get("classes");
    if (!(classesObj instanceof Map<?, ?> rawClasses) || rawClasses.isEmpty()) {
      throw new IllegalArgumentException("Train definition must have at least one class");
    }

    Map<String, ClassSpec> classes = new LinkedHashMap<>();
    Set<String> coachIds = new HashSet<>();
    for (Map.Entry<?, ?> entry : rawClasses.entrySet()) {
      String code = String.valueOf(entry.getKey());
      if (!(entry.getValue() instanceof Map<?, ?> specMap)) {
        throw new IllegalArgumentException("Class " + code + " definition must be an object");
      }
      BigDecimal ratePerKm = asDecimal(specMap.get("ratePerKm"), "Class " + code + " needs a valid ratePerKm");
      if (ratePerKm.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("Class " + code + " ratePerKm must be positive");
      }
      BigDecimal reservationFee = asDecimal(specMap.get("reservationFee"), "Class " + code + " needs reservationFee");
      if (reservationFee.compareTo(BigDecimal.ZERO) < 0) {
        throw new IllegalArgumentException("Class " + code + " reservationFee cannot be negative");
      }
      BigDecimal minimumDeduction = specMap.get("minimumDeduction") == null
          ? BigDecimal.ZERO
          : asDecimal(specMap.get("minimumDeduction"), "Invalid minimumDeduction for " + code);
      if (minimumDeduction.compareTo(BigDecimal.ZERO) < 0) {
        throw new IllegalArgumentException("Class " + code + " minimumDeduction cannot be negative");
      }

      List<FareSlab> fareSlabs = parseFareSlabs(code, specMap.get("fareSlabs"));
      Map<String, Integer> quotas = parseQuotas(code, specMap.get("quotas"));
      List<CoachSpec> coaches = parseCoaches(code, specMap.get("coaches"), coachIds);

      classes.put(code, new ClassSpec(
          code, ratePerKm, List.copyOf(fareSlabs), reservationFee, minimumDeduction, List.copyOf(coaches),
          Collections.unmodifiableMap(new LinkedHashMap<>(quotas))));
    }

    return new TrainDefinition(
        trainId, name, departure, List.copyOf(route), Collections.unmodifiableMap(new LinkedHashMap<>(classes)));
  }

  private static List<FareSlab> parseFareSlabs(String code, Object slabsObj) {
    if (slabsObj == null) {
      return List.of();
    }
    if (!(slabsObj instanceof List<?> rawSlabs) || rawSlabs.isEmpty()) {
      throw new IllegalArgumentException("fareSlabs for " + code + " must be a non-empty array when provided");
    }
    List<FareSlab> slabs = new ArrayList<>(rawSlabs.size());
    double previousUpper = 0.0;
    for (int i = 0; i < rawSlabs.size(); i++) {
      Object item = rawSlabs.get(i);
      if (!(item instanceof Map<?, ?> slabMap)) {
        throw new IllegalArgumentException("Invalid fare slab entry for " + code);
      }
      BigDecimal slabRate = asDecimal(slabMap.get("ratePerKm"), "Invalid slab ratePerKm for " + code);
      if (slabRate.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("Invalid distance fare slab rate for " + code);
      }
      Object upToRaw = slabMap.get("upToKm");
      Double upToKm = null;
      if (upToRaw != null) {
        upToKm = asDouble(upToRaw, "Invalid upToKm in fare slab for " + code);
        if (!Double.isFinite(upToKm) || upToKm <= previousUpper) {
          throw new IllegalArgumentException("Invalid distance fare slab bound for " + code);
        }
        previousUpper = upToKm;
      } else if (i != rawSlabs.size() - 1) {
        throw new IllegalArgumentException("Unbounded fare slab must be last for " + code);
      }
      slabs.add(new FareSlab(upToKm, slabRate));
    }
    if (slabs.get(slabs.size() - 1).upToKm() != null) {
      throw new IllegalArgumentException("Distance fare slabs for " + code + " need a final unbounded slab");
    }
    return slabs;
  }

  private static Map<String, Integer> parseQuotas(String code, Object quotasObj) {
    if (!(quotasObj instanceof Map<?, ?> rawQuotas) || rawQuotas.isEmpty()) {
      throw new IllegalArgumentException("Class " + code + " needs quota counts");
    }
    Map<String, Integer> quotas = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawQuotas.entrySet()) {
      String quota = String.valueOf(entry.getKey());
      int count = asExactInt(entry.getValue(), "Invalid " + quota + " quota count for " + code);
      if (count < 0) {
        throw new IllegalArgumentException("Invalid " + quota + " quota count for " + code);
      }
      quotas.put(quota, count);
    }
    return quotas;
  }

  private static List<CoachSpec> parseCoaches(String code, Object coachesObj, Set<String> coachIds) {
    if (!(coachesObj instanceof List<?> rawCoaches) || rawCoaches.isEmpty()) {
      throw new IllegalArgumentException("Class " + code + " needs coaches");
    }
    List<CoachSpec> coaches = new ArrayList<>(rawCoaches.size());
    for (Object item : rawCoaches) {
      if (!(item instanceof Map<?, ?> coachMap)) {
        throw new IllegalArgumentException("Coach definition in " + code + " must be an object");
      }
      String coachId = asNonEmptyString(coachMap.get("id"), "Coach id cannot be empty in " + code);
      if (!coachIds.add(coachId)) {
        throw new IllegalArgumentException("Invalid or duplicate coach " + coachId);
      }
      Object berthsObj = coachMap.get("berths");
      if (!(berthsObj instanceof List<?> rawBerths) || rawBerths.isEmpty()) {
        throw new IllegalArgumentException("Coach " + coachId + " requires a non-empty berths array");
      }
      List<BerthType> berths = new ArrayList<>(rawBerths.size());
      for (Object rawBerth : rawBerths) {
        if (!(rawBerth instanceof String s)) {
          throw new IllegalArgumentException("Berth type in coach " + coachId + " must be a string");
        }
        berths.add(BerthType.parseRequired(s));
      }
      coaches.add(new CoachSpec(coachId, List.copyOf(berths)));
    }
    return coaches;
  }

  /** Parses an ISO-8601 timestamp requiring an explicit timezone offset or 'Z'. */
  public static Instant parseIsoInstant(Object raw, String errorMessage) {
    if (!(raw instanceof String s) || (!s.endsWith("Z") && !s.matches(".*[+-]\\d{2}:\\d{2}$"))) {
      throw new IllegalArgumentException(errorMessage);
    }
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(errorMessage, e);
    }
  }

  private static String asString(Object raw, String fallback) {
    return raw instanceof String s && !s.isBlank() ? s : fallback;
  }

  private static String asNonEmptyString(Object raw, String message) {
    if (!(raw instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return s;
  }

  private static double asDouble(Object raw, String message) {
    if (raw instanceof Number n) {
      return n.doubleValue();
    }
    throw new IllegalArgumentException(message);
  }

  private static BigDecimal asDecimal(Object raw, String message) {
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof Long l) {
      return BigDecimal.valueOf(l);
    }
    if (raw instanceof Integer i) {
      return BigDecimal.valueOf(i);
    }
    if (raw instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    throw new IllegalArgumentException(message);
  }

  private static int asExactInt(Object raw, String message) {
    if (raw instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
      return l.intValue();
    }
    if (raw instanceof Integer i) {
      return i;
    }
    throw new IllegalArgumentException(message);
  }
}
