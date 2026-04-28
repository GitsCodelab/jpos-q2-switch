package com.qswitch.service;

import com.qswitch.dao.EventDAO;
import com.qswitch.dao.DatabaseSupport;
import com.qswitch.dao.TransactionDAO;
import com.qswitch.dao.TransactionMetaDAO;
import com.qswitch.model.Event;
import com.qswitch.model.Transaction;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import java.util.Optional;

public class TransactionService {
    private final TransactionDAO transactionDAO;
    private final EventDAO eventDAO;
    private final TransactionMetaDAO transactionMetaDAO;

    public TransactionService(TransactionDAO transactionDAO, EventDAO eventDAO) {
        this(transactionDAO, eventDAO, new TransactionMetaDAO());
    }

    public TransactionService(TransactionDAO transactionDAO, EventDAO eventDAO, TransactionMetaDAO transactionMetaDAO) {
        this.transactionDAO = transactionDAO;
        this.eventDAO = eventDAO;
        this.transactionMetaDAO = transactionMetaDAO;
    }

    public Transaction handleAuthorization(String stan, String rrn, long amount) {
        Optional<Transaction> existing = transactionDAO.findByStanAndRrn(stan, rrn);
        if (existing.isPresent() && existing.get().getResponseCode() != null) {
            eventDAO.save(new Event("REPLAY_DETECTED", "Replay detected for stan=" + stan + " rrn=" + rrn));
            if (DatabaseSupport.isJdbcEnabled()) {
                withTransaction(connection -> eventDAO.saveIsoEvent(
                    connection,
                    stan,
                    rrn,
                    existing.get().getMti(),
                    "REPLAY_DETECTED",
                    "Replay detected for stan=" + stan + " rrn=" + rrn,
                    null,
                    existing.get().getResponseCode()
                ));
            }
            return existing.get();
        }

        Transaction transaction = new Transaction();
        transaction.setMti("0200");
        transaction.setOriginalMti("0200");
        transaction.setStan(stan);
        transaction.setRrn(rrn);
        transaction.setAmount(amount);
        transaction.setCurrency("840");
        transaction.setStatus("AUTHORIZED");
        transaction.setFinalStatus("LOCAL_RESPONSE");

        if (amount > 0) {
            transaction.setApproved(true);
            transaction.setResponseCode("00");
            eventDAO.save(new Event("AUTH_APPROVED", "Approved amount=" + amount + " stan=" + stan));
        } else {
            transaction.setApproved(false);
            transaction.setResponseCode("13");
            eventDAO.save(new Event("AUTH_DECLINED", "Declined invalid amount=" + amount + " stan=" + stan));
        }

        if (!DatabaseSupport.isJdbcEnabled()) {
            return transactionDAO.save(transaction);
        }

        withTransaction(connection -> {
            transactionDAO.save(connection, transaction);
            String eventType = transaction.isApproved() ? "AUTH_APPROVED" : "AUTH_DECLINED";
            String eventMessage = transaction.isApproved()
                ? "Approved amount=" + amount + " stan=" + stan
                : "Declined invalid amount=" + amount + " stan=" + stan;
            eventDAO.saveIsoEvent(connection, stan, rrn, transaction.getMti(), eventType, eventMessage, null, transaction.getResponseCode());
        });
        return transaction;
    }

    public void persistIncomingRequest(ISOMsg request, String stan, String rrn, long amount) {
        Transaction transaction = new Transaction();
        transaction.setMti(fieldOrNull(request, 0));
        transaction.setOriginalMti(fieldOrNull(request, 0));
        transaction.setStan(stan);
        transaction.setRrn(rrn);
        transaction.setTerminalId(fieldOrNull(request, 41));
        transaction.setAmount(amount);
        transaction.setCurrency(fieldOrDefault(request, 49, "840"));
        transaction.setStatus("REQUEST_RECEIVED");
        transaction.setFinalStatus("PENDING");
        transaction.setReversal(isReversal(request));

        if (!DatabaseSupport.isJdbcEnabled()) {
            transactionDAO.save(transaction);
            return;
        }

        withTransaction(connection -> {
            transactionDAO.save(connection, transaction);
            String requestIso = dumpIso(request);
            eventDAO.saveIsoEvent(connection, stan, rrn, fieldOrNull(request, 0), "REQUEST", requestIso, null, null);
            transactionMetaDAO.saveMeta(connection, stan, fieldOrNull(request, 32), fieldOrNull(request, 33), fieldOrNull(request, 3));
        });
    }

    public void persistOutgoingResponse(ISOMsg request, ISOMsg response, String eventType) {
        String stan = fieldOrDefault(response, 11, fieldOrDefault(request, 11, "000000"));
        String rrn = fieldOrDefault(response, 37, fieldOrDefault(request, 37, "000000000000"));
        String rc = fieldOrNull(response, 39);

        if (!DatabaseSupport.isJdbcEnabled()) {
            transactionDAO.updateResponse(stan, rrn, rc, eventType);
            return;
        }

        withTransaction(connection -> {
            transactionDAO.updateResponse(connection, stan, rrn, rc, eventType);
            String responseIso = dumpIso(response);
            eventDAO.saveIsoEvent(connection, stan, rrn, fieldOrNull(response, 0), eventType, null, responseIso, rc);
        });
    }

    private void withTransaction(SqlWork work) {
        try (Connection connection = DatabaseSupport.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.apply(connection);
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Transaction failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("Transaction failed", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void apply(Connection connection) throws Exception;
    }

    private String fieldOrNull(ISOMsg msg, int field) {
        try {
            if (field == 0) {
                return msg.getMTI();
            }
            return msg.hasField(field) ? msg.getString(field) : null;
        } catch (ISOException e) {
            return null;
        }
    }

    private String fieldOrDefault(ISOMsg msg, int field, String fallback) {
        String value = fieldOrNull(msg, field);
        return value == null ? fallback : value;
    }

    private boolean isReversal(ISOMsg msg) {
        String mti = fieldOrNull(msg, 0);
        return mti != null && mti.startsWith("04");
    }

    private String dumpIso(ISOMsg msg) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        msg.dump(ps, "");
        return baos.toString(StandardCharsets.UTF_8);
    }
}
