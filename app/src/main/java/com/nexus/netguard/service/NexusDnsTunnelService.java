package com.nexus.netguard.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DNS Boost: a system-wide DNS changer that does NOT route your game traffic.
 *
 * The VPN advertises a virtual DNS server (10.78.0.1) and routes ONLY that one address into the
 * tunnel. Every app's DNS lookups (including the game's) arrive here, get answered from a small
 * cache or forwarded to your chosen fast resolver over a protect()ed socket, and the reply is
 * written back. All other traffic bypasses the tunnel entirely, so game packets never pass through
 * this app and gain no extra latency. Android allows only one VPN at a time, so this mode and the
 * Firewall mode are mutually exclusive.
 */
public class NexusDnsTunnelService extends VpnService {

    public static final String ACTION_START = "com.nexus.netguard.action.DNS_START";
    public static final String ACTION_STOP = "com.nexus.netguard.action.DNS_STOP";
    public static final String KEY_UPSTREAM = "dns_upstream";
    public static final String DEFAULT_UPSTREAM = "1.1.1.1,8.8.8.8";

    private static final String TAG = "NexusDns";
    private static final String CHANNEL_ID = "nexus_vpn";
    private static final int NOTIF_ID = 78;
    private static final String VIRTUAL_DNS = "10.78.0.1";
    private static final byte[] VDNS = {10, 78, 0, 1};
    private static final int CACHE_MAX = 500;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong QUERIES = new AtomicLong(0);
    private static final AtomicLong CACHE_HITS = new AtomicLong(0);

