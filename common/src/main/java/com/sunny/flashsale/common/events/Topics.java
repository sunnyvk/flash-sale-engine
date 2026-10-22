package com.sunny.flashsale.common.events;

/** Topic names in one place so a typo is a compile error, not a silent empty topic. */
public final class Topics {

    /** Key = bookingId, so all events of one booking land on one partition and stay in order. */
    public static final String BOOKING_EVENTS = "booking.events";

    /** Key = bookingId, same reasoning: a booking's payment events stay in order. */
    public static final String PAYMENT_EVENTS = "payment.events";

    private Topics() {
    }
}
