package dev.msgforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JidsTest {

    @Test
    void brazilianMobileWithAndWithoutNinthDigit() {
        assertThat(Jids.brazilianMobileAlternative("5561999998888@s.whatsapp.net"))
                .contains("556199998888@s.whatsapp.net");
        assertThat(Jids.brazilianMobileAlternative("556199998888@s.whatsapp.net"))
                .contains("5561999998888@s.whatsapp.net");
    }

    @Test
    void noAlternativeForLandlinesOtherCountriesAndGroups() {
        assertThat(Jids.brazilianMobileAlternative("556133334444@s.whatsapp.net")).isEmpty();   // fixo
        assertThat(Jids.brazilianMobileAlternative("5561899998888@s.whatsapp.net")).isEmpty();  // 13 dígitos sem o 9
        assertThat(Jids.brazilianMobileAlternative("14155550123@s.whatsapp.net")).isEmpty();
        assertThat(Jids.brazilianMobileAlternative("120363000000000001@g.us")).isEmpty();
        assertThat(Jids.brazilianMobileAlternative(null)).isEmpty();
    }
}
