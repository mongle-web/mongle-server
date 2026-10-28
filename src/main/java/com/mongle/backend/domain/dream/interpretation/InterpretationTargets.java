package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.dream.analysis.AnalysisRecordService;
import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/** 기존 recent-context와 같은 대상을 사용한다. 사용자 잠금 뒤의 짧은 트랜잭션에서 호출한다. */
@Component
@RequiredArgsConstructor
public class InterpretationTargets {
    private final AnalysisRecordService records;
    private final DreamRepository dreams;
    private final EntityManager em;
    private final JsonMapper json = JsonMapper.builder().build();

    public record Source(Long dreamId, Long analysisId, LocalDate dreamedAt,
                         long sourceRevision, long analysisRevision, Long publishedVersionId,
                         String analysisFingerprint) {}
    public record Evidence(LocalDate dreamedAt, List<String> keywords,
                           List<StructureResult.Scene> scenes, List<StructureResult.Element> elements) {
        @Override public String toString() { return "InterpretationEvidence[redacted]"; }
    }
    public record Target(LocalDate from, LocalDate to, List<Source> sources,
                         String fingerprint, String evidenceJson) {
        public boolean limitedEvidence() { return sources.size() < 3; }
        public boolean empty() { return sources.isEmpty(); }
        public List<Long> dreamIds() { return sources.stream().map(Source::dreamId).toList(); }
        @Override public String toString() { return "InterpretationTarget[count=" + sources.size() + "]"; }
    }

    public Target current(Long userId) {
        var recent = records.recent(userId);
        var sources = new ArrayList<Source>();
        var evidence = new ArrayList<Evidence>();
        for (var a : recent.dreams()) {
            var dream = dreams.findByIdAndUserId(a.dreamId(), userId).orElseThrow();
            var item = new Evidence(a.dreamedAt(), a.displayKeywords(), a.scenes(), a.elements());
            var version = em.createQuery("select max(v.id) from StoryResultVersion v where v.story.analysis.id=:id", Long.class)
                    .setParameter("id", a.analysisId()).getSingleResult();
            long visibleRevision = a.resultRevision() == null ? a.sourceRevision() : a.resultRevision();
            sources.add(new Source(a.dreamId(), a.analysisId(), a.dreamedAt(),
                    dream.getSourceRevision(), visibleRevision, version, digest(json.writeValueAsString(item))));
            evidence.add(item);
        }
        // 날짜만 하루 바뀌고 대상·결과가 동일한 경우에는 불필요한 갱신을 요구하지 않는다.
        String fingerprint = digest(InterpretationPrompt.VERSION + json.writeValueAsString(sources));
        return new Target(recent.from(), recent.to(), List.copyOf(sources), fingerprint,
                json.writeValueAsString(evidence));
    }

    public String encodeSources(List<Source> sources) { return json.writeValueAsString(sources); }
    public List<Source> decodeSources(String encoded) {
        return encoded == null ? List.of() : List.of(json.readValue(encoded, Source[].class));
    }
    public String encodeIds(List<Long> ids) { return json.writeValueAsString(ids); }
    public List<Long> decodeIds(String encoded) {
        return encoded == null ? List.of() : List.of(json.readValue(encoded, Long[].class));
    }

    public boolean allPresent(Long userId, List<Source> sources) {
        if (sources.isEmpty()) return false;
        var ids = sources.stream().map(Source::dreamId).toList();
        return em.createQuery("select count(d) from Dream d where d.user.id=:user and d.id in :ids", Long.class)
                .setParameter("user", userId).setParameter("ids", ids).getSingleResult() == ids.size();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
