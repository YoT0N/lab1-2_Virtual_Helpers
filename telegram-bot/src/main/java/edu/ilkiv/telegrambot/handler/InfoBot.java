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
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Головний обробник Telegram бота.
 *
 * ЛАБ 1-2: Погода, курси валют, конвертація
 * ЛАБ 3:   NLP — розпізнавання вільного тексту
 * ЛАБ 4:   Профіль користувача (SQLite)
 * ЛАБ 5:   Нагадування з планувальником (@Scheduled)
 * ЛАБ 6:   Календар подій + .ics експорт
 * ЛАБ 7:   Переклад (en/de/it) офлайн-словниками
 * ЛАБ 8:   Логування запитів + аналітика + CSV файл
 * ЛАБ 9:   Голосові повідомлення (STT + TTS)
 * ЛАБ 10:  Офлайн NLL + керування системою
 */
@Slf4j
@Component
@EnableScheduling
public class InfoBot extends TelegramLongPollingBot {

    private final ApiClientService api;
    private final NlpService nlp;
    private final UserProfileService profiles;
    private final ReminderService reminders;
    private final CalendarService calendar;
    private final TranslationService translator;
    private final AnalyticsService analytics;
    // ЛАБ 9
    private final VoiceService voice;
    // ЛАБ 10
    private final SystemControlService system;
    private final NllService nll;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${voice.reply.with.voice:false}")
    private boolean replyWithVoice;

    public InfoBot(ApiClientService api, NlpService nlp, UserProfileService profiles,
                   ReminderService reminders, CalendarService calendar,
                   TranslationService translator, AnalyticsService analytics,
                   VoiceService voice, SystemControlService system, NllService nll,
                   @Value("${telegram.bot.token}") String token) {
        super(token);
        this.api        = api;
        this.nlp        = nlp;
        this.profiles   = profiles;
        this.reminders  = reminders;
        this.calendar   = calendar;
        this.translator = translator;
        this.analytics  = analytics;
        this.voice      = voice;
        this.system     = system;
        this.nll        = nll;
    }

