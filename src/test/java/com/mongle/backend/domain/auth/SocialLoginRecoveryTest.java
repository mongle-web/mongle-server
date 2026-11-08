package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.entity.SocialAccount;
import com.mongle.backend.domain.auth.entity.SocialProvider;
import com.mongle.backend.domain.auth.oauth.SocialIdentity;
import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.mongle.backend.domain.auth.service.SocialLoginService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SocialLoginRecoveryTest {
    @Test
    void resolvesWinnerAfterRollingBackDuplicateAndDoesNotHideOtherIntegrityFailures() {
        var accounts = mock(SocialAccountRepository.class);
        var users = mock(UserRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(call -> new SimpleTransactionStatus());
        var identity = new SocialIdentity(SocialProvider.KAKAO, "123456", "user@example.com");
        var winner = SocialAccount.link(User.create(identity.email(), "몽글이"),
                identity.provider(), identity.providerUserId());
        when(accounts.findByProviderAndProviderUserId(identity.provider(), identity.providerUserId()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
        var conflict = new DataIntegrityViolationException("소셜 계정이 중복되었습니다.");
        when(accounts.saveAndFlush(any())).thenThrow(conflict);
        var legal = mock(com.mongle.backend.domain.legal.LegalService.class);
        when(legal.response(any(User.class))).thenAnswer(call ->
                com.mongle.backend.domain.user.dto.UserResponse.from(call.getArgument(0),false));
        var service = new SocialLoginService(accounts, users, manager,
                org.mockito.Mockito.mock(com.mongle.backend.domain.user.withdrawal.WithdrawalIdentityService.class),legal);

        assertThat(service.login(identity).nickname()).isEqualTo("몽글이");
        var order = inOrder(manager, accounts);
        order.verify(accounts).findByProviderAndProviderUserId(identity.provider(), identity.providerUserId());
        order.verify(manager).commit(any());
        order.verify(accounts).findByProviderAndProviderUserId(identity.provider(), identity.providerUserId());
        order.verify(accounts).saveAndFlush(any());
        order.verify(manager).rollback(any());
        order.verify(accounts).findByProviderAndProviderUserId(identity.provider(), identity.providerUserId());

        when(accounts.findByProviderAndProviderUserId(identity.provider(), identity.providerUserId()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(identity)).isSameAs(conflict);
    }
}
