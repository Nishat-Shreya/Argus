package com.argus.core;

import java.util.Map;

/**
 * AbuseIPDB report category code -&gt; display name (23 documented codes, plan §3.8). Data, not
 * logic; kept out of {@link AbuseIpdbResponseParser} so the parser's tests are not also table
 * tests. An unknown code degrades to "Category &lt;n&gt;" rather than throwing or vanishing, so
 * a code AbuseIPDB adds after this table is still displayed.
 */
final class AbuseCategories {

    private AbuseCategories() {}

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(1, "DNS Compromise"),
            Map.entry(2, "DNS Poisoning"),
            Map.entry(3, "Fraud Orders"),
            Map.entry(4, "DDoS Attack"),
            Map.entry(5, "FTP Brute-Force"),
            Map.entry(6, "Ping of Death"),
            Map.entry(7, "Phishing"),
            Map.entry(8, "Fraud VoIP"),
            Map.entry(9, "Open Proxy"),
            Map.entry(10, "Web Spam"),
            Map.entry(11, "Email Spam"),
            Map.entry(12, "Blog Spam"),
            Map.entry(13, "VPN IP"),
            Map.entry(14, "Port Scan"),
            Map.entry(15, "Hacking"),
            Map.entry(16, "SQL Injection"),
            Map.entry(17, "Spoofing"),
            Map.entry(18, "Brute-Force"),
            Map.entry(19, "Bad Web Bot"),
            Map.entry(20, "Exploited Host"),
            Map.entry(21, "Web App Attack"),
            Map.entry(22, "SSH"),
            Map.entry(23, "IoT Targeted"));

    /** Documented name, or "Category &lt;code&gt;" for a code AbuseIPDB added after this table. */
    static String nameOf(int code) {
        return NAMES.getOrDefault(code, "Category " + code);
    }
}
