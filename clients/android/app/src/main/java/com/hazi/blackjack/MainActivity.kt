package com.hazi.blackjack

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Android 17 blocks connections to local network addresses (like 192.168.x.x)
// for apps targeting SDK 37, unless the user grants this permission.
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

class MainActivity : ComponentActivity() {
    private val game = GameState()
    private var connection: Connection? = null
    private var onTable by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var connectError by mutableStateOf("")
    private var pendingJoin: Triple<String, Int, String>? = null
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val join = pendingJoin ?: return@registerForActivityResult
        pendingJoin = null
        if (granted) connect(join.first, join.second, join.third)
        else connectError = "Allow 'nearby devices' / local network access to reach the server"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (onTable) {
                    TableScreen(game, onSend = { connection?.send(it) }, onExit = { finish() })
                } else {
                    ConnectScreen(connectError, connecting, ::join)
                }
            }
        }
    }

    private fun join(ip: String, port: Int, name: String) {
        val needsPermission = Build.VERSION.SDK_INT >= ANDROID_17 &&
                checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingJoin = Triple(ip, port, name)
            askLocalNetwork.launch(LOCAL_NETWORK)   // shows the system prompt, then connects
        } else {
            connect(ip, port, name)
        }
    }

    private fun connect(ip: String, port: Int, name: String) {
        connecting = true
        connectError = ""
        game.me = name
        connection = Connection(
            onLine = { line, arrived ->
                if (line.startsWith("WELCOME")) onTable = true
                if (!onTable && line.startsWith("ERROR")) connectError = line.removePrefix("ERROR ")   // e.g. table is full
                game.handle(line, arrived)
            },
            onClosed = { reason ->
                connecting = false
                if (onTable) game.connectionClosed()
                else if (connectError.isEmpty()) connectError = "Cannot connect: $reason"
            }
        ).also { it.connect(ip, port, name) }
    }

    override fun onDestroy() {
        connection?.close()
        super.onDestroy()
    }
}

@Composable
fun ConnectScreen(error: String, connecting: Boolean, onConnect: (String, Int, String) -> Unit) {
    var ip by remember { mutableStateOf("192.168.") }
    var port by remember { mutableStateOf("5050") }
    var name by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E2030)).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("BLACKJACK", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("first to 20,000 wins", color = Color(0xAAFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Server IP") }, singleLine = true)
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your name") }, singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !connecting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0BE32), contentColor = Color(0xFF14141E)),
            onClick = {
                val p = port.trim().toIntOrNull()
                val n = name.trim().replace(" ", "_")
                when {
                    n.isEmpty() -> inputError = "Enter a name"
                    p == null -> inputError = "Port must be a number"
                    else -> {
                        inputError = ""
                        onConnect(ip.trim(), p, n)
                    }
                }
            }
        ) {
            Text(if (connecting) "Connecting..." else "JOIN TABLE", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(inputError.ifEmpty { error }, color = Color(0xFFFFB4B4), fontWeight = FontWeight.Bold)
    }
}