// API Service for Skiperr Backend
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

// Types
export interface ApiResponse<T> {
  data?: T;
  error?: string;
  status: number;
}

export interface DashboardStats {
  totalVideos: number;
  totalSubtitles: number;
  activeTasks: number;
  completedTasks: number;
  failedTasks: number;
  storageUsed: number;
  storageTotal: number;
}

// Type definitions
const TaskType = {
  LIBRARY_SCAN: 'LIBRARY_SCAN',
  SUBTITLE_EXTRACTION: 'SUBTITLE_EXTRACTION',
  SUBTITLE_GENERATION: 'SUBTITLE_GENERATION',
  SUBTITLE_SYNC: 'SUBTITLE_SYNC',
  SUBTITLE_TRANSLATION: 'SUBTITLE_TRANSLATION',
  TRANSLATION: 'TRANSLATION',
  FILE_CLEANUP: 'FILE_CLEANUP',
  DATABASE_BACKUP: 'DATABASE_BACKUP',
  DATABASE_OPTIMIZATION: 'DATABASE_OPTIMIZATION',
  USER_EXPORT: 'USER_EXPORT',
  BATCH_SYNC: 'BATCH_SYNC',
  BATCH_TRANSLATION: 'BATCH_TRANSLATION'
} as const;

export type TaskType = typeof TaskType[keyof typeof TaskType];

const TaskStatus = {
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED'
} as const;

export type TaskStatus = typeof TaskStatus[keyof typeof TaskStatus];

export interface Task {
  id: string;
  type: TaskType;
  status: TaskStatus;
  progressMessage?: string;
  errorMessage?: string;
  message?: string;
  progress: number;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
  userId?: string;
  videoId?: string;
  subtitleId?: string;
}

export interface Subtitle {
  id: string;
  videoId: string;
  language: string;
  filePath?: string;
  content?: string;
  isGenerated: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Video {
  id: string;
  title: string;
  filePath: string;
  duration?: number;
  createdAt: string;
  updatedAt: string;
  userId: string;
  subtitles: Subtitle[];
}

export interface TaskStatistics {
  [key: string]: number;
}

// Authentication types
export interface LoginRequest {
  password: string;
}

export interface AuthResponse {
  success: boolean;
  token?: string;
  expiresIn?: number;
  message: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface ChangePasswordResponse {
  success: boolean;
  message: string;
}

// Token management
const TOKEN_KEY = 'skiperr_auth_token';

export const tokenManager = {
  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  },
  
  setToken(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
  },
  
  removeToken(): void {
    localStorage.removeItem(TOKEN_KEY);
  },
  
