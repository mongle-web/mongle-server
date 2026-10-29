package com.mongle.backend.domain.world.folder;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.common.SearchQuery;
import com.mongle.backend.domain.world.summary.WorldSummaryReader;
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
    private final WorldSelectionRepository selections;
    private final WorldSelectionQueryRepository queries;
    private final WorldFolderCardQueries cards;
    private final WorldSummaryReader summaryReader;
    private final UserRepository users;
    private final DreamRepository dreams;
    private final com.mongle.backend.domain.world.composition.WorldCompositionDeletion compositions;

    @Transactional
    public WorldFolderResponse create(Long userId, String name) {
        return response(folders.save(WorldFolder.create(lockUser(userId), name)));
    }

    public WorldFolderResponse get(Long userId, Long worldId) {
        return WorldFolderResponse.from(owned(userId, worldId));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public WorldFolderPage list(Long userId, Long beforeId, int size, String query) {
        validatePage(beforeId, size);
        String term = SearchQuery.normalize(query, WorldFolderErrorCode.INVALID_QUERY);
        var rows = folders.page(userId, beforeId, SearchQuery.containsPattern(term), PageRequest.of(0, size + 1));
        boolean hasNext = rows.size() > size;
        var selected = rows.stream().limit(size).toList();
        var ids = selected.stream().map(WorldFolder::getId).toList();
        var summaries = summaryReader.readAll(userId, ids);
        var representatives = cards.representatives(userId, ids);
        var items = selected.stream().map(folder -> {
            var view = summaries.get(folder.getId());
            return WorldFolderCardResponse.from(folder, view.composedDreamCount(),
                    representatives.get(folder.getId()), view.summary());
        }).toList();
        return new WorldFolderPage(items, hasNext, hasNext ? items.getLast().worldId() : null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public SelectedDreamPage selectedDreams(Long userId, Long worldId, String cursor, int size) {
        validatePage(null, size);
        var folder = owned(userId, worldId);
        var position = WorldSelectionCursor.parse(cursor, folder);
        var rows = queries.page(userId, worldId, position, size);
        boolean hasNext = rows.size() > size;
        var items = rows.stream().limit(size).toList();
        return new SelectedDreamPage(worldId, folder.getRevision(), folder.getSelectedDreamCount(), items,
                hasNext, hasNext ? WorldSelectionCursor.after(folder, items.getLast()) : null);
    }

    @Transactional
    public WorldFolderResponse rename(Long userId, Long worldId, WorldFolderUpdateRequest request) {
        lockUser(userId);
        var folder = owned(userId, worldId);
        folder.checkRevision(request.revision());
        folder.rename(request.name());
        return response(folder);
    }

    @Transactional
    public WorldFolderResponse select(Long userId, Long worldId, Long dreamId, Long revision) {
        lockUser(userId);
        var folder = owned(userId, worldId);
        folder.checkRevision(revision);
        var dream = completedDream(userId, dreamId);
        if (!selections.existsByFolderIdAndDreamId(worldId, dreamId)) {
            selections.save(WorldSelection.create(folder, dream));
            folder.selected();
        }
        return response(folder);
    }

    @Transactional
    public WorldFolderResponse exclude(Long userId, Long worldId, Long dreamId, Long revision) {
        lockUser(userId);
        var folder = owned(userId, worldId);
        folder.checkRevision(revision);
        completedDream(userId, dreamId);
        if (selections.exclude(worldId, dreamId) > 0) folder.excluded();
        return response(folder);
    }

    @Transactional
    public void delete(Long userId, Long worldId, Long revision) {
        lockUser(userId);
        var folder = owned(userId, worldId);
        folder.checkRevision(revision);
        compositions.folderDeleted(userId,worldId);
        selections.deleteForFolder(worldId);
        folders.delete(folder);
        folders.flush();
    }

    private WorldFolderResponse response(WorldFolder folder) {
        folders.flush();
        return WorldFolderResponse.from(folder);
    }

    private WorldFolder owned(Long userId, Long worldId) {
        return folders.findByIdAndUserId(worldId, userId)
                .orElseThrow(() -> new BusinessException(WorldFolderErrorCode.NOT_FOUND));
    }

    private Dream completedDream(Long userId, Long dreamId) {
        var dream = dreams.findByIdAndUserId(dreamId, userId)
                .orElseThrow(() -> new BusinessException(DreamErrorCode.NOT_FOUND));
        if (dream.getRecordStatus() != DreamRecordStatus.COMPLETED)
            throw new BusinessException(WorldFolderErrorCode.DREAM_NOT_COMPLETED);
        return dream;
    }

    private User lockUser(Long userId) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(WorldFolderErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
        return user;
    }

    private void validatePage(Long beforeId, int size) {
        if (size < 1 || size > 50 || (beforeId != null && beforeId < 1))
            throw new BusinessException(WorldFolderErrorCode.INVALID_PAGE);
    }
}
