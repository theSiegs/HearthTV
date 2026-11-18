package com.thesiegs.hearth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A Parent PIN as a TV keeps it (ParentPinHash in Dart): never the PIN, {@code v2$<iterations>$<salt>$<hash>}
 * (PBKDF2-HMAC-SHA256, hex). Only that form is checked. The old unsalted form (64 hex digits) is no longer one: a
 * four-digit PIN kept that way is the PIN for whoever holds it, so a value still stored so is worked out once
 * ({@link #pinOfLegacy}) and kept salted.
 *
 * <p>A <b>version</b> ({@link #isTag}) has the same shape with 0 iterations, so it checks no PIN anywhere, on any
 * build: it only says which Parent PIN a TV follows, and is what the household's TVs tell each other in place of the
 * hash (docs/household-sync.md, Parent PIN).
 */
final class ParentPinHash {
    static final Pattern SALTED = Pattern.compile("^v2\\$[0-9]{1,7}\\$[0-9a-f]{32}\\$[0-9a-f]{64}$");
    static final Pattern LEGACY = Pattern.compile("^[0-9a-f]{64}$");
    static final int ITERATIONS = 10_000;
    private static final String TAG_PREFIX = "v2$0$";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ParentPinHash() {
    }

    static boolean verify(String pin, String stored) {
        if (pin == null || stored == null || !SALTED.matcher(stored).matches()) return false;
        try {
            String[] parts = stored.split("\\$");
            int cost = Integer.parseInt(parts[1]);
            if (cost < 1 || cost > 200_000) return false;
            byte[] derived = pbkdf2(pin.getBytes(StandardCharsets.UTF_8), unhex(parts[2]), cost, 32);
            return same(Hex.of(derived), parts[3]);
        } catch (Exception e) {
            return false;
        }
    }

    /** What a TV keeps for {@code pin}: the salted form, with a fresh salt. Null if it can't be made. */
    static String make(String pin) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        try {
            byte[] derived = pbkdf2(pin.getBytes(StandardCharsets.UTF_8), salt, ITERATIONS, 32);
            return "v2$" + ITERATIONS + "$" + Hex.of(salt) + "$" + Hex.of(derived);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether {@code value} is a version: the stored shape, but checking no PIN. */
    static boolean isTag(String value) {
        return value != null && value.startsWith(TAG_PREFIX) && SALTED.matcher(value).matches();
    }

    /**
     * The version of a stored salted hash (a version is its own): the same on every TV that holds that hash, and of no
     * use for guessing the PIN without it (the hash's random salt is hashed in). Null for anything else.
     */
    static String tagOf(String stored) {
        if (stored == null || !SALTED.matcher(stored).matches()) return null;
        if (isTag(stored)) return stored;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String a = Hex.of(sha.digest(("hearth-parent-pin-version:" + stored).getBytes(StandardCharsets.UTF_8)));
            String b = Hex.of(sha.digest(("hearth-parent-pin-version-2:" + stored).getBytes(StandardCharsets.UTF_8)));
            return TAG_PREFIX + a.substring(0, 32) + "$" + b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The PIN an old unsalted value was made from (four digits, as every pad took), or null when it isn't one. This
     * is the migration for values still stored that way, and why the form went: it takes a moment.
     */
    static String pinOfLegacy(String stored) {
        if (stored == null || !LEGACY.matcher(stored).matches()) return null;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < 10_000; i++) {
                String pin = String.format(java.util.Locale.ROOT, "%04d", i);
                if (same(Hex.of(sha.digest(("ltv-parent-pin:" + pin).getBytes(StandardCharsets.UTF_8))), stored)) {
                    return pin;
                }
            }
        } catch (Exception e) {
            // Not one
        }
        return null;
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018), as the Dart side computes it. */
    static byte[] pbkdf2(byte[] password, byte[] salt, int cost, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA256"));
        byte[] out = new byte[length];
        int done = 0;
        for (int block = 1; done < length; block++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < cost; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        return out;
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
