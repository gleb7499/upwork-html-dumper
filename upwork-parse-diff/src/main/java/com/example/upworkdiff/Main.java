package com.example.upworkdiff;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * upwork-parse-diff — офлайн-дифф поисковой выдачи Upwork.
 *
 * Прочитать папку с HTML-дампами и обновить дашборд в РОДИТЕЛЬСКОЙ папке дампов:
 *   new.md      — открытые заказы (прошли автофильтр, ждут решения);
 *                 пользователь помечает ❌ (не интересно) / ✅ (откликнулся) —
 *                 помеченные уходят в history.md и навсегда исчезают из new.md,
 *                 даже если заказ снова попадётся в дампах;
 *   history.md  — всё разрешённое вручную (❌/✅ + дата);
 *   rejected.md — автоотсев текущего прогона (❌ проставляет парсер автоматически).
 * Неразмеченные строки new.md переносятся между прогонами и дополняются
 * подходящими заказами из новых дампов. Учёт «новых/повторных» — seen.bin.
 *
 * Exit codes: 0 — ок; 1 — неверные аргументы/папка; 2 — критическая ошибка записи;
 *             3 — структурная поломка парсера (разметка Upwork изменилась).
 */
public final class Main {

    private static final String DEFAULT_DB_DIR = ".upwork-parse-diff";
    private static final String DEFAULT_DB_NAME = "seen.bin";
    private static final String NEW_MD = "new.md";
    private static final String REJECTED_MD = "rejected.md";
    private static final String HISTORY_MD = "history.md";
    private static final String PENDING_TSV = ".upwork-pending.tsv"; // скрытый снапшот открытых

    private static final String MARK_REJECT = "❌"; // не интересно — в историю, не показывать
    private static final String MARK_APPLIED = "✅"; // откликнулся — в историю, не показывать

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

        // Папка не указана → берём самую свежую подпапку дампов в текущей
        // директории (имя вида yyyy-MM-dd_HH-mm, как называет их расширение).
        if (folderArg == null) {
            folderArg = latestDumpDir(Path.of("").toAbsolutePath());
            if (folderArg == null) {
                System.err.println("ERROR: папка не указана и в текущей директории нет "
                        + "подпапок дампов вида yyyy-MM-dd_HH-mm");
                return 1;
            }
            System.out.println("папка дампов (авто): " + folderArg);
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
                writeInvalidNewMd(dashboardDir(folder));
            } catch (IOException e) {
                System.err.println("CRITICAL ERROR: не удалось записать new.md: " + e.getMessage());
                return 2;
            }
            printSummary(folder, dashboardDir(folder), db, ttlDays, files.size(), 0, 0, 0, 0, uidMisses, errors, 0, 0, warnings);
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

        // ---- 5.5 автофильтр по жёстким правилам (применяется ко ВСЕМ тайлам прогона) ----
        List<JobTile> acceptedTiles = new ArrayList<>();
        List<JobTile> rejectedTiles = new ArrayList<>();
        for (JobTile tile : allTiles) {
            if (RejectionFilter.isRejected(tile)) {
                rejectedTiles.add(tile);
            } else {
                acceptedTiles.add(tile);
            }
        }

        Path outDir = dashboardDir(folder);

        // ---- 5.6 рабочее состояние дашборда: пометки из прошлого new.md + история ----
        //         ❌/✅ в любой ячейке строки = заказ разрешён: уходит в history.md
        //         и больше НИКОГДА не появляется в new.md, даже если есть в дампах.
        //         Неразмеченные строки остаются открытыми и переносятся дальше.
        Map<String, JobTile> pending = loadPending(outDir.resolve(PENDING_TSV));
        List<MarkedRow> prevRows = parseMarkedRows(outDir.resolve(NEW_MD));
        List<HistoryRow> resolved = loadHistory(outDir.resolve(HISTORY_MD));
        Set<String> resolvedUids = new HashSet<>();
        for (HistoryRow h : resolved) {
            resolvedUids.add(h.tile().uid());
        }
        Map<String, JobTile> openTiles = new LinkedHashMap<>();
        String today = java.time.LocalDate.now().toString();
        for (MarkedRow row : prevRows) {
            JobTile tile = pending.containsKey(row.tile().uid()) ? pending.get(row.tile().uid()) : row.tile();
            if (row.mark() == null) {
                openTiles.putIfAbsent(tile.uid(), tile);
            } else {
                if (resolvedUids.add(tile.uid())) {
                    resolved.add(0, new HistoryRow(row.mark(), tile, today));
                }
            }
        }

