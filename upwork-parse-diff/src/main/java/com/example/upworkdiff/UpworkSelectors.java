package com.example.upworkdiff;

import java.util.regex.Pattern;

/**
 * ЕДИНСТВЕННАЯ точка привязки парсера к разметке Upwork.
 *
 * Если Upwork меняет разметку — правится ТОЛЬКО этот класс.
 * CSS-классы (air3-*) не используем как якоря: Upwork меняет их постоянно.
 * Якоря — data-test / data-ev-* атрибуты и видимые тексты, они живут дольше.
 *
 * Замечания по живым дампам (проверено на 2026-08-14 и 2026-09-12):
 *  - uid есть в data-ev-job-uid тайла И в виде ~02... в href; берём из href
 *    (виден даже при частичной смене data-атрибутов), сверяем с data-атрибутом;
 *  - локаль страницы бывает EN и RU: тексты-якоря продублированы на оба языка;
 *  - у части тайлов (EN, старый формат) proposals — отдельный <li>,
 *    у части (RU, новый формат) proposals — инлайн рядом с датой в заголовке;
 *  - почасовая вилка живёт в job-type-label ("Hourly: $10.00 - $20.00"),
 *    отдельного li с вилкой нет.
 */
public final class UpworkSelectors {

    /** Версия привязки к разметке. Печатается в шапке каждого прогона. */
    public static final int MARKUP_VERSION = 1;

    private UpworkSelectors() {}

    // ---------- Маркеры «это страница поиска Upwork» (стартовая самопроверка файла) ----------
    /** Любой из этих маркеров в файле => файл похож на выдачу Upwork. */
    public static final String[] PAGE_MARKERS = {
            "upwork.com",
            "data-ev-job-uid",
            "air3-",
    };

    // ---------- Тайл ----------
    /** Корневой элемент тайла заказа. */
    public static final String TILE = "article[data-test=JobTile]";
    /** data-атрибут с uid (новый формат; подтверждение, не основной источник). */
    public static final String TILE_UID_ATTR = "data-ev-job-uid";
    /** Ссылка на заказ; источник истины для uid и url. */
    public static final String TITLE_LINK = "a[data-test~=job-tile-title-link]";
    /** Uid в href выглядит как ~022098064014889823053. */
    public static final Pattern UID_IN_URL = Pattern.compile("~\\d{10,}");
    /** База для склейки относительных ссылок. */
    public static final String SITE_BASE = "https://www.upwork.com";

    // ---------- Текстовые якоря полей (data-test атрибуты) ----------
    public static final String PUBLISHED = "[data-test=job-pubilshed-date]";
    public static final String TYPE_LABEL = "[data-test=job-type-label]";
    public static final String FIXED_BUDGET_LI = "[data-test=is-fixed-price]";
    public static final String RATING_BLOCK = "[data-test=total-feedback]";
    public static final String SPENT_LI = "[data-test=total-spent]";
    public static final String PROPOSALS = "[data-test=proposals-tier]";

    // ---------- Текстовые якоря (видимые тексты, EN + RU) ----------
    /** Рейтинг: «Rating is 4.9 out of 5». */
    public static final Pattern RATING_TEXT =
            Pattern.compile("Rating is (\\d+(?:[.,]\\d+)?) out of 5");
    /** Тип fixed. */
    public static final String TYPE_FIXED_EN = "Fixed price";
    public static final String TYPE_FIXED_RU = "Фиксированная цена";
    /** Тип hourly (префикс записи job-type-label). */
    public static final String TYPE_HOURLY_EN = "Hourly:";
    public static final String TYPE_HOURLY_RU = "Почасовая оплата:";
    /** Бюджет fixed: после этого текста в strong идёт сумма. */
    public static final String BUDGET_PREFIX_EN = "Est. budget:";
    public static final String BUDGET_PREFIX_RU = "Бюджет:";
    /** proposals: «Fewer than 5» / «менее 5» => 0-4. */
    public static final String PROPOSALS_FEWER_EN = "Fewer than 5";
    public static final String PROPOSALS_FEWER_RU = "менее 5";

    /** Лимит предупреждения «файл похож на выдачу, но тайлов нет». */
    public static final long BIG_FILE_BYTES = 500 * 1024;
    /** Доля тайлов без uid, выше которой — предупреждение о дрейфе разметки. */
    public static final double UID_MISS_WARN_RATIO = 0.05;
    public static final int UID_MISS_WARN_MIN_TILES = 100;
}
