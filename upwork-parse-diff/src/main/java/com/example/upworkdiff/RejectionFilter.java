package com.example.upworkdiff;

import java.util.ArrayList;
import java.util.List;

/**
 * Жёсткий автофильтр новых заказов: правило не пройдено — заказ идёт в rejected.md
 * и не попадает в new.md. Отсеянные считаются обработанными (остаются в seen.bin).
 *
 * Пустые поля (кроме оплаты) не отсеивают — уходят в new.md на ручную проверку.
 * Нераспарсившееся число трактуем как пустое поле (тоже ручная проверка).
 */
public final class RejectionFilter {

    private static final double MAX_PROPOSALS_UPPER = 14;
    private static final double MIN_HOURLY_RATE = 15;
    private static final double MIN_FIXED_BUDGET = 50;
    private static final double MIN_RATING = 4.5;

    private RejectionFilter() {}

    /** Пустой список — заказ прошёл; иначе причины отсева (в порядке проверки). */
    public static List<String> rejectReasons(JobTile t) {
        List<String> reasons = new ArrayList<>();
        if (!t.paymentVerified()) {
            reasons.add("оплата не подтверждена");
        }
        if (!t.proposals().isEmpty() && upperBound(t.proposals()) > MAX_PROPOSALS_UPPER) {
            reasons.add("предложений > 14");
        }
        if ("hourly".equals(t.type()) && !t.budget().isEmpty()
                && lowerBound(t.budget()) < MIN_HOURLY_RATE) {
            reasons.add("ставка < 15");
        }
        if ("fixed".equals(t.type()) && !t.budget().isEmpty()
                && lowerBound(t.budget()) < MIN_FIXED_BUDGET) {
            reasons.add("бюджет < 50");
        }
        if (!t.rating().isEmpty() && parseDouble(t.rating()) < MIN_RATING) {
            reasons.add("рейтинг < 4.5");
        }
        return reasons;
    }

    public static boolean isRejected(JobTile t) {
        return !rejectReasons(t).isEmpty();
    }

    /** Верхняя граница "a-b" (или единственное число); NaN — не распарсилось. */
    private static double upperBound(String s) {
        int dash = lastDash(s);
        return parseDouble(dash >= 0 ? s.substring(dash + 1) : s);
    }

    /** Нижняя граница "a-b" (или единственное число); NaN — не распарсилось. */
    private static double lowerBound(String s) {
        int dash = lastDash(s);
        return parseDouble(dash >= 0 ? s.substring(0, dash) : s);
    }

    private static int lastDash(String s) {
        return Math.max(s.lastIndexOf('-'), s.lastIndexOf('\u2013'));
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
