package io.seatstorm.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class VenueTest {
    @Test
    void twelveSectionsWithContiguousIndexes() {
        assertEquals(12, Sections.count());
        for (int i = 0; i < 12; i++) {
            assertEquals(i, Sections.ALL.get(i).index());
            assertEquals(i, Sections.indexOf(Sections.ALL.get(i).id()));
        }
        assertEquals(0, Sections.indexOf("PLATEA-A"));
        assertEquals(4, Sections.indexOf("PALCO-1"));
        assertEquals(11, Sections.indexOf("GRADA-O"));
        assertEquals(9000, Sections.priceCents("PLATEA-D"));
        assertEquals(15000, Sections.priceCents("PALCO-3"));
        assertEquals(4500, Sections.priceCents("GRADA-S"));
        assertThrows(IllegalArgumentException.class, () -> Sections.indexOf("NOPE"));
    }

    @Test
    void threeHundredSixtyUniqueSeatsWithExpectedFormat() {
        var all = VenueLayout.allSeatIds();
        assertEquals(360, all.size());
        assertEquals(360, new HashSet<>(all).size());
        assertEquals("PLATEA-A:B07", VenueLayout.seatId("PLATEA-A", "B", 7));
        assertTrue(all.contains("PLATEA-A:B07"));
        assertTrue(all.contains("GRADA-O:C10"));
        assertTrue(all.stream().allMatch(s -> s.matches("[A-Z]+-[A-Z0-9]:[ABC](0[1-9]|10)")));
        assertEquals(30, VenueLayout.seatIdsOf("PALCO-2").size());
        assertEquals("PLATEA-A", VenueLayout.sectionOf("PLATEA-A:B07"));
        assertEquals("B", VenueLayout.rowOf("PLATEA-A:B07"));
        assertEquals(7, VenueLayout.numberOf("PLATEA-A:B07"));
    }

    @Test
    void topicsAreSeventeen() {
        assertEquals(17, Topics.ALL.size());
        assertEquals(8, Topics.DLQ_TOPICS.size());
        assertTrue(Topics.ALL.contains("seat.commands.dlq"));
        assertTrue(Topics.ALL.contains("ui.events"));
        assertEquals(17, new HashSet<>(Topics.ALL).size());
    }
}
