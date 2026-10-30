package com.example.marketing;

import com.example.marketing.engine.PromotionEngine;
import com.example.marketing.service.CatalogService;
import com.example.marketing.service.PromotionQueryService;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MarketingApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketingApplication.class, args);
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PromotionEngine promotionEngine() { return new PromotionEngine(); }

    @Bean
    PromotionQueryService.Callback catalogCallback(CatalogService catalog) {
        return request -> catalog.promotions();
    }
}
