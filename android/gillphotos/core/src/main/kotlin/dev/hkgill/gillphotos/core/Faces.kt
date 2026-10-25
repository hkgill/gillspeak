package dev.hkgill.gillphotos.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A face YuNet found, in the pixels of the picture it was given: its box, how sure it is, and five landmarks as x, y
 * pairs: right eye, left eye, nose tip, right and left corners of the mouth. "Right" is the person's own right, so
 * their right eye is on the picture's left.
 */
class Face(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val landmarks: FloatArray)

/**
 * YuNet, OpenCV Zoo's face detector (MIT): finds faces about 10 to 300 pixels across. The 2023mar model takes a fixed
 * 640 × 640 picture as planar B, G, R values from 0 to 255; a smaller picture sits in the top-left corner on black,
 * as OpenCV's own padding does. Decoding follows OpenCV's FaceDetectorYN (modules/objdetect/src/face_detect.cpp).
 */
object YuNet {
    const val SIZE = 640
    private val STRIDES = intArrayOf(8, 16, 32)

    /** Output names in OpenCV's order: scores, objectness, boxes and landmarks for each stride. */
    val OUTPUTS = listOf("cls", "obj", "bbox", "kps").flatMap { kind -> STRIDES.map { "${kind}_$it" } }

    /** The model's input for an ARGB picture no bigger than [SIZE] either way. */
    fun input(argb: IntArray, width: Int, height: Int): FloatArray {
        require(width in 1..SIZE && height in 1..SIZE) { "picture is ${width}x$height, at most $SIZE either way" }
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (y in 0 until height) for (x in 0 until width) {
            val p = argb[y * width + x]
            val i = y * SIZE + x
            out[i] = (p and 0xFF).toFloat()
            out[plane + i] = (p shr 8 and 0xFF).toFloat()
            out[2 * plane + i] = (p shr 16 and 0xFF).toFloat()
        }
        return out
    }

    fun decode(outputs: Map<String, FloatArray>, minScore: Float, maxOverlap: Float = 0.3f, topK: Int = 50): List<Face> {
        val faces = ArrayList<Face>()
        for (stride in STRIDES) {
            val cls = outputs.getValue("cls_$stride")
            val obj = outputs.getValue("obj_$stride")
            val bbox = outputs.getValue("bbox_$stride")
            val kps = outputs.getValue("kps_$stride")
            val cols = SIZE / stride
            for (i in cls.indices) {
                val score = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                if (score < minScore) continue
                val r = i / cols
                val c = i % cols
                val cx = (c + bbox[i * 4]) * stride
                val cy = (r + bbox[i * 4 + 1]) * stride
                val w = exp(bbox[i * 4 + 2]) * stride
                val h = exp(bbox[i * 4 + 3]) * stride
                val landmarks = FloatArray(10) { n -> (kps[i * 10 + n] + if (n % 2 == 0) c else r) * stride }
                faces += Face(cx - w / 2, cy - h / 2, w, h, score, landmarks)
            }
        }
        return suppress(faces, maxOverlap, topK)
    }

    /** Non-maximum suppression: of boxes that overlap by more than [maxOverlap], keeps the surest. */
    fun suppress(faces: List<Face>, maxOverlap: Float, topK: Int): List<Face> {
        val kept = ArrayList<Face>()
        for (f in faces.sortedByDescending { it.score }) {
            if (kept.size == topK) break
            if (kept.none { overlap(it, f) > maxOverlap }) kept += f
        }
        return kept
    }

    /** Intersection over union of two boxes. */
    fun overlap(a: Face, b: Face): Float {
        val w = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
        val h = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        if (w <= 0 || h <= 0) return 0f
        val inter = w * h
        return inter / (a.w * a.h + b.w * b.h - inter)
    }
}

/**
 * Lines a face up for SFace: the rotation, scale and shift that best take its five landmarks onto the standard
 * positions in a 112 × 112 square (the ArcFace template OpenCV's FaceRecognizerSF.alignCrop uses), then a bilinear
 * warp, with black outside the picture.
 */
