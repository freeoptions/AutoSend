# AutoTG Design Specification

## 1. Project Overview
AutoTG is an Android application (optimized for Xiaomi 13/HyperOS) designed to schedule messages to Telegram groups or channels. It supports multiple bots and multiple chat destinations, with a robust retry mechanism for handling network instability (common when using proxies/VPNs).

## 2. Key Features
- **Bot Management**: Configure multiple Telegram bots using their API tokens.
- **Chat Management**: Store and categorize multiple destination Chat IDs (groups, channels, or individuals).
- **Task Scheduling**: Create one-time or recurring tasks using a selected Bot, Chat, and custom message content.
- **Reliability Engine**: 
    - Integration with Android **WorkManager** for background execution.
    - Automatic 3-time retry mechanism with exponential backoff.
    - Persistent failure logs for manual inspection and re-sending.
- **MIUI/HyperOS Optimization**: Guided permission setup for battery optimization, autostart, and background activity.

## 3. Technology Stack
- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel)
- **Database**: Room (for Bots, Chats, Tasks, and Logs)
- **Background Tasks**: WorkManager
- **Networking**: Retrofit + OkHttp
- **UI Framework**: Material 3 (Jetpack Compose)

## 4. Data Models (Room Tables)

### 4.1. Bots
- `id`: Int (PK)
- `name`: String (Alias)
- `token`: String (Bot Token)

### 4.2. Chats
- `id`: Int (PK)
- `name`: String (Alias)
- `chatId`: String (Real Telegram Chat ID)

### 4.3. ScheduledTasks
- `id`: Int (PK)
- `botId`: Int (FK to Bots)
- `chatId`: Int (FK to Chats)
- `content`: String
- `scheduledTime`: Long (Timestamp)
- `status`: String (PENDING, SUCCESS, FAILED)
- `retryCount`: Int (0-3)
- `lastError`: String?

## 5. Implementation Strategy
1. **Infrastructure**: Set up Room database and Retrofit client.
2. **UI Development**:
    - Bot/Chat management screens.
    - Task creation and list screens.
    - Log/Retry screen.
3. **Core Logic**:
    - `TelegramWorker` implementation for WorkManager.
    - Retry logic and error handling.
4. **Xiaomi Optimization**: Permission check utility for HyperOS background restrictions.

## 6. Success Criteria
- Tasks trigger within a reasonable window of the scheduled time (+/- 5 mins via WorkManager).
- Messages are successfully delivered when the system proxy/VPN is active.
- Failed tasks are correctly logged and can be manually re-sent after fixing network issues.
