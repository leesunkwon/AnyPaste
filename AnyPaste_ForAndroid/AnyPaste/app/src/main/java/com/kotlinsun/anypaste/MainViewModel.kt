package com.kotlinsun.anypaste

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import com.google.firebase.messaging.FirebaseMessaging
import com.kotlinsun.anypaste.data.FirebaseAuthRepository
import com.kotlinsun.anypaste.data.FirebaseStorageRepository
import com.kotlinsun.anypaste.data.FirestoreClipboardRepository
import com.kotlinsun.anypaste.data.FirestoreDeviceRepository
import com.kotlinsun.anypaste.data.DeviceSessionRevokedException
import com.kotlinsun.anypaste.data.awaitResult
import com.kotlinsun.anypaste.core.AppPreferences
import com.kotlinsun.anypaste.model.AuthUser
import com.kotlinsun.anypaste.model.ClipboardItem
import com.kotlinsun.anypaste.model.ClipboardType
import com.kotlinsun.anypaste.model.Device
import com.kotlinsun.anypaste.model.DevicePlatform
import com.kotlinsun.anypaste.service.ClipboardSyncService
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

data class MainUiState(
    val authResolved: Boolean = false,
    val user: AuthUser? = null,
    val clipboardItems: List<ClipboardItem> = emptyList(),
    val devices: List<Device> = emptyList(),
    val selectedItemId: String? = null,
    val selectedDeviceId: String? = null,
    val sendTargetDeviceId: String = "",
    val storageUsageBytes: Long = 0L,
    val storageLimitBytes: Long = 1L * 1024L * 1024L * 1024L,
    val isBusy: Boolean = false,
    val transferProgress: Int? = null,
    val transferStatus: TransferStatus = TransferStatus.IDLE,
    val transferTitle: String = "",
    val transferMeta: String = "",
    val transferType: ClipboardType = ClipboardType.TEXT,
    val transferFailureReason: String? = null,
    val failedTransfers: List<FailedTransfer> = emptyList(),
    val pendingEvents: List<QueuedUiEvent> = emptyList(),
)

enum class TransferStatus { IDLE, SENDING, SUCCEEDED, FAILED }

data class FailedTransfer(
    val id: String,
    val title: String,
    val meta: String,
    val reason: String,
)

data class QueuedUiEvent(
    val id: Long,
    val event: MainUiEvent,
)

sealed interface MainUiEvent {
    data class Message(val text: String) : MainUiEvent
    data class DownloadReady(val file: File, val mimeType: String) : MainUiEvent
    data class ImagePreviewReady(val itemId: String, val bytes: ByteArray) : MainUiEvent
    data object TransferCompleted : MainUiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val authRepository = FirebaseAuthRepository()
    private val clipboardRepository = FirestoreClipboardRepository()
    private val deviceRepository = FirestoreDeviceRepository()
    private val storageRepository = FirebaseStorageRepository()

    private val preferences = AppPreferences(application)
    private val deviceId = ClipboardSyncService.getOrCreateDeviceId(application)

