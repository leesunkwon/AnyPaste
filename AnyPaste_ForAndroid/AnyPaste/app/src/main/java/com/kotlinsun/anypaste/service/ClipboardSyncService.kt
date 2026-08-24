package com.kotlinsun.anypaste.service

import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kotlinsun.anypaste.core.AppPreferences
import com.kotlinsun.anypaste.data.FirebaseAuthRepository
import com.kotlinsun.anypaste.data.FirebaseStorageRepository
import com.kotlinsun.anypaste.data.FirestoreClipboardRepository
import com.kotlinsun.anypaste.data.FirestoreDeviceRepository
import com.kotlinsun.anypaste.data.DeviceSessionRevokedException
import com.kotlinsun.anypaste.model.ClipboardItem
import com.kotlinsun.anypaste.model.ClipboardType
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * User-started foreground service that mirrors text clipboard changes through Firestore.
 *
 * Android 10+ prevents an unfocused app from reading another app's clipboard. A foreground service
 * does not bypass that privacy restriction. Therefore this listener uploads clipboard text whenever
 * Android grants access (normally while AnyPaste is visible), while the Firestore listener can keep
 * receiving remote items for the lifetime allowed to a data-sync foreground service.
 */
class ClipboardSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authRepository by lazy { FirebaseAuthRepository() }
    private val clipboardRepository by lazy { FirestoreClipboardRepository() }
    private val deviceRepository by lazy { FirestoreDeviceRepository() }
    private val storageRepository by lazy { FirebaseStorageRepository() }
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val notificationManager by lazy { AnyPasteNotificationManager(this) }
    private val appPreferences by lazy { AppPreferences(this) }
    private val syncPreferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val deviceId by lazy { getOrCreateDeviceId(this) }

    private var syncJob: Job? = null
    private var maintenanceJob: Job? = null
    private var heartbeatJob: Job? = null
    private var fingerprintJob: Job? = null
    private var currentUserId: String? = null
    private var syncStarted = false
    private var preserveTerminalState = false
    private var stopRequested = false
    private var lastHeartbeatElapsedRealtime = 0L

    private val dedupeLock = Any()
    private var lastObservedLocalFingerprint: String? = null
    private var pendingLocalClip: PendingLocalClip? = null
    private val localUploadsInFlight = LinkedHashSet<String>()
    private val localUploadJobs = LinkedHashMap<String, Job>()
    private val pendingRemoteFingerprints = LinkedHashMap<String, Long>()
    private val remoteLock = Any()
    private val pendingRemoteItems = LinkedHashMap<String, ClipboardItem>()
    private val pendingReadReceiptItemIds = LinkedHashMap<String, Int>()
    private val readReceiptsInFlight = LinkedHashSet<String>()
    private val remoteItemsInFlight = LinkedHashSet<String>()
    private val processedRemoteItemIds = LinkedHashSet<String>()
    private val notifiedRemoteItemIds = LinkedHashSet<String>()

    private val primaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleLocalClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appPreferences.autoSyncEnabled = false
            requestStop(markDeviceOffline = true)
            return START_NOT_STICKY
        }
        if ((intent == null || intent.action == ACTION_START) &&
            !appPreferences.autoSyncEnabled
        ) {
            requestStop(markDeviceOffline = false)
            return START_NOT_STICKY
        }

        startSync()
        if (intent?.action == ACTION_CAPTURE_NOW) {
            handleLocalClipboardChanged()
            serviceScope.launch { retryPendingRemoteItems() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (syncStarted) {
            clipboardManager.removePrimaryClipChangedListener(primaryClipChangedListener)
        }
        syncJob?.cancel()
        maintenanceJob?.cancel()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        currentUserId = null
        syncStarted = false
        stopRequested = true
        running.set(false)
        if (!preserveTerminalState) {
            setState(ClipboardSyncState(ClipboardSyncPhase.STOPPED))
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        preserveTerminalState = true
        appPreferences.autoSyncEnabled = false
        appPreferences.backgroundSyncNotice =
            "Android의 백그라운드 동기화 시간 제한으로 중지되었습니다. 앱을 열어 다시 켜 주세요."
        setState(
            ClipboardSyncState(
                phase = ClipboardSyncPhase.STOPPED,
                message = "Android의 백그라운드 동기화 시간 제한으로 중지되었습니다.",
            ),
        )
        requestStop(markDeviceOffline = false)
    }

    private fun startSync() {
        if (syncStarted) return
        stopRequested = false
        preserveTerminalState = false
        appPreferences.backgroundSyncNotice = ""
        syncStarted = true
        running.set(true)
        setState(
            ClipboardSyncState(
                phase = ClipboardSyncPhase.SYNCING,
                message = "계정 연결을 확인하고 있습니다",
            ),
        )

        ServiceCompat.startForeground(
            this,
            AnyPasteNotificationManager.NOTIFICATION_ID_SYNC,
            notificationManager.buildSyncNotification("계정 연결을 확인하고 있습니다"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        clipboardManager.addPrimaryClipChangedListener(primaryClipChangedListener)
        observeRemoteClipboard()
        startMaintenance()
    }

    private fun requestStop(markDeviceOffline: Boolean) {
        if (stopRequested) return
        stopRequested = true
        val userId = currentUserId
        syncJob?.cancel()
        syncJob = null
        maintenanceJob?.cancel()
        maintenanceJob = null
        currentUserId = null
        clearUserScopedTracking()

        if (markDeviceOffline && userId != null) {
            serviceScope.launch {
                withTimeoutOrNull(OFFLINE_UPDATE_TIMEOUT_MILLIS) {
                    try {
                        deviceRepository.markOffline(userId, deviceId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A stale heartbeat still makes the device offline after the UI timeout.
                    }
                }
                finishStop()
            }
        } else {
            finishStop()
        }
    }

    private fun finishStop() {
        running.set(false)
        if (syncStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notificationManager.cancelSyncNotification()
        }
        stopSelf()
    }

    private fun observeRemoteClipboard() {
        syncJob = serviceScope.launch {
            authRepository.authState
                .distinctUntilChangedBy { it?.uid }
                .collectLatest { user ->
                    val userId = user?.uid
                    if (currentUserId != userId) {
                        clearUserScopedTracking()
                    }
                    currentUserId = userId
                    if (userId == null) {
                        updateStatus(
                            phase = ClipboardSyncPhase.WAITING_FOR_AUTH,
                            message = "로그인하면 클립보드 동기화를 시작합니다",
                        )
                        return@collectLatest
                    }
                    synchronized(dedupeLock) {
                        lastObservedLocalFingerprint = lastHandledAutoFingerprint(userId)
                    }

                    updateStatus(
                        phase = ClipboardSyncPhase.SYNCING,
                        message = "다른 기기의 클립보드를 기다리는 중입니다",
                    )
                    // Do not delay the Firestore listener while an offline heartbeat times out.
                    scheduleHeartbeat(userId)
                    // Captures content copied while Android had denied background clipboard access.
                    handleLocalClipboardChanged()

                    clipboardRepository.observeClipboard(userId)
                        .retryWhen { cause, attempt ->
                            if (cause is CancellationException) return@retryWhen false
                            updateStatus(
                                phase = ClipboardSyncPhase.ERROR,
                                message = "연결이 끊겨 다시 시도하고 있습니다",
                            )
                            delay(retryDelayMillis(attempt))
                            true
                        }
                        .collect { items ->
                            updateStatus(
                                phase = ClipboardSyncPhase.SYNCING,
                                message = "다른 기기의 클립보드를 기다리는 중입니다",
                            )
                            // Repository results are newest-first. Oldest-first processing leaves
                            // the newest received text in the Android clipboard.
                            items.asReversed().forEach { item ->
                                handleRemoteItem(userId, item)
                            }
                        }
                }
        }
    }

    private fun handleLocalClipboardChanged() {
        fingerprintJob?.cancel()
        val userId = currentUserId ?: return
        val clipSnapshot = readLocalClip() ?: return
        val job = serviceScope.launch {
            try {
                val fingerprint = clipSnapshot.automaticItemId()
                if (currentUserId == userId) {
                    enqueueLocalClip(
                        userId = userId,
                        clipSnapshot = clipSnapshot,
                        fingerprint = fingerprint,
                        checkClipboardLoop = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateStatus(
                    phase = ClipboardSyncPhase.ERROR,
                    message = "클립보드 내용을 확인하지 못했습니다",
                )
            }
        }
        fingerprintJob = job
        job.invokeOnCompletion {
            if (fingerprintJob === job) fingerprintJob = null
        }
    }

    private fun enqueueLocalClip(
        userId: String,
        clipSnapshot: LocalClip,
        fingerprint: String,
        checkClipboardLoop: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val waitingForWifi = clipSnapshot is LocalClip.Image && !canTransferBinaryNow()
        var queuedBehindUpload = false

        synchronized(dedupeLock) {
            if (checkClipboardLoop) {
                if (pendingLocalClip?.fingerprint != fingerprint) {
                    pendingLocalClip = null
                }
                pendingRemoteFingerprints.entries.removeAll { (_, createdAt) ->
                    now - createdAt > REMOTE_LOOP_GUARD_MILLIS
                }
                if (fingerprint in pendingRemoteFingerprints) {
                    // Remember the value beyond the short remote callback guard. Otherwise the
                    // maintenance read would upload the unchanged remote clipboard a minute later.
                    lastObservedLocalFingerprint = fingerprint
                    return
                }
                // The clipboard listener can fire more than once and maintenance also samples it.
                // A stable value is ignored for the whole service session. Copying another value
                // changes this marker; the repository still prevents a duplicate document if an
                // older payload is encountered again.
                if (lastObservedLocalFingerprint == fingerprint) return
                lastObservedLocalFingerprint = fingerprint
            }
            if (fingerprint in localUploadsInFlight) return
            if (localUploadsInFlight.isNotEmpty()) {
                pendingLocalClip = PendingLocalClip(
                    userId = userId,
                    clip = clipSnapshot,
                    fingerprint = fingerprint,
                    failureCount = 0,
                )
                queuedBehindUpload = true
                return@synchronized
            }

            if (waitingForWifi) {
                val previousFailures = pendingLocalClip
                    ?.takeIf { it.fingerprint == fingerprint }
                    ?.failureCount
                    ?: 0
                pendingLocalClip = PendingLocalClip(
                    userId = userId,
                    clip = clipSnapshot,
                    fingerprint = fingerprint,
                    failureCount = previousFailures,
                )
                return@synchronized
            }

            localUploadsInFlight.add(fingerprint)
        }

        if (queuedBehindUpload) {
            updateStatus(
                phase = ClipboardSyncPhase.SYNCING,
                message = "이전 클립보드 전송 후 최신 항목을 동기화합니다",
            )
            return
        }
        if (waitingForWifi) {
            updateStatus(
                phase = ClipboardSyncPhase.SYNCING,
                message = "Wi-Fi 연결 후 이미지를 동기화합니다",
            )
            return
        }

        val uploadJob = serviceScope.launch {
            try {
                when (clipSnapshot) {
                    is LocalClip.Text -> clipboardRepository.createAutomaticText(
                        userId = userId,
                        itemId = fingerprint,
                        content = clipSnapshot.value,
                        sourceDeviceId = deviceId,
                    )

                    is LocalClip.Image -> uploadLocalImage(
                        userId = userId,
                        itemId = fingerprint,
                        image = clipSnapshot,
                    )
                }
                rememberLastHandledAutoFingerprint(userId, fingerprint)
                synchronized(dedupeLock) {
                    if (pendingLocalClip?.fingerprint == fingerprint) {
                        pendingLocalClip = null
                    }
                }
                updateStatus(
                    phase = ClipboardSyncPhase.SYNCING,
                    message = "클립보드를 동기화했습니다",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AutomaticPayloadConflictException) {
                rememberLastHandledAutoFingerprint(userId, fingerprint)
                synchronized(dedupeLock) {
                    if (pendingLocalClip?.fingerprint == fingerprint) {
                        pendingLocalClip = null
                    }
                }
                updateStatus(
                    phase = ClipboardSyncPhase.ERROR,
                    message = error.message ?: "자동 이미지 데이터가 일치하지 않아 전송을 중지했습니다",
                )
            } catch (_: Exception) {
                var willRetry = false
                synchronized(dedupeLock) {
                    val queuedSuccessor = pendingLocalClip
                        ?.takeIf { it.fingerprint != fingerprint }
                    if (queuedSuccessor != null) {
                        willRetry = true
                    } else {
                        val failureCount = (pendingLocalClip
                            ?.takeIf { it.fingerprint == fingerprint }
                            ?.failureCount ?: 0) + 1
                        if (failureCount <= MAX_LOCAL_RETRY_ATTEMPTS) {
                            pendingLocalClip = PendingLocalClip(
                                userId = userId,
                                clip = clipSnapshot,
                                fingerprint = fingerprint,
                                failureCount = failureCount,
                            )
                            willRetry = true
                        } else if (pendingLocalClip?.fingerprint == fingerprint) {
                            pendingLocalClip = null
                        }
                    }
                }
                updateStatus(
                    phase = ClipboardSyncPhase.ERROR,
                    message = if (willRetry) {
                        "클립보드를 전송하지 못해 다시 시도합니다"
                    } else {
                        "클립보드를 반복해서 전송하지 못했습니다"
                    },
                )
            } finally {
                val hasQueuedSuccessor = synchronized(dedupeLock) {
                    localUploadsInFlight.remove(fingerprint)
                    pendingLocalClip?.fingerprint?.let { it != fingerprint } ?: false
                }
                if (hasQueuedSuccessor && currentUserId == userId) {
                    retryPendingLocalClip()
                }
            }
        }
        synchronized(dedupeLock) {
            localUploadJobs[fingerprint] = uploadJob
        }
        uploadJob.invokeOnCompletion {
            synchronized(dedupeLock) {
                if (localUploadJobs[fingerprint] === uploadJob) {
                    localUploadJobs.remove(fingerprint)
                }
            }
        }
    }

    private fun readLocalClip(): LocalClip? {
        return try {
            val description = clipboardManager.primaryClipDescription ?: return null
            if (description.label?.toString() == REMOTE_CLIP_LABEL) return null
            if (description.isMarkedSensitive()) return null
            val clip = clipboardManager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val item = clip.getItemAt(0)

            when {
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> {
                    val text = (item.text ?: item.htmlText)?.toString() ?: return null
                    if (text.isEmpty() || text.length > MAX_SYNC_TEXT_LENGTH) return null
                    LocalClip.Text(text)
                }

                description.hasMimeType(IMAGE_MIME_PATTERN) -> {
                    val uri = item.uri ?: return null
                    val metadata = queryImageMetadata(uri, description) ?: return null
                    if (metadata.sizeBytes !in 1L..MAX_SYNC_BINARY_BYTES) return null
                    LocalClip.Image(
                        uri = uri,
                        mimeType = metadata.mimeType,
                    )
                }

                else -> null
            }
        } catch (_: SecurityException) {
            // Expected when Android 10+ denies clipboard access while AnyPaste is not focused.
            null
        } catch (_: RuntimeException) {
            // A clipboard owner can disappear while its ClipData is being read.
            null
        }
    }

    private suspend fun uploadLocalImage(
        userId: String,
        itemId: String,
        image: LocalClip.Image,
    ) {
        check(canTransferBinaryNow()) { "Wi-Fi 연결 후 이미지를 전송할 수 있습니다." }
        val existing = clipboardRepository.getItem(userId, itemId)
        if (existing != null) {
            if (existing.type != ClipboardType.IMAGE.value ||
                !existing.storagePath.startsWith("users/$userId/clipboard/$itemId/")
            ) {
                throw AutomaticPayloadConflictException(
                    "자동 전송 문서 ID가 다른 이미지에 이미 사용 중입니다",
                )
            }
            verifyAutomaticImagePayload(itemId, existing.storagePath)
            return
        }
        val upload = try {
            storageRepository.uploadFile(
                userId = userId,
                itemId = itemId,
                source = image.uri,
                fileName = AUTO_IMAGE_STORAGE_FILE_NAME,
                mimeType = image.mimeType,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (uploadError: Exception) {
            // Storage rules allow create but not overwrite. If the first upload committed and its
            // response was lost, recover that object instead of attempting another object create.
            storageRepository.findUploadedFile(
                userId = userId,
                itemId = itemId,
                fileName = AUTO_IMAGE_STORAGE_FILE_NAME,
            ) ?: throw uploadError
        }
        verifyAutomaticImagePayload(itemId, upload.storagePath)
        try {
            clipboardRepository.createAutomaticBinary(
                userId = userId,
                itemId = itemId,
                type = ClipboardType.IMAGE,
                upload = upload,
                sourceDeviceId = deviceId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (createError: Exception) {
            val committed = clipboardRepository.getItem(userId, itemId) ?: throw createError
            if (committed.type != ClipboardType.IMAGE.value) {
                throw AutomaticPayloadConflictException(
                    "자동 전송 문서 ID가 다른 항목에 이미 사용 중입니다",
                )
            }
            verifyAutomaticImagePayload(itemId, committed.storagePath)
            if (committed.storagePath != upload.storagePath) {
                try {
                    storageRepository.delete(upload.storagePath)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The orphan is bounded to this deterministic item ID and lifecycle cleanup.
                }
            }
        }
    }

    private suspend fun verifyAutomaticImagePayload(itemId: String, storagePath: String) {
        val actualItemId = withContext(Dispatchers.IO) {
            val bytes = storageRepository.downloadBytes(
                storagePath = storagePath,
                maxDownloadSizeBytes = MAX_SYNC_BINARY_BYTES,
            )
            automaticImageItemId(bytes)
        }
        if (actualItemId != itemId) {
            throw AutomaticPayloadConflictException(
                "저장된 이미지가 원본과 일치하지 않아 자동 전송을 중지했습니다",
            )
        }
    }

    private fun queryImageMetadata(
        uri: Uri,
        description: ClipDescription,
    ): LocalImageMetadata? {
        val contentResolver = applicationContext.contentResolver
        val mimeType = contentResolver.getType(uri)
            ?: (0 until description.mimeTypeCount)
                .map(description::getMimeType)
                .firstOrNull { it.startsWith(IMAGE_MIME_PREFIX) }
            ?: return null

        var sizeBytes = 0L
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            sizeBytes = cursor.longValue(OpenableColumns.SIZE) ?: 0L
        }
        if (sizeBytes <= 0L) {
            sizeBytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.coerceAtLeast(0L)
            } ?: 0L
        }

        return LocalImageMetadata(
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        )
    }

    private fun Cursor.longValue(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && moveToFirst() && !isNull(index)) getLong(index) else null
    }

    private suspend fun handleRemoteItem(userId: String, item: ClipboardItem) {
        if (item.id.isBlank() ||
            item.sourceDeviceId == deviceId ||
            (item.targetDeviceId.isNotBlank() && item.targetDeviceId != deviceId) ||
            item.isReceivedBy(deviceId)
        ) {
            clearRemoteItemState(item.id)
            return
        }
        if (item.isExpired()) {
            clearRemoteItemState(item.id)
            return
        }
        synchronized(remoteLock) {
            if (item.id in processedRemoteItemIds || !remoteItemsInFlight.add(item.id)) return
        }

        try {
            val result = when (item.type.lowercase()) {
                AnyPasteNotificationManager.TYPE_TEXT -> handleRemoteText(item)
                AnyPasteNotificationManager.TYPE_IMAGE -> receiveRemoteImage(item)
                AnyPasteNotificationManager.TYPE_FILE -> {
                    notifyFileReceivedOnce(item)
                    RemoteItemResult.HANDLED
                }

                else -> RemoteItemResult.DISCARD
            }

            if (result == RemoteItemResult.RETRY_LATER) {
                rememberPendingRemoteItem(item)
                return
            }

            synchronized(remoteLock) {
                pendingRemoteItems.remove(item.id)
                processedRemoteItemIds.add(item.id)
                trimProcessedItems()
            }
            acknowledgeRemoteItem(userId, item.id)
        } finally {
            synchronized(remoteLock) {
                remoteItemsInFlight.remove(item.id)
            }
        }
    }

    private fun handleRemoteText(item: ClipboardItem): RemoteItemResult {
        val content = item.content
        if (content.isEmpty() || content.length > MAX_SYNC_TEXT_LENGTH) {
            return RemoteItemResult.DISCARD
        }
        notifyTextReceivedOnce(item)
        return if (applyRemoteText(content)) {
            RemoteItemResult.HANDLED
        } else {
            RemoteItemResult.RETRY_LATER
        }
    }

    private suspend fun receiveRemoteImage(item: ClipboardItem): RemoteItemResult {
        if (item.storagePath.isBlank() ||
            item.fileSize !in 1L..MAX_SYNC_BINARY_BYTES
        ) {
            return RemoteItemResult.DISCARD
        }
        notifyImageReceivedOnce(item)
        if (!canTransferBinaryNow()) {
            updateStatus(
                phase = ClipboardSyncPhase.SYNCING,
                message = "Wi-Fi 연결 후 받은 이미지를 동기화합니다",
            )
            return RemoteItemResult.RETRY_LATER
        }

        updateStatus(
            phase = ClipboardSyncPhase.SYNCING,
            message = "받은 이미지를 내려받고 있습니다",
        )
        var destination: File? = null
        return try {
            val receivedFile = receivedImageFile(item)
            destination = receivedFile
            storageRepository.downloadFile(item.storagePath, receivedFile)
            applyRemoteImage(receivedFile)
            updateStatus(
                phase = ClipboardSyncPhase.SYNCING,
                message = "받은 이미지를 클립보드에 복사했습니다",
            )
            RemoteItemResult.HANDLED
        } catch (error: CancellationException) {
            runCatching { destination?.delete() }
            throw error
        } catch (_: Exception) {
            runCatching { destination?.delete() }
            updateStatus(
                phase = ClipboardSyncPhase.ERROR,
                message = "받은 이미지를 자동으로 복사하지 못했습니다",
            )
            RemoteItemResult.RETRY_LATER
        }
    }

    private suspend fun applyRemoteImage(file: File) {
        val contentUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val fingerprint = withContext(Dispatchers.IO) {
            file.inputStream().use(::automaticImageItemId)
        }

        synchronized(dedupeLock) {
            registerPendingRemoteFingerprint(fingerprint)
        }
        try {
            clipboardManager.setPrimaryClip(
                ClipData.newUri(applicationContext.contentResolver, REMOTE_CLIP_LABEL, contentUri),
            )
            synchronized(dedupeLock) {
                lastObservedLocalFingerprint = fingerprint
            }
        } catch (error: RuntimeException) {
            synchronized(dedupeLock) {
                pendingRemoteFingerprints.remove(fingerprint)
            }
            throw error
        }
    }

    private fun receivedImageFile(item: ClipboardItem): File {
        val directory = File(cacheDir, RECEIVED_CACHE_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "수신 이미지 캐시 폴더를 만들 수 없습니다."
        }
        val normalizedItemId = item.id.replace(UNSAFE_FILE_NAME, "_").take(MAX_CACHE_ID_LENGTH)
        var normalizedName = item.fileName
            .replace(UNSAFE_FILE_NAME, "_")
            .trim('_', ' ', '.')
            .take(MAX_CACHE_FILE_NAME_LENGTH)
            .ifBlank { "image" }
        if ('.' !in normalizedName) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType)
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_IMAGE_EXTENSION
            normalizedName += ".$extension"
        }
        return File(directory, "${normalizedItemId}_$normalizedName")
    }

    private fun canTransferBinaryNow(): Boolean {
        if (!appPreferences.wifiOnlyFiles) return true
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        val allowedTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return allowedTransport &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun applyRemoteText(text: String): Boolean {
        val fingerprint = automaticTextItemId(text)
        synchronized(dedupeLock) {
            registerPendingRemoteFingerprint(fingerprint)
        }
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(REMOTE_CLIP_LABEL, text))
            synchronized(dedupeLock) {
                lastObservedLocalFingerprint = fingerprint
            }
            return true
        } catch (_: RuntimeException) {
            synchronized(dedupeLock) {
                pendingRemoteFingerprints.remove(fingerprint)
            }
            updateStatus(
                phase = ClipboardSyncPhase.ERROR,
                message = "받은 텍스트를 클립보드에 복사하지 못했습니다",
            )
            return false
        }
    }

    private fun ClipDescription.isMarkedSensitive(): Boolean {
        val clipExtras = extras ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clipExtras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
        } else {
            clipExtras.getBoolean(EXTRA_IS_SENSITIVE_COMPAT, false)
        }
    }

    private fun trimProcessedItems() {
        while (processedRemoteItemIds.size > MAX_PROCESSED_ITEM_IDS) {
            val removable = processedRemoteItemIds.firstOrNull { itemId ->
                itemId !in pendingReadReceiptItemIds
            } ?: return
            processedRemoteItemIds.remove(removable)
        }
    }

    private fun registerPendingRemoteFingerprint(fingerprint: String) {
        pendingRemoteFingerprints[fingerprint] = SystemClock.elapsedRealtime()
        while (pendingRemoteFingerprints.size > MAX_PENDING_REMOTE_FINGERPRINTS) {
            val oldest = pendingRemoteFingerprints.iterator()
            if (!oldest.hasNext()) return
            oldest.next()
            oldest.remove()
        }
    }

    private fun rememberPendingRemoteItem(item: ClipboardItem) {
        synchronized(remoteLock) {
            pendingRemoteItems[item.id] = item
            while (pendingRemoteItems.size > MAX_PENDING_REMOTE_ITEMS) {
                val oldest = pendingRemoteItems.iterator()
                if (!oldest.hasNext()) return
                oldest.next()
                oldest.remove()
            }
        }
    }

    private suspend fun retryPendingRemoteItems() {
        val userId = currentUserId ?: return
        val pendingItems = synchronized(remoteLock) { pendingRemoteItems.values.toList() }
        pendingItems.forEach { item ->
            if (item.isExpired()) {
                clearRemoteItemState(item.id)
                return@forEach
            }
            handleRemoteItem(userId, item)
        }
    }

    private suspend fun acknowledgeRemoteItem(userId: String, itemId: String) {
        synchronized(remoteLock) {
            if (!readReceiptsInFlight.add(itemId)) return
        }
        var restoredConnection = false
        try {
            clipboardRepository.markAsReceived(userId, itemId, deviceId)
            synchronized(remoteLock) {
                restoredConnection = pendingReadReceiptItemIds.remove(itemId) != null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val willRetry = synchronized(remoteLock) {
                val failureCount = (pendingReadReceiptItemIds[itemId] ?: 0) + 1
                if (failureCount <= MAX_READ_RECEIPT_RETRY_ATTEMPTS) {
                    pendingReadReceiptItemIds[itemId] = failureCount
                    true
                } else {
                    pendingReadReceiptItemIds.remove(itemId)
                    false
                }
            }
            updateStatus(
                phase = ClipboardSyncPhase.ERROR,
                message = if (willRetry) {
                    "수신 상태를 저장하지 못해 다시 시도합니다"
                } else {
                    "수신 상태를 반복해서 저장하지 못했습니다"
                },
            )
        } finally {
            synchronized(remoteLock) {
                readReceiptsInFlight.remove(itemId)
            }
        }
        if (restoredConnection && state.value.phase == ClipboardSyncPhase.ERROR) {
            updateStatus(
                phase = ClipboardSyncPhase.SYNCING,
                message = "다른 기기의 클립보드를 기다리는 중입니다",
            )
        }
    }

    private suspend fun retryPendingReadReceipts() {
        val userId = currentUserId ?: return
        val itemIds = synchronized(remoteLock) { pendingReadReceiptItemIds.keys.toList() }
        itemIds.forEach { itemId -> acknowledgeRemoteItem(userId, itemId) }
    }

    private fun retryPendingLocalClip() {
        val pending = synchronized(dedupeLock) { pendingLocalClip } ?: return
        if (pending.userId != currentUserId) {
            synchronized(dedupeLock) {
                if (pendingLocalClip === pending) pendingLocalClip = null
            }
            return
        }
        enqueueLocalClip(
            userId = pending.userId,
            clipSnapshot = pending.clip,
            fingerprint = pending.fingerprint,
            checkClipboardLoop = false,
        )
    }

    private fun notifyTextReceivedOnce(item: ClipboardItem) {
        if (!reserveRemoteNotification(item.id)) return
        notificationManager.notifyTextReceived(
            itemId = item.id,
            text = item.content,
            sourceDeviceId = item.sourceDeviceId,
        )
    }

    private fun notifyImageReceivedOnce(item: ClipboardItem) {
        if (!reserveRemoteNotification(item.id)) return
        notificationManager.notifyImageReceived(
            itemId = item.id,
            fileName = item.fileName,
            sourceDeviceId = item.sourceDeviceId,
        )
    }

    private fun notifyFileReceivedOnce(item: ClipboardItem) {
        if (!reserveRemoteNotification(item.id)) return
        notificationManager.notifyFileReceived(
            itemId = item.id,
            fileName = item.fileName,
            sourceDeviceId = item.sourceDeviceId,
        )
    }

    private fun reserveRemoteNotification(itemId: String): Boolean = synchronized(remoteLock) {
        if (!notifiedRemoteItemIds.add(itemId)) return@synchronized false
        while (notifiedRemoteItemIds.size > MAX_NOTIFIED_REMOTE_ITEMS) {
            val oldest = notifiedRemoteItemIds.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        true
    }

    private fun clearRemoteItemState(itemId: String) {
        synchronized(remoteLock) {
            pendingRemoteItems.remove(itemId)
            pendingReadReceiptItemIds.remove(itemId)
            processedRemoteItemIds.remove(itemId)
            notifiedRemoteItemIds.remove(itemId)
        }
    }

    private fun clearUserScopedTracking() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        fingerprintJob?.cancel()
        fingerprintJob = null
        lastHeartbeatElapsedRealtime = 0L
        val localJobs = synchronized(dedupeLock) {
            val jobs = localUploadJobs.values.toList()
            localUploadJobs.clear()
            lastObservedLocalFingerprint = null
            pendingLocalClip = null
            localUploadsInFlight.clear()
            pendingRemoteFingerprints.clear()
            jobs
        }
        localJobs.forEach { it.cancel() }
        synchronized(remoteLock) {
            pendingRemoteItems.clear()
            pendingReadReceiptItemIds.clear()
            readReceiptsInFlight.clear()
            remoteItemsInFlight.clear()
            processedRemoteItemIds.clear()
            notifiedRemoteItemIds.clear()
        }
    }

    private suspend fun heartbeat(userId: String) {
        val startedAt = SystemClock.elapsedRealtime()
        if (startedAt - lastHeartbeatElapsedRealtime < MAINTENANCE_INTERVAL_MILLIS) return
        lastHeartbeatElapsedRealtime = startedAt
        val succeeded = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MILLIS) {
            try {
                deviceRepository.heartbeat(userId, deviceId)
                true
            } catch (_: DeviceSessionRevokedException) {
                terminateRevokedDeviceSession()
                false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        } ?: false
        if (!succeeded) {
            if (lastHeartbeatElapsedRealtime == startedAt) {
                lastHeartbeatElapsedRealtime = 0L
            }
            // Retried by the maintenance loop; clipboard sync can continue independently.
        }
    }

    private fun terminateRevokedDeviceSession() {
        appPreferences.autoSyncEnabled = false
        preserveTerminalState = true
        setState(
            ClipboardSyncState(
                phase = ClipboardSyncPhase.STOPPED,
                message = "이 기기의 연결이 해제되어 동기화를 중지했습니다.",
            ),
        )
        authRepository.signOut()
        requestStop(markDeviceOffline = false)
    }

    private fun scheduleHeartbeat(userId: String) {
        heartbeatJob?.cancel()
        val job = serviceScope.launch { heartbeat(userId) }
        heartbeatJob = job
        job.invokeOnCompletion {
            if (heartbeatJob === job) heartbeatJob = null
        }
    }

    private fun startMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = serviceScope.launch {
            while (isActive) {
                currentUserId?.let { userId -> heartbeat(userId) }
                retryPendingReadReceipts()
                retryPendingRemoteItems()
                retryPendingLocalClip()
                handleLocalClipboardChanged()
                delay(MAINTENANCE_INTERVAL_MILLIS)
            }
        }
    }

    private fun retryDelayMillis(attempt: Long): Long {
        val exponent = attempt.coerceAtMost(MAX_RETRY_EXPONENT.toLong()).toInt()
        return (INITIAL_RETRY_MILLIS shl exponent).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private fun updateStatus(phase: ClipboardSyncPhase, message: String) {
        setState(ClipboardSyncState(phase = phase, message = message))
        notificationManager.updateSyncNotification(message)
    }

    private suspend fun LocalClip.automaticItemId(): String = when (this) {
        is LocalClip.Text -> automaticTextItemId(value)
        is LocalClip.Image -> withContext(Dispatchers.IO) {
            val input = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("클립보드 이미지를 읽을 수 없습니다.")
            input.use(::automaticImageItemId)
        }
    }

    private fun automaticTextItemId(text: String): String =
        "text\u0000$text".toByteArray(StandardCharsets.UTF_8).sha256AutomaticItemId()

    private fun automaticImageItemId(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(IMAGE_FINGERPRINT_PREFIX)
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            require(totalBytes <= MAX_SYNC_BINARY_BYTES) {
                "이미지는 ${MAX_SYNC_BINARY_BYTES / 1024L / 1024L}MB 이하여야 합니다."
            }
            digest.update(buffer, 0, count)
        }
        require(totalBytes > 0L) { "빈 이미지는 전송할 수 없습니다." }
        digest.update(IMAGE_FINGERPRINT_SUFFIX)
        return digest.digest().toAutomaticItemId()
    }

    private fun automaticImageItemId(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "빈 이미지는 전송할 수 없습니다." }
        require(bytes.size.toLong() <= MAX_SYNC_BINARY_BYTES) {
            "이미지는 ${MAX_SYNC_BINARY_BYTES / 1024L / 1024L}MB 이하여야 합니다."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(IMAGE_FINGERPRINT_PREFIX)
        digest.update(bytes)
        digest.update(IMAGE_FINGERPRINT_SUFFIX)
        return digest.digest().toAutomaticItemId()
    }

    private fun ByteArray.sha256AutomaticItemId(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toAutomaticItemId()

    private fun ByteArray.toAutomaticItemId(): String =
        AUTO_ITEM_ID_PREFIX + toLowercaseHex().take(AUTO_ITEM_ID_HASH_LENGTH)

    private fun ByteArray.toLowercaseHex(): String {
        return buildString(size * 2) {
            this@toLowercaseHex.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(LOWERCASE_HEX_DIGITS[unsigned ushr 4])
                append(LOWERCASE_HEX_DIGITS[unsigned and 0x0f])
            }
        }
    }

    private fun lastHandledAutoFingerprint(userId: String): String? =
        syncPreferences.getString(lastAutoFingerprintKey(userId), null)

    private fun rememberLastHandledAutoFingerprint(userId: String, fingerprint: String) {
        syncPreferences.edit()
            .putString(lastAutoFingerprintKey(userId), fingerprint)
            .apply()
    }

    private fun lastAutoFingerprintKey(userId: String): String =
        "$KEY_LAST_AUTO_FINGERPRINT_PREFIX$userId"

    private sealed interface LocalClip {
        data class Text(val value: String) : LocalClip

        data class Image(
            val uri: Uri,
            val mimeType: String,
        ) : LocalClip
    }

    private data class LocalImageMetadata(
        val mimeType: String,
        val sizeBytes: Long,
    )

    private data class PendingLocalClip(
        val userId: String,
        val clip: LocalClip,
        val fingerprint: String,
        val failureCount: Int,
    )

    private class AutomaticPayloadConflictException(message: String) :
        IllegalStateException(message)

    private enum class RemoteItemResult {
        HANDLED,
        RETRY_LATER,
        DISCARD,
    }

    companion object {
        const val ACTION_START = "com.kotlinsun.anypaste.action.START_CLIPBOARD_SYNC"
        const val ACTION_STOP = "com.kotlinsun.anypaste.action.STOP_CLIPBOARD_SYNC"
        const val ACTION_CAPTURE_NOW = "com.kotlinsun.anypaste.action.CAPTURE_CLIPBOARD_NOW"

        private const val PREFERENCES_NAME = "anypaste_sync"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_AUTO_FINGERPRINT_PREFIX = "last_auto_fingerprint."
        private const val AUTO_ITEM_ID_PREFIX = "auto-"
        private const val AUTO_ITEM_ID_HASH_LENGTH = 32
        private const val LOWERCASE_HEX_DIGITS = "0123456789abcdef"
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private const val AUTO_IMAGE_STORAGE_FILE_NAME = "clipboard_image"
        private const val REMOTE_CLIP_LABEL = "AnyPaste"
        private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
        private const val IMAGE_MIME_PATTERN = "image/*"
        private const val IMAGE_MIME_PREFIX = "image/"
        private const val DEFAULT_IMAGE_EXTENSION = "png"
        private const val RECEIVED_CACHE_DIRECTORY = "received"
        private const val MAX_CACHE_ID_LENGTH = 80
        private const val MAX_CACHE_FILE_NAME_LENGTH = 120
        private const val MAX_SYNC_TEXT_LENGTH = 100_000
        private const val MAX_SYNC_BINARY_BYTES = 50L * 1024L * 1024L
        private const val MAX_PROCESSED_ITEM_IDS = 500
        private const val MAX_PENDING_REMOTE_FINGERPRINTS = 50
        private const val MAX_PENDING_REMOTE_ITEMS = 100
        private const val MAX_NOTIFIED_REMOTE_ITEMS = 500
        private const val MAX_LOCAL_RETRY_ATTEMPTS = 5
        private const val MAX_READ_RECEIPT_RETRY_ATTEMPTS = 5
        private const val REMOTE_LOOP_GUARD_MILLIS = 5_000L
        private const val INITIAL_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 30_000L
        private const val MAX_RETRY_EXPONENT = 5
        private const val MAINTENANCE_INTERVAL_MILLIS = 60_000L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 5_000L
        private const val OFFLINE_UPDATE_TIMEOUT_MILLIS = 1_500L
        private val IMAGE_FINGERPRINT_PREFIX =
            "image\u0000".toByteArray(StandardCharsets.UTF_8)
        private val IMAGE_FINGERPRINT_SUFFIX = byteArrayOf(0)
        private val UNSAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")

        private val running = AtomicBoolean(false)
        private val mutableState = MutableStateFlow(ClipboardSyncState())
        val state: StateFlow<ClipboardSyncState> = mutableState.asStateFlow()

        val isRunning: Boolean
            get() = running.get()

        /** Must be called from a visible user action on Android 12+. */
        fun start(context: Context) {
            AppPreferences(context).autoSyncEnabled = true
            val intent = Intent(context, ClipboardSyncService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            AppPreferences(context).autoSyncEnabled = false
            val intent = Intent(context, ClipboardSyncService::class.java).apply {
                action = ACTION_STOP
            }
            if (isRunning) {
                try {
                    context.startService(intent)
                } catch (_: RuntimeException) {
                    context.stopService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }

        /** Call from Activity.onResume so Android grants access to the current clipboard. */
        fun captureNow(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, ClipboardSyncService::class.java).apply {
                action = ACTION_CAPTURE_NOW
            }
            context.startService(intent)
        }

        fun getOrCreateDeviceId(context: Context): String {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            preferences.getString(KEY_DEVICE_ID, null)?.let { existing ->
                if (existing.isNotBlank()) return existing
            }
            val generated = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

        /**
         * Explains settings that can limit an otherwise running foreground service. The app never
         * attempts to bypass these platform policies; it gives the user a recovery path instead.
         */
        fun backgroundSyncGuidance(context: Context): String? {
            val appContext = context.applicationContext
            val notificationBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            if (notificationBlocked) {
                return "알림 권한이 꺼져 있어 동기화 상태 알림을 볼 수 없습니다."
            }
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                powerManager != null &&
                !powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
            ) {
                return "절전 모드에서는 동기화가 늦어질 수 있습니다. 앱 배터리를 ‘제한 없음’으로 설정해 주세요."
            }
            return null
        }

        private fun setState(state: ClipboardSyncState) {
            mutableState.value = state
        }
    }
}

enum class ClipboardSyncPhase {
    STOPPED,
    WAITING_FOR_AUTH,
    SYNCING,
    ERROR,
}

data class ClipboardSyncState(
    val phase: ClipboardSyncPhase = ClipboardSyncPhase.STOPPED,
    val message: String? = null,
)
