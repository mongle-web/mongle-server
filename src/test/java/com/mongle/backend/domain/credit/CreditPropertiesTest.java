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
    @Test void signupAmountDefaultsToThreeWhenBoundAndHonorsExplicitConfiguration() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CreditConfiguration.class)
                .run(context -> assertThat(context.getBean(CreditProperties.class).signupAmount()).isEqualTo(3));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CreditConfiguration.class)
                .withPropertyValues("mongle.credit.enabled=true", "mongle.credit.signup-amount=")
                .run(context -> assertThat(context.getBean(CreditProperties.class).signupGrantAmount()).isEqualTo(3));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(CreditConfiguration.class)
                .withPropertyValues("mongle.credit.enabled=true", "mongle.credit.signup-amount=5")
                .run(context -> assertThat(context.getBean(CreditProperties.class).signupGrantAmount()).isEqualTo(5));
    }
    @Test void signupGrantRejectsDisabledNullAndZeroPolicyRatherThanCompletingWithoutBenefit() {
        for (Long amount : new Long[]{null, 0L}) {
            var p = new CreditProperties(true, CreditProperties.ExpiryPolicy.NO_EXPIRY, amount, null, null, null, null);
            assertThatThrownBy(p::signupGrantAmount).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(CreditErrorCode.UNAVAILABLE));
        }
        var off = new CreditProperties(false, CreditProperties.ExpiryPolicy.NO_EXPIRY, 3L, null, null, null, null);
        assertThatThrownBy(off::signupGrantAmount).isInstanceOf(BusinessException.class);
    }

}
