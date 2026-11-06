package com.mongle.backend.domain.user.withdrawal;

import com.mongle.backend.domain.auth.oauth.SocialIdentity;
import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.credit.*;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service @RequiredArgsConstructor
public class WithdrawalIdentityService {
    private final WithdrawalProperties properties;
    private final WithdrawalSecrets secrets;
    private final SocialUnlinkTaskRepository tasks;
    private final SignupRewardHistoryRepository history;
    private final SocialAccountRepository accounts;
    private final CreditGrantRepository grants;
    private final Clock authClock;
    private LocalDateTime now() { return LocalDateTime.ofInstant(authClock.instant(),ZoneOffset.UTC); }

    @Transactional(propagation=Propagation.MANDATORY)
    public void beforeNewLogin(SocialIdentity identity) {
        if (properties.identityKey().isBlank()) return;
        tasks.lock(secrets.identity(identity.provider().name(),identity.providerUserId())).ifPresent(task -> {
            if(task.rejoinBlocked()) throw new BusinessException(AuthErrorCode.LOGIN_UNAVAILABLE);
            task.cancelUnstarted();
        });
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean signupRewardDue(Long user) {
        if (properties.identityKey().isBlank()) return true;
        var account=accounts.findByUserId(user);
        if(account.isEmpty()) return true; // 비소셜 테스트/관리용 계정은 기존 사용자 ID 멱등키 사용
        var a=account.get();
        String hash=secrets.identity(a.getProvider().name(),a.getProviderUserId());
        if(history.existsById(hash)) return false;
        history.saveAndFlush(SignupRewardHistory.create(hash,now()));
        return true; // 크레딧 지급 실패 시 이 기록도 같은 트랜잭션으로 롤백
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void withdraw(Long user) {
        accounts.findByUserId(user).ifPresent(a -> {
            String hash=secrets.identity(a.getProvider().name(),a.getProviderUserId());
            // 기능 적용 이전 회원도 이미 받은 가입 보상을 보존한다.
            if (!history.existsById(hash))
                grants.findFirstByUserIdAndSourceTypeOrderByIdAsc(user,CreditSource.SIGNUP)
                        .ifPresent(g -> history.saveAndFlush(SignupRewardHistory.create(hash,g.getCreatedAt())));
            var task=tasks.lock(hash);
            String encrypted=secrets.encrypt(a.getProviderUserId());
            if(task.isPresent()) task.get().enqueue(user,encrypted,now());
            else tasks.saveAndFlush(SocialUnlinkTask.create(hash,user,encrypted,now()));
        });
    }
}
