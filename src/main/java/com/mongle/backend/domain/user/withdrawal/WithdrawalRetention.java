package com.mongle.backend.domain.user.withdrawal;

import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.dream.image.ImageCleanupRequest;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class WithdrawalRetention {
    private final UserRepository users;
    private final WithdrawalData data;
    private final WithdrawalRefundPolicy refunds;
    private final SocialUnlinkTaskRepository tasks;
    private final EntityManager em;
    private final Clock authClock;
    private LocalDateTime now() { return LocalDateTime.ofInstant(authClock.instant(),ZoneOffset.UTC); }
    @Transactional(readOnly=true)
    public List<Long> candidates(long afterUserId) {
        return em.createQuery("select u.id from User u where u.withdrawnAt is not null and u.retentionUntil<=:now and u.id>:after order by u.id",Long.class)
                .setParameter("now",now()).setParameter("after",afterUserId).setMaxResults(5).getResultList();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean purge(Long userId) {
        var found=users.findByIdForUpdate(userId);
        if(found.isEmpty() || !found.get().isWithdrawn() || found.get().getRetentionUntil().isAfter(now())) return false;
        if(refunds.hasOpenRefunds(userId) || tasks.existsByUserIdAndStateIn(userId,List.of(
                SocialUnlinkTask.State.PENDING,SocialUnlinkTask.State.PROCESSING,SocialUnlinkTask.State.RETRY_WAIT))) return false;
        long unfinished=em.createQuery("select count(r) from ImageCleanupRequest r where r.userId=:user and r.status<>:done",Long.class)
                .setParameter("user",userId).setParameter("done",ImageCleanupRequest.Status.COMPLETED).getSingleResult();
        if(unfinished>0) return false;
        data.deleteAccounting(userId,now());
        em.createQuery("delete from AiGenerationLog l where l.user.id=:user").setParameter("user",userId).executeUpdate();
        em.createQuery("delete from ImageCleanupRequest r where r.userId=:user").setParameter("user",userId).executeUpdate();
        em.createQuery("delete from SocialUnlinkTask t where t.userId=:user").setParameter("user",userId).executeUpdate();
        users.deleteById(userId); users.flush();
        return true;
    }
    /** 운영자가 INITIAL_SIGNUP 혜택의 운영 종료를 확정한 뒤 호출하는 내부 파기 수단. HTTP로 노출하지 않는다. */
    @Transactional
    public int deleteEndedSignupBenefit() {
        return em.createQuery("delete from SignupRewardHistory h where h.benefit='INITIAL_SIGNUP'").executeUpdate();
    }
}
