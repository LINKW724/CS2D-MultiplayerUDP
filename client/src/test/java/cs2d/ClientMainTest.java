package cs2d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientMainTest {
    private static final String[] RENDER_PROPERTIES = {
            "cs2d.renderHz", "javafx.animation.pulse", "javafx.animation.fullspeed"
    };
    private final Map<String, String> originalValues = captureProperties();

    @AfterEach
    void restoreProperties() {
        for (String key : RENDER_PROPERTIES) {
            String value = originalValues.get(key);
            if (value == null)
                System.clearProperty(key);
            else
                System.setProperty(key, value);
        }
    }

    @Test
    void configuresJavaFxPulseAsTheSingleExact165HzFrameClock() {
        System.setProperty("cs2d.renderHz", "165");
        System.clearProperty("javafx.animation.pulse");
        System.clearProperty("javafx.animation.fullspeed");

        assertEquals(165, ClientMain.configureRenderClock());
        assertEquals("165", System.getProperty("javafx.animation.pulse"));
        assertEquals("true", System.getProperty("javafx.animation.fullspeed"));
    }

    private static Map<String, String> captureProperties() {
        Map<String, String> values = new HashMap<>();
        for (String key : RENDER_PROPERTIES)
            values.put(key, System.getProperty(key));
        return values;
    }
}
