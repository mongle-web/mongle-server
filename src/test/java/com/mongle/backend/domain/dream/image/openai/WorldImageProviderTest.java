package com.mongle.backend.domain.dream.image.openai;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.story.StoryResult;
import com.mongle.backend.domain.world.image.WorldImage;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

class WorldImageProviderTest {
    @Test void sharedProviderSendsDedicatedWorldPromptWithOrderedStoriesAndBridges() throws Exception {
        try(var server=new OpenAiTestServer();var generator=server.generator(Duration.ofSeconds(3),4)) {
            server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(OpenAiTestServer.image("png"))));
            var section=new StoryResult.Section(1,StoryResult.Kind.SCENE,1,"I saw the sea");
            var input=new ImageGenerator.Input(1L,2L,UUID.randomUUID().toString(),WorldImage.PROMPT_VERSION,"test-style",null,List.of(),
                    List.of(new ImageGenerator.WorldStory(List.of(section),null),new ImageGenerator.WorldStory(List.of(section),"Across the sea")));
            assertThat(generator.generate(input).get(5,TimeUnit.SECONDS)).containsExactly(OpenAiTestServer.image("png"));
            var json=JsonMapper.builder().build().readTree(server.received.getFirst().body());
            assertThat(json.get("prompt").asString()).contains("image-v1-world","dreams","Across the sea","I saw the sea")
                    .doesNotContain("userId","imageId","attemptId");
            assertThat(server.received).hasSize(1);
        }
    }
    @Test void mixedOrUnknownInputContractFailsBeforePaidHttpRequest() throws Exception {
        try(var server=new OpenAiTestServer();var generator=server.generator(Duration.ofSeconds(3),4)) {
            var input=new ImageGenerator.Input(1L,2L,"attempt",WorldImage.PROMPT_VERSION,"test-style",null,
                    OpenAiTestServer.input().sections(),List.of(new ImageGenerator.WorldStory(OpenAiTestServer.input().sections(),null)));
            assertThatThrownBy(()->generator.generate(input).get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(server.received).isEmpty();
        }
    }
}
