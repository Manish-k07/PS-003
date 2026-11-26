package com.sj03.reservation.model;

/**
 * Lifecycle status of a passenger on a reservation booking.
 */
public enum PassengerStatus {
  PENDING,
  CNF,
  RAC,
  WL,
  NO_BERTH,
  CANCELLED
}
