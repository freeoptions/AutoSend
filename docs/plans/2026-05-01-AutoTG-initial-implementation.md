# AutoTG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app for Xiaomi 13 to schedule Telegram messages with multi-bot support and 3-time retry mechanism.

**Architecture:** MVVM with Jetpack Compose for UI, Room for persistence, and WorkManager for reliable background scheduling.

**Tech Stack:** Kotlin, Jetpack Compose, Room, WorkManager, Retrofit, OkHttp.

---

### Task 1: Project Initialization & Directory Structure

**Files:**
- Create: `build.gradle.kts` (Project level)
- Create: `app/build.gradle.kts` (Module level)
- Create: `app/src/main/AndroidManifest.xml`
- Create: Directory structure for `com.autotg`

- [ ] **Step 1: Define directory structure**
Run: `mkdir -p app/src/main/java/com/autotg/{data,ui,worker,di}`

- [ ] **Step 2: Configure app/build.gradle.kts with dependencies**
Include: Compose, Room, WorkManager, Retrofit.

- [ ] **Step 3: Setup AndroidManifest.xml**
Include INTERNET permission and WorkManager initializer if needed.

- [ ] **Step 4: Commit**
```bash
git add .
git commit -m "chore: initialize android project structure and dependencies"
```

---

### Task 2: Data Layer (Room Database)

**Files:**
- Create: `app/src/main/java/com/autotg/data/models/Bot.kt`
- Create: `app/src/main/java/com/autotg/data/models/Chat.kt`
- Create: `app/src/main/java/com/autotg/data/models/ScheduledTask.kt`
- Create: `app/src/main/java/com/autotg/data/local/AppDatabase.kt`

- [ ] **Step 1: Define Entity classes for Bot, Chat, and ScheduledTask**
- [ ] **Step 2: Define DAO interfaces with CRUD operations**
- [ ] **Step 3: Create AppDatabase class**
- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/autotg/data/
git commit -m "feat: implement room database layer"
```

---

### Task 3: Telegram API Layer

**Files:**
- Create: `app/src/main/java/com/autotg/data/remote/TelegramApi.kt`
- Create: `app/src/main/java/com/autotg/data/remote/RetrofitClient.kt`

- [ ] **Step 1: Define TelegramApi interface**
```kotlin
interface TelegramApi {
    @POST("bot{token}/sendMessage")
    suspend fun sendMessage(
        @Path("token") token: String,
        @Query("chat_id") chatId: String,
        @Query("text") text: String
    ): Response<ResponseBody>
}
```
- [ ] **Step 2: Implement RetrofitClient with OkHttp logging**
- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/autotg/data/remote/
git commit -m "feat: implement telegram api client"
```

---

### Task 4: Core Worker (WorkManager)

**Files:**
- Create: `app/src/main/java/com/autotg/worker/TelegramWorker.kt`

- [ ] **Step 1: Implement TelegramWorker extending CoroutineWorker**
Include logic to:
1. Fetch task details from Room.
2. Fetch Bot/Chat details.
3. Call TelegramApi.
4. Handle success/failure and update task status.
- [ ] **Step 2: Implement retry logic using WorkManager's Result.retry()**
- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/autotg/worker/
git commit -m "feat: implement core telegram worker with retry logic"
```

---

### Task 5: UI - Bot & Chat Management

**Files:**
- Create: `app/src/main/java/com/autotg/ui/screens/ConfigScreen.kt`
- Create: `app/src/main/java/com/autotg/ui/viewmodels/ConfigViewModel.kt`

- [ ] **Step 1: Create ConfigViewModel to manage Bot/Chat data**
- [ ] **Step 2: Build UI for adding/editing/deleting Bots and Chats**
- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/autotg/ui/
git commit -m "feat: add bot and chat management UI"
```

---

### Task 6: UI - Task Scheduling & Dashboard

**Files:**
- Create: `app/src/main/java/com/autotg/ui/screens/MainScreen.kt`
- Create: `app/src/main/java/com/autotg/ui/viewmodels/MainViewModel.kt`

- [ ] **Step 1: Create MainViewModel to handle task creation and WorkManager scheduling**
- [ ] **Step 2: Build Task List UI with status indicators**
- [ ] **Step 3: Build Task Creation Dialog**
- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/autotg/ui/
git commit -m "feat: add task scheduling UI and dashboard"
```

---

### Task 7: Xiaomi 13 Optimizations & Final Polish

**Files:**
- Modify: `app/src/main/java/com/autotg/MainActivity.kt`
- Create: `app/src/main/java/com/autotg/utils/PermissionUtils.kt`

- [ ] **Step 1: Implement battery optimization and autostart permission check**
- [ ] **Step 2: Add failure log view in UI**
- [ ] **Step 3: Final integration testing**
- [ ] **Step 4: Commit**
```bash
git add .
git commit -m "feat: add xiaomi optimizations and final polish"
```
