package dev.msgforge.bot;

import dev.msgforge.bot.InstanceBot.ResumeMode;
import dev.msgforge.bot.InstanceBot.TriggerMode;

/**
 * A configuração como vem da tela, já validada por {@link BotService#save}. {@code apiKey} em branco
 * mantém a guardada; {@code removeApiKey} a apaga.
 */
public record BotForm(boolean enabled, String baseUrl, String model, String apiKey, boolean removeApiKey,
                      String systemPrompt, TriggerMode triggerMode, String keywords, String stopKeyword,
                      int sessionMinutes, boolean ignoreGroups, boolean pauseOnHuman, ResumeMode resumeMode,
                      int pauseMinutes, int contextMessages, int typingSeconds) {

    @Override
    public String toString() {   // nunca a chave em log
        return "BotForm[enabled=" + enabled + ", baseUrl=" + baseUrl + ", model=" + model + ", trigger="
                + triggerMode + "]";
    }
}
