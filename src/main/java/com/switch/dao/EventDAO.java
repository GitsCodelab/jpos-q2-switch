package com.qswitch.dao;

import com.qswitch.model.Event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EventDAO {
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final boolean jdbcEnabled = DatabaseSupport.isJdbcEnabled();

    public void save(Event event) {
        events.add(event);
        if (jdbcEnabled) {
            saveIsoEvent(null, null, null, event.getType(), event.getMessage(), null, null);
        }
    }

    public void saveIsoEvent(String stan, String rrn, String mti, String eventType, String requestIso, String responseIso, String rc) {
        if (!jdbcEnabled) {
            return;
        }

        String sql = "INSERT INTO transaction_events (stan, rrn, mti, event_type, request_iso, response_iso, rc) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DatabaseSupport.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, stan);
            ps.setString(2, rrn);
            ps.setString(3, mti);
            ps.setString(4, eventType);
            ps.setString(5, requestIso);
            ps.setString(6, responseIso);
            ps.setString(7, rc);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist transaction event", e);
        }
    }

    public List<Event> findAll() {
        return List.copyOf(events);
    }

    public int count() {
        if (jdbcEnabled) {
            return countJdbc();
        }
        return events.size();
    }

    private int countJdbc() {
        String sql = "SELECT COUNT(*) FROM transaction_events";
        try (Connection connection = DatabaseSupport.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count events", e);
        }
    }
}
