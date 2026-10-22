package com.sunny.flashsale.booking.repository;

import com.sunny.flashsale.booking.domain.FlashEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashEventRepository extends JpaRepository<FlashEvent, Long> {
}
