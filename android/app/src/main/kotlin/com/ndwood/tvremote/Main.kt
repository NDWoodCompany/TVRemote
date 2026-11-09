package com.ndwood.tvremote

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.widget.*
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object Cfg {
    lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("cfg", 0) }
    private val def = mapOf("sonyIp" to "", "psk" to "", "tvMac" to "", "boxIp" to "127.0.0.1",
        "adbPort" to "5555", "searchApp" to "com.teamsmart.videomanager.tv", "follow" to "1", "hdmi" to "hdmi2")
    val keys: Set<String> get() = def.keys
    operator fun get(k: String): String = sp.getString(k, def[k] ?: "") ?: ""
    operator fun set(k: String, v: String) { sp.edit().putString(k, v).apply() }
}

fun myIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "?"
} catch (e: Exception) { "?" }

// ---------- Sony BRAVIA (IRCC + Wake-on-LAN) ----------
object Tv {
    private fun ir(id: Int) = "AAAAAQAAAAEAAAA" + (65 + id).toChar() + "Aw=="
    val codes = mutableMapOf("power" to ir(21), "wake" to ir(46), "off" to ir(47), "input" to ir(37), "tv" to ir(36),
        "volUp" to ir(18), "volDown" to ir(19), "mute" to ir(20), "chUp" to ir(16), "chDown" to ir(17),
        "hdmi1" to "AAAAAgAAABoAAABaAw==", "hdmi2" to "AAAAAgAAABoAAABbAw==",
        "hdmi3" to "AAAAAgAAABoAAABcAw==", "hdmi4" to "AAAAAgAAABoAAABdAw==").also { for (i in 0..9) it["num$i"] = ir((i + 9) % 10) }
    const val PS = """{"method":"getPowerStatus","id":1,"params":[],"version":"1.0"}"""

