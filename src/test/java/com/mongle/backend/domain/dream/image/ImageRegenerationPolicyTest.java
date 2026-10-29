package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ImageRegenerationPolicyTest {
    @ParameterizedTest
    @EnumSource(value = CreditUsage.class, names = {"DREAM_IMAGE_REGENERATION", "WORLD_IMAGE_REGENERATION"})
    void missingPriceBlocksFreeAttemptAndReturnsNoQuoteRegardlessOfAllowance(CreditUsage usage) {
        var properties = new CreditProperties(true, CreditProperties.ExpiryPolicy.NO_EXPIRY, null,
                3L, usage == CreditUsage.DREAM_IMAGE_REGENERATION ? null : 1L,
                3L, usage == CreditUsage.WORLD_IMAGE_REGENERATION ? null : 1L);
        var credits = mock(CreditService.class);
        var policy = new ImageRegenerationPolicy(properties, credits);

        assertThatThrownBy(() -> policy.requireFreeAttemptAllowed(1L, usage))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CreditErrorCode.UNAVAILABLE);
        assertThat(policy.nextCost(true, false, usage)).isNull();
        assertThat(policy.nextCost(true, true, usage)).isNull();
        verifyNoInteractions(credits);
    }

    @ParameterizedTest
    @EnumSource(value = CreditUsage.class, names = {"DREAM_IMAGE_REGENERATION", "WORLD_IMAGE_REGENERATION"})
    void configuredPriceAllowsFreeAttemptAtZeroBalanceAndQuotesPaidPriceAfterUse(CreditUsage usage) {
        var properties = new CreditProperties(true, CreditProperties.ExpiryPolicy.NO_EXPIRY, null, 3L, 1L, 3L, 2L);
        var credits = mock(CreditService.class);
        when(credits.balance(1L)).thenReturn(new CreditBalance(0, 0, 0));
        var policy = new ImageRegenerationPolicy(properties, credits);

        policy.requireFreeAttemptAllowed(1L, usage);
        assertThat(policy.nextCost(true, false, usage)).isZero();
        assertThat(policy.nextCost(true, true, usage)).isEqualTo(properties.configuredCost(usage));
        assertThat(policy.nextCost(false, false, usage)).isNull();
    }
}
