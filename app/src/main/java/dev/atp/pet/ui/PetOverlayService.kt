package dev.atp.pet.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.atp.pet.MainActivity
import dev.atp.pet.R
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.data.CharacterStore
import dev.atp.pet.data.SettingsStore

/**
 * 召唤到桌面：宠物浮在别的 App 上面，**整个屏幕都是它的地盘**。
 *
 * 两个窗口，不是一个：
 *
 *  - **宠物那一层**占满全屏，背景透明。世界就是屏幕，地面就是屏幕底边，所以它能被拖到
 *    任何地方，也会在整块屏幕上走 —— 「只能在一个小窗里动」是上一版的毛病；
 *  - **控制条**是一个很小的窗口，永远在上、永远收得到手指：**可摸 / 穿透**、**收回**。
 *    它必须单独一层，因为全屏那一层可以整体变成"点得穿"（`FLAG_NOT_TOUCHABLE`）——
 *    那时候它收不到任何触摸，而你还得有个地方把宠物收回去。
 *
 * 默认是**可摸**（召唤一只宠物出来，第一件想做的事就是抓它）。要让底下的 App 能用，
 * 点控制条上的「穿透」：宠物继续画着、继续按自己的规则动，但手指全部穿过去。
 *
 * 两句得说在明面上的话：
 *
 *  - **它的规则跑在自己的引擎里**。App 里那只和桌面上这只是同一个宠物的两个实例：
 *    打开 App 不会暂停桌面上那只，数值和状态从各自加载那一刻起就是两份。
 *  - **它不存档**。桌面上发生的事不写文件，收回就没了 —— 对一个随时会被收走的玩具来说，
 *    这是诚实的行为；要"跑起来的宠物"，用测试场。
 */
class PetOverlayService : Service() {

