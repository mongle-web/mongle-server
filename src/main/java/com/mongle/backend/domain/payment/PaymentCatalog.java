package com.mongle.backend.domain.payment;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentCatalog {
    private final PaymentProperties properties;
    public List<CreditProduct> products() {
        return properties.products().stream().filter(PaymentProperties.Product::enabled).map(CreditProduct::from).toList();
    }
    CreditProduct purchasable(String id) {
        return products().stream().filter(p -> p.productId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PRODUCT_UNAVAILABLE));
    }
}
