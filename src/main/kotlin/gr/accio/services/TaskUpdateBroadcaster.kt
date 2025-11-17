package gr.accio.services

import gr.accio.models.Task
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class TaskUpdateBroadcaster {

    private val taskUpdateProcessor = BroadcastProcessor.create<Task>()
    private val subscribers = ConcurrentHashMap<String, Multi<Task>>()

    /**
     * Broadcast a task update to all subscribers
     */
    fun broadcastTaskUpdate(task: Task) {
        Log.debug("Broadcasting task update for task ${task.id} with status ${task.status}")
        taskUpdateProcessor.onNext(task)
    }

    /**
     * Subscribe to task updates stream
     */
    fun subscribeToTaskUpdates(): Multi<Task> {
        val subscriptionId = java.util.UUID.randomUUID().toString()
        val stream = taskUpdateProcessor
            .onFailure().retry().indefinitely()
            .onCancellation().invoke { 
                Log.debug("Task updates subscription cancelled")
                subscribers.remove(subscriptionId)
            }
        
        subscribers[subscriptionId] = stream
        return stream
    }

    /**
     * Subscribe to task updates with a specific filter
     */
    fun subscribeToTaskUpdates(filter: (Task) -> Boolean): Multi<Task> {
        return subscribeToTaskUpdates()
            .filter(filter)
    }

    /**
     * Get the number of active subscribers
     */
    fun getSubscriberCount(): Int {
        // BroadcastProcessor doesn't expose subscriber count directly
        // Return the size of our manual tracking map
        return subscribers.size
    }

    /**
     * Handle task update events from CDI events
     */
    fun onTaskUpdate(@Observes task: Task) {
        broadcastTaskUpdate(task)
    }
}