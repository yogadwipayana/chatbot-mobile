# Flow Aplikasi Chatbot

Dokumen ini merangkum alur aplikasi dari splash screen, daftar sesi, halaman chat, pemrosesan AI, penyimpanan database, sampai response tampil kembali di UI.

## Flowchart Utama

```mermaid
flowchart TD
    A[User membuka aplikasi] --> B[AndroidManifest.xml menjalankan SplashActivity.java]
    B --> C[SplashActivity.java menampilkan activity_splash.xml]
    C -->|Delay 2 detik| D[MainActivity.java]

    D --> E[Inisialisasi RecyclerView, SessionAdapter.java, ChatDAO.java]
    E --> F[loadSessions]
    F --> G[ChatDAO.java.getAllSessions]
    G --> H{Ada session?}
    H -->|Tidak| I[Tampilkan empty state]
    H -->|Ya| J[Tampilkan daftar session]

    I --> K[User klik Chat Baru]
    J --> K
    J --> L[User klik session lama]
    J --> M[User buka menu session]

    K --> N[MainActivity.java.createNewSession]
    N --> O[ChatDAO.java.insertSession New Chat]
    O --> P[Buka ChatActivity.java dengan session_id baru]

    L --> Q[Buka ChatActivity.java dengan session_id lama]

    M --> R{Aksi session}
    R -->|Rename| S[showRenameDialog]
    S --> T[ChatDAO.java.updateSessionTitle]
    T --> F
    R -->|Delete| U[showDeleteConfirmDialog]
    U --> V[ChatDAO.java.deleteSession]
    V --> W[SQLite cascade delete messages]
    W --> F

    P --> X[ChatActivity.java + activity_chat.xml]
    Q --> X
    X --> Y[Validasi session_id]
    Y -->|Tidak valid| Z[finish]
    Y -->|Valid| AA[Inisialisasi UI activity_chat.xml, MessageAdapter.java, ChatDAO.java]
    AA --> AB[loadChatHistory]
    AB --> AC[ChatDAO.java.getMessagesBySession]
    AB --> AD[ChatDAO.java.getSessionById]
    AC --> AE[Tampilkan riwayat pesan]
    AD --> AE

    AE --> AF[User mengetik pesan]
    AF --> AG[User klik Send]
    AG --> AH{Internet valid?}
    AH -->|Tidak| AI[Tampilkan status Offline dan Toast]
    AH -->|Ya| AJ{AI sedang thinking?}
    AJ -->|Ya| AK[Tampilkan Toast tunggu jawaban]
    AJ -->|Tidak| AL[ChatActivity.java.sendMessage]

    AL --> AM[ChatDAO.java.insertMessage role user]
    AM --> AN{Pesan pertama?}
    AN -->|Ya| AO[Generate title dan update session]
    AN -->|Tidak| AP[Tampilkan pesan user]
    AO --> AP
    AP --> AQ[showProcessingStatus Thinking]
    AQ --> AR[startAiRequestService]

    AR --> AS[AiRequestService.java foreground service]
    AS --> AT[Load history dari ChatDAO.java]
    AT --> AU[DwipaApiClient.java.buildMessagesForRequest]
    AU --> AV[Request ke Chat Completion API]

    AV --> AW{Response model}
    AW -->|Final answer| AX[Simpan response bot]
    AW -->|Tool calls| AY[dispatchToolCalls]
    AY --> AZ{Jenis tool}
    AZ -->|web_search| BA[DwipaApiClient.java.webSearch]
    AZ -->|web_fetch| BB[DwipaApiClient.java.webFetch]
    AZ -->|image_generations| BC[DwipaApiClient.java.generateImage]

    BA --> BD[Masukkan hasil tool ke messages]
    BB --> BD
    BD --> AV
    BC --> BE[Simpan hasil gambar sebagai response]
    BE --> AX

    AX --> BF[ChatDAO.java.insertMessage role bot]
    BF --> BG[Broadcast ACTION_AI_RESPONSE_SAVED]
    BG --> BH[ChatActivity.java BroadcastReceiver]
    BH --> BI[Matikan thinking dan progress]
    BI --> BJ[loadChatHistory ulang]
    BJ --> BK[MessageAdapter render pesan bot]
```

