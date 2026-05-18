package sh.haven.feature.connections

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

data class LarktunWebPage(
    val title: String,
    val initialUrl: String,
    val proxyUrl: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarktunWebViewScreen(
    page: LarktunWebPage,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember(page.proxyUrl, page.initialUrl) { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(page.initialUrl) { mutableStateOf(page.initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var addressValue by remember(page.initialUrl) {
        mutableStateOf(
            TextFieldValue(
                text = page.initialUrl,
                selection = TextRange(page.initialUrl.length),
            ),
        )
    }
    var requestedUrl by remember(page.initialUrl) { mutableStateOf(page.initialUrl) }
    var addressFocused by remember { mutableStateOf(false) }
    var proxyReady by remember(page.proxyUrl) { mutableStateOf(false) }
    var proxyUnsupported by remember(page.proxyUrl) { mutableStateOf(false) }

    fun navigateToAddress(rawAddress: String) {
        val nextUrl = normalizeLarktunWebUrl(rawAddress, currentUrl)
        addressValue = TextFieldValue(nextUrl, TextRange(nextUrl.length))
        requestedUrl = nextUrl
        webView?.let { view ->
            view.tag = nextUrl
            view.loadUrl(nextUrl)
        }
    }

    DisposableEffect(page.proxyUrl) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule(page.proxyUrl)
                .build()
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                ContextCompat.getMainExecutor(context),
            ) {
                proxyReady = true
            }
        } else {
            proxyUnsupported = true
            proxyReady = true
        }

        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(
                    ContextCompat.getMainExecutor(context),
                ) {}
            }
            webView?.destroy()
        }
    }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) {
            view.goBack()
        } else {
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = addressValue,
                        onValueChange = { addressValue = it },
                        placeholder = { Text(stringResource(R.string.connections_larktun_web_address)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { navigateToAddress(addressValue.text) },
                        ),
                        trailingIcon = {
                            IconButton(onClick = { navigateToAddress(addressValue.text) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = stringResource(R.string.connections_larktun_web_go),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                addressFocused = focusState.isFocused
                                if (focusState.isFocused) {
                                    addressValue = addressValue.copy(
                                        selection = TextRange(addressValue.text.length),
                                    )
                                }
                            },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                    }
                },
                actions = {
                    IconButton(
                        enabled = canGoBack,
                        onClick = { webView?.goBack() },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.connections_larktun_web_back),
                        )
                    }
                    IconButton(
                        enabled = canGoForward,
                        onClick = { webView?.goForward() },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.connections_larktun_web_forward),
                        )
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.connections_larktun_web_reload),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (loading && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                Box(Modifier.height(2.dp))
            }

            if (proxyUnsupported) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.connections_larktun_web_proxy_unsupported),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (proxyReady && !proxyUnsupported) {
                LarktunAndroidWebView(
                    targetUrl = requestedUrl,
                    onWebView = { view -> webView = view },
                    onStateChanged = { state ->
                        currentUrl = state.url ?: page.initialUrl
                        if (!addressFocused) {
                            val nextAddress = state.url ?: page.initialUrl
                            if (addressValue.text != nextAddress) {
                                addressValue = TextFieldValue(
                                    text = nextAddress,
                                    selection = TextRange(nextAddress.length),
                                )
                            }
                        }
                        progress = state.progress
                        loading = state.loading
                        canGoBack = state.canGoBack
                        canGoForward = state.canGoForward
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LarktunAndroidWebView(
    targetUrl: String,
    onWebView: (WebView) -> Unit,
    onStateChanged: (LarktunWebViewState) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onStateChanged(view.toLarktunState(loading = true))
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        onStateChanged(view.toLarktunState(loading = false))
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val uri = request.url
                        return if (uri.scheme == "http" || uri.scheme == "https") {
                            false
                        } else {
                            true
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onStateChanged(view.toLarktunState(progress = newProgress))
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        super.onReceivedTitle(view, title)
                        onStateChanged(view.toLarktunState(title = title))
                    }
                }

                onWebView(this)
                tag = targetUrl
                loadUrl(targetUrl)
            }
        },
        update = { view ->
            onWebView(view)
            if (view.tag != targetUrl && Uri.parse(targetUrl).scheme != null) {
                view.tag = targetUrl
                view.loadUrl(targetUrl)
            }
        },
    )
}

private fun normalizeLarktunWebUrl(rawAddress: String, fallbackUrl: String): String {
    val trimmed = rawAddress.trim()
    if (trimmed.isBlank()) return fallbackUrl
    if (trimmed.startsWith("//")) return "http:$trimmed"
    val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(trimmed)
    val candidate = if (hasScheme) trimmed else "http://$trimmed"
    val parsed = Uri.parse(candidate)
    return if (parsed.scheme == "http" || parsed.scheme == "https") {
        candidate
    } else {
        "http://$trimmed"
    }
}

private data class LarktunWebViewState(
    val title: String?,
    val url: String?,
    val progress: Int,
    val loading: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

private fun WebView.toLarktunState(
    title: String? = this.title,
    url: String? = this.url,
    progress: Int = this.progress,
    loading: Boolean = progress in 1..99,
): LarktunWebViewState =
    LarktunWebViewState(
        title = title,
        url = url,
        progress = progress,
        loading = loading,
        canGoBack = canGoBack(),
        canGoForward = canGoForward(),
    )
