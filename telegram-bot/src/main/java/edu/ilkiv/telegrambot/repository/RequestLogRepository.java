package edu.ilkiv.telegrambot.repository;

import edu.ilkiv.telegrambot.model.RequestLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ЛАБ 8 — Репозиторій логів запитів.
 */
@Repository
public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    /** Кількість запитів за типом (для статистики) */
    @Query("SELECT r.requestType, COUNT(r) FROM RequestLog r " +
            "WHERE r.createdAt >= :from GROUP BY r.requestType ORDER BY COUNT(r) DESC")
    List<Object[]> countByTypeAfter(@Param("from") long from);

    /** Всі логи за останній час для конкретного чату */
    List<RequestLog> findByChatIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long chatId, long from);

    /** Всі логи за період (для CSV звіту) */
    List<RequestLog> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(long from);

    /** Кількість помилок за тип */
    @Query("SELECT r.requestType, COUNT(r) FROM RequestLog r " +
            "WHERE r.result = 'ERROR' AND r.createdAt >= :from GROUP BY r.requestType")
    List<Object[]> countErrorsByTypeAfter(@Param("from") long from);
}