package com.mongle.backend.domain.auth.service;

import com.mongle.backend.domain.auth.entity.SocialAccount;
import com.mongle.backend.domain.auth.oauth.SocialIdentity;
import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.logging.CommittedLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
@Slf4j
public class SocialLoginService {
    private final SocialAccountRepository accounts;
    private final UserRepository users;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public SocialLoginService(SocialAccountRepository accounts, UserRepository users,
                              PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.users = users;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 소셜 제공사에 대한 HTTP 호출을 마친 뒤 DB 트랜잭션을 시작한다. */
    public UserResponse login(SocialIdentity identity) {
        Optional<UserResponse> existing = find(identity);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return write.execute(status -> {
                // 가입 트랜잭션을 시작한 뒤 같은 소셜 계정이 이미 저장됐는지 다시 확인한다.
                Optional<SocialAccount> account = accounts.findByProviderAndProviderUserId(
                        identity.provider(), identity.providerUserId());
                if (account.isPresent()) {
                    return UserResponse.from(account.get().getUser());
                }
                User user = users.save(User.register(identity.email()));
                accounts.saveAndFlush(SocialAccount.link(user, identity.provider(), identity.providerUserId()));
                Long userId = user.getId();
                // 이메일·소셜 제공자 사용자 ID 대신 내부 PK와 제공사만 남긴다.
                CommittedLog.afterCommit(() -> log.info("소셜 회원 가입 완료: userId={}, provider={}", userId, identity.provider()));
                return UserResponse.from(user);
            });
        } catch (DataIntegrityViolationException duplicate) {
            // 중복 가입으로 실패한 트랜잭션은 사용자 저장까지 모두 롤백된 상태다.
            // MySQL의 REPEATABLE READ에서도 먼저 저장된 계정을 읽을 수 있도록 새 트랜잭션으로 조회한다.
            return find(identity).orElseThrow(() -> duplicate);
        }
    }

    private Optional<UserResponse> find(SocialIdentity identity) {
        return read.execute(status -> accounts.findByProviderAndProviderUserId(
                identity.provider(), identity.providerUserId())
                .map(account -> UserResponse.from(account.getUser())));
    }
}
