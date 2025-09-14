package com.loom.exchange.engine;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 撮合引擎性能统计
 * 用于监控延迟和吞吐量
 */
public class MatchingStats {
    
    private final LongAdder totalOrders = new LongAdder();
    private final LongAdder totalTrades = new LongAdder();
    private final AtomicLong totalLatencyNanos = new AtomicLong();
    private final AtomicLong minLatencyNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatencyNanos = new AtomicLong();
    
    /**
     * 记录延迟
     */
    public void recordLatency(long latencyNanos) {
        totalOrders.increment();
        totalLatencyNanos.addAndGet(latencyNanos);
        
        // 更新最小延迟
        minLatencyNanos.updateAndGet(current -> Math.min(current, latencyNanos));
        
        // 更新最大延迟
        maxLatencyNanos.updateAndGet(current -> Math.max(current, latencyNanos));
    }
    
    /**
     * 记录交易
     */
    public void recordTrade() {
        totalTrades.increment();
    }
    
    /**
     * 获取平均延迟（微秒）
     */
    public double getAverageLatencyMicros() {
        long orders = totalOrders.sum();
        if (orders == 0) return 0.0;
        
        return totalLatencyNanos.get() / (double) orders / 1000.0;
    }
    
    /**
     * 获取最小延迟（微秒）
     */
    public double getMinLatencyMicros() {
        long min = minLatencyNanos.get();
        return min == Long.MAX_VALUE ? 0.0 : min / 1000.0;
    }
    
    /**
     * 获取最大延迟（微秒）
     */
    public double getMaxLatencyMicros() {
        return maxLatencyNanos.get() / 1000.0;
    }
    
    /**
     * 获取总订单数
     */
    public long getTotalOrders() {
        return totalOrders.sum();
    }
    
    /**
     * 获取总交易数
     */
    public long getTotalTrades() {
        return totalTrades.sum();
    }
    
    /**
     * 重置统计数据
     */
    public void reset() {
        totalOrders.reset();
        totalTrades.reset();
        totalLatencyNanos.set(0);
        minLatencyNanos.set(Long.MAX_VALUE);
        maxLatencyNanos.set(0);
    }
    
    @Override
    public String toString() {
        return String.format(
            "MatchingStats{orders=%d, trades=%d, avgLatency=%.2fμs, minLatency=%.2fμs, maxLatency=%.2fμs}",
            getTotalOrders(), getTotalTrades(), 
            getAverageLatencyMicros(), getMinLatencyMicros(), getMaxLatencyMicros()
        );
    }
}
