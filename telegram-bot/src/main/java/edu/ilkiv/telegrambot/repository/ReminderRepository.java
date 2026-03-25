package edu.ilkiv.telegrambot.repository;

import edu.ilkiv.telegrambot.model.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ЛАБ 5 — Репозиторій нагадувань.
 */
@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    /** Всі ненадіслані нагадування що вже настав час */
    List<Reminder> findBySentFalseAndRemindAtLessThanEqual(long now);

    /** Нагадування конкретного користувача (ненадіслані) */
    List<Reminder> findByChatIdAndSentFalseOrderByRemindAtAsc(Long chatId);
}