package com.coursetrace.video;

import java.math.BigInteger;

public record ByteRange(long offset, long length, boolean partial) {
    public static ByteRange parse(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) return new ByteRange(0, size, false);
        String[] pair = header.substring(6).strip().split("-", -1);
        if (pair.length != 2 || size <= 0) throw new IllegalArgumentException("Unsatisfiable range");
        var total = BigInteger.valueOf(size);
        if (pair[0].isEmpty()) {
            var suffix = unsigned(pair[1]);
            if (suffix.signum() == 0) throw new IllegalArgumentException("Empty suffix");
            long length = suffix.min(total).longValueExact();
            return new ByteRange(size - length, length, true);
        }
        var start = unsigned(pair[0]);
        if (start.compareTo(total) >= 0) throw new IllegalArgumentException("Start beyond end");
        var end = pair[1].isEmpty() ? total.subtract(BigInteger.ONE) : unsigned(pair[1]).min(total.subtract(BigInteger.ONE));
        if (end.compareTo(start) < 0) throw new IllegalArgumentException("Inverted range");
        return new ByteRange(start.longValueExact(), end.subtract(start).longValueExact() + 1, true);
    }

    private static BigInteger unsigned(String text) {
        if (text.length() > 64 || !text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid range");
        return new BigInteger(text);
    }
}
