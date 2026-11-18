package com.thesiegs.hearth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Parent PIN a TV takes, and what it learns (HaParentPins): each row of the table in
 * docs/home-assistant-integration.md ("Which PIN works when"), with and without Home Assistant, and what another TV
 * of the household is told when it asks.
 */
public class ParentPinLearnTest {
    private static final String PIN = "2468";
    private static final String OTHER = "1357";
    private static final String V1 = "aaaaaaaaaaaaaaaa";
    private static final String V2 = "bbbbbbbbbbbbbbbb";
    private static final long NOW = 1_800_000_000_000L;

    /** Home Assistant and the household's other TVs, as a test says they answer. */
    private static final class Fake implements HaParentPins.Asker {
        boolean paired;
        /** null: unreachable. */
        String haPin;
        boolean haReachable;
        boolean haLocked;
        HaParentPins.PeerAnswer peers = HaParentPins.PeerAnswer.UNKNOWN;
        int haAsked;
        final List<String> peersAsked = new ArrayList<>();

        @Override
        public boolean paired(Context context) {
            return paired;
        }

        @Override
        public HaParentPins.Verdict ha(Context context, String pin) {
            haAsked++;
            if (!haReachable) return HaParentPins.Verdict.UNKNOWN;
            if (haLocked) return HaParentPins.Verdict.LOCKED;
            return pin.equals(haPin) ? HaParentPins.Verdict.YES : HaParentPins.Verdict.NO;
        }

        @Override
        public HaParentPins.PeerAnswer peers(Context context, String scope, String pin) {
            peersAsked.add(scope);
            return peers;
        }
    }

    private FakeContext tv;
    private Fake ask;

    @Before
    public void aTvWithItsKeysInMemory() {
        HaParentPins.sKeys = HouseholdTvs.memoryKeys();
        tv = new FakeContext("com.thesiegs.hearth");
        ask = new Fake();
    }

