package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.mongle.backend.domain.credit.CreditProperties;
import static org.mockito.Mockito.*;
import java.util.List;

class PaymentCatalogTest {
    static PaymentCatalog catalog(PaymentProperties properties,boolean credits,boolean available) {
        var gateway=mock(PaymentGateway.class);
        when(gateway.available()).thenReturn(available);
        var c=new CreditProperties(credits,CreditProperties.ExpiryPolicy.NO_EXPIRY,3L,null,null,null,null);
        return new PaymentCatalog(properties,c,gateway);
    }
    @Test void onlyReadyCheckoutExposesProductsWithoutCallingProvider() {
        var active=new PaymentProperties.Product("pack","충전",1100L,10L,"KRW",true);
        for(boolean payments:new boolean[]{false,true})
            for(boolean credits:new boolean[]{false,true})
                for(boolean available:new boolean[]{false,true}) {
                    var p=new PaymentProperties(payments,List.of(active));
                    var catalog=catalog(p,credits,available);
                    assertThat(catalog.products().size()).isEqualTo(payments && credits && available?1:0);
                    if(!payments || !credits || !available)
                        assertThatThrownBy(() -> catalog.purchasable("pack"))
                                .isInstanceOf(com.mongle.backend.global.error.BusinessException.class);
                }
        var gateway=mock(PaymentGateway.class);
        when(gateway.available()).thenReturn(true);
        var c=new CreditProperties(true,CreditProperties.ExpiryPolicy.NO_EXPIRY,3L,null,null,null,null);
        new PaymentCatalog(new PaymentProperties(true,List.of(active)),c,gateway).products();
        verify(gateway).available();
        verifyNoMoreInteractions(gateway);
    }
    @Test void noImplicitPricesAndInactiveProductsAreHidden() {
        assertThat(catalog(new PaymentProperties(false,null),false,false).products()).isEmpty();
        var active=new PaymentProperties.Product("pack", "충전 상품", 1000L, 7L, "KRW", true);
        var hidden=new PaymentProperties.Product("later", null, null, null, null, false);
        var catalog=catalog(new PaymentProperties(true,List.of(active,hidden)),true,true);
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
