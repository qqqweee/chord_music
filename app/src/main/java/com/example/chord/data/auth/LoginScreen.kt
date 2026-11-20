package com.example.chord.data.auth

import android.annotation.SuppressLint
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.chord.data.auth.AuthStore
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordTheme
import com.example.chord.ui.theme.ChordType

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
            "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
private const val MUSIC = "https://music.youtube.com"

/**
 * Google sign-in in a WebView. When the browser lands on music.youtube.com
 * with a login cookie, we save it and call [onDone].
 *
 * The page stays hidden (dark + spinner) until it has loaded, then fades in,
 * so you never see a white flash.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onClose)
    val done by rememberUpdatedState(onDone)
    var loaded by remember { mutableStateOf(false) }
    val webAlpha by animateFloatAsState(if (loaded) 1f else 0f, tween(280), label = "loginFade")

    Column(
        modifier.fillMaxSize().background(ChordTheme.bg)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                    .pressScale(pressedScale = 0.9f, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Text(
                "Sign in to YouTube Music", style = ChordType.Section.copy(fontSize = 17.sp),
                color = Color.White, modifier = Modifier.padding(start = 14.dp),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (!loaded) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(30.dp), color = Color.White, strokeWidth = 2.5.dp,
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        overScrollMode = View.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        alpha = 0f
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in inside a plain WebView; dropping "wv" fixes it.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loaded = true
                                if (url?.startsWith(MUSIC) != true) return
                                val cookies = CookieManager.getInstance().getCookie(MUSIC) ?: return
                                if (AuthStore.save(cookies)) done()
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                // Fade the native view directly. No Compose layer, so no flicker.
                update = { it.alpha = webAlpha },
                onRelease = { it.destroy() },
            )
        }
    }
}