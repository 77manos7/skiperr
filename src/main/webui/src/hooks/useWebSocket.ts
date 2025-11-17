import { useEffect, useCallback } from 'react';

// Type definitions
const TaskStatus = {
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED'
} as const;

type TaskStatus = typeof TaskStatus[keyof typeof TaskStatus];

interface TaskUpdate {
  taskId: string;
  status: TaskStatus;
  progress: number;
  progressMessage?: string;
  errorMessage?: string;
  message?: string;
  updatedAt: string;
}
import webSocketService from '../services/websocket';

export function useWebSocket(onTaskUpdate?: (update: TaskUpdate) => void) {
  const handleTaskUpdate = useCallback((update: TaskUpdate) => {
    if (onTaskUpdate) {
      onTaskUpdate(update);
    }
  }, [onTaskUpdate]);

  useEffect(() => {
    if (!onTaskUpdate) return;

    const unsubscribe = webSocketService.subscribe(handleTaskUpdate);

    return () => {
      unsubscribe();
    };
  }, [handleTaskUpdate]);

  return {
    isConnected: webSocketService.isConnected(),
    connect: () => webSocketService.connect(),
    disconnect: () => webSocketService.disconnect(),
  };
}