package com.thesiegs.hearth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "Share Bluetooth with Home Assistant": who decides between the TV and Home Assistant (the later change, by
 * revision), and what the setting comes to (Hearth's docs/contracts/hearth-tv.md, "Bluetooth sharing").
 */
public class BtProxyTest {
    @Test
    public void homeAssistantSTurningItOnReachesTheTv() {
        BtProxy.Word word = BtProxy.fromHomeAssistant(false, 0, false, true, 1);
        assertTrue(word.on);
        assertEquals(1, word.rev);
        assertFalse(word.changed);
    }

    @Test
    public void aChangeOnTheTvWaitsForHomeAssistantToTakeIt() {
        // Turned on here against revision 3; Home Assistant hasn't heard yet and still says off at 3
        BtProxy.Word word = BtProxy.fromHomeAssistant(true, 3, true, false, 3);
        assertTrue(word.on);
        assertEquals(3, word.rev);
        assertTrue(word.changed);
        // Taken: Home Assistant says on at 4
        word = BtProxy.fromHomeAssistant(word.on, word.rev, word.changed, true, 4);
        assertTrue(word.on);
        assertEquals(4, word.rev);
        assertFalse(word.changed);
    }

    @Test
    public void homeAssistantSLaterChangeWinsOverTheTvS() {
        // Turned on here against 3, while an adult turned it off in Home Assistant (4) before hearing of it
        BtProxy.Word word = BtProxy.fromHomeAssistant(true, 3, true, false, 4);
        assertFalse(word.on);
        assertEquals(4, word.rev);
        assertFalse(word.changed);
    }

    @Test
    public void anOlderRevisionKeepsTheTvS() {
        // Home Assistant's record started again (a new pairing): the TV keeps its own, and Home Assistant takes it
        BtProxy.Word word = BtProxy.fromHomeAssistant(true, 5, false, false, 0);
        assertTrue(word.on);
        assertEquals(5, word.rev);
    }

    @Test
    public void theSameRevisionWithNoChangeHereIsHomeAssistantS() {
        BtProxy.Word word = BtProxy.fromHomeAssistant(true, 2, false, false, 2);
        assertFalse(word.on);
    }

    @Test
    public void whatItComesTo() {
        assertEquals(BtProxy.OFF, BtProxy.state(false, false, false, false, false));
        assertEquals(BtProxy.NEEDS_PERMISSION, BtProxy.state(true, false, true, true, false));
        assertEquals(BtProxy.STOPPED, BtProxy.state(true, true, true, true, false));
        assertEquals(BtProxy.BLUETOOTH_OFF, BtProxy.state(true, true, true, false, true));
        assertEquals(BtProxy.LOCATION_OFF, BtProxy.state(true, true, false, true, true));
        assertEquals(BtProxy.RUNNING, BtProxy.state(true, true, true, true, true));
    }
}
