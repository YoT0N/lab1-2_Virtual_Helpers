package edu.ilkiv.telegrambot.service;

import edu.ilkiv.telegrambot.model.CalendarEvent;
import edu.ilkiv.telegrambot.repository.CalendarEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ЛАБ 6 — Сервіс календаря.
 *
 * Підтримує команди:
 *   /addevent 25.03 14:00 Назва події
 *   /events           — найближчі події
 *   /events 25.03     — події на конкретну дату
 *   /delevent <id>    — видалення події
 *   /exportics        — текстовий .ics формат
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarService {

    private final CalendarEventRepository repository;

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("dd.MM");

    // "25.03 14:00 Назва події"
    private static final Pattern EVENT_PATTERN = Pattern.compile(
            "^(\\d{1,2}\\.\\d{1,2})\\s+(\\d{1,2}:\\d{2})\\s+(.+)$"
    );
    // "понеділок 14:00 Назва"
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile(
            "^(понеділок|вівторок|середа|четвер|п'ятниця|субота|неділя)\\s+(\\d{1,2}:\\d{2})\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /**
     * Створити подію.
     */
    public String addEvent(Long chatId, String args) {
        if (args.isBlank()) {
            return """
                    ❓ *Формат додавання події:*
                    
                    `/addevent 25.03 14:00 Зустріч`
                    `/addevent понеділок 10:00 Лекція з Java`""";
        }

        try {
            LocalDateTime eventDt = null;
            String title = null;

            Matcher m1 = EVENT_PATTERN.matcher(args);
            Matcher m2 = WEEKDAY_PATTERN.matcher(args);

            if (m1.matches()) {
                String[] dateParts = m1.group(1).split("\\.");
                String[] timeParts = m1.group(2).split(":");
                eventDt = LocalDateTime.of(
                        LocalDateTime.now().getYear(),
                        Integer.parseInt(dateParts[1]),
                        Integer.parseInt(dateParts[0]),
                        Integer.parseInt(timeParts[0]),
                        Integer.parseInt(timeParts[1]));
                title = m1.group(3);

            } else if (m2.matches()) {
                String day = m2.group(1).toLowerCase();
                String[] timeParts = m2.group(2).split(":");
                LocalDate targetDate = getNextWeekday(day);
                eventDt = targetDate.atTime(
                        Integer.parseInt(timeParts[0]),
                        Integer.parseInt(timeParts[1]));
                title = m2.group(3);

            } else {
                return "❌ Не розпізнав дату. Спробуйте: `/addevent 25.03 14:00 Назва`";
            }

            long startMillis = eventDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endMillis = eventDt.plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            CalendarEvent event = CalendarEvent.builder()
                    .chatId(chatId)
                    .title(title)
                    .eventStart(startMillis)
                    .eventEnd(endMillis)
                    .build();
            repository.save(event);

            log.info("Подія створена: chatId={}, title={}, time={}", chatId, title, eventDt.format(DISPLAY_FORMAT));
            return String.format("✅ *Подію додано до календаря*\n\n📅 %s\n📌 %s",
                    eventDt.format(DISPLAY_FORMAT), title);

        } catch (Exception e) {
            log.error("Помилка створення події: {}", e.getMessage());
            return "❌ Помилка: " + e.getMessage();
        }
    }

    /**
     * Показати найближчі події або події на конкретну дату.
     */
    public String listEvents(Long chatId, String dateFilter) {
        List<CalendarEvent> events;
        long now = System.currentTimeMillis();

        if (dateFilter != null && !dateFilter.isBlank()) {
            // Фільтр за датою "25.03"
            try {
                String[] parts = dateFilter.trim().split("\\.");
                LocalDate date = LocalDate.of(
                        LocalDateTime.now().getYear(),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[0]));
                long dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long dayEnd = date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                events = repository.findByChatIdAndEventStartBetweenOrderByEventStartAsc(chatId, dayStart, dayEnd);
            } catch (Exception e) {
                return "❌ Невірний формат дати. Використайте: `/events 25.03`";
            }
        } else {
            // Найближчі 10 подій
            events = repository
                    .findByChatIdAndEventStartGreaterThanEqualOrderByEventStartAsc(chatId, now);
            if (events.size() > 10) events = events.subList(0, 10);
        }

        if (events.isEmpty()) {
            return "📭 Подій не знайдено.\n\nДодати: `/addevent 25.03 14:00 Зустріч`";
        }

        StringBuilder sb = new StringBuilder("📅 *Ваші події:*\n\n");
        for (CalendarEvent e : events) {
            LocalDateTime dt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(e.getEventStart()), ZoneId.systemDefault());
            sb.append(String.format("📌 *#%d* %s\n🕐 %s\n\n",
                    e.getId(), e.getTitle(), dt.format(DISPLAY_FORMAT)));
        }
        sb.append("Видалити: `/delevent <номер>`");
        return sb.toString();
    }

    /**
     * Видалення події.
     */
    public String deleteEvent(Long chatId, String idStr) {
        try {
            long id = Long.parseLong(idStr.trim());
            CalendarEvent event = repository.findById(id).orElse(null);
            if (event == null || !event.getChatId().equals(chatId)) {
                return "❌ Подію #" + id + " не знайдено.";
            }
            repository.delete(event);
            return "✅ Подію #" + id + " видалено.";
        } catch (NumberFormatException e) {
            return "❌ Вкажіть номер події: `/delevent 3`";
        }
    }

    /**
     * Експорт подій у .ics формат (текстовий вивід).
     */
    public String exportIcs(Long chatId) {
        long now = System.currentTimeMillis();
        List<CalendarEvent> events = repository
                .findByChatIdAndEventStartGreaterThanEqualOrderByEventStartAsc(chatId, now);

        if (events.isEmpty()) {
            return "📭 Немає подій для експорту.";
        }

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\n");
        ics.append("VERSION:2.0\n");
        ics.append("PRODID:-//VirtualHelpers Bot//UK\n");

        DateTimeFormatter icsFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

        for (CalendarEvent e : events) {
            LocalDateTime start = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(e.getEventStart()), ZoneId.systemDefault());
            LocalDateTime end = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(e.getEventEnd()), ZoneId.systemDefault());

            ics.append("BEGIN:VEVENT\n");
            ics.append("UID:event-").append(e.getId()).append("@bot\n");
            ics.append("DTSTART:").append(start.format(icsFormat)).append("\n");
            ics.append("DTEND:").append(end.format(icsFormat)).append("\n");
            ics.append("SUMMARY:").append(e.getTitle()).append("\n");
            ics.append("END:VEVENT\n");
        }
        ics.append("END:VCALENDAR");

        return "```\n" + ics + "\n```\n\n_Скопіюйте текст і збережіть як_ `calendar.ics`";
    }

    private LocalDate getNextWeekday(String day) {
        java.util.Map<String, DayOfWeek> days = java.util.Map.of(
                "понеділок", DayOfWeek.MONDAY,
                "вівторок",  DayOfWeek.TUESDAY,
                "середа",    DayOfWeek.WEDNESDAY,
                "четвер",    DayOfWeek.THURSDAY,
                "п'ятниця",  DayOfWeek.FRIDAY,
                "субота",    DayOfWeek.SATURDAY,
                "неділя",    DayOfWeek.SUNDAY
        );
        DayOfWeek target = days.getOrDefault(day, DayOfWeek.MONDAY);
        LocalDate today = LocalDate.now();
        int daysUntil = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        return today.plusDays(daysUntil == 0 ? 7 : daysUntil);
    }
}