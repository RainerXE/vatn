package dev.vatn.core;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Minimal, dependency-free 5-field UNIX cron evaluator
 * (minute hour day-of-month month day-of-week, {@code 0=Sunday}).
 *
 * <p>Supports {@code *}, lists ({@code 1,15}), ranges ({@code 1-5}), and step syntax such as
 * star-slash-2 or {@code 0-30/5}. Shared by the standalone {@link dev.vatn.core.VSchedulerImpl} and
 * available to any other component that needs cron-to-time resolution.
 */
public final class CronEvaluator {

    private CronEvaluator() {}

    /** Returns true if {@code cron} parses as a 5-field expression. */
    public static boolean isValid(String cron) {
        return cron != null && !cron.isBlank() && cron.trim().split("\\s+").length == 5;
    }

    /**
     * Returns the next fire time strictly after {@code after} for the given 5-field cron
     * expression, or {@code null} if it cannot be parsed or matches nothing within a year.
     */
    public static LocalDateTime nextFireTime(String cron, LocalDateTime after) {
        if (!isValid(cron)) return null;
        String[] fields = cron.trim().split("\\s+");
        LocalDateTime candidate = after.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
        for (int i = 0; i < 366 * 24 * 60; i++) {
            if (matches(fields, candidate)) return candidate;
            candidate = candidate.plusMinutes(1);
        }
        return null;
    }

    private static boolean matches(String[] f, LocalDateTime dt) {
        return field(f[0], dt.getMinute(), 0, 59)
                && field(f[1], dt.getHour(), 0, 23)
                && field(f[2], dt.getDayOfMonth(), 1, 31)
                && field(f[3], dt.getMonthValue(), 1, 12)
                && field(f[4], dt.getDayOfWeek().getValue() % 7, 0, 6); // 0=Sun
    }

    private static boolean field(String field, int value, int min, int max) {
        if ("*".equals(field)) return true;
        for (String part : field.split(",")) {
            if (part.contains("/")) {
                String[] sp = part.split("/");
                int step = Integer.parseInt(sp[1]);
                int start = "*".equals(sp[0]) ? min : Integer.parseInt(sp[0].contains("-") ? sp[0].split("-")[0] : sp[0]);
                if (value >= start && (value - start) % step == 0) return true;
            } else if (part.contains("-")) {
                String[] rp = part.split("-");
                if (value >= Integer.parseInt(rp[0]) && value <= Integer.parseInt(rp[1])) return true;
            } else if (Integer.parseInt(part) == value) {
                return true;
            }
        }
        return false;
    }
}
