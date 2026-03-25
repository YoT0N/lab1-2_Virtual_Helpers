package edu.ilkiv.telegrambot.handler;

import edu.ilkiv.telegrambot.model.UserProfile;
import edu.ilkiv.telegrambot.nlp.NlpResult;
import edu.ilkiv.telegrambot.nlp.NlpService;
import edu.ilkiv.telegrambot.service.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Головний обробник Telegram бота.
 *
 * ЛАБ 1-2: Погода, курси валют, конвертація
 * ЛАБ 3:   NLP — розпізнавання вільного тексту
 * ЛАБ 4:   Профіль користувача (SQLite)
 * ЛАБ 5:   Нагадування з планувальником (@Scheduled)
 * ЛАБ 6:   Календар подій + .ics експорт
 * ЛАБ 7:   Переклад (uk/en/de) через LibreTranslate
 * ЛАБ 8:   Логування запитів + аналітика + CSV звіт
 */
@Slf4j
@Component
@EnableScheduling  // ← ЛАБ 5: вмикаємо планувальник для нагадувань
public class InfoBot extends TelegramLongPollingBot {

    private final ApiClientService api;
    private final NlpService nlp;
    private final UserProfileService profiles;
    private final ReminderService reminders;     // ЛАБ 5
    private final CalendarService calendar;      // ЛАБ 6
    private final TranslationService translator; // ЛАБ 7
    private final AnalyticsService analytics;    // ЛАБ 8

    @Value("${telegram.bot.username}")
    private String botUsername;

    public InfoBot(ApiClientService api, NlpService nlp, UserProfileService profiles,
                   ReminderService reminders, CalendarService calendar,
                   TranslationService translator, AnalyticsService analytics,
                   @Value("${telegram.bot.token}") String token) {
        super(token);
        this.api = api;
        this.nlp = nlp;
        this.profiles = profiles;
        this.reminders = reminders;
        this.calendar = calendar;
        this.translator = translator;
        this.analytics = analytics;
    }

    /**
     * ЛАБ 5: Після старту передаємо боту callback для відправки нагадувань.
     * ReminderService сам не може відправляти — йому потрібен доступ до execute().
     */
    @PostConstruct
    public void init() {
        reminders.setMessageSender(this::send);
        log.info("InfoBot ініціалізовано з усіма сервісами (лаб 1-8)");
    }

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String text = update.getMessage().getText().trim();
        long chatId = update.getMessage().getChatId();
        var telegramUser = update.getMessage().getFrom();

        log.info("Повідомлення від {} (chatId={}): {}", telegramUser.getFirstName(), chatId, text);

        UserProfile profile = profiles.recordRequest(chatId, telegramUser);

        long startTime = System.currentTimeMillis();
        String requestType = detectRequestType(text);
        String reply;

        try {
            reply = process(text, profile);
            // ЛАБ 8: логуємо успішний запит
            analytics.logRequest(chatId, telegramUser.getUserName(),
                    requestType, text, "SUCCESS",
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("Помилка обробки запиту: {}", e.getMessage(), e);
            reply = "❌ Внутрішня помилка. Спробуйте ще раз.";
            // ЛАБ 8: логуємо помилку
            analytics.logRequest(chatId, telegramUser.getUserName(),
                    requestType, text, "ERROR",
                    System.currentTimeMillis() - startTime);
        }

        send(chatId, reply);
    }

    // ── Маршрутизація ─────────────────────────────────────────────────────

    private String process(String text, UserProfile profile) {
        if (text.startsWith("/")) {
            return handleCommand(text, profile);
        }

        // ЛАБ 7: перевіряємо чи це запит на переклад вільним текстом
        String translationResult = translator.translateFreeText(text);
        if (translationResult != null) return translationResult;

        // ЛАБ 3: NLP для всього іншого
        return handleNlp(text, profile);
    }

    // ── Обробка команд ────────────────────────────────────────────────────

