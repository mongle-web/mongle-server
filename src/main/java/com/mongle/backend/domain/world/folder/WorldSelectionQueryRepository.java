package com.mongle.backend.domain.world.folder;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class WorldSelectionQueryRepository {
    private final EntityManager em;

    public List<SelectedDreamResponse> page(Long userId, Long worldId, WorldSelectionCursor cursor, int size) {
        String jpql = "select new com.mongle.backend.domain.world.folder.SelectedDreamResponse"
                + "(d.id,s.dreamedAt,d.title,d.originalText) from WorldSelection s join s.dream d"
                + " where s.folder.id=:world and s.folder.user.id=:user and d.user.id=:user";
        if (cursor != null)
            jpql += " and (s.dreamedAt>:date or (s.dreamedAt=:date and d.id>:id))";
        var query = em.createQuery(jpql + " order by s.dreamedAt asc,s.dream.id asc", SelectedDreamResponse.class)
                .setParameter("world", worldId).setParameter("user", userId);
        if (cursor != null) query.setParameter("date", cursor.date()).setParameter("id", cursor.dreamId());
        // 컬렉션 fetch join이나 행별 AI 결과 조회 없이 요청한 개수+1만 읽는다.
        return query.setMaxResults(size + 1).getResultList();
    }
}
