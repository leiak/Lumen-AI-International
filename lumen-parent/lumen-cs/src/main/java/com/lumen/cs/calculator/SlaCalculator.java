package com.lumen.cs.calculator;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;

@Component
public class SlaCalculator {

    /**
     * 计算 [from, to] 之间的「业务分钟数」。
     * 算法：按天迭代；首末日按时间戳比例算；节假日/非工作日跳过。
     */
    public long businessMinutes(TenantCalendar cal, LocalDateTime from, LocalDateTime to) {
        if (to.isBefore(from) || to.isEqual(from)) return 0;
        long totalMinutes = 0;
        LocalDate cursor = from.toLocalDate();
        LocalDate endDate = to.toLocalDate();
        while (!cursor.isAfter(endDate)) {
            // Skip holidays and non-work weekdays
            int weekday = cursor.getDayOfWeek().getValue();
            List<TenantCalendar.TimeRange> ranges = cal.weekdayRanges().get(weekday);
            if (ranges == null || cal.holidays().contains(cursor)) {
                cursor = cursor.plusDays(1);
                continue;
            }
            for (TenantCalendar.TimeRange r : ranges) {
                LocalDateTime segStart = maxOf(from, LocalDateTime.of(cursor, r.start()));
                LocalDateTime segEnd = minOf(to, LocalDateTime.of(cursor, r.end()));
                if (segEnd.isAfter(segStart)) {
                    totalMinutes += Duration.between(segStart, segEnd).toMinutes();
                }
            }
            cursor = cursor.plusDays(1);
        }
        return totalMinutes;
    }

    private LocalDateTime maxOf(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalDateTime minOf(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
