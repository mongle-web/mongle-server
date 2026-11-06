package com.mongle.backend.domain.payment;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.domain.credit.CreditProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentCatalog {
    private final PaymentProperties properties;
    private final CreditProperties creditProperties;
    private final PaymentGateway gateway;
    public List<CreditProduct> products() {
        if(!properties.enabled() || !creditProperties.enabled() || !gateway.available()) return List.of();
        return properties.products().stream().filter(PaymentProperties.Product::enabled).map(CreditProduct::from).toList();
    }
    CreditProduct purchasable(String id) {
        return products().stream().filter(p -> p.productId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PRODUCT_UNAVAILABLE));
    }
}
