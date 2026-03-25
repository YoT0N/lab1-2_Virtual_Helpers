package edu.ilkiv.telegrambot.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * ЛАБ 6 — Подія календаря.
 * Зберігається в SQLite і також синхронізується з локальним .ics файлом.
 */
@Entity
@Table(name = "calendar_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** Назва події */
    @Column(name = "title", nullable = false)
    private String title;

    /** Опис події (необов'язковий) */
    @Column(name = "description")
    private String description;

    /** Початок події (unix millis) */
    @Column(name = "event_start", nullable = false)
    private long eventStart;

    /** Кінець події (unix millis) */
    @Column(name = "event_end")
    private long eventEnd;

    /** Коли створено */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}