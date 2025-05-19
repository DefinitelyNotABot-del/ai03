@file:Suppress("DEPRECATION")

package com.example.ai03

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.Locale

// ---- FIX: Move enum to top-level ----
enum class ConnectionStatus {
    NOT_CONNECTED, SCANNING, CONNECTED
}

val client = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val context = LocalContext.current

    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var discoveredHost by remember { mutableStateOf<String?>(null) }
    var discoveredPort by remember { mutableStateOf<Int?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf(ConnectionStatus.NOT_CONNECTED) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                prompt = matches[0]
            }
        }
    }

    // ---- NSD Discovery logic ----
    fun startDiscovery() {
        isScanning = true
        scanError = null
        discoveredHost = null
        discoveredPort = null
        connectionStatus = ConnectionStatus.SCANNING

        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceType = "_ollama._tcp." // Must match the type advertised by your PC

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NSD", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("NSD", "Service discovery success: $service")
                if (service.serviceType == serviceType) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            isScanning = false
                            scanError = "Resolve failed: $errorCode"
                            connectionStatus = ConnectionStatus.NOT_CONNECTED
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d("NSD", "Service resolved: $serviceInfo")
                            discoveredHost = serviceInfo.host.hostAddress
                            discoveredPort = serviceInfo.port
                            isScanning = false
                            scanError = null
                            connectionStatus = ConnectionStatus.CONNECTED
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("NSD", "service lost: $service")
                isScanning = false
                scanError = "Service lost"
                connectionStatus = ConnectionStatus.NOT_CONNECTED
                discoveredHost = null
                discoveredPort = null
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("NSD", "Discovery stopped: $serviceType")
                isScanning = false
                if (discoveredHost == null) {
                    connectionStatus = ConnectionStatus.NOT_CONNECTED
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery failed: Error code:$errorCode")
                isScanning = false
                scanError = "Discovery failed: $errorCode"
                connectionStatus = ConnectionStatus.NOT_CONNECTED
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Stop Discovery failed: Error code:$errorCode")
                isScanning = false
            }
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    // ---- UI for Connection Status ----
    @Composable
    fun ConnectionStatusBar() {
        val (text, color) = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> "🟢 Connected to $discoveredHost:$discoveredPort" to Color(0xFF4CAF50)
            ConnectionStatus.SCANNING -> "🟡 Scanning for Ollama server..." to Color(0xFFFFC107)
            ConnectionStatus.NOT_CONNECTED -> "🔴 Not Connected" to Color(0xFFF44336)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color, shape = RoundedCornerShape(8.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Color.White)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Automation Agent") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
            // Connection Status Bar
            ConnectionStatusBar()

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { startDiscovery() },
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "Scanning..." else "Scan for Ollama Server")
            }

            if (scanError != null) {
                Text(
                    text = scanError!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // Retry Button
                Button(
                    onClick = { startDiscovery() },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Retry")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Enter prompt") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE,
                                Locale.getDefault()
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                "Speak now"
                            )
                        }
                        voiceLauncher.launch(intent)
                    }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Voice input")
                    }
                },
                enabled = connectionStatus == ConnectionStatus.CONNECTED
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (discoveredHost != null && discoveredPort != null) {
                        isLoading = true
                        response = ""
                        lastError = null
                        fetchOllamaResponse(
                            prompt,
                            discoveredHost!!,
                            discoveredPort!!,
                        ) { result ->
                            if (result.startsWith("Error:")) {
                                lastError = result
                            } else {
                                response = result
                            }
                            isLoading = false
                        }
                    }
                },
                enabled = prompt.isNotBlank() && connectionStatus == ConnectionStatus.CONNECTED,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send")
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator()
            } else if (lastError != null) {
                Text(
                    text = lastError!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = response,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

fun fetchOllamaResponse(prompt: String, host: String, port: Int, onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val request = buildJsonObject {
                put("model", "gemma3")
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }
            val url = "http://$host:$port/api/chat"
            val response: HttpResponse = client.post(url) {
                setBody(Json.encodeToString(JsonObject.serializer(), request))
                headers.append("Content-Type", "application/json")
            }
            val responseBody = response.bodyAsText()
            // Ollama streams responses, so parse each line
            val lines = responseBody.trim().lines()
            val answer = StringBuilder()
            for (line in lines) {
                if (line.isNotBlank()) {
                    val obj = Json.decodeFromString<JsonObject>(line)
                    val messageObj = obj["message"]?.jsonObject
                    val content = messageObj?.get("content")?.jsonPrimitive?.contentOrNull
                    if (!content.isNullOrBlank()) answer.append(content)
                }
            }
            onResult(answer.toString().ifBlank { "No response" })
        } catch (e: Exception) {
            onResult("Error: ${e.message}")
        }
    }
}
