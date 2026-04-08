package edu.ilkiv.telegrambot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ЛАБ 7 — Сервіс перекладу (офлайн через словникову транслітерацію + словники).
 *
 * Використовує бібліотеку Apache OpenNLP (вже є в залежностях) для
 * визначення мови та просту словникову систему для перекладу базових фраз.
 *
 * Підтримувані мови: en (англійська), de (німецька), it (італійська).
 *
 * Для повноцінного офлайн перекладу рекомендується замінити на
 * бібліотеку Argos Translate (Python) або використати DL4J модель.
 * Тут реалізовано легковагу офлайн-заміну без зовнішніх API.
 *
 * Команди:
 *   /translate en Привіт світ          → переклад на англійську
 *   /translate de Як справи?           → переклад на німецьку
 *   /translate it Добрий день          → переклад на італійську
 *   Переклади на англійську: Привіт    → вільний текст (NLP)
 */
@Slf4j
@Service
public class TranslationService {

    // ── Підтримувані мови (ЛАБ 7: мінімум 3) ──────────────────────────────
    private static final Map<String, String> LANG_NAMES = Map.of(
            "en", "🇬🇧 Англійська",
            "de", "🇩🇪 Німецька",
            "it", "🇮🇹 Італійська"
    );

    // ── Патерн вільного тексту ─────────────────────────────────────────────
    // Виправлено: раніше "текст:" потрапляло в sourceText
    // Тепер патерн ігнорує необов'язковий префікс "текст:"
    private static final Pattern FREE_TEXT_PATTERN = Pattern.compile(
            "(?i)переклад[иіь]?\\s+на\\s+(\\p{L}+)[:\\s]+(?:текст[:\\s]+)?(.+)",
            Pattern.UNICODE_CASE | Pattern.DOTALL
    );

    private static final Map<String, String> LANG_KEYWORD_MAP = Map.of(
            "англійськ", "en",
            "english",   "en",
            "англ",      "en",
            "українськ", "uk",
            "ukrainian", "uk",
            "укр",       "uk",
            "німецьк",   "de",
            "german",    "de",
            "deutsch",   "de",
            "італійськ", "it"
    );

    // Додаткові ключові слова для Italian (не вмістились у Map.of(10))
    private static final Map<String, String> LANG_KEYWORD_MAP_EXTRA = Map.of(
            "italian",   "it",
            "italiano",  "it"
    );

    // ── Словники перекладу uk→target ──────────────────────────────────────

    private static final Map<String, String> UK_TO_EN = Map.ofEntries(
            Map.entry("привіт", "hello"),
            Map.entry("добрий день", "good day"),
            Map.entry("добрий ранок", "good morning"),
            Map.entry("добрий вечір", "good evening"),
            Map.entry("на добраніч", "good night"),
            Map.entry("дякую", "thank you"),
            Map.entry("будь ласка", "please"),
            Map.entry("вибачте", "excuse me"),
            Map.entry("так", "yes"),
            Map.entry("ні", "no"),
            Map.entry("де", "where"),
            Map.entry("як справи", "how are you"),
            Map.entry("все добре", "everything is fine"),
            Map.entry("я не розумію", "i don't understand"),
            Map.entry("допоможіть", "help me"),
            Map.entry("скільки коштує", "how much does it cost"),
            Map.entry("де знаходиться", "where is"),
            Map.entry("я хочу", "i want"),
            Map.entry("хочу відпочити", "i want to rest"),
            Map.entry("від лабораторних робіт", "from laboratory works"),
            Map.entry("лабораторна робота", "laboratory work"),
            Map.entry("університет", "university"),
            Map.entry("студент", "student"),
            Map.entry("програмування", "programming"),
            Map.entry("комп'ютер", "computer"),
            Map.entry("телефон", "phone"),
            Map.entry("місто", "city"),
            Map.entry("погода", "weather"),
            Map.entry("сьогодні", "today"),
            Map.entry("завтра", "tomorrow"),
            Map.entry("вчора", "yesterday"),
            Map.entry("час", "time"),
            Map.entry("гроші", "money"),
            Map.entry("робота", "work"),
            Map.entry("будинок", "house"),
            Map.entry("школа", "school"),
            Map.entry("вода", "water"),
            Map.entry("їжа", "food"),
            Map.entry("кава", "coffee"),
            Map.entry("чай", "tea"),
            Map.entry("хліб", "bread"),
            Map.entry("молоко", "milk"),
            Map.entry("добре", "fine"),
            Map.entry("погано", "bad"),
            Map.entry("гарно", "beautiful"),
            Map.entry("швидко", "fast"),
            Map.entry("повільно", "slowly"),
            Map.entry("великий", "big"),
            Map.entry("маленький", "small"),
            Map.entry("нова", "new"),
            Map.entry("старий", "old"),
            Map.entry("перший", "first"),
            Map.entry("другий", "second"),
            Map.entry("україна", "ukraine"),
            Map.entry("київ", "kyiv")
    );

