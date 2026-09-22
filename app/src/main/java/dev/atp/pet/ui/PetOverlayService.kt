package dev.atp.pet.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import dev.atp.pet.MainActivity
import dev.atp.pet.R
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.data.CharacterStore
import dev.atp.pet.data.SettingsStore
import kotlin.math.max
import kotlin.math.min

/**
 * 召唤到桌面：宠物浮在别的 App 上面。
 *
 * This is the same bench the app has, minus the room: one [PhysicsSandboxView] in desktop mode,
 * put in an overlay window. Same physics, same rules, same props, same long press -- a second
 * renderer would be a second pet that behaves slightly differently from the one in the app, and
 * "it walks differently when it is over Chrome" is a bug nobody can find.
 *
 * Two things are worth saying out loud, because they are not obvious from the outside:
 *
 *  - **Its rules run in ITS OWN engine.** The app's bench and the floating pet are two instances
 *    of the same pet: opening the app does not pause the one on the desktop, and the numbers
 *    (H/P and the states) are separate copies from the moment each was loaded. The pet on the
 *    desktop has no save file of its own, so nothing it does is written down -- which is the
 *    honest behaviour for a toy that is meant to be dismissed.
 *  - **It is a foreground service** with a notification, because Android only lets a window
 *    like this live as long as something is visibly running. The notification's 收回 action is
 *    the second way to send it home; the first is the ✕ on the window.
 */
class PetOverlayService : Service() {

    private lateinit var window: WindowManager
    private var root: View? = null
    private var pet: PhysicsSandboxView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        window = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 收回：✕、通知上的按钮、App 里那个按钮，三条路都到这里。
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // 长按 App 里那个按钮弹出来的菜单，按下之后就发这三个命令过来：它们作用于
            // 桌面上那一只，而不是 App 里那一只（那是两个实例，见类注释）。
            ACTION_PROP -> pet?.spawnProp(intent.getStringExtra(EXTRA_ID).orEmpty())
            ACTION_STATE -> {
                val tag = intent.getStringExtra(EXTRA_ID).orEmpty()
                if (tag.isNotEmpty()) pet?.toggleState(tag)
            }
            ACTION_RIG -> {
                val name = intent.getStringExtra(EXTRA_ID).orEmpty()
                val folder = petFolder(this)?.withRig(name)
                if (folder != null && pet?.swapRig(folder) != true) {
                    // 那一套读不出来：什么也不换，并说出来 —— 桌面上那只突然消失比"没换"更糟。
                    Toast.makeText(
                        this, getString(R.string.character_unreadable, folder.id),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        root?.let { view -> runCatching { window.removeView(view) } }
        root = null
        pet = null
        running = false
        super.onDestroy()
    }

    // ── the window ──────────────────────────────────────────────────────────

    private fun show() {
        val store = CharacterStore(this)
        store.ensureSeeded()
        val folder = petFolder(this) ?: run {
            stopSelf()
            return
        }

        val metrics = resources.displayMetrics
        val width = min((metrics.widthPixels * 0.62f).toInt(), (360f * metrics.density).toInt())
        val height = (width * 1.35f).toInt()

        val view = PhysicsSandboxView(this).apply {
            setDesktopMode(true)
            applySettings(SettingsStore(this@PetOverlayService).load())
            load(
                folder,
                store.loadLogic(folder.id),
                store.loadProps(),
                store.propsDir,
                store.loadObjectLogic(folder),
            )
            // 长按宠物本身仍然是规则的事（见 EventType.LONG_PRESS）；菜单在 App 里那个按钮上，
            // 因为菜单要读的是"这只桌宠有哪些道具、哪些状态"，而那些列表属于 App 的界面。
        }
        pet = view

        // 上面一条细把手：按住它拖窗口，右边的 ✕ 收回。宠物那一块留给宠物 —— 一块浮在
        // 别的 App 上面的区域，如果整块都能拖动窗口，那"抓住宠物的手"和"抓住窗口的手"
        // 就是同一只，而它只能是一个意思。
        val bar = FrameLayout(this)
        val grab = TextView(this).apply {
            text = getString(R.string.overlay_grab)
            textSize = 11f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        bar.addView(
            grab,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )
        val close = TextView(this).apply {
            text = getString(R.string.overlay_close)
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { stopSelf() }
        }
        bar.addView(
            close,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        dragByHandle(grab)
        dragByHandle(bar)

        val box = FrameLayout(this)
        box.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = dp(HANDLE_DP) },
        )
        box.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(HANDLE_DP),
                Gravity.TOP,
            ),
        )

        val p = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - width) / 2
            y = metrics.heightPixels - height - dp(96)
        }
        params = p
        root = box
        runCatching { window.addView(box, p) }.onFailure { stopSelf() }
    }

    /** Dragging the handle moves the WINDOW; the pet keeps its own fingers. */
    private fun dragByHandle(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (event.rawX - downX).toInt()
                    p.y = max(0, startY + (event.rawY - downY).toInt())
                    root?.let { runCatching { window.updateViewLayout(it, p) } }
                    true
                }
                else -> true
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── the notification ────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.overlay_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.overlay_channel_hint) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.overlay_title))
            .setContentText(getString(R.string.overlay_text))
            .setSmallIcon(R.drawable.ic_pet_mark)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.overlay_close), stop).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.atp.pet.overlay.STOP"
        const val ACTION_PROP = "dev.atp.pet.overlay.PROP"
        const val ACTION_STATE = "dev.atp.pet.overlay.STATE"
        const val ACTION_RIG = "dev.atp.pet.overlay.RIG"
        const val EXTRA_ID = "id"

        private const val CHANNEL = "pet_overlay"
        private const val NOTIFICATION_ID = 4711
        private const val HANDLE_DP = 26

        /** Where the pet on the bench is remembered, so the floating one is the same pet. */
        private const val PREFS = "overlay"
        private const val KEY_PET = "pet"

        /** Whether the window is up. Read by the app's button, which is the way in and out. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Which pet the floating one should be: the last one that was on the bench.
         *
         * Written by the app whenever it summons, read here. It is a preference rather than a
         * parameter because the two are separate processes' worth of state -- the button in the
         * app must be able to summon the pet it is showing without a handshake.
         */
        fun rememberPet(context: Context, id: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PET, id).apply()
        }

        fun petFolder(context: Context): CharacterFolder? {
            val store = CharacterStore(context)
            val wanted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PET, "").orEmpty()
            return store.folder(wanted) ?: store.list().firstOrNull()
        }

        fun start(context: Context) {
            val intent = Intent(context, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Send it home -- but only if it is out.
         *
         * `startService` STARTS a service that is not running, so an unguarded 收回 would
         * summon a pet in order to dismiss it. [running] is a flag in this process, which is
         * where the service lives, so it is the truth about whether there is a window.
         */
        fun stop(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, PetOverlayService::class.java).setAction(ACTION_STOP)
            )
        }

        /** One command to the pet that is on the desktop: a prop to put out, a switch, a body. */
        fun send(context: Context, action: String, id: String) {
            if (!running) return
            context.startService(
                Intent(context, PetOverlayService::class.java).setAction(action)
                    .putExtra(EXTRA_ID, id)
            )
        }
    }
}
