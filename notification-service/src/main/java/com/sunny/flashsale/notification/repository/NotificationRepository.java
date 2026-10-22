package com.sunny.flashsale.notification.repository;

import com.sunny.flashsale.notification.service.NotificationType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private static final String INSERT = "insert into notifications (event_id, user_id, type, channel, payload) "
            + "values (?, ?, ?, 'EMAIL', CAST(? AS JSON))";

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the event. The unique key on event_id makes "have I seen this event?" and "remember it" ONE atomic step,
     * so two consumers racing on a redelivered message cannot both win.
     *
     * @return true if this call recorded the event, false if it was already recorded
     */
    public boolean insertIfNew(String eventId, String userId, NotificationType type, String payloadJson) {
        try {
            jdbc.update(INSERT, eventId, userId, type.name(), payloadJson);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
