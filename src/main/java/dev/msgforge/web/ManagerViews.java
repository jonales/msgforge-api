package dev.msgforge.web;

import dev.msgforge.instance.ConnectionState;
import dev.msgforge.instance.Instance;
import dev.msgforge.webhook.WebhookDtos.WebhookResponse;
import java.time.Instant;
import java.util.List;

/**
 * Modelos das telas do Manager. São próprios da camada web: os DTOs da API são o contrato público e
 * não são reaproveitados aqui (ADR 0005). Nenhum deles carrega o token da instância.
 */
public final class ManagerViews {

    private ManagerViews() {}

    /**
     * Linha da lista de instâncias. {@code paired}: o WhatsApp já conectou alguma vez (há número logado),
     * mesmo que agora esteja fora. {@code deliveryFailing}: o webhook da instância tem entrega em nova
     * tentativa na outbox ({@code DeliveryHealth}). {@code canConnect}: quem vê a lista pode conectar
     * esta instância (sem isso, as ações "continuar" e "reconectar" não aparecem).
     */
    public record InstanceRow(String name, String state, String number, String profileName, Instant createdAt,
                              boolean paired, boolean deliveryFailing, boolean canConnect) {

        static InstanceRow of(Instance instance, ConnectionState state, boolean deliveryFailing) {
            return new InstanceRow(instance.getName(), state.value(), numberOf(instance), instance.getProfileName(),
                    instance.getCreatedAt(), everPaired(instance), deliveryFailing, false);
        }

        InstanceRow withConnect(boolean allowed) {
            return allowed == canConnect ? this
                    : new InstanceRow(name, state, number, profileName, createdAt, paired, deliveryFailing, allowed);
        }

        /** A ação principal da linha cabe a quem está vendo? Abrir e acompanhar só pedem ver. */
        public boolean actionAllowed() {
            return canConnect || !needsAction();
        }

        /** O selo da lista: quem nunca conectou aparece como "aguardando conexão" ({@code pending}). */
        public String badge() {
            return badgeOf(state, paired);
        }

        /**
         * A ação principal da linha, uma por estado: {@code open} (abrir o painel), {@code resume}
         * (continuar a conexão guiada), {@code follow} (acompanhar uma reconexão em curso) e
         * {@code reconnect} (conectar de novo quem caiu).
         */
        public String action() {
            return switch (badge()) {
                case "open" -> "open";
                case "pending" -> "resume";
                case "connecting" -> "follow";
                default -> "reconnect";
            };
        }

        /** Pede atenção (destaque do botão): só o que espera alguém agir. */
        public boolean needsAction() {
            return "resume".equals(action()) || "reconnect".equals(action());
        }
    }

    public record InstanceDetail(String name, String state, String number, String profileName, String integration,
                          Instant createdAt, String historySync, boolean paired) {

        static InstanceDetail of(Instance instance, ConnectionState state) {
            return new InstanceDetail(instance.getName(), state.value(), numberOf(instance),
                    instance.getProfileName(), instance.getIntegration(), instance.getCreatedAt(),
                    instance.getHistorySync().name(), everPaired(instance));
        }

        public boolean open() {
            return "open".equals(state);
        }

        /** Criada e ainda sem conexão: o painel oferece retomar a conexão guiada. */
        public boolean pending() {
            return !paired && !open();
        }

        /** O mesmo selo da lista: quem nunca conectou aparece como "aguardando conexão". */
        public String badge() {
            return badgeOf(state, paired);
        }
    }

    /**
     * Estado + QR para o trecho da página que se atualiza sozinho. {@code updatedAt}: hora (HH:mm:ss) em
     * que o código atual chegou, para a renovação do QR ficar visível. {@code codeHidden}: quem vê não
     * pode conectar, então o QR e o código de pareamento não vêm (só o estado).
     */
    public record Live(String name, String state, String qrCode, String qrImage, String pairingCode,
                       String updatedAt, boolean codeHidden) {

        private static final java.time.format.DateTimeFormatter CLOCK =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());

