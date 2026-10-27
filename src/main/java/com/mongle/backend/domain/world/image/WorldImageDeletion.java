package com.mongle.backend.domain.world.image;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.global.common.GenerationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.List;

/** 호출자는 사용자 행 잠금을 보유한다. 꿈 삭제는 성공 이미지를 보존하고 폴더 삭제는 파일 정리를 예약한다. */
@Service @RequiredArgsConstructor
public class WorldImageDeletion {
    private final WorldImageRepository images;
    private final WorldImageSourceRepository sources;
    private final CreditReservationService credits;
    private final ImageCleanupRegistration cleanup;
    private final Clock authClock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void dreamDeleted(Long user, Long dream) {
        // 현재 완성본에서 이미 제외됐더라도 보존 이미지의 출처에 남아 있으면 표시한다.
        for(Long id:sources.imagesForDream(user,dream)) {
            var image=images.findByIdAndUserId(id,user).orElseThrow();
            if(sources.existsByImageIdAndDreamIdAndCandidate(id,dream,false)) image.sourceDeleted();
            if(image.getStatus()==GenerationStatus.PROCESSING && sources.existsByImageIdAndDreamIdAndCandidate(id,dream,true)) {
                releaseAndRegister(image); image.fail("SOURCE_DELETED");
            }
        }
        sources.deleteForDream(user,dream);
        images.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void folderDeleted(Long user, Long world) {
        var found=images.findByWorldIdAndUserId(world,user);
        if(found.isEmpty()) return;
        var image=found.get(); releaseAndRegister(image);
        if(image.getStorageKey()!=null) cleanup.register(user,null,image.getId(),image.getStorageKey(),ImageCleanupRequest.Reason.WORLD_DELETED);
        sources.deleteLane(image.getId(),true); sources.deleteLane(image.getId(),false);
        images.delete(image); images.flush();
    }
    private void releaseAndRegister(WorldImage image) {
        if(image.getCreditReservationId()!=null) {
            var r=credits.get(image.getUserId(),image.getCreditReservationId());
            if(r.state()==CreditReservation.State.RESERVED) credits.release(image.getUserId(),r.reservationId(),
                    r.expiresAt().isAfter(authClock.instant())?CreditReservation.ReleaseReason.FAILURE:CreditReservation.ReleaseReason.EXPIRED);
        }
        if(image.getStatus()==GenerationStatus.PROCESSING) for(String ext:List.of(".png",".jpg"))
            cleanup.register(image.getUserId(),null,image.getId(),"world-images/"+image.getUserId()+"/"+image.getId()+"/"+image.getAttemptId()+ext,
                    ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
    }
}
