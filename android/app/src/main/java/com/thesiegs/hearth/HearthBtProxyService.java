package com.thesiegs.hearth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import com.thesiegs.esphome.proxy.android.EspProxyService;

/**
 * The ESPHome Bluetooth proxy's foreground service (BtProxy), in a process of its own (":btproxy" in the manifest): a
 * crash here never takes the launcher or its enforcement down. Only Hearth's words on the notification differ from
 * the library's service. Nothing else of Hearth runs in this process.
 */
public class HearthBtProxyService extends EspProxyService {
    private static final String CHANNEL = "bt_proxy";

    @Override
    public Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Bluetooth for Home Assistant", NotificationManager.IMPORTANCE_MIN));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Sharing Bluetooth with Home Assistant")
                .setContentText("Home Assistant hears nearby phones and watches through this TV, for who's in the room.")
                .setOngoing(true)
                .build();
    }
}
