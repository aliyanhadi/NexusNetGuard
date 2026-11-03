package com.nexus.netguard.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Benchmarks public DNS resolvers with real UDP queries so the user can pick the fastest one.
 *
 * Android does not let a normal (non-root) app change another app's DNS directly. The supported
 * route is the system "Private DNS" (DNS-over-TLS) setting, which is why each Server carries a
 * privateDnsHost the UI can hand to the user. This class only measures; it never touches other apps.
 */
public final class DnsResolverManager {

    private static final SecureRandom RNG = new SecureRandom();

    private DnsResolverManager() {}

    public static final class Server {
        public final String name;
        public final String ip;
        public final String privateDnsHost;

        public Server(String name, String ip, String privateDnsHost) {
            this.name = name;
            this.ip = ip;
            this.privateDnsHost = privateDnsHost;
        }
    }

    public static final class Result {
        public final Server server;
        /** Median round-trip in ms, or -1 if every attempt failed. */
        public final int medianMs;
        public final int successes;
        public final int attempts;

        Result(Server server, int medianMs, int successes, int attempts) {
            this.server = server;
            this.medianMs = medianMs;
            this.successes = successes;
            this.attempts = attempts;
        }
    }

    public static List<Server> defaults() {
        return Arrays.asList(
                new Server("Cloudflare", "1.1.1.1", "one.one.one.one"),
                new Server("Google", "8.8.8.8", "dns.google"),
                new Server("Quad9", "9.9.9.9", "dns.quad9.net"));
    }

    /** Blocking call - run it off the main thread. Results are sorted fastest-first, failures last. */
    public static List<Result> benchmark(List<Server> servers, int samples, int timeoutMs) {
        List<Result> out = new ArrayList<>();
        for (Server s : servers) {
            List<Integer> rtts = new ArrayList<>();
            for (int i = 0; i < samples; i++) {
                try {
                    rtts.add((int) queryOnce(s.ip, timeoutMs));
                } catch (IOException ignored) {
                    // counted as a failed attempt
                }
            }
            int median = -1;
            if (!rtts.isEmpty()) {
                Collections.sort(rtts);
                median = rtts.get(rtts.size() / 2);
            }
            out.add(new Result(s, median, rtts.size(), samples));
        }
        Collections.sort(out, new Comparator<Result>() {
            @Override
            public int compare(Result a, Result b) {
                if (a.medianMs < 0 && b.medianMs < 0) return 0;
                if (a.medianMs < 0) return 1;
                if (b.medianMs < 0) return -1;
                return Integer.compare(a.medianMs, b.medianMs);
            }
        });
        return out;
    }

    private static long queryOnce(String ip, int timeoutMs) throws IOException {
        int id = RNG.nextInt(0x10000);
        byte[] query = buildQuery("example.com", id);
        InetAddress addr = InetAddress.getByName(ip); // literal IP: no lookup happens
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            long t0 = System.nanoTime();
            socket.send(new DatagramPacket(query, query.length, addr, 53));
            byte[] buf = new byte[512];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            int rid = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
            if (resp.getLength() < 12 || rid != id) throw new IOException("Bad DNS response");
            return ms;
        }
    }

    static byte[] buildQuery(String host, int id) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8);
        o.write(id & 0xFF);
        o.write(0x01); // flags: recursion desired
        o.write(0x00);
        o.write(0x00); o.write(0x01); // QDCOUNT = 1
        o.write(0x00); o.write(0x00); // ANCOUNT
        o.write(0x00); o.write(0x00); // NSCOUNT
        o.write(0x00); o.write(0x00); // ARCOUNT
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            o.write(bytes.length);
            o.write(bytes, 0, bytes.length);
        }
        o.write(0x00);
        o.write(0x00); o.write(0x01); // QTYPE A
        o.write(0x00); o.write(0x01); // QCLASS IN
        return o.toByteArray();
    }
}
