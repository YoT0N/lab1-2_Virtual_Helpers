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

    @Value("${voice.tts.lang:uk}")
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
                "ffmpeg", "-y",
                "-i", ogg.toAbsolutePath().toString(),
                "-ar", "16000",   // 16 кГц
                "-ac", "1",       // моно
                "-f", "wav",
                wav.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // Читаємо вивід (щоб не заблокувало)
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
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
     * Очікує що vosk-transcriber встановлено: pip install vosk
     */
    private String runVoskTranscriber(Path wavFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "vosk-transcriber",
                "--model", voskModelPath,
                "--input", wavFile.toAbsolutePath().toString(),
                "--output-type", "txt"
        );
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        StringBuilder result = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
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
     * Потребує: sudo apt install espeak-ng
     */
    public byte[] textToSpeech(String text) {
        if (text == null || text.isBlank()) return null;

        // Очищаємо Markdown розмітку (зірочки, підкреслення тощо)
        String cleanText = stripMarkdown(text);
        if (cleanText.isBlank()) return null;

        Path tmpPath = Paths.get(tmpDir);
        String uid = UUID.randomUUID().toString().substring(0, 8);
        Path wavFile = tmpPath.resolve(uid + "_tts.wav");

        try {
            Files.createDirectories(tmpPath);

            ProcessBuilder pb = new ProcessBuilder(
                    "espeak-ng",
                    "-v", ttsLang,         // мова (uk, en, de тощо)
                    "-s", String.valueOf(ttsSpeed), // швидкість (слів/хв)
                    "-w", wavFile.toAbsolutePath().toString(), // вивід у WAV
                    cleanText
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
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
            log.info("TTS: синтезовано {} байт для тексту: '{}'", wavBytes.length, cleanText.substring(0, Math.min(50, cleanText.length())));
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
                    "ffmpeg", "-y",
                    "-i", wavFile.toAbsolutePath().toString(),
                    "-c:a", "libopus",
                    "-b:a", "32k",
                    oggFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
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
     * Викликається при старті (PostConstruct в InfoBot).
     */
    public String checkDependencies() {
        StringBuilder sb = new StringBuilder("🎙 *Залежності голосового модуля:*\n\n");
        sb.append(checkCommand("ffmpeg", "-version")      ? "✅" : "❌").append(" ffmpeg\n");
        sb.append(checkCommand("espeak-ng", "--version")  ? "✅" : "❌").append(" eSpeak-NG (TTS)\n");
        sb.append(checkCommand("vosk-transcriber", null)  ? "✅" : "❌").append(" Vosk Transcriber (STT)\n");

        boolean modelExists = Files.exists(Paths.get(voskModelPath));
        sb.append(modelExists ? "✅" : "❌").append(" Vosk модель (").append(voskModelPath).append(")\n");

        if (!modelExists) {
            sb.append("\n💡 Завантажте українську модель:\n");
            sb.append("`wget https://alphacephei.com/vosk/models/vosk-model-uk-v3.zip`\n");
            sb.append("`unzip vosk-model-uk-v3.zip -d vosk-model-uk`");
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
                br.lines().forEach(l -> {}); // споживаємо вивід
            }
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Видаляє Markdown розмітку з тексту перед синтезом мовлення.
     */
    public String stripMarkdown(String text) {
        return text
                .replaceAll("\\*([^*]+)\\*", "$1")    // **bold** або *italic*
                .replaceAll("_([^_]+)_", "$1")          // _italic_
                .replaceAll("`([^`]+)`", "$1")           // `code`
                .replaceAll("#+ ", "")                   // заголовки
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1") // [text](url)
                .replaceAll("[🌤💰💱🔔📅🌐👤📊❌✅🎙🤔💧💨📋👋🏙🏷🕐📌📍]", "") // emoji
                .trim();
    }

    private void silentDelete(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }
}