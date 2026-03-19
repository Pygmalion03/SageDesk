package com.nageoffer.ai.ragent.bench;

import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.strategy.FixedSizeTextChunker;
import com.nageoffer.ai.ragent.core.chunk.strategy.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.strategy.StructureAwareTextChunker;
import com.nageoffer.ai.ragent.core.parser.MarkdownDocumentParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class IngestionBenchmark2 {
    public static void main(String[] args) throws Exception {
        Path docsRoot = Paths.get("E:/Projects/ragent/bootstrap/src/main/resources/file");
        List<Path> docs = Files.walk(docsRoot)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .sorted()
                .collect(Collectors.toList());

        MarkdownDocumentParser parser = new MarkdownDocumentParser();
        StructureAwareTextChunker structureAware = new StructureAwareTextChunker(null, List.of());
        FixedSizeTextChunker fixedSize = new FixedSizeTextChunker(null, List.of());
        ParagraphChunker paragraph = new ParagraphChunker(null, List.of());
        ChunkingOptions options = ChunkingOptions.builder().chunkSize(512).overlapSize(128).build();
        int rounds = 200;

        List<byte[]> rawDocs = new ArrayList<>();
        long totalBytes = 0L;
        for (Path doc : docs) {
            byte[] bytes = Files.readAllBytes(doc);
            rawDocs.add(bytes);
            totalBytes += bytes.length;
        }

        List<String> parsedDocs = new ArrayList<>();
        List<Long> parseLatencies = new ArrayList<>();
        long parseStart = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (byte[] raw : rawDocs) {
                long one = System.nanoTime();
                String text = parser.parse(raw, "text/markdown", null).text();
                parseLatencies.add(System.nanoTime() - one);
                if (round == 0) {
                    parsedDocs.add(text);
                }
            }
        }
        long parseElapsed = System.nanoTime() - parseStart;

        BenchmarkResult structureAwareResult = benchmarkChunker(structureAware, parsedDocs, options, rounds);
        BenchmarkResult fixedSizeResult = benchmarkChunker(fixedSize, parsedDocs, options, rounds);
        BenchmarkResult paragraphResult = benchmarkChunker(paragraph, parsedDocs, options, rounds);

        long totalChars = parsedDocs.stream().mapToLong(String::length).sum();
        long totalProcessedBytes = totalBytes * rounds;
        long totalProcessedChars = totalChars * rounds;

        System.out.println("docs=" + docs.size());
        System.out.println("sample_bytes=" + totalBytes);
        System.out.println("sample_chars=" + totalChars);
        System.out.println("rounds=" + rounds);
        System.out.println(formatParserMetrics(parseLatencies, parseElapsed, totalProcessedBytes, totalProcessedChars));
        System.out.println(formatChunkMetrics("structure_aware", structureAwareResult, totalProcessedChars));
        System.out.println(formatChunkMetrics("fixed_size", fixedSizeResult, totalProcessedChars));
        System.out.println(formatChunkMetrics("paragraph", paragraphResult, totalProcessedChars));
    }

    private static BenchmarkResult benchmarkChunker(Object chunkerObj, List<String> docs, ChunkingOptions options, int rounds) {
        com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy chunker = (com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy) chunkerObj;
        List<Long> latencies = new ArrayList<>();
        long totalChunks = 0L;
        long started = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (String doc : docs) {
                long one = System.nanoTime();
                List<VectorChunk> chunks = chunker.chunk(doc, options);
                latencies.add(System.nanoTime() - one);
                totalChunks += chunks.size();
            }
        }
        long elapsed = System.nanoTime() - started;
        return new BenchmarkResult(elapsed, latencies, totalChunks);
    }

    private static String formatParserMetrics(List<Long> latencies, long elapsedNanos, long bytes, long chars) {
        double avgMs = avgMs(latencies);
        double p95Ms = percentileMs(latencies, 0.95);
        double docsPerSec = (latencies.size() * 1_000_000_000.0) / elapsedNanos;
        double mbPerSec = (bytes / 1024.0 / 1024.0) / (elapsedNanos / 1_000_000_000.0);
        double charsPerSec = chars / (elapsedNanos / 1_000_000_000.0);
        return String.format(Locale.US,
                "parser avg_ms=%.3f p95_ms=%.3f docs_per_sec=%.1f mb_per_sec=%.2f chars_per_sec=%.0f",
                avgMs, p95Ms, docsPerSec, mbPerSec, charsPerSec);
    }

    private static String formatChunkMetrics(String name, BenchmarkResult result, long chars) {
        double avgMs = avgMs(result.latencies());
        double p95Ms = percentileMs(result.latencies(), 0.95);
        double docsPerSec = (result.latencies().size() * 1_000_000_000.0) / result.elapsedNanos();
        double charsPerSec = chars / (result.elapsedNanos() / 1_000_000_000.0);
        double avgChunksPerDoc = result.totalChunks() / (double) result.latencies().size();
        return String.format(Locale.US,
                "%s avg_ms=%.3f p95_ms=%.3f docs_per_sec=%.1f chars_per_sec=%.0f avg_chunks_per_doc=%.2f total_chunks=%d",
                name, avgMs, p95Ms, docsPerSec, charsPerSec, avgChunksPerDoc, result.totalChunks());
    }

    private static double avgMs(List<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
    }

    private static double percentileMs(List<Long> nanos, double percentile) {
        if (nanos.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = nanos.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
        int index = (int) Math.ceil(sorted.size() * percentile) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index) / 1_000_000.0;
    }

    private record BenchmarkResult(long elapsedNanos, List<Long> latencies, long totalChunks) {
    }
}