package com.loom.exchange.risk;

import com.loom.exchange.core.Order;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 风险管理器
 * 实现实时风控检查，包括用户限额、价格偏离、频率控制等
 */
public class RiskManager {
    
    // 用户订单统计
    private final ConcurrentHashMap<String, UserRiskProfile> userProfiles;
    
    // 市场价格缓存
    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> marketPrices;
    
    // 风控配置
    private final RiskConfig riskConfig;
    
    public RiskManager(RiskConfig riskConfig) {
        this.riskConfig = riskConfig;
        this.userProfiles = new ConcurrentHashMap<>();
        this.marketPrices = new ConcurrentHashMap<>();
    }
    
    /**
     * 检查订单是否通过风控
     */
    public RiskCheckResult checkOrder(Order order) {
        // 1. 用户频率检查
        var frequencyCheck = checkOrderFrequency(order);
        if (!frequencyCheck.passed()) {
            return frequencyCheck;
        }
        
        // 2. 订单大小检查
        var sizeCheck = checkOrderSize(order);
        if (!sizeCheck.passed()) {
            return sizeCheck;
        }
        
        // 3. 价格偏离检查
        var priceCheck = checkPriceDeviation(order);
        if (!priceCheck.passed()) {
            return priceCheck;
        }
        
        // 4. 用户持仓检查
        var positionCheck = checkUserPosition(order);
        if (!positionCheck.passed()) {
            return positionCheck;
        }
        
        // 更新用户风险档案
        updateUserProfile(order);
        
        return RiskCheckResult.passed();
    }
    
    /**
     * 检查订单频率
     */
    private RiskCheckResult checkOrderFrequency(Order order) {
        var profile = getUserProfile(order.userId());
        
        // 检查每秒订单数限制
        if (profile.getOrdersPerSecond() > riskConfig.maxOrdersPerSecond()) {
            return RiskCheckResult.rejected("订单频率过高: " + profile.getOrdersPerSecond() + "/s");
        }
        
        // 检查每分钟订单数限制
        if (profile.getOrdersPerMinute() > riskConfig.maxOrdersPerMinute()) {
            return RiskCheckResult.rejected("订单频率过高: " + profile.getOrdersPerMinute() + "/min");
        }
        
        return RiskCheckResult.passed();
    }
    
    /**
     * 检查订单大小
     */
    private RiskCheckResult checkOrderSize(Order order) {
        // 检查单笔订单最大金额
        var orderValue = order.price().multiply(order.quantity());
        if (orderValue.compareTo(riskConfig.maxOrderValue()) > 0) {
            return RiskCheckResult.rejected("订单金额过大: " + orderValue);
        }
        
        // 检查单笔订单最大数量
        if (order.quantity().compareTo(riskConfig.maxOrderQuantity()) > 0) {
            return RiskCheckResult.rejected("订单数量过大: " + order.quantity());
        }
        
        return RiskCheckResult.passed();
    }
    
    /**
     * 检查价格偏离
     */
    private RiskCheckResult checkPriceDeviation(Order order) {
        var marketPriceRef = marketPrices.get(order.symbol());
        if (marketPriceRef == null) {
            // 如果没有市场价格参考，允许通过
            return RiskCheckResult.passed();
        }
        
        var marketPrice = marketPriceRef.get();
        if (marketPrice == null) {
            return RiskCheckResult.passed();
        }
        
        // 计算价格偏离百分比
        var deviation = order.price().subtract(marketPrice)
                              .abs()
                              .divide(marketPrice, 4, BigDecimal.ROUND_HALF_UP);
        
        if (deviation.compareTo(riskConfig.maxPriceDeviation()) > 0) {
            return RiskCheckResult.rejected("价格偏离过大: " + 
                deviation.multiply(new BigDecimal("100")) + "%");
        }
        
        return RiskCheckResult.passed();
    }
    
