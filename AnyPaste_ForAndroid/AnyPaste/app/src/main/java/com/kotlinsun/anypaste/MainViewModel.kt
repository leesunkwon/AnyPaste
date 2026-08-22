package com.kotlinsun.anypaste

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.messaging.FirebaseMessaging
import com.kotlinsun.anypaste.data.FirebaseAuthRepository
import com.kotlinsun.anypaste.data.FirebaseStorageRepository
import com.kotlinsun.anypaste.data.FirestoreClipboardRepository
import com.kotlinsun.anypaste.data.FirestoreDeviceRepository
import com.kotlinsun.anypaste.data.awaitResult
import com.kotlinsun.anypaste.model.AuthUser
import com.kotlinsun.anypaste.model.ClipboardItem
import com.kotlinsun.anypaste.model.ClipboardType
import com.kotlinsun.anypaste.model.Device
import com.kotlinsun.anypaste.model.DevicePlatform
import com.kotlinsun.anypaste.service.ClipboardSyncService
import java.io.File
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
    val isBusy: Boolean = false,
    val transferProgress: Int? = null,
    val transferStatus: TransferStatus = TransferStatus.IDLE,
    val transferTitle: String = "",
    val transferMeta: String = "",
    val transferType: ClipboardType = ClipboardType.TEXT,
    val pendingEvents: List<QueuedUiEvent> = emptyList(),
)

