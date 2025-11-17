# Quarkus Subtitle Manager

## 📘 Project Overview

The **Quarkus Subtitle Manager** is a Kotlin + React system that integrates with Plex to manage, extract, sync, and translate subtitles for your media library. It’s designed for users with large collections of movies and TV shows, providing AI-assisted synchronization and translation of subtitles while maintaining a clear overview through a React dashboard.

---

## 🎯 Goal

To automatically maintain perfectly synced Greek and English subtitles for every movie and episode in your library — using AI translation for missing Greek subtitles and auto-sync for out-of-sync ones.

---

## 🧩 Core Components

### 1. **Backend — Quarkus (Kotlin)**

A high-performance asynchronous REST backend powered by Quarkus and Kotlin.

#### Key Modules

* **MediaScannerService**: Scans directories or Plex libraries to discover new media files and update the database.
* **SubtitleExtractor**: Extracts embedded subtitles (using FFmpeg or MKVToolNix) and stores them in a structured format.
* **SubtitleSyncer**: Uses tools like `ffsubsync` or `WhisperX` to align subtitles with the video’s audio.
* **SubTranslator**: Uses AI translation APIs (OpenAI, DeepL, or custom) to translate English subtitles into Greek.
* **Database Layer**: Manages media metadata, subtitle states, and Plex mapping (via Panache ORM + PostgreSQL).
* **TaskScheduler**: Handles background jobs for periodic rescans, sync checks, and translation queues.

### 2. **Frontend — React Dashboard**

A modern, TailwindCSS-based dashboard showing:

* Scanning progress and recently added media.
* Subtitles summary (missing, synced, translated, or pending).
* Manual controls for resync, re-translate, or re-extract.
* Search and filtering by language, sync status, or media type.

---

## 🔄 Processing Workflow

### Step 1 — Library Scanning

* The **ScanService** enumerates all media directories or queries Plex via API.
* It populates the database with movies, shows, and episodes, tagging each with a unique hash.

### Step 2 — Subtitle Extraction

* Embedded subtitles are extracted using FFmpeg or MKVToolNix.
* Each track is saved under `/subtitles/{mediaId}/`.
* The service detects subtitle language and type (embedded, external, burned-in if detectable).

### Step 3 — Subtitle Validation

* Subtitles are checked for timing accuracy using sync metrics (e.g., silence alignment or speech-to-text comparison).
* Incorrectly synced subtitles are queued for resync.

### Step 4 — Subtitle Translation

* If no Greek subtitles are present, the **SubTranslator** extracts English lines, chunk-splits them, translates via AI, and reconstructs an SRT.
* AI translation preserves timing and subtitle structure.

### Step 5 — Syncing

* The **SubtitleSyncer** uses AI-based sync adjustment tools (ffsubsync or WhisperX alignment).
* Adjusted subtitles replace originals and are revalidated.

### Step 6 — Dashboard Update

* React UI polls backend status or subscribes via WebSocket.
* The user can view, approve, or trigger manual actions.

---

## 🧠 AI & Automation

* **AI Translation** — Handles both literal and contextual subtitle translation.
* **AI Syncing** — Re-aligns SRTs by matching speech to audio.
* **AI Detection** — Determines if subtitles are machine-translated or misaligned.

---

## 🗄️ Database Schema Overview

Tables:

* `media_items`: Movies, shows, or episodes.
* `subtitles`: Subtitle metadata (language, type, sync status, file path).
* `tasks`: Background job queue (scan, sync, translate, etc.).
* `plex_links`: Plex mapping for tracking.

---

## ⚙️ Deployment Setup

### Using Docker Compose

The setup includes:

```yaml
services:
  quarkus-backend:
    build: ./backend
    ports:
      - "8080:8080"
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: subtitles
      POSTGRES_PASSWORD: subtitles
      POSTGRES_DB: subtitles
    volumes:
      - ./data/db:/var/lib/postgresql/data
  react-frontend:
    build: ./frontend
    ports:
      - "5173:80"
```

---

## 🚀 Planned Features

* ✅ Extract embedded subtitles automatically.
* ✅ Detect and delete mismatched Greek subtitles.
* ✅ Translate missing Greek subtitles from English.
* ✅ Sync subtitles with WhisperX or ffsubsync.
* ✅ Manage everything from a clean dashboard.
* 🔜 Support multiple subtitle languages.
* 🔜 Local caching and cloud storage sync (Google Drive, Dropbox, S3).
* 🔜 Native Tauri desktop app integration.

---

## 🧰 Stack Summary

| Layer          | Technology                                       |
| -------------- | ------------------------------------------------ |
| Backend        | Quarkus (Kotlin, Panache ORM, RESTEasy Reactive) |
| Frontend       | React + TailwindCSS                              |
| Database       | PostgreSQL                                       |
| External Tools | FFmpeg, MKVToolNix, WhisperX, ffsubsync          |
| AI Translation | OpenAI / DeepL / Custom LLMs                     |

---

## 📈 Future Expansion

* Real-time subtitle sync feedback.
* AI-enhanced error correction.
* Mobile-friendly subtitle management view.
* Integration with Plex metadata (e.g., genre-based subtitle preferences).

---

## 🧭 Summary

This project bridges your Plex media library with AI-powered automation for subtitles — scanning, syncing, and translating without manual work. It provides full visibility and control from a single elegant dashboard.

> **In short:** You’ll never again have to manually find, sync, or translate a subtitle — the system handles it all, intelligently.
