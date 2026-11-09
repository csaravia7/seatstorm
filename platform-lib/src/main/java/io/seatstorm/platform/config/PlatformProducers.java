package io.seatstorm.platform.config;

import io.seatstorm.contracts.EventRegistry;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Produces;

public class PlatformProducers {
    @Produces
    @Singleton
    EventRegistry eventRegistry() {
        return new EventRegistry();
    }
}