    private static final class Cached {
        final byte[] data;
        final long expiryMs;
        Cached(byte[] data, long expiryMs) { this.data = data; this.expiryMs = expiryMs; }
    }

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private ParcelFileDescriptor tun;
    private Reader reader;
    private ExecutorService pool;
    private FileOutputStream out;
    private volatile List<String> upstream = new ArrayList<>();

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, NexusDnsTunnelService.class).setAction(ACTION_START));
    }

    public static void stop(Context c) {
        if (!RUNNING.get()) return;
        c.startService(new Intent(c, NexusDnsTunnelService.class).setAction(ACTION_STOP));
    }

    public static boolean isRunning() { return RUNNING.get(); }
    public static long queries() { return QUERIES.get(); }
    public static long cacheHits() { return CACHE_HITS.get(); }

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
        stopTunnel();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    // ---- tunnel ----

    private synchronized boolean startTunnel() {
        stopTunnelInternal();
        SharedPreferences sp = getSharedPreferences(NexusVpnTunnelService.PREFS, MODE_PRIVATE);
        upstream = parseUpstream(sp.getString(KEY_UPSTREAM, DEFAULT_UPSTREAM));

        Builder b = new Builder().setSession("Nexus DNS Boost").setMtu(1500);
        try {
            b.addAddress("10.78.0.2", 32);
            b.addRoute(VIRTUAL_DNS, 32);
            b.addDnsServer(VIRTUAL_DNS);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Bad tunnel config", e);
            return false;
        }
        ParcelFileDescriptor fd;
        try {
            fd = b.establish();
        } catch (RuntimeException e) {
            Log.e(TAG, "establish() failed", e);
            return false;
        }
        if (fd == null) return false;

        tun = fd;
        out = new FileOutputStream(fd.getFileDescriptor());
        pool = Executors.newFixedThreadPool(6);
        cache.clear();
        QUERIES.set(0);
        CACHE_HITS.set(0);
        reader = new Reader(fd.getFileDescriptor());
        reader.start();
        RUNNING.set(true);
        return true;
    }

    private synchronized void stopTunnel() {
        stopTunnelInternal();
    }

    private void stopTunnelInternal() {
        if (reader != null) {
            reader.halt = true;
            try {
                reader.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reader = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        out = null;
        RUNNING.set(false);
    }

    private static List<String> parseUpstream(String csv) {
        List<String> list = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (t.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") && list.size() < 3) list.add(t);
            }
        }
        if (list.isEmpty()) list.addAll(Arrays.asList("1.1.1.1", "8.8.8.8"));
        return list;
    }

    private final class Reader extends Thread {
        private final FileDescriptor fd;
        volatile boolean halt = false;

        Reader(FileDescriptor fd) {
            super("nexus-dns-reader");
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
                    if (Os.poll(set, 500) <= 0) continue;
                    if ((p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) break;
                    if ((p.revents & OsConstants.POLLIN) != 0) {
                        int n = Os.read(fd, buf, 0, buf.length);
                        if (n > 0) handlePacket(buf, n);
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

    // ---- packet handling ----

    private void handlePacket(byte[] buf, int n) {
        if (n < 28 || ((buf[0] >> 4) & 0xF) != 4) return;
        int ihl = (buf[0] & 0xF) * 4;
        if (n < ihl + 8 || (buf[9] & 0xFF) != 17) return; // UDP only
        if (buf[16] != VDNS[0] || buf[17] != VDNS[1] || buf[18] != VDNS[2] || buf[19] != VDNS[3]) return;
        int dstPort = ((buf[ihl + 2] & 0xFF) << 8) | (buf[ihl + 3] & 0xFF);
        if (dstPort != 53) return;
        final int srcPort = ((buf[ihl] & 0xFF) << 8) | (buf[ihl + 1] & 0xFF);
        final byte[] srcIp = Arrays.copyOfRange(buf, 12, 16);
        final byte[] query = Arrays.copyOfRange(buf, ihl + 8, n);
        ExecutorService ex = pool;
        if (ex == null) return;
        try {
            ex.execute(new Runnable() {
                @Override
                public void run() { resolve(query, srcIp, srcPort); }
            });
        } catch (RuntimeException ignored) {
            // pool shut down
        }
    }

    private void resolve(byte[] query, byte[] srcIp, int srcPort) {
        if (query.length < 12) return;
        QUERIES.incrementAndGet();
        String key = new String(query, 2, query.length - 2, StandardCharsets.ISO_8859_1);
        byte[] resp = null;
        Cached c = cache.get(key);
        if (c != null && c.expiryMs > System.currentTimeMillis()) {
            resp = c.data.clone();
            resp[0] = query[0];
            resp[1] = query[1];
            CACHE_HITS.incrementAndGet();
        } else {
            resp = forward(query);
            if (resp != null) maybeCache(key, resp);
        }
        if (resp != null) writeReply(resp, srcIp, srcPort);
    }

    private byte[] forward(byte[] query) {
        for (String ip : upstream) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s); // keep our own upstream traffic OUT of the tunnel
                s.setSoTimeout(1500);
                s.connect(InetAddress.getByName(ip), 53);
                s.send(new DatagramPacket(query, query.length));
                byte[] buf = new byte[4096];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                s.receive(r);
                if (r.getLength() >= 12 && buf[0] == query[0] && buf[1] == query[1]) {
                    return Arrays.copyOf(buf, r.getLength());
                }
            } catch (IOException ignored) {
                // try next upstream
            }
        }
        return null;
    }

    private void maybeCache(String key, byte[] resp) {
        int rcode = resp[3] & 0x0F;
        int an = ((resp[6] & 0xFF) << 8) | (resp[7] & 0xFF);
        if (rcode != 0 || an == 0) return;
        int ttl = minTtl(resp);
        if (ttl <= 0) return;
        ttl = Math.max(10, Math.min(ttl, 300));
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(key, new Cached(resp, System.currentTimeMillis() + ttl * 1000L));
    }

    /** Smallest TTL in the answer section, or -1 if the message can't be parsed. */
    private static int minTtl(byte[] m) {
        try {
            int qd = ((m[4] & 0xFF) << 8) | (m[5] & 0xFF);
            int an = ((m[6] & 0xFF) << 8) | (m[7] & 0xFF);
            int pos = 12;
            for (int i = 0; i < qd; i++) pos = skipName(m, pos) + 4;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < an; i++) {
                pos = skipName(m, pos);
                long ttl = ((long) (m[pos + 4] & 0xFF) << 24) | ((m[pos + 5] & 0xFF) << 16)
                        | ((m[pos + 6] & 0xFF) << 8) | (m[pos + 7] & 0xFF);
                int rdlen = ((m[pos + 8] & 0xFF) << 8) | (m[pos + 9] & 0xFF);
                min = Math.min(min, ttl);
                pos += 10 + rdlen;
            }
            return min == Long.MAX_VALUE ? -1 : (int) Math.min(min, Integer.MAX_VALUE);
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static int skipName(byte[] m, int pos) {
        while (true) {
            int len = m[pos] & 0xFF;
            if (len == 0) return pos + 1;
            if ((len & 0xC0) == 0xC0) return pos + 2;
            pos += 1 + len;
        }
    }

    private void writeReply(byte[] resp, byte[] dstIp, int dstPort) {
        int total = 28 + resp.length;
        FileOutputStream o = out;
        if (total > 1500 || o == null) return;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[2] = (byte) (total >> 8);
        p[3] = (byte) total;
        p[6] = 0x40; // don't fragment
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(VDNS, 0, p, 12, 4);
        System.arraycopy(dstIp, 0, p, 16, 4);
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int csum = ~sum & 0xFFFF;
        p[10] = (byte) (csum >> 8);
        p[11] = (byte) csum;
        p[21] = 53;
        p[22] = (byte) (dstPort >> 8);
        p[23] = (byte) dstPort;
        int udpLen = 8 + resp.length;
        p[24] = (byte) (udpLen >> 8);
        p[25] = (byte) udpLen; // UDP checksum 0 = not used (legal for IPv4)
        System.arraycopy(resp, 0, p, 28, resp.length);
        try {
            synchronized (o) {
                o.write(p);
            }
        } catch (IOException ignored) {
            // tunnel closing
        }
    }

    // ---- notification ----

    private void enterForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Firewall status", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Nexus DNS Boost active")
                .setContentText("Fast cached DNS for all apps. Game traffic is not routed through the app.")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
