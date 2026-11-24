package com.notes.notes.benchmarks;

import java.util.Arrays;
import java.util.List;

record PaperStats(int n, double meanMs, double stdDevMs, double minMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {

    static PaperStats of(List<Double> msSamples) {
        double[] ms = msSamples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (ms.length == 0) return new PaperStats(0, 0, 0, 0, 0, 0, 0, 0);
        double mean = Arrays.stream(ms).average().orElse(0);
        double variance = Arrays.stream(ms).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        return new PaperStats(ms.length, mean, Math.sqrt(variance), ms[0],
                percentile(ms, 0.50), percentile(ms, 0.95), percentile(ms, 0.99), ms[ms.length - 1]);
    }

    private static double percentile(double[] sortedMs, double p) {
        int idx = (int) Math.ceil(p * sortedMs.length) - 1;
        return sortedMs[Math.max(0, Math.min(idx, sortedMs.length - 1))];
    }
}