  isAuthenticated(): boolean {
    return !!this.getToken();
  }
};

// Generic API request function
async function apiRequest<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<ApiResponse<T>> {
  try {
    const url = `${API_BASE_URL}${endpoint}`;
    const token = tokenManager.getToken();
    
    const response = await fetch(url, {
      headers: {
        'Content-Type': 'application/json',
        ...(token && { 'Authorization': `Bearer ${token}` }),
        ...options.headers,
      },
      ...options,
    });

    const status = response.status;
    
    if (!response.ok) {
      // If unauthorized, clear the token
      if (status === 401) {
        tokenManager.removeToken();
      }
      
      const errorText = await response.text();
      return {
        error: errorText || `HTTP ${status}: ${response.statusText}`,
        status,
      };
    }

    const data = await response.json();
    return {
      data,
      status,
    };
  } catch (error) {
    return {
      error: error instanceof Error ? error.message : 'Network error',
      status: 0,
    };
  }
}

// Video API
export const videoApi = {
  // Get all videos with pagination
  getVideos: async (page = 0, size = 20): Promise<ApiResponse<Video[]>> => {
    return apiRequest<Video[]>(`/api/videos?page=${page}&size=${size}`);
  },

  // Get video by ID
  getVideo: async (id: string): Promise<ApiResponse<Video>> => {
    return apiRequest<Video>(`/api/videos/${id}`);
  },

  // Get videos by type
  getVideosByType: async (type: string): Promise<ApiResponse<Video[]>> => {
    return apiRequest<Video[]>(`/api/videos/type/${type}`);
  },

  // Get unprocessed videos
  getUnprocessedVideos: async (): Promise<ApiResponse<Video[]>> => {
    return apiRequest<Video[]>('/api/videos/unprocessed');
  },

  // Get videos without Greek subtitles
  getVideosWithoutGreek: async (): Promise<ApiResponse<Video[]>> => {
    return apiRequest<Video[]>('/api/videos/without-greek');
  },

  // Get out of sync videos
  getOutOfSyncVideos: async (): Promise<ApiResponse<Video[]>> => {
    return apiRequest<Video[]>('/api/videos/out-of-sync');
  },

  // Get recent videos
  getRecentVideos: async (): Promise<ApiResponse<Video[]>> => {
    return apiRequest<Video[]>('/api/videos/recent');
  },

  // Get video count by type
  getVideoCountByType: async (type: string): Promise<ApiResponse<number>> => {
    return apiRequest<number>(`/api/videos/count/${type}`);
  },
};

// Task API
export const taskApi = {
  // Get all tasks with pagination and filters
  getTasks: async (
    page = 0,
    size = 20,
    status?: string,
    type?: string
  ): Promise<ApiResponse<Task[]>> => {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (status) params.append('status', status);
    if (type) params.append('type', type);
    
    return apiRequest<Task[]>(`/api/tasks?${params.toString()}`);
  },

  // Get task by ID
  getTask: async (id: string): Promise<ApiResponse<Task>> => {
    return apiRequest<Task>(`/api/tasks/${id}`);
  },

  // Get tasks for specific video
  getTasksForVideo: async (videoId: string): Promise<ApiResponse<Task[]>> => {
    return apiRequest<Task[]>(`/api/tasks/video/${videoId}`);
  },

  // Get task statistics
  getTaskStatistics: async (): Promise<ApiResponse<TaskStatistics>> => {
    return apiRequest<TaskStatistics>('/api/tasks/statistics');
  },

  // Create scan task
  createScanTask: async (paths: string[]): Promise<ApiResponse<Task>> => {
    return apiRequest<Task>('/api/tasks/scan', {
      method: 'POST',
      body: JSON.stringify({ paths }),
    });
  },

  // Create sync task
  createSyncTask: async (
    videoId: string,
    subtitleId?: string,
    tool?: string
  ): Promise<ApiResponse<Task>> => {
    return apiRequest<Task>(`/api/tasks/sync/${videoId}`, {
      method: 'POST',
      body: JSON.stringify({ subtitleId, tool }),
    });
  },

  // Create translation task
  createTranslationTask: async (
    subtitleId: string,
    targetLanguage: string,
    provider?: string
  ): Promise<ApiResponse<Task>> => {
    return apiRequest<Task>(`/api/tasks/translate/${subtitleId}`, {
      method: 'POST',
      body: JSON.stringify({ targetLanguage, provider }),
    });
  },

  // Create batch task
  createBatchTask: async (
    type: string,
    videoIds: string[],
    parameters?: Record<string, any>
  ): Promise<ApiResponse<Task>> => {
    return apiRequest<Task>('/api/tasks/batch', {
      method: 'POST',
      body: JSON.stringify({ type, videoIds, parameters }),
    });
  },

  // Cancel task
  cancelTask: async (id: string): Promise<ApiResponse<void>> => {
    return apiRequest<void>(`/api/tasks/${id}/cancel`, {
      method: 'POST',
    });
  },

  // Retry task
  retryTask: async (id: string): Promise<ApiResponse<Task>> => {
    return apiRequest<Task>(`/api/tasks/${id}/retry`, {
      method: 'POST',
    });
  },

  // Cleanup completed tasks
  cleanupTasks: async (): Promise<ApiResponse<void>> => {
    return apiRequest<void>('/api/tasks/cleanup', {
      method: 'POST',
    });
  },
};

// Scan API
export const scanApi = {
  // Trigger library scan
  scanLibrary: async (paths?: string[]): Promise<ApiResponse<any>> => {
    return apiRequest<any>('/api/scan', {
      method: 'POST',
      body: paths ? JSON.stringify({ paths }) : undefined,
    });
  },
};

// Processing API
export const processingApi = {
  // Translate video subtitles
  translateVideo: async (id: string): Promise<ApiResponse<any>> => {
    return apiRequest<any>(`/api/translate/${id}`, {
      method: 'POST',
    });
  },

  // Sync video subtitles
  syncVideo: async (id: string): Promise<ApiResponse<any>> => {
    return apiRequest<any>(`/api/sync/${id}`, {
      method: 'POST',
    });
  },
};

// Health API
export const healthApi = {
  // Check backend health
  checkHealth: async (): Promise<ApiResponse<any>> => {
    return apiRequest<any>('/api/health');
  },
};

// Dashboard API - combines multiple endpoints for dashboard stats
export const dashboardApi = {
  // Get dashboard statistics
  getStats: async (): Promise<ApiResponse<DashboardStats>> => {
    try {
      // Make parallel requests to get all stats
      const [videosResponse, tasksResponse] = await Promise.all([
        videoApi.getVideos(0, 1), // Just to get total count from headers or response
        taskApi.getTaskStatistics(),
      ]);

      if (videosResponse.error || tasksResponse.error) {
        return {
          error: videosResponse.error || tasksResponse.error,
          status: videosResponse.status || tasksResponse.status,
        };
      }

      const taskStats = tasksResponse.data || {};
      
      // Calculate stats from task statistics
      const stats: DashboardStats = {
        totalVideos: 0, // Will need to be calculated from actual video count
        totalSubtitles: 0, // Will need to be calculated
        activeTasks: (taskStats['PENDING'] || 0) + (taskStats['RUNNING'] || 0),
        completedTasks: taskStats['COMPLETED'] || 0,
        failedTasks: taskStats['FAILED'] || 0,
        storageUsed: 0, // Will need backend endpoint for storage info
        storageTotal: 100, // Will need backend endpoint for storage info
      };

      return {
        data: stats,
        status: 200,
      };
    } catch (error) {
      return {
        error: error instanceof Error ? error.message : 'Failed to fetch dashboard stats',
        status: 0,
      };
    }
  },
};

// Authentication API
export const authApi = {
  // Login with password
  login: async (password: string): Promise<ApiResponse<AuthResponse>> => {
    const response = await apiRequest<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ password }),
    });
    
    // If login successful, store the token
    if (response.data?.success && response.data.token) {
      tokenManager.setToken(response.data.token);
    }
    
    return response;
  },

  // Validate current token
  validateToken: async (): Promise<ApiResponse<{ valid: boolean; message: string }>> => {
    return apiRequest<{ valid: boolean; message: string }>('/api/auth/validate', {
      method: 'POST',
    });
  },

  // Change password
  changePassword: async (currentPassword: string, newPassword: string): Promise<ApiResponse<ChangePasswordResponse>> => {
    return apiRequest<ChangePasswordResponse>('/api/auth/change-password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword, newPassword }),
    });
  },

  // Logout
  logout: async (): Promise<ApiResponse<{ message: string }>> => {
    const response = await apiRequest<{ message: string }>('/api/auth/logout', {
      method: 'POST',
    });
    
    // Always clear the token on logout, regardless of response
    tokenManager.removeToken();
    
    return response;
  },

  // Get authentication status
  getStatus: async (): Promise<ApiResponse<{ authenticated: boolean; user: string; timestamp: number }>> => {
    return apiRequest<{ authenticated: boolean; user: string; timestamp: number }>('/api/auth/status');
  },
};