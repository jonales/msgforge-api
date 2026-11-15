// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A mídia de terceiros sai na origem do Manager: só o que o navegador só decodifica vai inline. */
class ManagerMediaTypesTest {

    @Test
    void onlyPassiveMediaIsServedInline() {
        assertThat(ManagerMediaController.safeType("image/jpeg")).isEqualTo("image/jpeg");
        assertThat(ManagerMediaController.safeType("audio/ogg; codecs=opus")).isEqualTo("audio/ogg; codecs=opus");
        assertThat(ManagerMediaController.safeType("audio/ogg")).isEqualTo("audio/ogg");
        assertThat(ManagerMediaController.safeType("audio/mpeg; charset=x")).isEqualTo("audio/mpeg");
        assertThat(ManagerMediaController.safeType("VIDEO/MP4")).isEqualTo("video/mp4");
        assertThat(ManagerMediaController.safeType("image/svg+xml")).isEqualTo("application/octet-stream");
        assertThat(ManagerMediaController.safeType("text/html")).isEqualTo("application/octet-stream");
        assertThat(ManagerMediaController.safeType("application/pdf")).isEqualTo("application/octet-stream");
        assertThat(ManagerMediaController.safeType(null)).isEqualTo("application/octet-stream");
    }
}
