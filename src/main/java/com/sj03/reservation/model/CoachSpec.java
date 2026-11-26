package com.sj03.reservation.model;

import java.util.List;

/**
 * Coach definition with unique coach identifier and ordered berth layout.
 */
public record CoachSpec(String id, List<BerthType> berths) {}
