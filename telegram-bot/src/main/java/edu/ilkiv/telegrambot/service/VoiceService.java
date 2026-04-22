package edu.ilkiv.telegrambot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/**
 * ЛАБ 9 — Голосовий помічник.
 *
 * STT (Speech-to-Text): Vosk (офлайн) через vosk-transcriber CLI
 * TTS (Text-to-Speech): eSpeak-NG через системний виклик
 *
 * Vosk: https://alphacephei.com/vosk/
 * eSpeak-NG: https://espeak.sourceforge.net/
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceService {

    @Value("${voice.stt.model.path:./vosk-model-uk}")
    private String voskModelPath;

    @Value("${voice.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${voice.espeak.path:espeak-ng}")
    private String espeakPath;

    // ВИПРАВЛЕННЯ 1: Змінено дефолт з "uk" на "en" — eSpeak-NG "uk" звучить
    // нерозбірливо на більшості систем без встановленого mbrola.
    // Якщо хочете справжній український голос — встановіть mbrola + mb-uk1
    // і змініть на "mb-uk1" в application.properties (voice.tts.lang=mb-uk1)
    @Value("${voice.tts.lang:en}")
    private String ttsLang;

    @Value("${voice.tts.speed:150}")
    private int ttsSpeed;

    @Value("${voice.tmp.dir:./voice_tmp}")
    private String tmpDir;

    // ──────────────────────────────────────────────────────────────────────
    //  STT — перетворення аудіо → текст через Vosk
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Розпізнає мовлення з OGG/OGG-Opus файлу (формат Telegram).
     * Повертає розпізнаний текст або null у разі помилки.
     *
     * Алгоритм:
     * 1. Конвертуємо OGG → WAV (16 кГц, моно) через ffmpeg
     * 2. Запускаємо vosk-transcriber на WAV файлі
     * 3. Читаємо результат і повертаємо
     */
    public String speechToText(byte[] oggBytes) {
        Path tmpPath = Paths.get(tmpDir);
        String uid = UUID.randomUUID().toString().substring(0, 8);
        Path oggFile = tmpPath.resolve(uid + ".ogg");
        Path wavFile = tmpPath.resolve(uid + ".wav");

        try {
            Files.createDirectories(tmpPath);
            Files.write(oggFile, oggBytes);

            // Крок 1: OGG → WAV
            if (!convertOggToWav(oggFile, wavFile)) {
                log.error("Не вдалося конвертувати OGG → WAV");
                return null;
            }

            // Крок 2: Vosk STT
            return runVoskTranscriber(wavFile);

        } catch (Exception e) {
            log.error("Помилка STT: {}", e.getMessage(), e);
            return null;
        } finally {
            // Очищення тимчасових файлів
            silentDelete(oggFile);
            silentDelete(wavFile);
        }
    }

    /**
     * Конвертує OGG (Opus) у WAV 16 кГц моно — формат для Vosk.
     * Потребує встановленого ffmpeg.
     */
    private boolean convertOggToWav(Path ogg, Path wav) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath, "-y",
                "-i", ogg.toAbsolutePath().toString(),
                "-ar", "16000",
                "-ac", "1",
                "-f", "wav",
                wav.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            br.lines().forEach(line -> log.debug("ffmpeg: {}", line));
        }

        int exit = proc.waitFor();
        if (exit != 0) {
            log.warn("ffmpeg завершився з кодом {}", exit);
            return false;
        }
        return true;
    }

    /**
     * Запускає vosk-transcriber і повертає розпізнаний текст.
     * ВИПРАВЛЕННЯ: Встановлюємо PYTHONIOENCODING=utf-8 щоб уникнути
     * проблем з кодуванням CP1251 на Windows.
     */
    private String runVoskTranscriber(Path wavFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "vosk-transcriber",
                "--model", voskModelPath,
                "--input", wavFile.toAbsolutePath().toString(),
                "--output-type", "txt"
        );

        // КЛЮЧОВЕ ВИПРАВЛЕННЯ: змушуємо Python виводити UTF-8 на Windows
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");

        pb.redirectErrorStream(false);
        Process proc = pb.start();

        StringBuilder result = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                result.append(line).append(" ");
            }
        }

        int exit = proc.waitFor();
        String text = result.toString().trim();
        log.info("Vosk STT результат (exit={}): '{}'", exit, text);

        return text.isBlank() ? null : text;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  TTS — перетворення тексту → аудіо через eSpeak-NG
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Синтезує мовлення з тексту через eSpeak-NG.
     * Повертає WAV байти або null у разі помилки.
     *
     * ВИПРАВЛЕННЯ: Використовуємо покращений stripMarkdown який видаляє
     * весь Markdown, emoji та спецсимволи перед синтезом.
     */
    public byte[] textToSpeech(String text) {
        if (text == null || text.isBlank()) return null;

        // ВИПРАВЛЕННЯ 2: Використовуємо покращений stripMarkdown
        String cleanText = stripMarkdown(text);
        if (cleanText.isBlank()) return null;

        // Обмежуємо довжину — eSpeak може зависнути на дуже довгому тексті
        if (cleanText.length() > 500) {
            cleanText = cleanText.substring(0, 500);
            log.info("TTS: текст обрізано до 500 символів");
        }

        log.info("TTS: синтез тексту (lang={}): '{}'", ttsLang,
                cleanText.substring(0, Math.min(80, cleanText.length())));

        Path tmpPath = Paths.get(tmpDir);
        String uid = UUID.randomUUID().toString().substring(0, 8);
        Path wavFile = tmpPath.resolve(uid + "_tts.wav");

        try {
            Files.createDirectories(tmpPath);

            ProcessBuilder pb = new ProcessBuilder(
                    espeakPath,
                    "-v", ttsLang,
                    "-s", String.valueOf(ttsSpeed),
                    "-w", wavFile.toAbsolutePath().toString(),
                    cleanText
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                br.lines().forEach(line -> log.debug("espeak: {}", line));
            }

            int exit = proc.waitFor();
            if (exit != 0) {
                log.error("eSpeak-NG завершився з кодом {}", exit);
                return null;
            }

            if (!Files.exists(wavFile)) {
                log.error("eSpeak не створив WAV файл");
                return null;
            }

            byte[] wavBytes = Files.readAllBytes(wavFile);
            log.info("TTS: синтезовано {} байт", wavBytes.length);
            return wavBytes;

        } catch (Exception e) {
            log.error("Помилка TTS: {}", e.getMessage(), e);
            return null;
        } finally {
            silentDelete(wavFile);
        }
    }

    /**
     * Конвертує WAV → OGG Opus для відправки в Telegram як voice message.
     * Telegram Voice вимагає OGG/Opus формат.
     */
    public byte[] wavToOgg(byte[] wavBytes) {
        if (wavBytes == null) return null;

        Path tmpPath = Paths.get(tmpDir);
        String uid = UUID.randomUUID().toString().substring(0, 8);
        Path wavFile = tmpPath.resolve(uid + "_in.wav");
        Path oggFile = tmpPath.resolve(uid + "_out.ogg");

        try {
            Files.createDirectories(tmpPath);
            Files.write(wavFile, wavBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y",
                    "-i", wavFile.toAbsolutePath().toString(),
                    "-c:a", "libopus",
                    "-b:a", "32k",
                    oggFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                br.lines().forEach(line -> log.debug("ffmpeg OGG: {}", line));
            }
            proc.waitFor();

            if (!Files.exists(oggFile)) return null;
            return Files.readAllBytes(oggFile);

        } catch (Exception e) {
            log.error("Помилка WAV→OGG: {}", e.getMessage(), e);
            return null;
        } finally {
            silentDelete(wavFile);
            silentDelete(oggFile);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Утиліти
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Перевіряє наявність необхідних системних утиліт.
     */
    public String checkDependencies() {
        StringBuilder sb = new StringBuilder("🎙 *Залежності голосового модуля:*\n\n");
        sb.append(checkCommand(ffmpegPath, "-version")    ? "✅" : "❌").append(" ffmpeg\n");
        sb.append(checkCommand(espeakPath, "--version")   ? "✅" : "❌").append(" eSpeak-NG (TTS)\n");
        sb.append(checkCommand("vosk-transcriber", null)  ? "✅" : "❌").append(" Vosk Transcriber (STT)\n");

        boolean modelExists = Files.exists(Paths.get(voskModelPath));
        sb.append(modelExists ? "✅" : "❌").append(" Vosk модель (").append(voskModelPath).append(")\n");

        sb.append("\n🔊 TTS мова: `").append(ttsLang).append("`\n");
        sb.append("🔊 TTS швидкість: `").append(ttsSpeed).append("` слів/хв\n");

        if (!modelExists) {
            sb.append("\n💡 Завантажте українську модель:\n");
            sb.append("`wget https://alphacephei.com/vosk/models/vosk-model-uk-v3.zip`\n");
            sb.append("`unzip vosk-model-uk-v3.zip -d vosk-model-uk`");
        }

        // Підказка про голос
        if ("uk".equals(ttsLang)) {
            sb.append("\n\n⚠️ Голос `uk` потребує mbrola для кращої якості.\n");
            sb.append("Рекомендовано: встановіть `mbrola` і `mb-uk1`,\n");
            sb.append("потім змініть `voice.tts.lang=mb-uk1` в application.properties.\n");
            sb.append("Або використайте `voice.tts.lang=en` для чіткого англійського голосу.");
        }

        return sb.toString();
    }

    private boolean checkCommand(String cmd, String arg) {
        try {
            ProcessBuilder pb = arg != null
                    ? new ProcessBuilder(cmd, arg)
                    : new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                br.lines().forEach(l -> {});
            }
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ВИПРАВЛЕННЯ 2: Покращений stripMarkdown.
     *
     * Проблема: старий метод не видаляв всі emoji та деякі Markdown конструкції,
     * через що eSpeak намагався буквально вимовляти "*", "🌤", "/" тощо,
     * що робило голос нерозбірливим і "дивним".
     *
     * Тепер:
     * - Видаляє весь Unicode emoji блоком (діапазон \uD83C–\uDBFF + \uDC00–\uDFFF)
     * - Видаляє Markdown: **bold**, *italic*, _italic_, `code`, # headers
     * - Видаляє слеші команд (/weather → weather)
     * - Видаляє URL-и
     * - Видаляє зайві пробіли та пунктуацію що залишилась
     */
    public String stripMarkdown(String text) {
        if (text == null) return "";

        String result = text
                // Видаляємо емодзі (surrogate pairs)
                .replaceAll("[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]", "")
                // Видаляємо емодзі (BMP блоки)
                .replaceAll("[\\u2600-\\u27BF]", "")
                .replaceAll("[\\u2300-\\u23FF]", "")
                .replaceAll("[\\u2B00-\\u2BFF]", "")
                // Markdown
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("_([^_]+)_", "$1")
                .replaceAll("`[^`]+`", "")
                .replaceAll("```[\\s\\S]*?```", "")
                // URL
                .replaceAll("https?://\\S+", "")
                // /команди
                .replaceAll("(?<![\\w])/\\w+", " ")
                // Спецсимволи → пробіл
                .replaceAll("[|•→←↑↓►◄▲▼*#@_]", " ")
                // Замінюємо одиниці виміру на вимовні англійські
                .replaceAll("([\\d.]+)\\s*°C", "$1 degrees Celsius")
                .replaceAll("([\\d.]+)\\s*°", "$1 degrees")
                .replaceAll("([\\d]+)\\s*%", "$1 percent")
                .replaceAll("([\\d.]+)\\s*м/с", "$1 meters per second")
                // Рядки виду "USD -> 1.0000"
                .replaceAll("[A-Z]{2,5}\\s*->\\s*[\\d.]+", "")
                // Кирилицю транслітеруємо в латиницю
                .replaceAll("(?m)^[^a-zA-Zа-яіїєґА-ЯІЇЄҐ0-9]+$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();

        // Транслітерація кирилиці → латиниця для eSpeak en
        return transliterate(result);
    }

    private String transliterate(String text) {
        if (text == null) return "";

        // Таблиця транслітерації українська → англійська вимова
        String[][] table = {
                {"погода", "weather"},
                {"температура", "temperature"},
                {"відчувається", "feels like"},
                {"вологість", "humidity"},
                {"вітер", "wind"},
                {"рвані хмари", "broken clouds"},
                {"невеликі хмари", "few clouds"},
                {"ясно", "clear sky"},
                {"хмарно", "cloudy"},
                {"дощ", "rain"},
                {"сніг", "snow"},
                {"туман", "fog"},
                {"буря", "storm"},
                {"курс", "exchange rate"},
                {"конвертація", "conversion"},
                {"нагадування", "reminder"},
                {"подію додано", "event added"},
                {"нотатку збережено", "note saved"},
                {"системна інформація", "system information"},
                {"мережева інформація", "network information"},
                {"гучність встановлено", "volume set"},
                {"запускаю", "launching"},
                {"браузер", "browser"},
                {"калькулятор", "calculator"},
                {"термінал", "terminal"},
                {"привіт", "hello"},
                {"дякую", "thank you"},
                {"так", "yes"},
                {"ні", "no"},
                {"помилка", "error"},
                {"успішно", "success"},
                // Решта кирилиці — побуквена транслітерація
        };

        String result = text.toLowerCase();
        for (String[] pair : table) {
            result = result.replace(pair[0], pair[1]);
        }

        // Побуквена транслітерація для решти кирилиці
        result = cyrillicToLatin(result);

        return result;
    }

    private String cyrillicToLatin(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(switch (c) {
                case 'а' -> "a";  case 'б' -> "b";  case 'в' -> "v";
                case 'г' -> "h";  case 'ґ' -> "g";  case 'д' -> "d";
                case 'е' -> "e";  case 'є' -> "ye"; case 'ж' -> "zh";
                case 'з' -> "z";  case 'и' -> "y";  case 'і' -> "i";
                case 'ї' -> "yi"; case 'й' -> "y";  case 'к' -> "k";
                case 'л' -> "l";  case 'м' -> "m";  case 'н' -> "n";
                case 'о' -> "o";  case 'п' -> "p";  case 'р' -> "r";
                case 'с' -> "s";  case 'т' -> "t";  case 'у' -> "u";
                case 'ф' -> "f";  case 'х' -> "kh"; case 'ц' -> "ts";
                case 'ч' -> "ch"; case 'ш' -> "sh"; case 'щ' -> "shch";
                case 'ь' -> "";   case 'ю' -> "yu"; case 'я' -> "ya";
                // Великі літери
                case 'А' -> "A";  case 'Б' -> "B";  case 'В' -> "V";
                case 'Г' -> "H";  case 'Ґ' -> "G";  case 'Д' -> "D";
                case 'Е' -> "E";  case 'Є' -> "Ye"; case 'Ж' -> "Zh";
                case 'З' -> "Z";  case 'И' -> "Y";  case 'І' -> "I";
                case 'Ї' -> "Yi"; case 'Й' -> "Y";  case 'К' -> "K";
                case 'Л' -> "L";  case 'М' -> "M";  case 'Н' -> "N";
                case 'О' -> "O";  case 'П' -> "P";  case 'Р' -> "R";
                case 'С' -> "S";  case 'Т' -> "T";  case 'У' -> "U";
                case 'Ф' -> "F";  case 'Х' -> "Kh"; case 'Ц' -> "Ts";
                case 'Ч' -> "Ch"; case 'Ш' -> "Sh"; case 'Щ' -> "Shch";
                case 'Ь' -> "";   case 'Ю' -> "Yu"; case 'Я' -> "Ya";
                default  -> String.valueOf(c);
            });
        }
        return sb.toString();
    }

    private void silentDelete(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }
}