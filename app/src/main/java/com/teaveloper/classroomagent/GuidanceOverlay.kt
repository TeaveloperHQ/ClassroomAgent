package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 시스템 설정 화면 위에 반투명 오버레이를 그려서 초보자에게 "이걸 누르세요"
 * 를 정확한 위치로 안내한다.
 *
 * SYSTEM_ALERT_WINDOW 를 쓰지 않고 접근성 서비스 전용 창인
 * TYPE_ACCESSIBILITY_OVERLAY 를 사용하므로 오버레이 권한이 아직 부여되지
 * 않은 스텝에서도 그릴 수 있다 (마법사 순서상 이게 결정적).
 *
 * FLAG_NOT_TOUCHABLE 이라 오버레이는 터치를 가로채지 않는다 — 사용자가
 * 화면의 실제 스위치/버튼을 그대로 누를 수 있게. 우리는 어디를 눌러야
 * 하는지 시각적으로만 지시한다.
 */
class GuidanceOverlay(private val service: AccessibilityService) {

    private val wm: WindowManager by lazy {
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private var view: ArrowView? = null

    fun show(target: Rect, hint: String) {
        val existing = view
        if (existing != null) {
            existing.update(target, hint)
            return
        }
        val v = ArrowView(service).apply { update(target, hint) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (e: Exception) {
            android.util.Log.w("Guidance", "addView 실패: ${e.message}")
        }
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Exception) { /* already gone */ }
        view = null
    }

    /**
     * 화살표 + 힌트 말풍선을 그리는 커스텀 뷰. 대상 좌표(스크린 절대 좌표)를
     * 받아 그 위(공간이 없으면 아래)에 배치한다.
     */
    private class ArrowView(context: Context) : View(context) {

        private var target: Rect = Rect()
        private var hint: String = ""

        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF59E0B.toInt() // teavel_star
        }
        private val bubbleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF59E0B.toInt()
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF59E0B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        fun update(rect: Rect, message: String) {
            target = rect
            hint = message
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (target.isEmpty || hint.isEmpty()) return

            // 대상 주변 하이라이트 링
            val r = RectF(
                target.left - 12f, target.top - 12f,
                target.right + 12f, target.bottom + 12f
            )
            canvas.drawRoundRect(r, 24f, 24f, ringPaint)

            // 말풍선 위치 계산 — 대상 위에 여백이 충분하면 위, 아니면 아래.
            val bubbleWidth = maxOf(360f, textPaint.measureText(hint) + 80f)
            val bubbleHeight = 108f
            val bubbleCenterX = (target.left + target.right) / 2f
                .coerceIn(bubbleWidth / 2 + 24f, width - bubbleWidth / 2 - 24f)
            val above = target.top - bubbleHeight - 60f
            val below = target.bottom + 60f
            val putAbove = above > 24f
            val bubbleTop = if (putAbove) above else below

            val bubbleRect = RectF(
                bubbleCenterX - bubbleWidth / 2, bubbleTop,
                bubbleCenterX + bubbleWidth / 2, bubbleTop + bubbleHeight
            )
            // Drop shadow
            canvas.drawRoundRect(
                bubbleRect.left + 3, bubbleRect.top + 6,
                bubbleRect.right + 3, bubbleRect.bottom + 6,
                28f, 28f, bubbleShadow
            )
            canvas.drawRoundRect(bubbleRect, 28f, 28f, bubblePaint)

            // 텍스트
            val textY = bubbleRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(hint, bubbleCenterX, textY, textPaint)

            // 화살표 (삼각형) — 말풍선에서 대상 쪽으로
            val tipX = (target.left + target.right) / 2f
            val tipY = if (putAbove) target.top - 12f else target.bottom + 12f
            val base = 28f
            val baseY = if (putAbove) bubbleRect.bottom else bubbleRect.top
            val path = android.graphics.Path().apply {
                moveTo(tipX, tipY)
                lineTo(tipX - base, baseY)
                lineTo(tipX + base, baseY)
                close()
            }
            canvas.drawPath(path, arrowPaint)
        }
    }
}
