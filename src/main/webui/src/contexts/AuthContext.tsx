import { createContext, useContext, useState, useEffect } from 'react';
import type { ReactNode } from 'react';
import { authApi, tokenManager } from '../services/api';

interface AuthContextType {
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (password: string) => Promise<{ success: boolean; message: string }>;
  logout: () => Promise<void>;
  validateToken: () => Promise<boolean>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  // Check if user is authenticated on app start
  useEffect(() => {
    const checkAuth = async () => {
      const token = tokenManager.getToken();
      
      if (!token) {
        setIsAuthenticated(false);
        setIsLoading(false);
        return;
      }

      // Validate the token with the backend
      try {
        const response = await authApi.validateToken();
        if (response.data?.valid) {
          setIsAuthenticated(true);
        } else {
          // Token is invalid, remove it
          tokenManager.removeToken();
          setIsAuthenticated(false);
        }
      } catch (error) {
        // If validation fails, assume token is invalid
        tokenManager.removeToken();
        setIsAuthenticated(false);
      }
      
      setIsLoading(false);
    };

    checkAuth();
  }, []);

  const login = async (password: string): Promise<{ success: boolean; message: string }> => {
    try {
      const response = await authApi.login(password);
      
      if (response.data?.success) {
        setIsAuthenticated(true);
        return { success: true, message: response.data.message };
      } else {
        return { 
          success: false, 
          message: response.data?.message || response.error || 'Login failed' 
        };
      }
    } catch (error) {
      return { 
        success: false, 
        message: error instanceof Error ? error.message : 'Login failed' 
      };
    }
  };

  const logout = async (): Promise<void> => {
    try {
      await authApi.logout();
    } catch (error) {
      // Even if logout fails on backend, we still clear local state
      console.error('Logout error:', error);
    } finally {
      setIsAuthenticated(false);
      tokenManager.removeToken();
    }
  };

  const validateToken = async (): Promise<boolean> => {
    try {
      const response = await authApi.validateToken();
      const isValid = response.data?.valid || false;
      
      if (!isValid) {
        setIsAuthenticated(false);
        tokenManager.removeToken();
      }
      
      return isValid;
    } catch (error) {
      setIsAuthenticated(false);
      tokenManager.removeToken();
      return false;
    }
  };

  const value: AuthContextType = {
    isAuthenticated,
    isLoading,
    login,
    logout,
    validateToken,
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}