    /**
     * 检查用户持仓
     */
    private RiskCheckResult checkUserPosition(Order order) {
        var profile = getUserProfile(order.userId());
        
        // 检查用户总持仓价值
        if (profile.getTotalPositionValue().compareTo(riskConfig.maxUserPositionValue()) > 0) {
            return RiskCheckResult.rejected("用户持仓过大: " + profile.getTotalPositionValue());
        }
        
        // 检查单一交易对持仓
        var symbolPosition = profile.getSymbolPosition(order.symbol());
        var newPositionValue = symbolPosition.add(order.price().multiply(order.quantity()));
        
        if (newPositionValue.compareTo(riskConfig.maxSymbolPositionValue()) > 0) {
            return RiskCheckResult.rejected("单一交易对持仓过大: " + newPositionValue);
        }
        
        return RiskCheckResult.passed();
    }
    
    /**
     * 更新用户风险档案
     */
    private void updateUserProfile(Order order) {
        var profile = getUserProfile(order.userId());
        profile.recordOrder(order);
    }
    
    /**
     * 获取用户风险档案
     */
    private UserRiskProfile getUserProfile(String userId) {
        return userProfiles.computeIfAbsent(userId, k -> new UserRiskProfile(userId));
    }
    
    /**
     * 更新市场价格
     */
    public void updateMarketPrice(String symbol, BigDecimal price) {
        marketPrices.computeIfAbsent(symbol, k -> new AtomicReference<>())
                   .set(price);
    }
    
    /**
     * 获取风控统计
     */
    public RiskStats getStats() {
        var totalUsers = userProfiles.size();
        var totalOrdersBlocked = userProfiles.values().stream()
                                           .mapToLong(UserRiskProfile::getBlockedOrderCount)
                                           .sum();
        
        return new RiskStats(totalUsers, totalOrdersBlocked);
    }
    
    /**
     * 用户风险档案
     */
    private static class UserRiskProfile {
        private final String userId;
        private final AtomicInteger ordersPerSecond = new AtomicInteger();
        private final AtomicInteger ordersPerMinute = new AtomicInteger();
        private final AtomicReference<BigDecimal> totalPositionValue = 
            new AtomicReference<>(BigDecimal.ZERO);
        private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> symbolPositions = 
            new ConcurrentHashMap<>();
        private final AtomicInteger blockedOrderCount = new AtomicInteger();
        
        private volatile long lastSecond = System.currentTimeMillis() / 1000;
        private volatile long lastMinute = System.currentTimeMillis() / 60000;
        
        public UserRiskProfile(String userId) {
            this.userId = userId;
        }
        
        public void recordOrder(Order order) {
            updateOrderFrequency();
            updatePosition(order);
        }
        
        private void updateOrderFrequency() {
            long currentSecond = System.currentTimeMillis() / 1000;
            long currentMinute = System.currentTimeMillis() / 60000;
            
            // 重置秒级计数器
            if (currentSecond != lastSecond) {
                ordersPerSecond.set(1);
                lastSecond = currentSecond;
            } else {
                ordersPerSecond.incrementAndGet();
            }
            
            // 重置分钟级计数器
            if (currentMinute != lastMinute) {
                ordersPerMinute.set(1);
                lastMinute = currentMinute;
            } else {
                ordersPerMinute.incrementAndGet();
            }
        }
        
        private void updatePosition(Order order) {
            var orderValue = order.price().multiply(order.quantity());
            
            // 更新总持仓
            totalPositionValue.updateAndGet(current -> current.add(orderValue));
            
            // 更新交易对持仓
            symbolPositions.computeIfAbsent(order.symbol(), 
                k -> new AtomicReference<>(BigDecimal.ZERO))
                .updateAndGet(current -> current.add(orderValue));
        }
        
        public int getOrdersPerSecond() {
            return ordersPerSecond.get();
        }
        
        public int getOrdersPerMinute() {
            return ordersPerMinute.get();
        }
        
        public BigDecimal getTotalPositionValue() {
            return totalPositionValue.get();
        }
        
        public BigDecimal getSymbolPosition(String symbol) {
            var positionRef = symbolPositions.get(symbol);
            return positionRef != null ? positionRef.get() : BigDecimal.ZERO;
        }
        
        public long getBlockedOrderCount() {
            return blockedOrderCount.get();
        }
        
        public void incrementBlockedOrders() {
            blockedOrderCount.incrementAndGet();
        }
    }
}
