package com.sunny.flashsale.booking.api;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.idempotency.BookingResult;
import com.sunny.flashsale.booking.idempotency.IdempotentBookingService;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.GlobalExceptionHandler;
import com.sunny.flashsale.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingControllerTest {

    private final BookingService service = Mockito.mock(BookingService.class);
    private final IdempotentBookingService idempotent = Mockito.mock(IdempotentBookingService.class);
    private MockMvc mvc;

    private static final String BOOKING_ID = "77777777-7777-7777-7777-777777777777";

    private static Booking booking(String owner) {
        return new Booking(BOOKING_ID, owner, 1L, 42L, new BigDecimal("1500.00"), "k1");
    }

    private static Authentication user(String name) {
        return UsernamePasswordAuthenticationToken.authenticated(name, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication admin(String name) {
        return UsernamePasswordAuthenticationToken.authenticated(name, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new BookingController(service, idempotent))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    @DisplayName("POST /bookings returns 201 with the pending booking")
    void createsBooking() throws Exception {
        Booking booking = new Booking("11111111-1111-1111-1111-111111111111", "alice", 1L, 42L, new BigDecimal("1500.00"), "k1");
        when(idempotent.book("alice", 1L, "A-001", "k1")).thenReturn(new BookingResult(booking, false));

        mvc.perform(post("/api/v1/bookings")
                        .principal(user("alice")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"seatNumber\":\"A-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.seatId").value(42));
    }

    @Test
    @DisplayName("seatNumber is optional: omitting it asks for any seat")
    void anySeat() throws Exception {
        Booking booking = new Booking("22222222-2222-2222-2222-222222222222", "alice", 1L, 7L, new BigDecimal("1500.00"), "k2");
        when(idempotent.book(eq("alice"), eq(1L), isNull(), eq("k2"))).thenReturn(new BookingResult(booking, false));

        mvc.perform(post("/api/v1/bookings")
                        .principal(user("alice")).header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatId").value(7));
    }

    @Test
    @DisplayName("a lost seat race is a 409 with a stable error code")
    void seatTaken() throws Exception {
        when(idempotent.book(any(), anyLong(), any(), any())).thenThrow(BookingErrors.seatUnavailable());

        mvc.perform(post("/api/v1/bookings")
                        .principal(user("bob")).header("Idempotency-Key", "k3")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatNumber\":\"A-001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("missing Idempotency-Key header is a 400")
    void missingIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/bookings").principal(user("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the user id comes from the token, and a user id in the body or headers is ignored")
    void identityComesFromToken() throws Exception {
        Booking booking = new Booking("66666666-6666-6666-6666-666666666666", "alice", 1L, 42L, new BigDecimal("1500.00"), "k1");
        when(idempotent.book("alice", 1L, "A-001", "k1")).thenReturn(new BookingResult(booking, false));

        mvc.perform(post("/api/v1/bookings")
                        .principal(user("alice")).header("X-User-Id", "mallory").header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatNumber\":\"A-001\",\"userId\":\"mallory\"}"))
                .andExpect(status().isCreated());

        verify(idempotent).book("alice", 1L, "A-001", "k1");
        verify(idempotent, never()).book(eq("mallory"), anyLong(), any(), any());
    }

    @Test
    @DisplayName("the owner can read their booking")
    void ownerReads() throws Exception {
        when(service.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).principal(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(BOOKING_ID));
    }

    @Test
    @DisplayName("somebody else's booking is a 404, not a 403, so ids cannot be probed")
    void otherUsersBookingIs404() throws Exception {
        when(service.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).principal(user("bob")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("an ADMIN can read any booking")
    void adminReadsAny() throws Exception {
        when(service.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(get("/api/v1/bookings/" + BOOKING_ID).principal(admin("root")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("cancelling somebody else's booking is a 404 and nothing is cancelled")
    void cannotCancelOthers() throws Exception {
        when(service.get(BOOKING_ID)).thenReturn(booking("alice"));

        mvc.perform(post("/api/v1/bookings/" + BOOKING_ID + "/cancel").principal(user("bob")))
                .andExpect(status().isNotFound());

        verify(service, never()).cancel(anyString(), anyString());
    }

    @Test
    @DisplayName("missing eventId fails validation with a field-level detail")
    void missingEventId() throws Exception {
        mvc.perform(post("/api/v1/bookings")
                        .principal(user("alice")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a replayed request returns the same 201 body plus the Idempotent-Replayed header")
    void replayedBooking() throws Exception {
        Booking booking = new Booking("44444444-4444-4444-4444-444444444444", "alice", 1L, 42L, new BigDecimal("1500.00"), "k1");
        when(idempotent.book("alice", 1L, "A-001", "k1")).thenReturn(new BookingResult(booking, true));

        mvc.perform(post("/api/v1/bookings")
                        .principal(user("alice")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatNumber\":\"A-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value("44444444-4444-4444-4444-444444444444"));
    }

    @Test
    @DisplayName("a first-time request does not carry the replay header")
    void firstRequestHasNoReplayHeader() throws Exception {
        Booking booking = new Booking("55555555-5555-5555-5555-555555555555", "alice", 1L, 42L, new BigDecimal("1500.00"), "k9");
        when(idempotent.book("alice", 1L, "A-001", "k9")).thenReturn(new BookingResult(booking, false));

        mvc.perform(post("/api/v1/bookings")
                        .principal(user("alice")).header("Idempotency-Key", "k9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatNumber\":\"A-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    @DisplayName("GET availability returns per-status counts")
    void availability() throws Exception {
        when(service.availability(1L)).thenReturn(new BookingService.Availability(1L, 90, 8, 2));

        mvc.perform(get("/api/v1/events/1/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(90))
                .andExpect(jsonPath("$.held").value(8))
                .andExpect(jsonPath("$.booked").value(2));
    }

    @Test
    @DisplayName("cancel without a body uses a default reason")
    void cancelWithoutBody() throws Exception {
        Booking booking = new Booking("33333333-3333-3333-3333-333333333333", "alice", 1L, 1L, new BigDecimal("10.00"), "k");
        booking.markCancelled("cancelled by caller");
        when(service.get("33333333-3333-3333-3333-333333333333")).thenReturn(booking);
        when(service.cancel("33333333-3333-3333-3333-333333333333", "cancelled by caller")).thenReturn(booking);

        mvc.perform(post("/api/v1/bookings/33333333-3333-3333-3333-333333333333/cancel").principal(user("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
