package com.mongle.backend.domain.world.image;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.composition.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldImageTransactions {
    private final UserRepository users;
    private final WorldFolderRepository folders;
    private final WorldCompositionRepository compositions;
    private final WorldImageRepository images;
    private final WorldImageSourceRepository sources;
    private final WorldImageSources inputSources;
    private final CreditReservationService reservations;
    private final CreditService credits;
    private final ImageCleanupRegistration cleanup;
    private final ImageCleanupRequestRepository cleanupRequests;
    private final ImageOptions options;
    private final WorldImageProperties properties;
    private final Clock authClock;
    private final ImageRegenerationPolicy regenerationPolicy;

    public record Reservation(WorldImageResponse response, ImageGenerator.Input input) {}
    public record Completion(WorldImageResponse response, boolean stored) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Reservation begin(Long user, Long world, WorldImageRequest request, boolean available, Runnable admit) {
        lock(user); folder(user, world);
        var composition = composition(user, world);
        if (!Objects.equals(request.compositionRevision(), composition.getCompositionRevision())) throw conflict();
        options.requireAllowed(request.style(),request.mood());
        var prior = images.findByWorldIdAndUserId(world,user);
        if (prior.isPresent() && prior.get().active(authClock.instant())) {
            var image = prior.get();
            if (image.getCompositionRevision()!=composition.getCompositionRevision()
                    || !Objects.equals(image.getStyle(),request.style()) || !Objects.equals(image.getMood(),request.mood())) throw conflict();
            return new Reservation(response(image),null);
        }
        if (prior.isPresent() && prior.get().getStorageKey()!=null && !request.regenerate()) {
            var image = prior.get();
            if (Objects.equals(image.getResultCompositionRevision(),composition.getCompositionRevision())
                    && Objects.equals(image.getResultStyle(),request.style()) && Objects.equals(image.getResultMood(),request.mood()))
                return new Reservation(response(image),null);
            throw new BusinessException(WorldImageErrorCode.REGENERATION_REQUIRED);
        }
        if (request.regenerate() && (prior.isEmpty() || !Objects.equals(request.imageVersion(),prior.get().getVersion()))) throw conflict();
        if (composition.getStatus()==GenerationStatus.PENDING || composition.getStatus()==GenerationStatus.PROCESSING)
            throw new BusinessException(WorldImageErrorCode.COMPOSITION_BUSY);
        var snapshot = inputSources.capture(user,world);
        if (!properties.enabled() || !available) throw new BusinessException(ImageErrorCode.UNAVAILABLE);
        admit.run();
        var image = prior.orElseGet(()->WorldImage.create(user,world));
        release(image); registerAttempt(image);
        image.start(composition.getCompositionRevision(),request.style(),request.mood(),authClock.instant());
        if (image.isFreeRegenerationAttempt()) {
            regenerationPolicy.requireFreeAttemptAllowed(user, CreditUsage.WORLD_IMAGE_REGENERATION);
        } else {
            var receipt = reservations.reserve(user,"world-image:"+image.getAttemptId(),
                    image.getStorageKey()!=null?CreditUsage.WORLD_IMAGE_REGENERATION:CreditUsage.WORLD_IMAGE_GENERATION,
                    "world-image:"+world+":"+image.getAttemptId(),image.getLeaseUntil());
            image.reserve(receipt.reservationId());
        }
        images.saveAndFlush(image);
        sources.deleteLane(image.getId(),true);
        sources.saveAll(snapshot.references().stream().map(ref->WorldImageSource.create(image,ref)).toList());
        sources.flush();
        return new Reservation(response(image),new ImageGenerator.Input(user,image.getId(),image.getAttemptId(),
                WorldImage.PROMPT_VERSION,image.getStyle(),image.getMood(),List.of(),snapshot.stories()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Completion finish(ImageGenerator.Input input, String key, ImagePayload payload) {
        lock(input.userId()); var image = owned(input.userId(),input.imageId());
        if (!image.accepts(input.attemptId())) return new Completion(response(image),Objects.equals(image.getStorageKey(),key));
        String failure = sourceFailure(image);
        if (failure==null && !image.active(authClock.instant())) failure="ATTEMPT_EXPIRED";
        if (failure==null && cleanupRequests.existsByStorageKeyAndDeletionStartedAtIsNotNull(key)) failure="ASSET_DISCARDED";
        if (failure==null) failure=settlementFailure(image);
        if (failure!=null) { release(image); image.fail(failure); }
        else {
            String previousKey=image.getStorageKey();
            if (!image.isFreeRegenerationAttempt()) reservations.settle(image.getUserId(),image.getCreditReservationId(),key);
            sources.deleteLane(image.getId(),false); sources.publish(image.getId());
            image.finish(key,payload);
            if (previousKey!=null && !previousKey.equals(key))
                cleanup.register(image.getUserId(),null,image.getId(),previousKey,ImageCleanupRequest.Reason.REPLACED_RESULT);
        }
        images.flush();
        return new Completion(response(image),failure==null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public WorldImageResponse fail(ImageGenerator.Input input, String code) {
        lock(input.userId()); var image=owned(input.userId(),input.imageId());
        if (image.accepts(input.attemptId())) {
            String failure=sourceFailure(image); release(image); image.fail(failure==null?code:failure); images.flush();
        }
        return response(image);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean expire(Long user, Long imageId) {
        lock(user); var found=images.findByIdAndUserId(imageId,user);
        if (found.isEmpty()) return false;
        var image=found.get();
        if (image.getStatus()!=GenerationStatus.PROCESSING || image.active(authClock.instant())) return false;
        release(image); registerAttempt(image); image.fail("ATTEMPT_EXPIRED"); images.flush(); return true;
    }
    public WorldImageResponse latest(Long user, Long world) {
        folder(user,world);
        return response(images.findByWorldIdAndUserId(world,user).orElseThrow(()->new BusinessException(WorldImageErrorCode.NOT_FOUND)));
    }
    public String assetKey(Long user, Long world) {
        folder(user,world);
        String key=images.findByWorldIdAndUserId(world,user).orElseThrow(()->new BusinessException(WorldImageErrorCode.NOT_FOUND)).getStorageKey();
        if(key==null) throw new BusinessException(WorldImageErrorCode.NOT_FOUND); return key;
    }
    private String sourceFailure(WorldImage image) {
        var current=compositions.findByWorldIdAndUserId(image.getWorldId(),image.getUserId());
        if(current.isEmpty()) return "SOURCE_DELETED";
        if(current.get().getCompositionRevision()!=image.getCompositionRevision()) return "COMPOSITION_CHANGED";
        return null;
    }
    private String settlementFailure(WorldImage image) {
        if(image.isFreeRegenerationAttempt()) return credits.balance(image.getUserId()).spendingBlocked()?"CREDIT_PAYMENT_REVIEW":null;
        if(image.getCreditReservationId()==null) return "CREDIT_RESERVATION_MISSING";
        var r=reservations.get(image.getUserId(),image.getCreditReservationId());
        if(r.state()!=CreditReservation.State.RESERVED || !r.expiresAt().isAfter(authClock.instant())) return "CREDIT_RESERVATION_EXPIRED";
        return credits.balance(image.getUserId()).spendingBlocked()?"CREDIT_PAYMENT_REVIEW":null;
    }
    private void release(WorldImage image) {
        if(image.getCreditReservationId()==null) return;
        var r=reservations.get(image.getUserId(),image.getCreditReservationId());
        if(r.state()==CreditReservation.State.RESERVED) reservations.release(image.getUserId(),r.reservationId(),
                r.expiresAt().isAfter(authClock.instant())?CreditReservation.ReleaseReason.FAILURE:CreditReservation.ReleaseReason.EXPIRED);
    }
    private void registerAttempt(WorldImage image) {
        if(image.getStatus()!=GenerationStatus.PROCESSING) return;
        for(String ext:List.of(".png",".jpg")) cleanup.register(image.getUserId(),null,image.getId(),
                "world-images/"+image.getUserId()+"/"+image.getId()+"/"+image.getAttemptId()+ext,ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
    }
    private WorldImageResponse response(WorldImage image) {
        var composition=compositions.findByWorldIdAndUserId(image.getWorldId(),image.getUserId());
        long visible=image.getResultCompositionRevision()==null?image.getCompositionRevision():image.getResultCompositionRevision();
        return new WorldImageResponse(image.getId(),image.getWorldId(),image.getVersion(),image.getStatus(),image.getFailureCode(),
                image.getCompositionRevision(),image.getResultCompositionRevision(),composition.isEmpty()||composition.get().getCompositionRevision()!=visible,
                sources.sourceChanged(image.getId()),image.isResultSourceDeleted(),
                image.getStorageKey()!=null && image.getStatus()!=GenerationStatus.COMPLETED,
                image.getStyle(),image.getMood(),image.getResultStyle(),image.getResultMood(),image.getContentType(),image.getWidth(),image.getHeight(),
                image.getStorageKey()!=null && !image.isFreeRegenerationUsed(),
                regenerationPolicy.nextCost(image.getStorageKey()!=null,image.isFreeRegenerationUsed(),CreditUsage.WORLD_IMAGE_REGENERATION));
    }
    private WorldImage owned(Long user, Long image) { return images.findByIdAndUserId(image,user).orElseThrow(()->new BusinessException(WorldImageErrorCode.NOT_FOUND)); }
    private void folder(Long user, Long world) { folders.findByIdAndUserId(world,user).orElseThrow(()->new BusinessException(WorldFolderErrorCode.NOT_FOUND)); }
    private WorldComposition composition(Long user, Long world) {
        return compositions.findByWorldIdAndUserId(world,user).filter(WorldComposition::isHasResult)
                .orElseThrow(()->new BusinessException(WorldImageErrorCode.COMPOSITION_REQUIRED));
    }
    private void lock(Long user) {
        var u=users.findByIdForUpdate(user).orElseThrow(()->new BusinessException(WorldFolderErrorCode.NOT_FOUND));
        if(!u.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
    private BusinessException conflict() { return new BusinessException(WorldImageErrorCode.VERSION_CONFLICT); }
}
