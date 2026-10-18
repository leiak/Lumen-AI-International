package com.lumen.common.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackQueryTest {

    @Test
    void compactCtor_clampsPageBelow1() {
        TrackQuery q = new TrackQuery("D1", "PENDING", -5, 20);
        assertEquals(1, q.page());
    }

    @Test
    void compactCtor_clampsSizeToMax100() {
        TrackQuery q = new TrackQuery("D1", "PENDING", 1, 200);
        assertEquals(100, q.size());
    }

    @Test
    void compactCtor_sizeBelow1_defaultsTo20() {
        TrackQuery q = new TrackQuery("D1", "PENDING", 1, 0);
        assertEquals(20, q.size());
    }
}
