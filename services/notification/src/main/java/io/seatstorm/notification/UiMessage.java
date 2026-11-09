package io.seatstorm.notification;

import java.util.Map;

/** One JSON message for ui.events: Kafka key plus the body fields. */
public record UiMessage(String key, Map<String, Object> body) {
    public boolean isFeed() {
        return "feed".equals(body.get("type"));
    }
}
