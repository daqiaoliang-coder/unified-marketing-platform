package com.example.marketing.engine;

import com.example.marketing.domain.BusinessException;
import com.example.marketing.domain.CartRequest;
import com.example.marketing.domain.CartRequest.Item;
import com.example.marketing.domain.Promotion;
import com.example.marketing.domain.Promotion.Scope;
import com.example.marketing.domain.Promotion.Type;
import com.example.marketing.domain.Quote;
import com.example.marketing.domain.Quote.AppliedPromotion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Stateless, bounded exhaustive search over combinations, not execution permutations. */
public final class PromotionEngine {
    private static final int DEFAULT_MAX_EVALUATIONS = 50_000;
    private static final int MAX_CANDIDATES_PER_SCOPE = 12;
    private static final Comparator<Promotion> EXECUTION_ORDER =
            Comparator.comparingInt(Promotion::priority).thenComparing(Promotion::id);
    private static final Comparator<AppliedPromotion> STABLE_ID_ORDER =
            Comparator.comparing(AppliedPromotion::promotionId)
                    .thenComparing(AppliedPromotion::orderId)
                    .thenComparing(applied -> applied.scope().name());

    private final int maxEvaluations;

    public PromotionEngine() {
        this(DEFAULT_MAX_EVALUATIONS);
    }

    public PromotionEngine(int maxEvaluations) {
        if (maxEvaluations < 1) {
            throw new IllegalArgumentException("maxEvaluations must be positive");
        }
        this.maxEvaluations = maxEvaluations;
    }

    public Quote calculate(CartRequest request, List<Promotion> promotions, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(promotions, "promotions");
        Objects.requireNonNull(now, "now");
        try {
            return calculateChecked(request, promotions, now);
        } catch (ArithmeticException exception) {
            throw new BusinessException("AMOUNT_OVERFLOW", "Amount exceeds the supported long range", 400);
        }
    }

    private Quote calculateChecked(CartRequest request, List<Promotion> promotions, Instant now) {
        Set<String> orderIds = new HashSet<>();
        Set<String> lineIds = new HashSet<>();
        Set<String> promotionIds = new HashSet<>();
        var orders = new ArrayList<PreparedOrder>();
        var initialBalances = new TreeMap<String, Long>();
        var allItems = new ArrayList<Item>();
        for (var order : request.orders()) {
            requireUnique(orderIds, order.orderId(), "orderId");
            var items = order.items().stream().sorted(Comparator.comparing(Item::lineId)).toList();
            var balances = new TreeMap<String, Long>();
            for (var item : items) {
                requireUnique(lineIds, item.lineId(), "lineId");
                balances.put(item.lineId(), Math.multiplyExact(item.unitPrice(), (long) item.quantity()));
            }
            orders.add(new PreparedOrder(order.orderId(), items, balances));
            initialBalances.putAll(balances);
            allItems.addAll(items);
        }
        for (var promotion : promotions) {
            requireUnique(promotionIds, promotion.id(), "promotionId");
        }
        orders.sort(Comparator.comparing(PreparedOrder::id));
        allItems.sort(Comparator.comparing(Item::lineId));
        long originalTotal = sum(initialBalances);
        var initial = new State(initialBalances, List.of());

        // Limits apply per scope across the request after time/customer/SKU filtering.
        // Spend/quantity thresholds are deliberately NOT used for static filtering.
        var candidates = new EnumMap<Scope, List<Promotion>>(Scope.class);
        for (var scope : Scope.values()) {
            var filtered = promotions.stream()
                    .filter(promotion -> promotion.scope() == scope)
                    .filter(promotion -> active(promotion, request.customerId(), now))
                    .filter(promotion -> allItems.stream().anyMatch(item -> matches(promotion, item)))
                    .sorted(EXECUTION_ORDER).toList();
            if (filtered.size() > MAX_CANDIDATES_PER_SCOPE) {
                throw combinationLimit("More than 12 filtered candidates in scope " + scope);
            }
            candidates.put(scope, filtered);
        }
        if (candidates.values().stream().allMatch(List::isEmpty)) {
            return quote(request, now, originalTotal, initial);
        }

        var budget = new Budget(maxEvaluations);
        var cartCombinations = combinations(candidates.get(Scope.CART), budget);
        var orderOptions = new ArrayList<List<State>>();
        for (var order : orders) {
            var applicable = candidates.get(Scope.ORDER).stream()
                    .filter(promotion -> order.items().stream().anyMatch(item -> matches(promotion, item)))
                    .toList();
            var alternatives = new ArrayList<State>();
            var orderInitial = new State(order.balances(), List.of());
            for (var combination : combinations(applicable, budget)) {
                budget.step();
                alternatives.add(apply(orderInitial, order.id(), order.items(), combination));
            }
            // Do not discard locally worse alternatives: CART thresholds/compatibility can reverse them.
            orderOptions.add(alternatives);
        }
        var search = new Search(budget, orderOptions, cartCombinations, allItems, request.cartId());
        search.visitOrders(0, initial);
        return quote(request, now, originalTotal, search.best.state());
    }

