package com.mongle.backend.domain.archive.repository;

import com.mongle.backend.domain.archive.dto.request.ArchiveSearch;
import com.mongle.backend.domain.archive.dto.request.ArchiveSort;
import com.mongle.backend.domain.archive.dto.request.ArchiveMonth;
import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.image.DreamImage;
import com.mongle.backend.domain.dream.story.DreamStory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Archive 전용 읽기 쿼리. 기존 Dream·분석·서사·이미지 모델을 조회하며 새 저장 모델을 만들지 않는다.
 * 컬렉션 fetch join에 페이지 제한을 적용하면 메모리 페이지네이션이 되므로 Dream 경계를 먼저 조회한다.
 */
@Repository
@RequiredArgsConstructor
public class ArchiveQueryRepository {
    private final EntityManager em;

    public List<com.mongle.backend.domain.dream.generation.DreamGenerationJob> findGenerationJobs(
            Long userId, List<Long> dreamIds) {
        return em.createQuery("select j from DreamGenerationJob j where j.userId=:user and j.dreamId in :ids",
                com.mongle.backend.domain.dream.generation.DreamGenerationJob.class)
                .setParameter("user", userId).setParameter("ids", dreamIds).getResultList();
    }

    /**
     * 캘린더는 날짜만 필요하므로 Dream 엔티티와 연관 컬렉션을 로딩하지 않는다.
     * DISTINCT 날짜로 기록 일수를 계산하고, 분석·이미지 유무와 무관하게 내 완성 기록만 포함한다.
     * 한 달 전체가 필요하므로 페이지 제한을 적용하지 않으며 최대 31개의 날짜를 반환한다.
     */
    public List<LocalDate> findRecordedDates(Long userId, ArchiveMonth month) {
        return em.createQuery("select distinct d.dreamedAt from Dream d "
                        + "where d.user.id=:user and d.recordStatus=:completed "
                        + "and d.dreamedAt between :from and :to order by d.dreamedAt asc", LocalDate.class)
                .setParameter("user", userId).setParameter("completed", DreamRecordStatus.COMPLETED)
                .setParameter("from", month.firstDay()).setParameter("to", month.lastDay()).getResultList();
    }

    /** 마지막 날짜·ID를 경계로 삼는다. 오래된순은 큰 값, 최신순은 작은 값부터 이어서 조회한다. */
    public List<Dream> findDreams(Long userId, ArchiveSearch search) {
        // 검증한 enum으로 고정된 연산자와 정렬 방향을 고른다. 사용자 문자열을 JPQL에 삽입하지 않는다.
        boolean oldest = search.sort() == ArchiveSort.OLDEST;
        String comparison = oldest ? ">" : "<";
        String direction = oldest ? " asc" : " desc";
        String jpql = "select d from Dream d where d.user.id=:user and d.recordStatus=:completed";
        if (search.from() != null) jpql += " and d.dreamedAt between :from and :to";
        if (search.position() != null)
            jpql += " and (d.dreamedAt" + comparison + ":lastDate or (d.dreamedAt=:lastDate and d.id"
                    + comparison + ":lastId))";
        var query = em.createQuery(jpql + " order by d.dreamedAt" + direction + ", d.id" + direction, Dream.class)
                .setParameter("user", userId).setParameter("completed", DreamRecordStatus.COMPLETED);
        if (search.from() != null) query.setParameter("from", search.from()).setParameter("to", search.to());
        if (search.position() != null)
            query.setParameter("lastDate", search.position().dreamedAt()).setParameter("lastId", search.position().dreamId());
        // 한 행을 더 읽어 hasNext를 판단한다. 무제한 조회와 별도 전체 count를 피한다.
        return query.setMaxResults(search.size() + 1).getResultList();
    }

    /** 상세는 한 기록만 읽으므로 감정 컬렉션을 함께 로딩해 트랜잭션 밖의 지연 로딩을 막는다. */
    public Optional<Dream> findDream(Long userId, Long dreamId) {
        return em.createQuery("select distinct d from Dream d left join fetch d.emotions "
                        + "where d.user.id=:user and d.id=:id and d.recordStatus=:completed", Dream.class)
                .setParameter("user", userId).setParameter("id", dreamId)
                .setParameter("completed", DreamRecordStatus.COMPLETED).getResultList().stream().findFirst();
    }

    /** 페이지에 실제 포함되는 기록만 배치 조회한다. 삭제 후 보존된 분석은 살아 있는 dream FK로 제외한다. */
    public List<DreamAnalysis> findAnalyses(Long userId, List<Long> dreamIds) {
        return em.createQuery("select distinct a from DreamAnalysis a left join fetch a.displayKeywords "
                        + "where a.user.id=:user and a.dream.id in :ids", DreamAnalysis.class)
                .setParameter("user", userId).setParameter("ids", dreamIds).getResultList();
    }

    /** 소유권 조건을 각 배치 쿼리에도 적용한다. 생성 결과 JSON은 출처 해시 비교에만 사용한다. */
    public List<DreamStory> findStories(Long userId, List<Long> analysisIds) {
        return em.createQuery("select s from DreamStory s where s.user.id=:user and s.analysis.id in :ids", DreamStory.class)
                .setParameter("user", userId).setParameter("ids", analysisIds).getResultList();
    }

    /** 객체 키를 클라이언트로 반환하지 않으며 이미지 존재·성공 결과 여부를 계산할 때만 사용한다. */
    public List<DreamImage> findImages(Long userId, List<Long> analysisIds) {
        return em.createQuery("select i from DreamImage i where i.user.id=:user and i.analysis.id in :ids", DreamImage.class)
                .setParameter("user", userId).setParameter("ids", analysisIds).getResultList();
    }
}
