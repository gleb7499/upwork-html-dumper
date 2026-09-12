package com.example.upworkdiff;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * upwork-parse-diff — офлайн-дифф поисковой выдачи Upwork.
 *
 * Прочитать папку с HTML-дампами, выдать ТОЛЬКО НОВЫЕ заказы (uid которых ещё
 * не было в seen.bin) в new.md рядом с дампами.
 *
 * Exit codes: 0 — ок; 1 — неверные аргументы/папка; 2 — критическая ошибка записи;
 *             3 — структурная поломка парсера (разметка Upwork изменилась).
 */
public final class Main {

    private static final String DEFAULT_DB_DIR = ".upwork-parse-diff";
    private static final String DEFAULT_DB_NAME = "seen.bin";
    private static final String NEW_MD = "new.md";
    private static final String REJECTED_MD = "rejected.md";

    public static void main(String[] args) {
        // Читаемый русский текст в консоли Windows: JEP 400 по умолчанию шлёт UTF-8,
        // а консоль ждёт свою кодовую страницу (sun.stdout.encoding). Файлы — строго UTF-8.
        String consoleEnc = System.getProperty("sun.stdout.encoding");
        if (consoleEnc != null) {
            try {
                System.setOut(new java.io.PrintStream(
                        new java.io.FileOutputStream(java.io.FileDescriptor.out), true, consoleEnc));
                System.setErr(new java.io.PrintStream(
                        new java.io.FileOutputStream(java.io.FileDescriptor.err), true, consoleEnc));
            } catch (java.io.UnsupportedEncodingException ignored) {
                // остаёмся на UTF-8
            }
        }
        int code;
        try {
            code = new Main().run(args);
        } catch (Exception e) {
            System.err.println("CRITICAL ERROR: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    private int run(String[] args) throws Exception {
        // ---- разбор аргументов ----
        String folderArg = null;
        String dbArg = null;
        long ttlDays = 90;
        boolean selftest = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--selftest" -> selftest = true;
                case "--ttl-days" -> {
                    if (i + 1 >= args.length) {
                        return usage("после --ttl-days нужно число дней");
                    }
                    try {
                        ttlDays = Long.parseLong(args[++i]);
                    } catch (NumberFormatException e) {
                        return usage("--ttl-days: не число: " + args[i]);
                    }
                    if (ttlDays < 0) {
                        return usage("--ttl-days не может быть отрицательным");
                    }
                }
                case "--db" -> {
                    if (i + 1 >= args.length) {
                        return usage("после --db нужен путь к seen.bin");
                    }
                    dbArg = unquote(args[++i]);
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        return usage("неизвестный параметр: " + args[i]);
                    }
                    if (folderArg != null) {
                        return usage("указано две папки: " + folderArg + " и " + args[i]);
                    }
                    folderArg = unquote(args[i]);
                }
            }
        }

        if (selftest) {
            return selftest();
        }
        if (folderArg == null) {
            return usage("не указана папка с дампами");
        }

        Path folder = Path.of(folderArg);
        if (!Files.isDirectory(folder)) {
            System.err.println("ERROR: папка не существует: " + folderArg);
            return 1;
        }

        Path db = (dbArg != null)
                ? Path.of(dbArg)
                : Path.of(System.getProperty("user.home"), DEFAULT_DB_DIR, DEFAULT_DB_NAME);

        long now = System.currentTimeMillis();
        JobTileParser parser = new JobTileParser(now);

        // ---- 1. загрузить seen.bin ----
        Map<String, Long> seen = new HashMap<>();
        boolean seenLoaded = false;
        try {
            seen = SeenStore.load(db);
            seenLoaded = true;
        } catch (SeenStore.CorruptFileException e) {
            System.err.println("WARNING: seen.bin повреждён (" + e.getMessage()
                    + ") — стартуем с пустой базы, битый файл сохранён как .corrupt");
            try {
                SeenStore.quarantineCorrupt(db);
            } catch (IOException ignore) {
                // не смогли переименовать — идём дальше с пустой базой
            }
        }

