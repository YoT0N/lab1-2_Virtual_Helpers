package edu.ilkiv.telegrambot.repository;

import edu.ilkiv.telegrambot.model.LearnedCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ЛАБ 10 — Репозиторій навчених команд NLL.
 */
@Repository
public interface LearnedCommandRepository extends JpaRepository<LearnedCommand, Long> {

    /** Знайти команду за chatId і точною фразою (case-insensitive) */
    Optional<LearnedCommand> findByChatIdAndPhraseIgnoreCase(Long chatId, String phrase);

    /** Всі навчені команди користувача */
    List<LearnedCommand> findByChatId(Long chatId);

    /** Команди, відсортовані за частотою використання */
    List<LearnedCommand> findByChatIdOrderByUsageCountDesc(Long chatId);
}