package com.sj03.reservation.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable audit record emitted for every promotion or chart auto-cancellation transition.
 */
public record PromotionRecord(
    String passengerId,
    String from,
    String to,
    String berthId,
    String racPair,
    String reason,
    String at) {

  /** Converts this record into a serializable map omitting null optional fields. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("passengerId", passengerId);
    map.put("from", from);
    map.put("to", to);
    if (berthId != null) {
      map.put("berthId", berthId);
    }
    if (racPair != null) {
      map.put("racPair", racPair);
    }
    map.put("reason", reason);
    if (at != null) {
      map.put("at", at);
    }
    return map;
  }
}
