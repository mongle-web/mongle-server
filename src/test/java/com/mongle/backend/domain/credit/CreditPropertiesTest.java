package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;

class CreditPropertiesTest {
    @Test void unknownPolicyCannotBeEnabledAndAmountsAreNotInvented(){
        assertThatThrownBy(()->new CreditProperties(true,CreditProperties.ExpiryPolicy.UNDECIDED,null,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        var off=new CreditProperties(false,CreditProperties.ExpiryPolicy.UNDECIDED,null,null,null,null,null);
        assertThat(off.signupAmount()).isNull();
        assertThatThrownBy(()->off.cost(CreditUsage.DREAM_IMAGE_GENERATION)).isInstanceOf(BusinessException.class);
    }
    @Test void usageAmountsAreIndependentAndUnconfiguredFeatureIsUnavailable(){
        var p=new CreditProperties(true,CreditProperties.ExpiryPolicy.NO_EXPIRY,0L,10L,7L,30L,null);
        assertThat(p.cost(CreditUsage.DREAM_IMAGE_GENERATION)).isEqualTo(10);
        assertThat(p.cost(CreditUsage.DREAM_IMAGE_REGENERATION)).isEqualTo(7);
        assertThat(p.cost(CreditUsage.WORLD_IMAGE_GENERATION)).isEqualTo(30);
        assertThatThrownBy(()->p.cost(CreditUsage.WORLD_IMAGE_REGENERATION)).isInstanceOf(BusinessException.class);
    }
    @Test void invalidAmountsAreRejectedEvenWhenDisabled(){
        assertThatThrownBy(()->new CreditProperties(false,CreditProperties.ExpiryPolicy.UNDECIDED,-1L,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CreditProperties(false,CreditProperties.ExpiryPolicy.UNDECIDED,null,0L,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
