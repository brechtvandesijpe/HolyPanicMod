package be.breroeluean;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HolyPanicModClient implements ClientModInitializer {
    private static KeyBinding keyBinding;
    private static int clickCount = 0;
    private static long firstClickTime = 0L;
    private static final long CLICK_WINDOW_MS = 2500L;
    private static boolean wasKeyDown = false;

    @Override
    public void onInitializeClient() {
        keyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Alert the team",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                "HolyPanicMod"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean isDown = keyBinding.isPressed();
            if (isDown && !wasKeyDown) {
                long now = System.currentTimeMillis();

                if (clickCount == 0) {
                    firstClickTime = now;
                    clickCount = 1;
                } else {
                    if (now - firstClickTime > CLICK_WINDOW_MS) {
                        firstClickTime = now;
                        clickCount = 1;
                    } else {
                        clickCount++;
                    }
                }

                if (clickCount >= 6) {
                    if (client.player != null) {
                        try {
                            sendAlert(client);

                            client.getToastManager().add(new SystemToast(
                                    SystemToast.Type.NARRATOR_TOGGLE,
                                    Text.literal("HolyPanicMod"),
                                    Text.literal("Alert sent to the team!").formatted(Formatting.GREEN)
                            ));
                        } catch (Exception e) {
                            client.getToastManager().add(new SystemToast(
                                    SystemToast.Type.NARRATOR_TOGGLE,
                                    Text.literal("HolyPanicMod"),
                                    Text.literal("Error while sending alert.").formatted(Formatting.RED)
                            ));

                            LoggerFactory.getLogger("MinecraftClient").error(e.getMessage());
                        }
                    }

                    // reset sequence after successful 6 hits
                    clickCount = 0;
                    firstClickTime = 0L;
                }
            }
            wasKeyDown = isDown;
        });
    }

    private void sendAlert(MinecraftClient client) {
        String webhookUrl = "REDACTED";

        if (webhookUrl == null || webhookUrl.isBlank()) {
            LoggerFactory.getLogger("HolyPanicMod").warn("DISCORD_WEBHOOK_URL not set; skipping Discord alert");
            return;
        }

        String mentionId = "REDACTED"; // Discord user ID to mention, optional
        String playerName = client.player != null ? client.player.getName().getString() : "Unknown";

        assert client.player != null;

        String baseContent = formatCoordinates(client);

        String content;
        String json;
        if (mentionId != null && !mentionId.isBlank()) {
            content = "<@&" + mentionId.trim() + "> " + baseContent;
            json = "{\"content\":\"" + escapeJson(content) + "\","
                    + "\"allowed_mentions\":{\"roles\":[\"" + escapeJson(mentionId.trim()) + "\"]}"
                    + "}";
        } else {
            content = baseContent;
            json = "{\"content\":\"" + escapeJson(content) + "\"}";
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        LoggerFactory.getLogger("HolyPanicMod").error("Discord webhook failed: {} {}", resp.statusCode(), resp.body());
                    } else {
                        LoggerFactory.getLogger("HolyPanicMod").info("Discord webhook sent successfully");
                    }
                })
                .exceptionally(t -> {
                    LoggerFactory.getLogger("HolyPanicMod").error("Discord webhook error", t);
                    return null;
                });
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String formatCoordinates(MinecraftClient client) {
        if (client.player == null) {
            return "(unknown location)";
        }

        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();

        return String.format("[x:%d, y:%d, z:%d]", new Object[] {Math.round(x), Math.round(y), Math.round(z)});
    }
}