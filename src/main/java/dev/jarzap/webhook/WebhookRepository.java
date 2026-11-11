package dev.jarzap.webhook;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {

    Optional<Webhook> findByInstanceId(UUID instanceId);
}
