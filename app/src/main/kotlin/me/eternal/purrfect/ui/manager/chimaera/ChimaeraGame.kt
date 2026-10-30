package me.eternal.purrfect.ui.manager.chimaera

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import kotlin.math.*
import kotlin.random.Random

// ── Game constants ────────────────────────────────────────────────────────────

private const val LANES = 3
private const val BASE_SPEED = 0.003f
private const val SPEED_INCREMENT = 0.0002f
private const val FORMATION_INTERVAL_MS = 15_000L
private const val BASE_BULLET_SPEED = 0.007f
private const val FIRE_RATE_MS = 600L
private const val BUFF_DURATION_MS = 6000L
private const val PREF_HIGH_SCORE = "chimaera_high_score"

private val starWarsQuotes = listOf(
    "Not every shadow is cast by the enemy." to "Grand Admiral Thrawn",
    "No one is ever completely immune to error." to "Grand Admiral Thrawn",
    "Experience is the best teacher." to "Grand Admiral Thrawn",
    "To defeat an enemy, you must know them." to "Grand Admiral Thrawn",
    "One's own knowledge is never complete." to "Grand Admiral Thrawn",
    "Do or do not. There is no try." to "Master Yoda",
    "The greatest teacher, failure is." to "Master Yoda",
    "Always in motion is the future." to "Master Yoda",
    "Your focus determines your reality." to "Master Yoda",
    "Patience you must have, my young Padawan." to "Master Yoda",
    "The Force will be with you. Always." to "General Obi-Wan Kenobi",
    "In my experience, there is no such thing as luck." to "General Obi-Wan Kenobi",
    "Your eyes can deceive you. Don't trust them." to "General Obi-Wan Kenobi",
    "Train yourself to let go of everything you fear to lose." to "Master Yoda",
    "Hope is not lost today, it is found." to "Princess Leia Organa",
    "Never tell me the odds!" to "Captain Han Solo",
    "I find your lack of faith disturbing." to "Lord Vader",
    "Difficult to see. Always in motion is the future." to "Master Yoda",
    "Rebellions are built on hope." to "Jyn Erso",
    "We are what they grow beyond." to "Master Yoda"
)

// ── Data classes ──────────────────────────────────────────────────────────────

private data class Enemy(
    val id: Int,
    var lane: Int,
    var y: Float,
    val type: EnemyType,
    var hits: Int = 0,
    var alive: Boolean = true
)

private enum class EnemyType(val maxHits: Int, val pointValue: Int, val speedMult: Float) {
    ASTEROID(2, 1, 1.0f),
    COMET(1, 3, 2.2f)
}

private data class Bullet(
    val id: Int,
    val lane: Int,
    var y: Float,
    var alive: Boolean = true
)

private data class Buff(
    val id: Int,
    val lane: Int,
    var y: Float,
    val type: BuffType,
    var alive: Boolean = true
)

private enum class BuffType(val label: String, val emoji: String) {
    SHIELD("Shield", "🛡"),
    GHOST("Ghost", "👻"),
    VELOCITY("Velocity", "⚡"),
    MAGNET("Magnet", "🧲"),
    OVERLOAD("Overload", "🔥"),
    MULTIPLIER("Multiplier", "×2")
}

private data class ActiveBuff(val type: BuffType, val expiresAt: Long)

