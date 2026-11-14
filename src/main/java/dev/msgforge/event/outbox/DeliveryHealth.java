package dev.msgforge.event.outbox;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Saúde das entregas para as telas (ADR 0012): quais instâncias têm o webhook falhando agora, com
 * entrega à espera de nova tentativa na outbox. Com {@code OUTBOX_ENABLED=false} a tabela fica vazia
 * e nada aparece (o caminho em memória não guarda falhas).
 */
@Service
public class DeliveryHealth {

    private static final Logger log = LoggerFactory.getLogger(DeliveryHealth.class);

    private final Outbox outbox;

    public DeliveryHealth(Outbox outbox) {
        this.outbox = outbox;
    }

    /** Nomes das instâncias com o webhook em nova tentativa. Erro na consulta não derruba a tela. */
    public Set<String> failingWebhooks() {
        try {
            return outbox.retryingKeys(Outbox.Type.WEBHOOK);
        } catch (RuntimeException e) {
            log.warn("Não deu para ler a saúde das entregas: {}", e.getMessage());
            return Set.of();
        }
    }
}
