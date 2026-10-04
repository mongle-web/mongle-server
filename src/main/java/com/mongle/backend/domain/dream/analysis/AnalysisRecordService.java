package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.global.common.GenerationStatus;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class AnalysisRecordService {
    private final EntityManager em;
    private final StoredAnalysisRepository analyses;
    private final AnalysisTransactions transactions;
    private final Clock clock;
    private final int days;
    private final int limit;

    public AnalysisRecordService(
            EntityManager em,
            StoredAnalysisRepository analyses,
            AnalysisTransactions transactions,
            Clock authClock,
            @Value("${mongle.analysis.recent-days:30}") int days,
            @Value("${mongle.analysis.recent-limit:10}") int limit) {
        if (days < 1 || days > 365 || limit < 1 || limit > 30)
            throw new IllegalArgumentException("최근 분석 범위 설정이 올바르지 않습니다.");
        this.em = em;
        this.analyses = analyses;
        this.transactions = transactions;
        this.clock = authClock;
        this.days = days;
        this.limit = limit;
    }

    public record Keyword(String name, long dreamCount) {}

    public record Emotion(DreamEmotion emotion, long dreamCount) {}

    public record Monthly(
            YearMonth month,
            long analyzedDreamCount,
            List<Keyword> keywords,
            List<Emotion> emotions) {}

    public record Recent(
            LocalDate from,
            LocalDate to,
            int maximumDreams,
            boolean limitedEvidence,
            List<AnalysisResponse> dreams) {}

    public Monthly monthly(Long userId, YearMonth month) {
        var from = month.atDay(1);
        var to = month.atEndOfMonth();
        long count =
                em.createQuery(
                                "select count(a) from DreamAnalysis a where a.user.id=:user and a.status=:status and a.dreamedAt between :from and :to",
                                Long.class)
                        .setParameter("user", userId)
                        .setParameter("status", GenerationStatus.COMPLETED)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult();
        var rows =
                em.createQuery(
                                "select min(e.name),count(distinct a.id) from DreamEntity e join e.analysis a where a.user.id=:user and a.status=:status and a.dreamedAt between :from and :to and e.entityType<>:emotion group by e.normalizedName order by count(distinct a.id) desc,min(e.name) asc",
                                Object[].class)
                        .setParameter("user", userId)
                        .setParameter("status", GenerationStatus.COMPLETED)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .setParameter("emotion", DreamEntityType.EMOTION)
                        .setMaxResults(5)
                        .getResultList();
        var emotions =
                em.createQuery(
                                "select emotion,count(distinct d.id) from Dream d join d.emotions emotion where d.user.id=:user and d.recordStatus=:status and d.dreamedAt between :from and :to group by emotion",
                                Object[].class)
                        .setParameter("user", userId)
                        .setParameter("status", DreamRecordStatus.COMPLETED)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList();
        return new Monthly(
                month,
                count,
                rows.stream()
                        .map(r -> new Keyword((String) r[0], ((Number) r[1]).longValue()))
                        .toList(),
                emotions.stream()
                        .map(r -> new Emotion((DreamEmotion) r[0], ((Number) r[1]).longValue()))
                        .sorted(Comparator.comparing(Emotion::emotion))
                        .toList());
    }

    public Recent recent(Long userId) {
        var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var from = today.minusDays(days - 1L);
        var selected =
                analyses.recent(
                        userId, GenerationStatus.COMPLETED, from, today, PageRequest.of(0, limit));
        return new Recent(
                from,
                today,
                limit,
                selected.size() < 3,
                selected.stream().map(transactions::response).toList());
    }
}