        // ---- 5.7 слияние: открытые из прошлого new.md + подходящие из текущих дампов ----
        int addedFresh = 0;
        for (JobTile tile : acceptedTiles) {
            if (resolvedUids.contains(tile.uid())) {
                continue; // разрешён раньше (❌/✅) — не показывать никогда
            }
            JobTile prev = openTiles.get(tile.uid());
            if (prev == null) {
                openTiles.put(tile.uid(), tile);
                addedFresh++;
            } else if (tile.postedMillis() > prev.postedMillis()) {
                openTiles.put(tile.uid(), tile); // свежее представление того же заказа
            }
        }

        // ---- 6. seen.bin с прунингом TTL (атомарно) ----
        try {
            SeenStore.save(db, seen, ttlDays, now);
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать seen.bin: " + e.getMessage());
            return 2;
        }

        // ---- 7. new.md — открытые заказы (mark пустой, ждёт ❌/✅) ----
        try {
            writeNewMd(outDir, openTiles.values());
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать new.md: " + e.getMessage());
            return 2;
        }

        // ---- 7.4 history.md — всё разрешённое (❌ отклонено / ✅ откликнуто) ----
        try {
            writeHistoryMd(outDir, resolved);
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать history.md: " + e.getMessage());
            return 2;
        }

        // ---- 7.5 rejected.md — автоотсев текущего прогона (❌ проставляет парсер) ----
        try {
            writeRejectedMd(outDir, rejectedTiles);
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать rejected.md: " + e.getMessage());
            return 2;
        }

        // ---- 7.6 снапшот открытых для переноса пометок и сортировки ----
        try {
            savePending(outDir.resolve(PENDING_TSV), openTiles.values());
        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: не удалось записать " + PENDING_TSV + ": " + e.getMessage());
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

        printSummary(folder, outDir, db, ttlDays, files.size(), totalTiles, fresh, rejectedTiles.size(),
                repeated, uidMisses, errors, openTiles.size(), addedFresh, warnings);
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

    /** Дашборд пишется в родительскую папку дампов (единая точка просмотра). */
    private static Path dashboardDir(Path dumpFolder) {
        Path parent = dumpFolder.toAbsolutePath().getParent();
        return parent != null ? parent : dumpFolder;
    }

    private void writeNewMd(Path outDir, Collection<JobTile> tiles) throws IOException {
        List<JobTile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingLong(JobTile::postedMillis).reversed());
        Path out = outDir.resolve(NEW_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Заказы Upwork — открытые (требуют решения)");
            w.println();
            w.println("Обновлено: " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            w.println();
            w.println("Пометка в первой колонке: " + MARK_REJECT + " — не интересно, "
                    + MARK_APPLIED + " — откликнулся. Помеченные уходят в history.md "
                    + "и больше не появляются здесь.");
            w.println();
            w.println("| mark | uid | title | url | type | budget | proposals | rating | spent | posted | query |");
            w.println("|------|-----|-------|-----|------|--------|-----------|--------|-------|--------|-------|");
            if (sorted.isEmpty()) {
                w.println("| | — | **открытых заказов нет** | | | | | | | | |");
            } else {
                for (JobTile t : sorted) {
                    w.println("|  | " + rowCells(t) + " |");
                }
            }
        }
    }

    private void writeHistoryMd(Path outDir, List<HistoryRow> rows) throws IOException {
        Path out = outDir.resolve(HISTORY_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# История — разрешённые заказы");
            w.println();
            w.println(MARK_REJECT + " — отклонено вручную · " + MARK_APPLIED + " — откликнуто");
            w.println();
            w.println("| mark | uid | title | url | type | budget | proposals | rating | spent | posted | query | resolved |");
            w.println("|------|-----|-------|-----|------|--------|-----------|--------|-------|--------|-------|----------|");
            if (rows.isEmpty()) {
                w.println("| | — | **история пуста** | | | | | | | | | |");
            } else {
                for (HistoryRow h : rows) {
                    w.println("| " + h.mark() + " | " + rowCells(h.tile()) + " | " + md(h.resolved()) + " |");
                }
            }
        }
    }

    /** Общие колонки uid..query — порядок должен совпадать с parseMarkedRows/loadHistory. */
    private static String rowCells(JobTile t) {
        return md(t.uid())
                + " | " + md(t.title())
                + " | " + md(t.url())
                + " | " + md(t.type())
                + " | " + md(t.budget())
                + " | " + md(t.proposals())
                + " | " + md(t.rating())
                + " | " + md(t.spent())
                + " | " + md(t.postedText())
                + " | " + md(t.query());
    }

    private void writeRejectedMd(Path outDir, List<JobTile> rejectedTiles) throws IOException {
        List<JobTile> sorted = new ArrayList<>(rejectedTiles);
        sorted.sort(Comparator.comparingLong(JobTile::postedMillis).reversed());
        Path out = outDir.resolve(REJECTED_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Отсеянные автофильтром — актуальный прогон");
            w.println();
            w.println("Парсер отсеял по жёстким правилам (" + MARK_REJECT + " проставлен автоматически). "
                    + "Сюда можно не заглядывать.");
            w.println();
            w.println("| mark | uid | title | url | type | budget | proposals | rating | spent | posted | query | reason |");
            w.println("|------|-----|-------|-----|------|--------|-----------|--------|-------|--------|-------|--------|");
            if (sorted.isEmpty()) {
                w.println("| | — | **нет отсеянных** | | | | | | | | | |");
            } else {
                for (JobTile t : sorted) {
                    w.println("| " + MARK_REJECT + " | " + rowCells(t)
                            + " | " + md(String.join(", ", RejectionFilter.rejectReasons(t)))
                            + " |");
                }
            }
        }
    }

    private void writeInvalidNewMd(Path outDir) throws IOException {
        Path out = outDir.resolve(NEW_MD);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# Заказы Upwork — актуальная выборка");
            w.println();
            w.println("ПРОГОН НЕВАЛИДЕН: парсер не нашёл тайлов — вероятно, Upwork сменил разметку.");
            w.println("Это НЕ означает «новых заказов нет». seen.bin не изменён.");
        }
    }

