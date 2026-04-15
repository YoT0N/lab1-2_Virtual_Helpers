package edu.ilkiv.telegrambot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * ЛАБ 10 — Локальне керування системою.
 *
 * Підтримувані операції:
 * - Запуск програм (notepad, calculator, browser)
 * - Керування файлами (нотатки, документи)
 * - Системні дії (гучність, Wi-Fi інформація)
 * - Кросплатформний (Windows / Linux / macOS)
 */
@Slf4j
@Service
public class SystemControlService {

    private static final String NOTES_DIR = "./notes";
    private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Визначаємо ОС один раз */
    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS.contains("win");
    private static final boolean IS_MAC     = OS.contains("mac");
    private static final boolean IS_LINUX   = OS.contains("nux") || OS.contains("nix");

    // ──────────────────────────────────────────────────────────────────────
    //  Запуск програм
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Запускає програму за назвою або псевдонімом.
     * Підтримує: notepad, calculator, browser, terminal, explorer
     */
    public String launchApp(String appName) {
        if (appName == null || appName.isBlank()) {
            return "❓ Вкажіть програму: `/launch browser`";
        }

        String app = appName.toLowerCase().trim();
        log.info("Запуск програми: '{}'", app);

        try {
            String[] command = resolveCommand(app);
            if (command == null) {
                return "❓ Не знаю програму: *" + appName + "*\n\n" +
                        "Відомі: `browser`, `notepad`, `calculator`, `terminal`, `explorer`, `files`";
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            pb.start();

            String displayName = getAppDisplayName(app);
            return "🚀 Запускаю: *" + displayName + "*";

        } catch (IOException e) {
            log.error("Помилка запуску '{}': {}", app, e.getMessage());
            return "❌ Не вдалося запустити *" + appName + "*: " + e.getMessage();
        }
    }

    private String[] resolveCommand(String app) {
        if (IS_WINDOWS) {
            return switch (app) {
                case "notepad", "блокнот", "текстовий редактор" -> new String[]{"notepad.exe"};
                case "calculator", "calc", "калькулятор"        -> new String[]{"calc.exe"};
                case "browser", "chrome", "браузер"             -> new String[]{"cmd", "/c", "start", "chrome"};
                case "firefox"                                  -> new String[]{"cmd", "/c", "start", "firefox"};
                case "terminal", "cmd", "термінал"              -> new String[]{"cmd.exe"};
                case "explorer", "files", "файли"               -> new String[]{"explorer.exe"};
                case "paint", "малювання"                       -> new String[]{"mspaint.exe"};
                case "wordpad"                                  -> new String[]{"wordpad.exe"};
                default -> null;
            };
        } else if (IS_MAC) {
            return switch (app) {
                case "notepad", "textedit", "текстовий редактор" -> new String[]{"open", "-a", "TextEdit"};
                case "calculator", "calc", "калькулятор"          -> new String[]{"open", "-a", "Calculator"};
                case "browser", "safari", "браузер"               -> new String[]{"open", "-a", "Safari"};
                case "chrome"                                      -> new String[]{"open", "-a", "Google Chrome"};
                case "firefox"                                     -> new String[]{"open", "-a", "Firefox"};
                case "terminal", "термінал"                        -> new String[]{"open", "-a", "Terminal"};
                case "files", "finder", "файли"                   -> new String[]{"open", "-a", "Finder"};
                default -> null;
            };
        } else { // Linux
            return switch (app) {
                case "notepad", "gedit", "текстовий редактор"   -> resolveLinuxEditor();
                case "calculator", "calc", "калькулятор"        -> resolveLinuxCalc();
                case "browser", "браузер"                       -> resolveLinuxBrowser();
                case "firefox"                                  -> new String[]{"firefox"};
                case "chrome", "chromium"                       -> new String[]{"chromium-browser"};
                case "terminal", "термінал"                     -> resolveLinuxTerminal();
                case "files", "файли"                           -> new String[]{"xdg-open", System.getProperty("user.home")};
                default -> null;
            };
        }
    }

    private String[] resolveLinuxEditor() {
        for (String ed : new String[]{"gedit", "kate", "mousepad", "xed", "nano"}) {
            if (commandExists(ed)) return new String[]{ed};
        }
        return new String[]{"xdg-open", "."};
    }

    private String[] resolveLinuxCalc() {
        for (String calc : new String[]{"gnome-calculator", "kcalc", "galculator", "bc"}) {
            if (commandExists(calc)) return new String[]{calc};
        }
        return null;
    }

    private String[] resolveLinuxBrowser() {
        for (String br : new String[]{"firefox", "chromium-browser", "google-chrome", "xdg-open"}) {
            if (commandExists(br)) return new String[]{br, "about:blank"};
        }
        return new String[]{"xdg-open", "https://google.com"};
    }

    private String[] resolveLinuxTerminal() {
        for (String t : new String[]{"gnome-terminal", "konsole", "xterm", "xfce4-terminal"}) {
            if (commandExists(t)) return new String[]{t};
        }
        return new String[]{"xterm"};
    }

    private String getAppDisplayName(String app) {
        return switch (app) {
            case "browser", "chrome", "firefox", "браузер" -> "Браузер";
            case "notepad", "блокнот"   -> "Текстовий редактор";
            case "calculator", "calc", "калькулятор" -> "Калькулятор";
            case "terminal", "термінал" -> "Термінал";
            case "files", "explorer", "файли" -> "Файловий менеджер";
            default -> app;
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Нотатки
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Створює нову нотатку у папці ./notes/
     */
    public String createNote(long chatId, String content) {
        if (content == null || content.isBlank()) {
            return "❓ Вкажіть текст нотатки: `/note Текст нотатки`";
        }

        try {
            Path notesPath = Paths.get(NOTES_DIR, String.valueOf(chatId));
            Files.createDirectories(notesPath);

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "note_" + timestamp + ".txt";
            Path notePath = notesPath.resolve(fileName);

            String noteContent = "=== Нотатка ===\n" +
                    "Дата: " + LocalDateTime.now().format(DT_FORMAT) + "\n\n" +
                    content;

            Files.writeString(notePath, noteContent);
            log.info("Нотатку збережено: {}", notePath);

            return "📝 *Нотатку збережено*\n\n" +
                    "📁 Файл: `" + fileName + "`\n" +
                    "📄 Вміст: " + content.substring(0, Math.min(100, content.length())) +
                    (content.length() > 100 ? "..." : "");

        } catch (Exception e) {
            log.error("Помилка збереження нотатки: {}", e.getMessage());
            return "❌ Не вдалося зберегти нотатку: " + e.getMessage();
        }
    }

    /**
     * Показує список нотаток користувача.
     */
    public String listNotes(long chatId) {
        try {
            Path notesPath = Paths.get(NOTES_DIR, String.valueOf(chatId));
            if (!Files.exists(notesPath)) {
                return "📭 У вас ще немає нотаток.\n\nДодати: `/note Текст нотатки`";
            }

            File[] files = notesPath.toFile().listFiles(
                    f -> f.getName().endsWith(".txt"));

            if (files == null || files.length == 0) {
                return "📭 Нотаток не знайдено.\n\nДодати: `/note Текст нотатки`";
            }

            Arrays.sort(files, Comparator.comparing(File::lastModified).reversed());

            StringBuilder sb = new StringBuilder("📋 *Ваші нотатки:*\n\n");
            int count = Math.min(files.length, 10);
            for (int i = 0; i < count; i++) {
                File f = files[i];
                String name = f.getName().replace("note_", "").replace(".txt", "");
                sb.append(String.format("%d. `%s`\n", i + 1, name));
            }

            if (files.length > 10) {
                sb.append("\n_...та ще ").append(files.length - 10).append(" нотаток_");
            }

            sb.append("\n\n📖 Читати: `/readnote <номер>`\n");
            sb.append("🗑 Видалити: `/delnote <номер>`");

            return sb.toString();

        } catch (Exception e) {
            log.error("Помилка читання нотаток: {}", e.getMessage());
            return "❌ Помилка: " + e.getMessage();
        }
    }

    /**
     * Читає вміст нотатки за номером.
     */
    public String readNote(long chatId, String indexStr) {
        try {
            int index = Integer.parseInt(indexStr.trim()) - 1;
            Path notesPath = Paths.get(NOTES_DIR, String.valueOf(chatId));
            File[] files = getSortedNotes(notesPath);

            if (files == null || index < 0 || index >= files.length) {
                return "❌ Нотатку #" + (index + 1) + " не знайдено.";
            }

            String content = Files.readString(files[index].toPath());
            return "📖 *Нотатка #" + (index + 1) + "*\n\n" + content;

        } catch (NumberFormatException e) {
            return "❌ Вкажіть номер: `/readnote 1`";
        } catch (Exception e) {
            log.error("Помилка читання нотатки: {}", e.getMessage());
            return "❌ Помилка: " + e.getMessage();
        }
    }

    /**
     * Видаляє нотатку за номером.
     */
    public String deleteNote(long chatId, String indexStr) {
        try {
            int index = Integer.parseInt(indexStr.trim()) - 1;
            Path notesPath = Paths.get(NOTES_DIR, String.valueOf(chatId));
            File[] files = getSortedNotes(notesPath);

            if (files == null || index < 0 || index >= files.length) {
                return "❌ Нотатку #" + (index + 1) + " не знайдено.";
            }

            String name = files[index].getName();
            Files.delete(files[index].toPath());
            return "✅ Нотатку *" + name + "* видалено.";

        } catch (NumberFormatException e) {
            return "❌ Вкажіть номер: `/delnote 1`";
        } catch (Exception e) {
            return "❌ Помилка: " + e.getMessage();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Системна інформація
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Повертає системну інформацію (CPU, RAM, ОС).
     */
    public String getSystemInfo() {
        Runtime runtime = Runtime.getRuntime();
        long totalMem = runtime.totalMemory() / (1024 * 1024);
        long freeMem  = runtime.freeMemory()  / (1024 * 1024);
        long usedMem  = totalMem - freeMem;
        int  cpuCount = runtime.availableProcessors();

        String osName    = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String javaVer   = System.getProperty("java.version");
        String userName  = System.getProperty("user.name");

        return String.format(
                "💻 *Системна інформація*\n\n" +
                        "🖥 ОС: *%s %s*\n" +
                        "👤 Користувач: *%s*\n" +
                        "☕ Java: *%s*\n\n" +
                        "🧮 CPU: *%d ядер*\n" +
                        "🗄 RAM: *%d MB / %d MB* (використано/всього)\n" +
                        "📊 Вільно RAM: *%d MB*\n\n" +
                        "📁 Робоча папка: `%s`",
                osName, osVersion,
                userName,
                javaVer,
                cpuCount,
                usedMem, totalMem,
                freeMem,
                System.getProperty("user.dir")
        );
    }

    /**
     * Керування гучністю (тільки Linux/amixer).
     */
    public String setVolume(String levelStr) {
        try {
            int level = Integer.parseInt(levelStr.trim());
            if (level < 0 || level > 100) return "❌ Рівень гучності: 0-100";

            if (IS_LINUX) {
                runCommand("amixer", "set", "Master", level + "%");
                return "🔊 Гучність встановлено: *" + level + "%*";
            } else if (IS_WINDOWS) {
                // Windows PowerShell
                String script = "$wshShell = New-Object -ComObject wscript.shell; " +
                        "[audio.mastervolume]::Volume = " + (level / 100.0);
                runCommand("powershell", "-Command", script);
                return "🔊 Гучність встановлено: *" + level + "%*";
            } else {
                return "⚠️ Керування гучністю не підтримується на " + OS;
            }
        } catch (NumberFormatException e) {
            return "❌ Вкажіть рівень 0-100: `/volume 50`";
        } catch (Exception e) {
            return "❌ Помилка: " + e.getMessage();
        }
    }

    /**
     * Показує мережеву інформацію.
     */
    public String getNetworkInfo() {
        try {
            StringBuilder sb = new StringBuilder("🌐 *Мережева інформація*\n\n");

            if (IS_LINUX || IS_MAC) {
                String result = runCommandOutput("hostname", "-I");
                if (result != null) {
                    sb.append("📡 IP адреси: `").append(result.trim()).append("`\n");
                }

                String hostname = runCommandOutput("hostname");
                if (hostname != null) {
                    sb.append("🖥 Hostname: `").append(hostname.trim()).append("`\n");
                }
            } else if (IS_WINDOWS) {
                String result = runCommandOutput("cmd", "/c", "ipconfig");
                if (result != null) {
                    // Витягуємо тільки IPv4
                    Pattern p = Pattern.compile("IPv4.+?:\\s*([\\d.]+)");
                    Matcher m = p.matcher(result);
                    while (m.find()) {
                        sb.append("📡 IP: `").append(m.group(1)).append("`\n");
                    }
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("Помилка отримання мережевої інформації: {}", e.getMessage());
            return "❌ Помилка: " + e.getMessage();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Утиліти
    // ──────────────────────────────────────────────────────────────────────

    private boolean commandExists(String cmd) {
        try {
            String whichCmd = IS_WINDOWS ? "where" : "which";
            Process p = new ProcessBuilder(whichCmd, cmd)
                    .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void runCommand(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            br.lines().forEach(l -> log.debug("cmd: {}", l));
        }
        proc.waitFor();
    }

    private String runCommandOutput(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                output = br.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            proc.waitFor();
            return output;
        } catch (Exception e) {
            log.warn("Помилка виконання команди: {}", e.getMessage());
            return null;
        }
    }

    private File[] getSortedNotes(Path notesPath) {
        if (!Files.exists(notesPath)) return null;
        File[] files = notesPath.toFile().listFiles(f -> f.getName().endsWith(".txt"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::lastModified).reversed());
        }
        return files;
    }
}