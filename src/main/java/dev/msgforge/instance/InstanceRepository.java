package dev.msgforge.instance;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRepository extends JpaRepository<Instance, UUID> {

    Optional<Instance> findByName(String name);

    boolean existsByName(String name);
}
