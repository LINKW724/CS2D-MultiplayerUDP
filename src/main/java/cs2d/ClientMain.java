package cs2d;

import cs2d.client.GameClient;
import javafx.application.Application;

public class ClientMain {
    static final int DEFAULT_RENDER_HZ = 165;

    public static void main(String[] args) {
        configureRenderClock();
        Application.launch(GameClient.class, args);
    }

    static int configureRenderClock() {
        int renderHz = Integer.getInteger("cs2d.renderHz", DEFAULT_RENDER_HZ);
        if (renderHz < 30 || renderHz > 500) {
            System.err.println("[Render] Invalid cs2d.renderHz=" + renderHz + ", falling back to "
                    + DEFAULT_RENDER_HZ);
            renderHz = DEFAULT_RENDER_HZ;
        }

        System.setProperty("cs2d.renderHz", Integer.toString(renderHz));
        System.setProperty("javafx.animation.pulse",
                System.getProperty("javafx.animation.pulse", Integer.toString(renderHz)));
        // JavaFX 17 lets native VSync override javafx.animation.pulse. A high-frequency
        // pulse clock plus the client's own limiter keeps rendering independent from
        // the 120 Hz server clock and from a wrongly reported desktop refresh rate.
        System.setProperty("javafx.animation.fullspeed",
                System.getProperty("javafx.animation.fullspeed", "true"));
        System.out.println("[Render] Client render target: " + renderHz + " Hz"
                + " (JavaFX fullspeed pulse=" + System.getProperty("javafx.animation.fullspeed") + ")");
        return renderHz;
    }
}
