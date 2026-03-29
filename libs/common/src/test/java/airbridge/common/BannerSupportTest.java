package airbridge.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerSupportTest {
    @Test
    void renderUsesDefaultVersion() {
        String rendered = BannerSupport.render("air-bridge sender");

        assertTrue(rendered.contains("____"));
        assertTrue(rendered.endsWith("air-bridge sender - " + VersionSupport.version() + System.lineSeparator()));
    }

    @Test
    void renderUsesCustomVersionText() {
        String rendered = BannerSupport.render("air-bridge receiver", "build custom-version");

        assertTrue(rendered.endsWith("air-bridge receiver - build custom-version" + System.lineSeparator()));
    }

    @Test
    void renderKeepsFooterRightAlignedWithinBannerWidth() {
        String rendered = BannerSupport.render("air-bridge sender", "custom-version");
        String[] lines = rendered.split("\\R");
        String footer = "";
        int bannerWidth = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.contains("air-bridge sender - custom-version")) {
                continue;
            }
            bannerWidth = Math.max(bannerWidth, line.length());
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                footer = lines[i];
                break;
            }
        }

        assertEquals(bannerWidth, footer.length());
    }
}
