package com.mongle.backend.domain.dream.entity;

import java.text.Normalizer;
import java.util.Locale;

public final class DreamElementNames {
    private DreamElementNames() {}

    public static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("[\\p{Z}\\s]+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
