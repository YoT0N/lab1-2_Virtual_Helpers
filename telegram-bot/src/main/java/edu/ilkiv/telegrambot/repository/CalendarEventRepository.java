package edu.ilkiv.telegrambot.repository;

import edu.ilkiv.telegrambot.model.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ЛАБ 6 — Репозиторій подій календаря.
 */
@Repository
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    /** Найближчі події користувача (від поточного часу) */
    List<CalendarEvent> findByChatIdAndEventStartGreaterThanEqualOrderByEventStartAsc(
            Long chatId, long from);

    /** Всі події користувача в діапазоні дат */
    List<CalendarEvent> findByChatIdAndEventStartBetweenOrderByEventStartAsc(
            Long chatId, long from, long to);
}