enum class TransferStatus { IDLE, SENDING, SUCCEEDED, FAILED }

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

    private val deviceId = ClipboardSyncService.getOrCreateDeviceId(application)
    private val deviceName = buildDeviceName()

    private val mutableState = MutableStateFlow(MainUiState(user = authRepository.currentUser))
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var sessionJob: Job? = null
    private var nextEventId = 0L

    init {
        viewModelScope.launch {
            authRepository.authState.collect { user ->
                mutableState.update {
                    val userChanged = it.user?.uid != user?.uid
                    it.copy(
                        authResolved = true,
                        user = user,
                        clipboardItems = if (user == null) emptyList() else it.clipboardItems,
                        devices = if (user == null) emptyList() else it.devices,
                        selectedItemId = if (userChanged) null else it.selectedItemId,
                        selectedDeviceId = if (userChanged) null else it.selectedDeviceId,
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
    }

    fun sendText(content: String, targetDeviceId: String = "") {
        val userId = requireUserId() ?: return
        val normalizedContent = content.trim()
        mutableState.update {
            it.copy(
                transferStatus = TransferStatus.SENDING,
                transferTitle = normalizedContent.lineSequence().firstOrNull().orEmpty().take(60),
                transferMeta = "텍스트 · ${normalizedContent.toByteArray().size} B",
                transferType = ClipboardType.TEXT,
                transferProgress = null,
            )
        }
        launchBusy(successEvent = MainUiEvent.TransferCompleted) {
            clipboardRepository.createText(
                userId = userId,
                content = normalizedContent,
                sourceDeviceId = deviceId,
                targetDeviceId = targetDeviceId,
            )
        }
    }

    fun sendFile(
        source: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        targetDeviceId: String = "",
    ) {
        val userId = requireUserId() ?: return
        if (fileSize == 0L) {
            postMessage("빈 파일은 전송할 수 없습니다.")
            return
        }
        if (fileSize > MAX_FILE_BYTES) {
            postMessage("파일은 50MB 이하만 전송할 수 있습니다.")
            return
        }

        val type = if (mimeType.startsWith("image/")) ClipboardType.IMAGE else ClipboardType.FILE
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isBusy = true,
                    transferProgress = 0,
                    transferStatus = TransferStatus.SENDING,
                    transferTitle = fileName,
                    transferMeta = "${if (type == ClipboardType.IMAGE) "이미지" else "파일"} · " +
                        formatByteCount(fileSize),
                    transferType = type,
                )
            }
            val itemId = clipboardRepository.newItemId(userId)
            var uploadedPath: String? = null
            var temporaryUpload: File? = null
            try {
                val uploadSource = if (fileSize < 0L) {
                    copyUnknownSizeSource(source).also { temporaryUpload = it }.let(Uri::fromFile)
                } else {
                    source
                }
                val resolvedSize = temporaryUpload?.length() ?: fileSize
                mutableState.update {
                    it.copy(
                        transferMeta = "${if (type == ClipboardType.IMAGE) "이미지" else "파일"} · " +
                            formatByteCount(resolvedSize),
                    )
                }
                val upload = storageRepository.uploadFile(
                    userId = userId,
                    itemId = itemId,
                    source = uploadSource,
                    fileName = fileName,
                    mimeType = mimeType,
                    onProgress = { progress ->
                        mutableState.update { state ->
                            state.copy(transferProgress = progress.percentage)
                        }
                    },
                )
                uploadedPath = upload.storagePath
                clipboardRepository.createBinary(
                    userId = userId,
                    itemId = itemId,
                    type = type,
                    upload = upload,
                    sourceDeviceId = deviceId,
                    targetDeviceId = targetDeviceId,
                )
                mutableState.update { it.copy(transferStatus = TransferStatus.SUCCEEDED) }
                postEvent(MainUiEvent.TransferCompleted)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uploadedPath?.let { path -> runCatching { storageRepository.delete(path) } }
                mutableState.update { it.copy(transferStatus = TransferStatus.FAILED) }
                postEvent(MainUiEvent.Message(error.toKoreanMessage()))
            } finally {
                temporaryUpload?.delete()
                mutableState.update { it.copy(isBusy = false, transferProgress = null) }
            }
        }
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

    fun deleteItem(item: ClipboardItem) {
        val userId = requireUserId() ?: return
        launchBusy(successMessage = "항목을 삭제했습니다.") {
            clipboardRepository.deleteItem(userId, item.id)
            if (item.storagePath.isNotBlank()) {
                runCatching { storageRepository.delete(item.storagePath) }
            }
        }
    }

    fun deleteItems(items: List<ClipboardItem>) {
        val userId = requireUserId() ?: return
        if (items.isEmpty()) {
            postMessage("삭제할 항목을 선택해 주세요.")
            return
        }
        launchBusy(successMessage = "선택한 항목을 삭제했습니다.") {
            items.forEach { item ->
                clipboardRepository.deleteItem(userId, item.id)
                if (item.storagePath.isNotBlank()) {
                    runCatching { storageRepository.delete(item.storagePath) }
                }
            }
        }
    }

    fun markAllRead() {
        val userId = requireUserId() ?: return
        val unread = state.value.clipboardItems.filter { item ->
            item.sourceDeviceId != deviceId && !item.isReadBy(deviceId)
        }
        if (unread.isEmpty()) {
            postMessage("모든 알림을 이미 확인했습니다.")
            return
        }
        launchBusy(successMessage = "모든 알림을 읽음 처리했습니다.") {
            unread.forEach { clipboardRepository.markAsRead(userId, it.id, deviceId) }
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

    fun selectItem(itemId: String?) {
        mutableState.update { it.copy(selectedItemId = itemId) }
    }

    fun selectDevice(deviceId: String?) {
        mutableState.update { it.copy(selectedDeviceId = deviceId) }
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
                deviceName = deviceName,
                platform = DevicePlatform.ANDROID,
                fcmToken = token,
            )
        }.onFailure { postMessage(it.toKoreanMessage()) }
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
                postEvent(MainUiEvent.Message(error.toKoreanMessage()))
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

    private fun Throwable.toKoreanMessage(): String {
        if (this is IllegalArgumentException) return message ?: "입력 내용을 확인해 주세요."
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
        const val MAX_PREVIEW_BYTES = 8L * 1024L * 1024L
        const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
        const val MAX_PENDING_EVENTS = 20
    }
}
