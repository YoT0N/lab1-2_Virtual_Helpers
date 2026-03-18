package edu.ilkiv.telegrambot;

import edu.ilkiv.telegrambot.handler.InfoBot;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@SpringBootApplication
public class TelegramBotApplication {

	public static void main(String[] args) {
		SpringApplication.run(TelegramBotApplication.class, args);
	}

	// ← Реєструємо бота вручну після старту Spring контексту
	@Bean
	public ApplicationRunner botRunner(InfoBot infoBot) {
		return args -> {
			try {
				TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
				botsApi.registerBot(infoBot);
				System.out.println("✅ Telegram бот успішно зареєстрований: " + infoBot.getBotUsername());
			} catch (TelegramApiException e) {
				System.err.println("❌ Помилка реєстрації бота: " + e.getMessage());
				throw new RuntimeException(e);
			}
		};
	}
}