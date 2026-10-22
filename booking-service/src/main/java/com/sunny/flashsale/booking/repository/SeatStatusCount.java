package com.sunny.flashsale.booking.repository;

import com.sunny.flashsale.booking.domain.SeatStatus;

/** Result row of the per-status seat count; also what the load test reads to check for oversells. */
public record SeatStatusCount(SeatStatus status, long count) {
}
