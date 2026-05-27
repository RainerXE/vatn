package dev.vatn.core.workflow;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VDagSchedulerImpl's cron parser (nextFireTime / fieldMatches).
 * No I/O, no DB, pure logic — runs in milliseconds.
 */
class VDagSchedulerTest {

    private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    @Test
    void everyMinute_firesOneMinuteLater() {
        LocalDateTime after = at(2026, 5, 23, 10, 0);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("* * * * *", after);
        assertNotNull(next);
        assertEquals(at(2026, 5, 23, 10, 1), next);
    }

    @Test
    void specificMinute_firesAtCorrectTime() {
        LocalDateTime after = at(2026, 5, 23, 10, 0);
        // "30 * * * *" = at minute 30 of every hour
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("30 * * * *", after);
        assertNotNull(next);
        assertEquals(30, next.getMinute());
        assertEquals(10, next.getHour());
    }

    @Test
    void hourlyAt30_wrapsToNextHour() {
        LocalDateTime after = at(2026, 5, 23, 10, 35);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("30 * * * *", after);
        assertNotNull(next);
        assertEquals(11, next.getHour());
        assertEquals(30, next.getMinute());
    }

    @Test
    void dailyAtMidnight_firesNextDay() {
        LocalDateTime after = at(2026, 5, 23, 0, 1);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("0 0 * * *", after);
        assertNotNull(next);
        assertEquals(24, next.getDayOfMonth());
        assertEquals(0, next.getHour());
        assertEquals(0, next.getMinute());
    }

    @Test
    void stepExpression_every5Minutes() {
        LocalDateTime after = at(2026, 5, 23, 10, 3);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("*/5 * * * *", after);
        assertNotNull(next);
        assertEquals(5, next.getMinute());   // next multiple of 5 after 3
        assertEquals(10, next.getHour());
    }

    @Test
    void rangeExpression_9to17() {
        LocalDateTime after = at(2026, 5, 23, 8, 59);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("0 9-17 * * *", after);
        assertNotNull(next);
        assertEquals(9, next.getHour());
        assertEquals(0, next.getMinute());
    }

    @Test
    void commaList_fireAtSpecificMinutes() {
        LocalDateTime after = at(2026, 5, 23, 10, 0);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("15,45 * * * *", after);
        assertNotNull(next);
        assertEquals(15, next.getMinute());
    }

    @Test
    void specificDayOfMonth_correctDay() {
        LocalDateTime after = at(2026, 5, 1, 0, 0);
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("0 9 15 * *", after);
        assertNotNull(next);
        assertEquals(15, next.getDayOfMonth());
        assertEquals(9, next.getHour());
    }

    @Test
    void invalidCron_returnsNull() {
        assertNull(VDagSchedulerImpl.nextFireTime("invalid", LocalDateTime.now()));
        assertNull(VDagSchedulerImpl.nextFireTime("* * * *", LocalDateTime.now())); // 4-field
        assertNull(VDagSchedulerImpl.nextFireTime(null, LocalDateTime.now()));
        assertNull(VDagSchedulerImpl.nextFireTime("", LocalDateTime.now()));
    }

    @Test
    void weekdayFilter_onlySunday() {
        // "0 9 * * 0" = 09:00 on Sunday (0 = Sunday in UNIX cron)
        LocalDateTime after = at(2026, 5, 18, 9, 1); // 2026-05-18 is a Monday
        LocalDateTime next = VDagSchedulerImpl.nextFireTime("0 9 * * 0", after);
        assertNotNull(next);
        assertEquals(0, next.getDayOfWeek().getValue() % 7); // Sunday = 0
    }
}
