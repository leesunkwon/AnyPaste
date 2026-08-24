package com.kotlinsun.anypaste

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Patterns
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kotlinsun.anypaste.core.AppPreferences
import com.kotlinsun.anypaste.model.ClipboardItem
import com.kotlinsun.anypaste.model.ClipboardType
import com.kotlinsun.anypaste.model.Device
import com.kotlinsun.anypaste.model.DevicePlatform
import com.kotlinsun.anypaste.service.AnyPasteNotificationManager
import com.kotlinsun.anypaste.service.ClipboardSyncPhase
import com.kotlinsun.anypaste.service.ClipboardSyncService
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val preferences by lazy { AppPreferences(this) }
    private val credentialManager by lazy { CredentialManager.create(this) }

    private lateinit var screenContainer: FrameLayout
    private var currentScreen = Screen.LOGIN
    private var currentRoot: View? = null
    private var authTransitionHandled = false
    private var lastUserId: String? = null

    private var clipboardFilter = ClipboardFilter.ALL
    private var clipboardSelectionMode = false
    private val selectedClipboardIds = linkedSetOf<String>()

    private var sendType = ClipboardType.TEXT
    private var selectedFile: SelectedFile? = null
    private var pendingPickerType = ClipboardType.FILE
    private var transferCompleted = false
    private var transferFailed = false
    private var lastTransferTitle = ""
    private var lastTransferMeta = ""
    private var previewRequestedItemId: String? = null
    private var pendingStartSyncAfterPermission = false
    private var pendingSharedText: String? = null
    private var localPreviewBitmap: Bitmap? = null
    private var localPreviewBitmapUri: Uri? = null
    private var localPreviewLoadingUri: Uri? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            toast("알림 권한이 없으면 동기화 상태를 놓칠 수 있습니다. 앱 설정에서 언제든 허용할 수 있어요.")
        }
        if (pendingStartSyncAfterPermission) {
            pendingStartSyncAfterPermission = false
            startClipboardSyncFromUserAction()
        }
        renderCurrent(viewModel.state.value)
    }

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectedFile = readSelectedFile(uri)
        if (selectedFile == null) {
            toast("선택한 파일 정보를 읽을 수 없습니다.")
            return@registerForActivityResult
        }
        sendType = if (pendingPickerType == ClipboardType.IMAGE) {
            ClipboardType.IMAGE
        } else {
            ClipboardType.FILE
        }
        renderCurrent(viewModel.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.main)
        screenContainer = findViewById(R.id.screen_container)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                safeInsets.bottom,
            )
            insets
        }

        currentScreen = savedInstanceState?.getString(STATE_SCREEN)
            ?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?: if (preferences.hasSeenOnboarding) Screen.LOGIN else Screen.ONBOARDING
        restorePendingFile(savedInstanceState)
        showScreen(currentScreen, force = true)
        bindCollectors()
        handleIncomingIntent(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBack()
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (ClipboardSyncService.isRunning) {
            ClipboardSyncService.captureNow(this)
        } else if (preferences.autoSyncEnabled && viewModel.state.value.user != null) {
            // A foreground service killed by the system can only be restarted safely while this
            // Activity is visible. Android force-stop still requires the user to open the app.
            startClipboardSyncFromUserAction(showRecoveryMessage = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN, currentScreen.name)
        selectedFile?.let { file ->
            outState.putString(STATE_FILE_URI, file.uri.toString())
            outState.putString(STATE_FILE_NAME, file.name)
            outState.putString(STATE_FILE_MIME, file.mimeType)
            outState.putLong(STATE_FILE_SIZE, file.size)
        }
        outState.putString(STATE_SEND_TYPE, sendType.name)
        super.onSaveInstanceState(outState)
    }

    private fun bindCollectors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        handleAuthTransition(state)
                        renderCurrent(state)
                        state.pendingEvents.forEach { queuedEvent ->
                            handleUiEvent(queuedEvent.event)
                            viewModel.consumeEvent(queuedEvent.id)
                        }
                    }
                }
                launch {
                    ClipboardSyncService.state.collect { renderCurrent(viewModel.state.value) }
                }
            }
        }
    }

    private fun handleAuthTransition(state: MainUiState) {
        if (!state.authResolved) return
        if (state.isBusy && currentScreen in AUTH_SCREENS) return
        val userId = state.user?.uid
        val changed = !authTransitionHandled || userId != lastUserId
        if (!changed) return
        authTransitionHandled = true
        lastUserId = userId

        if (userId == null) {
            ClipboardSyncService.stop(this)
            if (currentScreen !in AUTH_SCREENS) showScreen(Screen.LOGIN)
            return
        }

        if (currentScreen in AUTH_SCREENS) {
            when {
                hasPendingShare() -> openSend(sendType)
                shouldShowPermissionScreen() -> showScreen(Screen.PERMISSIONS)
                else -> showScreen(Screen.HOME)
            }
        }
        if (preferences.autoSyncEnabled) {
            if (ClipboardSyncService.isRunning) {
                ClipboardSyncService.captureNow(this)
            } else {
                // The Activity is visible here, and the user previously enabled this setting.
                startClipboardSyncFromUserAction()
            }
        }
    }

    private fun showScreen(screen: Screen, force: Boolean = false) {
        if (!force && screen == currentScreen && currentRoot != null) return
        currentScreen = screen
        previewRequestedItemId = null
        screenContainer.removeAllViews()
        val view = layoutInflater.inflate(screen.layoutRes, screenContainer, false)
        currentRoot = view
        screenContainer.addView(view)
        bindCommonNavigation(view)
        bindScreenActions(view, screen)
        renderCurrent(viewModel.state.value)
    }

    private fun bindCommonNavigation(root: View) = with(root) {
        onClick(R.id.nav_home) { showScreen(Screen.HOME) }
        onClick(R.id.nav_devices) { showScreen(Screen.DEVICES) }
        onClick(R.id.nav_settings) { showScreen(Screen.SETTINGS) }
        onClick(R.id.btn_send) { openSend(ClipboardType.TEXT) }
        onClick(R.id.btn_back) { navigateBack() }
        updateBottomNavigationSelection(this)
    }

    private fun updateBottomNavigationSelection(root: View) {
        val selectedId = when (currentScreen) {
            Screen.HOME -> R.id.nav_home
            Screen.DEVICES -> R.id.nav_devices
            Screen.SETTINGS -> R.id.nav_settings
            else -> View.NO_ID
        }
        listOf(R.id.nav_home, R.id.nav_devices, R.id.nav_settings).forEach { itemId ->
            root.findViewById<View>(itemId)?.let { item ->
                item.isSelected = itemId == selectedId
                ViewCompat.setStateDescription(item, if (item.isSelected) "선택됨" else null)
            }
        }
    }

    private fun bindScreenActions(root: View, screen: Screen) {
        when (screen) {
            Screen.ONBOARDING -> root.onClick(R.id.btn_continue) {
                preferences.hasSeenOnboarding = true
                showScreen(if (viewModel.state.value.user == null) Screen.LOGIN else Screen.HOME)
            }

            Screen.LOGIN -> {
                root.onClick(R.id.btn_login) { beginGoogleSignIn() }
                root.onClick(R.id.btn_login_email) { showScreen(Screen.EMAIL_LOGIN) }
                root.onClick(R.id.btn_sign_up) { showScreen(Screen.SIGN_UP) }
                root.onClick(R.id.btn_show_onboarding) { showScreen(Screen.ONBOARDING) }
            }

            Screen.EMAIL_LOGIN -> bindEmailActions(root)
            Screen.SIGN_UP -> bindSignUpActions(root)
            Screen.HOME -> bindHomeActions(root)
            Screen.CLIPBOARD_LIST -> bindClipboardListActions(root)
            Screen.CLIPBOARD_DETAIL -> bindClipboardDetailActions(root)
            Screen.SEND -> bindSendActions(root)
            Screen.TRANSFER_STATUS -> root.onClick(R.id.btn_retry_transfer) { retryTransfer() }
            Screen.DEVICES -> root.onClick(R.id.btn_add_device) { showScreen(Screen.CONNECT_DEVICE) }
            Screen.DEVICE_DETAIL -> bindDeviceDetailActions(root)
            Screen.CONNECT_DEVICE -> Unit
            Screen.NOTIFICATIONS -> root.onClick(R.id.btn_mark_all_read) { viewModel.markAllRead() }
            Screen.SETTINGS -> bindSettingsActions(root)
            Screen.PERMISSIONS -> bindPermissionActions(root)
        }
    }

    private fun bindEmailActions(root: View) {
        root.onClick(R.id.btn_sign_up) { showScreen(Screen.SIGN_UP) }
        root.onClick(R.id.btn_email_continue) {
            val email = root.findViewById<TextInputEditText>(R.id.input_email).text
                ?.toString()?.trim().orEmpty()
            val password = root.findViewById<TextInputEditText>(R.id.input_password).text
                ?.toString().orEmpty()
            val emailLayout = root.findViewById<TextInputLayout>(R.id.layout_email)
            val passwordLayout = root.findViewById<TextInputLayout>(R.id.layout_password)
            emailLayout.error = null
            passwordLayout.error = null

            when {
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    emailLayout.error = "올바른 이메일을 입력해 주세요."
                password.length < 6 -> passwordLayout.error = "비밀번호는 6자 이상 입력해 주세요."
                else -> viewModel.signInWithEmail(email, password)
            }
        }
        root.onClick(R.id.btn_forgot_password) {
            val email = root.findViewById<TextInputEditText>(R.id.input_email).text
                ?.toString()?.trim().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                root.findViewById<TextInputLayout>(R.id.layout_email).error =
                    "재설정 메일을 받을 이메일을 입력해 주세요."
            } else {
                viewModel.sendPasswordReset(email)
            }
        }
    }

    private fun bindSignUpActions(root: View) {
        root.onClick(R.id.btn_create_account) {
            val email = root.findViewById<TextInputEditText>(R.id.input_signup_email).text
                ?.toString()?.trim().orEmpty()
            val displayName = root.findViewById<TextInputEditText>(R.id.input_signup_name).text
                ?.toString()?.trim().orEmpty()
            val password = root.findViewById<TextInputEditText>(R.id.input_signup_password).text
                ?.toString().orEmpty()
            val passwordConfirmation = root
                .findViewById<TextInputEditText>(R.id.input_signup_password_confirmation)
                .text?.toString().orEmpty()
            val emailLayout = root.findViewById<TextInputLayout>(R.id.layout_signup_email)
            val nameLayout = root.findViewById<TextInputLayout>(R.id.layout_signup_name)
            val passwordLayout = root.findViewById<TextInputLayout>(R.id.layout_signup_password)
            val confirmationLayout = root
                .findViewById<TextInputLayout>(R.id.layout_signup_password_confirmation)

            listOf(emailLayout, nameLayout, passwordLayout, confirmationLayout).forEach {
                it.error = null
            }
            root.findViewById<TextView>(R.id.tv_signup_error).isVisible = false

            when {
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    emailLayout.error = "올바른 이메일을 입력해 주세요."
                displayName.isBlank() -> nameLayout.error = "이름을 입력해 주세요."
                displayName.length > MAX_DISPLAY_NAME_LENGTH ->
                    nameLayout.error = "이름은 ${MAX_DISPLAY_NAME_LENGTH}자 이하로 입력해 주세요."
                password.length < MIN_SIGN_UP_PASSWORD_LENGTH ->
                    passwordLayout.error =
                        "비밀번호는 ${MIN_SIGN_UP_PASSWORD_LENGTH}자 이상 입력해 주세요."
                password != passwordConfirmation ->
                    confirmationLayout.error = "비밀번호가 일치하지 않습니다."
                else -> viewModel.signUpWithEmail(email, displayName, password)
            }
        }
        root.onClick(R.id.btn_go_to_login) { showScreen(Screen.EMAIL_LOGIN) }
    }

    private fun bindHomeActions(root: View) {
        root.onClick(R.id.btn_notifications) { showScreen(Screen.NOTIFICATIONS) }
        root.onClick(R.id.btn_all_recent) { showScreen(Screen.CLIPBOARD_LIST) }
        root.onClick(R.id.btn_send_all) { openSend(ClipboardType.TEXT) }
        root.onClick(R.id.btn_send_text) { openSend(ClipboardType.TEXT) }
        root.onClick(R.id.btn_send_image) { openSend(ClipboardType.IMAGE, openPicker = true) }
        root.onClick(R.id.btn_send_file) { openSend(ClipboardType.FILE, openPicker = true) }
    }

    private fun bindClipboardListActions(root: View) {
        root.onClick(R.id.btn_select_clipboards) {
            clipboardSelectionMode = !clipboardSelectionMode
            if (!clipboardSelectionMode) selectedClipboardIds.clear()
            renderCurrent(viewModel.state.value)
        }
        root.onClick(R.id.btn_select_all_clipboards) {
            val visibleIds = filteredClipboardItems(viewModel.state.value.clipboardItems)
                .map(ClipboardItem::id)
            if (visibleIds.isNotEmpty() && visibleIds.all(selectedClipboardIds::contains)) {
                selectedClipboardIds.removeAll(visibleIds.toSet())
            } else {
                selectedClipboardIds.addAll(visibleIds)
            }
            renderCurrent(viewModel.state.value)
        }
        root.onClick(R.id.filter_all) { updateClipboardFilter(ClipboardFilter.ALL) }
        root.onClick(R.id.filter_text) { updateClipboardFilter(ClipboardFilter.TEXT) }
        root.onClick(R.id.filter_image) { updateClipboardFilter(ClipboardFilter.IMAGE) }
        root.onClick(R.id.filter_file) { updateClipboardFilter(ClipboardFilter.FILE) }
        root.onClick(R.id.btn_delete_selected) {
            val items = viewModel.state.value.clipboardItems
                .filter { it.id in selectedClipboardIds }
            viewModel.deleteItems(items)
            selectedClipboardIds.clear()
            clipboardSelectionMode = false
            renderCurrent(viewModel.state.value)
        }
    }

    private fun bindClipboardDetailActions(root: View) {
        root.onClick(R.id.btn_copy) { viewModel.selectedItem()?.let(::copyTextItem) }
        root.onClick(R.id.btn_download_file) { viewModel.selectedItem()?.let(::downloadItem) }
        root.onClick(R.id.btn_resend) {
            val item = viewModel.selectedItem() ?: return@onClick
            if (item.resolvedType() == ClipboardType.TEXT) {
                sendType = ClipboardType.TEXT
                showScreen(Screen.SEND)
                currentRoot?.findViewById<TextInputEditText>(R.id.input_send_text)
                    ?.setText(item.content)
            } else {
                openSend(item.resolvedType(), openPicker = true)
                toast("보안을 위해 원본 파일을 다시 선택해 주세요.")
            }
        }
    }

    private fun bindSendActions(root: View) {
        root.onClick(R.id.btn_type_text) {
            sendType = ClipboardType.TEXT
            renderCurrent(viewModel.state.value)
        }
        root.onClick(R.id.btn_type_image) {
            sendType = ClipboardType.IMAGE
            openDocument(ClipboardType.IMAGE)
        }
        root.onClick(R.id.btn_type_file) {
            sendType = ClipboardType.FILE
            openDocument(ClipboardType.FILE)
        }
        root.onClick(R.id.card_select_file) { openDocument(sendType) }
        root.onClick(R.id.btn_select_file) { openDocument(sendType) }
        root.onClick(R.id.card_send_target_device) {
            val devices = remoteDevices(viewModel.state.value)
            val currentIndex = devices.indexOfFirst { it.id == viewModel.selectedDevice()?.id }
            val next = devices.getOrNull((currentIndex + 1).coerceAtLeast(0) % devices.size.coerceAtLeast(1))
            viewModel.selectDevice(next?.id)
            renderCurrent(viewModel.state.value)
        }
        root.onClick(R.id.btn_transfer) { submitTransfer() }
    }

    private fun bindDeviceDetailActions(root: View) {
        root.findViewById<SwitchMaterial>(R.id.switch_device_auto_sync)
            .setOnCheckedChangeListener { _, checked -> setAutoSync(checked) }
        root.findViewById<SwitchMaterial>(R.id.switch_device_file_notifications)
            .setOnCheckedChangeListener { _, checked ->
                setIncomingNotifications(checked)
            }
        root.onClick(R.id.btn_disconnect_device) {
            val device = viewModel.selectedDevice() ?: return@onClick
            AlertDialog.Builder(this)
                .setTitle("기기 연결을 해제할까요?")
                .setMessage(
                    "${device.deviceName}에서 로그아웃되고 이후 동기화가 차단됩니다. " +
                        "다시 사용하려면 새 기기로 연결해야 합니다.",
                )
                .setNegativeButton("취소", null)
                .setPositiveButton("연결 해제") { _, _ ->
                    viewModel.removeDevice(device)
                    showScreen(Screen.DEVICES)
                }
                .show()
        }
    }

    private fun bindSettingsActions(root: View) {
        root.findViewById<SwitchMaterial>(R.id.switch_auto_sync)
            .setOnCheckedChangeListener { _, checked -> setAutoSync(checked) }
        root.findViewById<SwitchMaterial>(R.id.switch_wifi_only)
            .setOnCheckedChangeListener { _, checked -> preferences.wifiOnlyFiles = checked }
        root.findViewById<SwitchMaterial>(R.id.switch_receive_notifications)
            .setOnCheckedChangeListener { _, checked ->
                setIncomingNotifications(checked)
            }
        root.onClick(R.id.btn_permissions) { showScreen(Screen.PERMISSIONS) }
        root.onClick(R.id.btn_account) { toast("계정 정보는 아래 로그아웃 버튼에서 관리할 수 있습니다.") }
        root.onClick(R.id.btn_logout) {
            ClipboardSyncService.stop(this)
            preferences.autoSyncEnabled = false
            lifecycleScope.launch {
                runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
            }
            viewModel.signOut()
        }
    }

    private fun bindPermissionActions(root: View) {
        root.findViewById<SwitchMaterial>(R.id.switch_auto_expiry).apply {
            isChecked = true
            isEnabled = false
        }
        root.onClick(R.id.card_permission_notifications) {
            if (shouldRequestNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast("알림 권한이 이미 설정되어 있습니다.")
            }
        }
        root.onClick(R.id.btn_continue_permissions) {
            preferences.hasSeenOnboarding = true
            preferences.permissionsConfigured = true
            preferences.autoSyncEnabled = true
            if (shouldRequestNotificationPermission()) {
                pendingStartSyncAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startClipboardSyncFromUserAction()
            }
            showScreen(Screen.HOME)
        }
    }

    private fun renderCurrent(state: MainUiState) {
        val root = currentRoot ?: return
        when (currentScreen) {
            Screen.ONBOARDING -> Unit
            Screen.LOGIN -> renderLogin(root, state)
            Screen.EMAIL_LOGIN -> renderEmail(root, state)
            Screen.SIGN_UP -> renderSignUp(root, state)
            Screen.HOME -> renderHome(root, state)
            Screen.CLIPBOARD_LIST -> renderClipboardList(root, state)
            Screen.CLIPBOARD_DETAIL -> renderClipboardDetail(root, state)
            Screen.SEND -> renderSend(root, state)
            Screen.TRANSFER_STATUS -> renderTransferStatus(root, state)
            Screen.DEVICES -> renderDevices(root, state)
            Screen.DEVICE_DETAIL -> renderDeviceDetail(root, state)
            Screen.CONNECT_DEVICE -> renderConnectDevice(root, state)
            Screen.NOTIFICATIONS -> renderNotifications(root, state)
            Screen.SETTINGS -> renderSettings(root, state)
            Screen.PERMISSIONS -> renderPermissions(root)
        }
    }

    private fun renderLogin(root: View, state: MainUiState) {
        root.findViewById<ProgressBar>(R.id.progress_login).isVisible = state.isBusy
        root.findViewById<View>(R.id.btn_login).isEnabled = !state.isBusy
        root.findViewById<View>(R.id.btn_login_email).isEnabled = !state.isBusy
        root.findViewById<View>(R.id.btn_sign_up).isEnabled = !state.isBusy
    }

    private fun renderEmail(root: View, state: MainUiState) {
        root.findViewById<ProgressBar>(R.id.progress_auth).isVisible = state.isBusy
        root.findViewById<View>(R.id.btn_email_continue).isEnabled = !state.isBusy
        root.findViewById<View>(R.id.btn_sign_up).isEnabled = !state.isBusy
    }

    private fun renderSignUp(root: View, state: MainUiState) {
        root.findViewById<ProgressBar>(R.id.progress_signup).isVisible = state.isBusy
        root.findViewById<View>(R.id.btn_create_account).isEnabled = !state.isBusy
        root.findViewById<View>(R.id.btn_go_to_login).isEnabled = !state.isBusy
    }

    private fun renderHome(root: View, state: MainUiState) {
        val name = displayName(state)
        root.setText(R.id.tv_home_avatar, name.firstOrNull()?.uppercase() ?: "A")
        root.setText(R.id.tv_home_account_name, name)
        root.setText(R.id.tv_home_greeting, "안녕하세요, ${name}님")

        val remote = remoteDevices(state)
        val onlineCount = remote.count(::isDeviceOnline)
        root.setText(
            R.id.tv_home_device_summary,
            if (remote.isEmpty()) "연결된 다른 기기가 없어요" else "${remote.size}대의 기기가 연결되어 있어요",
        )
        root.setText(
            R.id.tv_home_last_sync,
            if (remote.isEmpty()) "기기 연결 화면에서 연결 방법을 확인하세요"
            else "현재 ${onlineCount}대 온라인 · ${lastItemTime(state.clipboardItems)}",
        )
        root.setText(R.id.tv_home_sync_status, syncStatusText())
        val hasOnlineDevice = onlineCount > 0
        root.findViewById<View>(R.id.card_home_device_status).setBackgroundResource(
            if (hasOnlineDevice) R.drawable.bg_card_success else R.drawable.bg_card_stroke,
        )
        root.findViewById<View>(R.id.view_home_sync_indicator).isVisible = hasOnlineDevice
        root.findViewById<ImageView>(R.id.iv_home_device_status).isVisible = hasOnlineDevice
        root.findViewById<TextView>(R.id.tv_home_device_summary).setTextColor(
            ContextCompat.getColor(this, if (hasOnlineDevice) R.color.success_foreground else R.color.ink),
        )
        root.findViewById<ProgressBar>(R.id.progress_home_recent).isVisible = !state.authResolved
        root.findViewById<View>(R.id.tv_home_recent_empty).isVisible =
            state.authResolved && state.clipboardItems.isEmpty()
        bindClipboardCards(root, state.clipboardItems.take(3), selectable = false)
    }

    private fun renderClipboardList(root: View, state: MainUiState) {
        val availableIds = state.clipboardItems.mapTo(hashSetOf(), ClipboardItem::id)
        selectedClipboardIds.retainAll(availableIds)
        val items = filteredClipboardItems(state.clipboardItems)
        val selectedVisibleCount = items.count { it.id in selectedClipboardIds }
        val allVisibleSelected = items.isNotEmpty() && selectedVisibleCount == items.size

        root.findViewById<ProgressBar>(R.id.progress_clipboards).isVisible = !state.authResolved
        root.findViewById<View>(R.id.tv_clipboards_empty).isVisible =
            state.authResolved && items.isEmpty()
        root.findViewById<TextView>(R.id.btn_select_clipboards).text =
            if (clipboardSelectionMode) "완료" else "선택"
        root.findViewById<View>(R.id.layout_clipboard_selection_actions).isVisible =
            clipboardSelectionMode
        root.setText(R.id.tv_selected_clipboard_count, "${selectedVisibleCount}개 선택")
        root.findViewById<TextView>(R.id.btn_select_all_clipboards).apply {
            isEnabled = items.isNotEmpty()
            text = if (allVisibleSelected) "전체 해제" else "전체 선택"
        }
        root.findViewById<View>(R.id.btn_delete_selected).apply {
            isVisible = clipboardSelectionMode
            isEnabled = selectedVisibleCount > 0
        }
        root.setText(
            R.id.btn_delete_selected,
            if (selectedVisibleCount > 0) "선택 항목 삭제 ($selectedVisibleCount)" else "선택 항목 삭제",
        )
        renderFilterButtons(root)
        renderClipboardListItems(root, items)
    }

    private fun renderClipboardDetail(root: View, state: MainUiState) {
        val item = viewModel.selectedItem()
        root.findViewById<ProgressBar>(R.id.progress_clipboard_detail).isVisible = item == null
        root.findViewById<View>(R.id.layout_clipboard_detail_content).isVisible = item != null
        if (item == null) return

        val typeLabel = typeLabel(item)
        root.setText(R.id.tv_detail_type, typeLabel)
        root.findViewById<ImageView>(R.id.iv_detail_type).setImageResource(typeIcon(item))
        root.setText(R.id.tv_detail_title, itemTitle(item))
        root.setText(R.id.tv_detail_meta, itemMeta(item, state))
        root.setText(
            R.id.tv_detail_content,
            if (item.resolvedType() == ClipboardType.TEXT) item.content
            else item.fileName.ifBlank { typeLabel },
        )
        val source = state.devices.firstOrNull { it.id == item.sourceDeviceId }
        root.setText(R.id.tv_detail_source_name, source?.deviceName ?: "알 수 없는 기기")
        root.setText(
            R.id.tv_detail_source_status,
            if (source?.let(::isDeviceOnline) == true) "발신 기기 · 온라인" else "발신 기기 · 오프라인",
        )
        root.setText(
            R.id.tv_detail_expiry,
            item.expiresAt?.toDate()?.let { "${dateTimeFormat().format(it)}에 자동 만료됩니다." }
                ?: "자동 만료 정보가 없습니다.",
        )
        root.setText(R.id.tv_detail_expiry_badge, "24시간")

        val isText = item.resolvedType() == ClipboardType.TEXT
        root.findViewById<View>(R.id.btn_copy).isVisible = isText
        root.findViewById<View>(R.id.btn_download_file).isVisible = !isText
        root.findViewById<ImageView>(R.id.iv_detail_preview).isVisible =
            item.resolvedType() == ClipboardType.IMAGE
        if (item.resolvedType() == ClipboardType.IMAGE && previewRequestedItemId != item.id) {
            previewRequestedItemId = item.id
            viewModel.loadImagePreview(item)
        }
        viewModel.markRead(item)
    }

    private fun renderSend(root: View, state: MainUiState) {
        val isText = sendType == ClipboardType.TEXT
        root.findViewById<View>(R.id.layout_send_text).isVisible = isText
        root.findViewById<View>(R.id.card_select_file).isVisible = !isText
        root.findViewById<ImageView>(R.id.iv_send_image_preview).isVisible =
            sendType == ClipboardType.IMAGE && selectedFile != null
        if (sendType == ClipboardType.IMAGE) {
            selectedFile?.let { file ->
                if (localPreviewBitmapUri == file.uri) {
                    localPreviewBitmap?.let {
                        root.findViewById<ImageView>(R.id.iv_send_image_preview).setImageBitmap(it)
                    }
                } else {
                    loadLocalImagePreview(file)
                }
            }
        }
        root.setText(R.id.tv_send_file_name, selectedFile?.name ?: "파일을 선택해 주세요")
        root.setText(
            R.id.tv_send_file_info,
            selectedFile?.let { "${it.mimeType} · ${formatSize(it.size)}" }
                ?: "이미지 또는 파일 탐색기에서 선택",
        )
        renderSendType(root, R.id.btn_type_text, sendType == ClipboardType.TEXT)
        renderSendType(root, R.id.btn_type_image, sendType == ClipboardType.IMAGE)
        renderSendType(root, R.id.btn_type_file, sendType == ClipboardType.FILE)

        val remoteDevices = remoteDevices(state)
        val remote = viewModel.selectedDevice()
            ?.takeIf { selected -> remoteDevices.any { it.id == selected.id } }
            ?: remoteDevices.firstOrNull()
        root.findViewById<View>(R.id.card_send_target_device).isVisible = remote != null
        root.findViewById<View>(R.id.tv_send_device_empty).isVisible = remote == null
        remote?.let { device ->
            root.setText(R.id.tv_send_target_name, device.deviceName)
            root.setText(
                R.id.tv_send_target_status,
                if (isDeviceOnline(device)) "온라인 · 즉시 동기화 가능" else "오프라인 · 연결 시 동기화",
            )
            root.findViewById<ImageView>(R.id.iv_send_target_device)
                .setImageResource(deviceIcon(device))
        }
        root.findViewById<ProgressBar>(R.id.progress_send).isVisible = state.isBusy
        root.findViewById<View>(R.id.btn_transfer).isEnabled = !state.isBusy
        root.setText(
            R.id.btn_transfer,
            remote?.let { "${it.deviceName}에 보내기" } ?: "내 계정에 저장하기",
        )
    }

    private fun renderTransferStatus(root: View, state: MainUiState) {
        transferCompleted = state.transferStatus == TransferStatus.SUCCEEDED
        transferFailed = state.transferStatus == TransferStatus.FAILED
        val progress = if (transferCompleted) 100 else state.transferProgress ?: 0
        val progressView = root.findViewById<ProgressBar>(R.id.progress_transfer).apply {
            isIndeterminate = state.isBusy && state.transferProgress == null
            max = 100
            setProgress(progress, true)
        }
        root.setText(R.id.tv_transfer_percent, "$progress%")
        root.setText(
            R.id.tv_transfer_item_title,
            state.transferTitle.ifBlank { lastTransferTitle.ifBlank { "전송 항목" } },
        )
        root.setText(R.id.tv_transfer_item_meta, state.transferMeta.ifBlank { lastTransferMeta })
        root.findViewById<ImageView>(R.id.iv_transfer_type).setImageResource(
            when (state.transferType) {
                ClipboardType.TEXT -> R.drawable.ic_text
                ClipboardType.IMAGE -> R.drawable.ic_image
                ClipboardType.FILE -> R.drawable.ic_file
            },
        )

        when {
            transferCompleted -> {
                root.setText(R.id.tv_transfer_state_icon, "✓")
                root.setText(R.id.tv_transfer_title, "전송이 완료됐어요")
                root.setText(R.id.tv_transfer_message, "연결된 기기에서 바로 확인할 수 있습니다.")
                root.setText(R.id.tv_transfer_network_status, "Firebase 동기화 완료")
                applyTransferTone(
                    root = root,
                    progressView = progressView,
                    stateBackground = R.drawable.bg_circle_success,
                    progressColor = R.color.success_foreground,
                    progressTrackColor = R.color.success_container,
                    noticeBackground = R.drawable.bg_card_success,
                    noticeTextColor = R.color.success_foreground,
                    showNoticeIcon = true,
                    showNotice = true,
                )
            }
            transferFailed -> {
                root.setText(R.id.tv_transfer_state_icon, "!")
                root.setText(R.id.tv_transfer_title, "전송하지 못했어요")
                root.setText(R.id.tv_transfer_message, "네트워크와 Firebase 설정을 확인해 주세요.")
                root.setText(R.id.tv_transfer_network_status, "재시도할 수 있습니다")
                applyTransferTone(
                    root = root,
                    progressView = progressView,
                    stateBackground = R.drawable.bg_circle_danger,
                    progressColor = R.color.danger_foreground,
                    progressTrackColor = R.color.danger_container,
                    noticeBackground = R.drawable.bg_card_success,
                    noticeTextColor = R.color.danger_foreground,
                    showNoticeIcon = false,
                    showNotice = false,
                )
            }
            else -> {
                root.setText(R.id.tv_transfer_state_icon, "↗")
                root.setText(R.id.tv_transfer_title, "연결된 기기로 보내는 중")
                root.setText(R.id.tv_transfer_message, "앱을 열어 둔 상태에서 전송을 완료해 주세요.")
                root.setText(R.id.tv_transfer_network_status, "보안 연결로 업로드 중")
                applyTransferTone(
                    root = root,
                    progressView = progressView,
                    stateBackground = R.drawable.bg_circle_primary,
                    progressColor = R.color.primary_contrast,
                    progressTrackColor = R.color.primary_container,
                    noticeBackground = R.drawable.bg_card_primary,
                    noticeTextColor = R.color.on_primary_container,
                    showNoticeIcon = false,
                    showNotice = true,
                )
            }
        }
        val failureReason = state.transferFailureReason.orEmpty()
        root.findViewById<View>(R.id.tv_transfer_failure_reason).isVisible =
            transferFailed && failureReason.isNotBlank()
        root.setText(R.id.tv_transfer_failure_reason, failureReason)
        val retryQueue = state.failedTransfers
        root.findViewById<View>(R.id.tv_transfer_retry_queue).isVisible = retryQueue.isNotEmpty()
        root.setText(
            R.id.tv_transfer_retry_queue,
            if (retryQueue.isEmpty()) "" else "재전송 대기 ${retryQueue.size}건 · ${retryQueue.last().title}",
        )
        root.findViewById<TextView>(R.id.btn_retry_transfer).apply {
            isVisible = transferFailed && retryQueue.isNotEmpty()
            text = if (retryQueue.size > 1) "다시 시도 (${retryQueue.size})" else "다시 시도"
        }
    }

    private fun applyTransferTone(
        root: View,
        progressView: ProgressBar,
        @DrawableRes stateBackground: Int,
        progressColor: Int,
        progressTrackColor: Int,
        @DrawableRes noticeBackground: Int,
        noticeTextColor: Int,
        showNoticeIcon: Boolean,
        showNotice: Boolean,
    ) {
        root.findViewById<View>(R.id.tv_transfer_state_icon).setBackgroundResource(stateBackground)
        val progressTint = ColorStateList.valueOf(ContextCompat.getColor(this, progressColor))
        progressView.progressTintList = progressTint
        progressView.indeterminateTintList = progressTint
        progressView.progressBackgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, progressTrackColor))
        root.findViewById<TextView>(R.id.tv_transfer_percent).setTextColor(
            ContextCompat.getColor(this, progressColor),
        )
        root.findViewById<View>(R.id.card_transfer_notice).apply {
            isVisible = showNotice
            setBackgroundResource(noticeBackground)
        }
        root.findViewById<ImageView>(R.id.iv_transfer_notice).isVisible = showNoticeIcon
        root.findViewById<TextView>(R.id.tv_transfer_notice).setTextColor(
            ContextCompat.getColor(this, noticeTextColor),
        )
    }

    private fun renderDevices(root: View, state: MainUiState) {
        val devices = state.devices.sortedBy { it.id != viewModel.currentDeviceId() }
        val online = state.devices.count(::isDeviceOnline)
        root.setText(
            R.id.tv_devices_summary,
            if (state.devices.isEmpty()) "연결된 기기가 없습니다"
            else "${state.devices.size}대 중 ${online}대가 현재 온라인이에요",
        )
        root.setText(R.id.tv_devices_count, "연결된 기기 ${state.devices.size}")
        val hasOnlineDevice = online > 0
        root.findViewById<View>(R.id.card_devices_summary).setBackgroundResource(
            if (hasOnlineDevice) R.drawable.bg_card_success else R.drawable.bg_card_stroke,
        )
        root.findViewById<View>(R.id.view_devices_summary_indicator).isVisible = hasOnlineDevice
        root.findViewById<ImageView>(R.id.iv_devices_summary_status).isVisible = hasOnlineDevice
        root.findViewById<TextView>(R.id.tv_devices_summary).setTextColor(
            ContextCompat.getColor(this, if (hasOnlineDevice) R.color.success_foreground else R.color.ink),
        )
        root.findViewById<ProgressBar>(R.id.progress_devices).isVisible = !state.authResolved
        root.findViewById<View>(R.id.tv_devices_empty).isVisible =
            state.authResolved && state.devices.isEmpty()
        renderDeviceListItems(root, devices)
    }

    private fun renderDeviceDetail(root: View, state: MainUiState) {
        val device = viewModel.selectedDevice()
        root.findViewById<ProgressBar>(R.id.progress_device_detail).isVisible = device == null
        root.findViewById<View>(R.id.layout_device_detail_content).isVisible = device != null
        if (device == null) return
        val online = isDeviceOnline(device)
        root.findViewById<ImageView>(R.id.iv_device_detail_type).apply {
            setImageResource(deviceIcon(device))
            setBackgroundResource(
                if (device.resolvedPlatform() == DevicePlatform.MACOS) {
                    R.drawable.bg_icon_purple
                } else {
                    R.drawable.bg_icon_primary
                },
            )
        }
        root.setText(R.id.tv_device_detail_name, device.deviceName)
        root.setText(R.id.tv_device_detail_status, if (online) "온라인 · 동기화 가능" else "오프라인")
        root.findViewById<View>(R.id.badge_device_detail_status).setBackgroundResource(
            if (online) R.drawable.bg_card_success else R.drawable.bg_card_stroke,
        )
        root.findViewById<View>(R.id.view_device_detail_status).setBackgroundResource(
            if (online) R.drawable.bg_circle_success else R.drawable.bg_circle_neutral,
        )
        root.findViewById<TextView>(R.id.tv_device_detail_status).setTextColor(
            ContextCompat.getColor(this, if (online) R.color.success_foreground else R.color.ink_secondary),
        )
        root.setText(
            R.id.tv_device_detail_platform,
            if (device.resolvedPlatform() == DevicePlatform.MACOS) "macOS" else "Android",
        )
        root.setText(R.id.tv_device_detail_last_sync, relativeTime(device.lastSeenAt?.toDate()))
        root.setText(
            R.id.tv_device_detail_connected_at,
            device.lastSeenAt?.toDate()?.let { DateFormat.getDateInstance().format(it) } ?: "정보 없음",
        )
        root.findViewById<SwitchMaterial>(R.id.switch_device_auto_sync).setCheckedSilently(
            preferences.autoSyncEnabled,
        ) { checked -> setAutoSync(checked) }
        root.findViewById<SwitchMaterial>(R.id.switch_device_file_notifications).setCheckedSilently(
            preferences.incomingNotificationsEnabled,
        ) { checked -> setIncomingNotifications(checked) }
        root.findViewById<View>(R.id.btn_disconnect_device).isVisible =
            device.id != viewModel.currentDeviceId()
    }

    private fun renderConnectDevice(root: View, state: MainUiState) {
        val remote = remoteDevices(state)
        root.findViewById<ProgressBar>(R.id.progress_device_discovery).isVisible = remote.isEmpty()
        root.setText(
            R.id.tv_device_discovery_status,
            if (remote.isEmpty()) "같은 계정으로 로그인한 기기를 기다리는 중입니다…"
            else "${remote.joinToString { it.deviceName }} 연결됨",
        )
    }

    private fun renderNotifications(root: View, state: MainUiState) {
        val items = state.clipboardItems
            .filter { it.sourceDeviceId != viewModel.currentDeviceId() }
        val unreadCount = items.count { !it.isReadBy(viewModel.currentDeviceId()) }
        root.findViewById<ProgressBar>(R.id.progress_notifications).isVisible = !state.authResolved
        root.findViewById<View>(R.id.tv_notifications_empty).isVisible =
            state.authResolved && items.isEmpty()
        root.findViewById<View>(R.id.btn_mark_all_read).isEnabled = unreadCount > 0
        renderNotificationListItems(root, items, state)
    }

    private fun renderSettings(root: View, state: MainUiState) {
        val name = displayName(state)
        root.setText(R.id.tv_settings_avatar, name.firstOrNull()?.uppercase() ?: "A")
        root.setText(R.id.tv_settings_account_name, name)
        root.setText(R.id.tv_settings_account_email, state.user?.email ?: "Google 계정")
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        root.setText(R.id.tv_app_version, "AnyPaste $versionName")
        root.findViewById<SwitchMaterial>(R.id.switch_auto_sync).setCheckedSilently(
            preferences.autoSyncEnabled,
        ) { checked -> setAutoSync(checked) }
        root.findViewById<SwitchMaterial>(R.id.switch_wifi_only).setCheckedSilently(
            preferences.wifiOnlyFiles,
        ) { checked -> preferences.wifiOnlyFiles = checked }
        root.findViewById<SwitchMaterial>(R.id.switch_receive_notifications).setCheckedSilently(
            preferences.incomingNotificationsEnabled,
        ) { checked -> setIncomingNotifications(checked) }
        val guidance = ClipboardSyncService.backgroundSyncGuidance(this)
            ?: preferences.backgroundSyncNotice.takeIf(String::isNotBlank)
        root.findViewById<View>(R.id.tv_background_sync_guidance).isVisible = guidance != null
        root.setText(R.id.tv_background_sync_guidance, guidance.orEmpty())
    }

    private fun renderPermissions(root: View) {
        val notificationGranted = !shouldRequestNotificationPermission()
        root.setText(
            R.id.tv_notification_permission_status,
            if (notificationGranted) "허용됨" else "필요",
        )
        root.setText(
            R.id.tv_clipboard_permission_status,
            if (ClipboardSyncService.isRunning) "동기화 실행 중" else "앱 실행 중 사용 가능",
        )
        root.findViewById<SwitchMaterial>(R.id.switch_auto_expiry).apply {
            isChecked = true
            isEnabled = false
        }
    }

    private fun bindClipboardCards(root: View, items: List<ClipboardItem>, selectable: Boolean) {
        CLIPBOARD_CARD_IDS.forEachIndexed { index, ids ->
            val container = root.findOptional<View>(ids.container) ?: return@forEachIndexed
            val item = items.getOrNull(index)
            container.isVisible = item != null
            if (item == null) return@forEachIndexed
            root.findOptional<ImageView>(ids.icon)?.setImageResource(typeIcon(item))
            root.setText(ids.title, itemTitle(item))
            root.setText(ids.meta, itemMeta(item, viewModel.state.value))
            root.findOptional<CheckBox>(ids.check)?.apply {
                isVisible = selectable
                isChecked = item.id in selectedClipboardIds
                setOnClickListener { toggleClipboardSelection(item.id) }
            }
            root.findOptional<View>(ids.arrow)?.isVisible = !selectable
            container.setOnClickListener {
                if (selectable) {
                    toggleClipboardSelection(item.id)
                } else {
                    viewModel.selectItem(item.id)
                    showScreen(Screen.CLIPBOARD_DETAIL)
                }
            }
        }
    }

    private fun renderClipboardListItems(root: View, items: List<ClipboardItem>) {
        val listContainer = root.findViewById<LinearLayout>(R.id.layout_clipboard_items)
        listContainer.removeAllViews()

        items.forEach { item ->
            val itemView = layoutInflater.inflate(R.layout.item_clipboard, listContainer, false)
            val selected = item.id in selectedClipboardIds
            val type = item.resolvedType()

            itemView.setBackgroundResource(
                if (selected) R.drawable.bg_card_primary else R.drawable.bg_card,
            )
            itemView.findViewById<ImageView>(R.id.iv_clipboard_type).apply {
                setImageResource(typeIcon(item))
                setBackgroundResource(
                    when (type) {
                        ClipboardType.TEXT -> R.drawable.bg_icon_primary
                        ClipboardType.IMAGE -> R.drawable.bg_icon_teal
                        ClipboardType.FILE -> R.drawable.bg_icon_purple
                    },
                )
                contentDescription = typeLabel(type)
            }
            itemView.findViewById<TextView>(R.id.tv_clipboard_title).text = itemTitle(item)
            itemView.findViewById<TextView>(R.id.tv_clipboard_meta).text =
                itemMeta(item, viewModel.state.value)
            itemView.findViewById<ImageView>(R.id.iv_clipboard_arrow).isVisible =
                !clipboardSelectionMode
            itemView.findViewById<CheckBox>(R.id.check_clipboard).apply {
                isVisible = clipboardSelectionMode
                isChecked = selected
                contentDescription = "${itemTitle(item)} 선택"
                setOnClickListener { toggleClipboardSelection(item.id) }
            }
            itemView.setOnClickListener {
                if (clipboardSelectionMode) {
                    toggleClipboardSelection(item.id)
                } else {
                    viewModel.selectItem(item.id)
                    showScreen(Screen.CLIPBOARD_DETAIL)
                }
            }
            listContainer.addView(itemView)
        }
    }

    private fun renderDeviceListItems(root: View, devices: List<Device>) {
        val listContainer = root.findViewById<LinearLayout>(R.id.layout_device_items)
        listContainer.removeAllViews()

        devices.forEach { device ->
            val itemView = layoutInflater.inflate(R.layout.item_device, listContainer, false)
            val current = device.id == viewModel.currentDeviceId()
            val online = isDeviceOnline(device)

            itemView.findViewById<ImageView>(R.id.iv_device_type).apply {
                setImageResource(deviceIcon(device))
                setBackgroundResource(
                    if (device.resolvedPlatform() == DevicePlatform.MACOS) {
                        R.drawable.bg_icon_purple
                    } else {
                        R.drawable.bg_icon_primary
                    },
                )
                contentDescription = platformLabel(device)
            }
            itemView.findViewById<TextView>(R.id.tv_device_name).text = device.deviceName
            itemView.findViewById<TextView>(R.id.tv_device_meta).text =
                if (current) "이 기기 · Android"
                else "${platformLabel(device)} · 마지막 동기화 ${relativeTime(device.lastSeenAt?.toDate())}"
            itemView.findViewById<View>(R.id.view_device_status).isVisible = online
            itemView.findViewById<TextView>(R.id.tv_device_status).apply {
                text = if (online) "온라인" else "오프라인"
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (online) R.color.success_foreground else R.color.ink_secondary,
                    ),
                )
            }
            itemView.setOnClickListener {
                viewModel.selectDevice(device.id)
                showScreen(Screen.DEVICE_DETAIL)
            }
            listContainer.addView(itemView)
        }
    }

    private fun renderNotificationListItems(
        root: View,
        items: List<ClipboardItem>,
        state: MainUiState,
    ) {
        val listContainer = root.findViewById<LinearLayout>(R.id.layout_notification_items)
        listContainer.removeAllViews()

        items.forEach { item ->
            val itemView = layoutInflater.inflate(R.layout.item_notification, listContainer, false)
            val unread = !item.isReadBy(viewModel.currentDeviceId())
            val type = item.resolvedType()

            itemView.setBackgroundResource(
                if (unread) R.drawable.bg_card else R.drawable.bg_card_stroke,
            )
            itemView.findViewById<ImageView>(R.id.iv_notification_type).apply {
                setImageResource(typeIcon(item))
                setBackgroundResource(
                    when (type) {
                        ClipboardType.TEXT -> R.drawable.bg_icon_primary
                        ClipboardType.IMAGE -> R.drawable.bg_icon_teal
                        ClipboardType.FILE -> R.drawable.bg_icon_purple
                    },
                )
                contentDescription = "${typeLabel(type)} 수신"
            }
            itemView.findViewById<TextView>(R.id.tv_notification_title).text =
                "새 ${typeLabel(item)}를 받았어요"
            itemView.findViewById<TextView>(R.id.tv_notification_body).text = itemTitle(item)
            itemView.findViewById<TextView>(R.id.tv_notification_meta).text = itemMeta(item, state)
            itemView.findViewById<View>(R.id.view_notification_unread).isVisible = unread
            itemView.setOnClickListener {
                viewModel.selectItem(item.id)
                viewModel.markRead(item)
                showScreen(Screen.CLIPBOARD_DETAIL)
            }
            listContainer.addView(itemView)
        }
    }

    private fun submitTransfer() {
        if (preferences.wifiOnlyFiles && sendType != ClipboardType.TEXT && !isOnWifi()) {
            showSendError("Wi-Fi 전용 파일 전송이 켜져 있습니다.")
            return
        }
        transferCompleted = false
        transferFailed = false
        val targetDeviceId = viewModel.selectedDevice()?.id
            ?.takeIf { selectedId -> remoteDevices(viewModel.state.value).any { it.id == selectedId } }
            ?: remoteDevices(viewModel.state.value).firstOrNull()?.id.orEmpty()
        when (sendType) {
            ClipboardType.TEXT -> {
                val input = currentRoot?.findViewById<TextInputEditText>(R.id.input_send_text)
                    ?.text?.toString()?.trim().orEmpty()
                if (input.isBlank()) {
                    showSendError("보낼 텍스트를 입력해 주세요.")
                    return
                }
                lastTransferTitle = input.lineSequence().firstOrNull().orEmpty().take(60)
                lastTransferMeta = "텍스트 · ${input.toByteArray().size} B"
                viewModel.sendText(input, targetDeviceId)
            }
            ClipboardType.IMAGE, ClipboardType.FILE -> {
                val file = selectedFile
                if (file == null) {
                    showSendError("보낼 파일을 선택해 주세요.")
                    return
                }
                lastTransferTitle = file.name
                lastTransferMeta = "${typeLabel(sendType)} · ${formatSize(file.size)}"
                viewModel.sendFile(
                    file.uri,
                    file.name,
                    file.mimeType,
                    file.size,
                    targetDeviceId,
                )
            }
        }
        showScreen(Screen.TRANSFER_STATUS)
    }

    private fun retryTransfer() {
        transferFailed = false
        viewModel.retryLastFailedTransfer()
        renderCurrent(viewModel.state.value)
    }

    private fun handleUiEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.Message -> {
                if (currentScreen == Screen.TRANSFER_STATUS && !transferCompleted) {
                    transferFailed = true
                    renderCurrent(viewModel.state.value)
                }
                currentRoot?.findOptional<TextView>(R.id.tv_auth_error)?.apply {
                    text = event.text
                    isVisible = true
                }
                currentRoot?.findOptional<TextView>(R.id.tv_signup_error)?.apply {
                    text = event.text
                    isVisible = true
                }
                currentRoot?.findOptional<TextView>(R.id.tv_send_error)?.apply {
                    text = event.text
                    isVisible = true
                }
                toast(event.text)
            }
            is MainUiEvent.DownloadReady -> openDownloadedFile(event.file, event.mimeType)
            is MainUiEvent.ImagePreviewReady -> {
                if (viewModel.selectedItem()?.id == event.itemId && currentScreen == Screen.CLIPBOARD_DETAIL) {
                    decodeSampledBitmap(event.bytes)?.let { bitmap ->
                        currentRoot?.findViewById<ImageView>(R.id.iv_detail_preview)
                            ?.setImageBitmap(bitmap)
                    }
                }
            }
            MainUiEvent.TransferCompleted -> {
                transferCompleted = true
                transferFailed = false
                showScreen(Screen.TRANSFER_STATUS, force = currentScreen != Screen.TRANSFER_STATUS)
            }
        }
    }

    private fun beginGoogleSignIn() {
        val clientIdResource = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (clientIdResource == 0) {
            toast("Firebase Console에서 Google 로그인과 Web OAuth 클라이언트를 먼저 설정해 주세요.")
            return
        }
        val serverClientId = getString(clientIdResource)
        lifecycleScope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(serverClientId)
                    .setAutoSelectEnabled(true)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    viewModel.signInWithGoogleIdToken(googleCredential.idToken)
                } else {
                    toast("Google 계정 정보를 확인할 수 없습니다.")
                }
            } catch (_: GetCredentialException) {
                toast("Google 로그인이 취소되었거나 계정을 선택할 수 없습니다.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                toast("Google 로그인 설정을 확인해 주세요.")
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(AnyPasteNotificationManager.EXTRA_ITEM_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { itemId ->
                viewModel.selectItem(itemId)
                if (viewModel.state.value.user != null) showScreen(Screen.CLIPBOARD_DETAIL)
            }

        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            )?.firstOrNull()
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        when {
            stream != null -> {
                selectedFile = readSelectedFile(stream)
                sendType = if (selectedFile?.mimeType?.startsWith("image/") == true) {
                    ClipboardType.IMAGE
                } else {
                    ClipboardType.FILE
                }
            }
            sharedText != null -> sendType = ClipboardType.TEXT
            else -> return
        }

        if (viewModel.state.value.user != null) {
            showScreen(Screen.SEND)
            sharedText?.let {
                currentRoot?.findViewById<TextInputEditText>(R.id.input_send_text)?.setText(it)
            }
        } else {
            pendingSharedText = sharedText
            toast("로그인 후 공유 항목을 전송할 수 있습니다.")
            showScreen(Screen.LOGIN)
        }
        intent.action = null
    }

    private fun hasPendingShare(): Boolean = pendingSharedText != null || selectedFile != null

    private fun openSend(type: ClipboardType, openPicker: Boolean = false) {
        sendType = type
        if (type == ClipboardType.TEXT) selectedFile = null
        showScreen(Screen.SEND)
        pendingSharedText?.let { text ->
            currentRoot?.findViewById<TextInputEditText>(R.id.input_send_text)?.setText(text)
            pendingSharedText = null
        }
        if (openPicker) openDocument(type)
    }

    private fun openDocument(type: ClipboardType) {
        if (type == ClipboardType.TEXT) return
        pendingPickerType = type
        documentPicker.launch(if (type == ClipboardType.IMAGE) arrayOf("image/*") else arrayOf("*/*"))
    }

    private fun downloadItem(item: ClipboardItem) {
        if (preferences.wifiOnlyFiles && !isOnWifi()) {
            toast("Wi-Fi 전용 파일 전송이 켜져 있습니다.")
            return
        }
        val safeName = item.fileName.ifBlank { "anypaste_${item.id}" }
            .replace(Regex("[^a-zA-Z0-9._가-힣-]"), "_")
            .take(180)
        val destination = File(File(cacheDir, "received"), safeName)
        if (destination.exists()) destination.delete()
        viewModel.download(item, destination)
    }

    private fun openDownloadedFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "파일 열기")) }
            .onFailure { toast("이 파일을 열 수 있는 앱이 없습니다.") }
    }

    private fun copyTextItem(item: ClipboardItem) {
        if (item.resolvedType() != ClipboardType.TEXT || item.content.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AnyPaste", item.content))
        toast("클립보드에 복사했습니다.")
    }

    private fun setAutoSync(enabled: Boolean) {
        preferences.autoSyncEnabled = enabled
        if (enabled) {
            if (shouldRequestNotificationPermission()) {
                pendingStartSyncAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startClipboardSyncFromUserAction()
            }
        } else {
            ClipboardSyncService.stop(this)
        }
        renderCurrent(viewModel.state.value)
    }

    private fun startClipboardSyncFromUserAction(showRecoveryMessage: Boolean = true) {
        if (viewModel.state.value.user == null) {
            preferences.autoSyncEnabled = false
            toast("로그인 후 동기화를 켜 주세요.")
            return
        }
        try {
            ClipboardSyncService.start(this)
            if (showRecoveryMessage) {
                ClipboardSyncService.backgroundSyncGuidance(this)?.let(::toast)
            }
        } catch (_: RuntimeException) {
            preferences.autoSyncEnabled = false
            preferences.backgroundSyncNotice =
                "Android가 백그라운드에서 동기화 시작을 제한했습니다. 앱을 연 뒤 다시 켜 주세요."
            toast(preferences.backgroundSyncNotice)
        }
    }

    private fun setIncomingNotifications(enabled: Boolean) {
        preferences.incomingNotificationsEnabled = enabled
        if (enabled && shouldRequestNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun shouldShowPermissionScreen(): Boolean = !preferences.permissionsConfigured

    private fun shouldRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun navigateBack() {
        when (currentScreen) {
            Screen.HOME, Screen.LOGIN -> finish()
            Screen.EMAIL_LOGIN, Screen.SIGN_UP, Screen.ONBOARDING -> showScreen(Screen.LOGIN)
            Screen.CLIPBOARD_DETAIL, Screen.CLIPBOARD_LIST, Screen.SEND,
            Screen.TRANSFER_STATUS, Screen.NOTIFICATIONS, Screen.PERMISSIONS ->
                showScreen(if (viewModel.state.value.user == null) Screen.LOGIN else Screen.HOME)
            Screen.DEVICE_DETAIL, Screen.CONNECT_DEVICE -> showScreen(Screen.DEVICES)
            Screen.DEVICES, Screen.SETTINGS -> showScreen(Screen.HOME)
        }
    }

    private fun updateClipboardFilter(filter: ClipboardFilter) {
        clipboardFilter = filter
        selectedClipboardIds.clear()
        renderCurrent(viewModel.state.value)
    }

    private fun toggleClipboardSelection(itemId: String) {
        if (!selectedClipboardIds.add(itemId)) selectedClipboardIds.remove(itemId)
        renderCurrent(viewModel.state.value)
    }

    private fun filteredClipboardItems(items: List<ClipboardItem>): List<ClipboardItem> =
        when (clipboardFilter) {
            ClipboardFilter.ALL -> items
            ClipboardFilter.TEXT -> items.filter { it.resolvedType() == ClipboardType.TEXT }
            ClipboardFilter.IMAGE -> items.filter { it.resolvedType() == ClipboardType.IMAGE }
            ClipboardFilter.FILE -> items.filter { it.resolvedType() == ClipboardType.FILE }
        }

    private fun renderFilterButtons(root: View) {
        listOf(
            R.id.filter_all to ClipboardFilter.ALL,
            R.id.filter_text to ClipboardFilter.TEXT,
            R.id.filter_image to ClipboardFilter.IMAGE,
            R.id.filter_file to ClipboardFilter.FILE,
        ).forEach { (id, filter) ->
            root.findViewById<TextView>(id).apply {
                isSelected = filter == clipboardFilter
                ViewCompat.setStateDescription(this, if (isSelected) "선택됨" else null)
            }
        }
    }

    private fun renderSendType(root: View, @IdRes cardId: Int, selected: Boolean) {
        root.findViewById<View>(cardId).apply {
            isSelected = selected
            ViewCompat.setStateDescription(this, if (isSelected) "선택됨" else null)
        }
    }

    private fun showSendError(message: String) {
        currentRoot?.findViewById<TextView>(R.id.tv_send_error)?.apply {
            text = message
            isVisible = true
        }
        toast(message)
    }

    private fun readSelectedFile(uri: Uri): SelectedFile? {
        var name = "AnyPaste_file"
        var size = -1L
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() == true) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        } catch (_: RuntimeException) {
            return null
        } finally {
            cursor?.close()
        }
        if (size < 0L) {
            size = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        val mimeType = contentResolver.getType(uri)
            ?: if (sendType == ClipboardType.IMAGE) "image/*" else "application/octet-stream"
        return SelectedFile(uri, name, size, mimeType)
    }

    private fun restorePendingFile(state: Bundle?) {
        if (state == null) return
        sendType = state.getString(STATE_SEND_TYPE)
            ?.let { name -> ClipboardType.entries.firstOrNull { it.name == name } }
            ?: ClipboardType.TEXT
        val uri = state.getString(STATE_FILE_URI)?.let(Uri::parse) ?: return
        selectedFile = SelectedFile(
            uri = uri,
            name = state.getString(STATE_FILE_NAME).orEmpty(),
            size = state.getLong(STATE_FILE_SIZE, -1L),
            mimeType = state.getString(STATE_FILE_MIME).orEmpty(),
        )
    }

    private fun displayName(state: MainUiState): String = state.user?.displayName
        ?.takeIf { it.isNotBlank() }
        ?: state.user?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: "사용자"

    private fun remoteDevices(state: MainUiState): List<Device> = state.devices
        .filter { it.id != viewModel.currentDeviceId() }

    private fun isDeviceOnline(device: Device): Boolean {
        val lastSeen = device.lastSeenAt?.toDate()?.time ?: return device.isOnline
        return device.isOnline && System.currentTimeMillis() - lastSeen <= ONLINE_WINDOW_MILLIS
    }

    private fun platformLabel(device: Device): String =
        if (device.resolvedPlatform() == DevicePlatform.MACOS) "macOS" else "Android"

    @DrawableRes
    private fun deviceIcon(device: Device): Int =
        if (device.resolvedPlatform() == DevicePlatform.MACOS) R.drawable.ic_desktop
        else R.drawable.ic_phone

    @DrawableRes
    private fun typeIcon(item: ClipboardItem): Int = when (item.resolvedType()) {
        ClipboardType.TEXT -> R.drawable.ic_text
        ClipboardType.IMAGE -> R.drawable.ic_image
        ClipboardType.FILE -> R.drawable.ic_file
    }

    private fun typeLabel(item: ClipboardItem): String = typeLabel(item.resolvedType())

    private fun typeLabel(type: ClipboardType): String = when (type) {
        ClipboardType.TEXT -> "텍스트"
        ClipboardType.IMAGE -> "이미지"
        ClipboardType.FILE -> "파일"
    }

    private fun itemTitle(item: ClipboardItem): String = when (item.resolvedType()) {
        ClipboardType.TEXT -> item.content.trim().lineSequence().firstOrNull().orEmpty()
            .ifBlank { "빈 텍스트" }
            .take(80)
        ClipboardType.IMAGE, ClipboardType.FILE -> item.fileName.ifBlank { typeLabel(item) }
    }

    private fun itemMeta(item: ClipboardItem, state: MainUiState): String {
        val sourceName = state.devices.firstOrNull { it.id == item.sourceDeviceId }?.deviceName
            ?: if (item.sourceDeviceId == viewModel.currentDeviceId()) "이 기기" else "다른 기기"
        val parts = mutableListOf(sourceName, relativeTime(item.createdAt?.toDate()))
        if (item.fileSize > 0L) parts += formatSize(item.fileSize)
        return parts.joinToString(" · ")
    }

    private fun lastItemTime(items: List<ClipboardItem>): String =
        items.firstOrNull()?.createdAt?.toDate()?.let { "마지막 동기화 ${relativeTime(it)}" }
            ?: "아직 동기화된 항목이 없습니다"

    private fun relativeTime(date: Date?): String {
        if (date == null) return "방금 전"
        return DateUtils.getRelativeTimeSpanString(
            date.time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private fun syncStatusText(): String = when (ClipboardSyncService.state.value.phase) {
        ClipboardSyncPhase.STOPPED -> preferences.backgroundSyncNotice
            .takeIf(String::isNotBlank) ?: "자동 동기화 꺼짐"
        ClipboardSyncPhase.WAITING_FOR_AUTH -> "로그인 대기 중"
        ClipboardSyncPhase.SYNCING -> ClipboardSyncService.state.value.message ?: "동기화 중"
        ClipboardSyncPhase.ERROR -> ClipboardSyncService.state.value.message ?: "동기화 확인 필요"
    }

    private fun dateTimeFormat(): DateFormat = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
    )

    private fun formatSize(bytes: Long): String =
        if (bytes < 0L) "크기 정보 없음" else Formatter.formatFileSize(this, bytes)

    private fun isOnWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun loadLocalImagePreview(file: SelectedFile) {
        if (localPreviewLoadingUri == file.uri) return
        localPreviewLoadingUri = file.uri
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampledUri(file.uri) }
            if (selectedFile?.uri == file.uri) {
                localPreviewBitmap = bitmap
                localPreviewBitmapUri = file.uri
                if (currentScreen == Screen.SEND) renderCurrent(viewModel.state.value)
            }
            if (localPreviewLoadingUri == file.uri) localPreviewLoadingUri = null
        }
    }

    private fun decodeSampledUri(uri: Uri): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION ||
                bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION
            ) {
                sampleSize *= 2
            }
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize },
                )
            }
        }.getOrNull()
    }

    private fun SwitchMaterial.setCheckedSilently(
        checked: Boolean,
        listener: (Boolean) -> Unit,
    ) {
        setOnCheckedChangeListener(null)
        isChecked = checked
        setOnCheckedChangeListener { _, value -> listener(value) }
    }

    private fun View.setText(@IdRes id: Int, value: CharSequence) {
        findOptional<TextView>(id)?.text = value
    }

    private inline fun <reified T : View> View.findOptional(@IdRes id: Int): T? = findViewById(id)

    private fun View.onClick(@IdRes id: Int, action: () -> Unit) {
        findOptional<View>(id)?.setOnClickListener { action() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class SelectedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
    )

    private data class ClipboardCardIds(
        @param:IdRes val container: Int,
        @param:IdRes val icon: Int,
        @param:IdRes val title: Int,
        @param:IdRes val meta: Int,
        @param:IdRes val arrow: Int,
        @param:IdRes val check: Int,
    )

    private enum class ClipboardFilter { ALL, TEXT, IMAGE, FILE }

    private enum class Screen(@param:LayoutRes val layoutRes: Int) {
        ONBOARDING(R.layout.screen_onboarding),
        LOGIN(R.layout.screen_login),
        EMAIL_LOGIN(R.layout.screen_email_login),
        SIGN_UP(R.layout.screen_sign_up),
        HOME(R.layout.screen_home),
        CLIPBOARD_LIST(R.layout.screen_clipboard_list),
        SEND(R.layout.screen_send),
        TRANSFER_STATUS(R.layout.screen_transfer_status),
        CLIPBOARD_DETAIL(R.layout.screen_clipboard_detail),
        DEVICES(R.layout.screen_devices),
        DEVICE_DETAIL(R.layout.screen_device_detail),
        CONNECT_DEVICE(R.layout.screen_connect_device),
        NOTIFICATIONS(R.layout.screen_notifications),
        SETTINGS(R.layout.screen_settings),
        PERMISSIONS(R.layout.screen_permissions),
    }

    private companion object {
        const val STATE_SCREEN = "screen"
        const val STATE_FILE_URI = "file_uri"
        const val STATE_FILE_NAME = "file_name"
        const val STATE_FILE_MIME = "file_mime"
        const val STATE_FILE_SIZE = "file_size"
        const val STATE_SEND_TYPE = "send_type"
        const val ONLINE_WINDOW_MILLIS = 2L * 60L * 1000L
        const val MAX_PREVIEW_DIMENSION = 2_048
        const val MIN_SIGN_UP_PASSWORD_LENGTH = 8
        const val MAX_DISPLAY_NAME_LENGTH = 50

        val AUTH_SCREENS = setOf(
            Screen.LOGIN,
            Screen.EMAIL_LOGIN,
            Screen.SIGN_UP,
            Screen.ONBOARDING,
        )

        val CLIPBOARD_CARD_IDS = listOf(
            ClipboardCardIds(
                R.id.item_clipboard_first,
                R.id.iv_clipboard_first_type,
                R.id.tv_clipboard_first_title,
                R.id.tv_clipboard_first_meta,
                View.NO_ID,
                View.NO_ID,
            ),
            ClipboardCardIds(
                R.id.item_clipboard_second,
                R.id.iv_clipboard_second_type,
                R.id.tv_clipboard_second_title,
                R.id.tv_clipboard_second_meta,
                View.NO_ID,
                View.NO_ID,
            ),
            ClipboardCardIds(
                R.id.item_clipboard_third,
                R.id.iv_clipboard_third_type,
                R.id.tv_clipboard_third_title,
                R.id.tv_clipboard_third_meta,
                View.NO_ID,
                View.NO_ID,
            ),
        )

    }
}