    private lateinit var window: WindowManager
    private var petRoot: View? = null
    private var strip: View? = null
    private var toggleTag: TextView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private var pet: PhysicsSandboxView? = null
    private var touchable = true

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
            // 收回：控制条上的按钮、通知上的按钮、App 里那个按钮，三条路都到这里。
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // 长按 App 里那个按钮弹出来的菜单，按下之后把命令发到这里：它们作用于桌面上
            // 这一只，而不是 App 里那一只（两个实例，见类注释）。
            ACTION_PROP -> pet?.spawnProp(intent.getStringExtra(EXTRA_ID).orEmpty())
            ACTION_STATE -> {
                val tag = intent.getStringExtra(EXTRA_ID).orEmpty()
                if (tag.isNotEmpty()) pet?.toggleState(tag)
            }
            // 摆动作：和 App 里那张动作表是同一批预设（同一个 poses.json），只是这一次
            // 摆的是桌面上这一只。
            ACTION_POSE -> {
                val name = intent.getStringExtra(EXTRA_ID).orEmpty()
                if (name.isNotEmpty() && pet?.playPose(name, home = true) != true) {
                    Toast.makeText(
                        this, getString(R.string.pet_summon_pose_missing, name), Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            ACTION_RIG -> {
                val name = intent.getStringExtra(EXTRA_ID).orEmpty()
                val folder = petFolder(this)?.withRig(name)
                // 动作是**跟着骨骼套**的，所以换套要连动作表一起换 —— 少了这一句，换套之后
                // 旧套的动作名还在，规则照样"摆得动"一个这套身体里不存在的动作。
                val poses = folder?.let { CharacterStore(this).loadPoses(it) } ?: emptyList()
                if (folder != null) rememberRig(this, name)
                if (folder != null && pet?.swapRig(folder, poses.associate { it.name to it.angles }) != true) {
                    Toast.makeText(
                        this, getString(R.string.character_unreadable, folder.id),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            // 全局设置改了：桌面上那只跟着变。App 侧每次改动都会发这一条。
            ACTION_SETTINGS -> applyPetSettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        for (v in listOf(petRoot, strip)) {
            if (v != null) runCatching { window.removeView(v) }
        }
        petRoot = null
        strip = null
        pet = null
        running = false
        super.onDestroy()
    }

    // ── the two windows ─────────────────────────────────────────────────────

    /**
     * 把全局设置整套装到桌面上那一只身上。
     *
     * 两句话，缺一不可：`applySettings` 管重力、特效那些开关；**刚度**要用属性单独设
     * （它是"这只布娃娃现在多硬"，不是一份设置里的一行）。少了第二句的症状很具体：
     * 设置里明明选的是半软，召唤出来却是垮的 —— 因为它停在 0（全松垮）。
     */
    private fun applyPetSettings() {
        val settings = SettingsStore(this).load()
        pet?.applySettings(settings)
        pet?.stiffness = settings.defaultStiffness
    }

    private fun show() {
        val store = CharacterStore(this)
        store.ensureSeeded()
        val folder = petFolder(this) ?: run {
            stopSelf()
            return
        }
        val metrics = resources.displayMetrics

        val poses = store.loadPoses(folder)
        val view = PhysicsSandboxView(this).apply {
            setDesktopMode(true)
            load(
                folder,
                store.loadLogic(folder.id),
                store.loadProps(),
                store.propsDir,
                store.loadObjectLogic(folder),
                // 动作**必须**一起交过来：规则里写的是「摆动作 挥手」，名字在这一边查不到
                // 就什么都不发生（以前还会把宠物弄瘫）。桌面这一只是第二个实例，忘了这一份
                // 的症状是"在测试场好好的，召唤到桌面上就不摆动作了"。
                poses.associate { it.name to it.angles },
            )
        }
        pet = view
        applyPetSettings()
        // 桌面上这一只接着做测试场上正摆着的那个动作。召唤是"把这一只送到桌面上"，不是
        // "另起一只"：动作是眼睛看得见的那一部分，掉了最明显。
        rememberedPose(this)?.let { view.playPose(it, home = false) }

        // 宠物那一层：满屏。世界就是屏幕 —— 它落在屏幕底边上，也会在整块屏幕里被拖来拖去。
        val petLayer = FrameLayout(this).apply { addView(view) }
        val p = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE 不抢键盘；NOT_TOUCH_MODAL 让这一层之外的触摸照样给下面的 App
            // （满屏时用不上，但加上它，将来窗口不占满时行为也是对的）。
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        petParams = p
        petRoot = petLayer
        runCatching { window.addView(petLayer, p) }.onFailure { stopSelf() }

        // 控制条：很小的一层，单独的窗口，所以"宠物那层点得穿"的时候它照样收得到手指。
        val bar = buildStrip()
        strip = bar
        val sp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(10)
        }
        stripParams = sp
        runCatching { window.addView(bar, sp) }
        // 初始状态也从这一个地方落地：两个窗口的 alpha 和触摸标志只有一处说了算。
        setTouchable(touchable)
    }

    private val baseFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private fun buildStrip(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xCC2B2A38.toInt())
            }
        }
        val toggle = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { setTouchable(!touchable) }
        }
        val close = TextView(this).apply {
            text = getString(R.string.overlay_close)
            textSize = 12f
            setTextColor(0xFFFFD9D9.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { stopSelf() }
        }
        row.addView(toggle)
        row.addView(close)
        toggleTag = toggle
        return row
    }

    private fun refreshStrip() {
        toggleTag?.text = getString(
            if (touchable) R.string.overlay_mode_touchable else R.string.overlay_mode_through
        )
    }

    /**
     * 可摸 / 穿透。
     *
     * 全屏那一层如果一直收触摸，底下的 App 就等于被一块透明玻璃盖住了 —— 所以这两个状态
     * 必须能切，而且切换的按钮必须在**另一层**上（不然穿透之后就没有东西能把它切回来）。
     *
     * **光有 FLAG_NOT_TOUCHABLE 不够，这是这一版修的东西。** Android 12 起有一条
     * 「不可信触摸」规则：从**不透明的悬浮窗**穿过去的触摸，系统直接丢掉（logcat 里是
     * "Untrusted touch due to occlusion by <包名>"）。TYPE_APPLICATION_OVERLAY 的窗口
     * 明说了不算可信窗口，而"够不够透明"看的是**窗口自己的 alpha**：系统把一组系统警告窗
     * 的合成不透明度算出来，**大于 0.8 就不放行**（Android 12 的默认上限就是 0.8）。
     * 我们这个窗口是全屏的、alpha 是 1，所以标志设对了、按钮也切了、界面也变了 ——
     * 手指还是穿不过去，而且什么都不报。
     *
     * 所以穿透时把两个窗口的 alpha 一起压到 [PASS_THROUGH_ALPHA]（0.8 以下）：宠物会
     * 淡一点点（20%），换来的是"点得穿"真的点得穿。控制条也一起压 —— 它也是系统警告窗，
     * 而这条规则算的是**一组**窗口的合成值。
     */
    private fun setTouchable(on: Boolean) {
        val p = petParams ?: return
        val was = touchable
        p.flags = if (on) {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        p.alpha = if (on) 1f else PASS_THROUGH_ALPHA
        stripParams?.alpha = if (on) 1f else PASS_THROUGH_ALPHA
        // 两个窗口各自更新；updateViewLayout 会在窗口被系统收走之后抛异常，
        // 那种情况重新挂一次（参数是新的那一份），别再无声无息地什么都不做。
        val ok = applyParams(petRoot, p) and applyParams(strip, stripParams)
        touchable = if (ok) on else was
        if (!ok) {
            // 说不了话的开关比没有这个开关更糟：界面上的字会变，手指底下什么都没发生。
            Toast.makeText(this, R.string.overlay_switch_failed, Toast.LENGTH_SHORT).show()
        }
        refreshStrip()
    }

    /** 更新一个窗口的参数；窗口被收走过就重新挂一次。false = 两样都没成。 */
    private fun applyParams(view: View?, params: WindowManager.LayoutParams?): Boolean {
        if (view == null || params == null) return true
        if (runCatching { window.updateViewLayout(view, params) }.isSuccess) return true
        runCatching { window.removeView(view) }
        return runCatching { window.addView(view, params) }.isSuccess
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

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
        const val ACTION_POSE = "dev.atp.pet.overlay.POSE"
        const val ACTION_SETTINGS = "dev.atp.pet.overlay.SETTINGS"
        const val EXTRA_ID = "id"

        /**
         * 穿透时窗口的不透明度。
         *
         * Android 12 起，从悬浮窗穿过去的触摸在窗口**合成不透明度 > 0.8** 时会被系统丢掉
         * （见 setTouchable 那段注释和系统文档的 "Untrusted touch events are blocked"）。
         * 0.8 是官方写的上限，取 0.79 留一点余量；代价是穿透的时候宠物淡 20%。
         */
        private const val PASS_THROUGH_ALPHA = 0.79f

        private const val CHANNEL = "pet_overlay"
        private const val NOTIFICATION_ID = 4711

        /** Where the pet on the bench is remembered, so the floating one is the same pet. */
        private const val PREFS = "overlay"
        private const val KEY_PET = "pet"

        /** 召唤时测试场上摆着的那个预设动作的名字，跟着宠物一起过来。 */
        private const val KEY_POSE = "pose"

        /** 召唤时它穿着的那套骨骼，同理：动作是按套存的。 */
        private const val KEY_RIG = "rig"

        /** Whether the windows are up. Read by the app's button, which is the way in and out. */
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
        fun rememberPet(
            context: Context,
            id: String,
            pose: String? = null,
            /** 测试场上这一只正穿着哪套骨骼。动作是按骨骼套存的，身体记错了动作就全对不上。 */
            rig: String = "",
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PET, id).putString(KEY_POSE, pose.orEmpty())
                .putString(KEY_RIG, rig).apply()
        }

        /** 桌面上这一只换了身体：记下来，下一次召唤还是这副。 */
        fun rememberRig(context: Context, name: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RIG, name).apply()
        }

        /** 测试场上正摆着的那个动作，""=没摆。见 [show]。 */
        private fun rememberedPose(context: Context): String? = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POSE, "").orEmpty().ifEmpty { null }

