package dev.msgforge.bot;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceBotRepository extends JpaRepository<InstanceBot, UUID> {}
