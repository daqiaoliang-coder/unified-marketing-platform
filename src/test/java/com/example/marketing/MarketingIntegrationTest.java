package com.example.marketing;

import com.example.marketing.domain.BusinessException;
import com.example.marketing.domain.CartRequest;
import com.example.marketing.domain.Promotion;
import com.example.marketing.domain.Quote;
import com.example.marketing.domain.TransactionModels.EventInput;
import com.example.marketing.domain.TransactionModels.EventState;
import com.example.marketing.domain.TransactionModels.EventType;
import com.example.marketing.domain.TransactionModels.OrderEvent;
import com.example.marketing.domain.TransactionModels.Resource;
import com.example.marketing.domain.TransactionModels.ResourceInput;
import com.example.marketing.domain.TransactionModels.Reward;
import com.example.marketing.domain.TransactionModels.State;
import com.example.marketing.domain.TransactionModels.Submit;
import com.example.marketing.persistence.MarketingStore;
import com.example.marketing.service.CatalogService;
import com.example.marketing.service.EventService;
import com.example.marketing.service.EventWorker;
import com.example.marketing.service.QuoteService;
import com.example.marketing.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:marketing-test;DB_CLOSE_DELAY=-1",
        "marketing.events.delay-ms=3600000"
})
@AutoConfigureMockMvc
@Import(MarketingIntegrationTest.TestClockConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class MarketingIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-30T10:00:00Z");
    private static final String RESOURCE_ID = "stock";
    private static final String PROMOTION_ID = "promotion";
    private static final String ORDER_ID = "order-1";
    private static final Promotion.Benefit BENEFIT = new Promotion.Benefit(25, "gift-original", 2);

    @Autowired CatalogService catalog;
    @Autowired QuoteService quotes;
    @Autowired TransactionService transactions;
    @Autowired EventService events;
    @Autowired EventWorker worker;
    @Autowired MarketingStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;

    // Do not wrap tests in a transaction: service commits and rollback must be observable.
    @BeforeEach
    void resetTestDatabase() throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL().split(";", 2)[0])
                    .isEqualTo("jdbc:h2:mem:marketing-test");
        }
        jdbc.update("DELETE FROM reward_grants");
        jdbc.update("DELETE FROM order_events");
        jdbc.update("DELETE FROM snapshots");
        jdbc.update("DELETE FROM promotions");
        jdbc.update("DELETE FROM resources");
        clock.set(START);
    }

    @Test
    void tryReservesAndConfirmConsumesExactlyOnce() {
        seedPromotion(3);
        var command = submit("tx", cart("cart"));
        var quoted = quotes.calculate(command.cart());
        assertThat(command.expectedPayable()).isEqualTo(9_000);

        var trying = transactions.tryOrder(command);
        assertThat(trying.status()).isEqualTo(State.TRYING);
        assertThat(trying.request()).isEqualTo(command.cart());
        assertThat(trying.quote()).isEqualTo(quoted);
        assertThat(transactions.get("tx")).isEqualTo(trying);
        assertResource(RESOURCE_ID, 3, 2, 1, 0);
        assertThat(transactions.rewards("tx")).isEmpty();
        assertThat(transactions.tryOrder(command)).isEqualTo(trying);
        assertResource(RESOURCE_ID, 3, 2, 1, 0);

        clock.set(START.plusSeconds(1));
        var confirmed = transactions.confirm("tx");
        assertThat(confirmed.status()).isEqualTo(State.CONFIRMED);
        assertThat(confirmed.createdAt()).isEqualTo(trying.createdAt());
        assertThat(confirmed.updatedAt()).isEqualTo(clock.instant());
        assertThat(confirmed.quote()).isEqualTo(quoted);
        assertResource(RESOURCE_ID, 3, 2, 0, 1);
        clock.set(START.plusSeconds(2));
        assertThat(transactions.confirm("tx")).isEqualTo(confirmed);
        assertThat(transactions.tryOrder(command)).isEqualTo(confirmed);
        assertThat(transactions.rewards("tx")).isEmpty();
        assertResource(RESOURCE_ID, 3, 2, 0, 1);
        assertSnapshotCount(1);
    }

    @Test
    void sameTransactionIdRejectsChangedCartOrExpectedPayable() {
        seedPromotion(2);
        var command = submit("tx", cart("cart"));
        var original = transactions.tryOrder(command);
        var changedCart = new CartRequest("cart", "another-customer", command.cart().orders());

        assertBusiness("IDEMPOTENCY_CONFLICT", 409,
                () -> transactions.tryOrder(new Submit("tx", changedCart, command.expectedPayable())));
        assertBusiness("IDEMPOTENCY_CONFLICT", 409,
                () -> transactions.tryOrder(new Submit("tx", command.cart(), command.expectedPayable() + 1)));
        assertThat(transactions.get("tx")).isEqualTo(original);
        assertResource(RESOURCE_ID, 2, 1, 1, 0);
        assertSnapshotCount(1);
    }

    @Test
    void differentTransactionIdCannotReuseAnExistingCart() {
        seedPromotion(2);
        var command = submit("tx", cart("shared-cart"));
        transactions.tryOrder(command);

        assertBusiness("CART_ALREADY_SUBMITTED", 409,
                () -> transactions.tryOrder(new Submit("other-tx", command.cart(), command.expectedPayable())));
        assertThat(store.snapshot("other-tx")).isEmpty();
        assertResource(RESOURCE_ID, 2, 1, 1, 0);
        assertSnapshotCount(1);
    }

    @Test
    void insufficientSecondResourceRollsBackSnapshotAndFirstReservation() {
        seedTwoStackablePromotions();
        var command = twoPromotionSubmit("tx", "cart");

        assertBusiness("RESOURCE_EXHAUSTED", 409, () -> transactions.tryOrder(command));

        assertThat(store.snapshot("tx")).isEmpty();
        assertSnapshotCount(0);
        assertResource("A-stock", 3, 3, 0, 0);
        assertResource("B-stock", 0, 0, 0, 0);
    }

    @Test
    void twentyConcurrentCartsCannotOversellLimitedInventory() throws Exception {
        seedPromotion(5);
        var commands = new ArrayList<Submit>();
        for (int i = 0; i < 20; i++) {
            commands.add(submit("tx-" + i, cart("cart-" + i)));
        }

        var accepted = concurrently(20, index -> {
            try {
                assertThat(transactions.tryOrder(commands.get(index)).status()).isEqualTo(State.TRYING);
                return true;
            } catch (BusinessException error) {
                assertThat(error.code()).isEqualTo("RESOURCE_EXHAUSTED");
                assertThat(error.status()).isEqualTo(409);
                return false;
            }
        });

        assertThat(accepted.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        assertResource(RESOURCE_ID, 5, 0, 5, 0);
        assertSnapshotCount(5);
        for (int i = 0; i < commands.size(); i++) {
            String id = commands.get(i).transactionId();
            if (accepted.get(i)) {
                assertThat(transactions.confirm(id).status()).isEqualTo(State.CONFIRMED);
            } else {
                assertThat(store.snapshot(id)).isEmpty();
            }
        }
        assertResource(RESOURCE_ID, 5, 0, 0, 5);
    }

    @Test
    void concurrentDuplicateTryAndConfirmDoNotDeductTwice() throws Exception {
        seedPromotion(2);
        var command = submit("shared-tx", cart("shared-cart"));

        var attempts = concurrently(20, ignored -> transactions.tryOrder(command));
        assertThat(attempts).hasSize(20).allSatisfy(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(State.TRYING);
            assertThat(snapshot).isEqualTo(attempts.get(0));
        });
        assertResource(RESOURCE_ID, 2, 1, 1, 0);
        assertSnapshotCount(1);

        var confirmations = concurrently(20, ignored -> transactions.confirm("shared-tx"));
        assertThat(confirmations).hasSize(20).allSatisfy(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(State.CONFIRMED);
            assertThat(snapshot).isEqualTo(confirmations.get(0));
        });
        assertResource(RESOURCE_ID, 2, 1, 0, 1);
        assertSnapshotCount(1);
    }

    @Test
    void emptyCancelCreatesTombstoneAndRejectsLateTry() {
        seedPromotion(1);
        var command = submit("tx", cart("cart"));

        var tombstone = transactions.cancel("tx");
        assertThat(tombstone.status()).isEqualTo(State.CANCELLED);
        assertThat(tombstone.request()).isNull();
        assertThat(tombstone.quote()).isNull();
        assertThat(transactions.get("tx")).isEqualTo(tombstone);
        assertThat(transactions.cancel("tx")).isEqualTo(tombstone);
        assertBusiness("TRANSACTION_CANCELLED", 409, () -> transactions.tryOrder(command));
        assertBusiness("TRANSACTION_CANCELLED", 409, () -> transactions.confirm("tx"));
        assertResource(RESOURCE_ID, 1, 1, 0, 0);
        assertSnapshotCount(1);
    }

    @Test
    void cancellingTryingTransactionReleasesReservationsExactlyOnce() {
        seedPromotion(2);
        var trying = transactions.tryOrder(submit("tx", cart("cart")));
        assertResource(RESOURCE_ID, 2, 1, 1, 0);

        var cancelled = transactions.cancel("tx");
        assertThat(cancelled.status()).isEqualTo(State.CANCELLED);
        assertThat(cancelled.quote()).isEqualTo(trying.quote());
        assertResource(RESOURCE_ID, 2, 2, 0, 0);
        assertThat(transactions.cancel("tx")).isEqualTo(cancelled);
        assertBusiness("TRANSACTION_CANCELLED", 409, () -> transactions.confirm("tx"));
        assertResource(RESOURCE_ID, 2, 2, 0, 0);
        assertThat(transactions.rewards("tx")).isEmpty();
    }

    @Test
    void tccCancelCannotReleaseConfirmedConsumption() {
        seedPromotion(2);
        var confirmed = transactions.placeOrder(submit("tx", cart("cart")));

        assertBusiness("TRANSACTION_CONFIRMED", 409, () -> transactions.cancel("tx"));

        assertThat(transactions.get("tx")).isEqualTo(confirmed);
        assertResource(RESOURCE_ID, 2, 1, 0, 1);
    }

    @Test
    void directPlaceOrderAtomicallyConfirmsAndIsIdempotent() {
        seedPromotion(2);
        var command = submit("tx", cart("cart"));

        var confirmed = transactions.placeOrder(command);
        assertThat(confirmed.status()).isEqualTo(State.CONFIRMED);
        assertThat(transactions.get("tx")).isEqualTo(confirmed);
        assertResource(RESOURCE_ID, 2, 1, 0, 1);
        assertThat(transactions.placeOrder(command)).isEqualTo(confirmed);
        assertThat(transactions.tryOrder(command)).isEqualTo(confirmed);
        assertResource(RESOURCE_ID, 2, 1, 0, 1);
        assertSnapshotCount(1);
    }

    @Test
    void directPlaceOrderFailureRollsBackEveryReservation() {
        seedTwoStackablePromotions();
        var command = twoPromotionSubmit("tx", "cart");

        assertBusiness("RESOURCE_EXHAUSTED", 409, () -> transactions.placeOrder(command));

        assertSnapshotCount(0);
        assertResource("A-stock", 3, 3, 0, 0);
        assertResource("B-stock", 0, 0, 0, 0);
    }

    @Test
    void paidGrantsCoinsAndGiftsOnlyOnceAcrossDuplicateEvents() {
        seedPromotion(2);
        transactions.placeOrder(submit("tx", cart("cart")));
        var input = new EventInput("paid-1", "tx", EventType.PAID);

        var accepted = events.accept(input);
        assertThat(accepted.status()).isEqualTo(EventState.PENDING);
        assertThat(events.accept(input)).isEqualTo(accepted);
        assertThat(transactions.rewards("tx")).isEmpty();
        worker.runOnce();
        assertProcessed("paid-1", 1);
        assertOriginalRewards("tx", "ACTIVE");
        var firstRewards = transactions.rewards("tx");
        assertThat(events.accept(input)).isEqualTo(events.get("paid-1"));
        events.accept(new EventInput("paid-2", "tx", EventType.PAID));
        worker.runOnce();
        worker.runOnce();

        assertProcessed("paid-1", 1);
        assertProcessed("paid-2", 1);
        assertThat(transactions.rewards("tx")).isEqualTo(firstRewards);
        assertResource(RESOURCE_ID, 2, 1, 0, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events", Integer.class)).isEqualTo(2);
    }

    @Test
    void cancellationEventReturnsConsumedResourceRevokesRewardsAndIgnoresLatePaid() {
        seedPromotion(2);
        transactions.placeOrder(submit("tx", cart("cart")));
        processEvent("paid", "tx", EventType.PAID);
        var originalIds = transactions.rewards("tx").stream().map(Reward::id).toList();
        assertResource(RESOURCE_ID, 2, 1, 0, 1);

        processEvent("cancelled", "tx", EventType.CANCELLED);
        assertThat(transactions.get("tx").status()).isEqualTo(State.CANCELLED);
        assertResource(RESOURCE_ID, 2, 2, 0, 0);
        assertOriginalRewards("tx", "REVOKED");
        processEvent("cancelled", "tx", EventType.CANCELLED);
        processEvent("cancelled-again", "tx", EventType.CANCELLED);
        processEvent("late-paid", "tx", EventType.PAID);
        processEvent("paid", "tx", EventType.PAID);

        assertOriginalRewards("tx", "REVOKED");
        assertThat(transactions.rewards("tx")).extracting(Reward::id).containsExactlyElementsOf(originalIds);
        assertResource(RESOURCE_ID, 2, 2, 0, 0);
        assertThat(transactions.get("tx").status()).isEqualTo(State.CANCELLED);
    }

    @Test
    void cancellationBeforeAnyPaidEventPreventsAllRewards() {
        seedPromotion(1);
        transactions.placeOrder(submit("tx", cart("cart")));

        processEvent("cancelled", "tx", EventType.CANCELLED);
        processEvent("late-paid", "tx", EventType.PAID);

        assertThat(transactions.rewards("tx")).isEmpty();
        assertThat(transactions.get("tx").status()).isEqualTo(State.CANCELLED);
        assertResource(RESOURCE_ID, 1, 1, 0, 0);
    }

    @Test
    void eventIdCannotBeReusedWithDifferentTypeOrTransaction() {
        seedPromotion(2);
        transactions.placeOrder(submit("tx-1", cart("cart-1")));
        transactions.placeOrder(submit("tx-2", cart("cart-2")));
        var original = events.accept(new EventInput("event", "tx-1", EventType.PAID));

        assertBusiness("EVENT_ID_CONFLICT", 409,
                () -> events.accept(new EventInput("event", "tx-1", EventType.CANCELLED)));
        assertBusiness("EVENT_ID_CONFLICT", 409,
                () -> events.accept(new EventInput("event", "tx-2", EventType.PAID)));
        assertThat(events.get("event")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_events", Integer.class)).isEqualTo(1);
        worker.runOnce();
        assertOriginalRewards("tx-1", "ACTIVE");
        assertThat(transactions.rewards("tx-2")).isEmpty();
    }

    @Test
    void earlyPaidIsPersistedForRetryAndSucceedsAfterConfirmation() {
        seedPromotion(1);
        transactions.tryOrder(submit("tx", cart("cart")));
        events.accept(new EventInput("paid", "tx", EventType.PAID));

        worker.runOnce();
        var failedAttempt = events.get("paid");
        assertThat(failedAttempt.status()).isEqualTo(EventState.PENDING);
        assertThat(failedAttempt.attempts()).isEqualTo(1);
        assertThat(failedAttempt.lastError()).isEqualTo("ORDER_NOT_CONFIRMED");
        assertThat(failedAttempt.nextAttemptAt()).isEqualTo(START.plusSeconds(2));
        assertThat(events.dueEvents()).isEmpty();
        assertThat(transactions.rewards("tx")).isEmpty();
        assertThat(transactions.get("tx").status()).isEqualTo(State.TRYING);
        assertResource(RESOURCE_ID, 1, 0, 1, 0);

        transactions.confirm("tx");
        clock.set(failedAttempt.nextAttemptAt().minusSeconds(1));
        worker.runOnce();
        assertThat(events.get("paid")).isEqualTo(failedAttempt);
        assertThat(transactions.rewards("tx")).isEmpty();
        clock.set(failedAttempt.nextAttemptAt());
        assertThat(events.dueEvents()).containsExactly("paid");
        worker.runOnce();
        assertProcessed("paid", 2);
        assertOriginalRewards("tx", "ACTIVE");
        assertResource(RESOURCE_ID, 1, 0, 0, 1);
    }

    @Test
    void eightFailedAttemptsStopAutomaticRetryAndManualRetryCanRecover() throws Exception {
        seedPromotion(1);
        transactions.tryOrder(submit("tx", cart("cart")));
        events.accept(new EventInput("paid", "tx", EventType.PAID));
        assertBusiness("EVENT_NOT_FAILED", 409, () -> events.retry("paid"));

        for (int attempt = 1; attempt <= 8; attempt++) {
            Instant attemptedAt = clock.instant();
            worker.runOnce();
            var event = events.get("paid");
            assertThat(event.attempts()).isEqualTo(attempt);
            assertThat(event.status()).isEqualTo(attempt == 8 ? EventState.FAILED : EventState.PENDING);
            assertThat(event.lastError()).isEqualTo("ORDER_NOT_CONFIRMED");
            long delay = Math.min(60, 1L << Math.min(attempt, 6));
            assertThat(event.nextAttemptAt()).isEqualTo(attemptedAt.plusSeconds(delay));
            assertThat(transactions.rewards("tx")).isEmpty();
            clock.set(event.nextAttemptAt());
        }
        var failed = events.get("paid");
        clock.set(clock.instant().plusSeconds(3_600));
        assertThat(events.dueEvents()).isEmpty();
        worker.runOnce();
        assertThat(events.get("paid")).isEqualTo(failed);
        assertResource(RESOURCE_ID, 1, 0, 1, 0);

        mvc.perform(post("/api/events/paid/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        var retried = events.get("paid");
        assertThat(retried.nextAttemptAt()).isEqualTo(clock.instant());
        assertThat(retried.lastError()).isNull();
        assertThat(retried.createdAt()).isEqualTo(failed.createdAt());
        assertThat(events.dueEvents()).containsExactly("paid");
        transactions.confirm("tx");
        worker.runOnce();
        assertProcessed("paid", 1);
        assertOriginalRewards("tx", "ACTIVE");
        assertResource(RESOURCE_ID, 1, 0, 0, 1);
        assertBusiness("EVENT_NOT_FAILED", 409, () -> events.retry("paid"));
    }

    @Test
    void confirmAndPaidUsePersistedQuoteAfterCatalogChanges() {
        seedPromotion(3);
        var command = submit("tx", cart("cart"));
        var trying = transactions.tryOrder(command);
        Quote originalQuote = trying.quote();
        assertThat(originalQuote.payableTotal()).isEqualTo(9_000);
        catalog.createResource(new ResourceInput("new-stock", 10));
        catalog.savePromotion(promotion(PROMOTION_ID, Promotion.Type.COUPON, "new-stock", 2,
                2_000, new Promotion.Benefit(999, "gift-new", 7), Map.of("merchant", 10_000), Set.of()));
        clock.set(START.plusSeconds(60));
        var updatedQuote = quotes.calculate(command.cart());
        assertThat(updatedQuote.payableTotal()).isEqualTo(8_000);
        assertThat(updatedQuote.promotions()).singleElement().satisfies(applied -> {
            assertThat(applied.resourceId()).isEqualTo("new-stock");
            assertThat(applied.resourceUnits()).isEqualTo(2);
            assertThat(applied.benefit().giftSku()).isEqualTo("gift-new");
        });

        assertThat(transactions.tryOrder(command)).isEqualTo(trying);
        var confirmed = transactions.confirm("tx");
        assertThat(confirmed.quote()).isEqualTo(originalQuote);
        assertThat(confirmed.request()).isEqualTo(command.cart());
        assertThat(confirmed.createdAt()).isEqualTo(trying.createdAt());
        assertThat(confirmed.quote().funding()).containsExactlyInAnyOrderEntriesOf(Map.of("platform", 1_000L));
        assertResource(RESOURCE_ID, 3, 2, 0, 1);
        assertResource("new-stock", 10, 10, 0, 0);
        processEvent("paid", "tx", EventType.PAID);
        assertOriginalRewards("tx", "ACTIVE");
        assertThat(transactions.get("tx").quote()).isEqualTo(originalQuote);
        assertResource(RESOURCE_ID, 3, 2, 0, 1);
        assertResource("new-stock", 10, 10, 0, 0);
    }

    @Test
    void staleExpectedPayableAfterPromotionChangeLeavesNoSnapshotOrReservation() {
        seedPromotion(2);
        var oldCommand = submit("tx", cart("cart"));
        catalog.savePromotion(promotion(PROMOTION_ID, Promotion.Type.COUPON, RESOURCE_ID, 1,
                2_000, BENEFIT, Map.of("platform", 10_000), Set.of()));
        assertThat(quotes.calculate(oldCommand.cart()).payableTotal()).isEqualTo(8_000);

        assertBusiness("PRICE_CHANGED", 409, () -> transactions.tryOrder(oldCommand));
        assertThat(store.snapshot("tx")).isEmpty();
        assertSnapshotCount(0);
        assertResource(RESOURCE_ID, 2, 2, 0, 0);

        var refreshed = submit("tx", oldCommand.cart());
        assertThat(transactions.tryOrder(refreshed).quote().payableTotal()).isEqualTo(8_000);
        assertResource(RESOURCE_ID, 2, 1, 1, 0);
    }

    @Test
    void httpQuoteReturnsRealCentAmountsWithoutReservingResources() throws Exception {
        seedPromotion(2);
        var cart = cart("http-cart");

        var response = mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body(cart)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value("http-cart"))
                .andExpect(jsonPath("$.originalTotal").value(10_000))
                .andExpect(jsonPath("$.discountTotal").value(1_000))
                .andExpect(jsonPath("$.payableTotal").value(9_000))
                .andExpect(jsonPath("$.promotions[0].promotionId").value(PROMOTION_ID))
                .andExpect(jsonPath("$.funding.platform").value(1_000))
                .andReturn();

        assertThat(json.readValue(response.getResponse().getContentAsString(), Quote.class))
                .isEqualTo(quotes.calculate(cart));
        assertSnapshotCount(0);
        assertResource(RESOURCE_ID, 2, 2, 0, 0);
    }

    @ParameterizedTest(name = "invalid cart: {0}")
    @MethodSource("invalidCarts")
    void httpRejectsInvalidCartsForQuoteAndOrderSubmission(String description, CartRequest invalid) throws Exception {
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body(new Submit("invalid-tx", invalid, 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertSnapshotCount(0);
    }

    static Stream<Arguments> invalidCarts() {
        return Stream.of(
                Arguments.of("blank cart id", new CartRequest(" ", "customer", cart("cart").orders())),
                Arguments.of("null orders", new CartRequest("cart", "customer", null)),
                Arguments.of("empty orders", new CartRequest("cart", "customer", List.of())),
                Arguments.of("empty items", new CartRequest("cart", "customer",
                        List.of(new CartRequest.Order(ORDER_ID, List.of())))),
                Arguments.of("negative quantity", new CartRequest("cart", "customer",
                        List.of(new CartRequest.Order(ORDER_ID,
                                List.of(new CartRequest.Item("line-1", "sku-1", -1, 10_000)))))));
    }

    @Test
    void httpRejectsMalformedJsonAndUnknownEnums() throws Exception {
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        ObjectNode promotion = json.valueToTree(simplePromotion());
        promotion.put("type", "UNKNOWN_PROMOTION");
        mvc.perform(put("/api/promotions/{id}", PROMOTION_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body(promotion)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"event\",\"transactionId\":\"tx\",\"type\":\"UNKNOWN_EVENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(catalog.promotions()).isEmpty();
        assertSnapshotCount(0);
    }

    @Test
    void httpRejectsNumericEnumsWithoutCancellingATransaction() throws Exception {
        seedPromotion(1);
        transactions.placeOrder(submit("tx", cart("cart")));
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"numeric\",\"transactionId\":\"tx\",\"type\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(store.event("numeric")).isEmpty();
        assertThat(transactions.get("tx").status()).isEqualTo(State.CONFIRMED);
        assertResource(RESOURCE_ID, 1, 0, 0, 1);
    }

    @Test
    void httpRejectsFractionalMoneyInsteadOfTruncatingIt() throws Exception {
        seedPromotion(1);
        ObjectNode input = json.valueToTree(submit("tx", cart("cart")));
        input.put("expectedPayable", 9000.9);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body(input)))
                .andExpect(status().isBadRequest());
        assertSnapshotCount(0);
        assertResource(RESOURCE_ID, 1, 1, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {9_000, -1, 10_001})
    void httpRejectsInvalidFunding(int basisPoints) throws Exception {
        ObjectNode promotion = json.valueToTree(simplePromotion());
        promotion.set("fundingBps", json.valueToTree(Map.of("platform", basisPoints)));

        mvc.perform(put("/api/promotions/{id}", PROMOTION_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body(promotion)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(basisPoints == 9_000 ? "INVALID_FUNDING" : "INVALID_REQUEST"));

        assertThat(catalog.promotions()).isEmpty();
    }

    @Test
    void httpRejectsMismatchedPromotionPathId() throws Exception {
        mvc.perform(put("/api/promotions/another-id").contentType(MediaType.APPLICATION_JSON)
                        .content(body(simplePromotion())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ID_MISMATCH"));
        assertThat(catalog.promotions()).isEmpty();
    }

    @Test
    void httpMissingSnapshotReturns404() throws Exception {
        mvc.perform(get("/api/transactions/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
        mvc.perform(get("/api/transactions/missing/rewards"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
        mvc.perform(post("/api/transactions/missing/confirm"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
        assertSnapshotCount(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/transactions/try", "/api/orders"})
    void httpResourceExhaustionReturns409AndRollsBack(String endpoint) throws Exception {
        seedPromotion(0);
        var command = submit("tx", cart("cart"));

        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(command)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_EXHAUSTED"));

        assertSnapshotCount(0);
        assertResource(RESOURCE_ID, 0, 0, 0, 0);
    }

    @Test
    void httpEventsReturn202AndExposePendingThenProcessedStateAndRewards() throws Exception {
        seedPromotion(1);
        var command = submit("tx", cart("cart"));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        var input = new EventInput("paid", "tx", EventType.PAID);
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(body(input)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("paid"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/events/paid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("tx"))
                .andExpect(jsonPath("$.type").value("PAID"))
                .andExpect(jsonPath("$.attempts").value(0));

        worker.runOnce();
        mvc.perform(get("/api/events/paid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.attempts").value(1));
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(body(input)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
        mvc.perform(get("/api/transactions/tx/rewards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("COINS"))
                .andExpect(jsonPath("$[0].quantity").value(25))
                .andExpect(jsonPath("$[1].kind").value("GIFT"))
                .andExpect(jsonPath("$[1].referenceCode").value("gift-original"));
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                        .content(body(new EventInput("paid", "tx", EventType.CANCELLED))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"));
        assertOriginalRewards("tx", "ACTIVE");
        assertResource(RESOURCE_ID, 1, 0, 0, 1);
    }

    @Test
    void httpTccTryConfirmAndCancelUseRealTransactionLifecycle() throws Exception {
        seedPromotion(2);
        var first = submit("tx-confirm", cart("cart-confirm"));
        mvc.perform(post("/api/transactions/try").contentType(MediaType.APPLICATION_JSON).content(body(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRYING"));
        assertResource(RESOURCE_ID, 2, 1, 1, 0);
        var changedCart = new CartRequest(first.cart().cartId(), "different-customer", first.cart().orders());
        mvc.perform(post("/api/transactions/try").contentType(MediaType.APPLICATION_JSON)
                        .content(body(new Submit(first.transactionId(), changedCart, first.expectedPayable()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/transactions/try").contentType(MediaType.APPLICATION_JSON)
                        .content(body(new Submit("other-tx", first.cart(), first.expectedPayable()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_ALREADY_SUBMITTED"));
        mvc.perform(post("/api/transactions/tx-confirm/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/transactions/tx-confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote.payableTotal").value(9_000));
        mvc.perform(post("/api/transactions/tx-confirm/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_CONFIRMED"));
        assertResource(RESOURCE_ID, 2, 1, 0, 1);

        var second = submit("tx-cancel", cart("cart-cancel"));
        mvc.perform(post("/api/transactions/try").contentType(MediaType.APPLICATION_JSON).content(body(second)))
                .andExpect(status().isOk());
        assertResource(RESOURCE_ID, 2, 0, 1, 1);
        mvc.perform(post("/api/transactions/tx-cancel/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/transactions/tx-cancel/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertResource(RESOURCE_ID, 2, 1, 0, 1);
        assertSnapshotCount(2);
    }

    private static CartRequest cart(String cartId) {
        return new CartRequest(cartId, "customer", List.of(new CartRequest.Order(ORDER_ID,
                List.of(new CartRequest.Item("line-1", "sku-1", 1, 10_000)))));
    }

    private static Promotion promotion(String id, Promotion.Type type, String resourceId, long resourceUnits,
                                       long discount, Promotion.Benefit benefit, Map<String, Integer> funding,
                                       Set<Promotion.Type> compatibleTypes) {
        return new Promotion(id, "Promotion " + id, type, Promotion.Scope.ORDER, 10,
                new Promotion.Condition(0, 1, Set.of(), Set.of(), null, null),
                new Promotion.Discount(Promotion.DiscountKind.AMOUNT_OFF, discount),
                new Promotion.Stacking(false, compatibleTypes, null),
                funding, resourceId, resourceUnits, benefit);
    }

    private static Promotion simplePromotion() {
        return promotion(PROMOTION_ID, Promotion.Type.COUPON, null, 0,
                1_000, BENEFIT, Map.of("platform", 10_000), Set.of());
    }

    private void seedPromotion(long total) {
        catalog.createResource(new ResourceInput(RESOURCE_ID, total));
        catalog.savePromotion(promotion(PROMOTION_ID, Promotion.Type.COUPON, RESOURCE_ID, 1,
                1_000, BENEFIT, Map.of("platform", 10_000), Set.of()));
    }

    private void seedTwoStackablePromotions() {
        catalog.createResource(new ResourceInput("A-stock", 3));
        catalog.createResource(new ResourceInput("B-stock", 0));
        // TransactionService reserves resource ids in sorted order; A must succeed before B fails.
        catalog.savePromotion(promotion("coupon", Promotion.Type.COUPON, "A-stock", 1,
                1_000, Promotion.Benefit.NONE, Map.of("platform", 10_000), Set.of(Promotion.Type.FULL_REDUCTION)));
        catalog.savePromotion(promotion("reduction", Promotion.Type.FULL_REDUCTION, "B-stock", 1,
                1_000, Promotion.Benefit.NONE, Map.of("platform", 10_000), Set.of(Promotion.Type.COUPON)));
    }

    private Submit submit(String transactionId, CartRequest cart) {
        return new Submit(transactionId, cart, quotes.calculate(cart).payableTotal());
    }

    private Submit twoPromotionSubmit(String transactionId, String cartId) {
        var cart = cart(cartId);
        var quote = quotes.calculate(cart);
        assertThat(quote.payableTotal()).isEqualTo(8_000);
        assertThat(quote.promotions()).extracting(Quote.AppliedPromotion::resourceId)
                .containsExactlyInAnyOrder("A-stock", "B-stock");
        return new Submit(transactionId, cart, quote.payableTotal());
    }

    private void assertResource(String id, long total, long available, long reserved, long consumed) {
        assertThat(store.resource(id)).contains(new Resource(id, total, available, reserved, consumed));
        assertThat(total).isEqualTo(available + reserved + consumed);
    }

    private void assertSnapshotCount(int expected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM snapshots", Integer.class)).isEqualTo(expected);
    }

    private static void assertBusiness(String code, int status, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.status()).isEqualTo(status);
        });
    }

    private void assertOriginalRewards(String transactionId, String status) {
        assertThat(transactions.rewards(transactionId))
                .extracting(Reward::transactionId, Reward::orderId, Reward::promotionId,
                        Reward::kind, Reward::referenceCode, Reward::quantity, Reward::status)
                .containsExactlyInAnyOrder(
                        tuple(transactionId, ORDER_ID, PROMOTION_ID, "COINS", "COIN", 25L, status),
                        tuple(transactionId, ORDER_ID, PROMOTION_ID, "GIFT", "gift-original", 2L, status));
    }

    private void processEvent(String eventId, String transactionId, EventType type) {
        events.accept(new EventInput(eventId, transactionId, type));
        worker.runOnce();
        assertProcessed(eventId, 1);
    }

    private void assertProcessed(String eventId, int attempts) {
        OrderEvent event = events.get(eventId);
        assertThat(event.status()).isEqualTo(EventState.PROCESSED);
        assertThat(event.attempts()).isEqualTo(attempts);
        assertThat(event.lastError()).isNull();
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private static <T> List<T> concurrently(int count, IntFunction<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start latch timed out");
                    }
                    return action.apply(index);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all concurrent callers are ready").isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).as("executor terminated").isTrue();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(new AtomicReference<>(START), ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
