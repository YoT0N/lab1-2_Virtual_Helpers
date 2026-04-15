package edu.ilkiv.telegrambot.service;

import edu.ilkiv.telegrambot.model.LearnedCommand;
import edu.ilkiv.telegrambot.repository.LearnedCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ЛАБ 10 — Natural Language Learning (NLL).
 *
 * Навчання нових команд та адаптація до стилю користувача.
 *
 * Функції:
 * 1. Запам'ятовування нових асоціацій (фраза → намір або системна команда)
 * 2. Пошук збережених команд за подібністю
 * 3. Статистика навчання
 * 4. Адаптивні відповіді (найчастіше використовувані команди)
 *
 * Приклад:
 *   Користувач: "Відкрий браузер"
 *   Після навчання: "Запусти Chrome" → той самий результат
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NllService {

    private final LearnedCommandRepository repository;

    // ──────────────────────────────────────────────────────────────────────
    //  Базові наміри (вбудовані асоціації)
    // ──────────────────────────────────────────────────────────────────────

    /** Вбудовані групи синонімів → канонічний намір */
    private static final Map<String, List<String>> BUILT_IN_SYNONYMS = new LinkedHashMap<>();

    static {
        BUILT_IN_SYNONYMS.put("LAUNCH_BROWSER", Arrays.asList(
                "відкрий браузер", "запусти браузер", "запусти chrome",
                "відкрий chrome", "запусти firefox", "відкрий інтернет",
                "покажи браузер", "open browser", "launch browser", "start chrome"
        ));
        BUILT_IN_SYNONYMS.put("LAUNCH_NOTEPAD", Arrays.asList(
                "відкрий блокнот", "запусти блокнот", "відкрий текстовий редактор",
                "хочу записати", "відкрий notepad", "open notepad", "start notepad"
        ));
        BUILT_IN_SYNONYMS.put("LAUNCH_CALC", Arrays.asList(
                "відкрий калькулятор", "запусти калькулятор",
                "мені треба порахувати", "open calculator", "launch calc"
        ));
        BUILT_IN_SYNONYMS.put("LAUNCH_TERMINAL", Arrays.asList(
                "відкрий термінал", "запусти термінал", "відкрий консоль",
                "відкрий командний рядок", "open terminal", "open console"
        ));
        BUILT_IN_SYNONYMS.put("SYSTEM_INFO", Arrays.asList(
                "системна інформація", "покажи систему", "інфо про систему",
                "скільки пам'яті", "ресурси комп'ютера", "system info", "sysinfo"
        ));
        BUILT_IN_SYNONYMS.put("NETWORK_INFO", Arrays.asList(
                "мережа", "мій ip", "мережева інформація", "wi-fi", "wifi",
                "показати ip", "network info", "my ip", "ip address"
        ));
        BUILT_IN_SYNONYMS.put("CREATE_NOTE", Arrays.asList(
                "створи нотатку", "запиши", "зроби нотатку", "нотатка",
                "збережи текст", "create note", "save note", "write note"
        ));
        BUILT_IN_SYNONYMS.put("LIST_NOTES", Arrays.asList(
                "мої нотатки", "список нотаток", "покажи нотатки",
                "які є нотатки", "show notes", "list notes", "my notes"
        ));
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Розпізнавання намірів
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Розпізнає намір із тексту.
     * Спочатку перевіряє навчені команди (БД), потім вбудовані синоніми.
     *
     * @return Intent або null якщо не розпізнано
     */
    public NllResult recognize(long chatId, String text) {
        if (text == null || text.isBlank()) return null;

        String lower = text.toLowerCase().trim();

        // 1. Перевіряємо персональні навчені команди (вищий пріоритет)
        NllResult personalResult = matchPersonalCommands(chatId, lower);
        if (personalResult != null) {
            log.info("NLL: знайдено персональну команду для '{}': {}", lower, personalResult.getIntent());
            // Збільшуємо лічильник використання
            repository.findByChatIdAndPhraseIgnoreCase(chatId, lower)
                    .ifPresent(cmd -> {
                        cmd.setUsageCount(cmd.getUsageCount() + 1);
                        repository.save(cmd);
                    });
            return personalResult;
        }

        // 2. Перевіряємо вбудовані синоніми
        NllResult builtInResult = matchBuiltInSynonyms(lower);
        if (builtInResult != null) {
            log.info("NLL: вбудована асоціація для '{}': {}", lower, builtInResult.getIntent());
            return builtInResult;
        }

        // 3. Нечіткий пошук (fuzzy matching) серед навчених команд
        return fuzzyMatch(chatId, lower);
    }

    private NllResult matchPersonalCommands(long chatId, String text) {
        // Точний збіг
        Optional<LearnedCommand> exact = repository.findByChatIdAndPhraseIgnoreCase(chatId, text);
        if (exact.isPresent()) {
            return NllResult.of(exact.get().getIntent(), exact.get().getAction());
        }

        // Пошук серед навчених фраз (contains)
        List<LearnedCommand> commands = repository.findByChatId(chatId);
        for (LearnedCommand cmd : commands) {
            if (text.contains(cmd.getPhrase().toLowerCase()) ||
                    cmd.getPhrase().toLowerCase().contains(text)) {
                return NllResult.of(cmd.getIntent(), cmd.getAction());
            }
        }
        return null;
    }

    private NllResult matchBuiltInSynonyms(String text) {
        for (Map.Entry<String, List<String>> entry : BUILT_IN_SYNONYMS.entrySet()) {
            for (String synonym : entry.getValue()) {
                if (text.contains(synonym) || synonym.contains(text)) {
                    return NllResult.of(entry.getKey(), null);
                }
            }
        }
        return null;
    }

    /**
     * Нечіткий збіг за відстанню Левенштейна (для коротких фраз).
     */
    private NllResult fuzzyMatch(long chatId, String text) {
        List<LearnedCommand> commands = repository.findByChatId(chatId);
        if (commands.isEmpty()) return null;

        LearnedCommand best = null;
        int bestDist = Integer.MAX_VALUE;

        for (LearnedCommand cmd : commands) {
            int dist = levenshtein(text, cmd.getPhrase().toLowerCase());
            // Поріг: не більше 30% від довжини рядка
            int threshold = Math.max(3, text.length() / 3);
            if (dist < bestDist && dist <= threshold) {
                bestDist = dist;
                best = cmd;
            }
        }

        if (best != null) {
            log.info("NLL fuzzy: '{}' ≈ '{}' (dist={})", text, best.getPhrase(), bestDist);
            return NllResult.of(best.getIntent(), best.getAction());
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Навчання
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Зберігає нову асоціацію: фраза → намір + дія.
     *
     * Приклад:
     * /learn запусти хром → LAUNCH_BROWSER
     * /learn відкрий ютуб → OPEN_URL https://youtube.com
     */
    public String learn(long chatId, String args) {
        if (args == null || args.isBlank()) {
            return """
                    📚 *Навчання помічника:*
                    
                    `/learn <фраза> → <намір>`
                    
                    *Приклади:*
                    `/learn запусти хром → LAUNCH_BROWSER`
                    `/learn мій робочий стіл → LAUNCH_FILES`
                    `/learn нотатка → CREATE_NOTE`
                    
                    *Доступні наміри:*
                    `LAUNCH_BROWSER`, `LAUNCH_NOTEPAD`, `LAUNCH_CALC`,
                    `LAUNCH_TERMINAL`, `LAUNCH_FILES`, `SYSTEM_INFO`,
                    `NETWORK_INFO`, `CREATE_NOTE`, `LIST_NOTES`
                    
                    `/mycommands` — переглянути навчені команди""";
        }

        // Парсимо: "фраза → намір" або "фраза -> намір"
        String[] parts = args.split("→|->", 2);
        if (parts.length != 2) {
            return "❓ Формат: `/learn фраза → НАМІР`\n\nПриклад: `/learn відкрий хром → LAUNCH_BROWSER`";
        }

        String phrase  = parts[0].trim().toLowerCase();
        String intent  = parts[1].trim().toUpperCase();
        String action  = null;

        // Перевіряємо чи є дія після наміру (напр. OPEN_URL https://...)
        String[] intentParts = intent.split("\\s+", 2);
        if (intentParts.length == 2) {
            intent = intentParts[0];
            action = intentParts[1];
        }

        if (phrase.isEmpty() || phrase.length() > 200) {
            return "❌ Фраза має бути від 1 до 200 символів.";
        }

        // Перевіряємо чи вже існує
        Optional<LearnedCommand> existing = repository.findByChatIdAndPhraseIgnoreCase(chatId, phrase);
        if (existing.isPresent()) {
            LearnedCommand cmd = existing.get();
            cmd.setIntent(intent);
            cmd.setAction(action);
            repository.save(cmd);
            return "🔄 *Асоціацію оновлено*\n\n" +
                    "📝 Фраза: `" + phrase + "`\n" +
                    "🎯 Намір: `" + intent + "`";
        }

        LearnedCommand cmd = LearnedCommand.builder()
                .chatId(chatId)
                .phrase(phrase)
                .intent(intent)
                .action(action)
                .build();
        repository.save(cmd);

        log.info("NLL: навчено '{}' → {} для chatId={}", phrase, intent, chatId);

        return "✅ *Навчено нову команду!*\n\n" +
                "📝 Фраза: `" + phrase + "`\n" +
                "🎯 Намір: `" + intent + "`\n\n" +
                "Тепер коли ви напишете _\"" + phrase + "\"_, я буду знати що ви маєте на увазі!";
    }

    /**
     * Показує список навчених команд користувача.
     */
    public String listCommands(long chatId) {
        List<LearnedCommand> commands = repository.findByChatIdOrderByUsageCountDesc(chatId);

        if (commands.isEmpty()) {
            return "📭 У вас немає навчених команд.\n\n" +
                    "Навчити: `/learn запусти хром → LAUNCH_BROWSER`";
        }

        StringBuilder sb = new StringBuilder("📚 *Ваші навчені команди:*\n\n");
        for (int i = 0; i < Math.min(commands.size(), 20); i++) {
            LearnedCommand cmd = commands.get(i);
            sb.append(String.format("• `%s` → *%s*",
                    cmd.getPhrase(), cmd.getIntent()));
            if (cmd.getUsageCount() > 0) {
                sb.append(" _(×").append(cmd.getUsageCount()).append(")_");
            }
            sb.append("\n");
        }

        if (commands.size() > 20) {
            sb.append("\n_...та ще ").append(commands.size() - 20).append(" команд_");
        }

        sb.append("\n\n🗑 Видалити: `/forgetcmd <фраза>`");
        return sb.toString();
    }

    /**
     * Видаляє навчену команду.
     */
    public String forgetCommand(long chatId, String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return "❓ Вкажіть фразу: `/forgetcmd запусти хром`";
        }

        Optional<LearnedCommand> cmd = repository.findByChatIdAndPhraseIgnoreCase(chatId, phrase.trim().toLowerCase());
        if (cmd.isEmpty()) {
            return "❌ Команду `" + phrase + "` не знайдено.";
        }

        repository.delete(cmd.get());
        return "🗑 Команду `" + phrase + "` видалено.";
    }

    /**
     * Повертає статистику NLL.
     */
    public String getStats(long chatId) {
        List<LearnedCommand> commands = repository.findByChatId(chatId);
        long totalUses = commands.stream().mapToLong(LearnedCommand::getUsageCount).sum();

        List<LearnedCommand> topCommands = commands.stream()
                .filter(c -> c.getUsageCount() > 0)
                .sorted(Comparator.comparingLong(LearnedCommand::getUsageCount).reversed())
                .limit(5)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder("🧠 *Статистика навчання (NLL):*\n\n");
        sb.append("📚 Навчено команд: *").append(commands.size()).append("*\n");
        sb.append("🔄 Всього використань: *").append(totalUses).append("*\n");
        sb.append("📖 Вбудованих синонімів: *").append(
                BUILT_IN_SYNONYMS.values().stream().mapToLong(List::size).sum()).append("*\n\n");

        if (!topCommands.isEmpty()) {
            sb.append("🏆 *Найчастіші команди:*\n");
            for (LearnedCommand cmd : topCommands) {
                sb.append(String.format("• `%s` (×%d)\n",
                        cmd.getPhrase(), cmd.getUsageCount()));
            }
        }

        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Алгоритм Левенштейна
    // ──────────────────────────────────────────────────────────────────────

    private int levenshtein(String a, String b) {
        int la = a.length(), lb = b.length();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1,
                        Math.min(dp[i][j - 1] + 1,
                                dp[i - 1][j - 1] + cost));
            }
        }
        return dp[la][lb];
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Результат розпізнавання
    // ──────────────────────────────────────────────────────────────────────

    public static class NllResult {
        private final String intent;
        private final String action; // необов'язкова додаткова дія (URL, параметр)

        private NllResult(String intent, String action) {
            this.intent = intent;
            this.action = action;
        }

        public static NllResult of(String intent, String action) {
            return new NllResult(intent, action);
        }

        public String getIntent() { return intent; }
        public String getAction() { return action; }
    }
}