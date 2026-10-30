package com.mongle.backend.domain.dream.analysis;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.YearMonth;
import java.util.List;

@Schema(name = "MonthlyRecurringElementsResponse")
public record RecurringElementsResponse(
        YearMonth month, long analyzedDreamCount, List<Element> places, List<Element> characters) {
    public RecurringElementsResponse {
        places = List.copyOf(places);
        characters = List.copyOf(characters);
    }

    @Schema(name = "MonthlyRecurringElement")
    public record Element(String name, long dreamCount) {}
}
