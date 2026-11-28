package com.thesiegs.hearth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the launcher after boot when the "Start on boot" setting is on. Meant for devices whose
 * stock launcher always wins HOME resolution (Google TV, Fire TV). Android 10+ only allows this
 * background activity start while the app holds an exemption, e.g. the Home Button Fix
 * accessibility service is enabled or the overlay permission is granted; otherwise it is dropped.
 *
 * <p>An update of Hearth (MY_PACKAGE_REPLACED) kills its processes, the Bluetooth proxy's too, and nothing else
 * starts them again until Hearth is next in front: so the services start here after an update as well, but the
 * screen doesn't open (an update mustn't put Hearth in front of what's on the TV).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        // In another Google TV profile's user, this is that profile starting: its agent goes with it
        if (AgentService.isAgent(context)) {
            AgentService.start(context);
            return;
        }
        // Home Assistant's link, whether or not Hearth's screen opens at boot
        HaBridgeService.sync(context);
        RemoteApiService.sync(context);
        // Bluetooth for Home Assistant, when an adult turned it on
        BtProxy.sync(context);
        if (!boot) return;
        // On unless turned off (SettingsService.startOnBoot)
        if (!FlutterPrefs.getBoolean(context, "start_on_boot", true)) return;
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