## Flowchart Pengiriman Pesan

```mermaid
flowchart TD
    A[User klik tombol Send] --> B[ChatActivity.java.sendMessage]
    B --> C{hasInternetConnection?}
    C -->|Tidak| D[updateConnectionStatus false]
    D --> E[Toast tidak ada koneksi]
    E --> F[Stop]

    C -->|Ya| G{adapter.isThinking?}
    G -->|Ya| H[Toast tunggu jawaban AI]
    H --> F

    G -->|Tidak| I[Ambil text dari etMessage]
    I --> J{text kosong?}
    J -->|Ya| F
    J -->|Tidak| K[buildApiText]
    K --> L[Clear input]
    L --> M[Thread background]

    M --> N[Cek apakah pesan pertama]
    N --> O[ChatDAO.java.insertMessage user]
    O --> P{Pesan pertama?}
    P -->|Ya| Q[buildSessionTitle]
    Q --> R[ChatDAO.java.updateSessionTitle]
    P -->|Tidak| S[Load updated messages]
    R --> S

    S --> T[Ambil lastUserMsg]
    T --> U[runOnUiThread]
    U --> V[adapter.addMessage]
    V --> W[scrollToBottom]
    W --> X[clear retry/reply]
    X --> Y[showProcessingStatus Thinking]
    Y --> Z[startAiRequestService]
```

## Flowchart AI Service dan Tool Calling

```mermaid
flowchart TD
    A[AiRequestService.onStartCommand] --> B{Intent valid?}
    B -->|Tidak| C[stopSelf]
    B -->|Ya| D[Ambil sessionId, userMsgId, apiText]
    D --> E{sessionId valid?}
    E -->|Tidak| C
    E -->|Ya| F[startForeground notification]
    F --> G[notifyStatus Menganalisis permintaan]
    G --> H[processRequest]

    H --> I[ChatDAO.java.getMessagesBySession]
    I --> J[RequestLifecycle dibuat]
    J --> K[replaceActiveRequest untuk session sama]
    K --> L[setTimeout chat 90s]
    L --> M[DwipaApiClient.java.buildMessagesForRequest]
    M --> N[runToolLoop iteration 0]

    N --> O{iteration > max?}
    O -->|Ya| P[complete error terlalu banyak tool]
    O -->|Tidak| Q[DwipaApiClient.continueWithMessages]
    Q --> R{Callback result}

    R -->|onFinalAnswer| S[cleanBotResponse]
    S --> T[RequestLifecycle.complete]

    R -->|onToolCalls| U[dispatchToolCalls]
    U --> V[executeSingleTool paralel]
    V --> W{Tool name}
    W -->|web_search| X[apiClient.webSearch]
    W -->|web_fetch| Y[apiClient.webFetch]
    W -->|image_generations| Z[apiClient.generateImage]
    W -->|unknown| AA[Return tool tidak dikenal]

    X --> AB[onAllToolsDone]
    Y --> AB
    AA --> AB
    AB --> AC[Append assistant tool_call message]
    AC --> AD[Append tool results]
    AD --> AE[notifyStatus Menyusun jawaban]
    AE --> AF[Reset timeout]
    AF --> N

    Z --> AG{Image success?}
    AG -->|Ya| T
    AG -->|Tidak| AH[RequestLifecycle.fail]

    R -->|onFailure retryable| AH
    R -->|onFailure non-retryable| T

    T --> AI[persistLargeImageIfNeeded]
    AI --> AJ[ChatDAO.java.insertMessage role bot]
    AJ --> AK[notifyResponseSaved]
    AK --> AL[finishService]

    AH --> AM[notifyRequestFailed]
    AM --> AL
```

## Sequence Diagram: App Launch sampai Chat Screen

