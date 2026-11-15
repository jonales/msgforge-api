package dev.msgforge.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * O bot de uma instância (ADR 0015). A chave do provedor fica cifrada ({@code SecretCipher}, com o id
 * da instância como dado associado) e só é decifrada na chamada ao modelo.
 */
@Entity
@Table(name = "instance_bot")
public class InstanceBot {

    public enum Provider { OPENAI }

    /** Quando o bot entra numa conversa. */
    public enum TriggerMode { ALL, KEYWORD, EXACT }

    /** Como o bot volta depois de um atendente assumir. */
    public enum ResumeMode { AUTOMATIC, MANUAL }

    @Id
    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Provider provider = Provider.OPENAI;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(name = "api_key_encrypted", length = 2000)
    private String apiKeyEncrypted;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt = "";

    @Column(name = "trigger_mode", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private TriggerMode triggerMode = TriggerMode.ALL;

    @Column(nullable = false, length = 1000)
    private String keywords = "";

    @Column(name = "stop_keyword", nullable = false, length = 60)
    private String stopKeyword = "";

    @Column(name = "session_minutes", nullable = false)
    private int sessionMinutes;

    @Column(name = "ignore_groups", nullable = false)
    private boolean ignoreGroups = true;

    @Column(name = "pause_on_human", nullable = false)
    private boolean pauseOnHuman = true;

    @Column(name = "resume_mode", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private ResumeMode resumeMode = ResumeMode.AUTOMATIC;

    @Column(name = "pause_minutes", nullable = false)
    private int pauseMinutes = 30;

    @Column(name = "context_messages", nullable = false)
    private int contextMessages = 10;

    @Column(name = "typing_seconds", nullable = false)
    private int typingSeconds;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected InstanceBot() {}

    public InstanceBot(UUID instanceId) {
        this.instanceId = instanceId;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getInstanceId() { return instanceId; }
    public boolean isEnabled() { return enabled; }
    public Provider getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public boolean hasApiKey() { return apiKeyEncrypted != null; }
    public String getSystemPrompt() { return systemPrompt; }
    public TriggerMode getTriggerMode() { return triggerMode; }
    public String getKeywords() { return keywords; }
    public String getStopKeyword() { return stopKeyword; }
    public int getSessionMinutes() { return sessionMinutes; }
    public boolean isIgnoreGroups() { return ignoreGroups; }
    public boolean isPauseOnHuman() { return pauseOnHuman; }
    public ResumeMode getResumeMode() { return resumeMode; }
    public int getPauseMinutes() { return pauseMinutes; }
    public int getContextMessages() { return contextMessages; }
    public int getTypingSeconds() { return typingSeconds; }

    /** Tudo menos a chave (que só muda quando vem uma nova ou o pedido de remover). */
    void apply(BotForm form) {
        this.enabled = form.enabled();
        this.baseUrl = form.baseUrl();
        this.model = form.model();
        this.systemPrompt = form.systemPrompt();
        this.triggerMode = form.triggerMode();
        this.keywords = form.keywords();
        this.stopKeyword = form.stopKeyword();
        this.sessionMinutes = form.sessionMinutes();
        this.ignoreGroups = form.ignoreGroups();
        this.pauseOnHuman = form.pauseOnHuman();
        this.resumeMode = form.resumeMode();
        this.pauseMinutes = form.pauseMinutes();
        this.contextMessages = form.contextMessages();
        this.typingSeconds = form.typingSeconds();
    }

    void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }
}
