// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.instance.ProfileNameChanged;
import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** Conta comum: o nome do perfil chega depois do login, pela sincronização, e precisa ser gravado. */
class ProfileNameIT extends IntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    InstanceRepository instances;

    @Test
    void nameArrivingAfterLoginIsSavedAndBlankIsIgnored() {
        var name = uniqueName("perfil");
        assertThat(post("/instance/create", "{\"instanceName\":\"%s\"}".formatted(name)).status()).isEqualTo(201);

        events.publishEvent(new ProfileNameChanged(name, "Loja da Ana"));
        assertThat(instances.findByName(name).orElseThrow().getProfileName()).isEqualTo("Loja da Ana");

        events.publishEvent(new ProfileNameChanged(name, " "));
        assertThat(instances.findByName(name).orElseThrow().getProfileName()).isEqualTo("Loja da Ana");
    }
}
