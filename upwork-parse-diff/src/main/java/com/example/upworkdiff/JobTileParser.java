package com.example.upworkdiff;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсит HTML-дамп выдачи Upwork в JobTile.
 * Lenient: битый HTML глотает jsoup; проблемный тайл пропускается со счётчиком ошибок.
 */
public final class JobTileParser {

    /** Результат парсинга одного файла. */
    public record FileResult(
            List<JobTile> tiles,
            int uidMisses,   // тайлы, из которых не вытащился uid
            int errors,      // тайлы, упавшие с исключением
            boolean looksLikeUpwork
    ) {}

    private static final Pattern MONEY =
            Pattern.compile("(\\d[\\d\\s\\u00A0.,]*)([kK]?)");
    private static final Pattern RANGE_DASH = Pattern.compile("(\\d+)\\s*[-\\u2013\\u2014]\\s*(\\d+)");
    private static final Pattern RANGE_TO = Pattern.compile("(\\d+)\\s+to\\s+(\\d+)");
    private static final Pattern RELATIVE =
            Pattern.compile("(\\d+)\\s*(minutes?|hours?|days?|weeks?|months?|минут[уы]?|час(а|ов)?|дн(я|ей)?|недел[иь]?|месяц(а|ев)?)\\s+назад");

    private final long nowMillis;

    public JobTileParser(long nowMillis) {
        this.nowMillis = nowMillis;
    }

    public FileResult parse(File file, String query) throws IOException {
        Document doc = Jsoup.parse(file, "UTF-8");
        boolean looksLike = looksLikeUpwork(doc);
        List<JobTile> tiles = new ArrayList<>();
        int uidMisses = 0;
        int errors = 0;
        for (Element tileEl : doc.select(UpworkSelectors.TILE)) {
            try {
                JobTile tile = parseTile(tileEl, query);
                if (tile == null) {
                    uidMisses++;
                } else {
                    tiles.add(tile);
                }
            } catch (RuntimeException e) {
                errors++;
            }
        }
        return new FileResult(tiles, uidMisses, errors, looksLike);
    }

    /** Та же логика для selftest на фикстуре, уже загруженной в строку. */
    public FileResult parseString(String html, String query) {
        Document doc = Jsoup.parse(html);
        boolean looksLike = looksLikeUpwork(doc);
        List<JobTile> tiles = new ArrayList<>();
        int uidMisses = 0;
        int errors = 0;
        for (Element tileEl : doc.select(UpworkSelectors.TILE)) {
            try {
                JobTile tile = parseTile(tileEl, query);
                if (tile == null) {
                    uidMisses++;
                } else {
                    tiles.add(tile);
                }
            } catch (RuntimeException e) {
                errors++;
            }
        }
        return new FileResult(tiles, uidMisses, errors, looksLike);
    }