    private String handleCommand(String text, UserProfile profile) {
        String cmd = text.split("\\s+")[0].toLowerCase();
        String arg = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";

        return switch (cmd) {

            // ── Базові (ЛАБ 1-2) ──────────────────────────────────────────
            case "/start"    -> startMessage(profile);
            case "/help"     -> helpMessage();

            case "/weather"  -> {
                String city = arg.isEmpty() ? profile.getFavoriteCity() : arg;
                if (city == null || city.isBlank())
                    yield "🏙 Вкажіть місто або збережіть: `/setcity Kyiv`";
                yield api.getWeather(city);
            }

            case "/currency" -> {
                String base = arg.isEmpty() ? profile.getBaseCurrency() : arg.toUpperCase();
                yield api.getRates(base);
            }

            case "/convert"  -> handleConvertCommand(arg);

            // ── Профіль (ЛАБ 4) ───────────────────────────────────────────
            case "/profile"  -> profiles.formatProfile(profile);

            case "/setcity"  -> {
                if (arg.isBlank()) yield "❓ `/setcity Kyiv`";
                String norm = nlp.normalizeCity(arg);
                profiles.setFavoriteCity(profile.getChatId(), norm);
                yield "✅ Місто: *" + norm + "*";
            }

            case "/setcurrency" -> {
                if (arg.isBlank() || arg.length() != 3)
                    yield "❓ `/setcurrency EUR`";
                profiles.setBaseCurrency(profile.getChatId(), arg);
                yield "✅ Валюта: *" + arg.toUpperCase() + "*";
            }

            case "/setlang" -> {
                if (!arg.equals("uk") && !arg.equals("en"))
                    yield "❓ Доступні: `uk` або `en`";
                profiles.setLanguage(profile.getChatId(), arg);
                yield "uk".equals(arg) ? "✅ Мова: 🇺🇦 Українська" : "✅ Language: 🇬🇧 English";
            }

            // ── Нагадування (ЛАБ 5) ───────────────────────────────────────
            case "/remind"     -> reminders.createReminder(profile.getChatId(), arg);
            case "/reminders"  -> reminders.listReminders(profile.getChatId());
            case "/delremind"  -> reminders.deleteReminder(profile.getChatId(), arg);

            // ── Календар (ЛАБ 6) ──────────────────────────────────────────
            case "/addevent"   -> calendar.addEvent(profile.getChatId(), arg);
            case "/events"     -> calendar.listEvents(profile.getChatId(), arg);
            case "/delevent"   -> calendar.deleteEvent(profile.getChatId(), arg);
            case "/exportics"  -> calendar.exportIcs(profile.getChatId());

            // ── Переклад (ЛАБ 7) ──────────────────────────────────────────
            case "/translate"  -> translator.translate(arg);

            // ── Аналітика (ЛАБ 8) ─────────────────────────────────────────
            case "/stats"      -> analytics.getStats(arg);
            case "/exportcsv"  -> analytics.exportCsv(arg);

            default -> "❓ Невідома команда. /help";
        };
    }

    // ── NLP (ЛАБ 3) ───────────────────────────────────────────────────────

    private String handleNlp(String text, UserProfile profile) {
        NlpResult result = nlp.analyze(text);

        log.info("NLP: intent={}, city={}, currency={}, amount={}",
                result.getIntent(), result.getCity(),
                result.getCurrency(), result.getAmount());

        return switch (result.getIntent()) {
            case WEATHER -> {
                String city = result.hasCity() ? result.getCity() : profile.getFavoriteCity();
                if (city == null)
                    yield "🏙 Не зрозумів місто. Спробуйте: *погода в Києві*";
                yield api.getWeather(city);
            }
            case CURRENCY -> {
                String base = result.hasCurrency() ? result.getCurrency() : profile.getBaseCurrency();
                yield api.getRates(base);
            }
            case CONVERT -> {
                if (!result.hasCurrency() || !result.hasTargetCurrency())
                    yield "❓ Не зрозумів. Спробуйте: *100 USD в UAH*";
                yield api.convert(result.getCurrency(), result.getTargetCurrency(),
                        result.hasAmount() ? result.getAmount() : 1.0);
            }
            case GET_PROFILE -> profiles.formatProfile(profile);
            case GREETING    -> "👋 Привіт, *" + profile.getFirstName() + "*! /help";
            case HELP        -> helpMessage();
            default -> "🤔 Не зрозумів.\n\nСпробуйте:\n" +
                    "• _погода в Одесі_\n• _курс євро_\n" +
                    "• _100 USD в UAH_\n• _переклади на англійську: текст_\n• /help";
        };
    }