    fun http(path: String, body: String, h: Map<String, String>): String {
        if (Cfg["sonyIp"].isEmpty()) Disc.ensure()
        if (Cfg["sonyIp"].isEmpty()) throw Exception("Chưa tìm thấy TV – bấm \"Tự tìm TV\" hoặc nhập IP trong Cài đặt")
        val c = URL("http://${Cfg["sonyIp"]}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000; c.requestMethod = "POST"; c.doOutput = true
        if (Cfg["psk"].isNotEmpty()) c.setRequestProperty("X-Auth-PSK", Cfg["psk"])
        h.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code >= 400) throw Exception("TV trả HTTP $code" + if (code == 403) " – sai/thiếu PSK hoặc chưa bật IP Control" else "")
        return txt
    }
    private fun send(code: String) {
        val b = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>$code</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>"""
        http("/sony/IRCC", b, mapOf("Content-Type" to "text/xml; charset=UTF-8", "SOAPAction" to "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\""))
    }
    // Gửi lệnh; nếu không tới được TV thì kiểm tra/quét lại IP rồi thử 1 lần nữa
    fun ircc(code: String) {
        try { send(code) } catch (e: Exception) {
            val r = Disc.ensure()
            if (r == "moved" || r == "found") send(code) else throw e
        }
    }
    fun status(): String = try {
        JSONObject(http("/sony/system", PS, mapOf("Content-Type" to "application/json"))).optJSONArray("result")?.optJSONObject(0)?.optString("status") ?: ""
    } catch (e: Exception) { "" }
    fun diag(): String = try { "OK · " + http("/sony/system", PS, mapOf("Content-Type" to "application/json")) } catch (e: Exception) { "Không kết nối được (${e.message})" }
    fun wol(): Boolean {
        val m = Cfg["tvMac"].replace(Regex("[^0-9a-fA-F]"), ""); if (m.length != 12) return false
        val mac = ByteArray(6) { m.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val p = ByteArray(6) { 0xFF.toByte() } + ByteArray(96).also { for (i in 0 until 16) mac.copyInto(it, i * 6) }
        DatagramSocket().use { s ->
            s.broadcast = true
            for (a in listOf("255.255.255.255", Cfg["sonyIp"].replaceFirst(Regex("\\d+$"), "255")))
                try { s.send(DatagramPacket(p, p.size, InetAddress.getByName(a), 9)) } catch (e: Exception) {}
        }
        return true
    }
    // Sony BRAVIA dùng IRCC Power chuẩn để bật/tắt. Tránh mã off/wake tự suy diễn.
    // Nếu TV đang standby, gửi Wake-on-LAN trước rồi gửi Power.
    fun power() {
        val s = status()
        if (s == "standby") wol()
        ircc(codes["power"]!!)
    }
    fun on() {
        if (status() == "active") return
        repeat(5) {
            wol()
            try { ircc(codes["power"]!!); return } catch (e: Exception) { Thread.sleep(2500) }
        }
        throw Exception("Không gửi được lệnh bật TV")
    }
    fun off() {
        when (status()) {
            "standby" -> return
            "active" -> ircc(codes["power"]!!)
            else -> throw Exception("Không đọc được trạng thái TV; không gửi lệnh nguồn để tránh bật nhầm TV đang ngủ")
        }
    }
    // Chờ TV bật xong (tối đa ~30s); nếu TV không báo trạng thái thì chờ cố định ~12s
    fun waitActive() {
        var seen = false
        for (i in 0 until 15) {
            val s = status(); if (s == "active") { Thread.sleep(1500); return }
            if (s.isNotEmpty()) seen = true
            if (!seen && i == 3) { Thread.sleep(4000); return }
            Thread.sleep(2000)
        }
    }
}

// ---------- Tự tìm TV (IP + MAC) trong Wi-Fi hiện tại ----------
object Disc {
    private var last = 0L
    private var lastRes = "none"
    private fun fetch(u: String, body: String? = null): Pair<Int, String>? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 700; c.readTimeout = 1200
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        Pair(code, (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
    } catch (e: Exception) { null }
    fun isSony(ip: String): Boolean {
        fetch("http://$ip:52323/dd.xml")?.let { if (it.second.contains("Sony", true)) return true }
        val r = fetch("http://$ip/sony/system", Tv.PS) ?: return false
        return r.second.contains("\"result\"") || r.second.contains("\"error\"")
    }
    // IP -> MAC từ bảng ARP (đọc được trên Android ≤ 9; Android mới hơn thì phải nhập MAC tay)
    fun arp(): Map<String, String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3].contains(":") && p[3] != "00:00:00:00:00:00") p[0] to p[3].lowercase() else null
        }.toMap()
    } catch (e: Exception) { emptyMap() }
    private fun sweep() {
        val base = myIp().substringBeforeLast('.'); if (base.count { it == '.' } != 2) return
        val ex = Executors.newFixedThreadPool(64)
        for (i in 1..254) ex.execute { try { InetAddress.getByName("$base.$i").isReachable(300) } catch (e: Exception) {} }
        ex.shutdown(); ex.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
    }
    // ok = IP đang lưu còn đúng; standby = IP/MAC khớp nhưng TV đang ngủ; moved = đã cập nhật IP mới theo MAC;
    // found = tìm thấy TV mới và lưu IP/MAC; none = không thấy
    @Synchronized fun ensure(force: Boolean = false): String {
        val ip = Cfg["sonyIp"]; val mac = Cfg["tvMac"].lowercase()
        if (ip.isNotEmpty() && isSony(ip)) { if (mac.isEmpty()) arp()[ip]?.let { Cfg["tvMac"] = it }; return "ok" }
        if (!force && System.currentTimeMillis() - last < 60000) return lastRes
        sweep(); val a = arp()
        var res = "none"
        if (ip.isNotEmpty() && mac.isNotEmpty() && a[ip] == mac) res = "standby"
        else {
            val moved = if (mac.isNotEmpty()) a.entries.firstOrNull { it.value == mac }?.key else null
            if (moved != null) { Cfg["sonyIp"] = moved; res = "moved" }
            else {
                val ex = Executors.newFixedThreadPool(16)
                val fs = a.keys.map { k -> ex.submit(Callable { isSony(k) }) }
                val found = a.keys.filterIndexed { i, _ -> fs[i].get() }
                ex.shutdown()
                if (found.isNotEmpty()) { Cfg["sonyIp"] = found[0]; Cfg["tvMac"] = a[found[0]] ?: ""; res = "found" }
            }
        }
        last = System.currentTimeMillis(); lastRes = res; return res
    }
}

// ---------- Nút nguồn: bật Box -> bật TV -> HDMI; tắt TV -> tắt Box ----------
object Scene {
    @Volatile var busy = false
    @Volatile var err = ""
    @Volatile var lastCommandAt = 0L
    fun tvOn() {
        Tv.on()
        Tv.waitActive()
        Tv.ircc(Tv.codes[Cfg["hdmi"]] ?: Tv.codes["hdmi2"]!!)
    }
    @Synchronized fun toggle(c: Context): String {
        if (busy) return "Đang xử lý…"
        busy = true
        err = ""
        lastCommandAt = System.currentTimeMillis()
        val tvState = Tv.status()
        val wasAwake = tvState == "active"
        Thread {
            try {
                if (wasAwake) {
                    // Tắt TV trước, sau đó chỉ đưa màn hình Box vào sleep.
                    // Không gửi KEYCODE_POWER dự phòng vì đó là phím toggle, có thể bật màn hình lại.
                    try { Tv.off() } catch (e: Exception) { err = "TV: " + (e.message ?: "Không gửi được lệnh tắt") }
                    Thread.sleep(900)
                    try { Adb.sh("input keyevent 223") } catch (e: Exception) { err += " · Box: " + (e.message ?: "Không sleep được") }
                } else {
                    // Đánh thức màn hình Box trước, đợi hệ thống và Wi-Fi/ADB ổn định rồi bật TV.
                    Adb.sh("input keyevent 224")
                    Thread.sleep(1200)
                    tvOn()
                }
            } catch (e: Exception) {
                err = e.message ?: "Lỗi điều khiển"
            } finally {
                lastCommandAt = System.currentTimeMillis()
                Thread.sleep(1200)
                busy = false
            }
        }.start()
        return if (wasAwake) "Đang tắt TV rồi đưa Box vào chế độ ngủ" else "Đang đánh thức Box rồi bật TV"
    }
}

// ---------- ADB tới chính Box (127.0.0.1) ----------
object Adb {
    private var d: Dadb? = null
    lateinit var dir: File
    fun reset() { try { d?.close() } catch (e: Exception) {}; d = null }
    private fun conn(): Dadb {
        d?.let { return it }
        val pri = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!pri.exists()) AdbKeyPair.generate(pri, pub)
        val x = Dadb.create(Cfg["boxIp"], Cfg["adbPort"].toIntOrNull() ?: 5555, AdbKeyPair.read(pri, pub)); d = x; return x
    }
    @Synchronized fun sh(cmd: String): String {
        try { return conn().shell(cmd).allOutput.trim() } catch (e: Exception) { reset() }
        try { return conn().shell(cmd).allOutput.trim() } catch (e: Exception) {
            throw Exception("ADB lỗi – bật ADB qua mạng trên Box và chọn \"Luôn cho phép\" trên màn hình TV (${e.message})")
        }
    }
}

object Box {
    val KEY = mutableMapOf("UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23, "BACK" to 4, "EXIT" to 3, "HOME" to 3,
        "POWER" to 26, "VOL+" to 24, "VOL-" to 25, "MUTE" to 164, "*" to 17, "#" to 18).also { for (i in 0..9) it["$i"] = 7 + i }
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    fun text(t: String, enter: Boolean) {
        if (t.all { it.code in 32..126 }) Adb.sh("input text " + q(t.replace(" ", "%s")))
        else {
            val pk = "com.android.adbkeyboard"; val ime = "$pk/.AdbIME"
            if (!Adb.sh("pm list packages $pk").contains(pk)) throw Exception("Gõ tiếng Việt cần cài ADBKeyboard ($pk) trên Box")
            val prev = Adb.sh("settings get secure default_input_method")
            Adb.sh("ime enable $ime;ime set $ime")
            try { Adb.sh("am broadcast -a ADB_INPUT_B64 --es msg " + android.util.Base64.encodeToString(t.toByteArray(), android.util.Base64.NO_WRAP)) }
            finally { Thread.sleep(300); if (prev.isNotEmpty() && prev != "null") try { Adb.sh("ime set $prev") } catch (e: Exception) {} }
        }
        if (enter) Adb.sh("input keyevent 66")
    }
    fun search(qs: String) {
        val app = Cfg["searchApp"]
        val o = Adb.sh("am start -a android.intent.action.VIEW -d " + q("https://www.youtube.com/results?search_query=" + URLEncoder.encode(qs, "UTF-8")) + (if (app.isNotEmpty()) " -p $app" else ""))
        if (o.contains("Error")) throw Exception(o.lines().first { it.contains("Error") } + " (app chưa cài hoặc không hỗ trợ link tìm kiếm)")
    }
    fun open() {
        val p = Cfg["searchApp"].ifEmpty { "com.google.android.youtube.tv" }
        fun a(c: String) = Adb.sh("am start -a android.intent.action.MAIN -c android.intent.category.$c -p $p")
        var o = a("LEANBACK_LAUNCHER"); if (o.contains("Error")) o = a("LAUNCHER")
        if (o.contains("Error")) throw Exception("Không mở được $p")
    }
    fun key(key: String) { val code = KEY[key] ?: throw Exception("Phím không hỗ trợ: $key"); Adb.sh("input keyevent $code") }
}

// ---------- Dịch vụ nền tự chạy cùng hệ thống ----------
class BridgeService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var last = 0L
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); Cfg.init(this); Adb.dir = filesDir }
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("b", "TVRemote", NotificationManager.IMPORTANCE_MIN))
        val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "b") else Notification.Builder(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        startForeground(1, nb.setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("TVRemote đang chạy").setContentText("Đang chạy nền · điều khiển native").setContentIntent(open).setOngoing(true).build())
        if (wl == null) {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:srv").also { if (!it.isHeld) it.acquire() }
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvremote").also { if (!it.isHeld) it.acquire() }
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, it: Intent) {
                    val now = System.currentTimeMillis()
                    if (Cfg["follow"] != "1" || Scene.busy || now - last < 3000 || now - Scene.lastCommandAt < 10000) return
                    last = now
                    val on = it.action == Intent.ACTION_SCREEN_ON
                    Thread { try { if (on) Scene.tvOn() else Tv.off() } catch (e: Exception) { Scene.err = e.message ?: "Lỗi đồng bộ TV" } }.start()
                }
            }.also { r -> registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }) }
            val boot = i?.getBooleanExtra("boot", false) == true
            Thread {
                try { Disc.ensure() } catch (e: Exception) {}
                if (boot) {
                    if (Cfg["follow"] == "1") try { Scene.tvOn() } catch (e: Exception) {}
                }
            }.start()
        }
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        val i = Intent(applicationContext, BridgeService::class.java)
        val pi = PendingIntent.getService(this, 17, i, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        (getSystemService(ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        screenReceiver = null
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) {}
        wl = null; wifiLock = null
        super.onDestroy()
    }
    companion object {
        fun start(c: Context, boot: Boolean = false) {
            val i = Intent(c, BridgeService::class.java).putExtra("boot", boot)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Cfg.init(c); BridgeService.start(c, i.action != Intent.ACTION_MY_PACKAGE_REPLACED) }
}

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var tabArea: LinearLayout
    private var selectedTab = 0
    private val bg = 0xFF101820.toInt(); private val panel = 0xFF1D2A35.toInt(); private val accent = 0xFF35C7B0.toInt(); private val fg = 0xFFF1F5F8.toInt()
    override fun onCreate(b: Bundle?) { super.onCreate(b); Cfg.init(this); Adb.dir = filesDir; BridgeService.start(this); buildUi() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun label(t: String, size: Float = 15f) = TextView(this).apply { text=t; textSize=size; setTextColor(fg); gravity=Gravity.CENTER; setPadding(dp(6),dp(8),dp(6),dp(8)) }
    private fun button(t: String, action: () -> Unit): Button = Button(this).apply { text=t; isAllCaps=false; textSize=15f; setTextColor(fg); setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel)); setOnClickListener { action() } }
    private fun row(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) { val r=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; weightSum=items.size.toFloat() }; items.forEach { (t,a) -> r.addView(button(t,a), LinearLayout.LayoutParams(0,dp(58),1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }; parent.addView(r) }
    private fun action(name: String, block: () -> Unit) { Thread { val result=try { block(); "Đã gửi: $name" } catch(e:Exception) { "Lỗi $name: ${e.message}" }; runOnUiThread { status.text=result } }.start() }
    private fun buildUi() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(12),dp(8),dp(12),dp(8)) }
        val head=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        head.addView(label("TVRemote",22f),LinearLayout.LayoutParams(0,dp(52),1f)); head.addView(button("⟳ Tìm thiết bị") { discover() },LinearLayout.LayoutParams(-2,dp(48)))
        root.addView(head); status=label("Đang khởi động dịch vụ…",12f).apply { gravity=Gravity.START; setBackgroundColor(panel) }; root.addView(status)
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }; listOf("ĐIỀU KHIỂN","BÀN PHÍM","CÀI ĐẶT").forEachIndexed { i,t -> tabs.addView(button(t) { selectedTab=i; renderTab() },LinearLayout.LayoutParams(0,dp(48),1f)) }; root.addView(tabs)
        tabArea=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(8),0,0) }; root.addView(tabArea,LinearLayout.LayoutParams(-1,0,1f))
        row(root,"Chạy nền / về Home" to { BridgeService.start(this); moveTaskToBack(true) },"Kiểm tra kết nối" to { checkConnections() })
        setContentView(root); renderTab(); refreshStatus()
    }
    private fun renderTab() { tabArea.removeAllViews(); when(selectedTab) {
        0 -> { row(tabArea,"⏻ NGUỒN / SCENE" to { action("Scene") { Scene.toggle(this) } }); row(tabArea,"TV Power" to { action("TV Power") { Tv.power() } },"HDMI 1" to { action("HDMI 1") { Tv.ircc(Tv.codes["hdmi1"]!!) } },"HDMI 2" to { action("HDMI 2") { Tv.ircc(Tv.codes["hdmi2"]!!) } }); row(tabArea,"TV Vol +" to { action("TV Vol+") { Tv.ircc(Tv.codes["volUp"]!!) } },"TV Vol −" to { action("TV Vol-") { Tv.ircc(Tv.codes["volDown"]!!) } },"TV Mute" to { action("TV Mute") { Tv.ircc(Tv.codes["mute"]!!) } }); row(tabArea,"Box Vol +" to { action("Box Vol+") { Box.key("VOL+") } },"Box Vol −" to { action("Box Vol-") { Box.key("VOL-") } },"Box Mute" to { action("Box Mute") { Box.key("MUTE") } }); row(tabArea,"BACK" to { action("Back") { Box.key("BACK") } },"HOME" to { action("Home") { Box.key("HOME") } },"EXIT" to { action("Exit") { Box.key("EXIT") } }); val pad=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }; row(pad,"▲" to { action("Up") { Box.key("UP") } }); row(pad,"◀" to { action("Left") { Box.key("LEFT") } },"OK" to { action("OK") { Box.key("OK") } },"▶" to { action("Right") { Box.key("RIGHT") } }); row(pad,"▼" to { action("Down") { Box.key("DOWN") } }); tabArea.addView(pad); row(tabArea,"Mở YouTube" to { action("Mở ứng dụng tìm kiếm") { Box.open() } },"🎙 Giọng nói" to { voice() }) }
        1 -> { row(tabArea,"1" to { sendKey("1") },"2" to { sendKey("2") },"3" to { sendKey("3") }); row(tabArea,"4" to { sendKey("4") },"5" to { sendKey("5") },"6" to { sendKey("6") }); row(tabArea,"7" to { sendKey("7") },"8" to { sendKey("8") },"9" to { sendKey("9") }); row(tabArea,"*" to { sendKey("*") },"0" to { sendKey("0") },"#" to { sendKey("#") }); row(tabArea,"CH +" to { action("Channel+") { Box.key("UP") } },"CH −" to { action("Channel-") { Box.key("DOWN") } }) }
        else -> settingsUi()
    } }
    private fun sendKey(k:String) = action("Phím $k") { Box.key(k) }
    private fun discover() = action("Tìm thiết bị") { val r=Disc.ensure(true); "Quét xong: $r · TV ${Cfg["sonyIp"]}".also { runOnUiThread { refreshStatus() } } }
    private fun checkConnections() = action("Kiểm tra") { val tv=Tv.diag(); val box=Adb.sh("getprop ro.product.model"); "TV: $tv\nBox: $box" }
    private fun refreshStatus() { status.text="TV: ${Cfg["sonyIp"].ifEmpty { "chưa tìm thấy" }}   ·   Box: ${Cfg["boxIp"]}:${Cfg["adbPort"]}\nDịch vụ điều khiển đang chạy trên điện thoại" }
    private fun settingsUi() {
        fun field(title:String,key:String,hint:String=""): EditText { tabArea.addView(label(title).apply { gravity=Gravity.START }); val e=EditText(this).apply { setText(Cfg[key]); setSingleLine(true); setTextColor(fg); setHintTextColor(0xFF9AAAB5.toInt()); setHint(hint); setPadding(dp(12),0,dp(12),0); setBackgroundColor(panel) }; tabArea.addView(e,LinearLayout.LayoutParams(-1,dp(48))); return e }
        val tv=field("IP TV Sony","sonyIp","Ví dụ 192.168.1.234"); val box=field("IP Android TV Box","boxIp","Ví dụ 192.168.1.233"); val psk=field("Sony PSK (nếu đã bật IP Control)","psk","Để trống nếu không dùng"); val mac=field("MAC TV (dùng Wake-on-LAN)","tvMac","AA:BB:CC:DD:EE:FF")
        row(tabArea,"LƯU CẤU HÌNH" to { Cfg["sonyIp"]=tv.text.toString().trim(); Cfg["boxIp"]=box.text.toString().trim(); Cfg["psk"]=psk.text.toString().trim(); Cfg["tvMac"]=mac.text.toString().trim(); refreshStatus(); status.text="Đã lưu cấu hình thiết bị" })
        row(tabArea,"Kiểm tra ADB" to { action("ADB") { "ADB OK · "+Adb.sh("getprop ro.product.model") } },"Chạy nền" to { BridgeService.start(this); requestBatteryExemption() })
        row(tabArea,"Cập nhật APK" to { Toast.makeText(this,"Bản cập nhật sẽ được bổ sung trong bước hoàn thiện",Toast.LENGTH_LONG).show() })
    }
    private fun voice() { try { val i=Intent("android.speech.action.RECOGNIZE_SPEECH").putExtra("android.speech.extra.LANGUAGE","vi-VN").putExtra("android.speech.extra.LANGUAGE_MODEL","free_form").putExtra("android.speech.extra.PROMPT","Nói nội dung cần tìm trên YouTube"); startActivityForResult(i,41) } catch(e:Exception) { status.text="Thiết bị chưa có dịch vụ nhận dạng giọng nói tiếng Việt" } }
    override fun onActivityResult(req:Int,res:Int,data:Intent?) { super.onActivityResult(req,res,data); if(req==41 && res==RESULT_OK) { val q=data?.getStringArrayListExtra("android.speech.extra.RESULTS")?.firstOrNull().orEmpty(); if(q.isNotBlank()) action("Tìm kiếm: $q") { Box.search(q) } } }
    private fun requestBatteryExemption() { if(Build.VERSION.SDK_INT>=23) try { val pm=getSystemService(POWER_SERVICE) as PowerManager; if(!pm.isIgnoringBatteryOptimizations(packageName)) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName"))) else status.text="Đã miễn tối ưu pin" } catch(e:Exception) { status.text="Không mở được cài đặt pin: ${e.message}" } else status.text="Android 7 không cần quyền tối ưu pin này" }
    override fun onResume() { super.onResume(); if(::status.isInitialized) refreshStatus() }
    @Suppress("DEPRECATION") override fun onBackPressed() { moveTaskToBack(true) }
}
