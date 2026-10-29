package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.dream.analysis.StructureValidator;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.story.StoryResult;
import com.mongle.backend.domain.dream.story.StoryValidator;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 공개된 성공 서사 버전의 스냅샷만 집계한다. 현재 분석/감정으로 대체하지 않는다. */
@Component
@RequiredArgsConstructor
public class WorldSummaryEvidence {
    private final StructureValidator analyses;
    private final StoryValidator stories;
    private final JsonMapper json = JsonMapper.builder().build();

    public record Source(
            Long dreamId,
            Long storyVersionId,
            long sourceRevision,
            long observedRevision,
            long position,
            Long bridgeId) {}

    record Row(Source source, String story, String analysis, String emotions, String bridge) {
        @Override
        public String toString() {
            return "SummaryRow[redacted]";
        }
    }

    public record Passage(List<StoryResult.Section> sections, String connectionBefore) {
        public Passage {
            sections = List.copyOf(sections);
        }

        @Override
        public String toString() {
            return "SummaryPassage[redacted]";
        }
    }

    public record Data(
            List<Passage> stories,
            List<WorldSummaryContent.Keyword> commonKeywords,
            List<WorldSummaryContent.Emotion> recurringEmotions,
            boolean limitedEvidence) {
        public Data {
            stories = List.copyOf(stories);
            commonKeywords = List.copyOf(commonKeywords);
            recurringEmotions = List.copyOf(recurringEmotions);
        }

        @Override
        public String toString() {
            return "SummaryData[redacted]";
        }
    }

    record Snapshot(List<Source> sources, Data data, String fingerprint) {
        Snapshot {
            sources = List.copyOf(sources);
        }

        @Override
        public String toString() {
            return "SummarySnapshot[redacted]";
        }
    }

    Snapshot assemble(List<Row> rows, String settings, int maxCharacters) {
        try {
            var counts = new TreeMap<String, Long>();
            var labels = new TreeMap<String, String>();
            var emotions = new EnumMap<DreamEmotion, Long>(DreamEmotion.class);
            var passages = new ArrayList<Passage>();
            var seen = new HashSet<Long>();
            boolean limited = rows.size() < 2;
            for (var row : rows) {
                if (!seen.add(row.source().dreamId())) throw new IllegalArgumentException();
                passages.add(new Passage(stories.decode(row.story()).sections(), row.bridge()));
                if (row.analysis() == null || row.emotions() == null) limited = true;
                if (row.analysis() != null) {
                    var perDream = new HashSet<String>();
                    for (var keyword : analyses.parse(row.analysis()).displayKeywords()) {
                        String key = StructureValidator.normalize(keyword);
                        if (perDream.add(key)) counts.merge(key, 1L, Long::sum);
                        labels.merge(key, keyword, (a, b) -> a.compareTo(b) <= 0 ? a : b);
                    }
                }
                if (row.emotions() != null && !row.emotions().isBlank()) {
                    var perDream = EnumSet.noneOf(DreamEmotion.class);
                    for (String value : row.emotions().split(","))
                        perDream.add(DreamEmotion.valueOf(value));
                    for (var emotion : perDream) emotions.merge(emotion, 1L, Long::sum);
                }
            }
            var keywords =
                    counts.entrySet().stream()
                            .filter(e -> e.getValue() >= 2)
                            .sorted(
                                    Map.Entry.<String, Long>comparingByValue()
                                            .reversed()
                                            .thenComparing(Map.Entry.comparingByKey()))
                            .limit(5)
                            .map(
                                    e ->
                                            new WorldSummaryContent.Keyword(
                                                    labels.get(e.getKey()), e.getValue()))
                            .toList();
            var repeated =
                    emotions.entrySet().stream()
                            .filter(e -> e.getValue() >= 2)
                            .sorted(
                                    Map.Entry.<DreamEmotion, Long>comparingByValue()
                                            .reversed()
                                            .thenComparing(e -> e.getKey().name()))
                            .map(
                                    e ->
                                            new WorldSummaryContent.Emotion(
                                                    e.getKey(),
                                                    e.getKey().getLabel(),
                                                    e.getValue()))
                            .toList();
            var data = new Data(passages, keywords, repeated, limited);
            String payload = json.writeValueAsString(data);
            if (payload.length() > maxCharacters)
                throw new BusinessException(WorldSummaryErrorCode.TOO_LARGE);
            var refs = rows.stream().map(Row::source).toList();
            String identity =
                    json.writeValueAsString(List.of(WorldSummaryPrompt.VERSION, settings, refs));
            return new Snapshot(
                    refs,
                    data,
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(identity.getBytes(StandardCharsets.UTF_8))));
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == WorldSummaryErrorCode.TOO_LARGE) throw ex;
            throw new BusinessException(WorldSummaryErrorCode.INPUT_UNAVAILABLE);
        } catch (Exception ex) {
            throw new BusinessException(WorldSummaryErrorCode.INPUT_UNAVAILABLE);
        }
    }
}
