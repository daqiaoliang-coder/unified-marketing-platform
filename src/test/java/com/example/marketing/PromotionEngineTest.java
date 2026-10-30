package com.example.marketing;

import com.example.marketing.domain.BusinessException;
import com.example.marketing.domain.CartRequest;
import com.example.marketing.domain.CartRequest.Item;
import com.example.marketing.domain.CartRequest.Order;
import com.example.marketing.domain.Promotion;
import com.example.marketing.domain.Promotion.Benefit;
import com.example.marketing.domain.Promotion.Condition;
import com.example.marketing.domain.Promotion.Discount;
import com.example.marketing.domain.Promotion.DiscountKind;
import com.example.marketing.domain.Promotion.Scope;
import com.example.marketing.domain.Promotion.Stacking;
import com.example.marketing.domain.Promotion.Type;
import com.example.marketing.domain.Quote;
import com.example.marketing.engine.PromotionEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static com.example.marketing.domain.Promotion.DiscountKind.AMOUNT_OFF;
import static com.example.marketing.domain.Promotion.DiscountKind.FIXED_UNIT_PRICE;
import static com.example.marketing.domain.Promotion.DiscountKind.PERCENT_OFF;
import static com.example.marketing.domain.Promotion.Scope.CART;
import static com.example.marketing.domain.Promotion.Scope.ORDER;
import static com.example.marketing.domain.Promotion.Type.COUPON;
import static com.example.marketing.domain.Promotion.Type.FULL_REDUCTION;
import static com.example.marketing.domain.Promotion.Type.TARGETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Set<Type> ALL_TYPES = Set.of(Type.values());

    @Test
    void noPromotionsReturnsOriginalPricesAndPassedClock() {
        var request = cart(new Order("o2", List.of(new Item("l2", "sku2", 2, 200))),
                new Order("o1", List.of(new Item("l1", "sku1", 3, 100), new Item("free", "gift", 1, 0))));
        var quote = calculate(request);
        assertThat(quote.cartId()).isEqualTo("cart");
        assertThat(quote.customerId()).isEqualTo("customer");
        assertThat(quote.calculatedAt()).isEqualTo(NOW);
        assertThat(quote.originalTotal()).isEqualTo(700);
        assertThat(quote.payableTotal()).isEqualTo(700);
        assertThat(quote.linePayables()).containsExactlyInAnyOrderEntriesOf(Map.of("l1", 300L, "l2", 400L, "free", 0L));
        assertThat(quote.promotions()).isEmpty();
        assertThat(quote.funding()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"AMOUNT_OFF,400,400", "PERCENT_OFF,1000,150", "FIXED_UNIT_PRICE,200,900"})
    void builtInDiscountKindsUseSubtotalAndQuantity(DiscountKind kind, long value, long expectedDiscount) {
        var quote = calculate(cart(new Order("o1", List.of(new Item("l1", "sku", 3, 500)))),
                p("p", COUPON, ORDER, kind, value));
        assertThat(quote.discountTotal()).isEqualTo(expectedDiscount);
        assertThat(quote.promotions()).hasSize(1);
    }

    @Test
    void amountOffIsAppliedOncePerOrderAndCappedAtMatchedSubtotal() {
        var quote = calculate(cart(new Order("o1", List.of(new Item("a", "sku", 1, 80), new Item("b", "sku", 1, 20)))),
                p("p", COUPON, ORDER, AMOUNT_OFF, 500));
        assertThat(quote.discountTotal()).isEqualTo(100);
        assertThat(quote.linePayables()).containsExactlyInAnyOrderEntriesOf(Map.of("a", 0L, "b", 0L));
        assertThat(quote.promotions()).hasSize(1);
    }

    @Test
    void percentageMeansDiscountBasisPointsAndRoundsDown() {
        var quote = calculate(single(1009), p("p", COUPON, CART, PERCENT_OFF, 1000));
        assertThat(quote.discountTotal()).isEqualTo(100);
        assertThat(quote.payableTotal()).isEqualTo(909);
    }

    @Test
    void fixedUnitPriceNeverRaisesCurrentPriceAndUsesCurrentBalanceAfterEarlierDiscounts() {
        var fixed = p("fixed", TARGETED, ORDER, FIXED_UNIT_PRICE, 800);
        var amount = priority(p("amount", COUPON, ORDER, AMOUNT_OFF, 300), 0);
        var quote = calculate(single(1000), amount, fixed);
        assertThat(quote.payableTotal()).isEqualTo(700);
        assertThat(ids(quote)).containsExactly("amount");
        assertThat(calculate(single(100), fixed).promotions()).isEmpty();
    }

    @Test
    void fixedUnitPriceCapsEachLineInsteadOfAveragingDifferentPrices() {
        var request = cart(new Order("order", List.of(
                new Item("cheap", "sku-cheap", 1, 1000),
                new Item("expensive", "sku-expensive", 1, 10000))));
        var quote = calculate(request, p("fixed", COUPON, ORDER, FIXED_UNIT_PRICE, 5000));
        assertThat(quote.payableTotal()).isEqualTo(6000);
        assertThat(quote.linePayables()).containsExactlyInAnyOrderEntriesOf(Map.of("cheap", 1000L, "expensive", 5000L));
        assertThat(quote.promotions().getFirst().allocations())
                .containsExactlyInAnyOrderEntriesOf(Map.of("cheap", 0L, "expensive", 5000L));
    }

    @Test
    void zeroTargetUnitPriceAndFullPercentageCanMakeTheOrderFree() {
        assertThat(calculate(single(1000), p("fixed", COUPON, ORDER, FIXED_UNIT_PRICE, 0)).payableTotal()).isZero();
        assertThat(calculate(single(1000), p("percent", COUPON, CART, PERCENT_OFF, 10000)).payableTotal()).isZero();
    }

    @Test
    void discountMultiplicationsUseBigIntegers() {
        var quote = calculate(single(Long.MAX_VALUE), p("percent", COUPON, CART, PERCENT_OFF, 9999));
        assertThat(quote.discountTotal()).isEqualTo(9_222_449_699_651_090_329L);
        var hugeTarget = p("fixed", COUPON, ORDER, FIXED_UNIT_PRICE, Long.MAX_VALUE);
        assertThat(calculate(cart(new Order("o1", List.of(new Item("l1", "sku", 2, 1)))), hugeTarget)
                .promotions()).isEmpty();
    }

    @Test
    void detectsAmountOverflowInsteadOfWrappingToNegativeMoney() {
        var request = cart(new Order("o1", List.of(new Item("l1", "sku", 2, Long.MAX_VALUE))));
        assertBusinessError(() -> new PromotionEngine().calculate(request, List.of(), NOW), "AMOUNT_OVERFLOW", 400);
        var sumOverflow = cart(new Order("o1", List.of(new Item("a", "sku", 1, Long.MAX_VALUE),
                new Item("b", "sku", 1, 1))));
        assertBusinessError(() -> new PromotionEngine().calculate(sumOverflow, List.of(), NOW), "AMOUNT_OVERFLOW", 400);
    }

    @Test
    void timeWindowIsStartInclusiveAndEndExclusive() {
        var atStart = condition(p("start", COUPON, CART, AMOUNT_OFF, 100),
                new Condition(0, 1, Set.of(), Set.of(), NOW, NOW.plusSeconds(1)));
        var atEnd = condition(p("end", COUPON, CART, AMOUNT_OFF, 1000),
                new Condition(0, 1, Set.of(), Set.of(), NOW.minusSeconds(1), NOW));
        var future = condition(p("future", COUPON, CART, AMOUNT_OFF, 1000),
                new Condition(0, 1, Set.of(), Set.of(), NOW.plusSeconds(1), null));
        assertThat(ids(calculate(single(1000), atEnd, future, atStart))).containsExactly("start");
        assertThat(new PromotionEngine().calculate(single(1000), List.of(atStart), NOW.plusSeconds(1)).promotions()).isEmpty();
    }

    @Test
    void customerAndSkuFilteringLeavesUnmatchedLinesUntouched() {
        var request = cart(new Order("o1", List.of(new Item("hit", "sku-hit", 2, 100),
                new Item("miss", "sku-miss", 4, 1000))));
        var hit = condition(p("hit", TARGETED, ORDER, PERCENT_OFF, 5000),
                new Condition(200, 2, Set.of("sku-hit"), Set.of("customer"), null, null));
        var wrongCustomer = condition(p("other-customer", COUPON, CART, AMOUNT_OFF, 5000),
                new Condition(0, 1, Set.of(), Set.of("someone-else"), null, null));
        var wrongSku = condition(p("other-sku", COUPON, CART, AMOUNT_OFF, 5000),
                new Condition(0, 1, Set.of("absent"), Set.of(), null, null));
        var quote = calculate(request, hit, wrongCustomer, wrongSku);
        assertThat(ids(quote)).containsExactly("hit");
        assertThat(quote.linePayables()).containsEntry("hit", 100L).containsEntry("miss", 4000L);
    }

    @Test
    void spendAndQuantityThresholdsUseOnlyMatchedLines() {
        var request = cart(new Order("o1", List.of(new Item("hit", "sku-hit", 1, 100),
                new Item("miss", "sku-miss", 10, 1000))));
        var spend = condition(p("spend", COUPON, ORDER, AMOUNT_OFF, 100),
                new Condition(101, 1, Set.of("sku-hit"), Set.of(), null, null));
        var quantity = condition(p("quantity", TARGETED, CART, AMOUNT_OFF, 100),
                new Condition(0, 2, Set.of("sku-hit"), Set.of(), null, null));
        assertThat(calculate(request, spend, quantity).promotions()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,false,200", "true,false,200", "false,true,200", "true,true,300"})
    void sameTypeStackingRequiresBothFlags(boolean firstAllows, boolean secondAllows, long expected) {
        var a = stack(p("a", COUPON, ORDER, AMOUNT_OFF, 100), firstAllows, ALL_TYPES, null);
        var b = stack(p("b", COUPON, ORDER, AMOUNT_OFF, 200), secondAllows, ALL_TYPES, null);
        assertThat(calculate(single(1000), a, b).discountTotal()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"false,false,200", "true,false,200", "false,true,200", "true,true,300"})
    void differentTypeStackingRequiresMutualCompatibility(boolean firstAllows, boolean secondAllows, long expected) {
        var a = stack(p("a", COUPON, ORDER, AMOUNT_OFF, 100), true,
                firstAllows ? Set.of(TARGETED) : Set.of(), null);
        var b = stack(p("b", TARGETED, ORDER, AMOUNT_OFF, 200), true,
                secondAllows ? Set.of(COUPON) : Set.of(), null);
        assertThat(calculate(single(1000), a, b).discountTotal()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = {"COUPON", "TARGETED"})
    void nonemptyExclusiveGroupOverridesTypeCompatibility(Type secondType) {
        var a = stack(p("a", COUPON, ORDER, AMOUNT_OFF, 100), true, ALL_TYPES, "exclusive");
        var b = stack(p("b", secondType, ORDER, AMOUNT_OFF, 200), true, ALL_TYPES, "exclusive");
        assertThat(calculate(single(1000), a, b).discountTotal()).isEqualTo(200);
        var emptyA = stack(a, true, ALL_TYPES, "");
        var emptyB = stack(b, true, ALL_TYPES, "");
        assertThat(calculate(single(1000), emptyA, emptyB).discountTotal()).isEqualTo(300);
    }

    @Test
    void orderScopesAreIndependentAndEachPromotionIsAppliedAtMostOncePerOrder() {
        var request = cart(new Order("o1", List.of(new Item("l1", "sku", 1, 1000))),
                new Order("o2", List.of(new Item("l2", "sku", 1, 1000))));
        var promotion = stack(p("a", COUPON, ORDER, AMOUNT_OFF, 300), false, Set.of(), "one");
        var quote = calculate(request, promotion);
        assertThat(quote.discountTotal()).isEqualTo(600);
        assertThat(quote.promotions()).extracting(Quote.AppliedPromotion::orderId).containsExactly("o1", "o2");
        assertThat(ids(quote)).containsExactly("a", "a");
    }

    @Test
    void differentOrdersMayUseDifferentActivitiesInTheSameExclusiveGroup() {
        var request = cart(new Order("o1", List.of(new Item("l1", "sku1", 1, 1000))),
                new Order("o2", List.of(new Item("l2", "sku2", 1, 1000))));
        var a = condition(stack(p("a", COUPON, ORDER, AMOUNT_OFF, 100), false, Set.of(), "x"),
                new Condition(0, 1, Set.of("sku1"), Set.of(), null, null));
        var b = condition(stack(p("b", TARGETED, ORDER, AMOUNT_OFF, 200), false, Set.of(), "x"),
                new Condition(0, 1, Set.of("sku2"), Set.of(), null, null));
        assertThat(ids(calculate(request, a, b))).containsExactly("a", "b");
    }

    @Test
    void cartChecksExclusiveGroupsAgainstEveryPreviouslyAppliedOrder() {
        var request = cart(new Order("o1", List.of(new Item("l1", "sku1", 1, 1000))),
                new Order("o2", List.of(new Item("l2", "sku2", 1, 1000))));
        var a = condition(p("a", COUPON, ORDER, AMOUNT_OFF, 100),
                new Condition(0, 1, Set.of("sku1"), Set.of(), null, null));
        var b = condition(stack(p("b", TARGETED, ORDER, AMOUNT_OFF, 400), true, ALL_TYPES, "exclusive"),
                new Condition(0, 1, Set.of("sku2"), Set.of(), null, null));
        var c = stack(p("c", FULL_REDUCTION, CART, AMOUNT_OFF, 700), true, ALL_TYPES, "exclusive");
        var quote = calculate(request, a, b, c);
        assertThat(quote.payableTotal()).isEqualTo(1200);
        assertThat(ids(quote)).containsExactly("a", "c");
    }

    @Test
    void crossLayerCompatibilityIsGlobalEvenForDisjointSkus() {
        var request = cart(new Order("o1", List.of(new Item("l1", "sku1", 1, 1000),
                new Item("l2", "sku2", 1, 1000))));
        var order = condition(stack(p("order", COUPON, ORDER, AMOUNT_OFF, 400), true, Set.of(), null),
                new Condition(0, 1, Set.of("sku1"), Set.of(), null, null));
        var cart = condition(p("cart", TARGETED, CART, AMOUNT_OFF, 600),
                new Condition(0, 1, Set.of("sku2"), Set.of(), null, null));
        assertThat(ids(calculate(request, order, cart))).containsExactly("cart");
    }

    @ParameterizedTest
    @CsvSource({"false,600", "true,1000"})
    void sameTypeFlagAlsoAppliesAcrossOrderAndCart(boolean allow, long discount) {
        var order = stack(p("order", COUPON, ORDER, AMOUNT_OFF, 400), allow, ALL_TYPES, null);
        var cart = p("cart", COUPON, CART, AMOUNT_OFF, 600);
        assertThat(calculate(single(2000), order, cart).discountTotal()).isEqualTo(discount);
    }

    @Test
    void cartesianProductCanChooseWeakerSameTypeAlternativeForBetterCombinedResult() {
        var strongestAlone = stack(p("a", COUPON, ORDER, AMOUNT_OFF, 600), false, Set.of(), null);
        var stackable = stack(p("b", COUPON, ORDER, AMOUNT_OFF, 400), false, Set.of(FULL_REDUCTION), null);
        var fullReduction = stack(p("c", FULL_REDUCTION, ORDER, AMOUNT_OFF, 500), false, Set.of(COUPON), null);
        var quote = calculate(single(2000), strongestAlone, stackable, fullReduction);
        assertThat(quote.discountTotal()).isEqualTo(900);
        assertThat(ids(quote)).containsExactly("b", "c");
    }

    @Test
    void skippingLocallyBestOrderDiscountCanUnlockBetterCartThreshold() {
        var order = p("order", COUPON, ORDER, AMOUNT_OFF, 1000);
        var cart = condition(p("cart", FULL_REDUCTION, CART, AMOUNT_OFF, 2000),
                new Condition(10000, 1, Set.of(), Set.of(), null, null));
        var quote = calculate(single(10000), order, cart);
        assertThat(quote.payableTotal()).isEqualTo(8000);
        assertThat(ids(quote)).containsExactly("cart");
    }

    @Test
    void searchesAllOrderCombinationsRatherThanKeepingOnlyEachLocalMinimum() {
        var request = cart(new Order("o1", List.of(new Item("l1", "sku", 1, 6000))),
                new Order("o2", List.of(new Item("l2", "sku", 1, 4000))));
        var order = p("order", COUPON, ORDER, AMOUNT_OFF, 1000);
        var cart = condition(p("cart", FULL_REDUCTION, CART, AMOUNT_OFF, 2500),
                new Condition(9000, 1, Set.of(), Set.of(), null, null));
        var quote = calculate(request, order, cart);
        assertThat(quote.payableTotal()).isEqualTo(6500);
        assertThat(quote.promotions()).extracting(Quote.AppliedPromotion::orderId).containsExactly("o1", "cart");
    }

    @Test
    void priorityIsRespectedEvenWhenAnotherExecutionPermutationWouldSaveMore() {
        var early = priority(p("a", COUPON, ORDER, AMOUNT_OFF, 2000), 0);
        var threshold = condition(p("b", TARGETED, ORDER, AMOUNT_OFF, 3000),
                new Condition(9000, 1, Set.of(), Set.of(), null, null));
        var fixedOrder = calculate(single(10000), early, threshold);
        assertThat(fixedOrder.payableTotal()).isEqualTo(7000);
        assertThat(ids(fixedOrder)).containsExactly("b");
        var reversedPriority = calculate(single(10000), priority(early, 2), priority(threshold, 1));
        assertThat(reversedPriority.payableTotal()).isEqualTo(5000);
        assertThat(ids(reversedPriority)).containsExactly("b", "a");
    }

    @Test
    void equalPriorityUsesIdForExecutionNotCandidateInputOrder() {
        var a = p("a", COUPON, ORDER, AMOUNT_OFF, 2000);
        var b = condition(p("b", TARGETED, ORDER, AMOUNT_OFF, 3000),
                new Condition(9000, 1, Set.of(), Set.of(), null, null));
        assertThat(calculate(single(10000), b, a).payableTotal()).isEqualTo(7000);
    }

    @Test
    void allocationsUseRemainingLineBalancesAndFundingUsesTheSameRemainderRule() {
        var request = cart(new Order("o1", List.of(new Item("b", "sku", 1, 2), new Item("a", "sku", 1, 3))));
        var funding = Map.of("platform", 5000, "merchant", 5000);
        var order = funding(p("order", COUPON, ORDER, AMOUNT_OFF, 1), funding);
        var cart = funding(p("cart", TARGETED, CART, PERCENT_OFF, 5000), funding);
        var quote = calculate(request, order, cart);
        assertThat(quote.originalTotal()).isEqualTo(5);
        assertThat(quote.discountTotal()).isEqualTo(3);
        assertThat(quote.linePayables()).containsExactlyInAnyOrderEntriesOf(Map.of("a", 1L, "b", 1L));
        assertThat(quote.promotions().get(0).allocations()).containsExactlyInAnyOrderEntriesOf(Map.of("a", 1L, "b", 0L));
        assertThat(quote.promotions().get(1).allocations()).containsExactlyInAnyOrderEntriesOf(Map.of("a", 1L, "b", 1L));
        assertThat(quote.funding()).containsExactlyInAnyOrderEntriesOf(Map.of("merchant", 2L, "platform", 1L));
        assertThat(quote.promotions().get(1).orderId()).isEqualTo("cart");
        assertThat(quote.promotions().get(1).scope()).isEqualTo(CART);
    }

    @Test
    void zeroDiscountAndNoBenefitDoNotCreateSnapshotsOrResourceConsumption() {
        var noop = p("noop", COUPON, ORDER, AMOUNT_OFF, 0);
        var roundedToZero = p("rounding", TARGETED, CART, PERCENT_OFF, 1);
        assertThat(calculate(single(1), noop, roundedToZero).promotions()).isEmpty();
        assertThat(calculate(single(0), p("free", COUPON, ORDER, AMOUNT_OFF, 500)).promotions()).isEmpty();
    }

    @Test
    void benefitOnlyPromotionsAreSelectedOnFreeOrdersAndKeepEverySnapshotField() {
        var benefit = new Benefit(20, "gift-sku", 2);
        var promotion = benefit(p("benefit", TARGETED, CART, AMOUNT_OFF, 0), benefit);
        var quote = calculate(single(0), promotion);
        assertThat(quote.promotions()).containsExactly(new Quote.AppliedPromotion("benefit", "cart", CART, 0,
                Map.of("l1", 0L), Map.of("merchant", 0L), "resource-benefit", 1, benefit));
        assertThat(quote.payableTotal()).isZero();
    }

    @Test
    void benefitOnlyPromotionsStillRequireThresholdsAndMatchedSkus() {
        var benefit = benefit(p("benefit", TARGETED, CART, AMOUNT_OFF, 0), new Benefit(20, null, 0));
        var spend = condition(benefit, new Condition(100, 1, Set.of(), Set.of(), null, null));
        assertThat(calculate(single(0), spend).promotions()).isEmpty();
        var sku = condition(benefit, new Condition(0, 1, Set.of("absent"), Set.of(), null, null));
        assertThat(calculate(single(1000), sku).promotions()).isEmpty();
    }

    @Test
    void benefitTiesPreferCoinsThenGiftUnits() {
        var coins = stack(benefit(p("coins", COUPON, CART, AMOUNT_OFF, 0), new Benefit(2, null, 0)),
                false, Set.of(), null);
        var gifts = stack(benefit(p("gifts", COUPON, CART, AMOUNT_OFF, 0), new Benefit(1, "gift", 100)),
                false, Set.of(), null);
        assertThat(ids(calculate(single(1000), gifts, coins))).containsExactly("coins");
        var moreGifts = benefit(gifts, new Benefit(2, "gift", 3));
        assertThat(ids(calculate(single(1000), coins, moreGifts))).containsExactly("gifts");
    }

    @Test
    void compatibleBenefitsAccumulateEvenWithoutAnyMonetarySaving() {
        var coins = benefit(p("coins", COUPON, ORDER, AMOUNT_OFF, 0), new Benefit(10, null, 0));
        var gift = benefit(p("gift", TARGETED, CART, AMOUNT_OFF, 0), new Benefit(0, "gift", 2));
        assertThat(ids(calculate(single(1000), coins, gift))).containsExactly("coins", "gift");
    }

    @Test
    void lowerPayableOutranksMoreBenefits() {
        var money = stack(p("money", COUPON, CART, AMOUNT_OFF, 1), false, Set.of(), null);
        var coins = stack(benefit(p("coins", COUPON, CART, AMOUNT_OFF, 0), new Benefit(1000, "gift", 100)),
                false, Set.of(), null);
        assertThat(ids(calculate(single(1000), coins, money))).containsExactly("money");
    }

    @Test
    void equalPayableAndBenefitsPreferFewerAppliedPromotionsThenStableIds() {
        var a = p("a", COUPON, CART, AMOUNT_OFF, 1000);
        var b = p("b", TARGETED, CART, AMOUNT_OFF, 500);
        var c = p("c", FULL_REDUCTION, CART, AMOUNT_OFF, 500);
        assertThat(ids(calculate(single(1000), c, b, a))).containsExactly("a");
        var z = p("z", COUPON, CART, AMOUNT_OFF, 1000);
        assertThat(ids(calculate(single(1000), z, a))).containsExactly("a");
    }

    @Test
    void unappliedOrderActivitiesDoNotExcludeCartActivities() {
        var noop = stack(p("noop", COUPON, ORDER, AMOUNT_OFF, 0), false, Set.of(), "x");
        var cart = stack(p("cart", TARGETED, CART, AMOUNT_OFF, 100), false, Set.of(), "x");
        assertThat(ids(calculate(single(1000), noop, cart))).containsExactly("cart");
    }

    @Test
    void rejectsDuplicateOrderIds() {
        var request = cart(new Order("same", List.of(new Item("a", "sku", 1, 1))),
                new Order("same", List.of(new Item("b", "sku", 1, 1))));
        assertBusinessError(() -> new PromotionEngine().calculate(request, List.of(), NOW), "DUPLICATE_ID", 400);
    }

    @Test
    void rejectsDuplicateLineIdsWithinOneOrder() {
        var request = cart(new Order("o1", List.of(new Item("same", "sku1", 1, 1), new Item("same", "sku2", 1, 1))));
        assertBusinessError(() -> new PromotionEngine().calculate(request, List.of(), NOW), "DUPLICATE_ID", 400);
    }

    @Test
    void rejectsDuplicateLineIdsAcrossDifferentOrders() {
        var request = cart(new Order("o1", List.of(new Item("same", "sku", 1, 1))),
                new Order("o2", List.of(new Item("same", "sku", 1, 1))));
        assertBusinessError(() -> new PromotionEngine().calculate(request, List.of(), NOW), "DUPLICATE_ID", 400);
    }

    @Test
    void rejectsDuplicateCandidateIdsBeforeFilteringEvenAcrossScopes() {
        var order = p("same", COUPON, ORDER, AMOUNT_OFF, 1);
        var expiredCart = condition(p("same", TARGETED, CART, AMOUNT_OFF, 1),
                new Condition(0, 1, Set.of(), Set.of(), null, NOW));
        assertBusinessError(() -> new PromotionEngine().calculate(single(100), List.of(order, expiredCart), NOW),
                "DUPLICATE_ID", 400);
    }

    @ParameterizedTest
    @EnumSource(Scope.class)
    void rejectsMoreThanTwelveFilteredCandidatesInEitherScope(Scope scope) {
        var candidates = IntStream.range(0, 13).mapToObj(i -> p("p" + i, COUPON, scope, AMOUNT_OFF, 1)).toList();
        assertBusinessError(() -> new PromotionEngine().calculate(single(100), candidates, NOW), "COMBINATION_LIMIT", 422);
    }

    @Test
    void candidateLimitRunsAfterTimeCustomerAndSkuFiltering() {
        var candidates = new ArrayList<Promotion>();
        for (int i = 0; i < 13; i++) {
            candidates.add(condition(p("expired-" + i, COUPON, ORDER, AMOUNT_OFF, 1),
                    new Condition(0, 1, Set.of(), Set.of(), null, NOW)));
            candidates.add(condition(p("customer-" + i, COUPON, ORDER, AMOUNT_OFF, 1),
                    new Condition(0, 1, Set.of(), Set.of("other"), null, null)));
            candidates.add(condition(p("sku-" + i, COUPON, CART, AMOUNT_OFF, 1),
                    new Condition(0, 1, Set.of("absent"), Set.of(), null, null)));
        }
        assertThat(new PromotionEngine(1).calculate(single(100), candidates, NOW).payableTotal()).isEqualTo(100);
        candidates.add(p("valid", COUPON, CART, AMOUNT_OFF, 1));
        assertThat(new PromotionEngine().calculate(single(100), candidates, NOW).discountTotal()).isEqualTo(1);
    }

    @Test
    void twelveCandidatesPerScopeAreAllowedAndIncompatibleBranchesArePruned() {
        var candidates = new ArrayList<Promotion>();
        for (var scope : Scope.values()) {
            for (int i = 0; i < 12; i++) {
                candidates.add(stack(p(scope + "-" + i, COUPON, scope, AMOUNT_OFF, i + 1), false, Set.of(), null));
            }
        }
        assertThat(new PromotionEngine().calculate(single(100), candidates, NOW).discountTotal()).isEqualTo(12);
    }

    @Test
    void budgetExhaustionThrowsEvenAfterACompleteCandidateWasEvaluated() {
        var promotions = List.of(p("a", COUPON, ORDER, AMOUNT_OFF, 100), p("b", COUPON, ORDER, AMOUNT_OFF, 200));
        assertBusinessError(() -> new PromotionEngine(14).calculate(single(1000), promotions, NOW), "COMBINATION_LIMIT", 422);
        var engine = new PromotionEngine(19);
        assertThat(engine.calculate(single(1000), promotions, NOW).discountTotal()).isEqualTo(300);
        assertThat(engine.calculate(single(1000), promotions, NOW).discountTotal()).isEqualTo(300);
    }

    @Test
    void defaultBudgetRejectsCrossOrderCombinatorialExplosion() {
        var orders = IntStream.range(0, 8)
                .mapToObj(i -> new Order("o" + i, List.of(new Item("l" + i, "sku", 1, 1000)))).toList();
        var promotions = IntStream.range(0, 6).mapToObj(i -> p("p" + i, COUPON, ORDER, AMOUNT_OFF, 1)).toList();
        assertBusinessError(() -> new PromotionEngine().calculate(new CartRequest("cart", "customer", orders), promotions, NOW),
                "COMBINATION_LIMIT", 422);
    }

    @Test
    void budgetIsPositiveAndTrulyEmptyCandidatesNeedNoSearch() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PromotionEngine(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PromotionEngine(-1));
        assertThat(new PromotionEngine(1).calculate(single(100), List.of(), NOW).payableTotal()).isEqualTo(100);
    }

    @Test
    void deterministicAcrossPermutationsOfOrdersLinesAndCandidates() {
        var first = new Order("o1", List.of(new Item("a", "sku", 1, 501), new Item("b", "sku", 1, 501)));
        var second = new Order("o2", List.of(new Item("c", "sku", 1, 999)));
        var promotions = new ArrayList<>(List.of(p("z", COUPON, ORDER, AMOUNT_OFF, 100),
                p("a", COUPON, ORDER, AMOUNT_OFF, 100), p("cart", TARGETED, CART, PERCENT_OFF, 1000)));
        var expected = calculate(cart(first, second), promotions.toArray(Promotion[]::new));
        var random = new Random(2026);
        for (int i = 0; i < 30; i++) {
            Collections.shuffle(promotions, random);
            var reversedFirst = new Order("o1", List.of(first.items().get(1), first.items().get(0)));
            assertThat(calculate(cart(second, reversedFirst), promotions.toArray(Promotion[]::new))).isEqualTo(expected);
        }
    }

    @Test
    void oneEngineCanServeConcurrentRequestsWithoutLeakingState() {
        var engine = new PromotionEngine();
        var promotions = List.of(p("order", COUPON, ORDER, AMOUNT_OFF, 100), p("cart", TARGETED, CART, PERCENT_OFF, 1000));
        var results = IntStream.range(0, 80).parallel().mapToObj(i ->
                engine.calculate(single(1000 + 10L * i), promotions, NOW.plusSeconds(i))).toList();
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).calculatedAt()).isEqualTo(NOW.plusSeconds(i));
            assertThat(results.get(i).payableTotal()).isEqualTo(810 + 9L * i);
            assertMoney(results.get(i));
        }
    }

    @Test
    void outputCollectionsAreImmutableAndInputListsRemainUnchanged() {
        var items = new ArrayList<>(List.of(new Item("l1", "sku", 1, 1000)));
        var orders = new ArrayList<>(List.of(new Order("o1", items)));
        var candidates = new ArrayList<>(List.of(p("p", COUPON, ORDER, AMOUNT_OFF, 100)));
        var quote = new PromotionEngine().calculate(new CartRequest("cart", "customer", orders), candidates, NOW);
        assertThat(items).containsExactly(new Item("l1", "sku", 1, 1000));
        assertThat(orders).hasSize(1);
        assertThat(candidates).hasSize(1);
        assertThatThrownBy(() -> quote.linePayables().put("l1", 0L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> quote.promotions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> quote.promotions().getFirst().allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> quote.promotions().getFirst().funding().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> quote.funding().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Promotion p(String id, Type type, Scope scope, DiscountKind kind, long value) {
        return new Promotion(id, "Promotion " + id, type, scope, 1,
                new Condition(0, 1, Set.of(), Set.of(), null, null), new Discount(kind, value),
                new Stacking(true, ALL_TYPES, null), Map.of("merchant", 10000), "resource-" + id, 1, Benefit.NONE);
    }

    private static Promotion condition(Promotion p, Condition condition) {
        return new Promotion(p.id(), p.name(), p.type(), p.scope(), p.priority(), condition, p.discount(),
                p.stacking(), p.fundingBps(), p.resourceId(), p.resourceUnits(), p.benefit());
    }

    private static Promotion stack(Promotion p, boolean allowSameType, Set<Type> compatibleTypes, String group) {
        return new Promotion(p.id(), p.name(), p.type(), p.scope(), p.priority(), p.condition(), p.discount(),
                new Stacking(allowSameType, compatibleTypes, group), p.fundingBps(), p.resourceId(), p.resourceUnits(), p.benefit());
    }

    private static Promotion priority(Promotion p, int priority) {
        return new Promotion(p.id(), p.name(), p.type(), p.scope(), priority, p.condition(), p.discount(),
                p.stacking(), p.fundingBps(), p.resourceId(), p.resourceUnits(), p.benefit());
    }

    private static Promotion benefit(Promotion p, Benefit benefit) {
        return new Promotion(p.id(), p.name(), p.type(), p.scope(), p.priority(), p.condition(), p.discount(),
                p.stacking(), p.fundingBps(), p.resourceId(), p.resourceUnits(), benefit);
    }

    private static Promotion funding(Promotion p, Map<String, Integer> funding) {
        return new Promotion(p.id(), p.name(), p.type(), p.scope(), p.priority(), p.condition(), p.discount(),
                p.stacking(), funding, p.resourceId(), p.resourceUnits(), p.benefit());
    }

    private static CartRequest single(long cents) {
        return cart(new Order("o1", List.of(new Item("l1", "sku", 1, cents))));
    }

    private static CartRequest cart(Order... orders) {
        return new CartRequest("cart", "customer", List.of(orders));
    }

    private static Quote calculate(CartRequest request, Promotion... promotions) {
        var quote = new PromotionEngine().calculate(request, List.of(promotions), NOW);
        assertMoney(quote);
        for (var order : request.orders()) {
            for (var item : order.items()) {
                long allocated = quote.promotions().stream().mapToLong(applied -> applied.allocations().getOrDefault(item.lineId(), 0L)).sum();
                assertThat(quote.linePayables().get(item.lineId())).isEqualTo(item.unitPrice() * item.quantity() - allocated);
            }
        }
        return quote;
    }

    private static List<String> ids(Quote quote) {
        return quote.promotions().stream().map(Quote.AppliedPromotion::promotionId).toList();
    }

    private static void assertMoney(Quote quote) {
        assertThat(quote.originalTotal()).isEqualTo(quote.payableTotal() + quote.discountTotal());
        assertThat(quote.discountTotal()).isEqualTo(quote.promotions().stream().mapToLong(Quote.AppliedPromotion::discount).sum());
        assertThat(quote.discountTotal()).isEqualTo(quote.funding().values().stream().mapToLong(Long::longValue).sum());
        assertThat(quote.payableTotal()).isEqualTo(quote.linePayables().values().stream().mapToLong(Long::longValue).sum());
        assertThat(quote.linePayables().values()).allMatch(value -> value >= 0);
        for (var applied : quote.promotions()) {
            assertThat(applied.discount()).isEqualTo(applied.allocations().values().stream().mapToLong(Long::longValue).sum());
            assertThat(applied.discount()).isEqualTo(applied.funding().values().stream().mapToLong(Long::longValue).sum());
        }
    }

    private static void assertBusinessError(Runnable action, String code, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.code()).isEqualTo(code);
            assertThat(exception.status()).isEqualTo(status);
        });
    }
}
