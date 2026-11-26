package com.sj03.reservation.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Travel class specification including fare rates, quotas, and coach layouts.
 */
public record ClassSpec(
    String code,
    BigDecimal ratePerKm,
    List<FareSlab> fareSlabs,
    BigDecimal reservationFee,
    BigDecimal minimumDeduction,
    List<CoachSpec> coaches,
    Map<String, Integer> quotas) {

  /** AC classes (3A, 2A) have a 200 km minimum chargeable distance and 5% GST. */
  public boolean isAc() {
    return "3A".equals(code) || "2A".equals(code);
  }
}
