package com.hazi.blackjack

import androidx.compose.ui.graphics.Color
import java.util.Locale

class Seat(
    val name: String,
    val bankroll: Int,
    val bet: Int,
    val cards: List<String>,
    val value: String,
    val spectator: Boolean
)

// Everything the screen shows, built from the server's lines. Used only on the UI thread.
class GameState {
    var me = ""
    var bankroll = 0
    var lastBet = 0
    var pendingBet = 0
    var minBet = 100
    var myCards: List<String> = emptyList()
    var myValue = ""
    var dealerCards: List<String> = emptyList()
    var dealerValue = ""
    var phase = "WAITING"
    var current = "-"
    var turnEnd = 0L
    var turnTotal = 20.0
    val seats = mutableListOf<Seat>()
    val log = ArrayDeque<String>()
    var banner: String? = null
    var bannerColor = Color.Gray
    var bannerEnd = 0L
    var overlay: String? = null
    var spectating = false
    var actionSent = false   // blocks double taps until the server answers with the next TABLE

    // card animations, key = hand id + "#" + card index
    val dealStart = HashMap<String, Long>()
    val flipStart = HashMap<String, Long>()
    private val shownSeatCards = HashMap<String, List<String>>()
    private val incoming = ArrayDeque<Pair<String, Long>>()
    private var nextFree = 0L
    private var busyUntil = 0L

    fun handle(line: String, arrived: Long) {
        incoming.addLast(line to arrived)
        pump()
    }

    // Lines wait while cards are still flying, so the game unfolds in order
    // (the result shows only after the dealer's last card lands).
    // SEAT lines belong to the TABLE snapshot right before them, so they never wait.
    fun pump() {
        while (incoming.isNotEmpty()) {
            val (line, arrived) = incoming.first()
            if (!line.startsWith("SEAT") && System.currentTimeMillis() < busyUntil) return
            incoming.removeFirst()
            process(line, arrived)
        }
    }

    private fun process(line: String, arrived: Long) {
        val p = line.split(" ")
        val rest = line.substringAfter(' ', "")
        when (p[0]) {
            "WELCOME" -> {
                bankroll = p[1].toInt()
                if (p.size > 2) me = p[2]   // the server may change the name (duplicates, symbols)
            }
            "YOUR_BET" -> {
                minBet = p[1].toInt()
                pendingBet = 0
            }
            "BANKROLL" -> bankroll = p[1].toInt()
            "HAND" -> {
                val c = cards(p[1])
                animate("me", myCards, c)
                myCards = c
                myValue = p[2]
            }
            "DEALER" -> {
                val c = cards(p[1])
                animate("dealer", dealerCards, c)
                dealerCards = c
                dealerValue = p[2]
            }
            "RESULT" -> showResult(p[1], p[2].toInt())
            "TURN", "DONE" -> {}   // the TABLE line that follows tells whose turn it is
            "SPECTATING" -> spectating = true
            "GAME_OVER" -> {
                banner = null
                overlay = when {
                    p[1] == "DEALER" -> "THE DEALER WINS"
                    p[1].contains(",") -> "WINNERS: " + p[1].replace(",", ", ")
                    else -> "WINNER: " + p[1]
                }
            }
            "MSG" -> addLog(rest)
            "ERROR" -> {
                addLog("! $rest")
                setBanner(rest, Color(0xFFBE2828), 1800)
            }
            "TABLE" -> {
                if (p[1] == "BETTING" && phase != "BETTING") startNewRound()
                phase = p[1]
                current = p[2]
                actionSent = false
                turnEnd = arrived + (p[3].toDouble() * 1000).toLong()
                turnTotal = maxOf(1.0, p[4].toDouble())
                seats.clear()
            }
            "SEAT" -> {
                val s = Seat(p[1], p[2].toInt(), p[3].toInt(), if (p[4] == "-") emptyList() else cards(p[4]), p[5], p[6] == "SPECTATOR")
                seats.add(s)
                if (s.name == me) {
                    bankroll = s.bankroll
                    spectating = s.spectator
                } else {
                    animate("seat:" + s.name, shownSeatCards[s.name] ?: emptyList(), s.cards)
                    shownSeatCards[s.name] = s.cards
                }
            }
            else -> addLog(line)
        }
    }

    private fun startNewRound() {
        myCards = emptyList()
        myValue = ""
        dealerCards = emptyList()
        dealerValue = ""
        dealStart.clear()
        flipStart.clear()
        shownSeatCards.clear()
    }

    fun connectionClosed() {
        if (overlay == null) overlay = "CONNECTION LOST"
    }

    fun act(command: String, send: (String) -> Unit) {
        actionSent = true
        send(command)
    }

    private fun cards(list: String) = list.split(",")

    private fun addLog(text: String) {
        log.addLast(text)
        while (log.size > 2) log.removeFirst()
    }

    private fun showResult(result: String, newBankroll: Int) {
        val net = newBankroll - (bankroll + lastBet)   // bankroll still has the bet taken out
        bankroll = newBankroll
        when (result) {
            "WIN" -> setBanner("YOU WIN  +" + money(net), Color(0xFF1E9646), 3500)
            "BLACKJACK" -> setBanner("BLACKJACK!  +" + money(net), Color(0xFFD29614), 3500)
            "PUSH" -> setBanner("PUSH  " + money(0), Color(0xFF5A5A6E), 3500)
            else -> setBanner("YOU LOSE  -" + money(lastBet), Color(0xFFAA232D), 3500)
        }
    }

    private fun setBanner(text: String, color: Color, ms: Int) {
        banner = text
        bannerColor = color
        bannerEnd = System.currentTimeMillis() + ms
    }

    // new cards get a start time, one after another; a card that changed (?? -> real) gets a flip
    private fun animate(hand: String, before: List<String>, after: List<String>) {
        val now = System.currentTimeMillis()
        for (i in after.indices) {
            val key = "$hand#$i"
            if (i >= before.size) {
                val start = maxOf(now, nextFree)
                dealStart[key] = start
                nextFree = start + DEAL_GAP
                busyUntil = maxOf(busyUntil, start + DEAL_MS)
            } else if (before[i] != after[i]) {
                val start = maxOf(now, nextFree)
                flipStart[key] = start
                nextFree = start + FLIP_MS
                busyUntil = maxOf(busyUntil, start + FLIP_MS)
            }
        }
    }

    // true when all cards of this hand have landed (then its value can be shown)
    fun settled(hand: String, n: Int): Boolean {
        val now = System.currentTimeMillis()
        for (i in 0 until n) {
            val st = dealStart["$hand#$i"]
            if (st != null && now < st + DEAL_MS) return false
            val fs = flipStart["$hand#$i"]
            if (fs != null && now < fs + FLIP_MS) return false
        }
        return true
    }

    fun secondsLeft(now: Long) = maxOf(0.0, (turnEnd - now) / 1000.0)

    fun mySeat() = seats.firstOrNull { it.name == me }

    companion object {
        const val DEAL_MS = 380    // flight time of one card
        const val DEAL_GAP = 260   // time between two dealt cards
        const val FLIP_MS = 320
        const val GOAL = 20000

        fun money(n: Int): String = String.format(Locale.US, "$%,d", n)
    }
}
