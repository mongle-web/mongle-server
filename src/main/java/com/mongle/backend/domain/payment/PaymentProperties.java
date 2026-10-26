package com.mongle.backend.domain.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.util.*;

@ConfigurationProperties("mongle.payment")
public record PaymentProperties(@DefaultValue("false") boolean enabled, List<Product> products) {
    public PaymentProperties {
        products = products == null ? List.of() : List.copyOf(products);
        if (products.size() > 50 || products.stream().map(Product::id).distinct().count() != products.size())
            throw new IllegalArgumentException("결제 상품은 중복 없는 50개 이하의 ID여야 합니다.");
    }
    public record Product(String id, String name, Long price, Long credits,
            @DefaultValue("KRW") String currency, @DefaultValue("false") boolean enabled) {
        public Product {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,49}"))
                throw new IllegalArgumentException("결제 상품 ID 형식이 올바르지 않습니다.");
            if (enabled && (name == null || name.isBlank() || name.codePointCount(0,name.length()) > 80
                    || name.contains("://") || name.codePoints().anyMatch(Character::isISOControl)
                    || price == null || price < 1000 || price > Integer.MAX_VALUE
                    || credits == null || credits < 1 || !"KRW".equals(currency)))
                throw new IllegalArgumentException("판매 상품의 이름·원화 결제금액·지급량을 확인해주세요.");
        }
    }
}
