package com.inpartnerhub.diligence.project.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/** An IPv4 or IPv6 CIDR range ({@code 203.0.113.0/24}, {@code 2001:db8::/32}) or a single address (PRD Bab 10.1). */
record IpRange(String text, byte[] network, int prefixLength) {

    // Literals only: InetAddress.getByName must never trigger a DNS lookup for a configured range.
    private static final Pattern LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    static IpRange parse(String value) {
        var text = value == null ? "" : value.strip();
        var slash = text.indexOf('/');
        var address = slash < 0 ? text : text.substring(0, slash);
        if (address.isEmpty() || !LITERAL.matcher(address).matches()) {
            throw invalid(text);
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            throw invalid(text);
        }
        var max = bytes.length * 8;
        int prefix;
        try {
            prefix = slash < 0 ? max : Integer.parseInt(text.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw invalid(text);
        }
        if (prefix < 0 || prefix > max) {
            throw invalid(text);
        }
        return new IpRange(text, bytes, prefix);
    }

    boolean contains(String ip) {
        if (ip == null || !LITERAL.matcher(ip).matches()) {
            return false;
        }
        byte[] candidate;
        try {
            candidate = InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        if (candidate.length != network.length) {
            return false;
        }
        var fullBytes = prefixLength / 8;
        for (var i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        var remaining = prefixLength % 8;
        if (remaining == 0) {
            return true;
        }
        var mask = (byte) (0xff << (8 - remaining));
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private static InvalidRequestException invalid(String text) {
        return new InvalidRequestException("Not an IP address or CIDR range: " + text);
    }
}
