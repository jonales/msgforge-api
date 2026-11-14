package dev.msgforge.access;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    List<Organization> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