```mermaid
sequenceDiagram
    actor User
    participant Android as Android OS
    participant Splash as SplashActivity.java
    participant Main as MainActivity.java
    participant DAO as ChatDAO.java
    participant DB as SQLite Database
    participant Adapter as SessionAdapter.java
    participant Chat as ChatActivity.java

    User->>Android: Membuka aplikasi
    Android->>Splash: Launch MAIN/LAUNCHER activity
    Splash->>Splash: setContentView(activity_splash.xml)
    Splash->>Splash: Delay 2 detik
    Splash->>Main: startActivity(MainActivity.java)
    Splash->>Splash: finish()

    Main->>Main: setContentView(activity_main.xml)
    Main->>DAO: new ChatDAO()
    Main->>Adapter: new SessionAdapter(sessionList)
    Main->>Main: setup RecyclerView dan listener
    Main->>Main: onResume()
    Main->>DAO: getAllSessions()
    DAO->>DB: SELECT sessions ORDER BY updated_at DESC
    DB-->>DAO: List Session
    DAO-->>Main: sessions
    Main->>Adapter: setSessions(sessions)

    alt User klik Chat Baru
        User->>Main: Tap fabAdd
        Main->>DAO: insertSession("New Chat")
        DAO->>DB: INSERT INTO sessions
        DB-->>DAO: session_id baru
        DAO-->>Main: session_id
        Main->>Chat: startActivity(ChatActivity.java, session_id)
    else User klik session lama
        User->>Adapter: Tap item session
        Adapter-->>Main: onItemClick(session)
        Main->>Chat: startActivity(ChatActivity.java, session_id)
    end

    Chat->>Chat: Ambil extra session_id
    Chat->>DAO: new ChatDAO()
    Chat->>Chat: setup MessageAdapter dan RecyclerView
    Chat->>DAO: getMessagesBySession(session_id)
    DAO->>DB: SELECT messages WHERE session_id = ?
    DB-->>DAO: List Message
    DAO-->>Chat: messages
    Chat->>DAO: getSessionById(session_id)
    DAO->>DB: SELECT session WHERE id = ?
    DB-->>DAO: Session
    DAO-->>Chat: session title
    Chat->>Chat: Render riwayat chat
```

## Sequence Diagram: Kirim Pesan dan Terima Jawaban AI

```mermaid
sequenceDiagram
    actor User
    participant Chat as ChatActivity.java
    participant MsgAdapter as MessageAdapter.java
    participant DAO as ChatDAO.java
    participant DB as SQLite Database
    participant Service as AiRequestService.java
    participant Client as DwipaApiClient.java
    participant API as AI API

    User->>Chat: Ketik pesan dan tap Send
    Chat->>Chat: hasInternetConnection()
    Chat->>MsgAdapter: isThinking()
    Chat->>Chat: Ambil text dari etMessage
    Chat->>Chat: buildApiText(text)

    Chat->>DAO: getMessagesBySession(sessionId)
    DAO->>DB: SELECT messages
    DB-->>DAO: messages
    DAO-->>Chat: messages

    Chat->>DAO: insertMessage(sessionId, "user", text)
    DAO->>DB: INSERT INTO messages role=user
    DAO->>DB: UPDATE sessions.updated_at

    alt Pesan pertama dalam session
        Chat->>Chat: buildSessionTitle(text)
        Chat->>DAO: updateSessionTitle(sessionId, title)
        DAO->>DB: UPDATE sessions.title
    end

    Chat->>DAO: getMessagesBySession(sessionId)
    DAO->>DB: SELECT messages
    DB-->>DAO: updated messages
    DAO-->>Chat: updated messages
    Chat->>MsgAdapter: addMessage(lastUserMsg)
    Chat->>MsgAdapter: setThinking(true)
    Chat->>Service: startForegroundService(sessionId, apiText, userMessageId)

    Service->>Service: onStartCommand()
    Service->>Service: startForeground(notification)
    Service-->>Chat: Broadcast ACTION_AI_STATUS_CHANGED
    Chat->>MsgAdapter: update thinking status

    Service->>DAO: getMessagesBySession(sessionId)
    DAO->>DB: SELECT history messages
    DB-->>DAO: history
    DAO-->>Service: history

    Service->>Client: buildMessagesForRequest(history, userMsgId, apiText)
    Client-->>Service: request messages
    Service->>Client: continueWithMessages(messages)
    Client->>API: POST /v1/chat/completions
    API-->>Client: response

    alt Final answer
        Client-->>Service: onFinalAnswer(content)
        Service->>Service: cleanBotResponse(content)
        Service->>DAO: insertMessage(sessionId, "bot", response)
        DAO->>DB: INSERT INTO messages role=bot
        DAO->>DB: UPDATE sessions.updated_at
        Service-->>Chat: Broadcast ACTION_AI_RESPONSE_SAVED
        Chat->>MsgAdapter: setThinking(false)
        Chat->>Chat: hide progressBar
        Chat->>DAO: getMessagesBySession(sessionId)
        DAO->>DB: SELECT updated messages
        DB-->>DAO: messages including bot response
        DAO-->>Chat: messages
        Chat->>MsgAdapter: notifyDataSetChanged()
    else Retryable failure
        Client-->>Service: onFailure(error)
        Service-->>Chat: Broadcast ACTION_AI_REQUEST_FAILED
        Chat->>MsgAdapter: setThinking(false)
        Chat->>Chat: Toast error
        Chat->>MsgAdapter: setRetryMessageId(userMessageId)
    end
```

