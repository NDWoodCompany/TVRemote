package com.ndwood.tvremote

import android.app.*
import android.content.*
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.widget.*
import dadb.AdbKeyPair
import dadb.Dadb
import fi.iki.elonen.NanoHTTPD
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
    fun power() {
        val s = status(); if (s == "active") { ircc(codes["off"]!!); return }
        val w = wol()
        try { ircc(codes[if (s == "standby") "wake" else "power"]!!) } catch (e: Exception) { if (!w) throw e }
    }
    fun on() { repeat(5) { wol(); try { ircc(codes["wake"]!!); return } catch (e: Exception) { Thread.sleep(3000) } } }
    fun off() { ircc(codes["off"]!!) }
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
    fun awake(c: Context) = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    fun tvOn() { Tv.on(); Tv.waitActive(); Tv.ircc(Tv.codes[Cfg["hdmi"]] ?: Tv.codes["hdmi2"]!!) }
    fun toggle(c: Context): String {
        if (busy) return "Đang xử lý…"
        busy = true; err = ""
        val on = !awake(c)
        Thread {
            try {
                if (on) { try { Adb.sh("input keyevent 224") } catch (e: Exception) { err = e.message ?: "" }; tvOn() }
                else {
                    try { Tv.off() } catch (e: Exception) { err = e.message ?: "" }
                    Thread.sleep(1000); Adb.sh("input keyevent 223"); Thread.sleep(2000)
                    if (awake(c)) Adb.sh("input keyevent 26")
                }
            } catch (e: Exception) { err = e.message ?: "Lỗi" } finally { Thread.sleep(3000); busy = false }
        }.start()
        return if (on) "Đang bật Box → TV → ${Cfg["hdmi"].uppercase()}" else "Đang tắt TV → Box"
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
}

