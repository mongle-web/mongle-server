package com.mongle.backend.domain.world.folder;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** DreamService의 소유권·revision 검사와 사용자 잠금 안에서만 실행한다. */
@Repository
@RequiredArgsConstructor
public class WorldSelectionDeletion {
    private final EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForDream(Long userId, Long dreamId) {
        em.flush();
        // 삭제할 꿈이 속한 폴더를 전부 로딩하지 않고 선택 수·버전·수정 시각을 함께 갱신한다.
        em.createQuery("update WorldFolder f set f.selectedDreamCount=f.selectedDreamCount-1,"
                        + " f.revision=f.revision+1,f.updatedAt=:now where f.user.id=:user and f.id in"
                        + " (select s.folder.id from WorldSelection s where s.dream.id=:dream)")
                .setParameter("now", LocalDateTime.now()).setParameter("user", userId)
                .setParameter("dream", dreamId).executeUpdate();
        em.createQuery("delete from WorldSelection s where s.dream.id=:dream and s.folder.user.id=:user")
                .setParameter("dream", dreamId).setParameter("user", userId).executeUpdate();
        // 벌크 변경 이전의 폴더 상태가 같은 트랜잭션에서 다시 저장되지 않도록 비운다.
        em.clear();
    }
}
