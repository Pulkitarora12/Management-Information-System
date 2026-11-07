package com.notes.notes.benchmarks;

import java.util.Arrays;

record LatencyStats(double meanMs, double stdDevMs, double p50Ms, double p95Ms) {

    static LatencyStats of(long[] samplesNanos) {
        double[] ms = Arrays.stream(samplesNanos).mapToDouble(n -> n / 1_000_000.0).sorted().toArray();
        double mean = Arrays.stream(ms).average().orElse(0);
        double variance = Arrays.stream(ms).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        double std = Math.sqrt(variance);
        double p50 = percentile(ms, 0.50);
        double p95 = percentile(ms, 0.95);
        return new LatencyStats(mean, std, p50, p95);
    }

    private static double percentile(double[] sortedMs, double p) {
        if (sortedMs.length == 0) return 0;
        int idx = (int) Math.ceil(p * sortedMs.length) - 1;
        return sortedMs[Math.max(0, Math.min(idx, sortedMs.length - 1))];
    }
}