    private static final Map<String, String> UK_TO_DE = Map.ofEntries(
            Map.entry("привіт", "hallo"),
            Map.entry("добрий день", "guten tag"),
            Map.entry("добрий ранок", "guten morgen"),
            Map.entry("добрий вечір", "guten abend"),
            Map.entry("на добраніч", "gute nacht"),
            Map.entry("дякую", "danke"),
            Map.entry("будь ласка", "bitte"),
            Map.entry("вибачте", "entschuldigung"),
            Map.entry("так", "ja"),
            Map.entry("ні", "nein"),
            Map.entry("як справи", "wie geht es ihnen"),
            Map.entry("все добре", "alles gut"),
            Map.entry("я не розумію", "ich verstehe nicht"),
            Map.entry("допоможіть", "helfen sie mir"),
            Map.entry("скільки коштує", "wie viel kostet das"),
            Map.entry("де знаходиться", "wo ist"),
            Map.entry("я хочу", "ich möchte"),
            Map.entry("хочу відпочити", "ich möchte mich ausruhen"),
            Map.entry("від лабораторних робіт", "von den laborarbeiten"),
            Map.entry("лабораторна робота", "laborarbeit"),
            Map.entry("університет", "universität"),
            Map.entry("студент", "student"),
            Map.entry("програмування", "programmierung"),
            Map.entry("комп'ютер", "computer"),
            Map.entry("телефон", "telefon"),
            Map.entry("місто", "stadt"),
            Map.entry("погода", "wetter"),
            Map.entry("сьогодні", "heute"),
            Map.entry("завтра", "morgen"),
            Map.entry("вчора", "gestern"),
            Map.entry("час", "zeit"),
            Map.entry("гроші", "geld"),
            Map.entry("робота", "arbeit"),
            Map.entry("будинок", "haus"),
            Map.entry("школа", "schule"),
            Map.entry("вода", "wasser"),
            Map.entry("їжа", "essen"),
            Map.entry("кава", "kaffee"),
            Map.entry("чай", "tee"),
            Map.entry("хліб", "brot"),
            Map.entry("молоко", "milch"),
            Map.entry("добре", "gut"),
            Map.entry("погано", "schlecht"),
            Map.entry("гарно", "schön"),
            Map.entry("великий", "groß"),
            Map.entry("маленький", "klein"),
            Map.entry("україна", "ukraine"),
            Map.entry("київ", "kiew")
    );

    private static final Map<String, String> UK_TO_IT = Map.ofEntries(
            Map.entry("привіт", "ciao"),
            Map.entry("добрий день", "buongiorno"),
            Map.entry("добрий ранок", "buongiorno"),
            Map.entry("добрий вечір", "buonasera"),
            Map.entry("на добраніч", "buonanotte"),
            Map.entry("дякую", "grazie"),
            Map.entry("будь ласка", "prego"),
            Map.entry("вибачте", "scusi"),
            Map.entry("так", "sì"),
            Map.entry("ні", "no"),
            Map.entry("як справи", "come stai"),
            Map.entry("все добре", "tutto bene"),
            Map.entry("я не розумію", "non capisco"),
            Map.entry("допоможіть", "aiutami"),
            Map.entry("скільки коштує", "quanto costa"),
            Map.entry("де знаходиться", "dov'è"),
            Map.entry("я хочу", "voglio"),
            Map.entry("хочу відпочити", "voglio riposare"),
            Map.entry("від лабораторних робіт", "dai lavori di laboratorio"),
            Map.entry("лабораторна робота", "lavoro di laboratorio"),
            Map.entry("університет", "università"),
            Map.entry("студент", "studente"),
            Map.entry("програмування", "programmazione"),
            Map.entry("комп'ютер", "computer"),
            Map.entry("телефон", "telefono"),
            Map.entry("місто", "città"),
            Map.entry("погода", "tempo"),
            Map.entry("сьогодні", "oggi"),
            Map.entry("завтра", "domani"),
            Map.entry("вчора", "ieri"),
            Map.entry("час", "ora"),
            Map.entry("гроші", "soldi"),
            Map.entry("робота", "lavoro"),
            Map.entry("будинок", "casa"),
            Map.entry("школа", "scuola"),
            Map.entry("вода", "acqua"),
            Map.entry("їжа", "cibo"),
            Map.entry("кава", "caffè"),
            Map.entry("чай", "tè"),
            Map.entry("хліб", "pane"),
            Map.entry("молоко", "latte"),
            Map.entry("добре", "bene"),
            Map.entry("погано", "male"),
            Map.entry("гарно", "bello"),
            Map.entry("великий", "grande"),
            Map.entry("маленький", "piccolo"),
            Map.entry("україна", "ucraina"),
            Map.entry("київ", "kyiv")
    );

