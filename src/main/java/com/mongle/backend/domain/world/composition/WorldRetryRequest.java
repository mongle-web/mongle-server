package com.mongle.backend.domain.world.composition;
import jakarta.validation.constraints.*;
public record WorldRetryRequest(@NotNull @PositiveOrZero Long jobVersion) {}
