package com.loom.exchange.risk;

import java.math.BigDecimal;

/**
 * 风控配置参数
 */
public record RiskConfig(
    // 频率限制
    int maxOrdersPerSecond,
    int maxOrdersPerMinute,
    
    // 订单大小限制
    BigDecimal maxOrderValue,
    BigDecimal maxOrderQuantity,
    
    // 价格偏离限制
    BigDecimal maxPriceDeviation,
    
    // 持仓限制
    BigDecimal maxUserPositionValue,
    BigDecimal maxSymbolPositionValue
) {
    
    /**
     * 默认风控配置
     */
    public static RiskConfig defaultConfig() {
        return new RiskConfig(
            100,                                    // 每秒最多100个订单
            1000,                                   // 每分钟最多1000个订单
            new BigDecimal("1000000"),              // 单笔订单最大100万USDT
            new BigDecimal("10000"),                // 单笔订单最大10000个
            new BigDecimal("0.1"),                  // 价格偏离最大10%
            new BigDecimal("10000000"),             // 用户总持仓最大1000万USDT
            new BigDecimal("5000000")               // 单一交易对持仓最大500万USDT
        );
    }
    
    /**
     * 严格风控配置
     */
    public static RiskConfig strictConfig() {
        return new RiskConfig(
            50,                                     // 每秒最多50个订单
            500,                                    // 每分钟最多500个订单
            new BigDecimal("100000"),               // 单笔订单最大10万USDT
            new BigDecimal("1000"),                 // 单笔订单最大1000个
            new BigDecimal("0.05"),                 // 价格偏离最大5%
            new BigDecimal("1000000"),              // 用户总持仓最大100万USDT
            new BigDecimal("500000")                // 单一交易对持仓最大50万USDT
        );
    }
    
    /**
     * 宽松风控配置 (用于测试)
     */
    public static RiskConfig lenientConfig() {
        return new RiskConfig(
            1000,                                   // 每秒最多1000个订单
            10000,                                  // 每分钟最多10000个订单
            new BigDecimal("10000000"),             // 单笔订单最大1000万USDT
            new BigDecimal("100000"),               // 单笔订单最大10万个
            new BigDecimal("0.5"),                  // 价格偏离最大50%
            new BigDecimal("100000000"),            // 用户总持仓最大1亿USDT
            new BigDecimal("50000000")              // 单一交易对持仓最大5000万USDT
        );
    }
}
