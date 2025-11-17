import { useState, useEffect } from 'react';
import {
  StopIcon,
  ArrowPathIcon,
  FunnelIcon,
  MagnifyingGlassIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline';
import { taskApi } from '../services/api';
import type { Task, TaskType, TaskStatus } from '../services/api';

export default function Tasks() {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [filteredTasks, setFilteredTasks] = useState<Task[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<TaskStatus | 'all'>('all');
  const [typeFilter, setTypeFilter] = useState<TaskType | 'all'>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchTasks = async () => {
    try {
      setLoading(true);
      setError(null);
      
      const response = await taskApi.getTasks(0, 100); // Get first 100 tasks
      
      if (response.error) {
        throw new Error(response.error);
      }

      setTasks(response.data || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch tasks');
      console.error('Tasks fetch error:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTasks();
  }, []);

  useEffect(() => {
    let filtered = tasks;

    // Filter by search term
    if (searchTerm) {
      filtered = filtered.filter(task =>
        task.type.toLowerCase().includes(searchTerm.toLowerCase()) ||
        task.progressMessage?.toLowerCase().includes(searchTerm.toLowerCase()) ||
        task.errorMessage?.toLowerCase().includes(searchTerm.toLowerCase())
      );
    }

    // Filter by status
    if (statusFilter !== 'all') {
      filtered = filtered.filter(task => task.status === statusFilter);
    }

    // Filter by type
    if (typeFilter !== 'all') {
      filtered = filtered.filter(task => task.type === typeFilter);
    }

    setFilteredTasks(filtered);
  }, [tasks, searchTerm, statusFilter, typeFilter]);

  const handleCancelTask = async (taskId: string) => {
    try {
      const response = await taskApi.cancelTask(taskId);
      if (response.error) {
        throw new Error(response.error);
      }
      // Refresh tasks after cancellation
      fetchTasks();
    } catch (error) {
      console.error('Failed to cancel task:', error);
    }
  };

  const handleRetryTask = async (taskId: string) => {
    try {
      const response = await taskApi.retryTask(taskId);
      if (response.error) {
        throw new Error(response.error);
      }
      // Refresh tasks after retry
      fetchTasks();
    } catch (error) {
      console.error('Failed to retry task:', error);
    }
  };

  if (loading) {
    return (
      <div className="space-y-6">
        {/* Loading skeleton for header */}
        <div className="flex justify-between items-center">
          <div className="space-y-2">
            <div className="h-8 bg-gray-200 rounded w-48 animate-pulse" />
            <div className="h-4 bg-gray-200 rounded w-64 animate-pulse" />
          </div>
          <div className="w-24 h-10 bg-gray-200 rounded animate-pulse" />
        </div>

        {/* Loading skeleton for filters */}
        <div className="card">
          <div className="flex flex-wrap gap-4">
            <div className="flex-1 min-w-64 h-10 bg-gray-200 rounded animate-pulse" />
            <div className="w-32 h-10 bg-gray-200 rounded animate-pulse" />
            <div className="w-32 h-10 bg-gray-200 rounded animate-pulse" />
          </div>
        </div>

        {/* Loading skeleton for tasks */}
        <div className="space-y-4">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="card animate-pulse">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-4 flex-1">
                  <div className="w-8 h-8 bg-gray-200 rounded" />
                  <div className="flex-1 space-y-2">
                    <div className="h-5 bg-gray-200 rounded w-48" />
                    <div className="h-4 bg-gray-200 rounded w-64" />
                    <div className="h-3 bg-gray-200 rounded w-32" />
                  </div>
                </div>
                <div className="w-32 h-4 bg-gray-200 rounded" />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-6">
        <div className="card">
          <div className="text-center py-8">
            <XCircleIcon className="w-12 h-12 text-red-500 mx-auto mb-4" />
            <h3 className="text-lg font-medium text-gray-900 mb-2">Error Loading Tasks</h3>
            <p className="text-gray-500 mb-4">{error}</p>
            <button
              onClick={fetchTasks}
              className="btn-primary"
            >
              Retry
            </button>
          </div>
        </div>
      </div>
    );
  }

  const getStatusColor = (status: TaskStatus) => {
    switch (status) {
      case 'PENDING':
        return 'bg-yellow-100 text-yellow-800';
      case 'RUNNING':
        return 'bg-blue-100 text-blue-800';
      case 'COMPLETED':
        return 'bg-green-100 text-green-800';
      case 'FAILED':
        return 'bg-red-100 text-red-800';
      case 'CANCELLED':
        return 'bg-gray-100 text-gray-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  const getTypeIcon = (type: TaskType) => {
    switch (type) {
      case 'SUBTITLE_EXTRACTION':
      case 'SUBTITLE_GENERATION':
        return '📝';
      case 'SUBTITLE_TRANSLATION':
      case 'TRANSLATION':
        return '🌐';
      case 'LIBRARY_SCAN':
        return '📁';
      case 'FILE_CLEANUP':
        return '🧹';
      case 'DATABASE_BACKUP':
        return '💾';
      case 'SUBTITLE_SYNC':
        return '🔄';
      default:
        return '⚙️';
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Task Monitor</h1>
          <p className="text-gray-600">Monitor and manage background tasks</p>
        </div>
        <button
          onClick={fetchTasks}
          className="btn-secondary flex items-center space-x-2"
        >
          <ArrowPathIcon className="w-4 h-4" />
          <span>Refresh</span>
        </button>
      </div>

      {/* Filters */}
      <div className="card">
        <div className="flex flex-wrap gap-4">
          {/* Search */}
          <div className="flex-1 min-w-64">
            <div className="relative">
              <MagnifyingGlassIcon className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                placeholder="Search tasks..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-10 pr-4 py-2 w-full border border-gray-300 rounded-md focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
              />
            </div>
          </div>

          {/* Status Filter */}
          <div className="flex items-center space-x-2">
            <FunnelIcon className="w-4 h-4 text-gray-400" />
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as TaskStatus | 'all')}
              className="border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
            >
              <option value="all">All Status</option>
              <option value="PENDING">Pending</option>
              <option value="RUNNING">Running</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
          </div>

          {/* Type Filter */}
          <div>
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value as TaskType | 'all')}
              className="border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
            >
              <option value="all">All Types</option>
              <option value="SUBTITLE_EXTRACTION">Subtitle Extraction</option>
              <option value="SUBTITLE_GENERATION">Subtitle Generation</option>
              <option value="SUBTITLE_TRANSLATION">Subtitle Translation</option>
              <option value="TRANSLATION">Translation</option>
              <option value="LIBRARY_SCAN">Library Scan</option>
              <option value="FILE_CLEANUP">File Cleanup</option>
              <option value="DATABASE_BACKUP">Database Backup</option>
              <option value="SUBTITLE_SYNC">Subtitle Sync</option>
            </select>
          </div>
        </div>
      </div>

      {/* Tasks List */}
      <div className="space-y-4">
        {filteredTasks.length === 0 ? (
          <div className="card text-center py-12">
            <p className="text-gray-500">No tasks found matching your criteria.</p>
          </div>
        ) : (
          filteredTasks.map((task) => (
            <div key={task.id} className="card">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-4 flex-1">
                  <div className="text-2xl">{getTypeIcon(task.type)}</div>
                  
                  <div className="flex-1">
                    <div className="flex items-center space-x-3">
                      <h3 className="font-medium text-gray-900">
                        {task.type.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase())}
                      </h3>
                      <span className={`px-2 py-1 text-xs font-medium rounded-full ${getStatusColor(task.status)}`}>
                        {task.status}
                      </span>
                    </div>
                    
                    {task.progressMessage && (
                      <p className="text-sm text-gray-600 mt-1">{task.progressMessage}</p>
                    )}
                    
                    {task.errorMessage && (
                      <p className="text-sm text-red-600 mt-1">{task.errorMessage}</p>
                    )}
                    
                    <div className="flex items-center space-x-4 mt-2 text-xs text-gray-500">
                      <span>Created: {new Date(task.createdAt).toLocaleString()}</span>
                      {task.updatedAt && (
                        <span>Updated: {new Date(task.updatedAt).toLocaleString()}</span>
                      )}
                    </div>
                  </div>
                </div>

                <div className="flex items-center space-x-4">
                  {/* Progress Bar */}
                  {task.status === 'RUNNING' && (
                    <div className="w-32">
                      <div className="flex justify-between text-xs text-gray-600 mb-1">
                        <span>Progress</span>
                        <span>{task.progress}%</span>
                      </div>
                      <div className="w-full bg-gray-200 rounded-full h-2">
                        <div
                          className="bg-primary-600 h-2 rounded-full transition-all duration-300"
                          style={{ width: `${task.progress}%` }}
                        ></div>
                      </div>
                    </div>
                  )}

                  {/* Actions */}
                  <div className="flex space-x-2">
                    {task.status === 'RUNNING' && (
                      <button
                        onClick={() => handleCancelTask(task.id)}
                        className="p-2 text-red-600 hover:bg-red-50 rounded-md transition-colors duration-200"
                        title="Cancel Task"
                      >
                        <StopIcon className="w-4 h-4" />
                      </button>
                    )}
                    
                    {task.status === 'FAILED' && (
                      <button
                        onClick={() => handleRetryTask(task.id)}
                        className="p-2 text-blue-600 hover:bg-blue-50 rounded-md transition-colors duration-200"
                        title="Retry Task"
                      >
                        <ArrowPathIcon className="w-4 h-4" />
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}