## Sequence Diagram: Tool Calling

```mermaid
sequenceDiagram
    participant Service as AiRequestService.java
    participant Client as DwipaApiClient.java
    participant API as Chat Completion API
    participant Search as Search API
    participant Fetch as Web Fetch API
    participant Image as Image Generation API
    participant DAO as ChatDAO.java
    participant DB as SQLite Database
    participant Chat as ChatActivity.java

    Service->>Client: continueWithMessages(messages)
    Client->>API: POST chat completions + tools
    API-->>Client: finish_reason = tool_calls
    Client-->>Service: onToolCalls(toolCalls)

    Service->>Service: dispatchToolCalls(toolCalls)

    par web_search
        Service->>Client: webSearch(query)
        Client->>Search: POST /v1/search
        Search-->>Client: search results
        Client-->>Service: formatted search result
    and web_fetch
        Service->>Client: webFetch(url)
        Client->>Fetch: POST /v1/web/fetch
        Fetch-->>Client: markdown/content
        Client-->>Service: formatted fetch result
    and image_generations
        Service->>Client: generateImage(prompt)
        Client->>Image: POST /v1/images/generations
        Image-->>Client: image url/base64
        Client-->>Service: image response
    end

    alt Tool search/fetch selesai
        Service->>Service: Append assistant tool call message
        Service->>Service: Append tool result messages
        Service-->>Chat: Broadcast status Menyusun jawaban
        Service->>Client: continueWithMessages(messages + tool results)
        Client->>API: POST chat completions lanjutan
        API-->>Client: final answer
        Client-->>Service: onFinalAnswer(content)
        Service->>DAO: insertMessage(sessionId, "bot", content)
        DAO->>DB: INSERT bot message
        Service-->>Chat: ACTION_AI_RESPONSE_SAVED
    else Image generation selesai
        Service->>Service: persistLargeImageIfNeeded(image response)
        Service->>DAO: insertMessage(sessionId, "bot", image reference)
        DAO->>DB: INSERT bot image message
        Service-->>Chat: ACTION_AI_RESPONSE_SAVED
    end
```

## Sequence Diagram: Rename dan Delete Session

```mermaid
sequenceDiagram
    actor User
    participant Main as MainActivity.java
    participant Adapter as SessionAdapter.java
    participant DAO as ChatDAO.java
    participant DB as SQLite Database

    Main->>Adapter: Tampilkan daftar session
    User->>Adapter: Klik menu / long press session
    Adapter-->>Main: onMenuClick(session)
    Main->>Main: showSessionMenu(session)

    alt Rename session
        User->>Main: Pilih Rename
        Main->>Main: showRenameDialog(session)
        User->>Main: Input title baru dan Save
        Main->>DAO: updateSessionTitle(sessionId, newTitle)
        DAO->>DB: UPDATE sessions SET title = ?
        Main->>DAO: getAllSessions()
        DAO->>DB: SELECT sessions ORDER BY updated_at DESC
        DB-->>DAO: sessions
        DAO-->>Main: sessions
        Main->>Adapter: setSessions(sessions)
    else Delete session
        User->>Main: Pilih Delete
        Main->>Main: showDeleteConfirmDialog(session)
        User->>Main: Konfirmasi Hapus
        Main->>DAO: deleteSession(sessionId)
        DAO->>DB: DELETE FROM sessions WHERE id = ?
        DB->>DB: ON DELETE CASCADE messages
        Main->>DAO: getAllSessions()
        DAO->>DB: SELECT sessions ORDER BY updated_at DESC
        DB-->>DAO: sessions
        DAO-->>Main: sessions
        Main->>Adapter: setSessions(sessions)
    end
```

