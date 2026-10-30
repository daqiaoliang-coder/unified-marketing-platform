package com.example.marketing.service;

import com.example.marketing.domain.*;
import com.example.marketing.domain.TransactionModels.*;
import com.example.marketing.persistence.MarketingStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final MarketingStore store;

    public CatalogService(MarketingStore store) { this.store = store; }

    public List<Promotion> promotions() { return store.promotions(); }
    public List<Resource> resources() { return store.resources(); }

    @Transactional
    public Resource createResource(ResourceInput input) {
        store.lock();
        if (store.resource(input.id()).isPresent()) throw new BusinessException("RESOURCE_EXISTS", "Resource already exists", 409);
        store.insertResource(input);
        return store.resource(input.id()).orElseThrow();
    }

    @Transactional
    public Promotion savePromotion(Promotion value) {
        store.lock();
        if (value.fundingBps().values().stream().mapToInt(Integer::intValue).sum() != 10000)
            throw new BusinessException("INVALID_FUNDING", "Funding shares must sum to 10000 basis points", 400);
        if (value.discount().kind() == Promotion.DiscountKind.PERCENT_OFF && value.discount().value() > 10000)
            throw new BusinessException("INVALID_DISCOUNT", "Percentage discount cannot exceed 10000 basis points", 400);
        var condition = value.condition();
        if (condition.startsAt() != null && condition.endsAt() != null && !condition.endsAt().isAfter(condition.startsAt()))
            throw new BusinessException("INVALID_WINDOW", "endsAt must be after startsAt", 400);
        if (value.resourceId() == null ? value.resourceUnits() != 0 : value.resourceId().isBlank() || value.resourceUnits() <= 0)
            throw new BusinessException("INVALID_RESOURCE", "A resource id requires positive units; no resource requires zero units", 400);
        if (value.resourceId() != null && store.resource(value.resourceId()).isEmpty())
            throw new BusinessException("RESOURCE_NOT_FOUND", "Promotion resource does not exist", 400);
        var benefit = value.benefit();
        if (benefit.giftSku() == null ? benefit.giftQuantity() != 0 : benefit.giftSku().isBlank() || benefit.giftQuantity() <= 0)
            throw new BusinessException("INVALID_BENEFIT", "A gift SKU requires a positive quantity", 400);
        store.savePromotion(value);
        return value;
    }
}