    // ── Конвертація через команду ─────────────────────────────────────────

    private String handleConvertCommand(String args) {
        NlpResult result = nlp.analyze(args);
        if (result.getIntent() == NlpService.Intent.CONVERT
                && result.hasCurrency() && result.hasTargetCurrency()) {
            return api.convert(result.getCurrency(), result.getTargetCurrency(),
                    result.hasAmount() ? result.getAmount() : 1.0);
        }
        return "❓ Формат: `/convert 100 USD to UAH`";
    }

    // ── Визначення типу запиту для логування (ЛАБ 8) ─────────────────────

    private String detectRequestType(String text) {
        String lower = text.toLowerCase();
        if (lower.startsWith("/weather") || lower.contains("погода")) return "WEATHER";
        if (lower.startsWith("/currency") || lower.contains("курс")) return "CURRENCY";
        if (lower.startsWith("/convert") || lower.contains(" в ")) return "CONVERT";
        if (lower.startsWith("/remind")) return "REMINDER";
        if (lower.startsWith("/addevent") || lower.startsWith("/events") || lower.startsWith("/delevent")) return "CALENDAR";
        if (lower.startsWith("/translate") || lower.contains("переклад")) return "TRANSLATE";
        if (lower.startsWith("/profile") || lower.startsWith("/set")) return "PROFILE";
        if (lower.startsWith("/stats") || lower.startsWith("/exportcsv")) return "ANALYTICS";
        return "OTHER";
    }

    // ── Повідомлення ──────────────────────────────────────────────────────

    private String startMessage(UserProfile p) {
        return String.format(
                "👋 Привіт, *%s*!\n\n" +
                        "Я розумію природній текст. Просто пишіть:\n\n" +
                        "🌤 _яка погода в Харкові?_\n" +
                        "💰 _курс євро_\n" +
                        "💱 _100 доларів у гривні_\n" +
                        "🌐 _переклади на англійську: Добрий день_\n\n" +
                        "/help — всі команди", p.getFirstName());
    }

    private String helpMessage() {
        return "📖 *Всі команди:*\n\n" +
                "🌤 *Погода*\n`/weather [місто]` або _погода в Києві_\n\n" +
                "💰 *Курси валют*\n`/currency [USD]` або _курс долара_\n\n" +
                "💱 *Конвертація*\n`/convert 100 USD to UAH` або _100 євро в злотих_\n\n" +
                "👤 *Профіль (Лаб 4)*\n`/profile` | `/setcity` | `/setcurrency` | `/setlang`\n\n" +
                "🔔 *Нагадування (Лаб 5)*\n" +
                "`/remind завтра 9:00 Лекція`\n" +
                "`/remind через 30 хвилин Подзвонити`\n" +
                "`/reminders` — список | `/delremind <id>` — видалити\n\n" +
                "📅 *Календар (Лаб 6)*\n" +
                "`/addevent 25.03 14:00 Зустріч`\n" +
                "`/events` — найближчі | `/events 25.03` — за датою\n" +
                "`/delevent <id>` | `/exportics` — .ics файл\n\n" +
                "🌐 *Переклад (Лаб 7)*\n" +
                "`/translate en Привіт` | `/translate de Як справи?`\n" +
                "Або: _переклади на англійську: текст_\n\n" +
                "📊 *Аналітика (Лаб 8)*\n" +
                "`/stats` — тижнева статистика\n" +
                "`/stats day` — за сьогодні\n" +
                "`/exportcsv` — CSV звіт\n\n" +
                "💡 Бот розуміє вільний текст!";
    }

    // ── Відправка ─────────────────────────────────────────────────────────

    private void send(long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Помилка відправки: {}", e.getMessage());
        }
    }
}