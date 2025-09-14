package com.loom.exchange.cluster;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 集群节点信息
 */
public record ClusterNode(
    String nodeId,
    String hostname,
    int port,
    NodeRole role,
    NodeStatus status,
    Instant lastHeartbeat,
    Set<String> capabilities,
    NodeMetrics metrics
) {
    
    public enum NodeRole {
        MASTER,     // 主节点
        SLAVE,      // 从节点
        CANDIDATE   // 候选节点
    }
    
    public enum NodeStatus {
        HEALTHY,    // 健康
        DEGRADED,   // 降级
        UNHEALTHY,  // 不健康
        OFFLINE     // 离线
    }
    
    /**
     * 创建新节点
     */
    public static ClusterNode create(String nodeId, String hostname, int port) {
        return new ClusterNode(
            nodeId,
            hostname,
            port,
            NodeRole.SLAVE,  // 默认为从节点
            NodeStatus.HEALTHY,
            Instant.now(),
            Set.of("matching", "risk-control", "persistence"),
            NodeMetrics.empty()
        );
    }
    
    /**
     * 是否为主节点
     */
    public boolean isMaster() {
        return role == NodeRole.MASTER;
    }
    
    /**
     * 是否健康
     */
    public boolean isHealthy() {
        return status == NodeStatus.HEALTHY;
    }
    
    /**
     * 是否在线
     */
    public boolean isOnline() {
        return status != NodeStatus.OFFLINE;
    }
    
    /**
     * 更新心跳时间
     */
    public ClusterNode withHeartbeat() {
        return new ClusterNode(
            nodeId, hostname, port, role, status,
            Instant.now(), capabilities, metrics
        );
    }
    
    /**
     * 更新角色
     */
    public ClusterNode withRole(NodeRole newRole) {
        return new ClusterNode(
            nodeId, hostname, port, newRole, status,
            lastHeartbeat, capabilities, metrics
        );
    }
    
    /**
     * 更新状态
     */
    public ClusterNode withStatus(NodeStatus newStatus) {
        return new ClusterNode(
            nodeId, hostname, port, role, newStatus,
            lastHeartbeat, capabilities, metrics
        );
    }
    
    /**
     * 更新指标
     */
    public ClusterNode withMetrics(NodeMetrics newMetrics) {
        return new ClusterNode(
            nodeId, hostname, port, role, status,
            lastHeartbeat, capabilities, newMetrics
        );
    }
    
    /**
     * 获取节点地址
     */
    public String getAddress() {
        return hostname + ":" + port;
    }
    
    /**
     * 检查是否支持某项能力
     */
    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }
    
    /**
     * 计算节点健康得分（0-100）
     */
    public int getHealthScore() {
        if (status == NodeStatus.OFFLINE) return 0;
        if (status == NodeStatus.UNHEALTHY) return 20;
        if (status == NodeStatus.DEGRADED) return 60;
        
        // 基于指标计算健康得分
        var baseScore = 100;
        
        // CPU使用率影响
        if (metrics.cpuUsage() > 0.8) baseScore -= 20;
        else if (metrics.cpuUsage() > 0.6) baseScore -= 10;
        
        // 内存使用率影响
        if (metrics.memoryUsage() > 0.9) baseScore -= 30;
        else if (metrics.memoryUsage() > 0.7) baseScore -= 15;
        
        // 网络延迟影响
        if (metrics.networkLatency() > 100) baseScore -= 20;
        else if (metrics.networkLatency() > 50) baseScore -= 10;
        
        return Math.max(0, baseScore);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterNode that = (ClusterNode) o;
        return Objects.equals(nodeId, that.nodeId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }
    
    @Override
    public String toString() {
        return String.format("Node{id=%s, role=%s, status=%s, address=%s, score=%d}",
            nodeId, role, status, getAddress(), getHealthScore());
    }
}