    public boolean looksLikeUpwork(Document doc) {
        String html = doc.html();
        for (String marker : UpworkSelectors.PAGE_MARKERS) {
            if (html.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** null — uid не извлёкся, тайл надо считать пропущенным. */
    private JobTile parseTile(Element tileEl, String query) {
        // --- ссылка и uid: источник истины — href ---
        Element link = first(tileEl, UpworkSelectors.TITLE_LINK);
        String href = (link != null) ? link.attr("href").trim() : null;

        if (href == null || href.isEmpty()) {
            // запасной вариант: любой href с ~uid внутри тайла
            for (Element a : tileEl.select("a[href]")) {
                if (UpworkSelectors.UID_IN_URL.matcher(a.attr("href")).find()) {
                    href = a.attr("href").trim();
                    link = a;
                    break;
                }
            }
        }
        if (href == null || href.isEmpty()) {
            return null;
        }
        Matcher uidMatcher = UpworkSelectors.UID_IN_URL.matcher(href);
        if (!uidMatcher.find()) {
            return null;
        }
        String uid = uidMatcher.group();

        String url = href.startsWith("/")
                ? UpworkSelectors.SITE_BASE + href
                : href;

        String title = link != null ? clean(link.text()) : "";

        // --- тип и бюджет ---
        String type = "";
        String budget = "";
        Element typeEl = first(tileEl, UpworkSelectors.TYPE_LABEL);
        if (typeEl != null) {
            String typeText = clean(typeEl.text());
            if (startsWithIgnoreCase(typeText, UpworkSelectors.TYPE_FIXED_EN)
                    || startsWithIgnoreCase(typeText, UpworkSelectors.TYPE_FIXED_RU)) {
                type = "fixed";
                Element budgetEl = first(tileEl, UpworkSelectors.FIXED_BUDGET_LI);
                if (budgetEl != null) {
                    String bt = clean(budgetEl.text());
                    bt = stripPrefix(bt, UpworkSelectors.BUDGET_PREFIX_EN);
                    bt = stripPrefix(bt, UpworkSelectors.BUDGET_PREFIX_RU);
                    budget = firstMoney(bt);
                }
            } else if (startsWithIgnoreCase(typeText, UpworkSelectors.TYPE_HOURLY_EN)
                    || startsWithIgnoreCase(typeText, UpworkSelectors.TYPE_HOURLY_RU)) {
                type = "hourly";
                String rest = typeText;
                int colon = rest.indexOf(':');
                if (colon >= 0) {
                    rest = rest.substring(colon + 1);
                }
                List<String> amounts = allMoney(rest);
                if (amounts.size() >= 2) {
                    budget = amounts.get(0) + "-" + amounts.get(1);
                } else if (amounts.size() == 1) {
                    budget = amounts.get(0);
                }
            }
        }

        // --- proposals ---
        String proposals = "";
        Element propEl = first(tileEl, UpworkSelectors.PROPOSALS);
        if (propEl != null) {
            proposals = parseProposals(clean(propEl.text()));
        }

        // --- rating ---
        String rating = "";
        Matcher ratingMatcher = UpworkSelectors.RATING_TEXT.matcher(tileEl.text());
        if (ratingMatcher.find()) {
            String r = ratingMatcher.group(1).replace(',', '.');
            // «Rating is 0 out of 5» = «No feedback yet» — считаем отсутствующим
            if (!"0".equals(r) && !"0.0".equals(r)) {
                rating = r;
            }
        }

        // --- spent ---
        String spent = "";
        Element spentEl = first(tileEl, UpworkSelectors.SPENT_LI);
        if (spentEl != null) {
            spent = firstMoney(clean(spentEl.text()));
        }

        // --- posted ---
        long postedMillis = 0;
        String postedText = "";
        Element postedEl = first(tileEl, UpworkSelectors.PUBLISHED);
        if (postedEl != null) {
            postedText = clean(postedEl.text());
            // в новом формате рядом с датой через «·» идёт proposals — отрезаем
            int sep = postedText.indexOf('\u00B7');
            if (sep >= 0) {
                postedText = postedText.substring(0, sep).trim();
            }
            postedMillis = parsePosted(postedText, nowMillis);
        }

        return new JobTile(uid, title, url, type, budget, proposals, rating, spent,
                postedMillis, postedText, query);
    }

    private static Element first(Element root, String css) {
        org.jsoup.select.Elements found = root.select(css);
        return found.isEmpty() ? null : found.first();
    }

    static String parseProposals(String text) {
        String t = text.toLowerCase();
        if (t.contains(UpworkSelectors.PROPOSALS_FEWER_EN.toLowerCase())
                || t.contains(UpworkSelectors.PROPOSALS_FEWER_RU.toLowerCase())) {
            return "0-4";
        }
        Matcher m = RANGE_DASH.matcher(text);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2);
        }
        m = RANGE_TO.matcher(text);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2);
        }
        m = Pattern.compile("(\\d+)\\s+до\\s+(\\d+)").matcher(text);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2);
        }
        m = Pattern.compile("(\\d+)").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    static long parsePosted(String text, long now) {
        String t = text.toLowerCase()
                .replace("опубликовано", "")
                .replace("posted", "")
                .trim();
        if (t.startsWith("вчера") || t.startsWith("yesterday")) {
            return now - 86_400_000L;
        }
        Matcher m = RELATIVE.matcher(t);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2);
            long multiplier = switch (unit) {
                case "minute", "minutes", "минуту", "минуты", "минут" -> 60_000L;
                case "hour", "hours", "час", "часа", "часов" -> 3_600_000L;
                case "day", "days", "дня", "дней" -> 86_400_000L;
                case "week", "weeks", "неделю", "недели", "недель" -> 7 * 86_400_000L;
                default -> 30 * 86_400_000L; // month(s), месяц...
            };
            return now - n * multiplier;
        }
        if (t.contains("last month") || t.contains("прошлом месяце")) {
            return now - 30L * 86_400_000L;
        }
        if (t.contains("last week") || t.contains("прошлой неделе")) {
            return now - 7L * 86_400_000L;
        }
        return 0; // не распознали — сортируется как самое старое
    }

    /** Первое денежное число в тексте, развёрнутое (10k -> 10000), без дробной части если целое. */
    static String firstMoney(String text) {
        List<String> all = allMoney(text);
        return all.isEmpty() ? "" : all.get(0);
    }

    static List<String> allMoney(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            String raw = m.group(1).trim();
            if (raw.isEmpty()) {
                continue;
            }
            String normalized = raw
                    .replace("\u00A0", "")
                    .replace(" ", "")
                    .replace(",", ".");
            // если и точка, и запятая были — убираем тысячные разделители
            if (raw.contains(".") && raw.contains(",")) {
                normalized = raw.replace("\u00A0", "").replace(" ", "");
                char lastSep = raw.charAt(Math.max(raw.lastIndexOf('.'), raw.lastIndexOf(',')));
                char thousandsSep = lastSep == '.' ? ',' : '.';
                normalized = normalized.replace(String.valueOf(thousandsSep), "");
            }
            BigDecimal value;
            try {
                value = new BigDecimal(normalized);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!m.group(2).isEmpty()) {
                value = value.multiply(BigDecimal.valueOf(1000));
            }
            out.add(value.stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private static String stripPrefix(String text, String prefix) {
        return startsWithIgnoreCase(text, prefix) ? text.substring(prefix.length()).trim() : text;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    static String clean(String s) {
        return s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}
