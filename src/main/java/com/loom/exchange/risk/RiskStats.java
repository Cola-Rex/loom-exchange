package com.loom.exchange.risk;

/**
 * 风控统计信息
 */
public record RiskStats(
    int totalUsers,
    long totalBlockedOrders
) {
    
    /**
     * 计算拒绝率
     */
    public double getBlockedOrderRate(long totalOrders) {
        if (totalOrders == 0) return 0.0;
        return (double) totalBlockedOrders / totalOrders;
    }
    
    @Override
    public String toString() {
        return String.format("RiskStats{users=%d, blockedOrders=%d}", 
                           totalUsers, totalBlockedOrders);
    }
}
