package com.loom.exchange.cluster;

/**
 * 集群事件监听器接口
 */
public interface ClusterEventListener {
    
    /**
     * 节点加入集群
     */
    default void onNodeJoined(ClusterNode node) {}
    
    /**
     * 节点离开集群
     */
    default void onNodeLeft(ClusterNode node) {}
    
    /**
     * 节点下线
     */
    default void onNodeDown(ClusterNode node) {}
    
    /**
     * 节点恢复
     */
    default void onNodeRecovered(ClusterNode node) {}
    
    /**
     * 主节点选举完成
     */
    default void onMasterElected(ClusterNode masterNode) {}
    
    /**
     * 主节点变更
     */
    default void onMasterChanged(ClusterNode oldMaster, ClusterNode newMaster) {}
    
    /**
     * 集群分裂
     */
    default void onClusterSplit() {}
    
    /**
     * 集群合并
     */
    default void onClusterMerged() {}
}
