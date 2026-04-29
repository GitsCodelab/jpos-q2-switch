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
    private final boolean mirrorInMemory = DatabaseSupport.isInMemoryMirrorEnabled();

    public void save(Event event) {
        if (!jdbcEnabled || mirrorInMemory) {
            events.add(event);
        }
    }

    public void saveBusinessEvent(String stan, String rrn, String mti, String eventType, String message, String rc) {
        saveIsoEvent(stan, rrn, mti, eventType, message, null, rc);
    }

    public void saveIsoEvent(String stan, String rrn, String mti, String eventType, String requestIso, String responseIso, String rc) {
        if (!jdbcEnabled) {
            return;
        }

        try (Connection connection = DatabaseSupport.getConnection()) {
            saveIsoEvent(connection, stan, rrn, mti, eventType, requestIso, responseIso, rc);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist transaction event", e);
        }
    }

    public void saveIsoEvent(Connection connection, String stan, String rrn, String mti, String eventType, String requestIso, String responseIso, String rc) {
        if (!jdbcEnabled) {
            return;
        }

        if (stan == null || stan.isBlank() || rrn == null || rrn.isBlank()) {
            throw new IllegalArgumentException("transaction event requires non-empty STAN and RRN");
        }

        if (exists(connection, stan, rrn, eventType)) {
            return;
        }

        String sql = "INSERT INTO transaction_events (stan, rrn, mti, event_type, request_iso, response_iso, rc) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (stan, rrn, event_type) DO NOTHING";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    public boolean exists(String stan, String rrn, String eventType) {
        if (!jdbcEnabled) {
            return false;
        }

        try (Connection connection = DatabaseSupport.getConnection()) {
            return exists(connection, stan, rrn, eventType);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check event existence", e);
        }
    }

    public boolean exists(Connection connection, String stan, String rrn, String eventType) {
        if (!jdbcEnabled) {
            return false;
        }

        String sql = "SELECT 1 FROM transaction_events WHERE stan=? AND rrn=? AND event_type=? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, stan);
            ps.setString(2, rrn);
            ps.setString(3, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check event existence", e);
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
