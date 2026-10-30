package com.example.marketing.persistence;

import com.example.marketing.domain.*;
import com.example.marketing.domain.TransactionModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketingStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MarketingStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void lock() {
        jdbc.queryForObject("SELECT id FROM platform_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public List<Promotion> promotions() {
        return jdbc.query("SELECT payload FROM promotions ORDER BY id", (r, n) -> decode(r.getString(1), Promotion.class));
    }

    public void savePromotion(Promotion promotion) {
        jdbc.update("MERGE INTO promotions (id, payload) KEY(id) VALUES (?, ?)", promotion.id(), encode(promotion));
    }

    public List<Resource> resources() {
        return jdbc.query("SELECT * FROM resources ORDER BY id", (r, n) -> resource(r));
    }

    public Optional<Resource> resource(String id) {
        return jdbc.query("SELECT * FROM resources WHERE id = ?", (r, n) -> resource(r), id).stream().findFirst();
    }

    public void insertResource(ResourceInput input) {
        jdbc.update("INSERT INTO resources (id, total, available, reserved, consumed) VALUES (?, ?, ?, 0, 0)",
                input.id(), input.total(), input.total());
    }

    public boolean reserve(String id, long units) {
        return jdbc.update("UPDATE resources SET available = available - ?, reserved = reserved + ? WHERE id = ? AND available >= ?",
                units, units, id, units) == 1;
    }

    public void consume(String id, long units) {
        requireUpdate(jdbc.update("UPDATE resources SET reserved = reserved - ?, consumed = consumed + ? WHERE id = ? AND reserved >= ?",
                units, units, id, units));
    }

    public void release(String id, long units, boolean confirmed) {
        String counter = confirmed ? "consumed" : "reserved";
        requireUpdate(jdbc.update("UPDATE resources SET " + counter + " = " + counter + " - ?, available = available + ? WHERE id = ? AND " + counter + " >= ?",
                units, units, id, units));
    }

    public Optional<Snapshot> snapshot(String transactionId) {
        return jdbc.query("SELECT * FROM snapshots WHERE transaction_id = ?", (r, n) -> snapshot(r), transactionId).stream().findFirst();
    }

    public boolean cartExists(String cartId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM snapshots WHERE cart_id = ?", Integer.class, cartId) > 0;
    }

    public void insertSnapshot(Snapshot value) {
        jdbc.update("INSERT INTO snapshots (transaction_id, cart_id, status, request_json, quote_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                value.transactionId(), value.request() == null ? null : value.request().cartId(), value.status().name(),
                value.request() == null ? null : encode(value.request()), value.quote() == null ? null : encode(value.quote()),
                timestamp(value.createdAt()), timestamp(value.updatedAt()));
    }

    public void state(String transactionId, State state, Instant now) {
        requireUpdate(jdbc.update("UPDATE snapshots SET status = ?, updated_at = ? WHERE transaction_id = ?",
                state.name(), timestamp(now), transactionId));
    }

    public Optional<OrderEvent> event(String id) {
        return jdbc.query("SELECT * FROM order_events WHERE event_id = ?", (r, n) -> event(r), id).stream().findFirst();
    }

    public void insertEvent(EventInput value, Instant now) {
        jdbc.update("INSERT INTO order_events (event_id, transaction_id, event_type, status, attempts, next_attempt_at, created_at) VALUES (?, ?, ?, 'PENDING', 0, ?, ?)",
                value.eventId(), value.transactionId(), value.type().name(), timestamp(now), timestamp(now));
    }

    public List<String> dueEvents(Instant now) {
        return jdbc.query("SELECT event_id FROM order_events WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY created_at, event_id LIMIT 100",
                (r, n) -> r.getString(1), timestamp(now));
    }

    public void eventResult(String id, EventState state, int attempts, Instant next, String error) {
        requireUpdate(jdbc.update("UPDATE order_events SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ? WHERE event_id = ?",
                state.name(), attempts, timestamp(next), error, id));
    }

    public boolean rewardExists(String transactionId, String orderId, String promotionId, String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reward_grants WHERE transaction_id = ? AND order_id = ? AND promotion_id = ? AND kind = ?",
                Integer.class, transactionId, orderId, promotionId, kind) > 0;
    }

    public void insertReward(Reward value) {
        jdbc.update("INSERT INTO reward_grants (id, transaction_id, order_id, promotion_id, kind, reference_code, quantity, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                value.id(), value.transactionId(), value.orderId(), value.promotionId(), value.kind(), value.referenceCode(), value.quantity(), value.status());
    }

    public void revokeRewards(String transactionId) {
        jdbc.update("UPDATE reward_grants SET status = 'REVOKED' WHERE transaction_id = ? AND status = 'ACTIVE'", transactionId);
    }

    public List<Reward> rewards(String transactionId) {
        return jdbc.query("SELECT * FROM reward_grants WHERE transaction_id = ? ORDER BY order_id, promotion_id, kind", (r, n) ->
                new Reward(r.getString("id"), r.getString("transaction_id"), r.getString("order_id"), r.getString("promotion_id"),
                        r.getString("kind"), r.getString("reference_code"), r.getLong("quantity"), r.getString("status")), transactionId);
    }

    private Snapshot snapshot(ResultSet r) throws SQLException {
        return new Snapshot(r.getString("transaction_id"), State.valueOf(r.getString("status")),
                decode(r.getString("request_json"), CartRequest.class), decode(r.getString("quote_json"), Quote.class),
                instant(r, "created_at"), instant(r, "updated_at"));
    }

    private OrderEvent event(ResultSet r) throws SQLException {
        return new OrderEvent(r.getString("event_id"), r.getString("transaction_id"), EventType.valueOf(r.getString("event_type")),
                EventState.valueOf(r.getString("status")), r.getInt("attempts"), instant(r, "next_attempt_at"),
                r.getString("last_error"), instant(r, "created_at"));
    }

    private Resource resource(ResultSet r) throws SQLException {
        return new Resource(r.getString("id"), r.getLong("total"), r.getLong("available"), r.getLong("reserved"), r.getLong("consumed"));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize persisted value", e); }
    }

    private <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read persisted value", e); }
    }

    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static void requireUpdate(int count) {
        if (count != 1) throw new IllegalStateException("Resource or snapshot invariant violated");
    }
}
