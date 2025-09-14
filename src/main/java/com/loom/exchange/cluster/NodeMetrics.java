package com.loom.exchange.cluster;

import java.time.Instant;

/**
 * 节点性能指标
 */
public record NodeMetrics(
    double cpuUsage,           // CPU使用率 (0.0-1.0)
    double memoryUsage,        // 内存使用率 (0.0-1.0)
    long networkLatency,       // 网络延迟 (毫秒)
    long diskUsage,            // 磁盘使用量 (字节)
    double diskIoWait,         // 磁盘IO等待时间
    long orderTps,             // 订单处理TPS
    long tradeTps,             // 交易处理TPS
    double averageLatency,     // 平均延迟 (微秒)
    long activeConnections,    // 活跃连接数
    Instant timestamp          // 指标时间戳
) {
    
    /**
     * 创建空指标
     */
    public static NodeMetrics empty() {
        return new NodeMetrics(
            0.0, 0.0, 0L, 0L, 0.0, 
            0L, 0L, 0.0, 0L, 
            Instant.now()
        );
    }
    
    /**
     * 创建当前指标
     */
    public static NodeMetrics current(
        double cpuUsage, double memoryUsage, long networkLatency,
        long orderTps, long tradeTps, double averageLatency, long activeConnections) {
        
        var runtime = Runtime.getRuntime();
        var totalMemory = runtime.totalMemory();
        var freeMemory = runtime.freeMemory();
        var usedMemory = totalMemory - freeMemory;
        
        return new NodeMetrics(
            cpuUsage,
            (double) usedMemory / runtime.maxMemory(),
            networkLatency,
            0L, // 磁盘使用量需要系统调用获取
            0.0, // 磁盘IO等待需要系统监控
            orderTps,
            tradeTps,
            averageLatency,
            activeConnections,
            Instant.now()
        );
    }
    
    /**
     * 检查指标是否过期
     */
    public boolean isStale(long maxAgeMillis) {
        return Instant.now().toEpochMilli() - timestamp.toEpochMilli() > maxAgeMillis;
    }
    
    /**
     * 计算性能得分 (0-100)
     */
    public int getPerformanceScore() {
        var score = 100;
        
        // CPU负载影响
        if (cpuUsage > 0.9) score -= 30;
        else if (cpuUsage > 0.7) score -= 15;
        else if (cpuUsage > 0.5) score -= 5;
        
        // 内存使用影响
        if (memoryUsage > 0.9) score -= 25;
        else if (memoryUsage > 0.8) score -= 15;
        else if (memoryUsage > 0.6) score -= 5;
        
        // 网络延迟影响
        if (networkLatency > 100) score -= 20;
        else if (networkLatency > 50) score -= 10;
        else if (networkLatency > 20) score -= 5;
        
        // 处理延迟影响
        if (averageLatency > 1000) score -= 25; // > 1ms
        else if (averageLatency > 500) score -= 15; // > 500μs
        else if (averageLatency > 100) score -= 5;  // > 100μs
        
        return Math.max(0, score);
    }
    
    /**
     * 检查是否过载
     */
    public boolean isOverloaded() {
        return cpuUsage > 0.9 || 
               memoryUsage > 0.9 || 
               averageLatency > 1000 || 
               networkLatency > 200;
    }
    
    /**
     * 检查是否健康
     */
    public boolean isHealthy() {
        return !isOverloaded() && 
               cpuUsage < 0.8 && 
               memoryUsage < 0.8 && 
               averageLatency < 500;
    }
    
    @Override
    public String toString() {
        return String.format(
            "Metrics{cpu=%.1f%%, mem=%.1f%%, latency=%dms, " +
            "orderTps=%d, tradeTps=%d, avgLatency=%.1fμs, connections=%d}",
            cpuUsage * 100, memoryUsage * 100, networkLatency,
            orderTps, tradeTps, averageLatency, activeConnections
        );
    }
}
