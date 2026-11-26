package com.thesiegs.hearth;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.thesiegs.esphome.proxy.MacAddress;
import com.thesiegs.esphome.proxy.ProxyIdentity;
import com.thesiegs.esphome.proxy.ProxyServer;
import com.thesiegs.esphome.proxy.android.EspProxy;
import com.thesiegs.esphome.proxy.android.EspProxyConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * "Share Bluetooth with Home Assistant": this TV as an ESPHome Bluetooth proxy (the vendored
 * android/esphome-proxy modules), so Home Assistant hears nearby phones and watches through it for room presence.
 * Off unless an adult turns it on, here on the TV or with the TV's switch in Home Assistant (Hearth's
 * docs/contracts/hearth-tv.md, "Bluetooth sharing").
 *
 * The proxy runs in {@link HearthBtProxyService}, a process of its own (":btproxy"), so a crash there never takes the
 * launcher or its enforcement down. Everything here runs in the launcher's process and only in the TV owner's user.
 *
 * Who decides: one value with a revision. Home Assistant's word ({@code bluetooth_proxy} in the state document) carries
 * {@code on} and {@code rev}; a change made on the TV keeps the revision it was made against and is marked
 * {@code changed} until Home Assistant takes it (its revision goes up). So the later change wins either way, without
 * comparing clocks ({@link #fromHomeAssistant}).
 */
final class BtProxy {
    private static final String TAG = "HearthBtProxy";
    private static final String PREFS = "hearth_bt_proxy";
    private static final String K_ON = "on";
    private static final String K_REV = "rev";
    private static final String K_CHANGED = "changed";
    /** The ESPHome encryption key (Noise PSK), made once; never logged, sent only to the paired Home Assistant. */
    private static final String K_KEY = "key";
    /** The ESPHome node name, chosen once, so a renamed TV is still the same device to ESPHome. */
    private static final String K_NODE = "node";
    private static final String K_AREA = "area";

    static final String OFF = "off";
    static final String RUNNING = "running";
    static final String NEEDS_PERMISSION = "needs_permission";
    static final String BLUETOOTH_OFF = "bluetooth_off";
    static final String LOCATION_OFF = "location_off";
    /** Wanted, permitted, but the service isn't running (Android refused to start it from the background, say). */
    static final String STOPPED = "stopped";

    private static final long REPORT_AFTER_START_MS = 5_000;

    private BtProxy() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context context) {
        return prefs(context).getBoolean(K_ON, false);
    }

    /** What the TV keeps after Home Assistant's word: {on, rev, changed}. */
    static final class Word {
        final boolean on;
        final int rev;
        final boolean changed;

        Word(boolean on, int rev, boolean changed) {
            this.on = on;
            this.rev = rev;
            this.changed = changed;
        }
    }

    /**
     * The TV's value after Home Assistant says {@code haOn} at {@code haRev}. A newer revision is Home Assistant's
     * change (or its taking the TV's): it wins. The same revision while the TV has a change of its own not yet taken
     * keeps the TV's (Home Assistant takes it from the next status). An older revision (Home Assistant's own record
     * started again) keeps the TV's too: Home Assistant takes the TV's value then.
     */
    static Word fromHomeAssistant(boolean on, int rev, boolean changed, boolean haOn, int haRev) {
        if (haRev > rev) return new Word(haOn, haRev, false);
        if (haRev == rev && !changed) return new Word(haOn, rev, false);
        return new Word(on, rev, changed);
    }

    /** An adult turned it on or off on the TV: kept, and told to Home Assistant with the next status. */
    static void setOnTv(Context context, boolean on) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(K_ON, false) == on && !p.getBoolean(K_CHANGED, false)) return;
        p.edit().putBoolean(K_ON, on).putBoolean(K_CHANGED, true).apply();
        sync(context);
        reportSoon();
    }

    /** Home Assistant's word in a state document (null or no key: an older Home Assistant, nothing changes). */
    static void applyDocument(Context context, JSONObject state) {
        JSONObject word = state == null ? null : state.optJSONObject("bluetooth_proxy");
        if (word == null || !word.has("on")) return;
        SharedPreferences p = prefs(context);
        boolean before = p.getBoolean(K_ON, false);
        Word next = fromHomeAssistant(before, p.getInt(K_REV, 0), p.getBoolean(K_CHANGED, false),
                word.optBoolean("on", false), word.optInt("rev", 0));
        String area = word.isNull("area") ? "" : word.optString("area", "");
        String areaBefore = p.getString(K_AREA, "");
        p.edit().putBoolean(K_ON, next.on).putInt(K_REV, next.rev).putBoolean(K_CHANGED, next.changed)
                .putString(K_AREA, area).apply();
        if (next.on != before || next.on && !area.equals(areaBefore)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                sync(context);
                reportSoon();
            });
        }
    }

    /** The encryption key, made the first time it's needed. */
    static synchronized String key(Context context) {
        SharedPreferences p = prefs(context);
        String key = p.getString(K_KEY, null);
        if (key == null) {
            key = EspProxy.INSTANCE.generateKey();
            p.edit().putString(K_KEY, key).apply();
        }
        return key;
    }

    /** The TV's name as Hearth shows it ("Living room TV"), and as Home Assistant's device for the proxy. */
    static String friendlyName(Context context) {
        return ShareDiscovery.deviceName(context) + " Bluetooth";
    }

    /** The ESPHome node name ("living-room-tv-bluetooth"), chosen once. */
    static synchronized String nodeName(Context context) {
        SharedPreferences p = prefs(context);
        String node = p.getString(K_NODE, null);
        if (node == null) {
            node = ProxyIdentity.Companion.sanitizeName(friendlyName(context));
            p.edit().putString(K_NODE, node).apply();
        }
        return node;
    }

    /** The MAC Home Assistant's ESPHome entry knows the proxy by, as the service derives it ("aa:bb:cc:dd:ee:ff"). */
    @SuppressLint("HardwareIds")
    static String mac(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return MacAddress.INSTANCE.format(ProxyIdentity.Companion.deriveMac(id == null ? "unknown" : id)).toLowerCase();
    }

    static List<String> missingPermissions(Context context) {
        return EspProxy.INSTANCE.missingPermissions(context);
    }

    /** The permissions to ask for when an adult turns it on (location, Bluetooth scanning, the notification). */
    static String[] requiredPermissions() {
        return EspProxy.INSTANCE.requiredPermissions();
    }

    /**
     * Starts, refreshes or stops the proxy to match the setting. Safe to call often (Hearth's start, its screen coming
     * to the front, boot): a start of a running proxy with the same settings keeps it as it is, and lets Android give
     * it the location type again when Hearth is in front.
     */
    static void sync(Context context) {
        Context app = context.getApplicationContext();
        if (AgentService.isAgent(app)) return;
        boolean wanted = isOn(app);
        if (!wanted || !missingPermissions(app).isEmpty()) {
            if (isServiceRunning(app)) {
                EspProxy.INSTANCE.stop(app, HearthBtProxyService.class);
                Log.i(TAG, wanted ? "Stopped: permissions missing" : "Stopped");
            }
            return;
        }
        try {
            EspProxy.INSTANCE.start(app, new EspProxyConfig(nodeName(app), friendlyName(app),
                    prefs(app).getString(K_AREA, ""), key(app), ProxyServer.DEFAULT_PORT, null),
                    HearthBtProxyService.class);
        } catch (RuntimeException e) {
            // Android refuses a foreground start from the background; Hearth's next time in front tries again
            Log.w(TAG, "Couldn't start the Bluetooth proxy: " + e.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation")
    static boolean isServiceRunning(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        try {
            // An app still sees its own services here
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(200)) {
                if (info.service != null && context.getPackageName().equals(info.service.getPackageName())
                        && HearthBtProxyService.class.getName().equals(info.service.getClassName())) {
                    return info.started;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static boolean bluetoothOn(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What the setting comes to now (OFF, NEEDS_PERMISSION, LOCATION_OFF, BLUETOOTH_OFF, RUNNING or STOPPED). */
    static String state(Context context) {
        return state(isOn(context), missingPermissions(context).isEmpty(), EspProxy.INSTANCE.isLocationEnabled(context),
                bluetoothOn(context), isServiceRunning(context));
    }

    static String state(boolean on, boolean permitted, boolean locationOn, boolean bluetoothOn, boolean running) {
        if (!on) return OFF;
        if (!permitted) return NEEDS_PERMISSION;
        if (!running) return STOPPED;
        if (!bluetoothOn) return BLUETOOTH_OFF;
        if (!locationOn) return LOCATION_OFF;
        return RUNNING;
    }

    /**
     * The status's {@code bluetooth_proxy}: the TV's value and revision, whether it's a change of its own, and what it
     * comes to; while on, how ESPHome reaches the proxy, with its key (the status goes only to the paired Home
     * Assistant, over its secret).
     */
    static JSONObject statusJson(Context context) throws JSONException {
        SharedPreferences p = prefs(context);
        boolean on = p.getBoolean(K_ON, false);
        JSONObject out = new JSONObject();
        out.put("on", on);
        out.put("rev", p.getInt(K_REV, 0));
        out.put("changed", p.getBoolean(K_CHANGED, false));
        out.put("state", state(context));
        if (on) {
            out.put("name", nodeName(context));
            out.put("friendly_name", friendlyName(context));
            out.put("mac", mac(context));
            String host = LocalNet.ipv4Address();
            if (host != null) out.put("host", host);
            out.put("port", ProxyServer.DEFAULT_PORT);
            out.put("key", key(context));
        }
        return out;
    }

    /** Home Assistant hears of a change soon: once the service had time to start. Main thread. */
    static void reportSoon() {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(HaBridgeService::refreshNow);
        main.postDelayed(HaBridgeService::refreshNow, REPORT_AFTER_START_MS);
    }

    /** For the settings page: {on, state, paired-irrelevant details}. */
    static java.util.Map<String, Object> describe(Context context) {
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("on", isOn(context));
        out.put("state", state(context));
        out.put("missing", new java.util.ArrayList<>(missingPermissions(context)));
        return out;
    }
}
