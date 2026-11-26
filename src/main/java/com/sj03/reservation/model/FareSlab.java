package com.sj03.reservation.model;

import java.math.BigDecimal;

/**
 * Distance-slab rate entry; null upToKm denotes the final unbounded distance tier.
 */
public record FareSlab(Double upToKm, BigDecimal ratePerKm) {}
