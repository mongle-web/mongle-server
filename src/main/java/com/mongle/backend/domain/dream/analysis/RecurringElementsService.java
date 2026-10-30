package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.DreamEntityType;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;

/** 마지막 공개 성공 분석을 읽는다. 재생성 후보와 현재 원문으로 통계 근거를 교체하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RecurringElementsService {
    private static final String SOURCES =
            " where a.user.id=:user and d.user.id=:user and d.recordStatus=:recordStatus and"
                + " (a.resultRevision is not null or (a.status=:success and a.regenerating=false))"
                + " and a.dreamedAt between :from and :to";
    private final EntityManager em;

    public RecurringElementsResponse monthly(Long user, YearMonth month) {
        long count =
                parameters(
                                em.createQuery(
                                        "select count(distinct d.id) from DreamAnalysis a join"
                                            + " a.dream d"
                                                + SOURCES,
                                        Long.class),
                                user,
                                month)
                        .getSingleResult();
        return new RecurringElementsResponse(
                month,
                count,
                elements(user, month, DreamEntityType.PLACE),
                elements(user, month, DreamEntityType.CHARACTER));
    }

    private List<RecurringElementsResponse.Element> elements(
            Long user, YearMonth month, DreamEntityType type) {
        var rows =
                parameters(
                                em.createQuery(
                                        "select min(e.name),count(distinct d.id) from DreamEntity e"
                                            + " join e.analysis a join a.dream d"
                                                + SOURCES
                                                + " and e.user.id=:user and"
                                                + " e.entityType=:elementType and e.normalizedName"
                                                + " is not null and e.normalizedName<>'' group by"
                                                + " e.normalizedName having count(distinct d.id)>=2"
                                                + " order by count(distinct d.id)"
                                                + " desc,e.normalizedName asc",
                                        Object[].class),
                                user,
                                month)
                        .setParameter("elementType", type)
                        .setMaxResults(5)
                        .getResultList();
        return rows.stream()
                .map(
                        row ->
                                new RecurringElementsResponse.Element(
                                        (String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    private <T> TypedQuery<T> parameters(TypedQuery<T> query, Long user, YearMonth month) {
        return query.setParameter("user", user)
                .setParameter("recordStatus", DreamRecordStatus.COMPLETED)
                .setParameter("success", GenerationStatus.COMPLETED)
                .setParameter("from", month.atDay(1))
                .setParameter("to", month.atEndOfMonth());
    }
}
