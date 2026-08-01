// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.planner

import com.returngift.agent.agent.memory.ContextualMemory
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Graph-based execution state following LangGraph's state machine pattern.
 * 
 * Design reference: LangGraph's pregel/checkpoint system for:
 * - State persistence across nodes
 * - Checkpoint after successful steps
 * - Resume from checkpoints on failure
 * 
 * Combined with OpenHands' observe → reason → act → verify pattern.
 */
object GraphState {

    private const val TAG = "GraphState"
    
    /**
     * Represents the current state of the execution graph.
     */
    data class State(
        val taskId: String,
        val taskDescription: String,
        val currentNode: String? = null,
        val completedNodes: List<String> = emptyList(),
        val failedNodes: List<String> = emptyList(),
        val pendingNodes: List<String> = emptyList(),
        val nodeResults: Map<String, NodeResult> = emptyMap(),
        val checkpoint: Checkpoint? = null,
        val contextFacts: Map<String, String> = emptyMap(),
        val observationHistory: List<Observation> = emptyList(),
        val actionHistory: List<Action> = emptyList(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Checkpoint for recovery - stores enough state to resume from failure.
     */
    data class Checkpoint(
        val timestamp: Long,
        val completedNodes: List<String>,
        val nodeResults: Map<String, NodeResult>,
        val pendingNodes: List<String>,
        val currentNode: String?,
        val actionHistory: List<Action>,
        val contextFacts: Map<String, String>
    )
    
    /**
     * Result of executing a single node.
     */
    data class NodeResult(
        val nodeId: String,
        val status: NodeStatus,
        val observation: Observation?,
        val action: Action?,
        val errorMessage: String? = null,
        val retryCount: Int = 0,
        val confidence: Float = 0f
    )
    
    enum class NodeStatus {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, VERIFIED
    }
    
    /**
     * Observation from UI state (OpenHands-style).
     */
    data class Observation(
        val nodeId: String,
        val timestamp: Long,
        val screenHash: String,
        val elements: List<UIElement>,
        val landmarks: List<String>,
        val confidence: Float,
        val textContent: String = ""
    )
    
    /**
     * Action to be performed (OpenHands-style).
     */
    data class Action(
        val nodeId: String,
        val timestamp: Long,
        val toolName: String,
        val parameters: Map<String, Any>,
        val expectedOutcome: String,
        val executed: Boolean = false,
        val verified: Boolean = false
    )
    
    /**
     * UI element detected in observation.
     */
    data class UIElement(
        val id: String,
        val type: String,
        val text: String,
        val bounds: Rect,
        val clickable: Boolean,
        val confidence: Float
    )
    
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    
    /**
     * Execution graph with nodes and edges.
     */
    data class ExecutionGraph(
        val taskId: String,
        val nodes: Map<String, GraphNode>,
        val edges: List<Edge>,
        val rootNodes: List<String>,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    data class GraphNode(
        val id: String,
        val description: String,
        val skillId: String?,
        val parameters: Map<String, String>,
        val dependencies: List<String>,
        val preconditions: List<String> = emptyList(),
        val verificationCriteria: String = "",
        val maxRetries: Int = 2,
        val isSensitive: Boolean = false,
        val riskLevel: RiskLevel = RiskLevel.LOW
    )
    
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    
    data class Edge(
        val from: String,
        val to: String,
        val condition: EdgeCondition = EdgeCondition.ALWAYS
    )
    
    enum class EdgeCondition { ALWAYS, ON_SUCCESS, ON_FAILURE }
    
    /**
     * State store for active executions.
     */
    private val activeStates = ConcurrentHashMap<String, State>()
    private val checkpoints = ConcurrentHashMap<String, Checkpoint>()
    
    /**
     * Create a new execution state.
     */
    fun createState(taskId: String, taskDescription: String): State {
        val state = State(
            taskId = taskId,
            taskDescription = taskDescription
        )
        activeStates[taskId] = state
        XLog.d(TAG, "Created state for task: $taskId")
        return state
    }
    
    /**
     * Get current state.
     */
    fun getState(taskId: String): State? = activeStates[taskId]
    
    /**
     * Update state with node result.
     */
    fun updateState(taskId: String, nodeResult: NodeResult): State? {
        val state = activeStates[taskId] ?: return null
        
        val updatedResults = state.nodeResults.toMutableMap()
        updatedResults[nodeResult.nodeId] = nodeResult
        
        val completedNodes = state.completedNodes.toMutableList()
        val failedNodes = state.failedNodes.toMutableList()
        val pendingNodes = state.pendingNodes.toMutableList()
        
        when (nodeResult.status) {
            NodeStatus.COMPLETED, NodeStatus.VERIFIED -> {
                if (nodeResult.nodeId !in completedNodes) {
                    completedNodes.add(nodeResult.nodeId)
                }
                pendingNodes.remove(nodeResult.nodeId)
            }
            NodeStatus.FAILED -> {
                if (nodeResult.nodeId !in failedNodes) {
                    failedNodes.add(nodeResult.nodeId)
                }
                pendingNodes.remove(nodeResult.nodeId)
            }
            NodeStatus.RUNNING -> {
                // Update current node
            }
            else -> {}
        }
        
        val updatedState = state.copy(
            currentNode = if (nodeResult.status == NodeStatus.RUNNING) nodeResult.nodeId else state.currentNode,
            completedNodes = completedNodes,
            failedNodes = failedNodes,
            pendingNodes = pendingNodes,
            nodeResults = updatedResults
        )
        
        activeStates[taskId] = updatedState
        return updatedState
    }
    
    /**
     * Add observation to state history.
     */
    fun addObservation(taskId: String, observation: Observation): State? {
        val state = activeStates[taskId] ?: return null
        val updatedState = state.copy(
            observationHistory = state.observationHistory + observation
        )
        activeStates[taskId] = updatedState
        return updatedState
    }
    
    /**
     * Add action to state history.
     */
    fun addAction(taskId: String, action: Action): State? {
        val state = activeStates[taskId] ?: return null
        val updatedAction = action.copy(executed = true)
        val updatedState = state.copy(
            actionHistory = state.actionHistory + updatedAction
        )
        activeStates[taskId] = updatedState
        return updatedState
    }
    
    /**
     * Verify action was successful.
     */
    fun verifyAction(taskId: String, actionIndex: Int, verified: Boolean): State? {
        val state = activeStates[taskId] ?: return null
        val actions = state.actionHistory.toMutableList()
        if (actionIndex < actions.size) {
            actions[actionIndex] = actions[actionIndex].copy(verified = verified)
        }
        val updatedState = state.copy(actionHistory = actions)
        activeStates[taskId] = updatedState
        return updatedState
    }
    
    /**
     * Create checkpoint for recovery.
     */
    fun createCheckpoint(taskId: String): Checkpoint? {
        val state = activeStates[taskId] ?: return null
        
        val checkpoint = Checkpoint(
            timestamp = System.currentTimeMillis(),
            completedNodes = state.completedNodes,
            nodeResults = state.nodeResults,
            pendingNodes = state.pendingNodes,
            currentNode = state.currentNode,
            actionHistory = state.actionHistory,
            contextFacts = state.contextFacts
        )
        
        checkpoints[taskId] = checkpoint
        
        // Update state with checkpoint
        activeStates[taskId] = state.copy(checkpoint = checkpoint)
        
        XLog.d(TAG, "Created checkpoint for task: $taskId")
        return checkpoint
    }
    
    /**
     * Restore from checkpoint.
     */
    fun restoreFromCheckpoint(taskId: String): State? {
        val checkpoint = checkpoints[taskId] ?: return null
        val state = activeStates[taskId] ?: return null
        
        val restoredState = state.copy(
            completedNodes = checkpoint.completedNodes,
            nodeResults = checkpoint.nodeResults,
            pendingNodes = checkpoint.pendingNodes,
            currentNode = checkpoint.currentNode,
            actionHistory = checkpoint.actionHistory,
            contextFacts = checkpoint.contextFacts,
            checkpoint = checkpoint
        )
        
        activeStates[taskId] = restoredState
        XLog.d(TAG, "Restored state from checkpoint for task: $taskId")
        return restoredState
    }
    
    /**
     * Get latest checkpoint.
     */
    fun getCheckpoint(taskId: String): Checkpoint? = checkpoints[taskId]
    
    /**
     * Remove state after completion.
     */
    fun removeState(taskId: String) {
        activeStates.remove(taskId)
        checkpoints.remove(taskId)
        XLog.d(TAG, "Removed state for task: $taskId")
    }
    
    /**
     * Check if all dependencies are satisfied.
     */
    fun dependenciesSatisfied(nodeId: String, graph: ExecutionGraph): Boolean {
        val node = graph.nodes[nodeId] ?: return false
        return node.dependencies.all { dep ->
            val result = activeStates.values
                .firstOrNull { it.nodeResults.containsKey(dep) }
                ?.nodeResults?.get(dep)
            result?.status == NodeStatus.COMPLETED || result?.status == NodeStatus.VERIFIED
        }
    }
    
    /**
     * Get nodes ready for execution.
     */
    fun getReadyNodes(graph: ExecutionGraph, state: State): List<GraphNode> {
        return graph.nodes.values.filter { node ->
            node.id !in state.completedNodes &&
            node.id !in state.failedNodes &&
            node.id !in state.pendingNodes &&
            dependenciesSatisfied(node.id, graph)
        }
    }
    
    /**
     * Update context facts.
     */
    fun updateContext(taskId: String, facts: Map<String, String>): State? {
        val state = activeStates[taskId] ?: return null
        val updatedState = state.copy(
            contextFacts = state.contextFacts + facts
        )
        activeStates[taskId] = updatedState
        return updatedState
    }
}
