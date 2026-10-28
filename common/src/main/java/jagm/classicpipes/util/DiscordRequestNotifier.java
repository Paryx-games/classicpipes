package jagm.classicpipes.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jagm.classicpipes.ClassicPipes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiscordRequestNotifier {

    private static final Path CONFIG_PATH = Path.of("config", "classicpipes.toml");
    private static final long GROUPING_WINDOW_MILLIS = 60_000L;
    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "classicpipes-discord-notifier");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<String> RECENT_REQUEST_LINES = new ArrayList<>();

    private static String messageId;
    private static String recentPlayerName;
    private static long lastNotificationTime;
    private static long recentTimestamp;

    private DiscordRequestNotifier() {
    }

    public static void notifyRequest(Player player, ItemStack stack) {
        String webhookUrl = loadWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        EXECUTOR.execute(() -> sendRequest(webhookUrl, player.getPlainTextName(), stack));
    }

    public static void ensureConfig() {
        loadWebhookUrl();
    }

    private static String loadWebhookUrl() {
        try {
            if (Files.notExists(CONFIG_PATH)) {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH,
                        "[discord_request_notifications]\n"
                                + "# Set this to a Discord webhook URL to enable server-side request notifications.\n"
                                + "webhook_url = \"\"\n",
                        StandardCharsets.UTF_8);
                return null;
            }

            for (String line : Files.readAllLines(CONFIG_PATH, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("webhook_url")) {
                    continue;
                }
                int equalsIndex = trimmed.indexOf('=');
                if (equalsIndex < 0) {
                    continue;
                }
                String value = trimmed.substring(equalsIndex + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                }
            }
        } catch (IOException exception) {
            ClassicPipes.LOGGER.warn("Could not read Discord request notification config at {}", CONFIG_PATH, exception);
        }
        return null;
    }

    private static void sendRequest(String webhookUrl, String playerName, ItemStack stack) {
        long now = System.currentTimeMillis();
        boolean appendToRecentMessage = messageId != null
                && playerName.equals(recentPlayerName)
                && now - lastNotificationTime < GROUPING_WINDOW_MILLIS;
        String requestLine = requestLine(stack);

        if (appendToRecentMessage) {
            RECENT_REQUEST_LINES.add(requestLine);
            recentTimestamp = now / 1000L;
            if (send(webhookUrl + "/messages/" + messageId + "?with_components=true", "PATCH", payload(), messageId) == null) {
                RECENT_REQUEST_LINES.remove(RECENT_REQUEST_LINES.size() - 1);
            } else {
                lastNotificationTime = now;
            }
            return;
        }

        RECENT_REQUEST_LINES.clear();
        RECENT_REQUEST_LINES.add(requestLine);
        recentPlayerName = playerName;
        recentTimestamp = now / 1000L;
        String createdMessageId = send(webhookUrl + "?wait=true&with_components=true", "POST", payload(), null);
        if (createdMessageId != null) {
            messageId = createdMessageId;
            lastNotificationTime = now;
        } else {
            RECENT_REQUEST_LINES.clear();
            messageId = null;
            recentPlayerName = null;
        }
    }

    private static String payload() {
        StringBuilder content = new StringBuilder("# ").append(recentPlayerName);
        for (String requestLine : RECENT_REQUEST_LINES) {
            content.append('\n').append(requestLine);
        }
        content.append("\n-# <t:").append(recentTimestamp).append(":f>");
        return payload(content.toString());
    }

    private static String payload(String content) {
        JsonObject textDisplay = new JsonObject();
        textDisplay.addProperty("type", 10);
        textDisplay.addProperty("content", content);

        JsonArray sectionComponents = new JsonArray();
        sectionComponents.add(textDisplay);

        JsonObject thumbnail = new JsonObject();
        thumbnail.addProperty("type", 11);
        JsonObject media = new JsonObject();
        media.addProperty("url", "https://mc-heads.net/avatar/" + recentPlayerName + "/128");
        thumbnail.add("media", media);

        JsonObject section = new JsonObject();
        section.addProperty("type", 9);
        section.add("components", sectionComponents);
        section.add("accessory", thumbnail);

        JsonArray containerComponents = new JsonArray();
        containerComponents.add(section);

        JsonObject container = new JsonObject();
        container.addProperty("type", 17);
        container.addProperty("accent_color", 9550540);
        container.add("components", containerComponents);

        JsonArray components = new JsonArray();
        components.add(container);

        JsonObject payload = new JsonObject();
        payload.addProperty("flags", 32768);
        payload.add("components", components);
        return payload.toString();
    }

    private static String requestLine(ItemStack stack) {
        return "Requested **x" + stack.getCount() + "** of "
                + stack.getItemName().getString();
    }

    private static String send(String url, String method, String payload, String editedMessageId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                ClassicPipes.LOGGER.warn("Discord request notification {} failed with HTTP {}: {}",
                        method, response.statusCode(), response.body());
                if (editedMessageId != null) {
                    messageId = null;
                    RECENT_REQUEST_LINES.clear();
                    recentPlayerName = null;
                }
                return null;
            }

            if (editedMessageId != null) {
                return editedMessageId;
            }
            Matcher matcher = MESSAGE_ID_PATTERN.matcher(response.body());
            if (!matcher.find()) {
                ClassicPipes.LOGGER.warn("Discord request notification succeeded without a message id");
                return null;
            }
            return matcher.group(1);
        } catch (IllegalArgumentException | IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ClassicPipes.LOGGER.warn("Could not send Discord request notification", exception);
            return null;
        }
    }

}
