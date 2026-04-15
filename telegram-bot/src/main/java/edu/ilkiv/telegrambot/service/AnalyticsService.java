package edu.ilkiv.telegrambot.service;

import edu.ilkiv.telegrambot.model.RequestLog;
import edu.ilkiv.telegrambot.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final RequestLogRepository repository;

    private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    /**
     * Зберегти лог запиту.
     * Викликається з InfoBot для кожного повідомлення.
     */
    public void logRequest(Long chatId, String username, String requestType,
                           String requestText, String result, long durationMs) {
        RequestLog logEntry = RequestLog.builder()
                .chatId(chatId)
                .username(username)
                .requestType(requestType)
                .requestText(requestText != null && requestText.length() > 200
                        ? requestText.substring(0, 200) : requestText)
                .result(result)
                .durationMs(durationMs)
                .build();
        repository.save(logEntry);
    }

    /**
     * Сформувати текстовий звіт статистики.
     * /stats — за останній тиждень
     * /stats day — за сьогодні
     * /stats month — за місяць
     */
    public String getStats(String period) {
        long from;
        String periodLabel;

        if ("day".equalsIgnoreCase(period)) {
            from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            periodLabel = "сьогодні";
        } else if ("month".equalsIgnoreCase(period)) {
            from = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
            periodLabel = "за 30 днів";
        } else {
            // week або порожньо
            from = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
            periodLabel = "за 7 днів";
        }

        List<Object[]> byType = repository.countByTypeAfter(from);
        List<Object[]> errors = repository.countErrorsByTypeAfter(from);

        if (byType.isEmpty()) {
            return "📊 Статистика порожня — запитів ще не було.";
        }

        long total = byType.stream().mapToLong(r -> (Long) r[1]).sum();
        long totalErrors = errors.stream().mapToLong(r -> (Long) r[1]).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Статистика запитів (").append(periodLabel).append(")*\n\n");
        sb.append("📈 Всього запитів: *").append(total).append("*\n");
        sb.append("❌ З помилками: *").append(totalErrors).append("*\n\n");
        sb.append("🏆 *Популярні команди:*\n");

        for (int i = 0; i < Math.min(byType.size(), 8); i++) {
            Object[] row = byType.get(i);
            String type = (String) row[0];
            long count = (Long) row[1];
            String emoji = getEmoji(type);
            sb.append(String.format("%s %s: *%d*\n", emoji, type, count));
        }

        sb.append("\n_/stats day — за сьогодні_\n");
        sb.append("_/stats month — за місяць_\n");
        sb.append("_/exportcsv — завантажити CSV файл_");

        return sb.toString();
    }

    /**
     * Генерація CSV звіту — повертає байти файлу.
     * Використовується в InfoBot для відправки через sendDocument().
     *
     * @param period "day" або будь-що інше (тиждень)
     * @return масив байт CSV файлу, або null якщо даних немає
     */
    public byte[] exportCsvBytes(String period) {
        long from;
        if ("day".equalsIgnoreCase(period)) {
            from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } else {
            from = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        }

        List<RequestLog> logs = repository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(from);

        if (logs.isEmpty()) {
            return null;
        }

        StringBuilder csv = new StringBuilder();
        // BOM для коректного відображення UTF-8 в Excel
        csv.append("\uFEFF");
        csv.append("id,chat_id,username,request_type,request_text,result,duration_ms,created_at\n");

        for (RequestLog r : logs) {
            LocalDateTime dt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(r.getCreatedAt()), ZoneId.systemDefault());

            // Екрануємо поля що можуть містити коми або лапки
            String requestText = r.getRequestText() != null
                    ? "\"" + r.getRequestText().replace("\"", "\"\"") + "\""
                    : "";
            String username = r.getUsername() != null ? r.getUsername() : "";

            csv.append(r.getId()).append(",")
                    .append(r.getChatId()).append(",")
                    .append(escapeCsv(username)).append(",")
                    .append(r.getRequestType()).append(",")
                    .append(requestText).append(",")
                    .append(r.getResult()).append(",")
                    .append(r.getDurationMs()).append(",")
                    .append(dt.format(DT_FORMAT)).append("\n");
        }

        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Залишаємо для сумісності — тепер повертає лише підтвердження.
     * Реальний експорт йде через exportCsvBytes().
     */
    public String exportCsv(String period) {
        // Цей метод більше не використовується напряму — InfoBot викликає exportCsvBytes()
        // Залишено для зворотньої сумісності
        return "_Формується файл..._";
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String getEmoji(String type) {
        return switch (type) {
            case "WEATHER"   -> "🌤";
            case "CURRENCY"  -> "💰";
            case "CONVERT"   -> "💱";
            case "REMINDER"  -> "🔔";
            case "CALENDAR"  -> "📅";
            case "TRANSLATE" -> "🌐";
            case "PROFILE"   -> "👤";
            default          -> "📌";
        };
    }
}