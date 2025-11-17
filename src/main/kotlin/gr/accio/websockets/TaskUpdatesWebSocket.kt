package gr.accio.websockets

import gr.accio.models.Task
import gr.accio.services.TaskService
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.subscription.Cancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.websocket.*
import jakarta.websocket.server.ServerEndpoint

@ServerEndpoint("/ws/tasks")
@ApplicationScoped
class TaskUpdatesWebSocket {

    @Inject
    lateinit var taskService: TaskService

    // Store active WebSocket sessions
    private val sessions = ConcurrentHashMap<String, Session>()
    private val subscriptions = ConcurrentHashMap<String, Cancellable>()

    @OnOpen
    fun onOpen(session: Session) {
        val sessionId = session.id
        sessions[sessionId] = session
        
        Log.info("WebSocket connection opened: $sessionId")
        
        // Subscribe to task updates for this session
        val subscription = taskService.streamTaskUpdates()
            .subscribe().with(
                { task -> sendTaskUpdate(sessionId, task) },
                { error -> 
                    Log.error("Error in task updates stream for session $sessionId", error)
                    closeSession(sessionId)
                }
            )
        
        subscriptions[sessionId] = subscription
        
        // Send initial connection confirmation
        sendMessage(sessionId, mapOf(
            "type" to "connection",
            "status" to "connected",
            "sessionId" to sessionId,
            "timestamp" to System.currentTimeMillis()
        ))
    }

    @OnClose
    fun onClose(session: Session) {
        val sessionId = session.id
        Log.info("WebSocket connection closed: $sessionId")
        closeSession(sessionId)
    }

    @OnError
    fun onError(session: Session, throwable: Throwable) {
        val sessionId = session.id
        Log.error("WebSocket error for session $sessionId", throwable)
        closeSession(sessionId)
    }

    @OnMessage
    fun onMessage(session: Session, message: String) {
        val sessionId = session.id
        Log.debug("Received message from session $sessionId: $message")
        
        try {
            // Parse incoming message for potential commands
            val messageData = Json.decodeFromString<Map<String, String>>(message)
            
            when (messageData["type"]) {
                "ping" -> {
                    sendMessage(sessionId, mapOf(
                        "type" to "pong",
                        "timestamp" to System.currentTimeMillis()
                    ))
                }
                "subscribe" -> {
                    // Client can request specific task types or filters
                    val filter = messageData["filter"]
                    Log.info("Session $sessionId subscribed with filter: $filter")
                    // For now, we send all updates, but this could be enhanced
                }
                else -> {
                    Log.warn("Unknown message type from session $sessionId: ${messageData["type"]}")
                }
            }
        } catch (e: Exception) {
            Log.error("Error processing message from session $sessionId", e)
            sendMessage(sessionId, mapOf(
                "type" to "error",
                "message" to "Invalid message format",
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }

    private fun sendTaskUpdate(sessionId: String, task: Task) {
        val updateData = mapOf(
            "type" to "task_update",
            "task" to mapOf(
                "id" to task.id.toString(),
                "type" to task.type.toString(),
                "status" to task.status.toString(),
                "priority" to task.priority.toString(),
                "progressPercentage" to task.progressPercentage,
                "message" to task.progressMessage,
                "createdAt" to task.createdAt?.toEpochMilli(),
                "updatedAt" to task.updatedAt?.toEpochMilli(),
                "startedAt" to task.startedAt?.toEpochMilli(),
                "completedAt" to task.completedAt?.toEpochMilli(),
                "retryCount" to task.retryCount,
                "videoId" to task.video?.id?.toString(),
                "subtitleId" to task.subtitle?.id?.toString()
            ),
            "timestamp" to System.currentTimeMillis()
        )
        
        sendMessage(sessionId, updateData)
    }

    private fun sendMessage(sessionId: String, data: Map<String, Any?>) {
        val session = sessions[sessionId]
        if (session != null && session.isOpen) {
            try {
                val jsonMessage = Json.encodeToString(data)
                session.asyncRemote.sendText(jsonMessage)
            } catch (e: Exception) {
                Log.error("Error sending message to session $sessionId", e)
                closeSession(sessionId)
            }
        }
    }

    private fun closeSession(sessionId: String) {
        // Cancel subscription
        subscriptions[sessionId]?.cancel()
        subscriptions.remove(sessionId)
        
        // Remove session
        sessions.remove(sessionId)
        
        Log.info("Cleaned up resources for session $sessionId")
    }

    /**
     * Broadcast a task update to all connected sessions
     */
    fun broadcastTaskUpdate(task: Task) {
        sessions.keys.forEach { sessionId ->
            sendTaskUpdate(sessionId, task)
        }
    }

    /**
     * Get the number of active WebSocket connections
     */
    fun getActiveConnectionsCount(): Int = sessions.size
}