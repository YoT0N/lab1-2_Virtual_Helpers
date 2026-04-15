package edu.ilkiv.telegrambot.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * ЛАБ 10 — Навчена команда (NLL).
 *
 * Зберігає асоціацію між фразою користувача і наміром/дією.
 * Дозволяє помічнику "навчитися" розуміти нові способи
 * висловлювати однакові команди.
 *
 * Приклад:
 *   phrase: "запусти хром"
 *   intent: "LAUNCH_BROWSER"
 *   action: null
 *
 *   phrase: "відкрий ютуб"
 *   intent: "OPEN_URL"
 *   action: "https://youtube.com"
 */
@Entity
@Table(name = "learned_commands",
        uniqueConstraints = @UniqueConstraint(columnNames = {"chat_id", "phrase"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnedCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Telegram chat ID власника команди */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** Фраза яку сказав/написав користувач (нижній регістр) */
    @Column(name = "phrase", nullable = false, length = 200)
    private String phrase;

    /** Намір: LAUNCH_BROWSER, CREATE_NOTE, SYSTEM_INFO тощо */
    @Column(name = "intent", nullable = false, length = 50)
    private String intent;

    /** Додаткова дія або параметр (URL, назва файлу тощо) — може бути null */
    @Column(name = "action", length = 500)
    private String action;

    /** Кількість використань (для статистики) */
    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private long usageCount = 0;

    /** Коли створено */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}