package com.mongle.backend.domain.dream.image.openai;

import java.util.Map;

/** 참고 작가의 이름이나 대표 사물을 주입하지 않고 네 화풍의 표현 기법만 제공한다. */
final class ImageStylePrompts {
    private ImageStylePrompts() {}

    static Map<String, String> defaults() {
        return Map.of(
                "surrealism", "Surrealism, dreamlike landscapes, unexpected spatial relationships and subtly distorted forms, philosophical and mysterious atmosphere. Preserve the story's subjects and events.",
                "geometric-cubism", "Cubism and geometric abstraction, fragmented shapes, multiview perspective, bold lines and planes, vibrant primary colors and rhythmic composition. Keep the story's subjects recognizable.",
                "expressive-brushwork", "Expressive painting, swirling brushstrokes, thick impasto, strong emotional color contrasts, raw spontaneous marks and energetic drawing. Preserve the story's subjects and events.",
                "modern-pop-art", "Contemporary illustration and pop art, flat vibrant colors, clean precise line art, imaginative comic composition and pastel accents. Preserve the story's subjects and events.");
    }
}
