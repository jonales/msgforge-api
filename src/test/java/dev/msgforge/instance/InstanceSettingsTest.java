package dev.msgforge.instance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InstanceSettingsTest {

    private static final String CONTACT = "5561999999999@s.whatsapp.net";
    private static final String GROUP = "120363000000000000@g.us";
    private static final String STATUS = InstanceSettings.STATUS_BROADCAST;

    @Test
    void defaultsDeliverEverythingButStatusAndMarkNothing() {
        var settings = InstanceSettings.DEFAULTS;

        assertThat(settings.delivers(CONTACT)).isTrue();
        assertThat(settings.delivers(GROUP)).isTrue();
        assertThat(settings.delivers(STATUS)).as("readStatus=false: status não viram upsert").isFalse();
        assertThat(settings.marksAsRead(CONTACT, false)).isFalse();
        assertThat(settings.callReply()).isNull();
    }

    @Test
    void groupsIgnoreDropsOnlyGroups() {
        var settings = new InstanceSettings(false, null, true, false, true, false);

        assertThat(settings.delivers(GROUP)).isFalse();
        assertThat(settings.marksAsRead(GROUP, false)).as("ignorada não é lida").isFalse();
        assertThat(settings.delivers(CONTACT)).isTrue();
        assertThat(settings.marksAsRead(CONTACT, false)).isTrue();
        assertThat(settings.marksAsRead(CONTACT, true)).as("as próprias, nunca").isFalse();
    }

    @Test
    void readStatusDeliversAndMarksStatusIndependentlyOfReadMessages() {
        var settings = new InstanceSettings(false, null, false, false, false, true);

        assertThat(settings.delivers(STATUS)).isTrue();
        assertThat(settings.marksAsRead(STATUS, false)).isTrue();
        assertThat(settings.marksAsRead(CONTACT, false)).isFalse();
    }

    @Test
    void blankCallMessageMeansNoReply() {
        assertThat(new InstanceSettings(true, "  ", false, false, false, false).callReply()).isNull();
        assertThat(new InstanceSettings(false, "Não atendo", false, false, false, false).callReply())
                .isEqualTo("Não atendo");
    }

    @Test
    void proxyHelpersNormalizeAndValidate() {
        assertThat(InstanceProxy.normalizeProtocol(" SOCKS ")).isEqualTo("socks5");
        assertThat(InstanceProxy.normalizeProtocol("https")).isEqualTo("https");
        assertThat(InstanceProxy.normalizeProtocol("ftp")).isNull();
        assertThat(InstanceProxy.parsePort("3128")).isEqualTo(3128);
        assertThat(InstanceProxy.parsePort("0")).isNull();
        assertThat(InstanceProxy.parsePort("abc")).isNull();
        assertThat(InstanceProxy.validHost("proxy.example.com")).isTrue();
        assertThat(InstanceProxy.validHost("[::1]")).isTrue();
        assertThat(InstanceProxy.validHost("user@host")).isFalse();
        assertThat(InstanceProxy.validHost("host/path")).isFalse();
        assertThat(new InstanceProxy("http", "h", 80, "u", "v1:abc").toString())
                .contains("***").doesNotContain("v1:abc");
    }
}
