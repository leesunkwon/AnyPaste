package com.kotlinsun.anypaste

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var screenContainer: FrameLayout
    private var currentScreen = DesignScreen.LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.main)
        screenContainer = findViewById(R.id.screen_container)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        showScreen(DesignScreen.LOGIN)
    }

    private fun showScreen(screen: DesignScreen) {
        currentScreen = screen
        screenContainer.removeAllViews()

        val screenView = layoutInflater.inflate(screen.layoutRes, screenContainer, false)
        screenContainer.addView(screenView)
        bindPreviewNavigation(screenView)
    }

    /**
     * Firebase, clipboard, file-transfer logic is intentionally not connected yet.
     * These handlers only make the completed design screens reviewable in the app.
     */
    private fun bindPreviewNavigation(view: View) = with(view) {
        onClick(R.id.nav_home) { showScreen(DesignScreen.HOME) }
        onClick(R.id.nav_devices) { showScreen(DesignScreen.DEVICES) }
        onClick(R.id.nav_settings) { showScreen(DesignScreen.SETTINGS) }
        onClick(R.id.btn_send) { showScreen(DesignScreen.SEND) }
        onClick(R.id.btn_send_all) { showScreen(DesignScreen.SEND) }
        onClick(R.id.btn_send_text) { showScreen(DesignScreen.SEND) }
        onClick(R.id.btn_send_image) { showScreen(DesignScreen.SEND) }
        onClick(R.id.btn_send_file) { showScreen(DesignScreen.SEND) }
        onClick(R.id.btn_notifications) { showScreen(DesignScreen.NOTIFICATIONS) }
        onClick(R.id.btn_all_recent) { showScreen(DesignScreen.CLIPBOARD_LIST) }
        onClick(R.id.item_clipboard_first) { showScreen(DesignScreen.CLIPBOARD_DETAIL) }
        onClick(R.id.item_clipboard_second) { showScreen(DesignScreen.CLIPBOARD_DETAIL) }
        onClick(R.id.item_clipboard_third) { showScreen(DesignScreen.CLIPBOARD_DETAIL) }
        onClick(R.id.item_device_mac) { showScreen(DesignScreen.DEVICE_DETAIL) }
        onClick(R.id.item_device_phone) { showScreen(DesignScreen.DEVICE_DETAIL) }
        onClick(R.id.btn_add_device) { showScreen(DesignScreen.CONNECT_DEVICE) }
        onClick(R.id.btn_transfer) { showScreen(DesignScreen.TRANSFER_STATUS) }
        onClick(R.id.btn_continue) { showScreen(DesignScreen.LOGIN) }
        onClick(R.id.btn_login) { showScreen(DesignScreen.HOME) }
        onClick(R.id.btn_login_email) { showScreen(DesignScreen.EMAIL_LOGIN) }
        onClick(R.id.btn_email_continue) { showScreen(DesignScreen.HOME) }
        onClick(R.id.btn_show_onboarding) { showScreen(DesignScreen.ONBOARDING) }
        onClick(R.id.btn_permissions) { showScreen(DesignScreen.PERMISSIONS) }
        onClick(R.id.btn_continue_permissions) { showScreen(DesignScreen.HOME) }
        onClick(R.id.btn_account) { showScreen(DesignScreen.LOGIN) }
        onClick(R.id.btn_back) {
            showScreen(
                when (currentScreen) {
                    DesignScreen.DEVICE_DETAIL, DesignScreen.CONNECT_DEVICE -> DesignScreen.DEVICES
                    DesignScreen.EMAIL_LOGIN -> DesignScreen.LOGIN
                    else -> DesignScreen.HOME
                },
            )
        }
    }

    private fun View.onClick(@IdRes id: Int, action: () -> Unit) {
        findViewById<View?>(id)?.setOnClickListener { action() }
    }

    private enum class DesignScreen(@param:LayoutRes val layoutRes: Int) {
        ONBOARDING(R.layout.screen_onboarding),
        LOGIN(R.layout.screen_login),
        EMAIL_LOGIN(R.layout.screen_email_login),
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
}
