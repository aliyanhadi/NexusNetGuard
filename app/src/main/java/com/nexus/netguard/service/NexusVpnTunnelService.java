package com.nexus.netguard.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import com.nexus.netguard.MainActivity;
import com.nexus.netguard.data.GamePackages;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-app firewall built on Android's VpnService (no root, no injection, no file tampering).
 *
 * How it works: the tunnel is created with addAllowedApplication() for ONLY the apps the user chose
 * to block. Those apps' traffic is routed into the tunnel, where it is read and discarded (a
 * "black hole"), so they have no connectivity. Every other app - including the game - is not part
 * of the VPN at all and keeps using the normal network path at full speed. The game's packages are
 * additionally hard-excluded here as a safety net.
 */
public class NexusVpnTunnelService extends VpnService {

    public static final String ACTION_START = "com.nexus.netguard.action.START";
    public static final String ACTION_STOP = "com.nexus.netguard.action.STOP";
    public static final String PREFS = "nexus_netguard_prefs";
    public static final String KEY_BLOCKED = "blocked_packages";

    private static final String TAG = "NexusVpn";
    private static final String CHANNEL_ID = "nexus_vpn";
    private static final int NOTIF_ID = 77;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong DROPPED = new AtomicLong(0);

    private ParcelFileDescriptor tun;
    private Drainer drainer;
    private int blockedCount = 0;

    // ---- Static control API (used by the UI layer) ----

    public static void start(Context c) {
        Intent i = new Intent(c, NexusVpnTunnelService.class).setAction(ACTION_START);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        // Plain startService is fine: if the service isn't running there is nothing to stop.
        if (!RUNNING.get()) return;
        Intent i = new Intent(c, NexusVpnTunnelService.class).setAction(ACTION_STOP);
        c.startService(i);
    }

    public static boolean isRunning() { return RUNNING.get(); }

    public static long droppedPackets() { return DROPPED.get(); }

    // ---- Service lifecycle ----

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        enterForeground();
        if (!startTunnel()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        // User turned the VPN off from system settings or another VPN took over.
        stopTunnel();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    // ---- Tunnel management ----

    private synchronized boolean startTunnel() {
        stopTunnelInternal();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> wanted = sp.getStringSet(KEY_BLOCKED, Collections.<String>emptySet());
        if (wanted == null) wanted = Collections.emptySet();

        Builder b = new Builder()
                .setSession("Nexus NetGuard Firewall")
                .setMtu(1500);
        try {
            b.addAddress("10.77.0.2", 32);
            b.addRoute("0.0.0.0", 0);
            // Also capture IPv6 so blocked apps can't bypass the black hole over v6.
            b.addAddress("fd77:77::2", 128);
            b.addRoute("::", 0);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Bad tunnel address/route", e);
            return false;
        }

        int added = 0;
        Set<String> applied = new HashSet<>();
        for (String pkg : wanted) {
            if (pkg.equals(getPackageName()) || GamePackages.PACKAGES.contains(pkg)) continue;
            try {
                b.addAllowedApplication(pkg);
                applied.add(pkg);
                added++;
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Skipping uninstalled package " + pkg);
            }
        }
        if (added == 0) {
            // With zero allowed apps Android would route EVERYTHING through the tunnel. Refuse.
            Log.w(TAG, "No valid apps to block; not starting tunnel");
            return false;
        }

        ParcelFileDescriptor fd;
        try {
            fd = b.establish();
        } catch (RuntimeException e) {
            Log.e(TAG, "establish() failed", e);
            return false;
        }
        if (fd == null) {
            Log.w(TAG, "VPN permission not granted");
            return false;
        }

        tun = fd;
        blockedCount = added;
        DROPPED.set(0);
        drainer = new Drainer(fd.getFileDescriptor());
        drainer.start();
        RUNNING.set(true);
        updateNotification();
        return true;
    }

    private synchronized void stopTunnel() {
        stopTunnelInternal();
    }

    private void stopTunnelInternal() {
        if (drainer != null) {
            drainer.halt = true;
            try {
                drainer.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drainer = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        RUNNING.set(false);
    }

    /** Reads and discards every packet from blocked apps. Uses poll() so it can be stopped cleanly. */
    private static final class Drainer extends Thread {
        private final FileDescriptor fd;
        volatile boolean halt = false;

        Drainer(FileDescriptor fd) {
            super("nexus-blackhole");
            this.fd = fd;
        }

        @Override
        public void run() {
            byte[] buf = new byte[32768];
            StructPollfd p = new StructPollfd();
            p.fd = fd;
            p.events = (short) OsConstants.POLLIN;
            StructPollfd[] set = new StructPollfd[]{p};
            while (!halt) {
                try {
                    p.revents = 0;
                    int ready = Os.poll(set, 500);
                    if (ready <= 0) continue;
                    if ((p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) break;
                    if ((p.revents & OsConstants.POLLIN) != 0) {
                        int n = Os.read(fd, buf, 0, buf.length);
                        if (n > 0) DROPPED.incrementAndGet();
                    }
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue;
                    break;
                } catch (InterruptedIOException e) {
                    break;
                }
            }
        }
    }

    // ---- Notification ----

    private void enterForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Firewall status", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Nexus NetGuard active")
                .setContentText("Blocking " + blockedCount + " background app(s). Game traffic untouched.")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