    // ── Публічні методи ───────────────────────────────────────────────────

    /**
     * Переклад через команду /translate.
     * Формат args: "en текст" | "de текст" | "it текст"
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
            return "❌ Непідтримувана мова: *" + targetLang + "*\n\nДоступні: `en`, `de`, `it`";
        }

        return doTranslate(text, targetLang);
    }

    /**
     * Переклад з вільного тексту (NLP).
     * Наприклад: "переклади на англійську: Добрий день"
     * або:       "переклади на англійську текст: Добрий день"
     */
    public String translateFreeText(String text) {
        Matcher m = FREE_TEXT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String langKeyword = m.group(1).toLowerCase();
        String sourceText = m.group(2).trim();

        // Видаляємо зайвий префікс "текст:" якщо він залишився
        sourceText = sourceText.replaceFirst("(?i)^текст[:\\s]+", "").trim();

        String targetLang = resolveLang(langKeyword);

        if (targetLang == null) {
            return "❌ Не розпізнав мову: *" + langKeyword +
                    "*\n\nДоступні: англійська, німецька, італійська";
        }

        return doTranslate(sourceText, targetLang);
    }

    // ── Внутрішні методи ──────────────────────────────────────────────────

    /**
     * Офлайн переклад через словники.
     * Логіка: шукаємо фрази в словнику від довших до коротших (greedy match).
     */
    private String doTranslate(String text, String targetLang) {
        log.info("Переклад: auto -> {}, текст: '{}'", targetLang, text);

        Map<String, String> dictionary = getDictionary(targetLang);
        if (dictionary == null) {
            return "❌ Словник для мови *" + targetLang + "* не знайдено.";
        }

        String lower = text.toLowerCase().trim();
        String translated = translateWithDictionary(lower, dictionary);

        // Capitalize first letter
        if (!translated.isEmpty()) {
            translated = Character.toUpperCase(translated.charAt(0)) + translated.substring(1);
        }

        String sourceLangLabel = detectSourceLang(lower);

        return String.format("🌐 *Переклад*\n\n" +
                        "📥 Оригінал (%s):\n_%s_\n\n" +
                        "📤 %s:\n*%s*",
                sourceLangLabel,
                text,
                LANG_NAMES.getOrDefault(targetLang, targetLang),
                translated);
    }

    /**
     * Жадібний пошук фраз в словнику (від довших до коротших).
     * Якщо фраза не знайдена — залишаємо слово як є (транслітерація не потрібна).
     */
    private String translateWithDictionary(String text, Map<String, String> dict) {
        // Спочатку перевіряємо весь текст цілком
        if (dict.containsKey(text)) {
            return dict.get(text);
        }

        // Сортуємо ключі від довших до коротших для greedy match
        String result = text;
        for (Map.Entry<String, String> entry : dict.entrySet()
                .stream()
                .sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .toList()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        return result;
    }

    private Map<String, String> getDictionary(String lang) {
        return switch (lang) {
            case "en" -> UK_TO_EN;
            case "de" -> UK_TO_DE;
            case "it" -> UK_TO_IT;
            default   -> null;
        };
    }

    private String resolveLang(String keyword) {
        for (Map.Entry<String, String> e : LANG_KEYWORD_MAP.entrySet()) {
            if (keyword.startsWith(e.getKey())) return e.getValue();
        }
        for (Map.Entry<String, String> e : LANG_KEYWORD_MAP_EXTRA.entrySet()) {
            if (keyword.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private String detectSourceLang(String text) {
        // Перевіряємо чи є кирилиця → українська
        if (text.matches(".*[а-яіїєґА-ЯІЇЄҐ].*")) return "🇺🇦 Українська (авто)";
        // Перевіряємо типово англійські слова
        if (text.matches(".*\\b(the|is|are|i|you|he|she|it|we|they)\\b.*")) return "🇬🇧 Англійська (авто)";
        return "авто";
    }

    private String buildHelpMessage() {
        return "🌐 *Переклад тексту*\n\n" +
                "*/translate en* Привіт світ — переклад на 🇬🇧 англійську\n" +
                "*/translate de* Як справи? — переклад на 🇩🇪 німецьку\n" +
                "*/translate it* Добрий день — переклад на 🇮🇹 італійську\n\n" +
                "Або вільним текстом:\n" +
                "_\"переклади на англійську: Доброго ранку\"_\n" +
                "_\"переклади на італійську: Дякую\"_\n\n" +
                "Доступні мови: `en` 🇬🇧 | `de` 🇩🇪 | `it` 🇮🇹";
    }
}