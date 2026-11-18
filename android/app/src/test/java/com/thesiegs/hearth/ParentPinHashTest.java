package com.thesiegs.hearth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ParentPinHashTest {
    /** As the Dart side's ParentPinHash.hash("2468") keeps it, with salt 00..0f and a cost of 3. */
    private static final String SALTED =
            "v2$3$000102030405060708090a0b0c0d0e0f$9a3485228d1d796b879fd845db14495a013a59a0a41ac147f4151299fdaa0f1a";
    /** The old unsalted form of "2468". */
    private static final String OLD = "845cdcc0a8a73c7509cdfed614ca4e5811f2d342db3d8c89dfcc9dbf14e0d857";
    /** SALTED's version, as the Dart side works it out too (test/providers/parent_pin_hash_test.dart). */
    private static final String SALTED_TAG =
            "v2$0$160da7869ef6e3229bb14789f7d6db40$031d4dc76d5acff2a0bfac0d61f77a005b2c025d59cda8cd1f462b711aa32dd1";

    @Test
    public void checksTheSaltedFormAsDartMakesIt() {
        assertTrue(ParentPinHash.verify("2468", SALTED));
        assertFalse(ParentPinHash.verify("2469", SALTED));
    }

    @Test
    public void theOldUnsaltedFormIsNoLongerChecked() {
        assertFalse(ParentPinHash.verify("2468", OLD));
        assertFalse(ParentPinHash.verify("1357", OLD));
    }

    @Test
    public void anOldUnsaltedValueGivesUpItsPin_whichIsHowItIsReplaced() {
        assertEquals("2468", ParentPinHash.pinOfLegacy(OLD));
        // Not a four-digit PIN's, and not the old form at all
        assertNull(ParentPinHash.pinOfLegacy("0".repeat(64)));
        assertNull(ParentPinHash.pinOfLegacy(SALTED));
        assertNull(ParentPinHash.pinOfLegacy(null));
    }

    @Test
    public void makesASaltedHashThatChecksAndDiffersEachTime() {
        String a = ParentPinHash.make("2468");
        String b = ParentPinHash.make("2468");
        assertTrue(ParentPinHash.SALTED.matcher(a).matches());
        assertTrue(a.startsWith("v2$10000$"));
        assertNotEquals(a, b);
        assertTrue(ParentPinHash.verify("2468", a));
        assertFalse(ParentPinHash.verify("2469", a));
        assertFalse(ParentPinHash.isTag(a));
    }

    @Test
    public void aVersionChecksNoPin_andIsTheSameOnEveryTvThatHoldsTheHash() {
        assertEquals(SALTED_TAG, ParentPinHash.tagOf(SALTED));
        assertTrue(ParentPinHash.isTag(SALTED_TAG));
        // A version is its own
        assertEquals(SALTED_TAG, ParentPinHash.tagOf(SALTED_TAG));
        // It has the stored shape (an older build takes it for a hash), and no PIN matches it
        assertTrue(ParentPinHash.SALTED.matcher(SALTED_TAG).matches());
        for (String pin : new String[] {"2468", "0000", "9999"}) assertFalse(ParentPinHash.verify(pin, SALTED_TAG));
        // Another hash of the same PIN has another version: it can't be guessed from the PIN
        assertNotEquals(ParentPinHash.tagOf(ParentPinHash.make("2468")), ParentPinHash.tagOf(ParentPinHash.make("2468")));
        // Nothing but a salted value has one (an unsalted one would give the PIN away)
        assertNull(ParentPinHash.tagOf(OLD));
        assertNull(ParentPinHash.tagOf(null));
        assertFalse(ParentPinHash.isTag(SALTED));
        assertFalse(ParentPinHash.isTag(null));
    }

    @Test
    public void refusesWhatIsntAStoredPin() {
        assertFalse(ParentPinHash.verify("2468", null));
        assertFalse(ParentPinHash.verify(null, SALTED));
        assertFalse(ParentPinHash.verify("2468", "2468"));
        assertFalse(ParentPinHash.verify("2468", "v2$0$000102030405060708090a0b0c0d0e0f$" + OLD));
    }
}
