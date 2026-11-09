package io.seatstorm.platform.web;

import java.util.Map;

/** JSON error model: {@code {"code","message","details"}}. */
public record ErrorBody(String code, String message, Map<String, Object> details) {}