    // ================= рабочее состояние дашборда =================

    private record MarkedRow(String mark, JobTile tile, String resolved) {}

    private record HistoryRow(String mark, JobTile tile, String resolved) {}

    /**
     * Разобрать markdown-таблицу (new.md / history.md): mark (null / ❌ / ✅), тайл,
     * дата resolution (для history.md). Пометка ищется по всем ячейкам — пользователь
     * может поставить ❌ в любую. Файла нет / пустой — пустой список.
     */
    private static List<MarkedRow> parseMarkedRows(Path mdFile) {
        List<MarkedRow> rows = new ArrayList<>();
        if (!Files.isRegularFile(mdFile)) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(mdFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("|") || trimmed.contains("---")) {
                    continue;
                }
                String[] cells = Arrays.stream(trimmed.split("\\|", -1))
                        .map(String::trim)
                        .toArray(String[]::new);
                // cells[0] и cells[last] пустые (края таблицы); данных в new.md 11 ячеек,
                // в history.md — 12 (последняя — resolved).
                if (cells.length - 2 < 11 || cells[2].equals("uid")) {
                    continue; // шапка/разделитель/не таблица
                }
                String mark = null;
                for (String c : cells) {
                    if (c.contains(MARK_REJECT)) {
                        mark = MARK_REJECT;
                        break;
                    }
                    if (c.contains(MARK_APPLIED)) {
                        mark = MARK_APPLIED;
                        break;
                    }
                }
                JobTile tile = new JobTile(
                        unmd(cells[2]), unmd(cells[3]), unmd(cells[4]), unmd(cells[5]),
                        unmd(cells[6]), unmd(cells[7]), unmd(cells[8]), unmd(cells[9]),
                        true, 0, unmd(cells[10]), unmd(cells[11]));
                if (tile.uid().equals("—")) {
                    continue; // заглушка «пусто»
                }
                String resolved = cells.length - 2 >= 12 ? unmd(cells[12]) : "";
                rows.add(new MarkedRow(mark, tile, resolved));
            }
        } catch (IOException e) {
            System.err.println("WARNING: не удалось прочитать " + mdFile.getFileName()
                    + " (" + e.getMessage() + ") — пометки потеряны");
        }
        return rows;
    }

    private static List<HistoryRow> loadHistory(Path historyFile) {
        List<HistoryRow> rows = new ArrayList<>();
        for (MarkedRow r : parseMarkedRows(historyFile)) {
            rows.add(new HistoryRow(
                    r.mark() != null ? r.mark() : MARK_REJECT, r.tile(), r.resolved()));
        }
        return rows;
    }

    /** Скрытый снапшот открытых заказов: uid + epoch + поля (для сортировки и переноса). */
    private static Map<String, JobTile> loadPending(Path tsv) {
        Map<String, JobTile> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(tsv)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                if (c.length < 12) {
                    continue;
                }
                long epoch = 0;
                try {
                    epoch = Long.parseLong(c[1]);
                } catch (NumberFormatException ignored) {
                    // остаётся 0
                }
                map.put(c[0], new JobTile(c[0], unesc(c[2]), unesc(c[3]), unesc(c[4]), unesc(c[5]),
                        unesc(c[6]), unesc(c[7]), unesc(c[8]), true, epoch, unesc(c[10]), unesc(c[11])));
            }
        } catch (IOException e) {
            System.err.println("WARNING: не удалось прочитать " + tsv.getFileName()
                    + " — сортировка открытых по дате сброшена");
        }
        return map;
    }

    private static void savePending(Path tsv, Collection<JobTile> tiles) throws IOException {
        List<JobTile> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingLong(JobTile::postedMillis).reversed());
        StringBuilder sb = new StringBuilder();
        for (JobTile t : sorted) {
            sb.append(t.uid()).append('\t').append(t.postedMillis());
            for (String f : new String[]{t.title(), t.url(), t.type(), t.budget(), t.proposals(),
                    t.rating(), t.spent(), "", t.postedText(), t.query()}) {
                sb.append('\t').append(esc(f));
            }
            sb.append('\n');
        }
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    private static String unmd(String s) {
        return s.replace("\\|", "|");
    }

    // ================= утилиты =================

    private void printSummary(Path folder, Path outDir, Path db, long ttlDays,
                              int files, int tiles, int fresh, int rejected, int repeated,
                              int uidMisses, int errors, int open, int addedFresh,
                              List<String> warnings) {
        System.out.println("=== upwork-parse-diff · формат парсера v"
                + UpworkSelectors.MARKUP_VERSION + " ===");
        System.out.println("папка дампов : " + folder.toAbsolutePath());
        System.out.println("дашборд      : " + outDir.toAbsolutePath()
                + " (" + NEW_MD + ", " + REJECTED_MD + ", " + HISTORY_MD + ")");
        System.out.println("seen.bin     : " + db.toAbsolutePath() + " (TTL " + ttlDays + " дн.)");
        System.out.println("файлов       : " + files);
        System.out.println("тайлов       : " + tiles);
        System.out.println("новых        : " + fresh);
        System.out.println("отсеяно      : " + rejected);
        System.out.println("повторных    : " + repeated);
        System.out.println("тайлов без uid: " + uidMisses);
        System.out.println("ошибок       : " + errors);
        System.out.println("открыто в new.md: " + open + " (+" + addedFresh + " из этого прогона)");
        if (!warnings.isEmpty()) {
            System.out.println("предупреждения:");
            for (String warning : warnings) {
                System.out.println("  ! " + warning);
            }
        }
    }

    private int usage(String message) {
        System.err.println("ERROR: " + message);
        System.err.println("Использование: upwork-parse-diff [папка с дампами] [--ttl-days N] [--db путь\\seen.bin] [--selftest]");
        System.err.println("  папка не указана → берётся самая свежая подпапка вида yyyy-MM-dd_HH-mm в текущей директории");
        return 1;
    }

    /**
     * Самая свежая подпапка дампов в base (имя вида yyyy-MM-dd_HH-mm — лексикографический
     * порядок совпадает с хронологическим благодаря нулям). null — таких нет.
     */
    private static String latestDumpDir(Path base) {
        try (Stream<Path> walk = Files.list(base)) {
            return walk.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}.*"))
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .map(n -> base.resolve(n).toString())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
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
