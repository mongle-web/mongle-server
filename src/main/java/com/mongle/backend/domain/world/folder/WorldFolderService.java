package com.mongle.backend.domain.world.folder;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldFolderService {
    private final WorldFolderRepository folders;
    private final UserRepository users;

    @Transactional
    public WorldFolderResponse create(Long userId, String name) {
        return response(folders.save(WorldFolder.create(lockUser(userId), name)));
    }








    private WorldFolderResponse response(WorldFolder folder) {
        folders.flush();
        return WorldFolderResponse.from(folder);
    }



    private User lockUser(Long userId) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(WorldFolderErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        return user;
    }

}
