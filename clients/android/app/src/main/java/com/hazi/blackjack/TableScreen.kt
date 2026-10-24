package com.hazi.blackjack

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hazi.blackjack.GameState.Companion.money
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The table is drawn in this virtual size, then scaled to fit the screen
private const val VW = 400f
private const val VH = 620f
private const val DECK_X = 290f
private const val DECK_Y = 88f

private val Background = Color(0xFF0E2030)
private val Felt = Color(0xFF2CA05C)
private val FeltDark = Color(0xFF106436)
private val CardRed = Color(0xFFB22234)
private val Green = Color(0xFF1EA050)
private val Red = Color(0xFFC83232)
private val Grey = Color(0xFF6E7382)
private val CHIP_VALUES = intArrayOf(100, 500, 1000, 5000)
private val CHIP_COLORS = listOf(Color(0xFF1E5AC8), Color(0xFF7828A0), Color(0xFFE67814), Color(0xFF1E1E1E))

@Composable
fun TableScreen(game: GameState, onSend: (String) -> Unit, onExit: () -> Unit) {
    // redraw every frame: countdown rings and card animations
    // (Controls gets `now` as a parameter, so it is recomposed every frame too)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            game.pump()
            now = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            TopBar(game.bankroll, game.log.toList())
            Canvas(Modifier.weight(1f).fillMaxWidth()) { drawTable(game, now) }
            Controls(game, now, onSend)
        }
        game.overlay?.let { GameOver(it, onExit) }
    }
}

