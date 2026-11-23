package com.noop.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// MARK: - Route on an OpenStreetMap map (fork)
//
// Draws a GPS track over standard OpenStreetMap tiles. Tiles come from tile.openstreetmap.org (the
// only network request this view makes), load in parallel, are kept in cacheDir/osm for a year and
// reused. No area is downloaded in bulk: the OSM tile policy forbids it. The map credits
// "© OpenStreetMap contributors" as the tile licence requires. The zoom is the highest at which the
// whole route fits.

private const val TILE = 256.0

private object OsmTiles {
    private val http = OkHttpClient()
    /** Tile usage policy: identify the app; no personal details in it. */
    private const val UA = "NOOP-fork/1.0 (Android; personal activity viewer)"

    suspend fun load(ctx: android.content.Context, z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "osm/$z/$x/$y.png")
        val bytes = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 365L * 86_400_000L) f.readBytes() else runCatching {
            http.newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").header("User-Agent", UA).build())
                .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()?.also { f.parentFile?.mkdirs(); f.writeBytes(it) }
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
}

/** World pixel coordinates (Web Mercator) at zoom [z]. */
private fun worldPx(lat: Double, lng: Double, z: Int): Pair<Double, Double> {
    val n = TILE * (1 shl z)
    val x = (lng + 180.0) / 360.0 * n
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
    return x to y
}

@Composable
internal fun WxRouteMap(points: List<Pair<Double, Double>>, height: Dp = 240.dp, description: String) {
    val ctx = LocalContext.current
    val tiles = remember(points) { mutableStateMapOf<String, ImageBitmap>() }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Wx.chartRadius)).background(Wx.inset)
            .semantics { contentDescription = description },
    ) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toDouble()
        val hPx = with(density) { maxHeight.toPx() }.toDouble()
        if (points.size < 2) return@BoxWithConstraints
        // The highest zoom at which the track fits with a margin.
        val zoom = remember(points, wPx, hPx) {
            (16 downTo 3).firstOrNull { z ->
                val px = points.map { worldPx(it.first, it.second, z) }
                (px.maxOf { it.first } - px.minOf { it.first }) <= wPx * 0.85 &&
                    (px.maxOf { it.second } - px.minOf { it.second }) <= hPx * 0.85
            } ?: 3
        }
        val px = remember(points, zoom) { points.map { worldPx(it.first, it.second, zoom) } }
        val cx = (px.maxOf { it.first } + px.minOf { it.first }) / 2
        val cy = (px.maxOf { it.second } + px.minOf { it.second }) / 2
        val left = cx - wPx / 2; val top = cy - hPx / 2
        val tx0 = floor(left / TILE).toInt(); val tx1 = floor((left + wPx) / TILE).toInt()
        val ty0 = floor(top / TILE).toInt(); val ty1 = floor((top + hPx) / TILE).toInt()
        LaunchedEffect(points, zoom, wPx, hPx) {
            val max = 1 shl zoom
            // All tiles of the view at once (a handful), each from the phone's cache when it has it.
            kotlinx.coroutines.coroutineScope {
                for (tx in tx0..tx1) for (ty in ty0..ty1) {
                    if (ty !in 0 until max) continue
                    val wx = ((tx % max) + max) % max
                    val key = "$zoom/$tx/$ty"
                    if (key !in tiles) launch { OsmTiles.load(ctx, zoom, wx, ty)?.let { tiles[key] = it } }
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            for (tx in tx0..tx1) for (ty in ty0..ty1) {
                val img = tiles["$zoom/$tx/$ty"] ?: continue
                val ox = (tx * TILE - left).toFloat(); val oy = (ty * TILE - top).toFloat()
                drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(TILE.toInt(), TILE.toInt()))
            }
            // Darken the light map a little so the track and the dark cards read together.
            drawRect(Color.Black.copy(alpha = 0.18f))
            val path = Path()
            px.forEachIndexed { i, (x, y) ->
                val p = Offset((x - left).toFloat(), (y - top).toFloat())
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Wx.effort, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val s = px.first(); val e = px.last()
            drawCircle(Wx.recoveryHigh, 6.dp.toPx(), Offset((s.first - left).toFloat(), (s.second - top).toFloat()))
            drawCircle(Wx.recoveryLow, 6.dp.toPx(), Offset((e.first - left).toFloat(), (e.second - top).toFloat()))
        }
        Text("© OpenStreetMap contributors", style = WxType.caption.copy(fontSize = 9.sp), color = Color(0xFF333333),
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}
