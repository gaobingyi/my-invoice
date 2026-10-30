package com.example.invoice.controller;

import com.example.invoice.dto.ParsingMetricsResponse;
import com.example.invoice.dto.ParsingTrendEntry;
import com.example.invoice.service.ParsingMetricsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final ParsingMetricsService metrics;

    public MetricsController(ParsingMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/parsing")
    public ParsingMetricsResponse getParsingMetrics() {
        return metrics.getSnapshot();
    }

    @GetMapping("/parsing/trend")
    public List<ParsingTrendEntry> getParsingTrend(
            @RequestParam(defaultValue = "30") int days) {
        // 钳制到 1~365：0/负数会让日期偏移算出空区间，超大值则全表聚合，都无意义
        return metrics.getTrend(Math.min(Math.max(days, 1), 365));
    }
}
