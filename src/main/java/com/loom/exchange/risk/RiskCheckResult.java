package com.loom.exchange.risk;

/**
 * 风控检查结果
 */
public record RiskCheckResult(
    boolean passed,
    String reason
) {
    
    /**
     * 创建通过的检查结果
     */
    public static RiskCheckResult passed() {
        return new RiskCheckResult(true, null);
    }
    
    /**
     * 创建拒绝的检查结果
     */
    public static RiskCheckResult rejected(String reason) {
        return new RiskCheckResult(false, reason);
    }
    
    /**
     * 是否通过检查
     */
    public boolean passed() {
        return passed;
    }
    
    /**
     * 是否被拒绝
     */
    public boolean rejected() {
        return !passed;
    }
}
