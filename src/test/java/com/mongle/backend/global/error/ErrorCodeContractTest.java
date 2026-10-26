package com.mongle.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.dream.analysis.AnalysisErrorCode;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.image.ImageErrorCode;
import com.mongle.backend.domain.dream.story.StoryErrorCode;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.world.bridge.BridgeErrorCode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

class ErrorCodeContractTest {
    private static final Pattern FORMAT = Pattern.compile("([A-Z]+)_(\\d{3})_(\\d+)");

    @Test
    void everyErrorCodeHasMatchingStatusUniqueValueAndConsecutiveSequence() {
        Set<String> uniqueCodes = new HashSet<>();
        Map<String, List<Integer>> sequences = new HashMap<>();

        for (Family family : families()) {
            for (ErrorCode error : family.codes()) {
                var matcher = FORMAT.matcher(error.getCode());
                assertThat(matcher.matches())
                        .as("%s must follow DOMAIN_HTTP_STATUS_SEQUENCE", error.getCode())
                        .isTrue();
                assertThat(matcher.group(1)).isEqualTo(family.prefix());
                assertThat(Integer.parseInt(matcher.group(2)))
                        .isEqualTo(error.getHttpStatus().value());
                assertThat(uniqueCodes.add(error.getCode()))
                        .as("error code must be unique: %s", error.getCode())
                        .isTrue();

                String key = matcher.group(1) + '_' + matcher.group(2);
                sequences
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(Integer.parseInt(matcher.group(3)));
            }
        }

        sequences.forEach(
                (key, actual) -> {
                    actual.sort(Integer::compareTo);
                    assertThat(actual)
                            .as("%s sequence must start at 1 without gaps", key)
                            .containsExactlyElementsOf(
                                    IntStream.rangeClosed(1, actual.size()).boxed().toList());
                });
    }

    private List<Family> families() {
        return List.of(
                new Family("COMMON", CommonErrorCode.values()),
                new Family("AUTH", AuthErrorCode.values()),
                new Family("USER", UserErrorCode.values()),
                new Family("DREAM", DreamErrorCode.values()),
                new Family("ANALYSIS", AnalysisErrorCode.values()),
                new Family("STORY", StoryErrorCode.values()),
                new Family("IMAGE", ImageErrorCode.values()),
                new Family("BRIDGE", BridgeErrorCode.values()),
                new Family("AI", AiGatewayErrorCode.values()));
    }

    private record Family(String prefix, ErrorCode[] codes) {}
}
