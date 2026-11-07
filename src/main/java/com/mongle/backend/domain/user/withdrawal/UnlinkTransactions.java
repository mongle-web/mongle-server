package com.mongle.backend.domain.user.withdrawal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class UnlinkTransactions {
    private final SocialUnlinkTaskRepository tasks;
    private final Clock authClock;
    public record Claim(String identityHash,String token,String encryptedProviderId) {
        @Override public String toString() { return "UnlinkClaim[redacted]"; }
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(authClock.instant(),ZoneOffset.UTC); }
    @Transactional(readOnly=true)
    public List<String> candidates() {
        return tasks.due(List.of(SocialUnlinkTask.State.PENDING,SocialUnlinkTask.State.RETRY_WAIT,
                SocialUnlinkTask.State.PROCESSING),now(),PageRequest.of(0,5));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(String hash) {
        var found=tasks.lock(hash);
        if(found.isEmpty() || !found.get().due(now())) return Optional.empty();
        var t=found.get(); String token=t.claim(now());
        tasks.flush();
        return Optional.of(new Claim(hash,token,t.getEncryptedProviderId()));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(Claim claim,boolean success) {
        tasks.lock(claim.identityHash()).ifPresent(t->{
            if(success) t.complete(claim.token());
            else t.retry(claim.token(),"KAKAO_UNLINK_UNCONFIRMED",now());
        });
    }
}
