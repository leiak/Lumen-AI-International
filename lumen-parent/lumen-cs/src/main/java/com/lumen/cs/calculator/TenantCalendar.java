package com.lumen.cs.calculator;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record TenantCalendar(
    Map<Integer, List<TimeRange>> weekdayRanges,  // key 1..7 (Mon..Sun)
    Set<LocalDate> holidays
) {
    public record TimeRange(LocalTime start, LocalTime end) {}
}