    @PostConstruct
    public void init() {
        reminders.setMessageSender(this::send);
        log.info("InfoBot ініціалізовано з усіма сервісами (лаб 1-10)");
    }

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) return;

        Message msg = update.getMessage();
        long chatId = msg.getChatId();
        User telegramUser = msg.getFrom();

        UserProfile profile = profiles.recordRequest(chatId, telegramUser);
        long startTime = System.currentTimeMillis();

        try {
            // ── ЛАБ 9: Голосове повідомлення ────────────────────────────────
            if (msg.hasVoice()) {
                handleVoiceMessage(msg, chatId, telegramUser, profile, startTime);
                return;
            }

            if (!msg.hasText()) return;

            String text = msg.getText().trim();
            log.info("Повідомлення від {} (chatId={}): {}", telegramUser.getFirstName(), chatId, text);

            String requestType = detectRequestType(text);

            // /exportcsv — окрема обробка (відправляє файл)
            if (text.toLowerCase().startsWith("/exportcsv")) {
                String arg = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";
                handleExportCsv(chatId, arg);
                analytics.logRequest(chatId, telegramUser.getUserName(),
                        "ANALYTICS", text, "SUCCESS",
                        System.currentTimeMillis() - startTime);
                return;
            }

            String reply = process(text, chatId, profile);
            analytics.logRequest(chatId, telegramUser.getUserName(),
                    requestType, text, "SUCCESS",
                    System.currentTimeMillis() - startTime);

            // ── ЛАБ 9: Відповідь голосом якщо увімкнено ─────────────────────
            if (replyWithVoice && shouldSendVoice(text)) {
                sendVoiceReply(chatId, reply);
            } else {
                send(chatId, reply);
            }

        } catch (Exception e) {
            log.error("Помилка обробки запиту: {}", e.getMessage(), e);
            send(chatId, "❌ Внутрішня помилка. Спробуйте ще раз.");
            analytics.logRequest(chatId, telegramUser.getUserName(),
                    "ERROR", msg.hasText() ? msg.getText() : "voice", "ERROR",
                    System.currentTimeMillis() - startTime);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ЛАБ 9: Голосові повідомлення
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Обробляє голосове повідомлення:
     * 1. Завантажує OGG файл з Telegram
     * 2. Розпізнає мовлення через Vosk (STT)
     * 3. Обробляє текст як звичайне повідомлення
     * 4. Відповідає текстом (і голосом якщо replyWithVoice=true)
     */
    private void handleVoiceMessage(Message msg, long chatId, User telegramUser,
                                    UserProfile profile, long startTime) {
        send(chatId, "🎙 Розпізнаю мовлення...");

        try {
            // Крок 1: Завантаження файлу
            byte[] oggBytes = downloadVoiceFile(msg.getVoice().getFileId());
            if (oggBytes == null) {
                send(chatId, "❌ Не вдалося завантажити аудіофайл.");
                return;
            }

            // Крок 2: STT — Vosk
            String recognizedText = voice.speechToText(oggBytes);

            if (recognizedText == null || recognizedText.isBlank()) {
                send(chatId, "🤔 Не вдалося розпізнати мовлення.\n\n" +
                        "Переконайтеся що:\n" +
                        "• Встановлено Vosk та ffmpeg\n" +
                        "• Завантажено мовну модель (`/voicestatus`)\n" +
                        "• Аудіо записано чітко");
                return;
            }

            log.info("Розпізнано голос: '{}'", recognizedText);

            // Крок 3: Відображаємо розпізнаний текст
            send(chatId, "🎙 Розпізнано: _\"" + recognizedText + "\"_");

            // Крок 4: Обробляємо як звичайне повідомлення
            String reply = process(recognizedText, chatId, profile);

            analytics.logRequest(chatId, telegramUser.getUserName(),
                    "VOICE_" + detectRequestType(recognizedText),
                    recognizedText, "SUCCESS",
                    System.currentTimeMillis() - startTime);

            // Крок 5: Відповідь (голосом або текстом)
            if (replyWithVoice) {
                sendVoiceReply(chatId, reply);
            } else {
                send(chatId, reply);
            }

        } catch (Exception e) {
            log.error("Помилка обробки голосу: {}", e.getMessage(), e);
            send(chatId, "❌ Помилка розпізнавання: " + e.getMessage());
        }
    }

    /**
     * Завантажує OGG файл голосового повідомлення з серверів Telegram.
     */
    private byte[] downloadVoiceFile(String fileId) {
        try {
            GetFile getFile = new GetFile(fileId);
            File telegramFile = execute(getFile);
            String filePath = telegramFile.getFilePath();
            String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

            try (InputStream is = new URL(fileUrl).openStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.error("Помилка завантаження голосу: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Синтезує відповідь у голос і відправляє як Voice Message.
     */
    private void sendVoiceReply(long chatId, String text) {
        // Спочатку відправляємо текст
        send(chatId, text);

        // Потім синтезуємо та відправляємо голос
        try {
            byte[] wavBytes = voice.textToSpeech(text);
            if (wavBytes == null) {
                log.warn("TTS повернув null для тексту довжиною {}", text.length());
                return;
            }

            byte[] oggBytes = voice.wavToOgg(wavBytes);
            if (oggBytes == null) {
                log.warn("Конвертація WAV→OGG не вдалась");
                return;
            }

            SendVoice sendVoice = SendVoice.builder()
                    .chatId(String.valueOf(chatId))
                    .voice(new InputFile(
                            new java.io.ByteArrayInputStream(oggBytes),
                            "response.ogg"))
                    .build();
            execute(sendVoice);
            log.info("Голосову відповідь відправлено: {} байт", oggBytes.length);

        } catch (Exception e) {
            log.error("Помилка відправки голосу: {}", e.getMessage());
        }
    }

    /**
     * Визначає чи треба відповідати голосом на цей запит
     * (голосові запити завжди отримують голосову відповідь,
     * звичайні текстові — тільки якщо replyWithVoice=true).
     */
    private boolean shouldSendVoice(String text) {
        // Не відправляємо голос для довгих відповідей (списки, статистика)
        return !text.startsWith("/stats") &&
                !text.startsWith("/reminders") &&
                !text.startsWith("/events") &&
                !text.startsWith("/mycommands");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Маршрутизація
    // ═══════════════════════════════════════════════════════════════════════

    private String process(String text, long chatId, UserProfile profile) {
        if (text.startsWith("/")) {
            return handleCommand(text, chatId, profile);
        }

        // ЛАБ 10: NLL — перевіряємо навчені команди та вбудовані асоціації
        NllService.NllResult nllResult = nll.recognize(chatId, text);
        if (nllResult != null) {
            return handleNllIntent(nllResult, chatId, profile);
        }

        // ЛАБ 7: Переклад вільним текстом
        String translationResult = translator.translateFreeText(text);
        if (translationResult != null) return translationResult;

        // ЛАБ 3: NLP
        return handleNlp(text, profile);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Обробка команд
    // ═══════════════════════════════════════════════════════════════════════

    private String handleCommand(String text, long chatId, UserProfile profile) {
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
            case "/exportcsv"  -> "⏳ Формується файл..."; // обробляється в onUpdateReceived

            // ── Голос (ЛАБ 9) ─────────────────────────────────────────────
            case "/voicestatus" -> voice.checkDependencies();
            case "/voiceon"     -> {
                // Активація голосових відповідей (зберігаємо в профілі через флаг)
                yield "🎙 Голосові відповіді: спробуйте надіслати голосове повідомлення!";
            }
            case "/tts"         -> {
                if (arg.isBlank()) yield "❓ `/tts Текст для синтезу мовлення`";
                sendVoiceReply(chatId, arg);
                yield "🔊 Синтезую мовлення...";
            }

            // ── Система (ЛАБ 10) ──────────────────────────────────────────
            case "/launch"     -> system.launchApp(arg);
            case "/sysinfo"    -> system.getSystemInfo();
            case "/network"    -> system.getNetworkInfo();
            case "/volume"     -> system.setVolume(arg);
            case "/note"       -> system.createNote(chatId, arg);
            case "/notes"      -> system.listNotes(chatId);
            case "/readnote"   -> system.readNote(chatId, arg);
            case "/delnote"    -> system.deleteNote(chatId, arg);

            // ── NLL (ЛАБ 10) ──────────────────────────────────────────────
            case "/learn"       -> nll.learn(chatId, arg);
            case "/mycommands"  -> nll.listCommands(chatId);
            case "/forgetcmd"   -> nll.forgetCommand(chatId, arg);
            case "/nllstats"    -> nll.getStats(chatId);

            default -> "❓ Невідома команда. /help";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ЛАБ 10: Обробка NLL намірів
    // ═══════════════════════════════════════════════════════════════════════

    private String handleNllIntent(NllService.NllResult result, long chatId, UserProfile profile) {
        log.info("NLL намір: {} (дія: {})", result.getIntent(), result.getAction());

        return switch (result.getIntent()) {
            case "LAUNCH_BROWSER"  -> system.launchApp("browser");
            case "LAUNCH_NOTEPAD"  -> system.launchApp("notepad");
            case "LAUNCH_CALC"     -> system.launchApp("calculator");
            case "LAUNCH_TERMINAL" -> system.launchApp("terminal");
            case "LAUNCH_FILES"    -> system.launchApp("files");
            case "SYSTEM_INFO"     -> system.getSystemInfo();
            case "NETWORK_INFO"    -> system.getNetworkInfo();
            case "CREATE_NOTE"     -> "📝 Що записати? Напишіть: `/note Текст нотатки`";
            case "LIST_NOTES"      -> system.listNotes(chatId);

            // Якщо є кастомна дія (напр. URL)
            default -> {
                if (result.getAction() != null && !result.getAction().isBlank()) {
                    yield "🚀 Виконую: *" + result.getIntent() + "*\n\n_" + result.getAction() + "_";
                }
                // Fallback — спробуємо як NLP
                yield handleNlp(result.getIntent().toLowerCase(), profile);
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ЛАБ 8: CSV Export
    // ═══════════════════════════════════════════════════════════════════════

    private void handleExportCsv(long chatId, String period) {
        byte[] csvBytes = analytics.exportCsvBytes(period);

        if (csvBytes == null) {
            send(chatId, "📭 Немає даних для експорту.");
            return;
        }

        String fileName = "report_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) +
                ".csv";

        try {
            SendDocument doc = SendDocument.builder()
                    .chatId(String.valueOf(chatId))
                    .document(new InputFile(
                            new java.io.ByteArrayInputStream(csvBytes), fileName))
                    .caption("📊 *Звіт запитів*\n_" + fileName + "_")
                    .parseMode("Markdown")
                    .build();
            execute(doc);
        } catch (TelegramApiException e) {
            log.error("Помилка відправки CSV: {}", e.getMessage());
            send(chatId, "❌ Не вдалося відправити файл.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ЛАБ 3: NLP
    // ═══════════════════════════════════════════════════════════════════════

    private String handleNlp(String text, UserProfile profile) {
        NlpResult result = nlp.analyze(text);

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
                    "• _100 USD в UAH_\n• _переклади на англійську: текст_\n" +
                    "• _відкрий браузер_\n• /help";
        };
    }

    private String handleConvertCommand(String args) {
        NlpResult result = nlp.analyze(args);
        if (result.getIntent() == NlpService.Intent.CONVERT
                && result.hasCurrency() && result.hasTargetCurrency()) {
            return api.convert(result.getCurrency(), result.getTargetCurrency(),
                    result.hasAmount() ? result.getAmount() : 1.0);
        }
        return "❓ Формат: `/convert 100 USD to UAH`";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Повідомлення
    // ═══════════════════════════════════════════════════════════════════════

    private String detectRequestType(String text) {
        String lower = text.toLowerCase();
        if (lower.startsWith("/weather") || lower.contains("погода")) return "WEATHER";
        if (lower.startsWith("/currency") || lower.contains("курс")) return "CURRENCY";
        if (lower.startsWith("/convert") || lower.contains(" в ")) return "CONVERT";
        if (lower.startsWith("/remind")) return "REMINDER";
        if (lower.startsWith("/addevent") || lower.startsWith("/events")) return "CALENDAR";
        if (lower.startsWith("/translate") || lower.contains("переклад")) return "TRANSLATE";
        if (lower.startsWith("/profile") || lower.startsWith("/set")) return "PROFILE";
        if (lower.startsWith("/stats") || lower.startsWith("/exportcsv")) return "ANALYTICS";
        if (lower.startsWith("/launch") || lower.startsWith("/sysinfo") || lower.contains("відкрий")) return "SYSTEM";
        if (lower.startsWith("/learn") || lower.startsWith("/mycommands")) return "NLL";
        return "OTHER";
    }

    private String startMessage(UserProfile p) {
        return String.format(
                "👋 Привіт, *%s*!\n\n" +
                        "Я розумію природній текст і голосові команди. Спробуйте:\n\n" +
                        "🌤 _яка погода в Харкові?_\n" +
                        "💰 _курс євро_\n" +
                        "💱 _100 доларів у гривні_\n" +
                        "🌐 _переклади на англійську: Добрий день_\n" +
                        "🚀 _відкрий браузер_\n" +
                        "🎙 Або надішліть *голосове повідомлення*!\n\n" +
                        "/help — всі команди", p.getFirstName());
    }

    private String helpMessage() {
        return "📖 *Всі команди:*\n\n" +
                "🌤 *Погода*\n`/weather [місто]`\n\n" +
                "💰 *Курси валют*\n`/currency [USD]`\n\n" +
                "💱 *Конвертація*\n`/convert 100 USD to UAH`\n\n" +
                "👤 *Профіль (Лаб 4)*\n`/profile` | `/setcity` | `/setcurrency` | `/setlang`\n\n" +
                "🔔 *Нагадування (Лаб 5)*\n" +
                "`/remind завтра 9:00 Лекція` | `/reminders` | `/delremind <id>`\n\n" +
                "📅 *Календар (Лаб 6)*\n" +
                "`/addevent 25.03 14:00 Зустріч` | `/events` | `/exportics`\n\n" +
                "🌐 *Переклад (Лаб 7)*\n" +
                "`/translate en Привіт` | `de` | `it`\n\n" +
                "📊 *Аналітика (Лаб 8)*\n" +
                "`/stats` | `/stats day` | `/exportcsv`\n\n" +
                "🎙 *Голос (Лаб 9)*\n" +
                "`/voicestatus` — перевірити залежності\n" +
                "`/tts Текст` — синтез мовлення\n" +
                "_Або надішліть голосове повідомлення!_\n\n" +
                "💻 *Система (Лаб 10)*\n" +
                "`/launch browser` | `/launch notepad` | `/launch calculator`\n" +
                "`/sysinfo` — системна інформація\n" +
                "`/network` — мережа\n" +
                "`/volume 50` — гучність\n" +
                "`/note Текст` — нотатка | `/notes` | `/readnote 1` | `/delnote 1`\n\n" +
                "🧠 *Навчання NLL (Лаб 10)*\n" +
                "`/learn фраза → НАМІР` — навчити команду\n" +
                "`/mycommands` — мої команди\n" +
                "`/forgetcmd фраза` — забути команду\n" +
                "`/nllstats` — статистика навчання\n\n" +
                "💡 Бот розуміє вільний текст і голос!";
    }

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