@Composable
private fun TopBar(bankroll: Int, log: List<String>) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("CHIP BALANCE", color = Color(0xAAFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(money(bankroll), color = Color(0xFF6EE68C), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            for (l in log) {
                Text(l, color = if (l.startsWith("!")) Color(0xFFFFA0A0) else Color(0xFFE6E6E6), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Controls(game: GameState, now: Long, onSend: (String) -> Unit) {
    val myTurn = game.current == game.me
    val left = game.secondsLeft(now)
    val fraction = (left / game.turnTotal).toFloat()
    Column(
        Modifier.fillMaxWidth().height(184.dp).background(Color(0x99000000)).padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (myTurn && game.phase == "BETTING") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in CHIP_VALUES.indices) {
                    val v = CHIP_VALUES[i]
                    val ok = game.pendingBet + v <= game.bankroll
                    Canvas(Modifier.size(52.dp).clickable(enabled = ok) { game.pendingBet += v }) {
                        drawChip(center, size.minDimension / 2 - 2, if (ok) CHIP_COLORS[i] else Grey, shortMoney(v))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GameButton("ALL-IN", Red, game.bankroll >= game.minBet) {
                    game.pendingBet = game.bankroll - game.bankroll % 10   // bets must be multiples of 10
                }
                GameButton("CLEAR", Grey, game.pendingBet > 0) { game.pendingBet = 0 }
                Canvas(Modifier.size(64.dp)) { drawRing(center, size.minDimension / 2, fraction, money(game.pendingBet)) }
                GameButton("BET", Green, game.pendingBet >= game.minBet && !game.actionSent) {
                    game.lastBet = game.pendingBet
                    game.act("BET " + game.pendingBet, onSend)
                }
            }
            if (game.bankroll >= GameState.GOAL) {
                GameButton("CASH OUT (lock in the win)", Color(0xFF286EC8), !game.actionSent) { game.act("SPECTATE", onSend) }
            } else {
                Text("min ${money(game.minBet)}  ·  ${ceil(left).toInt()}s left", color = Color(0xAAFFFFFF), fontSize = 12.sp)
            }
        } else if (myTurn && game.phase == "PLAYING") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GameButton("HIT", Green, !game.actionSent, Modifier.width(120.dp).height(64.dp)) { game.act("HIT", onSend) }
                Canvas(Modifier.size(72.dp)) { drawRing(center, size.minDimension / 2, fraction, "${ceil(left).toInt()}s") }
                GameButton("STAND", Red, !game.actionSent, Modifier.width(120.dp).height(64.dp)) { game.act("STAND", onSend) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText(game, now), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (game.current != "-" || game.phase == "RESULTS") {
                    Spacer(Modifier.width(10.dp))
                    Canvas(Modifier.size(30.dp)) { drawRing(center, size.minDimension / 2, fraction, null) }
                }
            }
        }
    }
}

private fun statusText(game: GameState, now: Long): String {
    val left = ceil(game.secondsLeft(now)).toInt()
    val text = when {
        game.phase == "RESULTS" -> "Next round in $left..."
        game.phase == "BETTING" && game.current != "-" -> "Waiting for ${game.current} to bet"
        game.phase == "PLAYING" && game.current != "-" -> "${game.current} is playing"
        else -> "Waiting for players..."
    }
    val mine = game.mySeat()
    return when {
        game.spectating -> "Spectating · $text"
        game.phase == "PLAYING" && mine != null && mine.bet == 0 -> "Sitting out · $text"
        else -> text
    }
}

@Composable
private fun GameButton(label: String, color: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),   // four controls must fit on a phone
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GameOver(text: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GAME OVER", color = Color(0xFFFFD75A), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            GameButton("EXIT", Red, true, onClick = onExit)
        }
    }
}

// ---------- drawing the table ----------

private fun DrawScope.drawTable(game: GameState, now: Long) {
    val s = min(size.width / VW, size.height / VH)
    val ox = (size.width - VW * s) / 2
    val oy = (size.height - VH * s) / 2
    withTransform({
        translate(ox, oy)
        scale(s, s, Offset.Zero)
    }) {
        drawFelt()
        drawDealer(game, now)
        drawOthers(game, now)
        drawMe(game, now)
        drawBanner(game, now)
    }
}

private fun DrawScope.drawFelt() {
    drawRoundRect(Color(0x5A000000), Offset(12f, 14f), Size(384f, 604f), CornerRadius(192f))
    drawRoundRect(Color(0xFF6E4422), Offset(8f, 8f), Size(384f, 604f), CornerRadius(192f))
    drawRoundRect(
        Brush.radialGradient(listOf(Felt, FeltDark), center = Offset(200f, 300f), radius = 380f),
        Offset(20f, 20f), Size(360f, 580f), CornerRadius(180f)
    )
    drawRoundRect(Color(0x5AFFD778), Offset(34f, 34f), Size(332f, 552f), CornerRadius(166f), style = Stroke(1.5f))
    text("BLACKJACK PAYS 3 TO 2", 200f, 182f, 18f, Color(0x3CFFFFFF))
    text("DEALER STANDS ON 17  ·  REACH 20,000", 200f, 200f, 10f, Color(0x3CFFFFFF))
    for (i in 2 downTo 0) drawCard("??", DECK_X + i * 2, DECK_Y - i * 2, 40f, 58f)
}

private fun DrawScope.drawDealer(game: GameState, now: Long) {
    text("DEALER", 200f, 64f, 11f, Color(0xAAFFFFFF))
    if (game.dealerCards.isEmpty()) {
        drawRoundRect(Color(0x28FFFFFF), Offset(140f, 72f), Size(56f, 80f), CornerRadius(8f), style = Stroke(1.5f))
        drawRoundRect(Color(0x28FFFFFF), Offset(204f, 72f), Size(56f, 80f), CornerRadius(8f), style = Stroke(1.5f))
        return
    }
    val end = drawHand(game, "dealer", game.dealerCards, 200f, 72f, 56f, 80f, 34f, now)
    if (game.dealerValue != "?" && game.settled("dealer", game.dealerCards.size)) drawBadge(game.dealerValue, end + 6, 112f)
}

private fun DrawScope.drawOthers(game: GameState, now: Long) {
    val positions = listOf(Offset(105f, 330f), Offset(295f, 330f), Offset(105f, 240f), Offset(295f, 240f))
    var i = 0
    for (s in game.seats) {
        if (s.name == game.me || i >= positions.size) continue
        val pos = positions[i++]
        if (s.cards.isNotEmpty()) {
            val end = drawHand(game, "seat:" + s.name, s.cards, pos.x, pos.y - 62, 38f, 54f, 20f, now)
            if (game.settled("seat:" + s.name, s.cards.size)) drawBadge(s.value, end + 4, pos.y - 35)
        }
        drawPlate(game, s, pos.x, pos.y, 130f, false, now)
        if (s.bet > 0) drawBetStack(pos.x, pos.y + 64, 14f, s.bet)
    }
}

private fun DrawScope.drawMe(game: GameState, now: Long) {
    if (game.myCards.isNotEmpty()) {
        val end = drawHand(game, "me", game.myCards, 200f, 410f, 70f, 100f, 44f, now)
        if (game.settled("me", game.myCards.size)) drawBadge(game.myValue, end + 6, 455f)
    }
    val s = game.mySeat() ?: return
    if (s.bet > 0) drawBetStack(75f, 470f, 18f, s.bet)
    drawPlate(game, s, 200f, 522f, 170f, true, now)
}

// draws the cards dealt so far, centered on cx; returns the x where the last card ends
private fun DrawScope.drawHand(game: GameState, hand: String, cards: List<String>, cx: Float, y: Float, w: Float, h: Float, step: Float, now: Long): Float {
    val visible = cards.indices.count { i -> (game.dealStart["$hand#$i"] ?: 0L) <= now }
    if (visible == 0) return cx
    val total = w + (visible - 1) * step
    val x0 = cx - total / 2
    for (i in 0 until visible) {
        val key = "$hand#$i"
        val x = x0 + i * step
        val st = game.dealStart[key]
        val fs = game.flipStart[key]
        val t = if (st == null) 1f else (now - st) / GameState.DEAL_MS.toFloat()
        when {
            t < 1f -> drawFlying(cards[i], t, x, y, w, h)
            fs != null && now < fs + GameState.FLIP_MS -> drawFlipping(cards[i], (now - fs) / GameState.FLIP_MS.toFloat(), x, y, w, h)
            else -> drawCard(cards[i], x, y, w, h)
        }
    }
    return x0 + total
}

// a card on its way from the deck to (tx, ty), turning face up halfway
private fun DrawScope.drawFlying(code: String, t: Float, tx: Float, ty: Float, w: Float, h: Float) {
    val e = 1 - (1 - t).pow(3)   // ease-out: fast start, soft landing
    val cw = 40f + (w - 40f) * e
    val ch = 58f + (h - 58f) * e
    val x = DECK_X + (tx - DECK_X) * e
    val y = DECK_Y + (ty - DECK_Y) * e
    val center = Offset(x + cw / 2, y + ch / 2)
    rotate((1 - e) * -30f, center) {
        scale(max(0.03f, abs(1 - 2 * e)), 1f, center) {
            drawCard(if (e < 0.5f) "??" else code, x, y, cw, ch)
        }
    }
}

// a card lying on the table turning over (the dealer's hidden card)
private fun DrawScope.drawFlipping(code: String, f: Float, x: Float, y: Float, w: Float, h: Float) {
    val p = max(0f, f)
    scale(max(0.03f, abs(1 - 2 * p)), 1f, Offset(x + w / 2, y + h / 2)) {
        drawCard(if (p < 0.5f) "??" else code, x, y, w, h)
    }
}

private fun DrawScope.drawCard(code: String, x: Float, y: Float, w: Float, h: Float) {
    val r = CornerRadius(w / 7)
    drawRoundRect(Color(0x46000000), Offset(x + 2, y + 3), Size(w, h), r)
    if (code == "??") {
        drawRoundRect(CardRed, Offset(x, y), Size(w, h), r)
        clipRect(x + 4, y + 4, x + w - 4, y + h - 4) {
            var i = -h
            while (i < w) {
                drawLine(Color(0x33FFFFFF), Offset(x + i, y), Offset(x + i + h, y + h), strokeWidth = 1.5f)
                i += 6f
            }
        }
        drawRoundRect(Color.White, Offset(x + 3, y + 3), Size(w - 6, h - 6), r, style = Stroke(2f))
        return
    }
    val rank = code.dropLast(1)
    val suit = code.last()
    val symbol = when (suit) {
        'S' -> "♠"
        'H' -> "♥"
        'D' -> "♦"
        else -> "♣"
    }
    val color = if (suit == 'H' || suit == 'D') Color(0xFFCD1E2D) else Color(0xFF191923)
    drawRoundRect(Color.White, Offset(x, y), Size(w, h), r)
    drawRoundRect(Color(0x33000000), Offset(x, y), Size(w, h), r, style = Stroke(1f))
    for (corner in 0..1) {
        rotate(corner * 180f, Offset(x + w / 2, y + h / 2)) {
            text(rank, x + w * 0.08f, y + h * 0.22f, h * 0.2f, color, align = Paint.Align.LEFT)
            text(symbol, x + w * 0.09f, y + h * 0.38f, h * 0.16f, color, bold = false, align = Paint.Align.LEFT)
        }
    }
    text(symbol, x + w / 2, y + h * 0.66f, h * 0.42f, color, bold = false)
}

private fun DrawScope.drawPlate(game: GameState, s: Seat, cx: Float, y: Float, w: Float, isMe: Boolean, now: Long) {
    val h = 44f
    val x = cx - w / 2
    val turn = s.name == game.current && game.phase != "RESULTS"
    if (turn) {
        val pulse = 0.5f + 0.5f * sin(now / 200.0).toFloat()
        drawRoundRect(Color(1f, 0.86f, 0.24f, 0.5f + 0.4f * pulse), Offset(x - 4, y - 4), Size(w + 8, h + 8), CornerRadius(14f), style = Stroke(3f))
    }
    drawRoundRect(if (s.spectator) Color(0xD23C3C46) else Color(0xFFFAFAF5), Offset(x, y), Size(w, h), CornerRadius(12f))
    text(if (isMe) "${s.name} (you)" else s.name, cx, y + 18, 14f, if (s.spectator) Color(0xFFDCDCDC) else Color(0xFF141E28))
    if (s.spectator) {
        val label = if (s.bankroll >= GameState.GOAL) "WINNER · " else "SPECTATING · "
        text(label + money(s.bankroll), cx, y + 36, 11f, Color(0xFFC8C8D2))
    } else {
        text(money(s.bankroll), cx, y + 36, 12f, Color(0xFF148246))
    }
    if (turn && !isMe) {
        val rx = if (cx > VW / 2) x - 20 else x + w + 20   // ring on the side facing the table center
        val left = game.secondsLeft(now)
        drawRing(Offset(rx, y + h / 2), 15f, (left / game.turnTotal).toFloat(), ceil(left).toInt().toString())
    }
}

// the bet as a stack of chips (biggest at the bottom, at most 5) with the amount under it
private fun DrawScope.drawBetStack(cx: Float, cy: Float, r: Float, amount: Int) {
    val stack = mutableListOf<Int>()
    var rest = amount
    for (i in CHIP_VALUES.indices.reversed()) {
        while (rest >= CHIP_VALUES[i] && stack.size < 5) {
            stack.add(i)
            rest -= CHIP_VALUES[i]
        }
    }
    if (stack.isEmpty()) stack.add(0)
    for ((k, i) in stack.withIndex()) {
        val top = k == stack.size - 1
        drawChip(Offset(cx, cy - k * (r / 5 + 1)), r, CHIP_COLORS[i], if (top) shortMoney(CHIP_VALUES[i]) else "")
    }
    text(money(amount), cx, cy + r + 14, 11f, Color.White)
}

private fun DrawScope.drawChip(c: Offset, r: Float, base: Color, label: String) {
    drawCircle(Color(0x5A000000), r, c + Offset(1f, 3f))
    drawCircle(base, r, c)
    val d = r * 0.88f
    for (i in 0 until 6) {
        drawArc(Color(0xE6FFFFFF), i * 60f + 10f, 24f, false, Offset(c.x - d, c.y - d), Size(2 * d, 2 * d), style = Stroke(r * 0.24f))
    }
    drawCircle(Color(base.red * 0.7f, base.green * 0.7f, base.blue * 0.7f), r * 0.62f, c)
    drawCircle(Color(0xC8FFFFFF), r * 0.62f, c, style = Stroke(1.5f))
    if (label.isNotEmpty()) text(label, c.x, c.y + r * 0.19f, r * (if (label.length > 3) 0.42f else 0.55f), Color.White)
}

private fun DrawScope.drawRing(c: Offset, r: Float, fraction: Float, label: String?) {
    val f = fraction.coerceIn(0f, 1f)
    drawCircle(Color(0xAA000000), r, c)
    val color = when {
        f > 0.5f -> Color(0xFF46DC78)
        f > 0.25f -> Color(0xFFFAC832)
        else -> Color(0xFFF0463C)
    }
    val inset = max(3f, r / 8f)
    drawArc(
        color, -90f, 360f * f, false,
        Offset(c.x - r + inset, c.y - r + inset), Size(2 * (r - inset), 2 * (r - inset)),
        style = Stroke(max(3f, r / 6f), cap = StrokeCap.Round)
    )
    if (label != null) text(label, c.x, c.y + r / 6f, max(10f, r / 2.4f), Color.White)
}

private fun DrawScope.drawBadge(value: String, x: Float, y: Float) {
    if (value.isEmpty() || value == "-" || value == "?") return
    val v = value.toIntOrNull() ?: 0
    val w = 14f + value.length * 9f
    val color = when {
        v > 21 -> Color(0xFFC82828)
        v == 21 -> Color(0xFFDCA014)
        else -> Color(0xDC141419)
    }
    drawRoundRect(color, Offset(x, y - 12), Size(w, 24f), CornerRadius(12f))
    text(value, x + w / 2, y + 5, 14f, Color.White)
}

private fun DrawScope.drawBanner(game: GameState, now: Long) {
    val banner = game.banner ?: return
    if (now > game.bannerEnd) return
    val alpha = min(1f, (game.bannerEnd - now) / 400f)
    drawRoundRect(game.bannerColor.copy(alpha = alpha), Offset(50f, 272f), Size(300f, 56f), CornerRadius(16f))
    drawRoundRect(Color.White.copy(alpha = alpha), Offset(50f, 272f), Size(300f, 56f), CornerRadius(16f), style = Stroke(2.5f))
    text(banner, 200f, 308f, if (banner.length > 18) 18f else 24f, Color.White.copy(alpha = alpha))
}

// Compose has no simple "draw this string here", so text goes through Android's own canvas
private fun DrawScope.text(
    s: String, x: Float, y: Float, sizePx: Float, color: Color,
    bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        this.color = color.toArgb()
        textAlign = align
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    drawIntoCanvas { it.nativeCanvas.drawText(s, x, y, paint) }
}

private fun shortMoney(v: Int) = if (v >= 1000) "${v / 1000}K" else v.toString()
