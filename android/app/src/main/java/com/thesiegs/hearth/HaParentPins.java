package com.thesiegs.hearth;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Which Parent PIN this TV takes, and the ones it has learned (docs/home-assistant-integration.md, Parent PINs;
 * docs/household-sync.md, Parent PIN). A TV is never sent a PIN's hash. It keeps, sealed with a Keystore key, only
 * hashes of PINs that were typed on it and confirmed: by Home Assistant ({@code verify_pin}), or by another TV of the
 * household.
 *
 * <p><b>Home Assistant sets the PINs</b> ({@link #managed}: the TV is paired and its last state document named
 * {@code parent_pins_version}). The integration is asked, and its answer and lockout are final. A yes is learned,
 * tagged with the version and with whose it is (the adult's key and version from {@code parent_pins_versions}); a no
 * drops a learned PIN that matches; a new version drops only the PINs of the adults whose version changed (all of
 * them when the document doesn't say each adult's, or for a PIN learned without saying whose). With Home Assistant
 * unreachable, a PIN learned here works, then the household's other TVs are asked whether they learned it, and
 * otherwise the PIN isn't taken ({@link Check#NO_HA}). The TV's own PIN doesn't work while Home Assistant sets them.
 *
 * <p><b>Home Assistant no longer sets them</b> (unpaired, or it has none now): the PINs learned from it are kept, so
 * the TV is never left without a Parent PIN; an adult can set a PIN of the TV's own again, which replaces them
 * ({@link #forget}). With none learned, the TV's own applies.
 *
 * <p><b>The TV's own</b> (the app keeps it: SettingsService in Dart): its hash where it was set or typed before; where
 * only its version is known (set on another TV of the household), the other TVs are asked, and a yes is learned here.
 */
final class HaParentPins {
    private static final String KEY_SEALED = "ha_parent_pins_sealed";
    private static final String KEY_VERSION = "ha_parent_pins_version";
    /** Each adult's version from the last document ({@code parent_pins_versions}: {adult key: version}), as JSON. */
    private static final String KEY_VERSIONS = "ha_parent_pins_versions";
    private static final String KEY_MANAGED = "ha_parent_pins_managed";
    /** Hashes kept in the clear by 2026.11.04: sealed on first read, then removed. */
    private static final String KEY_PLAIN = "ha_parent_pins";
    /** How many times a Parent PIN pad here locked out wrong guesses, for the integration (only goes up). */
    private static final String KEY_LOCKOUTS = "parent_pin_lockouts";
    /** The household's Parent PIN as learned from another TV: sealed {"v": its version, "h": [hashes]}. */
    private static final String KEY_TV_SEALED = "tv_parent_pins_sealed";
    /** Wrong PINs other TVs asked about, per asking TV: {"<tv id>": [failures in a row, locked until (ms)]}. */
    private static final String KEY_PEER_LOCKOUTS = "parent_pin_peer_lockouts";
    private static final String ALIAS = "hearth_ha_parent_pins";
    private static final String TV_ALIAS = "hearth_tv_parent_pins";
    /** The app's keys (SettingsService): the TV's own Parent PIN as a hash, and the version it follows. */
    static final String OWN_HASH = "device_parent_pin_hash";
    static final String OWN_VERSION = "device_parent_pin_version";
    /** At most this many learned PINs are kept (one per adult is all there are). */
    static final int MAX_LEARNED = 12;
    private static final int MAX_PEER_LOCKOUTS = 32;
    static final String SCOPE_HA = "ha:";
    static final String SCOPE_TV = "tv:";

    /**
     * What the integration said about a PIN: yes, no, locked out (no, whatever the PIN), or nothing (unreachable, or
     * it has none).
     */
    enum Verdict { YES, NO, LOCKED, UNKNOWN }

    /**
     * What a check of a PIN came to. NO_HA and NO_TVS: the PIN isn't one this TV has learned and whoever could say
     * wasn't reached (Home Assistant; the household's other TVs), so it wasn't taken. Both count as a wrong try.
     */
    enum Check {
        YES, NO, NO_HA, NO_TVS;

        /** For the app (FLauncherChannel.verifyParentPin). */
        String code() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What the household's other TVs said about a PIN. */
    static final class PeerAnswer {
        enum Kind { YES, NO, WAIT, UNKNOWN }

        static final PeerAnswer UNKNOWN = new PeerAnswer(Kind.UNKNOWN, 0);

        final Kind kind;
        /** For WAIT: how long the TV that answered locks this one out. */
        final int retryS;

        PeerAnswer(Kind kind, int retryS) {
            this.kind = kind;
            this.retryS = retryS;
        }
    }

    /** Who can be asked about a PIN (tests pass their own). */
    interface Asker {
        boolean paired(Context context);

        /** The integration's answer (blocking). */
        Verdict ha(Context context, String pin);

        /** The household's other TVs' answer for {@code scope} (blocking); UNKNOWN when none could say. */
        PeerAnswer peers(Context context, String scope, String pin);

        /** The integration's answer with whose PIN it is (blocking); by default {@link #ha}, naming nobody. */
        default HaAnswer haAnswer(Context context, String pin) {
            return new HaAnswer(ha(context, pin), null);
        }
    }

    /** What the integration said, and on a yes whose PIN it is ({@code person}, its person entity id; may be null). */
    static final class HaAnswer {
        final Verdict verdict;
        final String person;

        HaAnswer(Verdict verdict, String person) {
            this.verdict = verdict;
            this.person = person;
        }
    }

    private static final Asker REAL = new Asker() {
        @Override
        public boolean paired(Context context) {
            return HaIntegration.isPaired(context);
        }

        @Override
        public Verdict ha(Context context, String pin) {
            return HaIntegration.verifyPin(context, pin);
        }

        @Override
        public HaAnswer haAnswer(Context context, String pin) {
            return HaIntegration.verifyPinAnswer(context, pin);
        }

        @Override
        public PeerAnswer peers(Context context, String scope, String pin) {
            return Household.askParentPin(context, scope, pin);
        }
    };

    /** Set by tests in place of the Android Keystore. */
    static SecretBox.Keys sKeys;

    private HaParentPins() {}

    private static SecretBox.Keys keys() {
        return sKeys != null ? sKeys : SecretBox.keystore();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(LauncherAccessibilityService.DEVICE_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Takes the document's word on the parent PINs: {@code parent_pins_version} and, from an integration that sends
     * it, {@code parent_pins_versions} (each adult's: {adult key: version}); the hashes an older integration still
     * sends are not read. A version other than the one the learned PINs were confirmed under drops those of the
     * adults whose own version changed (or went), so one adult changing their PIN leaves the others' working with
     * Home Assistant down. A learned PIN that doesn't say whose, or a document without each adult's version, drops
     * them all, as before. The same version keeps them, which is how PINs kept by a build that took hashes go on
     * working. No version: Home Assistant has none, and the last ones stay, no longer managed.
     */
    static synchronized void applyDocument(Context context, JSONObject state) {
        if (state == null) return;
        String version = state.optString("parent_pins_version", "");
        JSONObject versions = state.optJSONObject("parent_pins_versions");
        SharedPreferences prefs = prefs(context);
        if (version.isEmpty()) {
            prefs.edit().putBoolean(KEY_MANAGED, false).apply();
            return;
        }
        // Read before the editor below is made: reading may reseal what an older build kept in the clear
        List<Learned> learned = version.equals(prefs.getString(KEY_VERSION, null)) ? null : learnedEntries(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (learned != null) {
            List<Learned> keep = new ArrayList<>();
            if (versions != null) {
                for (Learned l : learned) {
                    if (l.adult != null && l.adultVersion.equals(versions.optString(l.adult, ""))) keep.add(l);
                }
            }
            putLearned(editor.remove(KEY_PLAIN), keep);
            editor.putString(KEY_VERSION, version);
        }
        if (versions != null) editor.putString(KEY_VERSIONS, versions.toString()); else editor.remove(KEY_VERSIONS);
        editor.putBoolean(KEY_MANAGED, true);
        editor.apply();
    }

    /** The version this TV follows, for its status ("" when none). */
    static String version(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    /**
     * The key an adult goes by in {@code parent_pins_versions}, worked out from their person entity id as the
     * integration does (household.py {@code adult_key}), so no name is sent: the first 16 hex digits of SHA-256 of
     * "hearth-adult:" and the id. Null for none.
     */
    static String adultKey(String person) {
        if (person == null || person.isEmpty()) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("hearth-adult:" + person).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** A PIN learned from Home Assistant: its hash, and whose it is at which of their versions (both null: unsaid). */
    static final class Learned {
        final String hash;
        final String adult;
        final String adultVersion;

        Learned(String hash, String adult, String adultVersion) {
            this.hash = hash;
            boolean whose = adult != null && !adult.isEmpty() && adultVersion != null && !adultVersion.isEmpty();
            this.adult = whose ? adult : null;
            this.adultVersion = whose ? adultVersion : null;
        }
    }

    /**
     * The learned PINs, sealed as a JSON array, each a hash (learned without saying whose, or by an older build) or
     * {"h": hash, "a": adult key, "av": that adult's version}.
     */
    private static List<Learned> learnedEntries(Context context) {
        List<Learned> out = new ArrayList<>();
        String json = learnedJson(context);
        if (json == null) return out;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                Learned l = item instanceof JSONObject
                        ? new Learned(((JSONObject) item).optString("h", ""), ((JSONObject) item).optString("a", ""),
                                ((JSONObject) item).optString("av", ""))
                        : new Learned(array.optString(i, ""), null, null);
                // Not a version, and not the old unsalted form (nothing from Home Assistant ever was)
                if (ParentPinHash.SALTED.matcher(l.hash).matches() && !ParentPinHash.isTag(l.hash)) out.add(l);
            }
        } catch (Exception ignored) {
            // None kept
        }
        return out;
    }

    /** Seals {@code learned} into {@code editor}; with none, or when sealing fails, none is kept. */
    private static void putLearned(SharedPreferences.Editor editor, List<Learned> learned) {
        String sealed = null;
        if (!learned.isEmpty()) {
            try {
                JSONArray array = new JSONArray();
                for (Learned l : learned) {
                    array.put(l.adult == null ? l.hash
                            : new JSONObject().put("h", l.hash).put("a", l.adult).put("av", l.adultVersion));
                }
                sealed = SecretBox.sealText(keys(), ALIAS, array.toString());
            } catch (JSONException e) {
                sealed = null;
            }
        }
        if (sealed == null) editor.remove(KEY_SEALED); else editor.putString(KEY_SEALED, sealed);
    }

    /** The PINs learned from Home Assistant, as hashes (they're for {@link #version}). */
    static synchronized List<String> hashes(Context context) {
        List<String> out = new ArrayList<>();
        for (Learned l : learnedEntries(context)) out.add(l.hash);
        return out;
    }

    /** The learned PINs as sealed (opened); null for none. */
    private static String learnedJson(Context context) {
        SharedPreferences prefs = prefs(context);
        String json = null;
        String sealed = prefs.getString(KEY_SEALED, null);
        if (sealed != null) {
            json = SecretBox.openText(keys(), ALIAS, sealed);
        } else if (prefs.contains(KEY_PLAIN)) {
            // Kept in the clear by an older build: sealed now
            json = prefs.getString(KEY_PLAIN, "[]");
            String resealed = SecretBox.sealText(keys(), ALIAS, json);
            if (resealed != null) prefs.edit().putString(KEY_SEALED, resealed).remove(KEY_PLAIN).apply();
        }
        return json;
    }

    private static List<String> valid(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String hash = array.optString(i, "");
                // Not a version, and not the old unsalted form (nothing from Home Assistant ever was)
                if (ParentPinHash.SALTED.matcher(hash).matches() && !ParentPinHash.isTag(hash)) out.add(hash);
            }
        } catch (Exception ignored) {
            // None kept
        }
        return out;
    }

    /** Whether PINs learned from Home Assistant are kept here. */
    static boolean kept(Context context) {
        return !hashes(context).isEmpty();
    }

    /** Home Assistant sets the Parent PIN: the TV is paired and its last document named PINs. */
    static boolean managed(Context context) {
        return managed(context, REAL);
    }

    private static boolean managed(Context context, Asker asker) {
        return prefs(context).getBoolean(KEY_MANAGED, false) && asker.paired(context);
    }

    /**
     * Home Assistant's PINs are this TV's Parent PIN, in place of its own: it sets them now, or the ones learned from
     * it are still kept.
     */
    static boolean inPlace(Context context) {
        return inPlace(context, REAL);
    }

    static boolean inPlace(Context context, Asker asker) {
        return managed(context, asker) || kept(context);
    }

    /** A Parent PIN of the TV's own was set here: the ones learned from Home Assistant go. */
    static synchronized void forget(Context context) {
        prefs(context).edit().remove(KEY_SEALED).remove(KEY_VERSION).remove(KEY_VERSIONS).remove(KEY_MANAGED)
                .remove(KEY_PLAIN).apply();
    }

    /**
     * Home Assistant confirmed {@code pin} while this TV followed {@code version}: kept as a fresh salted hash, unless
     * the version changed meanwhile (the PIN may be one that was just replaced). Learned without saying whose.
     */
    static void learn(Context context, String version, String pin) {
        learn(context, version, pin, null);
    }

    /**
     * As {@link #learn(Context, String, String)}, for {@code person}'s PIN (Home Assistant's answer named them): kept
     * with their adult key and version, so another adult's PIN change leaves it ({@link #applyDocument}). One learned
     * before without saying whose is replaced by it.
     */
    static synchronized void learn(Context context, String version, String pin, String person) {
        SharedPreferences prefs = prefs(context);
        if (version == null || version.isEmpty() || !version.equals(prefs.getString(KEY_VERSION, null))) return;
        String adult = adultKey(person);
        String adultVersion = adult == null ? "" : adultVersions(prefs).optString(adult, "");
        boolean whose = !adultVersion.isEmpty();
        List<Learned> keep = new ArrayList<>();
        boolean known = false;
        for (Learned l : learnedEntries(context)) {
            boolean same = ParentPinHash.verify(pin, l.hash);
            if (same && l.adult == null && whose) continue;
            known |= same;
            keep.add(l);
        }
        if (known) return;
        String hash = ParentPinHash.make(pin);
        if (hash == null) return;
        keep.add(new Learned(hash, adult, adultVersion));
        while (keep.size() > MAX_LEARNED) keep.remove(0);
        SharedPreferences.Editor editor = prefs.edit().remove(KEY_PLAIN);
        putLearned(editor, keep);
        editor.apply();
    }

    /** Each adult's version as the last document said ({} for none). */
    private static JSONObject adultVersions(SharedPreferences prefs) {
        try {
            return new JSONObject(prefs.getString(KEY_VERSIONS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Home Assistant accepted {@code pin} some other way (a notice's button): learned for the version followed now. */
    static void learned(Context context, String pin) {
        if (pin != null && managed(context)) learn(context, version(context), pin);
    }

    /** Home Assistant said {@code pin} is no Parent PIN: a learned one that matches goes. */
    static synchronized void dropMatching(Context context, String pin) {
        List<Learned> learned = learnedEntries(context);
        List<Learned> left = new ArrayList<>();
        for (Learned l : learned) {
            if (!ParentPinHash.verify(pin, l.hash)) left.add(l);
        }
        if (left.size() == learned.size()) return;
        SharedPreferences.Editor editor = prefs(context).edit().remove(KEY_PLAIN);
        putLearned(editor, left);
        editor.apply();
    }

    /** Every one is checked, so the time doesn't tell which matched. */
    private static boolean matches(List<String> hashes, String pin) {
        boolean ok = false;
        for (String hash : hashes) ok |= ParentPinHash.verify(pin, hash);
        return ok;
    }

    // ---- The TV's own Parent PIN (the app keeps it), and the household's as learned from another TV ----

    /** The TV's own Parent PIN as the app keeps it: a hash, or (set on another TV) only its version. */
    static final class Own {
        /** A salted hash, or an old unsalted value the app hasn't replaced yet; null: none known here. */
        final String hash;
        /** The version followed; null with no hash: the TV has no Parent PIN of its own. */
        final String version;

        Own(String hash, String version) {
            this.hash = hash;
            this.version = version;
        }
    }

    static Own own(Context context) {
        String hash = FlutterPrefs.getString(context, OWN_HASH, null);
        String version = FlutterPrefs.getString(context, OWN_VERSION, null);
        if (!ParentPinHash.isTag(version)) version = null;
        // A build from before versions took another TV's version for a hash: it is one, with no hash known here
        if (ParentPinHash.isTag(hash)) return new Own(null, hash);
        if (hash != null && ParentPinHash.LEGACY.matcher(hash).matches()) return new Own(hash, version);
        if (hash == null || !ParentPinHash.SALTED.matcher(hash).matches()) return new Own(null, version);
        return new Own(hash, version != null ? version : ParentPinHash.tagOf(hash));
    }

    /** Whether {@code pin} is the one {@code stored} (the app's value) was made from. */
    private static boolean ownMatches(String pin, String stored) {
        // The old unsalted form is no longer checked as a hash: the PIN it stands for is worked out, as the app does
        // when it replaces the value (ParentPinHash.pinOfLegacy)
        if (ParentPinHash.LEGACY.matcher(stored).matches()) return pin.equals(ParentPinHash.pinOfLegacy(stored));
        return ParentPinHash.verify(pin, stored);
    }

    /** The hashes learned from another TV for {@code version} (empty: none, or for another version). */
    static synchronized List<String> tvLearned(Context context, String version) {
        String sealed = prefs(context).getString(KEY_TV_SEALED, null);
        if (sealed == null || version == null) return new ArrayList<>();
        try {
            JSONObject kept = new JSONObject(SecretBox.openText(keys(), TV_ALIAS, sealed));
            if (!version.equals(kept.optString("v", ""))) return new ArrayList<>();
            return valid(kept.optJSONArray("h") == null ? null : kept.optJSONArray("h").toString());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Another TV confirmed {@code pin} for {@code version}: kept, in place of anything learned for another version. */
    static synchronized void learnTv(Context context, String version, String pin) {
        if (!ParentPinHash.isTag(version) || matches(tvLearned(context, version), pin)) return;
        String hash = ParentPinHash.make(pin);
        if (hash == null) return;
        try {
            String sealed = SecretBox.sealText(keys(), TV_ALIAS,
                    new JSONObject().put("v", version).put("h", new JSONArray().put(hash)).toString());
            if (sealed != null) prefs(context).edit().putString(KEY_TV_SEALED, sealed).apply();
        } catch (JSONException ignored) {
            // Not kept: the other TVs are asked again
        }
    }

    /** The version of the household's Parent PIN learned here from another TV (null: none). */
    static synchronized String tvLearnedVersion(Context context) {
        String sealed = prefs(context).getString(KEY_TV_SEALED, null);
        if (sealed == null) return null;
        try {
            String version = new JSONObject(SecretBox.openText(keys(), TV_ALIAS, sealed)).optString("v", "");
            return ParentPinHash.isTag(version) && !tvLearned(context, version).isEmpty() ? version : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the TV's own Parent PIN can be checked here with no other TV: its hash, or one learned for its version. */
    static boolean ownKnown(Context context) {
        Own own = own(context);
        return own.hash != null || (own.version != null && !tvLearned(context, own.version).isEmpty());
    }

    // ---- Checking a PIN ----

    /**
     * Whether {@code pin} is a Parent PIN (see the class comment for who decides). Not on the main thread: it may ask
     * Home Assistant or the household's other TVs.
     */
    static Check check(Context context, String pin) {
        return check(context, pin, REAL);
    }

    static boolean verify(Context context, String pin) {
        return check(context, pin) == Check.YES;
    }

    static Check check(Context context, String pin, Asker asker) {
        if (pin == null || pin.isEmpty()) return Check.NO;
        if (managed(context, asker)) {
            // The version before asking: an answer is learned only for the version it was given under
            String version = version(context);
            HaAnswer said = asker.haAnswer(context, pin);
            Verdict verdict = said == null ? Verdict.UNKNOWN : said.verdict;
            if (verdict == Verdict.YES) {
                learn(context, version, pin, said.person);
                return Check.YES;
            }
            if (verdict == Verdict.NO) {
                dropMatching(context, pin);
                return Check.NO;
            }
            // Locked out there: no, even for a right PIN, which stays learned
            if (verdict == Verdict.LOCKED) return Check.NO;
            if (matches(hashes(context), pin)) return Check.YES;
            PeerAnswer answer = asker.peers(context, SCOPE_HA + version, pin);
            if (answer.kind == PeerAnswer.Kind.YES) {
                learn(context, version, pin);
                return Check.YES;
            }
            if (answer.kind == PeerAnswer.Kind.WAIT) return held(context, answer);
            return Check.NO_HA;
        }
        List<String> learned = hashes(context);
        if (!learned.isEmpty()) return matches(learned, pin) ? Check.YES : Check.NO;
        Own own = own(context);
        if (own.hash != null) return ownMatches(pin, own.hash) ? Check.YES : Check.NO;
        if (own.version == null) return Check.NO;
        return checkVersion(context, own.version, pin, asker);
    }

    /**
     * Whether {@code pin} is the household's Parent PIN of {@code version}, of which this TV has no hash: one learned
     * here for it decides; else the other TVs are asked, and a yes is learned.
     */
    static Check checkVersion(Context context, String version, String pin, Asker asker) {
        if (pin == null || pin.isEmpty() || !ParentPinHash.isTag(version)) return Check.NO;
        List<String> learned = tvLearned(context, version);
        if (!learned.isEmpty()) return matches(learned, pin) ? Check.YES : Check.NO;
        PeerAnswer answer = asker.peers(context, SCOPE_TV + version, pin);
        switch (answer.kind) {
            case YES:
                learnTv(context, version, pin);
                return Check.YES;
            case NO:
            case WAIT:
                // A no that starts a wait there holds the pad here as long
                return held(context, answer);
            default:
                return Check.NO_TVS;
        }
    }

    /**
     * Whether {@code pin} is the household's Parent PIN, for a TV that kept its own when it joined and now takes the
     * household's (Settings > Family). {@code state}: Household.parentPin.
     */
    static Check checkHousehold(Context context, java.util.Map<String, Object> state, String pin) {
        return checkHousehold(context, state, pin, REAL);
    }

    static Check checkHousehold(Context context, java.util.Map<String, Object> state, String pin, Asker asker) {
        if (state == null || pin == null || !"live".equals(state.get("state"))) return Check.NO;
        // A hash an older build left in the household's entry is as good as the other TVs' word
        Object hash = state.get("hash");
        if (hash instanceof String) return ParentPinHash.verify(pin, (String) hash) ? Check.YES : Check.NO;
        Object legacy = state.get("legacy");
        if (legacy instanceof String) {
            return pin.equals(ParentPinHash.pinOfLegacy((String) legacy)) ? Check.YES : Check.NO;
        }
        Object version = state.get("version");
        return version instanceof String ? checkVersion(context, (String) version, pin, asker) : Check.NO;
    }

    /** A TV that was asked locks this one out: the pad here waits as long (ParentPinPolicy), and the PIN is wrong. */
    private static Check held(Context context, PeerAnswer answer) {
        if (answer.retryS > 0) ParentPinPolicy.holdUntil(context, System.currentTimeMillis() + answer.retryS * 1000L);
        return Check.NO;
    }

    /** Whether the TV has a Parent PIN at all (one that can't be checked right now is still one). */
    static boolean any(Context context) {
        return any(context, REAL);
    }

    static boolean any(Context context, Asker asker) {
        if (inPlace(context, asker)) return true;
        Own own = own(context);
        return own.hash != null || own.version != null;
    }

    // ---- Answering another TV of the household ----

    /**
     * Another TV of the household ({@code peer}, a member whose key was checked) asks whether {@code pin} is the
     * Parent PIN of {@code scope}: {"a": "yes" | "no" | "unknown" | "wait", "retry": seconds}. This TV says only what
     * it can check itself, for that very version: its own hash or a learned one for the household's PIN (yes or no);
     * a PIN it learned from Home Assistant (yes, never no: another adult's may be right). Each asking TV has a count of
     * its own, on the pad's schedule (ParentPinPolicy.waitFor): a PIN that was checked and matched nothing is a wrong
     * try, and past the free ones that TV waits, whatever it asks.
     */
    static synchronized JSONObject answerPeer(Context context, String peer, String scope, String pin, long now) {
        JSONObject out = new JSONObject();
        try {
            if (peer == null || scope == null || pin == null || !pin.matches("[0-9]{4,12}")) {
                return out.put("a", "unknown");
            }
            JSONObject lockouts = peerLockouts(context);
            JSONArray lock = lockouts.optJSONArray(peer);
            int failures = lock == null ? 0 : lock.optInt(0, 0);
            long until = lock == null ? 0 : Math.min(lock.optLong(1, 0), now + ParentPinPolicy.MAX_MS);
            if (now < until) return out.put("a", "wait").put("retry", (int) ((until - now + 999) / 1000));
            // null: nothing here to check it against
            Boolean right = null;
            boolean decides = false;
            if (scope.startsWith(SCOPE_TV)) {
                String version = scope.substring(SCOPE_TV.length());
                Own own = own(context);
                decides = true;
                if (own.hash != null && version.equals(own.version)) {
                    right = ownMatches(pin, own.hash);
                } else {
                    List<String> learned = tvLearned(context, version);
                    if (!learned.isEmpty()) right = matches(learned, pin);
                }
            } else if (scope.startsWith(SCOPE_HA)) {
                String version = scope.substring(SCOPE_HA.length());
                List<String> learned = hashes(context);
                if (!version.isEmpty() && version.equals(version(context)) && !learned.isEmpty()) {
                    right = matches(learned, pin);
                }
            }
            if (right == null) return out.put("a", "unknown");
            if (right) {
                if (lock != null) {
                    lockouts.remove(peer);
                    savePeerLockouts(context, lockouts);
                }
                return out.put("a", "yes");
            }
            failures++;
            long wait = ParentPinPolicy.waitFor(failures);
            lockouts.put(peer, new JSONArray().put(failures).put(wait > 0 ? now + wait : 0));
            savePeerLockouts(context, lockouts);
            out.put("a", decides ? "no" : "unknown");
            if (wait > 0) {
                // Told to Home Assistant like a lockout of this TV's own pad
                noteLockout(context);
                out.put("retry", (int) ((wait + 999) / 1000));
            }
            return out;
        } catch (JSONException e) {
            return out;
        }
    }

    /** What another TV's answer ({@link #answerPeer}) says; UNKNOWN for none, or one that isn't an answer. */
    static PeerAnswer peerAnswer(JSONObject answer) {
        if (answer == null) return PeerAnswer.UNKNOWN;
        int retry = Math.max(0, Math.min(answer.optInt("retry", 0), (int) (ParentPinPolicy.MAX_MS / 1000)));
        switch (answer.optString("a", "")) {
            case "yes":
                return new PeerAnswer(PeerAnswer.Kind.YES, 0);
            case "no":
                return new PeerAnswer(PeerAnswer.Kind.NO, retry);
            case "wait":
                return new PeerAnswer(PeerAnswer.Kind.WAIT, retry);
            default:
                return PeerAnswer.UNKNOWN;
        }
    }

    private static JSONObject peerLockouts(Context context) {
        try {
            return new JSONObject(prefs(context).getString(KEY_PEER_LOCKOUTS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static long until(JSONObject lockouts, String peer) {
        JSONArray lock = lockouts.optJSONArray(peer);
        return lock == null ? 0 : lock.optLong(1, 0);
    }

    private static void savePeerLockouts(Context context, JSONObject lockouts) {
        // Only members get this far, so there are few; the ones with the least to wait for go first
        while (lockouts.length() > MAX_PEER_LOCKOUTS) {
            String least = null;
            for (Iterator<String> it = lockouts.keys(); it.hasNext(); ) {
                String id = it.next();
                if (least == null || until(lockouts, id) < until(lockouts, least)) least = id;
            }
            lockouts.remove(least);
        }
        prefs(context).edit().putString(KEY_PEER_LOCKOUTS, lockouts.toString()).commit();
    }

    static synchronized void noteLockout(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putInt(KEY_LOCKOUTS, prefs.getInt(KEY_LOCKOUTS, 0) + 1).apply();
    }

    static int lockouts(Context context) {
        return prefs(context).getInt(KEY_LOCKOUTS, 0);
    }
}
