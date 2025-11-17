import { useState, useEffect } from 'react';
import { format } from 'date-fns';
import {
  DocumentTextIcon,
  LanguageIcon,
  EllipsisVerticalIcon,
  MagnifyingGlassIcon,
  FunnelIcon,
  PlusIcon,
  CheckCircleIcon,
  XCircleIcon,
  ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import { videoApi } from '../services/api';
import type { Subtitle, Video } from '../services/api';

interface SubtitleWithVideo extends Subtitle {
  video: Video;
}

export default function Subtitles() {
  const [subtitles, setSubtitles] = useState<SubtitleWithVideo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [languageFilter, setLanguageFilter] = useState<string>('all');
  const [typeFilter, setTypeFilter] = useState<'all' | 'generated' | 'uploaded'>('all');
  const [sortBy, setSortBy] = useState<'createdAt' | 'language' | 'video'>('createdAt');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');

  // Fetch videos and their subtitles
  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        
        const response = await videoApi.getVideos();
        if (response.error) {
          setError(response.error);
          return;
        }

        const videosData = response.data || [];
        
        // Extract all subtitles from videos and create SubtitleWithVideo objects
        const allSubtitles: SubtitleWithVideo[] = [];
        videosData.forEach(video => {
          if (video.subtitles && video.subtitles.length > 0) {
            video.subtitles.forEach(subtitle => {
              allSubtitles.push({
                ...subtitle,
                video: video,
              });
            });
          }
        });
        
        setSubtitles(allSubtitles);
      } catch (err) {
        setError('Failed to fetch subtitles data');
        console.error('Error fetching subtitles:', err);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, []);

  const availableLanguages = Array.from(new Set(subtitles.map(sub => sub.language)));

  const filteredAndSortedSubtitles = subtitles
    .filter(subtitle => {
      const matchesSearch = 
        subtitle.video.title.toLowerCase().includes(searchTerm.toLowerCase()) ||
        subtitle.language.toLowerCase().includes(searchTerm.toLowerCase());
      
      const matchesLanguage = languageFilter === 'all' || subtitle.language === languageFilter;
      
      const matchesType = 
        typeFilter === 'all' ||
        (typeFilter === 'generated' && subtitle.isGenerated) ||
        (typeFilter === 'uploaded' && !subtitle.isGenerated);
      
      return matchesSearch && matchesLanguage && matchesType;
    })
    .sort((a, b) => {
      let aValue: any;
      let bValue: any;
      
      switch (sortBy) {
        case 'createdAt':
          aValue = new Date(a.createdAt).getTime();
          bValue = new Date(b.createdAt).getTime();
          break;
        case 'language':
          aValue = a.language;
          bValue = b.language;
          break;
        case 'video':
          aValue = a.video.title;
          bValue = b.video.title;
          break;
        default:
          aValue = a.createdAt;
          bValue = b.createdAt;
      }
      
      if (sortOrder === 'asc') {
        return aValue > bValue ? 1 : -1;
      } else {
        return aValue < bValue ? 1 : -1;
      }
    });

  const handleSort = (field: 'createdAt' | 'language' | 'video') => {
    if (sortBy === field) {
      setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
    } else {
      setSortBy(field);
      setSortOrder('desc');
    }
  };

  const getLanguageDisplayName = (code: string) => {
    const languages: { [key: string]: string } = {
      'en': 'English',
      'es': 'Spanish',
      'fr': 'French',
      'de': 'German',
      'it': 'Italian',
      'pt': 'Portuguese',
      'ru': 'Russian',
      'ja': 'Japanese',
      'ko': 'Korean',
      'zh': 'Chinese',
    };
    return languages[code] || code.toUpperCase();
  };

  return (
    <div className="space-y-6">
      {/* Header Actions */}
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-4">
          {/* Search */}
          <div className="relative">
            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" />
            </div>
            <input
              type="text"
              placeholder="Search subtitles..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="block w-64 pl-10 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-primary-500 focus:border-primary-500 sm:text-sm"
            />
          </div>
          
          {/* Language Filter */}
          <select
            value={languageFilter}
            onChange={(e) => setLanguageFilter(e.target.value)}
            className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
          >
            <option value="all">All Languages</option>
            {availableLanguages.map(lang => (
              <option key={lang} value={lang}>{getLanguageDisplayName(lang)}</option>
            ))}
          </select>
          
          {/* Type Filter */}
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value as any)}
            className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
          >
            <option value="all">All Types</option>
            <option value="uploaded">Uploaded</option>
            <option value="generated">Generated</option>
          </select>
          
          {/* Sort */}
          <div className="flex items-center space-x-2">
            <FunnelIcon className="h-5 w-5 text-gray-400" />
            <select
              value={`${sortBy}-${sortOrder}`}
              onChange={(e) => {
                const [field, order] = e.target.value.split('-');
                setSortBy(field as any);
                setSortOrder(order as any);
              }}
              className="border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-primary-500 focus:border-primary-500"
            >
              <option value="createdAt-desc">Newest First</option>
              <option value="createdAt-asc">Oldest First</option>
              <option value="video-asc">Video A-Z</option>
              <option value="video-desc">Video Z-A</option>
              <option value="language-asc">Language A-Z</option>
              <option value="language-desc">Language Z-A</option>
            </select>
          </div>
        </div>
        
        <button className="btn-primary flex items-center space-x-2">
          <PlusIcon className="h-5 w-5" />
          <span>Add Subtitle</span>
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-4">
        <div className="card">
          <div className="flex items-center">
            <div className="flex-shrink-0 p-3 rounded-lg bg-blue-50">
              <DocumentTextIcon className="w-6 h-6 text-blue-600" />
            </div>
            <div className="ml-4">
              <p className="text-sm font-medium text-gray-500">Total Subtitles</p>
              <p className="text-2xl font-semibold text-gray-900">{subtitles.length}</p>
            </div>
          </div>
        </div>
        
        <div className="card">
          <div className="flex items-center">
            <div className="flex-shrink-0 p-3 rounded-lg bg-green-50">
              <CheckCircleIcon className="w-6 h-6 text-green-600" />
            </div>
            <div className="ml-4">
              <p className="text-sm font-medium text-gray-500">Generated</p>
              <p className="text-2xl font-semibold text-gray-900">
                {subtitles.filter(s => s.isGenerated).length}
              </p>
            </div>
          </div>
        </div>
        
        <div className="card">
          <div className="flex items-center">
            <div className="flex-shrink-0 p-3 rounded-lg bg-purple-50">
              <DocumentTextIcon className="w-6 h-6 text-purple-600" />
            </div>
            <div className="ml-4">
              <p className="text-sm font-medium text-gray-500">Uploaded</p>
              <p className="text-2xl font-semibold text-gray-900">
                {subtitles.filter(s => !s.isGenerated).length}
              </p>
            </div>
          </div>
        </div>
        
        <div className="card">
          <div className="flex items-center">
            <div className="flex-shrink-0 p-3 rounded-lg bg-yellow-50">
              <LanguageIcon className="w-6 h-6 text-yellow-600" />
            </div>
            <div className="ml-4">
              <p className="text-sm font-medium text-gray-500">Languages</p>
              <p className="text-2xl font-semibold text-gray-900">{availableLanguages.length}</p>
            </div>
          </div>
        </div>
      </div>

      {/* Subtitles Table */}
      <div className="card">
        {loading ? (
          <div className="text-center py-12">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
            <p className="mt-4 text-sm text-gray-500">Loading subtitles...</p>
          </div>
        ) : error ? (
          <div className="text-center py-12">
            <ExclamationTriangleIcon className="mx-auto h-12 w-12 text-red-400" />
            <h3 className="mt-2 text-sm font-medium text-gray-900">Error loading subtitles</h3>
            <p className="mt-1 text-sm text-gray-500">{error}</p>
            <button 
              onClick={() => window.location.reload()}
              className="mt-4 btn-secondary"
            >
              Try Again
            </button>
          </div>
        ) : filteredAndSortedSubtitles.length === 0 ? (
          <div className="text-center py-12">
            <DocumentTextIcon className="mx-auto h-12 w-12 text-gray-400" />
            <h3 className="mt-2 text-sm font-medium text-gray-900">No subtitles found</h3>
            <p className="mt-1 text-sm text-gray-500">
              {searchTerm || languageFilter !== 'all' || typeFilter !== 'all' 
                ? 'Try adjusting your search or filters.' 
                : 'Get started by adding your first subtitle file.'}
            </p>
          </div>
        ) : (
          <div className="overflow-hidden">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th
                    scope="col"
                    className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
                    onClick={() => handleSort('video')}
                  >
                    Video
                    {sortBy === 'video' && (
                      <span className="ml-1">{sortOrder === 'asc' ? '↑' : '↓'}</span>
                    )}
                  </th>
                  <th
                    scope="col"
                    className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
                    onClick={() => handleSort('language')}
                  >
                    Language
                    {sortBy === 'language' && (
                      <span className="ml-1">{sortOrder === 'asc' ? '↑' : '↓'}</span>
                    )}
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    Type
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                    File
                  </th>
                  <th
                    scope="col"
                    className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
                    onClick={() => handleSort('createdAt')}
                  >
                    Created
                    {sortBy === 'createdAt' && (
                      <span className="ml-1">{sortOrder === 'asc' ? '↑' : '↓'}</span>
                    )}
                  </th>
                  <th scope="col" className="relative px-6 py-3">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {filteredAndSortedSubtitles.map((subtitle) => (
                  <tr key={subtitle.id} className="hover:bg-gray-50">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center">
                        <div className="flex-shrink-0 h-10 w-10">
                          <div className="h-10 w-10 rounded-lg bg-gray-200 flex items-center justify-center">
                            <DocumentTextIcon className="h-5 w-5 text-gray-500" />
                          </div>
                        </div>
                        <div className="ml-4">
                          <div className="text-sm font-medium text-gray-900">{subtitle.video.title}</div>
                          <div className="text-sm text-gray-500 truncate max-w-xs">{subtitle.video.filePath}</div>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="flex items-center space-x-2">
                        <LanguageIcon className="h-4 w-4 text-gray-400" />
                        <span className="text-sm text-gray-900">{getLanguageDisplayName(subtitle.language)}</span>
                        <span className="text-xs text-gray-500">({subtitle.language})</span>
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      {subtitle.isGenerated ? (
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                          Generated
                        </span>
                      ) : (
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                          Uploaded
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                      {subtitle.filePath ? (
                        <div className="flex items-center space-x-1">
                          <CheckCircleIcon className="h-4 w-4 text-green-500" />
                          <span className="truncate max-w-xs">{subtitle.filePath}</span>
                        </div>
                      ) : (
                        <div className="flex items-center space-x-1">
                          <XCircleIcon className="h-4 w-4 text-red-500" />
                          <span className="text-red-500">No file</span>
                        </div>
                      )}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                      {format(new Date(subtitle.createdAt), 'MMM dd, yyyy')}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                      <button className="text-gray-400 hover:text-gray-600">
                        <EllipsisVerticalIcon className="h-5 w-5" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}