        // ---- 2. собрать html-файлы (рекурсивно) ----
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".html"))
                    .sorted()
                    .forEach(files::add);
        }

        // ---- 3. парсинг ----
        List<JobTile> allTiles = new ArrayList<>();
        int uidMisses = 0;
        int errors = 0;
        List<String> warnings = new ArrayList<>();
        for (Path file : files) {
            String query = stripExtension(file.getFileName().toString());
            JobTileParser.FileResult result;
            try {
                result = parser.parse(file.toFile(), query);
            } catch (IOException e) {
                warnings.add("не удалось прочитать файл (пропущен): " + file.getFileName());
                continue;
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                size = 0;
            }
            if (result.tiles().isEmpty() && size > UpworkSelectors.BIG_FILE_BYTES) {
                warnings.add("файл >500 КБ без единого тайла: " + file.getFileName()
                        + " — возможно, изменилась разметка или это не выдача Upwork");
            }
            if (!result.looksLikeUpwork() && size > UpworkSelectors.BIG_FILE_BYTES) {
                warnings.add("файл >500 КБ не похож на выдачу Upwork: " + file.getFileName());
            }
            allTiles.addAll(result.tiles());
            uidMisses += result.uidMisses();
            errors += result.errors();
        }

        // ---- 4. структурная поломка: файлы есть, а тайлов 0 ----
        if (!files.isEmpty() && allTiles.isEmpty()) {
            String message = "STRUCTURE BROKEN — вероятно, Upwork сменил разметку, парсер требует починки";
            System.err.println(message);
            warnings.add(0, message);
            try {
                writeInvalidNewMd(folder);
            } catch (IOException e) {
                System.err.println("CRITICAL ERROR: не удалось записать new.md: " + e.getMessage());
                return 2;
            }
            printSummary(folder, db, ttlDays, files.size(), 0, 0, 0, 0, uidMisses, errors, warnings);
            return 3; // seen.bin намеренно НЕ трогаем
        }

        // ---- 5. дедупликация ----
        int fresh = 0;
        int repeated = 0;
        List<JobTile> newTiles = new ArrayList<>();
        for (JobTile tile : allTiles) {
            Long prev = seen.get(tile.uid());
            if (prev != null) {
                repeated++;
                seen.put(tile.uid(), now); // обновить lastSeen
            } else {
                fresh++;
                seen.put(tile.uid(), now);
                newTiles.add(tile);
            }
        }

        // ---- 5.5 автофильтр по жёстким правилам (только среди новых; в seen.bin
        //         отсеянные уже записаны — они обработаны, просто не попадают в new.md) ----
        List<JobTile> acceptedTiles = new ArrayList<>();
        List<JobTile> rejectedTiles = new ArrayList<>();
        for (JobTile tile : newTiles) {
            if (RejectionFilter.isRejected(tile)) {
                rejectedTiles.add(tile);
            } else {
                acceptedTiles.add(tile);
            }
        }

        // ---- 6. seen.bin с прунингом TTL (атомарно) ----
        try {
            SeenStore.save(db, seen, ttlDays, now);
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать seen.bin: " + e.getMessage());
            return 2;
        }

        // ---- 7. new.md (перезаписывается всегда) ----
        try {
            writeNewMd(folder, acceptedTiles);
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать new.md: " + e.getMessage());
            return 2;
        }

        // ---- 7.5 rejected.md (перезаписывается всегда) ----
        try {
            writeRejectedMd(folder, rejectedTiles);
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать rejected.md: " + e.getMessage());
            return 2;
        }

        // ---- 8. предупреждение о дрейфе разметки ----
        int totalTiles = allTiles.size();
        if (totalTiles > UpworkSelectors.UID_MISS_WARN_MIN_TILES
                && (double) uidMisses / totalTiles > UpworkSelectors.UID_MISS_WARN_RATIO) {
            warnings.add(String.format(
                    "возможен частичный сдвиг разметки: тайлов без uid %d из %d (%.1f%%)",
                    uidMisses, totalTiles, 100.0 * uidMisses / totalTiles));
        }

        printSummary(folder, db, ttlDays, files.size(), totalTiles, fresh, rejectedTiles.size(),
                repeated, uidMisses, errors, warnings);
        return 0;
    }

    // ================= selftest =================

    private int selftest() throws IOException {
        String[] fixtures = {"/fixture-en.html", "/fixture-ru.html"};
        JobTileParser parser = new JobTileParser(System.currentTimeMillis());
        boolean allOk = true;
        List<JobTile> fixtureTiles = new ArrayList<>();
        for (String name : fixtures) {
            String html;
            try (var in = Main.class.getResourceAsStream(name)) {
                if (in == null) {
                    System.out.println("STRUCTURE BROKEN " + name + " — фикстура не найдена в jar");
                    allOk = false;
                    continue;
                }
                html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JobTileParser.FileResult result = parser.parseString(html, "selftest");
            boolean ok = result.looksLikeUpwork() && !result.tiles().isEmpty();
            if (ok) {
                System.out.println("STRUCTURE OK " + name
                        + " — тайлов: " + result.tiles().size()
                        + ", uid извлечён у всех: " + (result.uidMisses() == 0));
            } else {
                System.out.println("STRUCTURE BROKEN " + name
                        + " — тайлов: " + result.tiles().size()
                        + ", похоже на Upwork: " + result.looksLikeUpwork());
                allOk = false;
            }
            fixtureTiles.addAll(result.tiles());
        }
        // Маркер подтверждения оплаты: в обеих фикстурах тайлы верифицированы —
        // не распознался => Upwork сдвинул разметку, фильтр отсеивал бы всё подряд.
        if (!fixtureTiles.isEmpty() && !fixtureTiles.stream().allMatch(JobTile::paymentVerified)) {
            System.out.println("STRUCTURE BROKEN — маркер payment-verified не распознался в фикстуре");
            allOk = false;
        }
        // Жёсткий автофильтр: синтетические тайлы, по каждому правилу и на пропуск.
        allOk &= filterCheck("чистый заказ проходит",
                tile(true, "hourly", "20-30", "5-10", "4.9"), false);
        allOk &= filterCheck("оплата не подтверждена",
                tile(false, "hourly", "20-30", "5-10", "4.9"), true);
        allOk &= filterCheck("предложений 10-15 (верхняя граница > 14)",
                tile(true, "fixed", "100", "10-15", "4.9"), true);
        allOk &= filterCheck("предложений 5-10 проходят",
                tile(true, "fixed", "100", "5-10", "4.9"), false);
        allOk &= filterCheck("предложения не указаны — на ручную проверку",
                tile(true, "hourly", "20-30", "", "4.9"), false);
        allOk &= filterCheck("почасовая ставка 10-20 (min < 15)",
                tile(true, "hourly", "10-20", "5-10", "4.9"), true);
        allOk &= filterCheck("почасовой бюджет пуст — на ручную проверку",
                tile(true, "hourly", "", "5-10", "4.9"), false);
        allOk &= filterCheck("фикс 40 (< 50)",
                tile(true, "fixed", "40", "5-10", "4.9"), true);
        allOk &= filterCheck("фикс 500 проходит",
                tile(true, "fixed", "500", "5-10", "4.9"), false);
        allOk &= filterCheck("бюджет фикса пуст — на ручную проверку",
                tile(true, "fixed", "", "5-10", "4.9"), false);
        allOk &= filterCheck("рейтинг 4.0 (< 4.5)",
                tile(true, "fixed", "100", "5-10", "4.0"), true);
        allOk &= filterCheck("рейтинг пуст — на ручную проверку",
                tile(true, "fixed", "100", "5-10", ""), false);
        int reasonCount = RejectionFilter.rejectReasons(
                tile(false, "fixed", "40", "20", "4.9")).size();
        boolean multiOk = reasonCount == 3; // оплата + бюджет + предложения
        System.out.println((multiOk ? "FILTER OK " : "FILTER BROKEN ")
                + "несколько причин одной строкой — причин: " + reasonCount + ", ожидалось: 3");
        allOk &= multiOk;
        if (!allOk) {
            System.out.println("STRUCTURE BROKEN — парсер требует починки");
            return 3;
        }
        System.out.println("STRUCTURE OK — парсер и автофильтр живы (формат v"
                + UpworkSelectors.MARKUP_VERSION + ")");
        return 0;
    }

    private static boolean filterCheck(String name, JobTile tile, boolean expectRejected) {
        boolean rejected = RejectionFilter.isRejected(tile);
        boolean ok = rejected == expectRejected;
        System.out.println((ok ? "FILTER OK " : "FILTER BROKEN ") + name
                + " — отсеян: " + rejected + ", ожидалось: " + expectRejected);
        return ok;
    }

    /** Минимальный тайл для проверок автофильтра: только поля, влияющие на правила. */
    private static JobTile tile(boolean paymentVerified, String type, String budget,
                                String proposals, String rating) {
        return new JobTile("~0123456789", "t", "u", type, budget, proposals, rating, "",
                paymentVerified, 0, "", "selftest");
    }

    // ================= выходные файлы =================

    private void writeNewMd(Path folder, List<JobTile> newTiles) throws IOException {
        List<JobTile> sorted = new ArrayList<>(newTiles);
        sorted.sort(Comparator.comparingLong(JobTile::postedMillis).reversed());
        Path out = folder.resolve(NEW_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Новые заказы Upwork");
            w.println();
            w.println("| uid | title | url | type | budget | proposals | rating | spent | posted | query |");
            w.println("|-----|-------|-----|------|--------|-----------|--------|-------|--------|-------|");
            if (sorted.isEmpty()) {
                w.println("| — | **новых заказов нет** | | | | | | | | |");
            } else {
                for (JobTile t : sorted) {
                    w.println("| " + md(t.uid())
                            + " | " + md(t.title())
                            + " | " + md(t.url())
                            + " | " + md(t.type())
                            + " | " + md(t.budget())
                            + " | " + md(t.proposals())
                            + " | " + md(t.rating())
                            + " | " + md(t.spent())
                            + " | " + md(t.postedText())
                            + " | " + md(t.query())
                            + " |");
                }
            }
        }
    }

    private void writeRejectedMd(Path folder, List<JobTile> rejectedTiles) throws IOException {
        List<JobTile> sorted = new ArrayList<>(rejectedTiles);
        sorted.sort(Comparator.comparingLong(JobTile::postedMillis).reversed());
        Path out = folder.resolve(REJECTED_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Отсеянные заказы Upwork");
            w.println();
            w.println("| uid | title | url | type | budget | proposals | rating | spent | posted | query | reason |");
            w.println("|-----|-------|-----|------|--------|-----------|--------|-------|--------|-------|--------|");
            if (sorted.isEmpty()) {
                w.println("| — | **нет отсеянных** | | | | | | | | | |");
            } else {
                for (JobTile t : sorted) {
                    w.println("| " + md(t.uid())
                            + " | " + md(t.title())
                            + " | " + md(t.url())
                            + " | " + md(t.type())
                            + " | " + md(t.budget())
                            + " | " + md(t.proposals())
                            + " | " + md(t.rating())
                            + " | " + md(t.spent())
                            + " | " + md(t.postedText())
                            + " | " + md(t.query())
                            + " | " + md(String.join(", ", RejectionFilter.rejectReasons(t)))
                            + " |");
                }
            }
        }
    }

    private void writeInvalidNewMd(Path folder) throws IOException {
        Path out = folder.resolve(NEW_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Новые заказы Upwork");
            w.println();
            w.println("ПРОГОН НЕВАЛИДЕН: парсер не нашёл тайлов — вероятно, Upwork сменил разметку.");
            w.println("Это НЕ означает «новых заказов нет». seen.bin не изменён.");
        }
    }

    // ================= утилиты =================

    private void printSummary(Path folder, Path db, long ttlDays,
                              int files, int tiles, int fresh, int rejected, int repeated,
                              int uidMisses, int errors, List<String> warnings) {
        System.out.println("=== upwork-parse-diff · формат парсера v"
                + UpworkSelectors.MARKUP_VERSION + " ===");
        System.out.println("папка дампов : " + folder.toAbsolutePath());
        System.out.println("seen.bin     : " + db.toAbsolutePath() + " (TTL " + ttlDays + " дн.)");
        System.out.println("файлов       : " + files);
        System.out.println("тайлов       : " + tiles);
        System.out.println("новых        : " + fresh);
        System.out.println("отсеяно      : " + rejected);
        System.out.println("повторных    : " + repeated);
        System.out.println("тайлов без uid: " + uidMisses);
        System.out.println("ошибок       : " + errors);
        if (!warnings.isEmpty()) {
            System.out.println("предупреждения:");
            for (String warning : warnings) {
                System.out.println("  ! " + warning);
            }
        }
    }

    private int usage(String message) {
        System.err.println("ERROR: " + message);
        System.err.println("Использование: upwork-parse-diff <папка с дампами> [--ttl-days N] [--db путь\\seen.bin] [--selftest]");
        return 1;
    }

    /** cmd передаёт кавычки в %* дословно — снимаем одну внешнюю пару, если она есть. */
    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String md(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
