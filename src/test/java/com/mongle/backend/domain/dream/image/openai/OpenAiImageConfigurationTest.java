package com.mongle.backend.domain.dream.image.openai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mongle.backend.domain.dream.image.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import java.time.*;
import java.util.*;

class OpenAiImageConfigurationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(ImageCleanupProperties.class)
    static class Dependencies { @Bean Clock authClock() { return Clock.systemUTC(); } }
    ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(ImageConfiguration.class,Dependencies.class);
    String[] active={"mongle.image.openai.enabled=true","mongle.image.openai.api-key=fake-key",
            "mongle.image.openai.model=gpt-image-1-mini","mongle.image.openai.size=1024x1024","mongle.image.openai.quality=low",
            "mongle.image.styles[0]=test-style","mongle.image.moods[0]=test-mood",
            "mongle.image.openai.style-prompts[test-style]=watercolor","mongle.image.openai.mood-prompts[test-mood]=calm",
            "mongle.image.execution.max-concurrent-calls=4"};

    @Test void barePropertiesDisableProviderAndSupplyTheConfirmedStylePrompts() {
        runner.run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(ImageGenerator.class);
            assertThat(context.getBean(ImageGenerator.class).available()).isFalse();
            var properties=context.getBean(OpenAiImageProperties.class);
            assertThat(properties.model()).isEmpty(); assertThat(properties.size()).isEmpty(); assertThat(properties.quality()).isEmpty();
            assertThat(properties.stylePrompts()).containsOnlyKeys("surrealism", "geometric-cubism", "expressive-brushwork", "modern-pop-art");
            assertThat(properties.moodPrompts()).isEmpty();
        });
    }
    @Test void explicitValidConfigurationCreatesProviderWithoutSendingRequestsAndClosesIt() {
        var reference=new java.util.concurrent.atomic.AtomicReference<ImageGenerator>();
        runner.withPropertyValues(active).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(ImageGenerator.class);
            var generator=context.getBean(ImageGenerator.class); reference.set(generator);
            assertThat(generator).isInstanceOf(OpenAiImageGenerator.class); assertThat(generator.available()).isTrue();
            assertThat(context.getBean(ImageAssetStore.class).available()).isFalse();
        });
        assertThat(reference.get().available()).isFalse();
    }
    @Test void customGeneratorIsNotReplaced() {
        var custom=mock(ImageGenerator.class);
        runner.withPropertyValues(active).withBean(ImageGenerator.class,()->custom).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(ImageGenerator.class);
            assertThat(context.getBean(ImageGenerator.class)).isSameAs(custom); verifyNoInteractions(custom);
        });
    }
    @ParameterizedTest @ValueSource(strings={"model=","model=gpt-image-1","model=dall-e-3","size=512x512","quality=hd",
            "quality=max","output-format=webp","api-key=","connect-timeout=0s","total-timeout=91s","response-threads=0","response-threads=9"})
    void missingRetiredOrUnsupportedSettingsFailBeforeAnyCall(String input) {
        String[] entry=input.split("=",2);
        runner.withPropertyValues(active).withPropertyValues("mongle.image.openai."+entry[0]+"="+entry[1])
                .run(context->assertThat(context).hasFailed());
    }
    @Test void optionMappingMismatchAndOversubscribedStorageBudgetFailActivation() {
        runner.withPropertyValues(active).withPropertyValues("mongle.image.styles[1]=unmapped")
                .run(context->assertThat(context).hasFailed());
        runner.withPropertyValues(active).withPropertyValues("mongle.image.openai.style-prompts[extra]=extra")
                .run(context->assertThat(context).hasFailed());
        runner.withPropertyValues(active).withPropertyValues("mongle.image.execution.max-concurrent-calls=16")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void newerModelQualityIsExplicitAndPropertiesNeverPrintCredentialsOrPromptMappings() {
        var values=new HashMap<String,String>();
        for(String entry:active) { var pair=entry.split("=",2); if(pair[0].startsWith("mongle.image.openai.")) values.put(pair[0],pair[1]); }
        values.put("mongle.image.openai.model","gpt-image-2.5-sunburst-2026-09-08");
        values.put("mongle.image.openai.quality","max");
        var properties=new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("mongle.image.openai",Bindable.of(OpenAiImageProperties.class));
        assertThat(properties.quality()).isEqualTo("max");
        assertThat(properties.toString()).doesNotContain("fake-key","watercolor","calm");
    }
}
