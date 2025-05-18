package com.example.ai03

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class OllamaRequest(val model: String, val prompt: String)

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
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ollama Voice Chat") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
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
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    isLoading = true
                    response = ""
                    fetchOllamaResponse(prompt) {
                        response = it
                        isLoading = false
                    }
                },
                enabled = prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send")
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = response,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

fun fetchOllamaResponse(prompt: String, onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val request = OllamaRequest(model = "llama3", prompt = prompt)
            val response: HttpResponse = client.post("http://192.168.31.43:11434/api/generate") {
                setBody(Json.encodeToString(request))
                headers.append("Content-Type", "application/json")
            }
            val responseBody = response.bodyAsText()
            // Ollama streams responses, so parse accordingly
            val lines = responseBody.lines()
            val answer = buildString {
                for (line in lines) {
                    if (line.isNotBlank()) {
                        val obj = Json.decodeFromString<JsonObject>(line)
                        append(obj["response"]?.jsonPrimitive?.content ?: "")
                    }
                }
            }
            onResult(answer)
        } catch (e: Exception) {
            onResult("Error: ${e.message}")
        }
    }
}
