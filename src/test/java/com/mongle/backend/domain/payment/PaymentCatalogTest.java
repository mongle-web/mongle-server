package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;

class PaymentCatalogTest {
    @Test void noImplicitPricesAndInactiveProductsAreHidden() {
        assertThat(new PaymentCatalog(new PaymentProperties(false,null)).products()).isEmpty();
        var active=new PaymentProperties.Product("pack", "충전 상품", 1000L, 7L, "KRW", true);
        var hidden=new PaymentProperties.Product("later", null, null, null, null, false);
        var catalog=new PaymentCatalog(new PaymentProperties(false,List.of(active,hidden)));
        assertThat(catalog.products()).containsExactly(new CreditProduct("pack","충전 상품",1000,"KRW",7));
        assertThatThrownBy(() -> catalog.purchasable("later")).isInstanceOf(com.mongle.backend.global.error.BusinessException.class);
    }
    @Test void rejectsDuplicateOrInvalidActiveProducts() {
        var p=new PaymentProperties.Product("pack","충전",1000L,1L,"KRW",true);
        assertThatThrownBy(() -> new PaymentProperties(false,List.of(p,p))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProperties.Product("pack","충전",999L,1L,"KRW",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProperties.Product("pack","충전",1000L,0L,"KRW",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProperties.Product("pack","충전",1000L,1L,"USD",true)).isInstanceOf(IllegalArgumentException.class);
    }
}