object Align {
    const val SIZE = 112
    private val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
    )

    /**
     * [a, b, tx, ty] for x' = a·x − b·y + tx and y' = b·x + a·y + ty: least squares over the five points. This is the
     * 2-D case of Umeyama's method, which OpenCV solves with an SVD; for points that aren't mirrored the two agree.
     */
    fun transform(landmarks: FloatArray): FloatArray {
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until 5) {
            sx += landmarks[2 * i]; sy += landmarks[2 * i + 1]
            dx += TEMPLATE[2 * i]; dy += TEMPLATE[2 * i + 1]
        }
        sx /= 5; sy /= 5; dx /= 5; dy /= 5
        var dot = 0.0; var cross = 0.0; var norm = 0.0
        for (i in 0 until 5) {
            val x = landmarks[2 * i] - sx
            val y = landmarks[2 * i + 1] - sy
            val u = TEMPLATE[2 * i] - dx
            val v = TEMPLATE[2 * i + 1] - dy
            dot += x * u + y * v
            cross += x * v - y * u
            norm += x * x + y * y
        }
        val a = dot / norm
        val b = cross / norm
        return floatArrayOf(a.toFloat(), b.toFloat(), (dx - (a * sx - b * sy)).toFloat(), (dy - (b * sx + a * sy)).toFloat())
    }

    /** The [SIZE] × [SIZE] ARGB face: each output pixel is sampled from where [m] maps it back to in the picture. */
    fun warp(argb: IntArray, width: Int, height: Int, m: FloatArray): IntArray {
        val (a, b, tx, ty) = m
        val k = a * a + b * b
        val out = IntArray(SIZE * SIZE)
        for (v in 0 until SIZE) for (u in 0 until SIZE) {
            val pu = u - tx
            val pv = v - ty
            out[v * SIZE + u] = sample(argb, width, height, (a * pu + b * pv) / k, (a * pv - b * pu) / k)
        }
        return out
    }

    private fun sample(argb: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun px(xi: Int, yi: Int) = if (xi in 0 until width && yi in 0 until height) argb[yi * width + xi] else 0
        val p00 = px(x0, y0); val p10 = px(x0 + 1, y0); val p01 = px(x0, y0 + 1); val p11 = px(x0 + 1, y0 + 1)
        fun ch(shift: Int): Int {
            val top = (p00 shr shift and 0xFF) * (1 - fx) + (p10 shr shift and 0xFF) * fx
            val bottom = (p01 shr shift and 0xFF) * (1 - fx) + (p11 shr shift and 0xFF) * fx
            return (top * (1 - fy) + bottom * fy + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * SFace, OpenCV Zoo's face recogniser (Apache 2.0, a MobileFaceNet): a lined-up face becomes 128 numbers, and faces
 * of the same person point the same way.
 */
object SFace {
    /** OpenCV's cosine threshold for "the same person" with this model. */
    const val SAME_PERSON = 0.363f

    /** The model's input for an [Align]ed face: planar R, G, B from 0 to 255. */
    fun input(aligned: IntArray): FloatArray {
        val plane = Align.SIZE * Align.SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = aligned[i]
            out[i] = (p shr 16 and 0xFF).toFloat()
            out[plane + i] = (p shr 8 and 0xFF).toFloat()
            out[2 * plane + i] = (p and 0xFF).toFloat()
        }
        return out
    }
}

/** Scales [v] to length 1 in place, so a dot product is the cosine. */
fun normalize(v: FloatArray): FloatArray {
    var sum = 0.0
    for (x in v) sum += x * x
    val n = sqrt(sum).toFloat()
    if (n > 0) for (i in v.indices) v[i] /= n
    return v
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/**
 * YuNet then SFace through ONNX Runtime: the faces in a picture, each with its vector. The same Java API runs on the
 * desktop JVM (tests) and Android (the app brings onnxruntime-android). Sessions are thread-safe.
 */
class FaceModels(yunet: ByteArray, sface: ByteArray, threads: Int = 2) : AutoCloseable {
    class Found(val face: Face, val vector: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val detector = env.createSession(yunet, options)
    private val recogniser = env.createSession(sface, options)

    /** Faces in an ARGB picture at most [YuNet.SIZE] either way, at least [minSize] pixels across. */
    fun find(argb: IntArray, width: Int, height: Int, minScore: Float = 0.75f, minSize: Float = 24f): List<Found> =
        detect(argb, width, height, minScore).filter { min(it.w, it.h) >= minSize }.map { f ->
            Found(f, embed(Align.warp(argb, width, height, Align.transform(f.landmarks))))
        }

    fun detect(argb: IntArray, width: Int, height: Int, minScore: Float): List<Face> {
        val shape = longArrayOf(1, 3, YuNet.SIZE.toLong(), YuNet.SIZE.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(YuNet.input(argb, width, height)), shape).use { input ->
            detector.run(mapOf("input" to input)).use { result ->
                YuNet.decode(YuNet.OUTPUTS.associateWith { floats(result.get(it).get() as OnnxTensor) }, minScore)
            }
        }
    }

    /** The unit-length vector of an [Align]ed face. */
    fun embed(aligned: IntArray): FloatArray {
        val shape = longArrayOf(1, 3, Align.SIZE.toLong(), Align.SIZE.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(SFace.input(aligned)), shape).use { input ->
            recogniser.run(mapOf("data" to input)).use { result -> normalize(floats(result.get(0) as OnnxTensor)) }
        }
    }

    private fun floats(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    override fun close() {
        detector.close()
        recogniser.close()
        options.close()
    }
}
