package edu.ilkiv.telegrambot.handler;

import edu.ilkiv.telegrambot.model.UserProfile;
import edu.ilkiv.telegrambot.nlp.NlpResult;
import edu.ilkiv.telegrambot.nlp.NlpService;
import edu.ilkiv.telegrambot.service.ApiClientService;
import edu.ilkiv.telegrambot.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@Component
public class InfoBot extends TelegramLongPollingBot {

    private final ApiClientService api;
    private final NlpService nlp;               // ЛАБ 3
    private final UserProfileService profiles;  // ЛАБ 4

    @Value("${telegram.bot.username}")
    private String botUsername;

    public InfoBot(ApiClientService api,
                   NlpService nlp,
                   UserProfileService profiles,
                   @Value("${telegram.bot.token}") String token) {
        super(token);
        this.api = api;
        this.nlp = nlp;
        this.profiles = profiles;
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

        String reply = process(text, profile, telegramUser);
        send(chatId, reply);
    }

    // Маршрутизація

    private String process(String text, UserProfile profile,
                           org.telegram.telegrambots.meta.api.objects.User telegramUser) {

        // Команди з "/" обробляю напряму
        if (text.startsWith("/")) {
            return handleCommand(text, profile);
        }

        // Весь інший текст через NLP
        return handleNlp(text, profile);
    }

    // Обробка команд

    private String handleCommand(String text, UserProfile profile) {
        String cmd = text.split("\\s+")[0].toLowerCase();
        String arg = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";

        return switch (cmd) {
            case "/start"       -> startMessage(profile);
            case "/help"        -> helpMessage();

            case "/weather"     -> {
                // якщо місто не вказано беру з профілю
                String city = arg.isEmpty() ? profile.getFavoriteCity() : arg;
                if (city == null || city.isBlank())
                    yield "🏙 Вкажіть місто або збережіть його: `/setcity Kyiv`";
                yield api.getWeather(city);
            }

            case "/currency"    -> {
                // якщо валюта не вказана беру з профілю
                String base = arg.isEmpty() ? profile.getBaseCurrency() : arg.toUpperCase();
                yield api.getRates(base);
            }

            case "/convert"     -> handleConvertCommand(arg);

            // команди налаштувань профілю
            case "/profile"     -> profiles.formatProfile(profile);

            case "/setcity"     -> {
                if (arg.isBlank())
                    yield "❓ Вкажіть місто: `/setcity Kyiv`";
                String normalized = nlp.normalizeCity(arg);
                profiles.setFavoriteCity(profile.getChatId(), normalized);
                yield "✅ Улюблене місто збережено: *" + normalized + "*";
            }

            case "/setcurrency" -> {
                if (arg.isBlank() || arg.length() != 3)
                    yield "❓ Вкажіть код валюти: `/setcurrency EUR`";
                profiles.setBaseCurrency(profile.getChatId(), arg);
                yield "✅ Базова валюта збережена: *" + arg.toUpperCase() + "*";
            }

            case "/setlang"     -> {
                if (!arg.equals("uk") && !arg.equals("en"))
                    yield "❓ Доступні мови: `uk` (українська) або `en` (англійська)";
                profiles.setLanguage(profile.getChatId(), arg);
                yield "uk".equals(arg)
                        ? "✅ Мову встановлено: 🇺🇦 Українська"
                        : "✅ Language set: 🇬🇧 English";
            }

            default -> "❓ Невідома команда. Напишіть /help";
        };
    }

    // NLP обробка вільного тексту

    private String handleNlp(String text, UserProfile profile) {
        NlpResult result = nlp.analyze(text);

        log.info("NLP результат: intent={}, city={}, currency={}, amount={}",
                result.getIntent(), result.getCity(),
                result.getCurrency(), result.getAmount());

        return switch (result.getIntent()) {

            case WEATHER -> {
                String city = result.hasCity()
                        ? result.getCity()
                        : profile.getFavoriteCity();  //fallback на профіль
                if (city == null)
                    yield "🏙 Не зрозумів місто. Спробуйте: *погода в Києві*\n" +
                            "або збережіть місто: `/setcity Kyiv`";
                yield api.getWeather(city);
            }

            case CURRENCY -> {
                String base = result.hasCurrency()
                        ? result.getCurrency()
                        : profile.getBaseCurrency();  //fallback на профіль
                yield api.getRates(base);
            }

            case CONVERT -> {
                if (!result.hasCurrency() || !result.hasTargetCurrency())
                    yield "❓ Не зрозумів конвертацію. Спробуйте: *100 USD в UAH*";
                double amount = result.hasAmount() ? result.getAmount() : 1.0;
                yield api.convert(result.getCurrency(), result.getTargetCurrency(), amount);
            }

            case GET_PROFILE  -> profiles.formatProfile(profile);
            case GREETING     -> "👋 Привіт, *" + profile.getFirstName() + "*! Напишіть /help.";
            case HELP         -> helpMessage();

            default -> "🤔 Не зрозумів запит.\n\n" +
                    "Спробуйте написати природньою мовою:\n" +
                    "• *яка погода в Одесі?*\n" +
                    "• *курс долара*\n" +
                    "• *скільки 200 євро в гривнях?*\n\n" +
                    "Або використайте /help для списку команд.";
        };
    }

    // Конвертація через команду /convert

    private String handleConvertCommand(String args) {
        // Спочатку пробуємо через NLP
        NlpResult result = nlp.analyze(args);
        if (result.getIntent() == NlpService.Intent.CONVERT
                && result.hasCurrency() && result.hasTargetCurrency()) {
            double amount = result.hasAmount() ? result.getAmount() : 1.0;
            return api.convert(result.getCurrency(), result.getTargetCurrency(), amount);
        }
        return "❓ Формат: `/convert 100 USD to UAH` або `/convert 50 євро в гривню`";
    }

    // Повідомлення

    private String startMessage(UserProfile p) {
        return String.format(
                "👋 Привіт, *%s*!\n\n" +
                        "Я розумію природній текст — просто пишіть що хочете:\n\n" +
                        "🌤 *\"яка погода в Харкові?\"*\n" +
                        "💰 *\"курс євро\"*\n" +
                        "💱 *\"скільки 100 доларів у гривнях\"*\n\n" +
                        "Команди: /help | /profile | /setcity | /setcurrency",
                p.getFirstName());
    }

    private String helpMessage() {
        return "📖 *Команди:*\n\n" +
                "🌤 *Погода*\n" +
                "`/weather [місто]` або просто _\"погода в Києві\"_\n\n" +
                "💰 *Курси валют*\n" +
                "`/currency [USD]` або _\"курс долара\"_\n\n" +
                "💱 *Конвертація*\n" +
                "`/convert 100 USD to UAH` або _\"100 євро в гривні\"_\n\n" +
                "👤 *Профіль (ЛАБ 4)*\n" +
                "`/profile` — переглянути профіль\n" +
                "`/setcity Kyiv` — встановити місто\n" +
                "`/setcurrency EUR` — базова валюта\n" +
                "`/setlang uk` або `/setlang en` — мова\n\n" +
                "💡 *Бот розуміє вільний текст* — команди не обов'язкові!";
    }

    //Відправка повідомлення

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