        static Live withoutCode(String name, String state) {
            return new Live(name, state, null, null, null, null, false);
        }

        static Live withCode(String name, String state, String qrCode, String qrImage, String pairingCode,
                             Instant updatedAt) {
            return new Live(name, state, qrCode, qrImage, pairingCode, CLOCK.format(updatedAt), false);
        }

        /** Sem a permissão de conectar: o mesmo trecho, sem o QR e sem o código. */
        Live hidingCode() {
            return new Live(name, state, null, null, null, null, true);
        }

        public boolean open() {
            return "open".equals(state);
        }

        public boolean hasCode() {
            return qrCode != null || pairingCode != null;
        }

        /** Com código de pareamento, o passo a passo é o de digitar o código, e não o de ler o QR. */
        public boolean pairing() {
            return pairingCode != null;
        }

        /**
         * O que o leitor de tela anuncia quando o trecho troca ({@code manager.js}): muda só quando o
         * código ou o estado mudam, para a renovação do QR não repetir a tela inteira.
         */
        public String announceKey() {
            if (open()) {
                return "open";
            }
            if (hasCode()) {
                return "code:" + Integer.toHexString(java.util.Objects.hash(qrCode, pairingCode));
            }
            return state;
        }
    }

    /**
     * O que a pessoa pode no painel da instância (ADR 0010): cada seção aparece só com a permissão
     * dela. A rota confere de novo; esconder aqui é só para não oferecer o que daria 403.
     */
    public record PanelAccess(boolean connect, boolean chats, boolean send, boolean events, boolean account,
                              boolean delete) {

        static PanelAccess of(java.util.Set<dev.msgforge.access.Permission> granted) {
            return new PanelAccess(granted.contains(dev.msgforge.access.Permission.INSTANCE_CONNECT),
                    granted.contains(dev.msgforge.access.Permission.CHAT_VIEW),
                    granted.contains(dev.msgforge.access.Permission.CHAT_SEND),
                    granted.contains(dev.msgforge.access.Permission.EVENTS_CONFIGURE),
                    granted.contains(dev.msgforge.access.Permission.ACCOUNT_CONFIGURE),
                    granted.contains(dev.msgforge.access.Permission.INSTANCE_DELETE));
        }

        /** "Configurações": os grupos da conta ou o webhook (eventos). */
        public boolean settings() {
            return account || events;
        }

        /** A zona de perigo tem desconectar (conectar) e excluir. */
        public boolean danger() {
            return connect || delete;
        }
    }

    public record WebhookView(String url, boolean enabled, boolean byEvents, List<String> events) {

        static final WebhookView EMPTY = new WebhookView("", true, false, List.of());

        static WebhookView of(WebhookResponse webhook) {
            return new WebhookView(webhook.url(), Boolean.TRUE.equals(webhook.enabled()),
                    Boolean.TRUE.equals(webhook.webhookByEvents()), webhook.events());
        }
    }

    /**
     * Configurações da instância na tela. {@code historySync} é o nome do {@code HistorySync}; null no
     * que foi digitado quer dizer "não mudou".
     */
    public record SettingsView(boolean rejectCall, String msgCall, boolean groupsIgnore, boolean alwaysOnline,
                               boolean readMessages, boolean readStatus, String historySync) {

        /** Os interruptores de cada grupo de configurações, na ordem da tela. */
        static final java.util.Map<String, List<String>> BEHAVIORS = java.util.Map.of(
                "calls", List.of("rejectCall"),
                "reading", List.of("readMessages", "readStatus", "groupsIgnore"),
                "presence", List.of("alwaysOnline"));

        /** Os interruptores do grupo (vazio nos grupos sem interruptor). */
        public List<String> behaviors(String group) {
            return BEHAVIORS.getOrDefault(group, List.of());
        }

        /** Quantos do grupo estão ligados, para o estado na aba. */
        public long enabledIn(String group) {
            return behaviors(group).stream().filter(this::on).count();
        }

        static SettingsView of(Instance instance) {
            var s = instance.getSettings();
            return new SettingsView(s.rejectCall(), s.msgCall() == null ? "" : s.msgCall(), s.groupsIgnore(),
                    s.alwaysOnline(), s.readMessages(), s.readStatus(), instance.getHistorySync().name());
        }

        /** Ligado? Pelo nome do campo do contrato (o template percorre {@link #behaviors}). */
        public boolean on(String behavior) {
            return switch (behavior) {
                case "rejectCall" -> rejectCall;
                case "groupsIgnore" -> groupsIgnore;
                case "readMessages" -> readMessages;
                case "readStatus" -> readStatus;
                case "alwaysOnline" -> alwaysOnline;
                default -> throw new IllegalArgumentException(behavior);
            };
        }
    }

    /** O proxy na tela. Nunca carrega a senha: só se há uma guardada. */
    public record ProxyView(boolean enabled, String protocol, String host, String port, String username,
                            boolean hasPassword) {

        static final List<String> PROTOCOLS = List.of("http", "https", "socks5", "socks5h", "socks4", "socks4a");

        static ProxyView of(Instance instance) {
            return instance.getProxy()
                    .map(p -> new ProxyView(true, p.protocol(), p.host(), String.valueOf(p.port()),
                            p.username() == null ? "" : p.username(), p.hasPassword()))
                    .orElse(new ProxyView(false, "http", "", "", "", false));
        }

        /** Resumo para o título recolhido: "http://host:porta" ou null sem proxy. */
        public String summary() {
            return enabled && !host.isEmpty() ? protocol + "://" + host + ":" + port : null;
        }
    }

    private static boolean everPaired(Instance instance) {
        return instance.getOwnerJid() != null;
    }

    private static String badgeOf(String state, boolean paired) {
        return paired || "open".equals(state) ? state : "pending";
    }

    /** "5561999999999@s.whatsapp.net" → "5561999999999"; sem número conectado, o informado na criação. */
    private static String numberOf(Instance instance) {
        var jid = instance.getOwnerJid();
        if (jid != null && jid.contains("@")) {
            return jid.substring(0, jid.indexOf('@'));
        }
        return instance.getNumber();
    }

    // ---- Mensagens (histórico, ADR 0008)

    private static final java.time.ZoneId ZONE = java.time.ZoneId.systemDefault();
    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE);
    private static final java.time.format.DateTimeFormatter CLOCK_MINUTES =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZONE);

    /**
     * Uma conversa na lista: o contato (ou o grupo), a última mensagem e quando ela chegou.
     * {@code name}: o nome do contato, ou o do grupo quando o motor o conhece; {@code lastAuthor}: em
     * grupo, quem mandou a última mensagem.
     */
    public record ChatRow(String jid, String name, MessageContent last, String time, boolean fromMe,
                          String lastAuthor) {

        public boolean group() {
            return isGroup(jid);
        }

        /** O nome; sem nome, o telefone (+55...) ou o JID (grupo sem nome, LID sem tradução). */
        public String label() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return phoneOrJid(jid);
        }
    }

    /** A lista dividida: conversas com pessoas e grupos, cada uma da mais recente para a mais antiga. */
    public record ChatList(List<ChatRow> people, List<ChatRow> groups) {

        public static ChatList of(List<ChatRow> rows) {
            return new ChatList(rows.stream().filter(r -> !r.group()).toList(),
                    rows.stream().filter(ChatRow::group).toList());
        }

        public boolean empty() {
            return people.isEmpty() && groups.isEmpty();
        }

        public int size() {
            return people.size() + groups.size();
        }
    }

    /**
     * Uma mensagem da conversa aberta. {@code author}: o pushName de quem mandou (recebidas);
     * {@code authorJid}: em grupo, o participante (telefone ou {@code <lid>@lid}, nunca um número
     * inventado). {@code startsRun}: primeira de uma sequência do mesmo remetente no mesmo dia (só
     * ela mostra o remetente). {@code day}: não nulo na primeira mensagem de cada dia (o separador).
     */
    public record MessageRow(String id, boolean fromMe, String author, String authorJid, MessageContent content,
                             String time, String status, Day day, boolean startsRun) {

        /** O número de quem mandou, para mostrar ao lado do nome: "+55..." ou o LID como veio. */
        public String authorNumber() {
            return authorJid == null ? null : phoneOrJid(authorJid);
        }

        /** O nome de quem mandou; sem pushName, o número. */
        public String authorLabel() {
            if (author != null && !author.isBlank()) {
                return author;
            }
            return authorNumber();
        }

        /** Mostrar o número além do nome (quando há os dois e são diferentes). */
        public boolean showNumber() {
            return author != null && !author.isBlank() && authorNumber() != null;
        }
    }

    /** Separador de dia: {@code kind} {@code today}, {@code yesterday} ou {@code date} (com {@code date}). */
    public record Day(String kind, String date) {

        static Day of(long epochSeconds, java.time.LocalDate today) {
            var date = Instant.ofEpochSecond(epochSeconds).atZone(ZONE).toLocalDate();
            if (date.equals(today)) {
                return new Day("today", DAY.format(Instant.ofEpochSecond(epochSeconds)));
            }
            if (date.equals(today.minusDays(1))) {
                return new Day("yesterday", DAY.format(Instant.ofEpochSecond(epochSeconds)));
            }
            return new Day("date", DAY.format(Instant.ofEpochSecond(epochSeconds)));
        }
    }

    /**
     * Página da conversa, da mais antiga para a mais nova; {@code page} 1 é a mais recente.
     * {@code name}: o nome do contato ou do grupo, se conhecido.
     */
    public record Conversation(String jid, String name, List<MessageRow> rows, int page, long pages) {

        public boolean hasOlder() {
            return page < pages;
        }

        public int olderPage() {
            return page + 1;
        }

        public boolean group() {
            return isGroup(jid);
        }

        public String label() {
            return name != null && !name.isBlank() ? name : phoneOrJid(jid);
        }
    }

    /** Uma mensagem gravada, já na ordem da tela (antes de agrupar). */
    record StoredRow(String id, boolean fromMe, String pushName, String participant, MessageContent content,
                     long timestamp, String status) {}

    /**
     * Monta as linhas da conversa: separador no primeiro de cada dia e o remetente só no começo de
     * cada sequência (mesmo remetente, mesmo dia). Em conversa individual, o remetente é o lado.
     */
    static List<MessageRow> rows(List<StoredRow> ordered, boolean group, java.time.LocalDate today) {
        var out = new java.util.ArrayList<MessageRow>(ordered.size());
        String previousDay = null;
        String previousSender = null;
        for (var m : ordered) {
            var day = Day.of(m.timestamp(), today);
            var newDay = !day.date().equals(previousDay);
            var sender = m.fromMe() ? "(me)" : group ? String.valueOf(m.participant()) : "(them)";
            out.add(new MessageRow(m.id(), m.fromMe(), m.pushName(), group && !m.fromMe() ? m.participant() : null,
                    m.content(), CLOCK_MINUTES.format(Instant.ofEpochSecond(m.timestamp())), m.status(),
                    newDay ? day : null, newDay || !sender.equals(previousSender)));
            previousDay = day.date();
            previousSender = sender;
        }
        return out;
    }

    static boolean isGroup(String jid) {
        return jid != null && jid.endsWith("@g.us");
    }

    /** "5561...@s.whatsapp.net" → "+5561..."; o resto (LID, grupo) como veio. */
    static String phoneOrJid(String jid) {
        if (jid == null) {
            return null;
        }
        return jid.endsWith("@s.whatsapp.net") ? "+" + jid.substring(0, jid.indexOf('@')) : jid;
    }

    public static String time(long epochSeconds) {
        return TIME.format(Instant.ofEpochSecond(epochSeconds));
    }
}