    private static FakeContext.Prefs flutter(FakeContext context) {
        return context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE);
    }

    /** The TV's own Parent PIN as the app keeps it (null: not that key). */
    private static void own(FakeContext context, String hash, String version) {
        flutter(context).edit().putString("flutter." + HaParentPins.OWN_HASH, hash)
                .putString("flutter." + HaParentPins.OWN_VERSION, version).commit();
    }

    /** Home Assistant's state document named parent PINs of {@code version} (null: it has none). */
    private static void document(FakeContext context, String version) throws Exception {
        JSONObject state = new JSONObject();
        if (version != null) state.put("parent_pins_version", version);
        HaParentPins.applyDocument(context, state);
    }

    private HaParentPins.Check check(String pin) {
        return HaParentPins.check(tv, pin, ask);
    }

    private static HaParentPins.PeerAnswer said(HaParentPins.PeerAnswer.Kind kind) {
        return new HaParentPins.PeerAnswer(kind, 0);
    }

    // ---- No Home Assistant ----

    @Test
    public void row1_aTvOnItsOwnChecksItsOwnPinWithNobodyElse() {
        own(tv, ParentPinHash.make(PIN), null);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertEquals(0, ask.haAsked);
        assertTrue(ask.peersAsked.isEmpty());
        assertTrue(HaParentPins.any(tv));
        assertTrue(HaParentPins.ownKnown(tv));
    }

    @Test
    public void aTvWithNoParentPinTakesNone() {
        assertFalse(HaParentPins.any(tv));
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertTrue(ask.peersAsked.isEmpty());
    }

    @Test
    public void row2_inAHouseholdAPinSetHereWorksWithTheOtherTvsOff() {
        String hash = ParentPinHash.make(PIN);
        own(tv, hash, ParentPinHash.tagOf(hash));
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertTrue(ask.peersAsked.isEmpty());
    }

    @Test
    public void row3_aPinSetOnAnotherTvIsAskedThere_learnedHere_andThenWorksWithThatTvOff() {
        String version = ParentPinHash.tagOf(ParentPinHash.make(PIN));
        own(tv, null, version);
        // It has a Parent PIN (things stay locked), though it can't check one alone yet
        assertTrue(HaParentPins.any(tv));
        assertFalse(HaParentPins.ownKnown(tv));

        // No TV reached: not taken, and nothing learned
        assertEquals(HaParentPins.Check.NO_TVS, check(PIN));
        assertEquals("tv:" + version, ask.peersAsked.get(0));
        assertFalse(HaParentPins.ownKnown(tv));

        // A TV that knows it says no to a wrong one: nothing learned
        ask.peers = said(HaParentPins.PeerAnswer.Kind.NO);
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertFalse(HaParentPins.ownKnown(tv));

        // And yes to the right one: learned
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertTrue(HaParentPins.ownKnown(tv));

        // From then on this TV decides alone, whatever the others would say
        ask.peers = HaParentPins.PeerAnswer.UNKNOWN;
        int asked = ask.peersAsked.size();
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertEquals(asked, ask.peersAsked.size());
        // What's kept is sealed
        String kept = String.valueOf(tv.getSharedPreferences(LauncherAccessibilityService.DEVICE_PREFS, 0).getAll());
        assertTrue(kept.contains("tv_parent_pins_sealed"));
        assertFalse(kept.contains("v2$10000$"));
    }

    @Test
    public void row3_aTvThatLocksThisOneOutHoldsThePadHere() {
        own(tv, null, ParentPinHash.tagOf(ParentPinHash.make(PIN)));
        ask.peers = new HaParentPins.PeerAnswer(HaParentPins.PeerAnswer.Kind.WAIT, 120);
        long before = System.currentTimeMillis();
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertTrue(ParentPinPolicy.lockedUntil(tv, before) >= before + 110_000);
        assertFalse(HaParentPins.ownKnown(tv));
    }

    @Test
    public void row4_whenTheHouseholdsPinChangesWhatWasLearnedForTheOldOneIsNoGood() {
        String v1 = ParentPinHash.tagOf(ParentPinHash.make(PIN));
        String v2 = ParentPinHash.tagOf(ParentPinHash.make(OTHER));
        own(tv, null, v1);
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, check(PIN));

        // Changed on another TV: this TV follows the new version (the app's side), and the old PIN is asked again
        own(tv, null, v2);
        assertFalse(HaParentPins.ownKnown(tv));
        ask.peers = HaParentPins.PeerAnswer.UNKNOWN;
        assertEquals(HaParentPins.Check.NO_TVS, check(PIN));
        ask.peers = said(HaParentPins.PeerAnswer.Kind.NO);
        assertEquals(HaParentPins.Check.NO, check(PIN));
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        // Only the new one is kept
        ask.peers = HaParentPins.PeerAnswer.UNKNOWN;
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertTrue(HaParentPins.tvLearned(tv, v1).isEmpty());
    }

    @Test
    public void row5_aTvThatKeptItsOwnPinWhenItJoinedChecksTheHouseholdsWithTheOtherTvs() {
        own(tv, ParentPinHash.make(OTHER), null);
        String version = ParentPinHash.tagOf(ParentPinHash.make(PIN));
        Map<String, Object> household = new HashMap<>();
        household.put("state", "live");
        household.put("version", version);

        // Its own goes on working alone meanwhile
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        assertEquals(HaParentPins.Check.NO_TVS, HaParentPins.checkHousehold(tv, household, PIN, ask));
        ask.peers = said(HaParentPins.PeerAnswer.Kind.NO);
        assertEquals(HaParentPins.Check.NO, HaParentPins.checkHousehold(tv, household, OTHER, ask));
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, HaParentPins.checkHousehold(tv, household, PIN, ask));
        assertEquals("tv:" + version, ask.peersAsked.get(0));

        // A household with none (or this TV in none) has no PIN to enter
        household.put("state", "removed");
        assertEquals(HaParentPins.Check.NO, HaParentPins.checkHousehold(tv, household, PIN, ask));
        assertEquals(HaParentPins.Check.NO, HaParentPins.checkHousehold(tv, null, PIN, ask));
    }

    @Test
    public void row5_aHashAnOlderBuildLeftInTheHouseholdIsCheckedHere() {
        Map<String, Object> household = Household.parentPinOf(entry(ParentPinHash.make(PIN)));
        assertEquals(HaParentPins.Check.YES, HaParentPins.checkHousehold(tv, household, PIN, ask));
        assertEquals(HaParentPins.Check.NO, HaParentPins.checkHousehold(tv, household, OTHER, ask));
        // And an unsalted one by the PIN it stands for
        household = Household.parentPinOf(entry("845cdcc0a8a73c7509cdfed614ca4e5811f2d342db3d8c89dfcc9dbf14e0d857"));
        assertEquals(HaParentPins.Check.YES, HaParentPins.checkHousehold(tv, household, PIN, ask));
        assertEquals(HaParentPins.Check.NO, HaParentPins.checkHousehold(tv, household, OTHER, ask));
        assertTrue(ask.peersAsked.isEmpty());
    }

    private static HouseholdEntry entry(String value) {
        return new HouseholdEntry(HouseholdBridge.PARENT_PIN, HouseholdBridge.SHARED, "", value, NOW, "tv-1", 1, false);
    }

    @Test
    public void theHouseholdsEntryIsReadAsAVersion_orAsWhatAnOlderBuildWrote() {
        String hash = ParentPinHash.make(PIN);
        String tag = ParentPinHash.tagOf(hash);
        assertEquals("none", Household.parentPinOf(null).get("state"));
        Map<String, Object> version = Household.parentPinOf(entry(tag));
        assertEquals("live", version.get("state"));
        assertEquals(tag, version.get("version"));
        assertNull(version.get("hash"));
        // An older build's: the hash, with the version every TV works out from it
        Map<String, Object> old = Household.parentPinOf(entry(hash));
        assertEquals(tag, old.get("version"));
        assertEquals(hash, old.get("hash"));
        Map<String, Object> unsalted = Household.parentPinOf(entry("0".repeat(64)));
        assertEquals("live", unsalted.get("state"));
        assertNull(unsalted.get("version"));
        assertEquals("0".repeat(64), unsalted.get("legacy"));
        assertEquals("none", Household.parentPinOf(entry("1234")).get("state"));
        assertEquals("removed", Household.parentPinOf(new HouseholdEntry(HouseholdBridge.PARENT_PIN,
                HouseholdBridge.SHARED, "", "", NOW, "tv-1", 2, true)).get("state"));
    }

    @Test
    public void anUnsaltedPinTheAppHasntReplacedYetStillLetsItsAdultIn() {
        own(tv, "845cdcc0a8a73c7509cdfed614ca4e5811f2d342db3d8c89dfcc9dbf14e0d857", null);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertTrue(HaParentPins.any(tv));
    }

    @Test
    public void aVersionAnOlderBuildTookForAHashIsAVersion_notAPinNobodyCanEnter() {
        String version = ParentPinHash.tagOf(ParentPinHash.make(PIN));
        own(tv, version, null);
        assertEquals(version, HaParentPins.own(tv).version);
        assertNull(HaParentPins.own(tv).hash);
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals("tv:" + version, ask.peersAsked.get(0));
    }

    // ---- With Home Assistant ----

    private void paired(String version) throws Exception {
        ask.paired = true;
        ask.haReachable = true;
        ask.haPin = PIN;
        document(tv, version);
    }

    @Test
    public void row7_homeAssistantDecidesAndAYesIsLearned() throws Exception {
        paired(V1);
        // Nothing learned yet, and still a TV with a Parent PIN: nothing opens for a kid meanwhile
        assertTrue(HaParentPins.hashes(tv).isEmpty());
        assertTrue(HaParentPins.any(tv, ask));
        assertTrue(HaParentPins.inPlace(tv, ask));

        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertTrue(HaParentPins.hashes(tv).isEmpty());
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(1, HaParentPins.hashes(tv).size());
        assertTrue(ParentPinHash.verify(PIN, HaParentPins.hashes(tv).get(0)));
        // Typed again: asked again (Home Assistant's lockout and answer are final), not kept twice
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(1, HaParentPins.hashes(tv).size());
        assertEquals(3, ask.haAsked);
        assertEquals(V1, HaParentPins.version(tv));
    }

    @Test
    public void row8_pairingATvThatHasItsOwnPin_homeAssistantsTakeItsPlaceAtOnce() throws Exception {
        own(tv, ParentPinHash.make(OTHER), null);
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        paired(V1);
        // The TV's own is no Parent PIN any more, online
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertEquals(HaParentPins.Check.YES, check(PIN));
        // nor with Home Assistant down, before or after a PIN was learned: no way in around Home Assistant
        ask.haReachable = false;
        assertEquals(HaParentPins.Check.NO_HA, check(OTHER));
        assertEquals(HaParentPins.Check.YES, check(PIN));
    }

    @Test
    public void row8_pairedBeforeAnyPinWasTypedHere_andHomeAssistantDown_noPinIsTaken() throws Exception {
        own(tv, ParentPinHash.make(OTHER), null);
        paired(V1);
        ask.haReachable = false;
        assertEquals(HaParentPins.Check.NO_HA, check(PIN));
        assertEquals(HaParentPins.Check.NO_HA, check(OTHER));
        // It is still a TV with a Parent PIN: nothing opens for a kid meanwhile
        assertTrue(HaParentPins.hashes(tv).isEmpty());
        assertTrue(HaParentPins.any(tv, ask));
    }

    @Test
    public void row9_withHomeAssistantDownOnlyAPinUsedHereBeforeWorks() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        ask.haReachable = false;
        assertEquals(HaParentPins.Check.YES, check(PIN));
        // Another adult's PIN, right in Home Assistant but never typed here: not taken, and said why
        assertEquals(HaParentPins.Check.NO_HA, check(OTHER));
        // The other TVs were asked for it, by the version this TV follows
        assertEquals("ha:" + V1, ask.peersAsked.get(ask.peersAsked.size() - 1));
    }

    @Test
    public void row10_anAdultWhoNeverTypedTheirPinHere_anotherTvThatLearnedItVouches() throws Exception {
        paired(V1);
        ask.haReachable = false;
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        // Learned here too: it works with that TV off as well
        ask.peers = HaParentPins.PeerAnswer.UNKNOWN;
        assertEquals(HaParentPins.Check.YES, check(PIN));
        // A TV that locks this one out is a no, and holds the pad
        ask.peers = new HaParentPins.PeerAnswer(HaParentPins.PeerAnswer.Kind.WAIT, 60);
        long before = System.currentTimeMillis();
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertTrue(ParentPinPolicy.lockedUntil(tv, before) >= before + 50_000);
    }

    @Test
    public void row10_onlineTheirPinIsCheckedByHomeAssistantAndLearnedAtOnce() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        ask.haPin = OTHER;
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        ask.haReachable = false;
        assertEquals(HaParentPins.Check.YES, check(OTHER));
    }

    @Test
    public void row11_aNoFromHomeAssistantDropsTheLearnedPinThatMatches() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        ask.haPin = OTHER;
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        assertEquals(2, HaParentPins.hashes(tv).size());
        // PIN is no longer an adult's (say the version hasn't reached this TV yet)
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertEquals(1, HaParentPins.hashes(tv).size());
        ask.haReachable = false;
        assertEquals(HaParentPins.Check.NO_HA, check(PIN));
        assertEquals(HaParentPins.Check.YES, check(OTHER));
    }

    @Test
    public void row11_lockedOutThereIsANoThatKeepsWhatWasLearned() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        ask.haLocked = true;
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertEquals(1, HaParentPins.hashes(tv).size());
    }

    @Test
    public void row11_aNewVersionDropsEveryLearnedPin() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        // The same version again (every document says it) keeps them
        document(tv, V1);
        assertEquals(1, HaParentPins.hashes(tv).size());
        document(tv, V2);
        assertTrue(HaParentPins.hashes(tv).isEmpty());
        assertEquals(V2, HaParentPins.version(tv));
        ask.haReachable = false;
        assertEquals(HaParentPins.Check.NO_HA, check(PIN));
        assertEquals("ha:" + V2, ask.peersAsked.get(ask.peersAsked.size() - 1));
    }

    @Test
    public void aYesIsLearnedOnlyForTheVersionItWasGivenUnder() throws Exception {
        paired(V1);
        // The PINs changed between the question and the answer
        HaParentPins.Asker changing = new HaParentPins.Asker() {
            @Override
            public boolean paired(Context context) {
                return true;
            }

            @Override
            public HaParentPins.Verdict ha(Context context, String pin) {
                try {
                    document(tv, V2);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return HaParentPins.Verdict.YES;
            }

            @Override
            public HaParentPins.PeerAnswer peers(Context context, String scope, String pin) {
                return HaParentPins.PeerAnswer.UNKNOWN;
            }
        };
        assertEquals(HaParentPins.Check.YES, HaParentPins.check(tv, PIN, changing));
        assertTrue(HaParentPins.hashes(tv).isEmpty());
    }

    @Test
    public void row12_homeAssistantHasNoParentPins_theTvsOwnApplies() throws Exception {
        own(tv, ParentPinHash.make(OTHER), null);
        ask.paired = true;
        ask.haReachable = true;
        document(tv, null);
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        assertEquals(0, ask.haAsked);
    }

    @Test
    public void row12_homeAssistantRemovesItsPins_theOnesLearnedHereStay() throws Exception {
        own(tv, ParentPinHash.make(OTHER), null);
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        document(tv, null);
        assertTrue(HaParentPins.kept(tv));
        int asked = ask.haAsked;
        // The learned one works, checked here; the TV's own stays replaced until an adult sets one again
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertEquals(asked, ask.haAsked);
    }

    @Test
    public void row13_unpaired_learnedPinsStay_andASetPinOfTheTvsOwnReplacesThem() throws Exception {
        own(tv, ParentPinHash.make(OTHER), null);
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        ask.paired = false;
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals(HaParentPins.Check.NO, check(OTHER));
        assertTrue(HaParentPins.any(tv, ask));
        // An adult sets a PIN of the TV's own (which took the PIN): Home Assistant's go
        HaParentPins.forget(tv);
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertEquals(HaParentPins.Check.YES, check(OTHER));
    }

    @Test
    public void row13_unpairedBeforeAnyPinWasTypedHere_theTvsOwnAppliesAgain() throws Exception {
        own(tv, ParentPinHash.make(OTHER), null);
        paired(V1);
        ask.paired = false;
        assertEquals(HaParentPins.Check.YES, check(OTHER));
        assertEquals(HaParentPins.Check.NO, check(PIN));
        assertEquals(0, ask.haAsked);
    }

    @Test
    public void row13_unpairedInAHousehold_withNothingLearned_theHouseholdsPinIsAskedOfTheOtherTvs() throws Exception {
        String version = ParentPinHash.tagOf(ParentPinHash.make(PIN));
        own(tv, null, version);
        paired(V1);
        ask.paired = false;
        ask.peers = said(HaParentPins.PeerAnswer.Kind.YES);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals("tv:" + version, ask.peersAsked.get(0));
    }

    @Test
    public void row14_hashesAnOlderIntegrationSendsAreNotRead_andOnesKeptBeforeTheUpdateStillWork() throws Exception {
        // Kept by a build that took hashes, for version V1
        String hash = ParentPinHash.make(PIN);
        tv.getSharedPreferences(LauncherAccessibilityService.DEVICE_PREFS, 0).edit()
                .putString("ha_parent_pins_sealed",
                        SecretBox.sealText(HaParentPins.sKeys, "hearth_ha_parent_pins", "[\"" + hash + "\"]"))
                .putString("ha_parent_pins_version", V1).putBoolean("ha_parent_pins_managed", true).commit();
        ask.paired = true;
        // The same version: they stay, as the first PINs this TV knows
        HaParentPins.applyDocument(tv, new JSONObject().put("parent_pins_version", V1));
        assertEquals(HaParentPins.Check.YES, check(PIN));
        // A document that still carries hashes (an integration from before contract 4): only its version is read
        String sent = ParentPinHash.make(OTHER);
        HaParentPins.applyDocument(tv, new JSONObject().put("parent_pins_version", V2).put("parent_pins",
                new org.json.JSONArray().put(new JSONObject().put("person", "person.alex").put("hash", sent))));
        assertTrue(HaParentPins.hashes(tv).isEmpty());
        assertEquals(HaParentPins.Check.NO_HA, check(OTHER));
        // The TV says which version it follows, so that integration stops sending them
        assertEquals(V2, HaParentPins.version(tv));
    }

    // ---- What another TV of the household is told ----

    private static String answer(FakeContext on, String peer, String scope, String pin, long now) {
        return HaParentPins.answerPeer(on, peer, scope, pin, now).optString("a");
    }

    @Test
    public void aTvThatKnowsTheHouseholdsPinSaysYesOrNo_andOneThatDoesntCantSay() {
        String hash = ParentPinHash.make(PIN);
        String version = ParentPinHash.tagOf(hash);
        own(tv, hash, version);
        assertEquals("yes", answer(tv, "tv-b", "tv:" + version, PIN, NOW));
        assertEquals("no", answer(tv, "tv-b", "tv:" + version, OTHER, NOW));
        // Another version (the PIN changed, and one of the two hasn't heard): it can't say
        assertEquals("unknown", answer(tv, "tv-b", "tv:" + ParentPinHash.tagOf(ParentPinHash.make(PIN)), PIN, NOW));
        // A TV that follows the version without the hash can't either, until it has learned it
        FakeContext pending = new FakeContext("com.thesiegs.hearth");
        own(pending, null, version);
        assertEquals("unknown", answer(pending, "tv-b", "tv:" + version, PIN, NOW));
        HaParentPins.learnTv(pending, version, PIN);
        assertEquals("yes", answer(pending, "tv-b", "tv:" + version, PIN, NOW));
        assertEquals("no", answer(pending, "tv-b", "tv:" + version, OTHER, NOW));
        // What isn't a question
        assertEquals("unknown", answer(tv, "tv-b", "tv:" + version, "12", NOW));
        assertEquals("unknown", answer(tv, "tv-b", "else:" + version, PIN, NOW));
        assertEquals("unknown", answer(tv, null, "tv:" + version, PIN, NOW));
    }

    @Test
    public void aPinLearnedFromHomeAssistantIsVouchedFor_butNeverDenied() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        assertEquals("yes", answer(tv, "tv-b", "ha:" + V1, PIN, NOW));
        // Another adult's may be right in Home Assistant: this TV can't say
        assertEquals("unknown", answer(tv, "tv-b", "ha:" + V1, OTHER, NOW));
        assertEquals("unknown", answer(tv, "tv-b", "ha:" + V2, PIN, NOW));
        // A TV that learned none has nothing to check against
        FakeContext none = new FakeContext("com.thesiegs.hearth");
        document(none, V1);
        assertEquals("unknown", answer(none, "tv-b", "ha:" + V1, PIN, NOW));
    }

    @Test
    public void eachAskingTvHasItsOwnCountOfWrongTries_keptAcrossARestart() throws Exception {
        String hash = ParentPinHash.make(PIN);
        String version = ParentPinHash.tagOf(hash);
        own(tv, hash, version);
        String scope = "tv:" + version;
        for (int i = 1; i < ParentPinPolicy.FREE_TRIES; i++) {
            JSONObject said = HaParentPins.answerPeer(tv, "tv-b", scope, OTHER, NOW);
            assertEquals("no", said.optString("a"));
            assertEquals(0, said.optInt("retry"));
        }
        // The fifth starts a wait, and says so
        JSONObject fifth = HaParentPins.answerPeer(tv, "tv-b", scope, OTHER, NOW);
        assertEquals("no", fifth.optString("a"));
        assertEquals(30, fifth.optInt("retry"));
        assertEquals(1, HaParentPins.lockouts(tv));
        // While it lasts even the right PIN gets no answer (the prefs are all a restart keeps, and they hold it)
        JSONObject held = HaParentPins.answerPeer(tv, "tv-b", scope, PIN, NOW + 10_000);
        assertEquals("wait", held.optString("a"));
        assertEquals(20, held.optInt("retry"));
        // Another TV isn't held by it, and this TV's own pad isn't either
        assertEquals("yes", answer(tv, "tv-c", scope, PIN, NOW + 10_000));
        assertEquals(0, ParentPinPolicy.lockedUntil(tv, NOW));
        // After the wait a further wrong one doubles it; a right one clears the count
        JSONObject sixth = HaParentPins.answerPeer(tv, "tv-b", scope, OTHER, NOW + 31_000);
        assertEquals(60, sixth.optInt("retry"));
        assertEquals("yes", answer(tv, "tv-b", scope, PIN, NOW + 31_000 + 61_000));
        assertEquals(0, HaParentPins.answerPeer(tv, "tv-b", scope, OTHER, NOW + 200_000).optInt("retry"));
    }

    @Test
    public void guessesAtAPinLearnedFromHomeAssistantAreCountedThoughTheAnswerIsCantSay() throws Exception {
        paired(V1);
        assertEquals(HaParentPins.Check.YES, check(PIN));
        JSONObject last = null;
        for (int i = 0; i < ParentPinPolicy.FREE_TRIES; i++) {
            last = HaParentPins.answerPeer(tv, "tv-b", "ha:" + V1, OTHER, NOW);
            assertEquals("unknown", last.optString("a"));
        }
        assertEquals(30, last.optInt("retry"));
        assertEquals("wait", answer(tv, "tv-b", "ha:" + V1, PIN, NOW + 1_000));
        // A question this TV has nothing to check against isn't a guess
        FakeContext none = new FakeContext("com.thesiegs.hearth");
        for (int i = 0; i < 20; i++) assertEquals("unknown", answer(none, "tv-b", "ha:" + V1, OTHER, NOW));
        assertEquals(0, HaParentPins.lockouts(none));
    }

    @Test
    public void anAnswerIsReadForWhatItSays_andAnythingElseIsCantSay() throws Exception {
        assertEquals(HaParentPins.PeerAnswer.Kind.UNKNOWN, HaParentPins.peerAnswer(null).kind);
        assertEquals(HaParentPins.PeerAnswer.Kind.UNKNOWN, HaParentPins.peerAnswer(new JSONObject()).kind);
        assertEquals(HaParentPins.PeerAnswer.Kind.UNKNOWN,
                HaParentPins.peerAnswer(new JSONObject().put("a", "maybe")).kind);
        assertEquals(HaParentPins.PeerAnswer.Kind.YES, HaParentPins.peerAnswer(new JSONObject().put("a", "yes")).kind);
        HaParentPins.PeerAnswer wait = HaParentPins.peerAnswer(new JSONObject().put("a", "wait").put("retry", 99_999_999));
        assertEquals(HaParentPins.PeerAnswer.Kind.WAIT, wait.kind);
        // Never more than the pad's longest wait
        assertEquals(ParentPinPolicy.MAX_MS / 1000, wait.retryS);
        assertEquals(0, HaParentPins.peerAnswer(new JSONObject().put("a", "no").put("retry", -5)).retryS);
    }
}
