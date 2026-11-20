
package com.example.chord.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.lastfm.LastFmApi
import com.example.chord.data.lastfm.Scrobbler
import com.example.chord.data.mixes.MixEngine
import com.example.chord.data.settings.AppSettings
import com.example.chord.ui.theme.ChordType
import kotlinx.coroutines.launch

const val LASTFM_STEPS =
    "1. Tap \"Get free API key\" and sign in (or sign up) on Last.fm.\n" +
            "2. Fill the form: Application name \"Chord\", any short description, your email. Leave the rest empty.\n" +
            "3. Submit. Copy the \"API key\" (32 letters and numbers).\n" +
            "4. Paste it here and tap \"Save and test\"."

@Composable
fun LastFmSection() {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val busy by MixEngine.lastFmBusy.collectAsState()
    var input by rememberSaveable { mutableStateOf(AppSettings.lastFmKey) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val on = AppSettings.lastFmOn

    SettingsSection("Last.fm") {
        InfoRow(
            "Status",
            when {
                busy -> "Building mixes…"
                on -> "Connected"
                else -> "Not set up"
            },
        )

        Text(
            if (on) {
                "Last.fm powers Discover Weekly and \"If you like…\" mixes on Home."
            } else {
                "Free key unlocks Discover Weekly and \"If you like…\" mixes. Local mixes work without it."
            },
            style = ChordType.CardSub,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (!on) {
            Text(
                LASTFM_STEPS,
                style = ChordType.CardSub.copy(lineHeight = 17.sp),
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        ActionRow(
            "API key page",
            "last.fm/api/account/create",
            "Get free API key",
        ) {
            runCatching { uri.openUri(LastFmApi.KEY_URL) }
                .onFailure { status = "Could not open Last.fm. Check your browser." }
        }

        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (input.isBlank()) {
                Text(
                    "Paste API key",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                )
            }

            BasicTextField(
                value = input,
                onValueChange = {
                    input = it.trim()
                    status = null
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        status?.let {
            Text(
                it,
                style = ChordType.CardSub,
                color = if (it.startsWith("Connected")) {
                    Color(0xFF7CE38B)
                } else {
                    Color(0xFFFF8A80)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        ActionRow(
            "Connect",
            if (testing) "Checking key…" else "Checks the key, then builds your mixes",
            "Save and test",
        ) {
            if (testing) return@ActionRow

            if (input.isBlank()) {
                status = "Enter your Last.fm API key first."
                return@ActionRow
            }

            testing = true
            status = null

            scope.launch {
                try {
                    LastFmApi.validate(input).fold(
                        onSuccess = {
                            AppSettings.setLastFmKey(input)
                            AppSettings.setLastFmPromptSeen()
                            MixEngine.refreshLastFm()
                            status = "Connected. Your Last.fm mixes will show on Home."
                        },
                        onFailure = {
                            status = it.message ?: "Could not check the key."
                        },
                    )
                } catch (e: Exception) {
                    status = e.message ?: "Could not connect to Last.fm."
                } finally {
                    testing = false
                }
            }
        }

        if (on) {
            ActionRow(
                "Remove key",
                "Turns Last.fm mixes off",
                "Remove",
            ) {
                AppSettings.setLastFmKey("")
                input = ""
                status = null
                MixEngine.refreshLastFm()
            }
        }
    }
}

@Composable
private fun FmField(
    value: String,
    hint: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isBlank()) {
            Text(
                hint,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp,
            )
        }

        BasicTextField(
            value = value,
            onValueChange = { onChange(it.trim()) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FmStatus(text: String?) {
    text?.let {
        Text(
            it,
            style = ChordType.CardSub,
            color = if (it.startsWith("Signed in") || it.startsWith("Imported")) {
                Color(0xFF7CE38B)
            } else {
                Color(0xFFFF8A80)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun LastFmScrobbleSection() {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val pending by Scrobbler.pending.collectAsState()
    val expired by Scrobbler.expired.collectAsState()

    var signer by rememberSaveable {
        mutableStateOf(AppSettings.lastFmSigner)
    }
    var token by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    remember { Scrobbler.init(ctx) }

    fun finish() {
        val currentToken = token
        if (currentToken.isBlank() || busy) return

        busy = true

        scope.launch {
            try {
                val session = LastFmApi.authFinish(signer.trim(), currentToken)

                AppSettings.setLastFmSigner(signer.trim())
                AppSettings.setLastFmSession(session.sk, session.name)

                if (!AppSettings.lastFmOn && session.apiKey.isNotBlank()) {
                    AppSettings.setLastFmKey(session.apiKey)
                    MixEngine.refreshLastFm()
                }

                token = ""
                Scrobbler.signedIn()
                status = "Signed in as ${session.name}"
            } catch (e: Exception) {
                status = if (
                    e is LastFmApi.ApiError && e.code == 14
                ) {
                    "You did not tap Allow yet. Tap Connect and try again."
                } else {
                    e.message ?: "Could not finish Last.fm connection."
                }

                // Keep the token so a temporary network failure can be retried.
            } finally {
                busy = false
            }
        }
    }

    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                finish()
            }
        }

        owner.lifecycle.addObserver(observer)

        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }

    SettingsSection("Last.fm") {
        InfoRow(
            "Status",
            when {
                AppSettings.scrobbleOn -> "Connected as ${AppSettings.lastFmUser}"
                expired -> "Connect again"
                else -> "Not connected"
            },
        )

        if (AppSettings.scrobbleOn || expired) {
            InfoRow("Waiting to send", "$pending")
        }

        if (!AppSettings.scrobbleOn) {
            if (AppSettings.DEFAULT_SIGNER.isBlank()) {
                Text(
                    "Last.fm signer server address",
                    style = ChordType.CardSub,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                FmField(
                    signer,
                    "https://your-worker.your-subdomain.workers.dev",
                ) {
                    signer = it
                    status = null
                }
            }

            Text(
                "Tap Connect, approve access on the Last.fm page, then return to Chord.",
                style = ChordType.CardSub,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ActionRow(
                "Last.fm",
                if (busy) "Connecting…" else "Opens Last.fm in your browser",
                "Connect",
            ) {
                if (busy) return@ActionRow

                val server = signer.trim().trimEnd('/')

                if (server.isBlank()) {
                    status = "Enter your Last.fm signer URL first."
                    return@ActionRow
                }

                if (
                    !server.startsWith("https://") ||
                    !server.contains(".workers.dev")
                ) {
                    status = "Enter a valid HTTPS Cloudflare Workers URL."
                    return@ActionRow
                }

                busy = true
                status = null

                scope.launch {
                    try {
                        val (newToken, authUrl) = LastFmApi.authStart(server)

                        AppSettings.setLastFmSigner(server)
                        signer = server
                        token = newToken

                        uri.openUri(authUrl)
                        status = "Approve access in your browser, then return to Chord."
                    } catch (e: Exception) {
                        token = ""
                        status = e.message ?: "Could not start Last.fm connection. Check the server URL and internet."
                    } finally {
                        busy = false
                    }
                }
            }

            if (token.isNotBlank()) {
                ActionRow(
                    "Pending connection",
                    "Return from Last.fm or retry finishing authentication",
                    if (busy) "Finishing…" else "Finish",
                ) {
                    finish()
                }
            }
        } else {
            Text(
                "Scrobbling, Discover Weekly, mood mixes and smarter radio are on.",
                style = ChordType.CardSub,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ActionRow(
                "Send now",
                "Send anything still waiting",
                "Send",
            ) {
                Scrobbler.flush()
            }

            ActionRow(
                "Disconnect",
                "Turns off everything Last.fm",
                "Disconnect",
            ) {
                Scrobbler.signOut()

                if (AppSettings.DEFAULT_SIGNER.isNotBlank()) {
                    AppSettings.setLastFmKey("")
                    MixEngine.refreshLastFm()
                }

                status = null
                token = ""
            }
        }

        FmStatus(status)
    }
}
