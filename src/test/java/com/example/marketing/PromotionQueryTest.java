package com.example.marketing;

import com.example.marketing.domain.BusinessException;
import com.example.marketing.domain.CartRequest;
import com.example.marketing.service.PromotionQueryService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PromotionQueryTest {
    private final CartRequest request = new CartRequest("cart", "customer", List.of());

    @Test
    void plansAreLazyAndCallDataSourcesInOrder() throws Exception {
        var called = new ArrayList<Integer>();
        var queries = new PromotionQueryService(List.of(
                cart -> { assertThat(cart).isSameAs(request); called.add(1); return List.of(); },
                cart -> { called.add(2); return List.of(); }));
        var plan = queries.plan(request);
        assertThat(called).isEmpty();
        assertThat(plan).hasSize(2);
        plan.getFirst().call();
        assertThat(called).containsExactly(1);
        called.clear();
        assertThat(queries.execute(request)).isEmpty();
        assertThat(called).containsExactly(1, 2);
    }

    @Test
    void sourceFailureDoesNotReturnPartialCandidates() {
        var calls = new AtomicInteger();
        var queries = new PromotionQueryService(List.of(
                cart -> { calls.incrementAndGet(); return List.of(); },
                cart -> { throw new IOException("upstream unavailable"); },
                cart -> { calls.incrementAndGet(); return List.of(); }));
        assertThatThrownBy(() -> queries.execute(request)).isInstanceOfSatisfying(BusinessException.class,
                error -> { assertThat(error.code()).isEqualTo("PROMOTION_QUERY_FAILED"); assertThat(error.status()).isEqualTo(502); });
        assertThat(calls).hasValue(1);
    }

    @Test
    void runtimeFailuresKeepTheirOriginalClassification() {
        var failure = new IllegalStateException("broken source");
        var queries = new PromotionQueryService(List.of(cart -> { throw failure; }));
        assertThatThrownBy(() -> queries.execute(request)).isSameAs(failure);
    }

    @Test
    void interruptionIsPreserved() {
        var queries = new PromotionQueryService(List.of(cart -> { throw new InterruptedException(); }));
        try {
            assertThatThrownBy(() -> queries.execute(request)).isInstanceOf(BusinessException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
