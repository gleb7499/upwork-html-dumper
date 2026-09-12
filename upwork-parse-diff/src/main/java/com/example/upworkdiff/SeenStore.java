package com.example.upworkdiff;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Глобальное хранилище «виденных» заказов — бинарный seen.bin.
 *
 * Формат (DataOutputStream):
 *   magic "UWSEEN1" (7 байт ASCII) + int count + по записи: UTF uid + long lastSeen (epoch millis).
 *
 * Java Object Serialization НЕ используется (хрупко).
 * Повреждённый файл — исключение CorruptFileException: вызывающий код сохраняет
 * его как .corrupt и стартует с пустой базы.
 */
public final class SeenStore {

    public static final class CorruptFileException extends Exception {
        CorruptFileException(String message) {
            super(message);
        }
    }

    private static final byte[] MAGIC = "UWSEEN1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_ENTRIES = 20_000_000;

    private SeenStore() {}

    /** Загрузить seen.bin. Файла нет — пустая база. Повреждён — CorruptFileException. */
    public static Map<String, Long> load(Path file) throws IOException, CorruptFileException {
        Map<String, Long> map = new HashMap<>();
        if (!Files.exists(file)) {
            return map;
        }
        try (InputStream in = Files.newInputStream(file);
             DataInputStream data = new DataInputStream(in)) {
            byte[] magic = new byte[MAGIC.length];
            int read = data.read(magic);
            if (read != MAGIC.length || !new String(magic, StandardCharsets.US_ASCII).equals("UWSEEN1")) {
                throw new CorruptFileException("bad magic");
            }
            int count = data.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new CorruptFileException("bad count: " + count);
            }
            for (int i = 0; i < count; i++) {
                String uid;
                long lastSeen;
                try {
                    uid = data.readUTF();
                    lastSeen = data.readLong();
                } catch (EOFException e) {
                    throw new CorruptFileException("truncated at record " + i);
                }
                map.put(uid, lastSeen);
            }
            if (data.read() != -1) {
                throw new CorruptFileException("trailing bytes");
            }
        }
        return map;
    }

    /**
     * Атомарно записать файл: прунинг записей старше ttlDays, временный файл + rename.
     * ttlDays == 0 — база очищается полностью (санити-проверка прунинга).
     */
    public static void save(Path file, Map<String, Long> seen, long ttlDays, long nowMillis)
            throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, Long> pruned = new HashMap<>();
        if (ttlDays > 0) {
            long cutoff = nowMillis - ttlDays * 86_400_000L;
            for (Map.Entry<String, Long> e : seen.entrySet()) {
                if (e.getValue() >= cutoff) {
                    pruned.put(e.getKey(), e.getValue());
                }
            }
        }
        // ttlDays == 0 — полная очистка базы (санити-проверка прунинга)

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp);
             DataOutputStream data = new DataOutputStream(out)) {
            data.write(MAGIC);
            data.writeInt(pruned.size());
            for (Map.Entry<String, Long> e : pruned.entrySet()) {
                data.writeUTF(e.getKey());
                data.writeLong(e.getValue());
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Переименовать повреждённый файл в .corrupt. */
    public static void quarantineCorrupt(Path file) throws IOException {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt");
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
