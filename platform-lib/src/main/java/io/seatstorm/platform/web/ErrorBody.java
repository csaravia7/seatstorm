package io.seatstorm.platform.web;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;

/** JSON error model: {@code {"code","message","details"}}. Registered for reflection: it is returned inside a {@code Response} (native). */
@RegisterForReflection
public record ErrorBody(String code, String message, Map<String, Object> details) {}
