package com.nautile.app.rdp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.nautile.rdp.RdpInput
import com.nautile.rdp.RdpPointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a remote desktop and turns touches into mouse input:
 * tap = click, press and hold = right click (or left drag if the finger then moves),
 * two-finger tap = right click, two-finger drag = wheel, pinch = zoom, one-finger drag = pan when zoomed.
 * A hardware mouse and keyboard are passed through.
 */
@SuppressLint("ViewConstructor")
class RdpView(context: Context) : View(context), RdpDisplay {
    var session: RdpSession? = null
        set(value) {
            if (field === value) return
            field?.display = null
            field = value
            value?.display = this
            pointerBitmap = value?.pointer?.let(::toBitmap)
            zoom = 1f; panX = 0f; panY = 0f
            if (width > 0 && height > 0 && value != null) {
                if (value.isStarted) value.viewResized(width, height) else { removeCallbacks(startTask); postDelayed(startTask, START_DELAY_MS) }
            }
            invalidate()
        }

    private val _modifiers = MutableStateFlow<Set<Int>>(emptySet())
    /** Modifiers latched by the key bar (Ctrl, Alt, Shift, Win) for the next key. */
    val modifiers: StateFlow<Set<Int>> = _modifiers

    /** Touchpad mode: the finger moves the pointer relatively and taps click under it. */
    var touchpad: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Draw the remote pointer in direct mode (always drawn with the touchpad). */
    var drawPointer: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Height hidden at the bottom (keyboard, key bar): the desktop can be panned above it. */
    var obscuredBottom: Int = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    @Volatile private var pointerBitmap: Bitmap? = null
    @Volatile private var hotX = 0
    @Volatile private var hotY = 0
    private var cursorX = 0
    private var cursorY = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = com.nautile.i18n.tr("Bureau à distance")
    }

    // ---- RdpDisplay (FreeRDP thread) ----

    override fun resized(width: Int, height: Int) {
        post {
            // Touchpad: start with the pointer in the middle of the desktop.
            if (cursorX == 0 && cursorY == 0) { cursorX = width / 2; cursorY = height / 2 }
            cursorX = cursorX.coerceIn(0, (width - 1).coerceAtLeast(0)); cursorY = cursorY.coerceIn(0, (height - 1).coerceAtLeast(0))
            clampPan(); invalidate()
        }
    }
    override fun frame(x: Int, y: Int, width: Int, height: Int) = postInvalidate()
    override fun pointer(pointer: RdpPointer?) {
        pointerBitmap = pointer?.let(::toBitmap)
        postInvalidate()
    }

    private fun toBitmap(p: RdpPointer): Bitmap? {
        if (p.width <= 0 || p.height <= 0 || p.pixels.size < p.width * p.height) return null
        hotX = p.hotX; hotY = p.hotY
        return Bitmap.createBitmap(p.pixels, p.width, p.height, Bitmap.Config.ARGB_8888)
    }

    // ---- Layout and drawing ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        session?.let { s ->
            if (s.isStarted) s.viewResized(w, h)
            else {
                // Wait for the size to settle (full screen hides the system bars just after the first layout).
                removeCallbacks(startTask)
                postDelayed(startTask, START_DELAY_MS)
            }
        }
        clampPan()
    }

    private val startTask = Runnable {
        if (width > 0 && height > 0) session?.start(width, height, resources.displayMetrics.density)
    }

    private fun desktop(): Bitmap? = session?.bitmap

    private fun fitScale(b: Bitmap) = min(width.toFloat() / b.width, height.toFloat() / b.height)

    /** Keeps the desktop centred when smaller than the view, inside the view otherwise. */
    private fun clampPan() {
        val b = desktop() ?: return
        val s = fitScale(b) * zoom
        val dw = b.width * s
        val dh = b.height * s
        val visible = (height - obscuredBottom).coerceAtLeast(height / 4).toFloat()
        panX = if (dw <= width) (width - dw) / 2 else panX.coerceIn(width - dw, 0f)
        panY = when {
            dh <= visible && obscuredBottom == 0 -> (height - dh) / 2
            dh <= visible -> panY.coerceIn(0f, visible - dh)
            else -> panY.coerceIn(visible - dh, 0f)
        }
    }

    private fun updateMatrix(b: Bitmap) {
        val s = fitScale(b) * zoom
        viewMatrix.reset()
        viewMatrix.postScale(s, s)
        viewMatrix.postTranslate(panX, panY)
        viewMatrix.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = desktop() ?: return
        clampPan()
        updateMatrix(b)
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawBitmap(b, 0f, 0f, paint)
        if (touchpad || drawPointer) {
            val p = pointerBitmap
            if (p != null) canvas.drawBitmap(p, (cursorX - hotX).toFloat(), (cursorY - hotY).toFloat(), paint)
            else if (touchpad) drawArrow(canvas, fitScale(b) * zoom)
        }
        canvas.restore()
    }

    private val arrowPath = android.graphics.Path().apply {
        moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4.5f, 13f); lineTo(7.5f, 20f); lineTo(10.5f, 18.8f); lineTo(7.6f, 12f); lineTo(13f, 12f); close()
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.2f }

    /** Arrow drawn when the server shows no pointer image, so the touchpad always has something to aim with. */
    private fun drawArrow(canvas: Canvas, scale: Float) {
        canvas.save()
        canvas.translate(cursorX.toFloat(), cursorY.toFloat())
        // Constant size on screen whatever the zoom.
        val k = resources.displayMetrics.density / scale
        canvas.scale(k, k)
        canvas.drawPath(arrowPath, arrowFill)
        canvas.drawPath(arrowPath, arrowStroke)
        canvas.restore()
    }

    /** View coordinates to desktop pixels. */
    private fun toDesktop(x: Float, y: Float): Pair<Int, Int>? {
        val b = desktop() ?: return null
        updateMatrix(b)
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        return pts[0].toInt().coerceIn(0, b.width - 1) to pts[1].toInt().coerceIn(0, b.height - 1)
    }

    // ---- Mouse output ----

    private fun send(x: Float, y: Float, flags: Int) {
        val (dx, dy) = toDesktop(x, y) ?: return
        cursorX = dx; cursorY = dy
        session?.input?.pointer(dx, dy, flags)
        invalidate()
    }

    private fun click(x: Float, y: Float, button: Int) {
        if (touchpad) { clickAtCursor(button); return }
        send(x, y, RdpInput.PTR_MOVE)
        send(x, y, RdpInput.PTR_DOWN or button)
        send(x, y, button)
    }

    /** Touchpad: a press at the pointer, wherever the finger is. */
    private fun atCursor(flags: Int) {
        session?.input?.pointer(cursorX, cursorY, flags)
        invalidate()
    }

    private fun clickAtCursor(button: Int) {
        atCursor(RdpInput.PTR_DOWN or button)
        atCursor(button)
    }

    /** Touchpad: moves the pointer by a finger movement in view pixels, keeping it in sight. */
    private fun moveCursor(dxView: Float, dyView: Float) {
        val b = desktop() ?: return
        val s = fitScale(b) * zoom
        cursorX = (cursorX + dxView / s * TOUCHPAD_SPEED).toInt().coerceIn(0, b.width - 1)
        cursorY = (cursorY + dyView / s * TOUCHPAD_SPEED).toInt().coerceIn(0, b.height - 1)
        session?.input?.pointer(cursorX, cursorY, RdpInput.PTR_MOVE)
        val margin = 48 * resources.displayMetrics.density
        val vx = cursorX * s + panX
        val vy = cursorY * s + panY
        val visible = (height - obscuredBottom).coerceAtLeast(height / 4)
        if (vx < margin) panX += margin - vx else if (vx > width - margin) panX -= vx - (width - margin)
        if (vy < margin) panY += margin - vy else if (vy > visible - margin) panY -= vy - (visible - margin)
        invalidate()
    }

    private fun wheel(up: Boolean) { session?.input?.pointer(cursorX, cursorY, RdpInput.wheel(up)) }

    // ---- Touch gestures ----

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    /** Press and hold: right click on release, or left drag once the finger moves. */
    private var holding = false
    private var dragging = false
    private var holdX = 0f
    private var holdY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private enum class Two { UNDECIDED, ZOOM, SCROLL }
    private var two: Two? = null
    private var twoStartSpan = 0f
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var twoLastY = 0f
    private var twoDownAt = 0L
    private var twoMoved = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { click(e.x, e.y, RdpInput.PTR_LEFT); return true }
        override fun onLongPress(e: MotionEvent) {
            holding = true; dragging = false; holdX = e.x; holdY = e.y; lastX = e.x; lastY = e.y
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (!touchpad) send(e.x, e.y, RdpInput.PTR_MOVE)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (holding || two != null) return false
            if (touchpad) moveCursor(-dx, -dy) else { panX -= dx; panY -= dy; invalidate() }
            return true
        }
    }).apply { setIsLongpressEnabled(true) }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (two != Two.ZOOM) return false
            val b = desktop() ?: return true
            val old = zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, max(4f, 3f / fitScale(b)))
            val f = zoom / old
            panX = d.focusX - (d.focusX - panX) * f
            panY = d.focusY - (d.focusY - panY) * f
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(e)
        if (!hasFocus()) requestFocus()
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && !holding) {
                two = Two.UNDECIDED
                twoStartSpan = span(e); twoStartX = midX(e); twoStartY = midY(e); twoLastY = twoStartY
                twoDownAt = e.eventTime; twoMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                when {
                    two != null && e.pointerCount >= 2 -> twoFingers(e)
                    holding -> {
                        if (!dragging && hypot(e.x - holdX, e.y - holdY) > touchSlop) {
                            dragging = true
                            if (touchpad) atCursor(RdpInput.PTR_DOWN or RdpInput.PTR_LEFT)
                            else send(holdX, holdY, RdpInput.PTR_DOWN or RdpInput.PTR_LEFT)
                        }
                        if (dragging) {
                            if (touchpad) moveCursor(e.x - lastX, e.y - lastY) else send(e.x, e.y, RdpInput.PTR_MOVE)
                        }
                        lastX = e.x; lastY = e.y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (two != null && e.pointerCount == 2) {
                if (!twoMoved && e.eventTime - twoDownAt < 300) click(midX(e), midY(e), RdpInput.PTR_RIGHT)
            }
            MotionEvent.ACTION_UP -> {
                if (holding) {
                    when {
                        dragging && touchpad -> atCursor(RdpInput.PTR_LEFT)
                        dragging -> send(e.x, e.y, RdpInput.PTR_LEFT)
                        else -> click(holdX, holdY, RdpInput.PTR_RIGHT)
                    }
                }
                holding = false; dragging = false; two = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { if (touchpad) atCursor(RdpInput.PTR_LEFT) else send(e.x, e.y, RdpInput.PTR_LEFT) }
                holding = false; dragging = false; two = null
            }
        }
        if (two == null) gestures.onTouchEvent(e) else {
            // Keep the detector from turning the end of a two-finger gesture into a tap.
            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            gestures.onTouchEvent(cancel); cancel.recycle()
        }
        return true
    }

    private fun twoFingers(e: MotionEvent) {
        val moved = hypot(midX(e) - twoStartX, midY(e) - twoStartY)
        val spanDelta = abs(span(e) - twoStartSpan)
        if (moved > touchSlop || spanDelta > touchSlop) twoMoved = true
        if (two == Two.UNDECIDED) {
            two = when {
                spanDelta > touchSlop * 2 -> Two.ZOOM
                moved > touchSlop * 2 -> Two.SCROLL
                else -> Two.UNDECIDED
            }
        }
        if (two == Two.SCROLL) {
            val step = 24f * resources.displayMetrics.density
            while (midY(e) - twoLastY > step) { wheel(up = true); twoLastY += step }
            while (twoLastY - midY(e) > step) { wheel(up = false); twoLastY -= step }
        }
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2

    // ---- Hardware mouse ----

    private var mouseButtons = 0

    override fun onGenericMotionEvent(e: MotionEvent): Boolean =
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) mouse(e) else super.onGenericMotionEvent(e)

    private fun mouse(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) { send(e.x, e.y, RdpInput.PTR_MOVE); wheel(up = v > 0) }
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> send(e.x, e.y, RdpInput.PTR_MOVE)
        }
        val buttons = e.buttonState
        for ((android, rdp) in MOUSE_BUTTONS) {
            val was = mouseButtons and android != 0
            val now = buttons and android != 0
            if (was != now) send(e.x, e.y, if (now) RdpInput.PTR_DOWN or rdp else rdp)
        }
        mouseButtons = buttons
        return true
    }

    // ---- Keyboard ----

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleModifier(vk: Int) {
        _modifiers.value = _modifiers.value.let { if (vk in it) it - vk else it + vk }
    }

    /** Presses [vk] with the latched modifiers, then releases everything. */
    fun pressKey(vk: Int) {
        val c = session?.input ?: return
        val mods = _modifiers.value
        mods.forEach { c.key(it, true) }
        c.key(vk, true); c.key(vk, false)
        mods.reversed().forEach { c.key(it, false) }
        _modifiers.value = emptySet()
    }

    fun ctrlAltDel() {
        val c = session?.input ?: return
        listOf(RdpInput.VK_LCONTROL, RdpInput.VK_LMENU, RdpInput.VK_DELETE).let { keys ->
            keys.forEach { c.key(it, true) }
            keys.reversed().forEach { c.key(it, false) }
        }
    }

    /** Text from the IME: Unicode events, or key chords while modifiers are latched. */
    fun typeText(text: CharSequence) {
        val c = session?.input ?: return
        if (_modifiers.value.isEmpty()) { c.text(text.toString()); return }
        text.forEach { ch ->
            val vk = when (ch) {
                in 'a'..'z' -> 0x41 + (ch - 'a')
                in 'A'..'Z' -> 0x41 + (ch - 'A')
                in '0'..'9' -> 0x30 + (ch - '0')
                ' ' -> RdpInput.VK_SPACE
                '\n' -> RdpInput.VK_RETURN
                else -> null
            }
            if (vk != null) pressKey(vk) else { c.text(ch.toString()); _modifiers.value = emptySet() }
        }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                typeText(text)
                return true
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString(); return true
            }
            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) { typeText(composing); composing = "" }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) { composing = composing.dropLast(beforeLength); return true }
                repeat(beforeLength) { pressKey(RdpInput.VK_BACK) }
                repeat(afterLength) { pressKey(RdpInput.VK_DELETE) }
                return true
            }
            override fun performEditorAction(actionCode: Int): Boolean { pressKey(RdpInput.VK_RETURN); return true }
        }
    }

    /** Keys sent as virtual keys (to be released on key up); others went as Unicode. */
    private val downAsVk = HashSet<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !event.isFromSource(InputDevice.SOURCE_KEYBOARD)) return super.onKeyDown(keyCode, event)
        val c = session?.input ?: return super.onKeyDown(keyCode, event)
        val chord = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || _modifiers.value.isNotEmpty()
        val uc = event.unicodeChar
        if (!chord && uc != 0 && keyCode !in VK_ALWAYS) {
            c.text(String(Character.toChars(uc)))
            return true
        }
        val vk = RdpInput.vk(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (_modifiers.value.isNotEmpty() && vk !in MODIFIER_VKS) { pressKey(vk); return true }
        downAsVk += keyCode
        c.key(vk, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!downAsVk.remove(keyCode)) return keyCode != KeyEvent.KEYCODE_BACK || super.onKeyUp(keyCode, event)
        RdpInput.vk(keyCode)?.let { session?.input?.key(it, false) }
        return true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        session?.input?.setForeground(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        session?.let { if (it.display === this) it.display = null }
        super.onDetachedFromWindow()
    }

    companion object {
        /** Desktop pixels per view pixel moved, at the current zoom. */
        private const val TOUCHPAD_SPEED = 1.3f
        private const val START_DELAY_MS = 350L
        private val MOUSE_BUTTONS = listOf(
            MotionEvent.BUTTON_PRIMARY to RdpInput.PTR_LEFT,
            MotionEvent.BUTTON_SECONDARY to RdpInput.PTR_RIGHT,
            MotionEvent.BUTTON_TERTIARY to RdpInput.PTR_MIDDLE,
        )
        /** Keys always sent by scan code, even though Android gives them a character. */
        private val VK_ALWAYS = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_SPACE)
        private val MODIFIER_VKS = setOf(
            RdpInput.VK_LSHIFT, RdpInput.VK_RSHIFT, RdpInput.VK_LCONTROL, RdpInput.VK_RCONTROL,
            RdpInput.VK_LMENU, RdpInput.VK_RMENU, RdpInput.VK_LWIN, RdpInput.VK_RWIN,
        )
    }
}
