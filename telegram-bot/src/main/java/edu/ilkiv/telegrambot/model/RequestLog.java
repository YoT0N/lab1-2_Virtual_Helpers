package edu.ilkiv.telegrambot.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * ЛАБ 8 — Лог запиту користувача.
 * Зберігає всі запити для подальшого аналізу і формування звітів.
 */
@Entity
@Table(name = "request_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "username")
    private String username;

    /** Тип запиту: WEATHER, CURRENCY, CONVERT, REMINDER, CALENDAR, TRANSLATE, PROFILE, OTHER */
    @Column(name = "request_type", nullable = false)
    private String requestType;

    /** Повний текст запиту */
    @Column(name = "request_text")
    private String requestText;

    /** SUCCESS або ERROR */
    @Column(name = "result", nullable = false)
    private String result;

    /** Час виконання (мс) */
    @Column(name = "duration_ms")
    private long durationMs;

    /** Час запиту */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}