// ---------- HTTP: phục vụ giao diện + API (cùng giao thức với bridge/server.js) ----------
class Srv(private val ctx: Context) : NanoHTTPD(8765) {
    private fun cors(r: Response): Response { r.addHeader("Access-Control-Allow-Origin", "*"); r.addHeader("Access-Control-Allow-Headers", "Content-Type"); r.addHeader("Access-Control-Allow-Private-Network", "true"); return r }
    private fun js(o: JSONObject) = cors(newFixedLengthResponse(Response.Status.OK, "application/json", o.toString()))
    private fun ok() = js(JSONObject().put("ok", true))
    override fun serve(s: IHTTPSession): Response {
        val p = s.uri
        if (s.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        if (p.startsWith("/api/")) {
            var b = JSONObject()
            if (s.method == Method.POST) { val m = HashMap<String, String>(); try { s.parseBody(m); b = JSONObject(m["postData"] ?: "{}") } catch (e: Exception) {} }
            return try { api(p, s.method == Method.POST, b) } catch (e: Exception) { js(JSONObject().put("ok", false).put("error", e.message ?: "Lỗi")) }
        }
        val f = if (p == "/") "index.html" else p.substring(1)
        return try {
            if (f.contains("..")) throw Exception()
            val bytes = ctx.assets.open("web/$f").readBytes()
            val mime = when (f.substringAfterLast('.')) { "html" -> "text/html; charset=utf-8"; "css" -> "text/css"; "js" -> "text/javascript"
                "webmanifest" -> "application/manifest+json"; "png" -> "image/png"; else -> "application/octet-stream" }
            newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()).also { it.addHeader("Cache-Control", "no-cache") }
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404") }
    }
    private fun api(p: String, post: Boolean, b: JSONObject): Response = when (p) {
        "/api/config" -> if (!post) {
            val c = JSONObject(); for (k in Cfg.keys) if (k != "psk") c.put(k, Cfg[k])
            js(JSONObject().put("ok", true).put("cfg", c.put("pskSet", Cfg["psk"].isNotEmpty())))
        } else {
            for (k in listOf("sonyIp", "tvMac", "boxIp", "follow")) if (b.has(k)) Cfg[k] = b.getString(k).trim()
            if (b.has("searchApp") && b.getString("searchApp").matches(Regex("[\\w.]*"))) Cfg["searchApp"] = b.getString("searchApp")
            if (b.optString("psk").isNotEmpty()) Cfg["psk"] = b.getString("psk")
            if (b.optString("adbPort").toIntOrNull() != null) Cfg["adbPort"] = b.getString("adbPort")
            if (b.optString("hdmi") in listOf("tv", "hdmi1", "hdmi2", "hdmi3", "hdmi4")) Cfg["hdmi"] = b.getString("hdmi")
            Adb.reset(); ok()
        }
        "/api/sony" -> { val c = b.optString("cmd"); if (c == "power") Tv.power() else Tv.ircc(Tv.codes[c] ?: throw Exception("Lệnh TV không hỗ trợ")); ok() }
        "/api/box" -> { Adb.sh("input keyevent " + (Box.KEY[b.optString("key")] ?: throw Exception("Phím không hỗ trợ"))); ok() }
        "/api/box/text" -> { val t = b.optString("text"); if (t.isEmpty() || t.length > 200) throw Exception("Nội dung không hợp lệ"); Box.text(t, b.optBoolean("enter")); ok() }
        "/api/box/search" -> { val q = b.optString("q").trim(); if (q.isEmpty()) throw Exception("Thiếu nội dung"); Box.search(q.take(200)); ok() }
        "/api/box/open" -> { Box.open(); ok() }
        "/api/scene" -> js(JSONObject().put("ok", true).put("msg", Scene.toggle(ctx)))
        "/api/discover" -> { val r = Disc.ensure(true); js(JSONObject().put("ok", true).put("result", r)) }
        "/api/status" -> js(JSONObject().put("ok", true).put("sony", Tv.diag()).put("scene", Scene.err)
            .put("box", try { "OK · " + Adb.sh("getprop ro.product.model") } catch (e: Exception) { "Lỗi: " + e.message }))
        else -> throw Exception("API không tồn tại")
    }
}

// ---------- Dịch vụ nền tự chạy cùng hệ thống ----------
class BridgeService : Service() {
    private var srv: Srv? = null
    private var wl: PowerManager.WakeLock? = null
    private var last = 0L
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); Cfg.init(this); Adb.dir = filesDir }
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("b", "TVRemote", NotificationManager.IMPORTANCE_MIN))
        val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "b") else Notification.Builder(this)
        startForeground(1, nb.setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("TVRemote đang chạy").setContentText("http://${myIp()}:8765").build())
        if (srv == null) {
            srv = Srv(this).also { try { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } catch (e: Exception) {} }
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:srv").also { it.acquire() }
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvremote").also { it.acquire() }
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, it: Intent) {
                    val now = System.currentTimeMillis(); if (Cfg["follow"] != "1" || Scene.busy || now - last < 3000) return; last = now
                    val on = it.action == Intent.ACTION_SCREEN_ON
                    Thread { try { if (on) Scene.tvOn() else Tv.off() } catch (e: Exception) {} }.start()
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
            val boot = i?.getBooleanExtra("boot", false) == true
            Thread {
                try { Disc.ensure() } catch (e: Exception) {}
                if (boot) {
                    // Nếu Box có root: bật ADB TCP sau mỗi lần khởi động (không root thì bỏ qua)
                    try { Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop service.adb.tcp.port 5555;stop adbd;start adbd")).waitFor() } catch (e: Exception) {}
                    if (Cfg["follow"] == "1") try { Scene.tvOn() } catch (e: Exception) {}
                }
            }.start()
        }
        return START_STICKY
    }
    override fun onDestroy() { srv?.stop(); wl?.release(); super.onDestroy() }
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
    private lateinit var t: TextView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Cfg.init(this)
        val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; l.setPadding(48, 48, 48, 48)
        t = TextView(this); t.textSize = 20f; l.addView(t)
        fun btn(s: String, f: () -> Unit) { val x = Button(this); x.text = s; x.setOnClickListener { f() }; l.addView(x) }
        btn("Khởi động lại dịch vụ") { BridgeService.start(this) }
        btn("Bỏ tối ưu pin (nên bấm)") {
            if (Build.VERSION.SDK_INT >= 23) try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { t.append("\nBox không có mục này") }
        }
        setContentView(l); BridgeService.start(this)
    }
    override fun onResume() { super.onResume(); t.text = "TVRemote đang chạy nền.\nMở trên điện thoại (cùng Wi-Fi):\nhttp://${myIp()}:8765" }
}