private enum class GameState { PLAYING, DEAD, GAME_OVER, PAUSED }

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun ChimaeraGame(
    prefs: SharedPreferences,
    onExit: () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()

    var gameState by remember { mutableStateOf(GameState.PLAYING) }
    var playerLane by remember { mutableIntStateOf(1) }
    var lives by remember { mutableIntStateOf(3) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(prefs.getInt(PREF_HIGH_SCORE, 0)) }
    var combo by remember { mutableIntStateOf(0) }

    var enemies by remember { mutableStateOf(listOf<Enemy>()) }
    var bullets by remember { mutableStateOf(listOf<Bullet>()) }
    var buffs by remember { mutableStateOf(listOf<Buff>()) }
    var activeBuffs by remember { mutableStateOf(listOf<ActiveBuff>()) }

    var nextEnemyId by remember { mutableIntStateOf(0) }
    var nextBulletId by remember { mutableIntStateOf(0) }
    var nextBuffId by remember { mutableIntStateOf(0) }

    var lastFrameTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastFireTime by remember { mutableLongStateOf(0L) }
    var lastFormationTime by remember { mutableLongStateOf(0L) }

    var deathFlash by remember { mutableFloatStateOf(0f) }
    var showHint by remember { mutableStateOf(true) }

    val stars = remember {
        List(60) {
            Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 0.4f + 0.1f)
        }
    }
    var starOffset by remember { mutableFloatStateOf(0f) }

    // Onboarding hint timer
    LaunchedEffect(Unit) {
        delay(3500)
        showHint = false
    }

    // Fixed Game Loop: Recalculates effect flags internally to ensure state changes are caught immediately.
    LaunchedEffect(Unit) {
        while (true) {
            if (gameState == GameState.PLAYING) {
                val currentTime = System.currentTimeMillis()
                val dt = (currentTime - lastFrameTime).coerceIn(1L, 50L)
                lastFrameTime = currentTime

                // Recalculate flags inside the loop to ensure they reflect the latest 'activeBuffs' state
                activeBuffs = activeBuffs.filter { it.expiresAt > currentTime }
                val loopHasOverload = activeBuffs.any { it.type == BuffType.OVERLOAD }
                val loopHasVelocity = activeBuffs.any { it.type == BuffType.VELOCITY }
                val loopHasMagnet = activeBuffs.any { it.type == BuffType.MAGNET }
                val loopHasMultiplier = activeBuffs.any { it.type == BuffType.MULTIPLIER }
                val loopHasGhost = activeBuffs.any { it.type == BuffType.GHOST }
                val loopHasShield = activeBuffs.any { it.type == BuffType.SHIELD }

                val currentFireRate = if (loopHasOverload) FIRE_RATE_MS / 2 else FIRE_RATE_MS
                val currentBulletSpeed = if (loopHasVelocity) BASE_BULLET_SPEED * 1.6f else BASE_BULLET_SPEED
                val currentSpeed = BASE_SPEED + (score / 10) * SPEED_INCREMENT

                if (currentTime - lastFireTime > currentFireRate) {
                    lastFireTime = currentTime
                    val newBullet = Bullet(id = nextBulletId++, lane = playerLane, y = 0.88f)
                    bullets = bullets + newBullet
                }

                bullets = bullets.map { b ->
                    b.copy(y = b.y - currentBulletSpeed * dt / 16f)
                }.filter { it.alive && it.y > -0.05f }

                if (Random.nextFloat() < 0.02f + score * 0.0005f) {
                    val type = if (Random.nextFloat() < 0.25f) EnemyType.COMET else EnemyType.ASTEROID
                    enemies = enemies + Enemy(id = nextEnemyId++, lane = Random.nextInt(LANES), y = -0.05f, type = type)
                }

                if (currentTime - lastFormationTime > FORMATION_INTERVAL_MS) {
                    lastFormationTime = currentTime
                    val formationEnemies = (0 until LANES).map { lane ->
                        Enemy(id = nextEnemyId++, lane = lane, y = -0.05f - lane * 0.08f, type = EnemyType.ASTEROID)
                    }
                    enemies = enemies + formationEnemies
                }

                enemies = enemies.map { e ->
                    e.copy(y = e.y + currentSpeed * e.type.speedMult * dt / 16f)
                }

                buffs = buffs.map { b ->
                    b.copy(y = b.y + currentSpeed * 0.7f * dt / 16f)
                }.filter { it.alive && it.y < 1.1f }

                // Improved Magnet: pull from ANY lane if within range
                if (loopHasMagnet) {
                    buffs = buffs.map { b ->
                        if (b.y > 0.2f && b.lane != playerLane) b.copy(lane = playerLane) else b
                    }
                }

                val deadEnemyIds = mutableSetOf<Int>()
                val deadBulletIds = mutableSetOf<Int>()

                for (bullet in bullets) {
                    for (enemy in enemies) {
                        if (!enemy.alive) continue
                        if (enemy.lane == bullet.lane && abs(enemy.y - bullet.y) < 0.06f) {
                            enemy.hits++
                            deadBulletIds.add(bullet.id)
                            if (enemy.hits >= enemy.type.maxHits) {
                                enemy.alive = false
                                deadEnemyIds.add(enemy.id)
                                combo++
                                val points = enemy.type.pointValue * (if (loopHasMultiplier) 2 else 1)
                                score += points + (combo / 5)

                                if (Random.nextFloat() < 0.3f) {
                                    val buffType = BuffType.entries.random()
                                    buffs = buffs + Buff(id = nextBuffId++, lane = enemy.lane, y = enemy.y, type = buffType)
                                }
                            } else {
                                combo = 0
                            }
                        }
                    }
                }

                bullets = bullets.map { if (it.id in deadBulletIds) it.copy(alive = false) else it }.filter { it.alive }
                enemies = enemies.filter { it.alive }

                val collectedBuffIds = mutableSetOf<Int>()
                for (buff in buffs) {
                    if (buff.lane == playerLane && buff.y > 0.82f && buff.y < 0.95f) {
                        collectedBuffIds.add(buff.id)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        activeBuffs = activeBuffs.filter { it.type != buff.type } +
                            ActiveBuff(buff.type, currentTime + BUFF_DURATION_MS)
                    }
                }
                buffs = buffs.filter { it.id !in collectedBuffIds }

                if (!loopHasGhost) {
                    val hitEnemies = enemies.filter { it.y > 0.90f && it.lane == playerLane }
                    if (hitEnemies.isNotEmpty()) {
                        enemies = enemies.filter { it !in hitEnemies }
                        combo = 0
                        if (loopHasShield) {
                            activeBuffs = activeBuffs.filter { it.type != BuffType.SHIELD }
                            deathFlash = 1f
                        } else {
                            lives--
                            deathFlash = 1f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (lives <= 0) {
                                gameState = GameState.GAME_OVER
                                if (score > highScore) {
                                    highScore = score
                                    prefs.edit().putInt(PREF_HIGH_SCORE, highScore).apply()
                                }
                            } else {
                                gameState = GameState.DEAD
                                delay(1500)
                                enemies = emptyList()
                                bullets = emptyList()
                                buffs = emptyList()
                                activeBuffs = emptyList()
                                gameState = GameState.PLAYING
                                lastFrameTime = System.currentTimeMillis()
                            }
                        }
                    }
                }

                enemies = enemies.filter { it.y < 1.05f }
                if (deathFlash > 0f) deathFlash = (deathFlash - 0.08f).coerceAtLeast(0f)
                starOffset = (starOffset + currentSpeed * 0.5f * dt / 16f) % 1f
            } else if (gameState == GameState.PAUSED) {
                lastFrameTime = System.currentTimeMillis()
            }
            delay(16)
        }
    }

    // Derived effect flags for UI/Drawing
    val nowMs = System.currentTimeMillis()
    val uiHasShield by remember(activeBuffs, nowMs) { derivedStateOf { activeBuffs.any { it.type == BuffType.SHIELD } } }
    val uiHasGhost by remember(activeBuffs, nowMs) { derivedStateOf { activeBuffs.any { it.type == BuffType.GHOST } } }
    val uiHasOverload by remember(activeBuffs, nowMs) { derivedStateOf { activeBuffs.any { it.type == BuffType.OVERLOAD } } }
    val uiHasVelocity by remember(activeBuffs, nowMs) { derivedStateOf { activeBuffs.any { it.type == BuffType.VELOCITY } } }
    val uiHasMagnet by remember(activeBuffs, nowMs) { derivedStateOf { activeBuffs.any { it.type == BuffType.MAGNET } } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (skin.isDark) Color.Black else Color(0xFF1A1A2E))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(gameState) {
                    detectTapGestures { offset ->
                        if (gameState != GameState.PLAYING) return@detectTapGestures
                        val third = size.width / 3f
                        val tappedLane = (offset.x / third).toInt().coerceIn(0, 2)
                        if (tappedLane != playerLane) {
                            playerLane = tappedLane
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val laneWidth = w / LANES

            stars.forEach { (sx, sy, brightness) ->
                val drawY = ((sy + starOffset) % 1f) * h
                drawCircle(color = Color.White.copy(alpha = brightness), radius = brightness * 2f, center = Offset(sx * w, drawY))
            }

            for (i in 1 until LANES) {
                val x = laneWidth * i
                drawLine(
                    color = skin.glowPrimary.copy(alpha = 0.3f),
                    start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
                )
            }

            val activeLaneX = playerLane * laneWidth
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, skin.glowPrimary.copy(alpha = 0.06f), Color.Transparent),
                    startY = h * 0.6f, endY = h
                ),
                topLeft = Offset(activeLaneX, 0f), size = androidx.compose.ui.geometry.Size(laneWidth, h)
            )

            if (deathFlash > 0f) drawRect(color = Color.Red.copy(alpha = deathFlash * 0.3f))

            enemies.forEach { enemy ->
                val ey = enemy.y * h
                val enemyX = enemy.lane * laneWidth + laneWidth / 2f
                val color = if (enemy.type == EnemyType.ASTEROID) skin.glowSecondary else Color(0xFFFF9B5F)
                if (enemy.type == EnemyType.ASTEROID) drawAsteroid(enemyX, ey, 22f, color, enemy.hits)
                else drawComet(enemyX, ey, 14f, color)
            }

            bullets.forEach { bullet ->
                val bx = bullet.lane * laneWidth + laneWidth / 2f
                val by = bullet.y * h
                drawLine(
                    brush = Brush.verticalGradient(listOf(skin.glowPrimary, skin.glowPrimary.copy(alpha = 0f)), startY = by - 24f, endY = by),
                    start = Offset(bx, by - 24f), end = Offset(bx, by), strokeWidth = 3f
                )
                drawCircle(color = skin.glowPrimary, radius = 4f, center = Offset(bx, by - 24f))
            }

            buffs.forEach { buff ->
                val bx = buff.lane * laneWidth + laneWidth / 2f
                val by = buff.y * h
                drawBuffIcon(bx, by, buff.type, skin.glowPrimary, textMeasurer)
            }

            val px = playerLane * laneWidth + laneWidth / 2f
            val py = h * 0.88f
            
            drawSpacecraft(
                cx = px, cy = py, scale = 28f,
                primaryColor = skin.glowPrimary.copy(alpha = if (uiHasGhost) 0.4f else 1f),
                engineColor = if (uiHasOverload) Color.Red else skin.glowSecondary.copy(alpha = if (uiHasGhost) 0.4f else 1f),
                hasShield = uiHasShield,
                shieldColor = Color(0xFF5FD8FF),
                hasVelocity = uiHasVelocity,
                hasMagnet = uiHasMagnet
            )
        }

        // HUD: Top Bar
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp)) {
            Row(modifier = Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.08f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))) {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onExit() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.08f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))) {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); gameState = if (gameState == GameState.PAUSED) GameState.PLAYING else GameState.PAUSED }, modifier = Modifier.size(40.dp)) {
                        Icon(if (gameState == GameState.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (activeBuffs.isNotEmpty()) {
                Row(modifier = Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeBuffs.forEach { buff ->
                        val remaining = ((buff.expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                        Surface(shape = RoundedCornerShape(8.dp), color = skin.glowPrimary.copy(alpha = 0.15f), border = BorderStroke(0.5.dp, skin.glowPrimary.copy(alpha = 0.3f))) {
                            Text(text = "${buff.type.emoji} ${remaining}s", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = skin.glowPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }

            Column(modifier = Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i -> Text(text = "♦", fontSize = 18.sp, color = if (i < lives) skin.glowPrimary else Color.White.copy(alpha = 0.2f)) }
                }
                Text(text = score.toString().padStart(6, '0'), fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                Text(text = "BEST  $highScore", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = skin.glowSecondary.copy(alpha = 0.7f))
            }
        }

        AnimatedVisibility(visible = showHint, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(), modifier = Modifier.align(Alignment.Center)) {
            Text(text = "◀ TAP LANES TO MANEUVER ▶", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = skin.glowPrimary, textAlign = TextAlign.Center)
        }

        if (gameState == GameState.PAUSED) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Text("SYSTEM STANDBY", fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (gameState == GameState.GAME_OVER) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
                    Text("▓".repeat(24), fontFamily = FontFamily.Monospace, color = skin.glowPrimary.copy(alpha = 0.4f), fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("I.S.D. CHIMAERA", fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("SCORE", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = skin.glowPrimary.copy(alpha = 0.7f))
                    Text("$score", fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (score >= highScore && score > 0) Text("NEW RECORD", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = skin.glowSecondary)
                    Text("BEST  $highScore", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = skin.textSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("─".repeat(20), fontFamily = FontFamily.Monospace, color = skin.glowPrimary.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("DESIGNED & BUILT BY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = skin.textSecondary)
                    Text("ᴋᴀʟᴀᴅɪɴ", fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = skin.glowPrimary)
                    Text("FOR APHELION  •  PURRFECT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = skin.textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val randomQuote = remember(gameState) { starWarsQuotes.random() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "\"${randomQuote.first}\"",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = skin.textSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Text(
                            text = "— ${randomQuote.second}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = skin.glowSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("▓".repeat(24), fontFamily = FontFamily.Monospace, color = skin.glowPrimary.copy(alpha = 0.4f), fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onExit() },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { 
                            Text("EXIT", fontFamily = FontFamily.Monospace) 
                        }
                        androidx.compose.material3.Button(
                            onClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                score = 0
                                lives = 3
                                combo = 0
                                enemies = listOf()
                                bullets = listOf()
                                buffs = listOf()
                                activeBuffs = listOf()
                                lastFrameTime = System.currentTimeMillis()
                                lastFireTime = 0L
                                lastFormationTime = 0L
                                gameState = GameState.PLAYING 
                            }, 
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = skin.glowPrimary, contentColor = Color.White)
                        ) { 
                            Text("PLAY AGAIN", fontFamily = FontFamily.Monospace) 
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawAsteroid(cx: Float, cy: Float, radius: Float, color: Color, hits: Int) {
    val points = 7
    val path = Path()
    val tint = if (hits > 0) color.copy(alpha = 0.5f) else color
    for (i in 0..points) {
        val angle = (i.toFloat() / points) * 2 * PI
        val r = radius * (0.75f + Random.nextFloat() * 0.25f)
        val x = cx + cos(angle).toFloat() * r
        val y = cy + sin(angle).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, tint.copy(alpha = 0.25f))
    drawPath(path, tint, style = Stroke(width = 2f))
    if (hits > 0) {
        drawLine(tint.copy(alpha = 0.6f), Offset(cx - radius * 0.3f, cy - radius * 0.2f), Offset(cx + radius * 0.2f, cy + radius * 0.3f), strokeWidth = 1.5f)
    }
}

private fun DrawScope.drawComet(cx: Float, cy: Float, radius: Float, color: Color) {
    drawLine(brush = Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.5f)), startY = cy - radius * 3f, endY = cy), start = Offset(cx, cy - radius * 3f), end = Offset(cx, cy), strokeWidth = radius * 0.6f)
    drawCircle(color.copy(alpha = 0.3f), radius, Offset(cx, cy))
    drawCircle(color, radius * 0.6f, Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = 0.8f), radius * 0.2f, Offset(cx - radius * 0.15f, cy - radius * 0.15f))
}

private fun DrawScope.drawSpacecraft(
    cx: Float, cy: Float, scale: Float,
    primaryColor: Color, engineColor: Color,
    hasShield: Boolean, shieldColor: Color,
    hasVelocity: Boolean = false,
    hasMagnet: Boolean = false
) {
    val hull = Path().apply {
        moveTo(cx, cy - scale)
        lineTo(cx - scale * 0.55f, cy + scale * 0.6f)
        lineTo(cx - scale * 0.2f, cy + scale * 0.2f)
        lineTo(cx, cy + scale * 0.4f)
        lineTo(cx + scale * 0.2f, cy + scale * 0.2f)
        lineTo(cx - scale * 0.55f, cy + scale * 0.6f)
        close()
    }
    val cockpit = Path().apply {
        moveTo(cx, cy - scale * 0.6f)
        lineTo(cx - scale * 0.15f, cy)
        lineTo(cx + scale * 0.15f, cy)
        close()
    }

    if (hasVelocity) {
        for (i in 1..3) {
            drawLine(color = Color.White.copy(alpha = 0.3f / i), start = Offset(cx, cy + scale * (0.2f + i * 0.3f)), end = Offset(cx, cy + scale * (0.6f + i * 0.3f)), strokeWidth = 2f)
        }
    }

    if (hasMagnet) {
        drawCircle(color = Color(0xFF5FD8FF).copy(alpha = 0.15f), radius = scale * 2.5f, center = Offset(cx, cy), style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
    }

    drawPath(hull, primaryColor.copy(alpha = 0.2f))
    drawPath(hull, primaryColor, style = Stroke(width = 2f))
    drawPath(cockpit, primaryColor.copy(alpha = 0.5f))

    val engineY = cy + scale * 0.5f
    drawCircle(engineColor.copy(alpha = 0.9f), scale * 0.12f, Offset(cx - scale * 0.25f, engineY))
    drawCircle(engineColor.copy(alpha = 0.9f), scale * 0.12f, Offset(cx + scale * 0.25f, engineY))
    drawCircle(engineColor.copy(alpha = 0.4f), scale * 0.28f, Offset(cx - scale * 0.25f, engineY))
    drawCircle(engineColor.copy(alpha = 0.4f), scale * 0.28f, Offset(cx + scale * 0.25f, engineY))

    if (hasShield) {
        drawCircle(color = shieldColor.copy(alpha = 0.25f), radius = scale * 1.4f, center = Offset(cx, cy))
        drawCircle(color = shieldColor.copy(alpha = 0.8f), radius = scale * 1.4f, center = Offset(cx, cy), style = Stroke(width = 2f))
    }
}

private fun DrawScope.drawBuffIcon(
    cx: Float, cy: Float, type: BuffType,
    color: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    drawCircle(color.copy(alpha = 0.2f), 18f, Offset(cx, cy))
    drawCircle(color.copy(alpha = 0.8f), 18f, Offset(cx, cy), style = Stroke(width = 1.5f))
    val style = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center)
    val measured = textMeasurer.measure(type.emoji, style)
    drawText(measured, topLeft = Offset(cx - measured.size.width / 2f, cy - measured.size.height / 2f))
}
