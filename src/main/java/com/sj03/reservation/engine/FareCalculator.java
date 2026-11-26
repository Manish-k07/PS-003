package com.sj03.reservation.engine;

import com.sj03.reservation.model.Booking;
import com.sj03.reservation.model.ClassSpec;
import com.sj03.reservation.model.FareSlab;
import com.sj03.reservation.model.Passenger;
import com.sj03.reservation.model.PassengerStatus;
import com.sj03.reservation.model.TrainDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Pure fare, concession, repricing, and cancellation refund calculator (BR-05, BR-06, BR-10, BR-11, BR-12).
 */
public final class FareCalculator {
  private static final BigDecimal TATKAL_MULTIPLIER = new BigDecimal("1.3");
  private static final BigDecimal SENIOR_MALE_FACTOR = new BigDecimal("0.6");
  private static final BigDecimal SENIOR_FEMALE_FACTOR = new BigDecimal("0.5");
  private static final BigDecimal AC_GST_MULTIPLIER = new BigDecimal("1.05");
  private static final BigDecimal MIN_AC_DISTANCE_KM = new BigDecimal("200");
  private static final int RAC_WL_DEDUCTION_RS = 60;

  private FareCalculator() {}

  /** Resolves the applicable per-km rate for chargeableDistance from configured slabs or base rate. */
  public static BigDecimal rateForDistance(ClassSpec spec, double chargeableDistance) {
    List<FareSlab> slabs = spec.fareSlabs();
    if (slabs != null && !slabs.isEmpty()) {
      for (FareSlab slab : slabs) {
        if (slab.upToKm() == null || chargeableDistance <= slab.upToKm()) {
          return slab.ratePerKm();
        }
      }
      return slabs.get(slabs.size() - 1).ratePerKm();
    }
    return spec.ratePerKm();
  }

  /**
   * Recomputes passenger fares and booking currentFare using active passenger composition.
   * BR-05 / BR-12: if the last qualifying senior on a SENIOR ticket cancels, remaining passengers
   * revert to GENERAL fare. Under-five passengers always pay 0.
   */
  public static int reprice(TrainDefinition train, Booking booking) {
    ClassSpec spec = train.classes().get(booking.classCode());
    double rawDistance = train.route().get(booking.trip().end()).distance()
        - train.route().get(booking.trip().start()).distance();
    boolean isAc = spec.isAc();
    BigDecimal chargeableDistance = isAc
        ? BigDecimal.valueOf(rawDistance).max(MIN_AC_DISTANCE_KM)
        : BigDecimal.valueOf(rawDistance);
    BigDecimal rate = rateForDistance(spec, chargeableDistance.doubleValue());

    boolean hasQualifyingSenior = booking.passengers().stream()
        .anyMatch(p -> p.isActive() && p.isEligibleSenior());

    int currentFare = 0;
    for (Passenger p : booking.passengers()) {
      if (!p.isActive()) {
        continue;
      }
      if (p.isInfant()) {
        p.setFare(0);
        continue;
      }
      BigDecimal base = rate.multiply(chargeableDistance);
      if ("TATKAL".equals(booking.quota())) {
        base = base.multiply(TATKAL_MULTIPLIER);
      } else if ("SENIOR".equals(booking.quota()) && hasQualifyingSenior) {
        base = base.multiply("F".equals(p.gender()) ? SENIOR_FEMALE_FACTOR : SENIOR_MALE_FACTOR);
      }
      BigDecimal total = base.add(spec.reservationFee());
      if (isAc) {
        total = total.multiply(AC_GST_MULTIPLIER);
      }
      int fare = roundRupees(total);
      p.setFare(fare);
      currentFare += fare;
    }
    booking.setCurrentFare(currentFare);
    return currentFare;
  }

  /**
   * Computes cancellation refund for a single passenger based on pre-cancellation status and timing.
   * BR-06 / BR-11: Tatkal confirmed refunds 0; RAC/WL deducts Rs 60 up to 30 min before departure;
   * CNF follows 48h+ (75%), 12-48h (50%), 4-12h (25%), &lt;4h or post-chart (0), subject to class min deduction.
   */
  public static int refundFor(
      TrainDefinition train,
      Passenger passenger,
      Booking booking,
      PassengerStatus oldStatus,
      Instant cancelAt,
      boolean chartPrepared) {
    int refundBasis = passenger.paidFare() > 0
        ? Math.min(passenger.fare(), passenger.paidFare())
        : passenger.fare();
    if (refundBasis <= 0) {
      return 0;
    }
    Duration untilDeparture = Duration.between(cancelAt, train.departure());
    int unrefundedPaid = Math.max(0, booking.totalPaid() - booking.refunded());
    if (oldStatus == PassengerStatus.WL || oldStatus == PassengerStatus.RAC) {
      if (untilDeparture.compareTo(Duration.ofMinutes(30)) < 0) {
        return 0;
      }
      return Math.min(unrefundedPaid, Math.max(0, refundBasis - RAC_WL_DEDUCTION_RS));
    }
    if (oldStatus != PassengerStatus.CNF || "TATKAL".equals(booking.quota()) || chartPrepared) {
      return 0;
    }
    BigDecimal refundRate;
    if (untilDeparture.compareTo(Duration.ofHours(48)) >= 0) {
      refundRate = new BigDecimal("0.75");
    } else if (untilDeparture.compareTo(Duration.ofHours(12)) >= 0) {
      refundRate = new BigDecimal("0.50");
    } else if (untilDeparture.compareTo(Duration.ofHours(4)) >= 0) {
      refundRate = new BigDecimal("0.25");
    } else {
      return 0;
    }

    ClassSpec spec = train.classes().get(booking.classCode());
    BigDecimal fareDecimal = BigDecimal.valueOf(refundBasis);
    int pctDeduction = roundRupees(fareDecimal.multiply(BigDecimal.ONE.subtract(refundRate)));
    int minDeduction = roundRupees(spec.minimumDeduction());
    int deduction = Math.max(minDeduction, pctDeduction);
    int rawRefund = Math.max(0, refundBasis - deduction);
    return Math.min(rawRefund, unrefundedPaid);
  }

  private static int roundRupees(BigDecimal amount) {
    return amount.setScale(0, RoundingMode.HALF_UP).intValueExact();
  }
}
