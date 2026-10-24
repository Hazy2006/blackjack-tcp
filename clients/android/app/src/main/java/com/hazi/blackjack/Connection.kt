package com.hazi.blackjack

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

// The TCP connection to the server. Android forbids network calls on the UI thread,
// so reading happens on a background thread and writing on a single sender thread.
// Every received line is handed to the UI thread through a Handler.
class Connection(
    private val onLine: (line: String, arrived: Long) -> Unit,
    private val onClosed: (reason: String) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: Writer? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    fun connect(ip: String, port: Int, name: String) {
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 5000)
                socket = s
                out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                send("NAME $name")
                // BufferedReader keeps its own buffer and splits the TCP stream on "\n"
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = input.readLine() ?: break
                    val arrived = System.currentTimeMillis()
                    ui.post { onLine(line, arrived) }
                }
                ui.post { onClosed("Connection closed") }
            } catch (e: IOException) {
                ui.post { onClosed(e.message ?: "Connection error") }
            }
        }.start()
    }

    // Every message is one line ending in "\n"
    fun send(line: String) {
        sender.execute {
            try {
                out?.apply {
                    write(line + "\n")
                    flush()
                }
            } catch (e: IOException) {
                // the reader thread will notice the closed connection
            }
        }
    }

    fun close() {
        sender.execute {
            try {
                socket?.close()
            } catch (e: IOException) {
                // already closed
            }
        }
        sender.shutdown()
    }
}
