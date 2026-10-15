package com.lumen.cs.calculator;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SlaCalculatorTest {
    private final SlaCalculator calc = new SlaCalculator();

    private TenantCalendar calMonFri9to18() {
        return new TenantCalendar(
            Map.of(1, List.of(new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                   2, List.of(new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                   3, List.of(new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                   4, List.of(new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                   5, List.of(new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)))),
            Set.of()  // no holidays
        );
    }

    private LocalDateTime dt(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min);
    }

    @Test void fullWorkday_returns540min() {
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 12, 9, 0), dt(2026, 10, 12, 18, 0));
        assertThat(m).isEqualTo(540);  // 9 hours * 60
    }

    @Test void partialWorkday_returns120min() {
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 12, 10, 0), dt(2026, 10, 12, 12, 0));
        assertThat(m).isEqualTo(120);
    }

    @Test void acrossMidnight_skipsNight() {
        // Mon 22:00 -> Tue 10:00 = 0 (Mon closed) + 60 (Tue 9-10)
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 12, 22, 0), dt(2026, 10, 13, 10, 0));
        assertThat(m).isEqualTo(60);
    }

    @Test void holiday_isSkipped() {
        TenantCalendar c = new TenantCalendar(
            Map.of(1, List.of(new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)))),
            Set.of(LocalDate.of(2026, 10, 12))  // Monday is holiday
        );
        long m = calc.businessMinutes(c, dt(2026, 10, 12, 9, 0), dt(2026, 10, 12, 18, 0));
        assertThat(m).isEqualTo(0);
    }

    @Test void weekend_isSkipped() {
        // Sat 9:00 -> Sat 18:00 = 0
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 10, 9, 0), dt(2026, 10, 10, 18, 0));
        assertThat(m).isEqualTo(0);
    }

    @Test void multipleRangesPerDay() {
        TenantCalendar c = new TenantCalendar(
            Map.of(1, List.of(
                new TenantCalendar.TimeRange(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                new TenantCalendar.TimeRange(LocalTime.of(13, 0), LocalTime.of(18, 0))
            )),
            Set.of()
        );
        long m = calc.businessMinutes(c, dt(2026, 10, 12, 9, 0), dt(2026, 10, 12, 18, 0));
        assertThat(m).isEqualTo(480);  // 180 + 300
    }

    @Test void acrossYearBoundary() {
        // 2026-12-31 (Thu) 16:00 -> 2027-01-04 (Mon) 11:00
        // Thu 16-18 = 120, Fri 9-18 = 540, Sat 0, Sun 0, Mon 9-11 = 120
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 12, 31, 16, 0), dt(2027, 1, 4, 11, 0));
        assertThat(m).isEqualTo(120 + 540 + 120);
    }

    @Test void toEqualsFrom_returnsZero() {
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 12, 10, 0), dt(2026, 10, 12, 10, 0));
        assertThat(m).isEqualTo(0);
    }

    @Test void toBeforeFrom_returnsZero() {
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 12, 11, 0), dt(2026, 10, 12, 10, 0));
        assertThat(m).isEqualTo(0);
    }

    @Test void thirtyDayRange() {
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 10, 1, 9, 0), dt(2026, 10, 31, 9, 0));
        // 22 workdays * 540 = 11880
        assertThat(m).isEqualTo(22 * 540);
    }

    @Test void sundayOnDstTransitionDate_isSkipped() {
        // 2026-03-08 是 Sunday (weekday 7), 不在 Mon-Fri 业务时间范围 → 0
        // (顺便覆盖 US DST 切换日 2026-03-08 02:00→03:00, 验证日历计算不依赖 DST)
        long m = calc.businessMinutes(calMonFri9to18(), dt(2026, 3, 8, 2, 30), dt(2026, 3, 8, 4, 30));
        assertThat(m).isEqualTo(0);
    }

    @Test void emptyCalendar_returnsZero() {
        TenantCalendar c = new TenantCalendar(Map.of(), Set.of());
        long m = calc.businessMinutes(c, dt(2026, 10, 12, 9, 0), dt(2026, 10, 12, 18, 0));
        assertThat(m).isEqualTo(0);
    }
}
