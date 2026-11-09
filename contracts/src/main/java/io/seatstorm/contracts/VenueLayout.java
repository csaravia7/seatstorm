package io.seatstorm.contracts;

import java.util.ArrayList;
import java.util.List;

/** Fixed venue seed: 12 sections x 3 rows x 10 seats = 360 seats. */
public final class VenueLayout {
    private VenueLayout() {}

    public static final String CONCERT_ID = "neon-tide-2026";
    public static final String CONCERT_NAME = "Neon Tide · Arena Norte";
    public static final String CURRENCY = "USD";
    public static final List<String> ROWS = List.of("A", "B", "C");
    public static final int SEATS_PER_ROW = 10;
    public static final int SEATS_PER_SECTION = ROWS.size() * SEATS_PER_ROW;
    public static final int TOTAL_SEATS = Sections.count() * SEATS_PER_SECTION;

    /** {@code PLATEA-A:B07}. */
    public static String seatId(String sectionId, String row, int number) {
        return "%s:%s%02d".formatted(sectionId, row, number);
    }

    public static List<String> seatIdsOf(String sectionId) {
        Sections.byId(sectionId);
        List<String> ids = new ArrayList<>(SEATS_PER_SECTION);
        for (String row : ROWS) {
            for (int n = 1; n <= SEATS_PER_ROW; n++) {
                ids.add(seatId(sectionId, row, n));
            }
        }
        return List.copyOf(ids);
    }

    public static List<String> allSeatIds() {
        List<String> ids = new ArrayList<>(TOTAL_SEATS);
        for (Sections.Section s : Sections.ALL) {
            ids.addAll(seatIdsOf(s.id()));
        }
        return List.copyOf(ids);
    }

    /** Section part of a seat id, e.g. {@code PLATEA-A} for {@code PLATEA-A:B07}. */
    public static String sectionOf(String seatId) {
        int i = seatId.indexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException("Bad seatId: " + seatId);
        }
        return seatId.substring(0, i);
    }

    /** Row letter of a seat id. */
    public static String rowOf(String seatId) {
        return seatId.substring(seatId.indexOf(':') + 1, seatId.indexOf(':') + 2);
    }

    /** Seat number (1..10) of a seat id. */
    public static int numberOf(String seatId) {
        return Integer.parseInt(seatId.substring(seatId.indexOf(':') + 2));
    }
}