        fun petFolder(context: Context): CharacterFolder? {
            val store = CharacterStore(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wanted = prefs.getString(KEY_PET, "").orEmpty()
            val rig = prefs.getString(KEY_RIG, "").orEmpty()
            val folder = store.folder(wanted) ?: store.list().firstOrNull() ?: return null
            // 骨骼套跟着宠物一起过来。动作预设是**按套**存的，所以身体不对，规则里那些
            // 动作名在这一边一个都对不上 —— 而"桌面上那只不摆动作"看起来像是动作坏了。
            // 那一套已经不在了就用默认的，宁可换个身体也不要一只不出来的桌宠。
            return if (rig.isNotEmpty() && rig in folder.rigs()) folder.withRig(rig) else folder
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
         * where the service lives, so it is the truth about whether there are windows.
         */
        fun stop(context: Context) {
            if (!running) return
            context.startService(
                Intent(context, PetOverlayService::class.java).setAction(ACTION_STOP)
            )
        }

        /** One command to the pet that is on the desktop: a prop, a switch, a body, settings. */
        fun send(context: Context, action: String, id: String) {
            if (!running) return
            context.startService(
                Intent(context, PetOverlayService::class.java).setAction(action)
                    .putExtra(EXTRA_ID, id)
            )
        }
    }
}
