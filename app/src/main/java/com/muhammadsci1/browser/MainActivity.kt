package com.muhammadsci1.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.muhammadsci1.browser.data.HistoryDatabase
import com.muhammadsci1.browser.data.HistoryEntry
import com.muhammadsci1.browser.session.BrowserSessionPersistence
import com.muhammadsci1.browser.session.TabSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var developerIndicator: TextView
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var reloadButton: TextView
    private lateinit var tabButton: TextView

    private lateinit var developerPreferences: DeveloperPreferences
    private lateinit var historyDatabase: HistoryDatabase
    private lateinit var sessionPersistence: BrowserSessionPersistence

    private val tabIds = AtomicLong(System.currentTimeMillis())
    private val tabs = mutableListOf<BrowserTab>()
    private var selectedTabIndex = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        developerPreferences = DeveloperPreferences(this)
        historyDatabase = HistoryDatabase.getInstance(this)
        sessionPersistence = BrowserSessionPersistence(this)

        configureProcessWideWebViewSupport()
        requestNotificationPermissionIfNeeded()
        startKeepAliveServiceSafely()

        buildBrowserUi()
        setContentView(root)

        restoreTabs(savedInstanceState)
        if (tabs.isEmpty()) {
            openNewTab(DEFAULT_HOME, switchToNewTab = true)
        } else {
            selectedTabIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)
            switchToTab(selectedTabIndex)
        }
        updateDeveloperIndicator()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(STATE_TABS, ArrayList(tabs.map { tab ->
            Bundle().apply {
                putLong("id", tab.id)
                putString("title", tab.title)
                putString("url", tab.currentUrl)
                putBundle("web_state", Bundle().also { webState -> tab.webView.saveState(webState) })
            }
        }))
        outState.putInt(STATE_SELECTED_TAB, selectedTabIndex)
        persistSessionMetadata()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        persistSessionMetadata()
        // Deliberately do not call WebView.pauseTimers(): long-running test pages should continue
        // to execute as long as Android keeps this foreground-service-backed process alive.
    }

    override fun onStop() {
        super.onStop()
        persistSessionMetadata()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        persistSessionMetadata()
        // Do not auto-close or destroy tabs under memory pressure. The foreground service and
        // state persistence provide best-effort protection for professional long-running sessions.
    }

    override fun onBackPressed() {
        currentWebView()?.let { webView ->
            if (webView.canGoBack()) {
                webView.goBack()
                return
            }
        }
        super.onBackPressed()
    }

    private fun configureProcessWideWebViewSupport() {
        WebView.setWebContentsDebuggingEnabled(developerPreferences.developerMode)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            flush()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                allowContentAccess = true
                allowFileAccess = false
                blockNetworkLoads = false
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun startKeepAliveServiceSafely() {
        runCatching { KeepAliveService.start(this) }
            .onFailure { Toast.makeText(this, "Unable to start keep-alive service: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun buildBrowserUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.WHITE)
        }

        backButton = toolbarButton("‹", "Back").apply { setOnClickListener { currentWebView()?.goBack() } }
        forwardButton = toolbarButton("›", "Forward").apply { setOnClickListener { currentWebView()?.goForward() } }
        reloadButton = toolbarButton("↻", "Reload").apply {
            textSize = 19f
            setOnClickListener { currentWebView()?.reload() }
        }

        addressBar = EditText(this).apply {
            setSingleLine(true)
            hint = "Search or enter address"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.address_bar_background)
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                    loadFromAddressBar()
                    true
                } else {
                    false
                }
            }
        }

        val goButton = toolbarButton("Go", "Go").apply {
            textSize = 13f
            setOnClickListener { loadFromAddressBar() }
        }
        tabButton = toolbarButton("▣ 1", "Tabs").apply {
            textSize = 13f
            setOnClickListener { showTabsMenu() }
        }
        val menuButton = toolbarButton("⋮", "Menu").apply { setOnClickListener { showMainMenu(this) } }

        toolbar.addView(backButton)
        toolbar.addView(forwardButton)
        toolbar.addView(reloadButton)
        toolbar.addView(addressBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        toolbar.addView(goButton, LinearLayout.LayoutParams(dp(46), dp(40)))
        toolbar.addView(tabButton, LinearLayout.LayoutParams(dp(58), dp(40)).apply { marginStart = dp(4) })
        toolbar.addView(menuButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            isIndeterminate = false
        }

        developerIndicator = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(146, 64, 14))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dev_indicator_background)
            visibility = View.GONE
        }

        webContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(developerIndicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun toolbarButton(label: String, contentDescriptionValue: String): TextView {
        return TextView(this).apply {
            text = label
            contentDescription = contentDescriptionValue
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(60, 64, 67))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.toolbar_button_background)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(2) }
        }
    }

    private fun restoreTabs(savedInstanceState: Bundle?) {
        val savedTabs = savedInstanceState?.getParcelableArrayList<Bundle>(STATE_TABS)
        if (!savedTabs.isNullOrEmpty()) {
            selectedTabIndex = savedInstanceState.getInt(STATE_SELECTED_TAB, 0)
            savedTabs.forEach { saved ->
                val webState = saved.getBundle("web_state")
                val tab = createTab(
                    initialUrl = saved.getString("url") ?: DEFAULT_HOME,
                    title = saved.getString("title") ?: "Restored tab",
                    webState = webState
                )
                tab.currentUrl = saved.getString("url") ?: tab.webView.url ?: DEFAULT_HOME
            }
            return
        }

        val (persistedTabs, selectedIndex) = sessionPersistence.restore()
        selectedTabIndex = selectedIndex
        persistedTabs.forEach { snapshot ->
            createTab(initialUrl = snapshot.url, title = snapshot.title, webState = null, id = snapshot.id)
        }
    }

    private fun persistSessionMetadata() {
        if (::sessionPersistence.isInitialized) {
            sessionPersistence.save(
                tabs.map { TabSnapshot(it.id, it.title, it.currentUrl.ifBlank { it.webView.url ?: DEFAULT_HOME }) },
                selectedTabIndex
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(
        initialUrl: String,
        title: String = "New tab",
        webState: Bundle? = null,
        id: Long = tabIds.incrementAndGet()
    ): BrowserTab {
        val webView = WebView(this)
        val tab = BrowserTab(id, webView, title, initialUrl)
        webView.tag = id
        tabs.add(tab)

        configureWebView(webView)
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        webView.visibility = View.GONE

        val restored = webState?.let { webView.restoreState(it) } != null
        if (!restored) {
            webView.loadUrl(initialUrl)
        }
        applyRuntimeSecuritySettings(webView, initialUrl)
        return tab
    }

    private fun configureWebView(webView: WebView) {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            safeBrowsingEnabled = true
            offscreenPreRaster = true

            // Secure defaults. Developer overrides are applied only for the configured trusted origin.
            allowContentAccess = true
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) {
                    applyRuntimeSecuritySettings(view, request.url.toString())
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                findTab(view)?.currentUrl = url ?: findTab(view)?.currentUrl.orEmpty()
                applyRuntimeSecuritySettings(view, url)
                if (view == currentWebView()) {
                    updateAddressBar(url)
                    updateToolbarState()
                    updateDeveloperIndicator()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val tab = findTab(view)
                tab?.currentUrl = url ?: tab.currentUrl
                tab?.title = view.title?.takeIf { it.isNotBlank() } ?: tab?.title ?: "Untitled"
                if (view == currentWebView()) {
                    updateAddressBar(url)
                    updateToolbarState()
                    updateDeveloperIndicator()
                }
                saveHistoryEntry(url, view.title)
                persistSessionMetadata()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                Toast.makeText(
                    this@MainActivity,
                    "Blocked insecure TLS certificate: ${error.url}",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return maybeRewriteCorsResponse(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view == currentWebView()) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    updateToolbarState()
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                findTab(view)?.let { tab ->
                    tab.title = title?.takeIf { it.isNotBlank() } ?: tab.title
                    if (view == currentWebView()) updateToolbarState()
                }
                persistSessionMetadata()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newTab = createTab("about:blank")
                selectedTabIndex = tabs.indexOf(newTab)
                switchToTab(selectedTabIndex)
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun applyRuntimeSecuritySettings(webView: WebView, pageUrl: String? = webView.url) {
        val trustedPage = developerPreferences.isTrustedPage(pageUrl)
        val trustedOrigin = DeveloperPreferences.originOf(pageUrl)
        val trustedFilePage = trustedPage && trustedOrigin == "file://"
        webView.settings.apply {
            safeBrowsingEnabled = true
            if (trustedPage) {
                mixedContentMode = developerPreferences.mixedContentMode
            } else {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            val allowFileUniversalAccess = trustedFilePage && developerPreferences.allowUniversalAccessFromFileUrls
            allowFileAccess = allowFileUniversalAccess
            allowFileAccessFromFileURLs = allowFileUniversalAccess
            allowUniversalAccessFromFileURLs = allowFileUniversalAccess
        }
        WebView.setWebContentsDebuggingEnabled(developerPreferences.developerMode)
    }

    private fun applyRuntimeSecuritySettingsToAllTabs() {
        tabs.forEach { tab -> applyRuntimeSecuritySettings(tab.webView, tab.currentUrl.ifBlank { tab.webView.url }) }
    }

    private fun loadFromAddressBar() {
        val url = normalizeAddressInput(addressBar.text?.toString().orEmpty())
        currentWebView()?.loadUrl(url)
    }

    private fun normalizeAddressInput(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isBlank()) return DEFAULT_HOME
        if (input.startsWith("about:", ignoreCase = true) ||
            input.startsWith("file://", ignoreCase = true) ||
            input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            return input
        }

        val isLocalHost = input.equals("localhost", ignoreCase = true) || input.startsWith("localhost:", ignoreCase = true)
        val isIpAddress = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(input)
        val looksLikePublicHost = input.contains('.') && !input.contains(' ')

        return when {
            isLocalHost || isIpAddress -> "http://$input"
            looksLikePublicHost -> "https://$input"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
        }
    }

    private fun openNewTab(url: String = DEFAULT_HOME, switchToNewTab: Boolean = true) {
        val tab = createTab(url)
        if (switchToNewTab) {
            selectedTabIndex = tabs.indexOf(tab)
            switchToTab(selectedTabIndex)
        }
        persistSessionMetadata()
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            currentWebView()?.loadUrl(DEFAULT_HOME)
            return
        }
        val removed = tabs.removeAt(selectedTabIndex)
        webContainer.removeView(removed.webView)
        removed.webView.stopLoading()
        removed.webView.destroy()
        selectedTabIndex = selectedTabIndex.coerceAtMost(tabs.lastIndex)
        switchToTab(selectedTabIndex)
        persistSessionMetadata()
    }

    private fun switchToTab(index: Int) {
        if (tabs.isEmpty()) return
        selectedTabIndex = index.coerceIn(0, tabs.lastIndex)
        tabs.forEachIndexed { i, tab ->
            tab.webView.visibility = if (i == selectedTabIndex) View.VISIBLE else View.GONE
            if (i == selectedTabIndex) {
                tab.webView.requestFocus()
                applyRuntimeSecuritySettings(tab.webView, tab.currentUrl.ifBlank { tab.webView.url })
            }
        }
        updateAddressBar(currentWebView()?.url ?: tabs[selectedTabIndex].currentUrl)
        updateToolbarState()
        updateDeveloperIndicator()
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("New tab")
            menu.add("Close current tab")
            menu.add("History")
            menu.add("Developer Settings")
            menu.add("Restart keep-alive service")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "New tab" -> openNewTab(DEFAULT_HOME)
                    "Close current tab" -> closeCurrentTab()
                    "History" -> showHistoryDialog()
                    "Developer Settings" -> showDeveloperSettingsDialog()
                    "Restart keep-alive service" -> startKeepAliveServiceSafely()
                }
                true
            }
            show()
        }
    }

    private fun showTabsMenu() {
        val labels = tabs.mapIndexed { index, tab ->
            val selectedMarker = if (index == selectedTabIndex) "✓ " else ""
            val title = tab.title.ifBlank { tab.currentUrl.ifBlank { "Tab ${index + 1}" } }
            "$selectedMarker${index + 1}. ${title.take(48)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Open tabs")
            .setItems(labels) { _, which -> switchToTab(which) }
            .setPositiveButton("New tab") { _, _ -> openNewTab(DEFAULT_HOME) }
            .setNegativeButton("Close current") { _, _ -> closeCurrentTab() }
            .show()
    }

    private fun showHistoryDialog() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { historyDatabase.historyDao().recent(150) }
            if (entries.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("History")
                    .setMessage("No browsing history yet.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            val labels = entries.map { entry ->
                val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.url
                "${title.take(64)}\n${entry.url}"
            }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("History")
                .setItems(labels) { _, which -> currentWebView()?.loadUrl(entries[which].url) }
                .setNegativeButton("Clear") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) { historyDatabase.historyDao().clear() }
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun showDeveloperSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }

        val enableSwitch = Switch(this).apply {
            text = "Enable Developer Mode"
            isChecked = developerPreferences.developerMode
        }
        val trustedOriginInput = EditText(this).apply {
            hint = "Trusted Origin, e.g. http://192.168.1.50:3000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(developerPreferences.trustedOrigin)
        }
        val mixedLabel = TextView(this).apply {
            text = "mixedContentMode for the trusted origin"
            setPadding(0, dp(14), 0, dp(4))
        }
        val mixedSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                MIXED_CONTENT_LABELS
            )
            setSelection(indexForMixedContentMode(developerPreferences.mixedContentMode))
        }
        val universalFileAccess = CheckBox(this).apply {
            text = "allowUniversalAccessFromFileURLs for trusted file:// pages"
            isChecked = developerPreferences.allowUniversalAccessFromFileUrls
            setPadding(0, dp(12), 0, 0)
        }
        val warning = TextView(this).apply {
            text = "Developer Mode is OFF by default. When enabled, debug behavior is scoped to the exact Trusted Origin. The CORS response rewriter is active only while the current top-level page matches that origin."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(12), 0, 0)
        }

        container.addView(enableSwitch)
        container.addView(trustedOriginInput)
        container.addView(mixedLabel)
        container.addView(mixedSpinner)
        container.addView(universalFileAccess)
        container.addView(warning)

        AlertDialog.Builder(this)
            .setTitle("Developer Settings")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                developerPreferences.developerMode = enableSwitch.isChecked
                developerPreferences.trustedOrigin = trustedOriginInput.text?.toString().orEmpty()
                developerPreferences.mixedContentMode = mixedContentModeForIndex(mixedSpinner.selectedItemPosition)
                developerPreferences.allowUniversalAccessFromFileUrls = universalFileAccess.isChecked
                WebView.setWebContentsDebuggingEnabled(developerPreferences.developerMode)
                applyRuntimeSecuritySettingsToAllTabs()
                updateDeveloperIndicator()
                Toast.makeText(this, "Developer settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAddressBar(url: String?) {
        if (!addressBar.hasFocus()) {
            addressBar.setText(url.orEmpty())
        }
    }

    private fun updateToolbarState() {
        val webView = currentWebView()
        backButton.isEnabled = webView?.canGoBack() == true
        forwardButton.isEnabled = webView?.canGoForward() == true
        backButton.alpha = if (backButton.isEnabled) 1.0f else 0.35f
        forwardButton.alpha = if (forwardButton.isEnabled) 1.0f else 0.35f
        tabButton.text = "▣ ${tabs.size}"
    }

    private fun updateDeveloperIndicator() {
        if (!developerPreferences.developerMode) {
            developerIndicator.visibility = View.GONE
            return
        }
        val trusted = developerPreferences.trustedOrigin.ifBlank { "not configured" }
        val activeForPage = developerPreferences.isTrustedPage(currentWebView()?.url ?: currentTab()?.currentUrl)
        developerIndicator.text = if (activeForPage) {
            "⚠ Developer Mode ACTIVE for $trusted — debug WebView settings and CORS header rewriting enabled"
        } else {
            "⚠ Developer Mode ON — waiting for trusted origin: $trusted"
        }
        developerIndicator.visibility = View.VISIBLE
    }

    private fun saveHistoryEntry(url: String?, title: String?) {
        if (url.isNullOrBlank()) return
        if (url.startsWith("about:", ignoreCase = true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            historyDatabase.historyDao().insert(HistoryEntry(url = url, title = title))
        }
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(selectedTabIndex)

    private fun currentWebView(): WebView? = currentTab()?.webView

    private fun findTab(view: WebView): BrowserTab? = tabs.firstOrNull { it.webView == view }

    private fun maybeRewriteCorsResponse(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!shouldRewriteCorsForRequest(view, request)) return null
        val requestUrl = request.url ?: return null
        val scheme = requestUrl.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val method = request.method.uppercase(Locale.US)
        return try {
            when (method) {
                "OPTIONS" -> buildCorsPreflightResponse(request)
                "GET", "HEAD" -> proxyRequestAndRewriteCors(requestUrl.toString(), request, method)
                else -> null // WebResourceRequest does not expose request bodies for POST/PUT/PATCH.
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun shouldRewriteCorsForRequest(view: WebView, request: WebResourceRequest): Boolean {
        if (!developerPreferences.developerMode) return false
        val trusted = developerPreferences.trustedOrigin
        if (trusted.isBlank()) return false

        val topLevelOrigin = if (request.isForMainFrame) {
            DeveloperPreferences.originOf(request.url.toString())
        } else {
            DeveloperPreferences.originOf(view.url ?: findTab(view)?.currentUrl)
        }
        return topLevelOrigin == trusted
    }

    private fun buildCorsPreflightResponse(request: WebResourceRequest): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            corsHeaders(request),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun proxyRequestAndRewriteCors(
        url: String,
        request: WebResourceRequest,
        method: String
    ): WebResourceResponse? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            useCaches = true

            request.requestHeaders.forEach { (name, value) ->
                if (name.isBlank()) return@forEach
                if (name.equals("Host", ignoreCase = true) ||
                    name.equals("Connection", ignoreCase = true) ||
                    name.equals("Content-Length", ignoreCase = true) ||
                    name.equals("Accept-Encoding", ignoreCase = true)
                ) return@forEach
                setRequestProperty(name, value)
            }
            setRequestProperty("Accept-Encoding", "identity")

            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { cookie ->
                setRequestProperty("Cookie", cookie)
            }
        }

        val status = connection.responseCode
        if (status < 100) return null
        val reason = connection.responseMessage?.takeIf { it.isNotBlank() } ?: "HTTP $status"
        persistResponseCookies(url, connection)

        val stream = responseStream(connection)
        val contentType = connection.contentType
        val response = WebResourceResponse(
            mimeTypeFrom(contentType),
            charsetFrom(contentType),
            status,
            reason,
            rewrittenHeaders(connection, request),
            DisconnectOnCloseInputStream(stream, connection)
        )
        return response
    }

    private fun persistResponseCookies(url: String, connection: HttpURLConnection) {
        val cookieManager = CookieManager.getInstance()
        connection.headerFields.orEmpty().forEach { (name, values) ->
            if (name != null && name.equals("Set-Cookie", ignoreCase = true)) {
                values.orEmpty().forEach { cookie -> cookieManager.setCookie(url, cookie) }
            }
        }
        cookieManager.flush()
    }

    private fun responseStream(connection: HttpURLConnection): InputStream {
        return try {
            connection.inputStream
        } catch (_: IOException) {
            connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
    }

    private fun rewrittenHeaders(
        connection: HttpURLConnection,
        request: WebResourceRequest
    ): MutableMap<String, String> {
        val headers = linkedMapOf<String, String>()
        connection.headerFields.orEmpty().forEach { (name, values) ->
            if (name == null) return@forEach
            if (name.equals("Access-Control-Allow-Origin", ignoreCase = true)) return@forEach
            if (name.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
            if (name.equals("Content-Encoding", ignoreCase = true)) return@forEach
            val value = values.orEmpty().filterNotNull().joinToString(", ").trim()
            if (value.isNotBlank()) headers[name] = value
        }
        headers.putAll(corsHeaders(request))
        return headers
    }

    private fun corsHeaders(request: WebResourceRequest): MutableMap<String, String> {
        val requestedMethod = requestHeader(request, "Access-Control-Request-Method")
        val requestedHeaders = requestHeader(request, "Access-Control-Request-Headers")
        return linkedMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to (requestedMethod ?: "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD"),
            "Access-Control-Allow-Headers" to (requestedHeaders ?: "*"),
            "Access-Control-Expose-Headers" to "*",
            "Access-Control-Max-Age" to "86400"
        )
    }

    private fun requestHeader(request: WebResourceRequest, headerName: String): String? {
        return request.requestHeaders.entries
            .firstOrNull { it.key.equals(headerName, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun mimeTypeFrom(contentType: String?): String {
        return contentType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "text/plain"
    }

    private fun charsetFrom(contentType: String?): String {
        val charset = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
        return charset ?: "UTF-8"
    }

    private fun indexForMixedContentMode(mode: Int): Int = when (mode) {
        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE -> 1
        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW -> 2
        else -> 0
    }

    private fun mixedContentModeForIndex(index: Int): Int = when (index) {
        1 -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        2 -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        else -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class BrowserTab(
        val id: Long,
        val webView: WebView,
        var title: String,
        var currentUrl: String
    )

    private class DisconnectOnCloseInputStream(
        delegate: InputStream,
        private val connection: HttpURLConnection
    ) : FilterInputStream(delegate) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val DEFAULT_HOME = "https://www.google.com"
        private const val REQUEST_NOTIFICATIONS = 7001
        private const val STATE_TABS = "browser_tabs"
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val NETWORK_TIMEOUT_MS = 15_000

        private val MIXED_CONTENT_LABELS = listOf(
            "Never allow (secure default)",
            "Compatibility mode",
            "Always allow"
        )
    }
}
