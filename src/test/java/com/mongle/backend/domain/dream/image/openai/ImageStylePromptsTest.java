package com.mongle.backend.domain.dream.image.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.world.image.WorldImage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ImageStylePromptsTest {
    @ParameterizedTest
    @ValueSource(strings={"surrealism","geometric-cubism","expressive-brushwork","modern-pop-art"})
    void bothImageContractsUseSelectedTechniqueWithConfirmedOutputSettings(String style) {
        var properties=new OpenAiImageProperties(true,"fake-key","gpt-image-2.5-flare","1024x1024","medium","png",
                Duration.ofSeconds(3),Duration.ofSeconds(60),2,null,null);
        var prompt=new OpenAiImagePrompt(properties);
        var sections=OpenAiTestServer.input().sections();
        for(String version:List.of(DreamImage.PROMPT_VERSION,WorldImage.PROMPT_VERSION)) {
            boolean world=version.equals(WorldImage.PROMPT_VERSION);
            var input=new ImageGenerator.Input(1L,2L,"attempt",version,style,null,
                    world?List.of():sections,world?List.of(new ImageGenerator.WorldStory(sections,null)):List.of());
            var body=JsonMapper.builder().build().readTree(prompt.request(input));
            assertThat(body.get("model").asString()).isEqualTo("gpt-image-2.5-flare");
            assertThat(body.get("size").asString()).isEqualTo("1024x1024");
            assertThat(body.get("quality").asString()).isEqualTo("medium");
            assertThat(body.get("output_format").asString()).isEqualTo("png");
            assertThat(body.get("prompt").asString()).contains(properties.stylePrompts().get(style),"이야기 근거 없이 추가하지 않는다")
                    .doesNotContain("Dalí","Magritte","Picasso","Kandinsky","van Gogh","Basquiat","Murakami","Moebius");
            var selected=properties.stylePrompts().get(style);
            for(String other:properties.stylePrompts().values()) {
                if(!other.equals(selected)) assertThat(body.get("prompt").asString()).doesNotContain(other);
            }
        }
    }
}
