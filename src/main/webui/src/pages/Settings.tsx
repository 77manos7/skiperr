import React, { useState } from 'react';
import {
  CogIcon,
  ServerIcon,
  UserIcon,
  BellIcon,
  ShieldCheckIcon,
  FolderIcon,
} from '@heroicons/react/24/outline';

interface SettingsSection {
  id: string;
  name: string;
  icon: React.ComponentType<any>;
}

const sections: SettingsSection[] = [
  { id: 'general', name: 'General', icon: CogIcon },
  { id: 'storage', name: 'Storage', icon: ServerIcon },
  { id: 'profile', name: 'Profile', icon: UserIcon },
  { id: 'notifications', name: 'Notifications', icon: BellIcon },
  { id: 'security', name: 'Security', icon: ShieldCheckIcon },
  { id: 'library', name: 'Library', icon: FolderIcon },
];

export default function Settings() {
  const [activeSection, setActiveSection] = useState('general');
  const [settings, setSettings] = useState({
    // General
    theme: 'light',
    language: 'en',
    autoScan: true,
    
    // Storage
    videoPath: '/videos',
    subtitlePath: '/subtitles',
    maxStorageSize: 100,
    cleanupOldFiles: true,
    
    // Profile
    username: 'admin',
    email: 'admin@skiperr.com',
    
    // Notifications
    emailNotifications: true,
    taskNotifications: true,
    errorNotifications: true,
    
    // Security
    sessionTimeout: 24,
    requireAuth: true,
    
    // Library
    supportedFormats: ['mp4', 'avi', 'mkv', 'mov'],
    autoGenerateSubtitles: true,
    defaultLanguage: 'en',
  });

  const handleSettingChange = (key: string, value: any) => {
    setSettings(prev => ({ ...prev, [key]: value }));
  };

  const renderGeneralSettings = () => (
    <div className="space-y-6">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Theme
        </label>
        <select
          value={settings.theme}
          onChange={(e) => handleSettingChange('theme', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        >
          <option value="light">Light</option>
          <option value="dark">Dark</option>
          <option value="system">System</option>
        </select>
      </div>
      
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Language
        </label>
        <select
          value={settings.language}
          onChange={(e) => handleSettingChange('language', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        >
          <option value="en">English</option>
          <option value="es">Spanish</option>
          <option value="fr">French</option>
          <option value="de">German</option>
        </select>
      </div>
      
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Auto-scan library</label>
          <p className="text-sm text-gray-500">Automatically scan for new videos</p>
        </div>
        <input
          type="checkbox"
          checked={settings.autoScan}
          onChange={(e) => handleSettingChange('autoScan', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
    </div>
  );

  const renderStorageSettings = () => (
    <div className="space-y-6">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Video Storage Path
        </label>
        <input
          type="text"
          value={settings.videoPath}
          onChange={(e) => handleSettingChange('videoPath', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        />
      </div>
      
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Subtitle Storage Path
        </label>
        <input
          type="text"
          value={settings.subtitlePath}
          onChange={(e) => handleSettingChange('subtitlePath', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        />
      </div>
      
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Maximum Storage Size (GB)
        </label>
        <input
          type="number"
          value={settings.maxStorageSize}
          onChange={(e) => handleSettingChange('maxStorageSize', parseInt(e.target.value))}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        />
      </div>
      
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Cleanup old files</label>
          <p className="text-sm text-gray-500">Automatically remove old temporary files</p>
        </div>
        <input
          type="checkbox"
          checked={settings.cleanupOldFiles}
          onChange={(e) => handleSettingChange('cleanupOldFiles', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
    </div>
  );

  const renderProfileSettings = () => (
    <div className="space-y-6">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Username
        </label>
        <input
          type="text"
          value={settings.username}
          onChange={(e) => handleSettingChange('username', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        />
      </div>
      
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Email
        </label>
        <input
          type="email"
          value={settings.email}
          onChange={(e) => handleSettingChange('email', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        />
      </div>
      
      <div>
        <button className="btn-secondary">
          Change Password
        </button>
      </div>
    </div>
  );

  const renderNotificationSettings = () => (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Email notifications</label>
          <p className="text-sm text-gray-500">Receive notifications via email</p>
        </div>
        <input
          type="checkbox"
          checked={settings.emailNotifications}
          onChange={(e) => handleSettingChange('emailNotifications', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
      
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Task notifications</label>
          <p className="text-sm text-gray-500">Get notified when tasks complete</p>
        </div>
        <input
          type="checkbox"
          checked={settings.taskNotifications}
          onChange={(e) => handleSettingChange('taskNotifications', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
      
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Error notifications</label>
          <p className="text-sm text-gray-500">Get notified when errors occur</p>
        </div>
        <input
          type="checkbox"
          checked={settings.errorNotifications}
          onChange={(e) => handleSettingChange('errorNotifications', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
    </div>
  );

  const renderSecuritySettings = () => (
    <div className="space-y-6">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Session Timeout (hours)
        </label>
        <input
          type="number"
          value={settings.sessionTimeout}
          onChange={(e) => handleSettingChange('sessionTimeout', parseInt(e.target.value))}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        />
      </div>
      
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Require authentication</label>
          <p className="text-sm text-gray-500">Require login to access the dashboard</p>
        </div>
        <input
          type="checkbox"
          checked={settings.requireAuth}
          onChange={(e) => handleSettingChange('requireAuth', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
    </div>
  );

  const renderLibrarySettings = () => (
    <div className="space-y-6">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Supported Video Formats
        </label>
        <div className="space-y-2">
          {['mp4', 'avi', 'mkv', 'mov', 'wmv', 'flv'].map(format => (
            <div key={format} className="flex items-center">
              <input
                type="checkbox"
                id={format}
                checked={settings.supportedFormats.includes(format)}
                onChange={(e) => {
                  const formats = e.target.checked
                    ? [...settings.supportedFormats, format]
                    : settings.supportedFormats.filter(f => f !== format);
                  handleSettingChange('supportedFormats', formats);
                }}
                className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
              />
              <label htmlFor={format} className="ml-2 text-sm text-gray-700">
                .{format}
              </label>
            </div>
          ))}
        </div>
      </div>
      
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium text-gray-700">Auto-generate subtitles</label>
          <p className="text-sm text-gray-500">Automatically generate subtitles for new videos</p>
        </div>
        <input
          type="checkbox"
          checked={settings.autoGenerateSubtitles}
          onChange={(e) => handleSettingChange('autoGenerateSubtitles', e.target.checked)}
          className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
        />
      </div>
      
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Default Language
        </label>
        <select
          value={settings.defaultLanguage}
          onChange={(e) => handleSettingChange('defaultLanguage', e.target.value)}
          className="block w-full border border-gray-300 rounded-md px-3 py-2 focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
        >
          <option value="en">English</option>
          <option value="es">Spanish</option>
          <option value="fr">French</option>
          <option value="de">German</option>
        </select>
      </div>
    </div>
  );

  const renderContent = () => {
    switch (activeSection) {
      case 'general':
        return renderGeneralSettings();
      case 'storage':
        return renderStorageSettings();
      case 'profile':
        return renderProfileSettings();
      case 'notifications':
        return renderNotificationSettings();
      case 'security':
        return renderSecuritySettings();
      case 'library':
        return renderLibrarySettings();
      default:
        return renderGeneralSettings();
    }
  };

  return (
    <div className="flex space-x-6">
      {/* Settings Navigation */}
      <div className="w-64 card">
        <nav className="space-y-1">
          {sections.map((section) => (
            <button
              key={section.id}
              onClick={() => setActiveSection(section.id)}
              className={`w-full flex items-center px-3 py-2 text-sm font-medium rounded-md transition-colors duration-200 ${
                activeSection === section.id
                  ? 'bg-primary-50 text-primary-700 border-r-2 border-primary-700'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
              }`}
            >
              <section.icon className="flex-shrink-0 w-5 h-5 mr-3" />
              {section.name}
            </button>
          ))}
        </nav>
      </div>

      {/* Settings Content */}
      <div className="flex-1 card">
        <div className="mb-6">
          <h2 className="text-lg font-medium text-gray-900">
            {sections.find(s => s.id === activeSection)?.name} Settings
          </h2>
          <p className="text-sm text-gray-500 mt-1">
            Manage your {activeSection} preferences and configuration.
          </p>
        </div>

        {renderContent()}

        <div className="mt-8 pt-6 border-t border-gray-200">
          <div className="flex justify-end space-x-3">
            <button className="btn-secondary">
              Reset to Defaults
            </button>
            <button className="btn-primary">
              Save Changes
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}