package edu.ilkiv.telegrambot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ЛАБ 7 — Сервіс перекладу через LibreTranslate API.
 *
 * LibreTranslate — безкоштовний відкритий API перекладу.
 * Публічний endpoint: https://libretranslate.com
 *
 * Підтримувані мови: uk (українська), en (англійська), de (німецька).
 *
 * Команди:
 *   /translate en Привіт світ          → переклад на англійську
 *   /translate de Як справи?           → переклад на німецьку
 *   /translate uk Hello world          → переклад на українську
 *   Переклади на англійську: Привіт    → вільний текст (NLP)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private final RestTemplate restTemplate;

    // Публічний LibreTranslate API (безкоштовний, без ключа)
    private static final String API_URL = "https://libretranslate.com/translate";

    // Підтримувані мови (ЛАБ 7: мінімум 3 мови)
    private static final Map<String, String> LANG_NAMES = Map.of(
            "uk", "🇺🇦 Українська",
            "en", "🇬🇧 Англійська",
            "de", "🇩🇪 Німецька"
    );

    // Патерн вільного тексту: "переклади на англійську: текст"
    private static final Pattern FREE_TEXT_PATTERN = Pattern.compile(
            "(?i)переклад[иіь]?\\s+на\\s+(\\p{L}+)[:\\s]+(.+)",
            Pattern.UNICODE_CASE
    );

    private static final Map<String, String> LANG_KEYWORD_MAP = Map.of(
            "англійськ", "en",
            "english",   "en",
            "українськ", "uk",
            "ukrainian", "uk",
            "німецьк",   "de",
            "german",    "de",
            "deutsch",   "de"
    );

    /**
     * Переклад тексту — викликається з команди /translate.
     * args формат: "en текст для перекладу"
     */
    public String translate(String args) {
        if (args.isBlank()) {
            return buildHelpMessage();
        }

        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2) {
            return "❓ Вкажіть мову і текст: `/translate en Привіт`";
        }

        String targetLang = parts[0].toLowerCase();
        String text = parts[1];

        if (!LANG_NAMES.containsKey(targetLang)) {
            return "❌ Непідтримувана мова: *" + targetLang + "*\n\nДоступні: `uk`, `en`, `de`";
        }

        return doTranslate(text, "auto", targetLang);
    }

    /**
     * Переклад з вільного тексту (NLP).
     * Наприклад: "переклади на англійську: Добрий день"
     */
    public String translateFreeText(String text) {
        Matcher m = FREE_TEXT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String langKeyword = m.group(1).toLowerCase();
        String sourceText = m.group(2).trim();

        String targetLang = LANG_KEYWORD_MAP.entrySet().stream()
                .filter(e -> langKeyword.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);

        if (targetLang == null) {
            return "❌ Не розпізнав мову: *" + langKeyword + "*\n\nДоступні: українська, англійська, німецька";
        }

        return doTranslate(sourceText, "auto", targetLang);
    }

    /**
     * Виконує HTTP запит до LibreTranslate API.
     */
    private String doTranslate(String text, String sourceLang, String targetLang) {
        try {
            log.info("Переклад: {} -> {}, текст: '{}'", sourceLang, targetLang, text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // LibreTranslate очікує JSON body
            Map<String, String> body = Map.of(
                    "q", text,
                    "source", sourceLang,
                    "target", targetLang,
                    "format", "text"
            );

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    API_URL, HttpMethod.POST, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String translated = (String) response.getBody().get("translatedText");
                String detectedLang = getDetectedLang(response.getBody());

                return String.format("🌐 *Переклад*\n\n" +
                                "📥 Оригінал (%s):\n_%s_\n\n" +
                                "📤 %s:\n*%s*",
                        detectedLang, text,
                        LANG_NAMES.getOrDefault(targetLang, targetLang),
                        translated);
            }

            return "❌ Помилка API перекладу.";

        } catch (Exception e) {
            log.error("Помилка перекладу: {}", e.getMessage());
            // Якщо LibreTranslate недоступний — повідомляємо
            return "❌ Сервіс перекладу тимчасово недоступний.\n\n" +
                    "LibreTranslate може бути перевантажений. Спробуйте пізніше.\n" +
                    "_Альтернатива: translate.google.com_";
        }
    }

    @SuppressWarnings("unchecked")
    private String getDetectedLang(Map body) {
        try {
            Map<String, Object> detected = (Map<String, Object>) body.get("detectedLanguage");
            if (detected != null) {
                String lang = (String) detected.get("language");
                return LANG_NAMES.getOrDefault(lang, lang);
            }
        } catch (Exception ignored) {}
        return "авто";
    }

    private String buildHelpMessage() {
        return "🌐 *Переклад тексту*\n\n" +
                "*/translate en* Привіт світ — переклад на 🇬🇧 англійську\n" +
                "*/translate de* Як справи? — переклад на 🇩🇪 німецьку\n" +
                "*/translate uk* Hello world — переклад на 🇺🇦 українську\n\n" +
                "Або вільним текстом:\n" +
                "_\"переклади на англійську: Доброго ранку\"_\n\n" +
                "Доступні мови: `uk` 🇺🇦 | `en` 🇬🇧 | `de` 🇩🇪";
    }
}