    private val mutableState = MutableStateFlow(
        MainUiState(
            user = authRepository.currentUser,
            sendTargetDeviceId = preferences.lastTransferTargetDeviceId,
        ),
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var sessionJob: Job? = null
    private var nextEventId = 0L
    private val retryableTransfers = LinkedHashMap<String, PendingTransfer>()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { user ->
                mutableState.update {
                    val userChanged = it.user?.uid != user?.uid
                    if (userChanged) retryableTransfers.clear()
                    it.copy(
                        authResolved = true,
                        user = user,
                        clipboardItems = if (user == null) emptyList() else it.clipboardItems,
                        devices = if (user == null) emptyList() else it.devices,
                        selectedItemId = if (userChanged) null else it.selectedItemId,
                        selectedDeviceId = if (userChanged) null else it.selectedDeviceId,
                        transferFailureReason = if (userChanged) null else it.transferFailureReason,
                        failedTransfers = if (userChanged) emptyList() else it.failedTransfers,
                        pendingEvents = if (userChanged) emptyList() else it.pendingEvents,
                    )
                }
                startUserSession(user)
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        launchBusy { authRepository.signInWithEmail(email, password) }
    }

    fun signUpWithEmail(email: String, displayName: String, password: String) {
        launchBusy {
            val user = authRepository.createAccountWithEmail(
                email = email,
                password = password,
                displayName = displayName,
            )
            mutableState.update { it.copy(user = user) }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        launchBusy { authRepository.signInWithGoogleIdToken(idToken) }
    }

    fun sendPasswordReset(email: String) {
        launchBusy(successMessage = "비밀번호 재설정 메일을 보냈습니다.") {
            authRepository.sendPasswordResetEmail(email)
        }
    }

    fun signOut() {
        sessionJob?.cancel()
        sessionJob = null
        authRepository.signOut()
        preferences.autoSyncEnabled = false
        preferences.lastTransferTargetDeviceId = ""
        retryableTransfers.clear()
        mutableState.update {
            it.copy(
                authResolved = true,
                user = null,
                clipboardItems = emptyList(),
                devices = emptyList(),
                selectedItemId = null,
                selectedDeviceId = null,
                sendTargetDeviceId = "",
                storageUsageBytes = 0L,
                isBusy = false,
                transferProgress = null,
                transferStatus = TransferStatus.IDLE,
                transferFailureReason = null,
                failedTransfers = emptyList(),
                pendingEvents = emptyList(),
            )
        }
    }

    fun sendText(
        content: String,
        targetDeviceId: String = "",
    ) {
        val normalizedContent = content.trim()
        if (normalizedContent.isEmpty()) {
            postMessage("보낼 텍스트를 입력해 주세요.")
            return
        }
        sendTextTransfer(
            PendingTransfer.Text(
                id = UUID.randomUUID().toString(),
                content = normalizedContent,
                targetDeviceId = targetDeviceId,
            ),
        )
    }

    fun retryLastFailedTransfer() {
        val pending = state.value.failedTransfers.lastOrNull()?.let { retryableTransfers[it.id] }
        if (pending == null) {
            postMessage("다시 시도할 전송 항목이 없습니다.")
            return
        }
        when (pending) {
            is PendingTransfer.Text -> sendTextTransfer(pending)
            is PendingTransfer.File -> sendFileTransfer(pending)
        }
    }

    private fun sendTextTransfer(transfer: PendingTransfer.Text) {
        val userId = requireUserId() ?: return
        mutableState.update {
            it.copy(
                transferStatus = TransferStatus.SENDING,
                transferTitle = transfer.title,
                transferMeta = transfer.meta,
                transferType = ClipboardType.TEXT,
                transferProgress = null,
                transferFailureReason = null,
            )
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true) }
            try {
                val previousItems = state.value.clipboardItems
                val created = clipboardRepository.createText(
                    userId = userId,
                    content = transfer.content,
                    sourceDeviceId = deviceId,
                    targetDeviceId = transfer.targetDeviceId,
                    expiresAt = transfer.expiresAt(),
                )
                deleteReplacedStorage(previousItems, keepingStoragePath = created.storagePath)
                completeTransfer(transfer.id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failTransfer(transfer, error)
            } finally {
                mutableState.update { it.copy(isBusy = false, transferProgress = null) }
            }
        }
    }

    fun sendFile(
        source: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        targetDeviceId: String = "",
    ) {
        if (fileSize == 0L) {
            postMessage("빈 파일은 전송할 수 없습니다.")
            return
        }
        if (fileSize > MAX_FILE_BYTES) {
            postMessage("파일은 50MB 이하만 전송할 수 있습니다.")
            return
        }
        val type = if (mimeType.startsWith("image/")) ClipboardType.IMAGE else ClipboardType.FILE
        sendFileTransfer(
            PendingTransfer.File(
                id = UUID.randomUUID().toString(),
                source = source,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                targetDeviceId = targetDeviceId,
                type = type,
            ),
        )
    }

    private fun sendFileTransfer(transfer: PendingTransfer.File) {
        val userId = requireUserId() ?: return
        viewModelScope.launch {
            val previousItems = state.value.clipboardItems
            mutableState.update {
                it.copy(
                    isBusy = true,
                    transferProgress = 0,
                    transferStatus = TransferStatus.SENDING,
                    transferTitle = transfer.fileName,
                    transferMeta = transfer.meta,
                    transferType = transfer.type,
                    transferFailureReason = null,
                )
            }
            val itemId = clipboardRepository.newItemId(userId)
            var uploadedPath: String? = null
            var temporaryUpload: File? = null
            try {
                val uploadSource = if (transfer.fileSize < 0L) {
                    copyUnknownSizeSource(transfer.source).also { temporaryUpload = it }.let(Uri::fromFile)
                } else {
                    transfer.source
                }
                val resolvedSize = temporaryUpload?.length() ?: transfer.fileSize
                require(resolvedSize <= MAX_STORAGE_BYTES) {
                    "저장 공간 한도를 초과합니다. 불필요한 파일을 삭제한 뒤 다시 시도해 주세요."
                }
                mutableState.update {
                    it.copy(
                        transferMeta = "${if (transfer.type == ClipboardType.IMAGE) "이미지" else "파일"} · " +
                            formatByteCount(resolvedSize),
                    )
                }
                val upload = storageRepository.uploadFile(
                    userId = userId,
                    itemId = itemId,
                    source = uploadSource,
                    fileName = transfer.fileName,
                    mimeType = transfer.mimeType,
                    onProgress = { progress ->
                        mutableState.update { state ->
                            state.copy(transferProgress = progress.percentage)
                        }
                    },
                )
                uploadedPath = upload.storagePath
                val created = clipboardRepository.createBinary(
                    userId = userId,
                    itemId = itemId,
                    type = transfer.type,
                    upload = upload,
                    sourceDeviceId = deviceId,
                    targetDeviceId = transfer.targetDeviceId,
                    expiresAt = transfer.expiresAt(),
                )
                deleteReplacedStorage(previousItems, keepingStoragePath = created.storagePath)
                completeTransfer(transfer.id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uploadedPath?.let { path -> runCatching { storageRepository.delete(path) } }
                failTransfer(transfer, error)
            } finally {
                temporaryUpload?.delete()
                mutableState.update { it.copy(isBusy = false, transferProgress = null) }
            }
        }
    }

    private fun completeTransfer(transferId: String) {
        retryableTransfers.remove(transferId)
        mutableState.update { state ->
            state.copy(
                transferStatus = TransferStatus.SUCCEEDED,
                transferFailureReason = null,
                failedTransfers = state.failedTransfers.filterNot { it.id == transferId },
            )
        }
        postEvent(MainUiEvent.TransferCompleted)
    }

    private fun failTransfer(transfer: PendingTransfer, error: Throwable) {
        if (error is DeviceSessionRevokedException) {
            mutableState.update { it.copy(transferStatus = TransferStatus.FAILED) }
            endRevokedDeviceSession()
            return
        }
        val reason = error.toKoreanMessage()
        retryableTransfers[transfer.id] = transfer
        val failed = FailedTransfer(
            id = transfer.id,
            title = transfer.title,
            meta = transfer.meta,
            reason = reason,
        )
        mutableState.update { state ->
            state.copy(
                transferStatus = TransferStatus.FAILED,
                transferFailureReason = reason,
                failedTransfers = (state.failedTransfers.filterNot { it.id == transfer.id } + failed)
                    .takeLast(MAX_FAILED_TRANSFERS),
            )
        }
        while (retryableTransfers.size > MAX_FAILED_TRANSFERS) {
            retryableTransfers.entries.iterator().let { iterator ->
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        postMessage(reason)
    }

    fun download(item: ClipboardItem, destination: File) {
        if (item.storagePath.isBlank()) {
            postMessage("다운로드할 파일 정보가 없습니다.")
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true, transferProgress = 0) }
            try {
                val file = storageRepository.downloadFile(
                    storagePath = item.storagePath,
                    destination = destination,
                    onProgress = { progress ->
                        mutableState.update { state ->
                            state.copy(transferProgress = progress.percentage)
                        }
                    },
                )
                postEvent(
                    MainUiEvent.DownloadReady(
                        file = file,
                        mimeType = item.mimeType.ifBlank { "application/octet-stream" },
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                postEvent(MainUiEvent.Message(error.toKoreanMessage()))
            } finally {
                mutableState.update { it.copy(isBusy = false, transferProgress = null) }
            }
        }
    }

    fun loadImagePreview(item: ClipboardItem) {
        if (item.resolvedType() != ClipboardType.IMAGE || item.storagePath.isBlank()) return
        viewModelScope.launch {
            try {
                val bytes = storageRepository.downloadBytes(
                    storagePath = item.storagePath,
                    maxDownloadSizeBytes = MAX_PREVIEW_BYTES,
                )
                postEvent(MainUiEvent.ImagePreviewReady(item.id, bytes))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                postEvent(MainUiEvent.Message("이미지 미리보기를 불러오지 못했습니다."))
            }
        }
    }

    private suspend fun deleteReplacedStorage(
        replacedItems: List<ClipboardItem>,
        keepingStoragePath: String,
    ) {
        replacedItems.map(ClipboardItem::storagePath)
            .filter { it.isNotBlank() && it != keepingStoragePath }
            .distinct()
            .forEach { path ->
                try {
                    storageRepository.delete(path)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // The replacement is already visible; best-effort Storage cleanup can retry later.
                }
            }
    }

    fun markRead(item: ClipboardItem) {
        val userId = requireUserId() ?: return
        if (item.sourceDeviceId == deviceId || item.isReadBy(deviceId)) return
        viewModelScope.launch {
            runCatching { clipboardRepository.markAsRead(userId, item.id, deviceId) }
        }
    }

    fun removeDevice(device: Device) {
        val userId = requireUserId() ?: return
        if (device.id == deviceId) {
            postMessage("현재 사용 중인 기기는 연결 해제할 수 없습니다.")
            return
        }
        launchBusy(successMessage = "기기 연결을 해제했습니다.") {
            deviceRepository.removeDevice(userId, device.id)
        }
    }

    fun renameDevice(device: Device, name: String) {
        val userId = requireUserId() ?: return
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_DEVICE_NAME_LENGTH) {
            postMessage("기기 이름은 1~100자로 입력해 주세요.")
            return
        }
        if (device.id == deviceId) preferences.deviceDisplayName = normalizedName
        launchBusy(successMessage = "기기 이름을 변경했습니다.") {
            if (device.id == deviceId) {
                registerCurrentDevice(userId)
            } else {
                deviceRepository.renameDevice(userId, device.id, normalizedName)
            }
        }
    }

    fun selectItem(itemId: String?) {
        mutableState.update { it.copy(selectedItemId = itemId) }
    }

    fun selectDevice(deviceId: String?) {
        mutableState.update { it.copy(selectedDeviceId = deviceId) }
    }

    fun selectSendTarget(deviceId: String?) {
        val targetDeviceId = deviceId.orEmpty()
        preferences.lastTransferTargetDeviceId = targetDeviceId
        mutableState.update { it.copy(sendTargetDeviceId = targetDeviceId) }
    }

    fun selectedItem(): ClipboardItem? = state.value.clipboardItems
        .firstOrNull { it.id == state.value.selectedItemId }

    fun selectedDevice(): Device? = state.value.devices
        .firstOrNull { it.id == state.value.selectedDeviceId }

    fun currentDeviceId(): String = deviceId

    fun consumeEvent(eventId: Long) {
        mutableState.update { state ->
            state.copy(pendingEvents = state.pendingEvents.filterNot { it.id == eventId })
        }
    }

    private fun startUserSession(user: AuthUser?) {
        sessionJob?.cancel()
        sessionJob = null
        if (user == null) return

        sessionJob = viewModelScope.launch {
            supervisorScope {
                launch {
                    clipboardRepository.observeClipboard(user.uid)
                        .retryWhen { cause, attempt ->
                            if (cause is CancellationException) return@retryWhen false
                            postMessage("클립보드 연결을 다시 시도하고 있습니다.")
                            delay(retryDelayMillis(attempt))
                            true
                        }
                        .catch { error -> postMessage(error.toKoreanMessage()) }
                        .collect { items ->
                            mutableState.update { state ->
                                state.copy(
                                    clipboardItems = items.filterNot { item ->
                                        item.isExpired() ||
                                            (item.targetDeviceId.isNotBlank() &&
                                        item.targetDeviceId != deviceId &&
                                                item.sourceDeviceId != deviceId)
                                    },
                                    storageUsageBytes = items.asSequence()
                                        .filterNot(ClipboardItem::isExpired)
                                        .map(ClipboardItem::fileSize)
                                        .filter { it > 0L }
                                        .sum(),
                                )
                            }
                        }
                }
                launch {
                    deviceRepository.observeDevices(user.uid)
                        .retryWhen { cause, attempt ->
                            if (cause is CancellationException) return@retryWhen false
                            postMessage("기기 연결을 다시 시도하고 있습니다.")
                            delay(retryDelayMillis(attempt))
                            true
                        }
                        .catch { error -> postMessage(error.toKoreanMessage()) }
                        .collect { devices ->
                            mutableState.update { it.copy(devices = devices) }
                        }
                }
                launch { registerCurrentDevice(user.uid) }
                launch {
                    runCatching { clipboardRepository.deleteExpiredItems(user.uid) }
                }
                launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MILLIS)
                        if (!ClipboardSyncService.isRunning) {
                            runCatching { deviceRepository.heartbeat(user.uid, deviceId) }
                                .onFailure(::handleDeviceSessionError)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun registerCurrentDevice(userId: String) {
        val token = runCatching { FirebaseMessaging.getInstance().token.awaitResult() }
            .getOrDefault("")
        runCatching {
            deviceRepository.registerDevice(
                userId = userId,
                deviceId = deviceId,
                deviceName = currentDeviceName(),
                platform = DevicePlatform.ANDROID,
                fcmToken = token,
            )
        }.onFailure(::handleDeviceSessionError)
    }

    private fun launchBusy(
        successMessage: String? = null,
        successEvent: MainUiEvent? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            mutableState.update { it.copy(isBusy = true) }
            try {
                block()
                if (successEvent == MainUiEvent.TransferCompleted) {
                    mutableState.update { it.copy(transferStatus = TransferStatus.SUCCEEDED) }
                }
                successMessage?.let { postEvent(MainUiEvent.Message(it)) }
                successEvent?.let(::postEvent)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (successEvent == MainUiEvent.TransferCompleted) {
                    mutableState.update { it.copy(transferStatus = TransferStatus.FAILED) }
                }
                handleDeviceSessionError(error)
            } finally {
                mutableState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun requireUserId(): String? = state.value.user?.uid ?: run {
        postMessage("로그인이 필요합니다.")
        null
    }

    private fun postMessage(message: String) {
        postEvent(MainUiEvent.Message(message))
    }

    private fun handleDeviceSessionError(error: Throwable) {
        if (error is DeviceSessionRevokedException) {
            endRevokedDeviceSession()
        } else {
            postMessage(error.toKoreanMessage())
        }
    }

    private fun endRevokedDeviceSession() {
        sessionJob?.cancel()
        sessionJob = null
        ClipboardSyncService.stop(getApplication<Application>())
        authRepository.signOut()
        postMessage("이 기기의 연결이 해제되어 로그아웃되었습니다.")
    }

    private fun postEvent(event: MainUiEvent) {
        nextEventId += 1L
        val queuedEvent = QueuedUiEvent(nextEventId, event)
        mutableState.update { state ->
            state.copy(
                pendingEvents = (state.pendingEvents + queuedEvent).takeLast(MAX_PENDING_EVENTS),
            )
        }
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
            .ifBlank { "Android 기기" }
    }

    private fun currentDeviceName(): String = preferences.deviceDisplayName
        .takeIf(String::isNotBlank)
        ?: buildDeviceName()

    private fun Throwable.toKoreanMessage(): String {
        if (this is IllegalArgumentException) return message ?: "입력 내용을 확인해 주세요."
        if (this is FirebaseNetworkException) return "네트워크 연결이 끊겼습니다. 연결을 확인한 뒤 다시 시도해 주세요."
        if (this is FirebaseFirestoreException) {
            return when (code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "전송 권한이 없습니다. 로그인 상태와 연결된 기기를 확인해 주세요."
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                    "서비스 사용량 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "서버에 연결할 수 없습니다. 네트워크를 확인한 뒤 다시 시도해 주세요."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "로그인 정보가 만료되었습니다. 다시 로그인해 주세요."
                else -> message ?: "전송 정보를 저장하지 못했습니다."
            }
        }
        if (this is StorageException) {
            return when (errorCode) {
                StorageException.ERROR_QUOTA_EXCEEDED ->
                    "저장 공간 또는 전송 한도를 초과했습니다. 파일 크기를 확인해 주세요."
                StorageException.ERROR_NOT_AUTHENTICATED,
                StorageException.ERROR_NOT_AUTHORIZED ->
                    "파일 전송 권한이 없습니다. 다시 로그인해 주세요."
                StorageException.ERROR_RETRY_LIMIT_EXCEEDED ->
                    "파일 업로드 시간이 초과되었습니다. 네트워크를 확인한 뒤 다시 시도해 주세요."
                StorageException.ERROR_CANCELED -> "파일 전송이 취소되었습니다."
                else -> message ?: "파일을 업로드하지 못했습니다."
            }
        }
        if (this is FirebaseAuthException) {
            return when (errorCode) {
                "ERROR_INVALID_EMAIL" -> "이메일 형식을 확인해 주세요."
                "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
                    "이메일 또는 비밀번호를 확인해 주세요."
                "ERROR_USER_NOT_FOUND" -> "가입되지 않은 이메일입니다."
                "ERROR_USER_DISABLED" -> "사용이 중지된 계정입니다."
                "ERROR_EMAIL_ALREADY_IN_USE" -> "이미 가입된 이메일입니다. 로그인해 주세요."
                "ERROR_WEAK_PASSWORD" -> "Firebase 비밀번호 보안 조건을 확인해 주세요."
                "ERROR_OPERATION_NOT_ALLOWED" -> "Firebase Console에서 로그인 방식을 활성화해 주세요."
                "ERROR_NETWORK_REQUEST_FAILED" -> "네트워크 연결을 확인해 주세요."
                "ERROR_TOO_MANY_REQUESTS" -> "요청이 많습니다. 잠시 후 다시 시도해 주세요."
                else -> message ?: "인증 요청을 완료하지 못했습니다."
            }
        }
        return message?.takeIf { it.isNotBlank() } ?: "요청을 완료하지 못했습니다."
    }

    private fun formatByteCount(bytes: Long): String = when {
        bytes < 0L -> "크기 정보 없음"
        bytes < 1_024L -> "$bytes B"
        bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KB"
        else -> "${bytes / (1_024L * 1_024L)} MB"
    }

    private fun retryDelayMillis(attempt: Long): Long {
        val exponent = attempt.coerceAtMost(5L).toInt()
        return (1_000L shl exponent).coerceAtMost(30_000L)
    }

    private suspend fun copyUnknownSizeSource(source: Uri): File = withContext(Dispatchers.IO) {
        val application = getApplication<Application>()
        val directory = File(application.cacheDir, "pending_uploads")
        check(directory.exists() || directory.mkdirs()) {
            "전송 준비 폴더를 만들 수 없습니다."
        }
        val destination = File(directory, UUID.randomUUID().toString())
        try {
            val input = application.contentResolver.openInputStream(source)
                ?: throw IllegalArgumentException("선택한 파일을 읽을 수 없습니다.")
            input.use { sourceStream ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_FILE_BYTES) {
                            throw IllegalArgumentException("파일은 50MB 이하만 전송할 수 있습니다.")
                        }
                        output.write(buffer, 0, count)
                    }
                    require(totalBytes > 0L) { "빈 파일은 전송할 수 없습니다." }
                }
            }
            destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        const val MAX_STORAGE_BYTES = 1L * 1024L * 1024L * 1024L
        const val MAX_DEVICE_NAME_LENGTH = 100
        const val CLIPBOARD_TTL_MILLIS = 5L * 60L * 1_000L
        const val MAX_PREVIEW_BYTES = 8L * 1024L * 1024L
        const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val MAX_PENDING_EVENTS = 20
        const val MAX_FAILED_TRANSFERS = 20

        fun formatTransferSize(bytes: Long): String = when {
            bytes < 0L -> "크기 정보 없음"
            bytes < 1_024L -> "$bytes B"
            bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KB"
            else -> "${bytes / (1_024L * 1_024L)} MB"
        }
    }

    private sealed interface PendingTransfer {
        val id: String
        val title: String
        val meta: String

        data class Text(
            override val id: String,
            val content: String,
            val targetDeviceId: String,
        ) : PendingTransfer {
            override val title: String = content.lineSequence().firstOrNull().orEmpty().take(60)
            override val meta: String = "텍스트 · ${content.toByteArray().size} B"

            fun expiresAt(): Timestamp = Timestamp(
                Date(System.currentTimeMillis() + CLIPBOARD_TTL_MILLIS),
            )
        }

        data class File(
            override val id: String,
            val source: Uri,
            val fileName: String,
            val mimeType: String,
            val fileSize: Long,
            val targetDeviceId: String,
            val type: ClipboardType,
        ) : PendingTransfer {
            override val title: String = fileName
            override val meta: String =
                "${if (type == ClipboardType.IMAGE) "이미지" else "파일"} · ${formatTransferSize(fileSize)}"

            fun expiresAt(): Timestamp = Timestamp(
                Date(System.currentTimeMillis() + CLIPBOARD_TTL_MILLIS),
            )
        }
    }
}