## Ringkasan Fungsi per File dan Ekstensi

| File | Ekstensi | Jenis | Fungsi utama |
|---|---:|---|---|
| `AndroidManifest.xml` | `.xml` | Manifest Android | Menentukan permission, launcher activity, service, dan activity app |
| `activity_splash.xml` | `.xml` | Layout XML | Tampilan splash screen |
| `activity_main.xml` | `.xml` | Layout XML | Tampilan halaman daftar session |
| `activity_chat.xml` | `.xml` | Layout XML | Tampilan halaman percakapan chat |
| `item_session.xml` | `.xml` | Layout XML | Tampilan satu item session di RecyclerView |
| `item_message_user.xml` | `.xml` | Layout XML | Tampilan bubble pesan user |
| `item_message_bot.xml` | `.xml` | Layout XML | Tampilan bubble pesan bot |
| `item_message_thinking.xml` | `.xml` | Layout XML | Tampilan indikator AI sedang berpikir |
| `bottom_sheet_session_actions.xml` | `.xml` | Layout XML | Tampilan menu rename/delete session |
| `dialog_rename_session.xml` | `.xml` | Layout XML | Tampilan dialog rename session |
| `SplashActivity.java` | `.java` | Activity Java | Menampilkan splash screen lalu membuka `MainActivity` |
| `MainActivity.java` | `.java` | Activity Java | Menampilkan daftar session, membuat session baru, rename, delete |
| `ChatActivity.java` | `.java` | Activity Java | Menampilkan chat, mengirim pesan, menerima broadcast dari service |
| `SessionAdapter.java` | `.java` | RecyclerView Adapter Java | Render item session dan callback klik/menu session |
| `MessageAdapter.java` | `.java` | RecyclerView Adapter Java | Render pesan user, bot, thinking, image, code block, retry/reply |
| `AiRequestService.java` | `.java` | Foreground Service Java | Service untuk request AI, tool calling, dan simpan response |
| `DwipaApiClient.java` | `.java` | API Client Java | HTTP client untuk chat API, search, fetch, dan image generation |
| `ChatDAO.java` | `.java` | Data Access Object Java | Operasi database untuk sessions dan messages |
| `DatabaseHelper.java` | `.java` | SQLiteOpenHelper Java | Membuat dan mengatur schema SQLite |
| `Session.java` | `.java` | Model Java | Model data session |
| `Message.java` | `.java` | Model Java | Model data message |
| `build.gradle.kts` | `.kts` | Gradle Kotlin Script | Konfigurasi build module/root project |
| `settings.gradle.kts` | `.kts` | Gradle Kotlin Script | Konfigurasi nama project, repository, dan module `:app` |
| `libs.versions.toml` | `.toml` | Version Catalog | Daftar versi dependency dan plugin Gradle |

## Keterangan Ekstensi File

| Ekstensi | Digunakan untuk |
|---|---|
| `.java` | Source code Java: Activity, Service, Adapter, DAO, model, dan API client |
| `.xml` | Android Manifest, layout UI, drawable, theme, colors, strings, dan resource Android lainnya |
| `.kts` | Gradle Kotlin Script untuk konfigurasi build |
| `.toml` | Version catalog Gradle untuk dependency dan plugin |
| `.md` | Dokumentasi Markdown seperti file flow ini |

## Ringkasan Alur Akhir

```text
AndroidManifest.xml
→ SplashActivity.java + activity_splash.xml
→ MainActivity.java + activity_main.xml
→ SessionAdapter.java / ChatDAO.java / SQLite Database
→ ChatActivity.java + activity_chat.xml
→ MessageAdapter.java / ChatDAO.java / SQLite Database
→ AiRequestService.java
→ DwipaApiClient.java
→ API chat / tools / image
→ AiRequestService.java simpan response
→ Broadcast ke ChatActivity.java
→ ChatActivity.java reload history
→ MessageAdapter.java tampilkan jawaban
```