    private static void requireUnique(Set<String> seen, String id, String field) {
        if (!seen.add(id)) {
            throw new BusinessException("DUPLICATE_ID", "Duplicate " + field + ": " + id, 400);
        }
    }

    private static boolean active(Promotion promotion, String customerId, Instant now) {
        var condition = promotion.condition();
        return (condition.startsAt() == null || !now.isBefore(condition.startsAt()))
                && (condition.endsAt() == null || now.isBefore(condition.endsAt()))
                && (condition.customerIds().isEmpty() || condition.customerIds().contains(customerId));
    }

    private static boolean matches(Promotion promotion, Item item) {
        return promotion.condition().skuIds().isEmpty()
                || promotion.condition().skuIds().contains(item.skuId());
    }

    private static boolean compatible(Promotion left, Promotion right) {
        String group = left.stacking().exclusiveGroup();
        if (group != null && !group.isEmpty() && group.equals(right.stacking().exclusiveGroup())) {
            return false;
        }
        if (left.type() == right.type()) {
            return left.stacking().allowSameType() && right.stacking().allowSameType();
        }
        return left.stacking().compatibleTypes().contains(right.type())
                && right.stacking().compatibleTypes().contains(left.type());
    }

    private static boolean compatible(List<Promotion> left, List<Promotion> right) {
        for (var a : left) {
            for (var b : right) {
                if (!compatible(a, b)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Build compatible same-type subsets first, then their pruned cross-type Cartesian product. */
    private static List<List<Promotion>> combinations(List<Promotion> candidates, Budget budget) {
        if (candidates.isEmpty()) {
            return List.of(List.of());
        }
        var byType = new EnumMap<Type, List<Promotion>>(Type.class);
        for (var promotion : candidates) {
            byType.computeIfAbsent(promotion.type(), ignored -> new ArrayList<>()).add(promotion);
        }
        var groups = new ArrayList<List<List<Promotion>>>();
        for (var sameType : byType.values()) {
            var subsets = new ArrayList<List<Promotion>>();
            sameTypeSubsets(sameType, 0, new ArrayList<>(), subsets, budget);
            groups.add(subsets);
        }
        var result = new ArrayList<List<Promotion>>();
        crossTypes(groups, 0, new ArrayList<>(), result, budget);
        return result;
    }

    private static void sameTypeSubsets(List<Promotion> group, int from, List<Promotion> selected,
                                        List<List<Promotion>> result, Budget budget) {
        result.add(List.copyOf(selected)); // Includes the empty subset, even for a nonempty type.
        for (int i = from; i < group.size(); i++) {
            budget.step();
            var next = group.get(i);
            if (!compatible(selected, List.of(next))) {
                continue;
            }
            selected.add(next);
            sameTypeSubsets(group, i + 1, selected, result, budget);
            selected.removeLast();
        }
    }

    private static void crossTypes(List<List<List<Promotion>>> groups, int index,
                                   List<Promotion> selected, List<List<Promotion>> result, Budget budget) {
        if (index == groups.size()) {
            result.add(selected.stream().sorted(EXECUTION_ORDER).toList());
            return;
        }
        for (var subset : groups.get(index)) {
            budget.step();
            if (!compatible(selected, subset)) {
                continue;
            }
            int previousSize = selected.size();
            selected.addAll(subset);
            crossTypes(groups, index + 1, selected, result, budget);
            selected.subList(previousSize, selected.size()).clear();
        }
    }

    private static State apply(State initial, String orderId, List<Item> items, List<Promotion> combination) {
        var balances = new TreeMap<>(initial.balances());
        var uses = new ArrayList<>(initial.uses());
        for (var promotion : combination) {
            var weights = new TreeMap<String, Long>();
            long quantity = 0;
            for (var item : items) {
                if (matches(promotion, item)) {
                    weights.put(item.lineId(), balances.get(item.lineId()));
                    quantity = Math.addExact(quantity, item.quantity());
                }
            }
            long subtotal = sum(weights);
            if (weights.isEmpty() || subtotal < promotion.condition().minSpend()
                    || quantity < promotion.condition().minQuantity()) {
                continue;
            }
            var discount = promotion.discount();
            var plugin = DiscountPlugins.STANDARD.get(discount.kind());
            long saving;
            Map<String, Long> allocations;
            if (discount.kind() == Promotion.DiscountKind.FIXED_UNIT_PRICE) {
                var lineDiscounts = new TreeMap<String, Long>();
                for (var item : items) {
                    if (weights.containsKey(item.lineId())) {
                        lineDiscounts.put(item.lineId(), plugin.discount(weights.get(item.lineId()), item.quantity(), discount.value()));
                    }
                }
                saving = sum(lineDiscounts);
                allocations = Collections.unmodifiableMap(lineDiscounts);
            } else {
                saving = Math.max(0, Math.min(subtotal, plugin.discount(subtotal, quantity, discount.value())));
                allocations = Amounts.allocate(saving, weights);
            }
            if (saving == 0 && !hasBenefit(promotion)) {
                continue;
            }
            allocations.forEach((lineId, amount) -> balances.compute(lineId, (key, current) -> current - amount));
            var fundingWeights = new TreeMap<String, Long>();
            promotion.fundingBps().forEach((sponsor, bps) -> fundingWeights.put(sponsor, bps.longValue()));
            var funding = Amounts.allocate(saving, fundingWeights);
            var snapshot = new AppliedPromotion(promotion.id(), orderId, promotion.scope(), saving,
                    allocations, funding, promotion.resourceId(), promotion.resourceUnits(), promotion.benefit());
            uses.add(new Use(promotion, snapshot));
        }
        return new State(Collections.unmodifiableMap(balances), List.copyOf(uses));
    }

    private static boolean hasBenefit(Promotion promotion) {
        var benefit = promotion.benefit();
        return benefit.coins() > 0
                || (benefit.giftSku() != null && !benefit.giftSku().isBlank() && benefit.giftQuantity() > 0);
    }

    private static long sum(Map<String, Long> amounts) {
        long total = 0;
        for (long value : amounts.values()) {
            total = Math.addExact(total, value);
        }
        return total;
    }

    private static Quote quote(CartRequest request, Instant now, long originalTotal, State state) {
        long payable = sum(state.balances());
        var funding = new TreeMap<String, Long>();
        var snapshots = state.uses().stream().map(Use::snapshot).toList();
        for (var applied : snapshots) {
            applied.funding().forEach((sponsor, amount) -> funding.merge(sponsor, amount, Math::addExact));
        }
        return new Quote(request.cartId(), request.customerId(), now, originalTotal,
                originalTotal - payable, payable, Collections.unmodifiableMap(new TreeMap<>(state.balances())),
                snapshots, Collections.unmodifiableMap(funding));
    }

    private static BusinessException combinationLimit(String message) {
        return new BusinessException("COMBINATION_LIMIT", message, 422);
    }

    // A shared budget rejects the whole request rather than returning a partial optimum.
    private static final class Budget {
        private int remaining;

        private Budget(int maximum) {
            remaining = maximum;
        }

        private void step() {
            if (remaining == 0) {
                throw combinationLimit("Request combination search budget exceeded");
            }
            remaining--;
        }
    }

    private record PreparedOrder(String id, List<Item> items, Map<String, Long> balances) {}
    private record Use(Promotion promotion, AppliedPromotion snapshot) {}
    private record State(Map<String, Long> balances, List<Use> uses) {}

    private static final class Search {
        private final Budget budget;
        private final List<List<State>> orderOptions;
        private final List<List<Promotion>> cartCombinations;
        private final List<Item> items;
        private final String cartId;
        private Candidate best;

        private Search(Budget budget, List<List<State>> orderOptions,
                       List<List<Promotion>> cartCombinations, List<Item> items, String cartId) {
            this.budget = budget;
            this.orderOptions = orderOptions;
            this.cartCombinations = cartCombinations;
            this.items = items;
            this.cartId = cartId;
        }

        private void visitOrders(int index, State prefix) {
            if (index < orderOptions.size()) {
                for (var option : orderOptions.get(index)) {
                    budget.step();
                    var balances = new TreeMap<>(prefix.balances());
                    balances.putAll(option.balances());
                    var uses = new ArrayList<>(prefix.uses());
                    uses.addAll(option.uses()); // Different ORDER subjects do not exclude one another.
                    visitOrders(index + 1, new State(balances, uses));
                }
                return;
            }
            var prior = prefix.uses().stream().map(Use::promotion).toList();
            for (var cartCombination : cartCombinations) {
                budget.step();
                // Check every actually applied ORDER promotion before any CART plugin runs.
                if (!compatible(cartCombination, prior)) {
                    continue;
                }
                var candidate = Candidate.of(apply(prefix, cartId, items, cartCombination));
                if (best == null || candidate.compareTo(best) < 0) {
                    best = candidate;
                }
            }
        }
    }

    /** Tie-break: payable asc, coins desc, gift units desc, applied count asc, ID tuples asc. */
    private record Candidate(State state, long payable, long coins, long gifts,
                             List<AppliedPromotion> stableIds) implements Comparable<Candidate> {
        private static Candidate of(State state) {
            long coins = 0;
            long gifts = 0;
            for (var use : state.uses()) {
                coins = Math.addExact(coins, use.snapshot().benefit().coins());
                gifts = Math.addExact(gifts, use.snapshot().benefit().giftQuantity());
            }
            return new Candidate(state, sum(state.balances()), coins, gifts,
                    state.uses().stream().map(Use::snapshot).sorted(STABLE_ID_ORDER).toList());
        }

        @Override
        public int compareTo(Candidate other) {
            int comparison = Long.compare(payable, other.payable);
            if (comparison == 0) comparison = Long.compare(other.coins, coins);
            if (comparison == 0) comparison = Long.compare(other.gifts, gifts);
            if (comparison == 0) comparison = Integer.compare(stableIds.size(), other.stableIds.size());
            for (int i = 0; comparison == 0 && i < stableIds.size(); i++) {
                comparison = STABLE_ID_ORDER.compare(stableIds.get(i), other.stableIds.get(i));
            }
            return comparison;
        }
    }
}
