package edu.ilkiv.telegrambot.handler;


import edu.ilkiv.telegrambot.service.ApiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class InfoBot extends TelegramLongPollingBot {

    private final ApiClientService api;

    @Value("${telegram.bot.username}")
    private String botUsername;

    // Патерни для розпізнавання вільного тексту (NLP)
    private static final Pattern WEATHER_PATTERN =
            Pattern.compile("(?i)(?:погода|weather|температура)\\s*(?:в|у|in)?\\s*([а-яА-Яa-zA-Z\\-]+)");

    private static final Pattern CURRENCY_PATTERN =
            Pattern.compile("(?i)(?:курс|валюта|currency)\\s*([a-zA-Z]{3})?");

    private static final Pattern CONVERT_PATTERN =
            Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*([a-zA-Z]{3})\\s*(?:в|to|->|у)\\s*([a-zA-Z]{3})");

    public InfoBot(ApiClientService api,
                   @Value("${telegram.bot.token}") String token) {
        super(token);
        this.api = api;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String text    = update.getMessage().getText().trim();
        long   chatId  = update.getMessage().getChatId();
        String name    = update.getMessage().getFrom().getFirstName();

        log.info("Повідомлення від {} (chatId={}): {}", name, chatId, text);

        String reply = process(text, name);
        send(chatId, reply);
    }

    // ── Обробка команд і тексту ──────────────────────────────────────────

    private String process(String text, String name) {

        if (text.startsWith("/start"))
            return startMessage(name);

        if (text.startsWith("/help"))
            return helpMessage();

        if (text.startsWith("/weather")) {
            String city = arg(text, "/weather");
            return api.getWeather(city.isEmpty() ? "Kyiv" : city);
        }

        if (text.startsWith("/currency")) {
            String base = arg(text, "/currency");
            return api.getRates(base.isEmpty() ? "USD" : base.toUpperCase());
        }

        if (text.startsWith("/convert"))
            return handleConvert(arg(text, "/convert"));

        // Вільний текст
        return handleFreeText(text);
    }

    private String handleFreeText(String text) {
        // "100 USD в UAH"
        Matcher cm = CONVERT_PATTERN.matcher(text);
        if (cm.find()) {
            double amount = Double.parseDouble(cm.group(1).replace(',', '.'));
            return api.convert(cm.group(2), cm.group(3), amount);
        }

        // "погода в Харкові"
        Matcher wm = WEATHER_PATTERN.matcher(text);
        if (wm.find()) {
            String city = wm.group(1);
            return city != null ? api.getWeather(city.trim())
                    : "🌤 Напишіть місто: *погода в Києві* або /weather Kyiv";
        }

        // "курс EUR" / "курс валют"
        Matcher curm = CURRENCY_PATTERN.matcher(text);
        if (curm.find()) {
            String base = curm.group(1);
            return api.getRates(base != null ? base.toUpperCase() : "USD");
        }

        // Привітання
        if (text.matches("(?i)(привіт|hello|hi|добрий день|хай).*"))
            return "👋 Привіт! Напишіть /help щоб побачити команди.";

        return "🤔 Не зрозумів запит.\n\nСпробуйте:\n" +
                "• /weather Kyiv\n• /currency USD\n• 100 USD в UAH\n• /help";
    }

    private String handleConvert(String args) {
        Matcher m = Pattern.compile(
                "(?i)(\\d+(?:[.,]\\d+)?)\\s+([a-zA-Z]{3})\\s+(?:to|в|у|->)\\s+([a-zA-Z]{3})"
        ).matcher(args);
        if (m.find()) {
            double amount = Double.parseDouble(m.group(1).replace(',', '.'));
            return api.convert(m.group(2), m.group(3), amount);
        }
        return "❓ Формат: /convert 100 USD to UAH";
    }

    // ── Повідомлення ─────────────────────────────────────────────────────

    private String startMessage(String name) {
        return String.format(
                "👋 Привіт, *%s*!\n\n" +
                        "Я інформаційний бот. Можу показати:\n" +
                        "🌤 Погоду в будь-якому місті\n" +
                        "💰 Курси валют\n" +
                        "💱 Конвертацію між валютами\n\n" +
                        "Напиши /help для деталей!", name);
    }

    private String helpMessage() {
        return "📖 *Команди:*\n\n" +
                "🌤 *Погода*\n" +
                "`/weather Kyiv` або `погода в Одесі`\n\n" +
                "💰 *Курси валют*\n" +
                "`/currency USD` або `курс EUR`\n\n" +
                "💱 *Конвертація*\n" +
                "`/convert 100 USD to UAH` або `100 EUR в PLN`\n\n" +
                "💡 Можна писати без команд — я розумію вільний текст!";
    }

    // ── Утиліти ──────────────────────────────────────────────────────────

    private String arg(String text, String command) {
        String s = text.substring(command.length()).trim();
        if (s.startsWith("@")) {
            int idx = s.indexOf(' ');
            s = idx > 0 ? s.substring(idx).trim() : "";
        }
        return s;
    }

    private void send(long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Помилка відправки повідомлення: {}", e.getMessage());
        }
    }
}