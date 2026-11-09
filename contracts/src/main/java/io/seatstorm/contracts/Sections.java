package io.seatstorm.contracts;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The 12 venue sections. The index doubles as the Kafka partition for seat.commands / seat.events. */
public final class Sections {
    private Sections() {}

    public record Section(int index, String id, String label, int priceCents) {}

    public static final List<Section> ALL = List.of(
            new Section(0, "PLATEA-A", "Platea A", 9000),
            new Section(1, "PLATEA-B", "Platea B", 9000),
            new Section(2, "PLATEA-C", "Platea C", 9000),
            new Section(3, "PLATEA-D", "Platea D", 9000),
            new Section(4, "PALCO-1", "Palco 1", 15000),
            new Section(5, "PALCO-2", "Palco 2", 15000),
            new Section(6, "PALCO-3", "Palco 3", 15000),
            new Section(7, "PALCO-4", "Palco 4", 15000),
            new Section(8, "GRADA-N", "Grada N", 4500),
            new Section(9, "GRADA-S", "Grada S", 4500),
            new Section(10, "GRADA-E", "Grada E", 4500),
            new Section(11, "GRADA-O", "Grada O", 4500));

    private static final Map<String, Section> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(Section::id, Function.identity()));

    public static int count() {
        return ALL.size();
    }

    public static boolean exists(String sectionId) {
        return BY_ID.containsKey(sectionId);
    }

    /** Section index 0..11 (the Kafka partition). Throws IllegalArgumentException when unknown. */
    public static int indexOf(String sectionId) {
        return byId(sectionId).index();
    }

    public static Section byId(String sectionId) {
        Section s = BY_ID.get(sectionId);
        if (s == null) {
            throw new IllegalArgumentException("Unknown sectionId: " + sectionId);
        }
        return s;
    }

    public static int priceCents(String sectionId) {
        return byId(sectionId).priceCents();
    }
}
