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

object Cfg {
    lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("cfg", 0) }
    private val def = mapOf("sonyIp" to "192.168.1.234", "psk" to "", "tvMac" to "", "boxIp" to "127.0.0.1",
        "adbPort" to "5555", "searchApp" to "com.teamsmart.videomanager.tv", "follow" to "1")
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

    private fun http(path: String, body: String, h: Map<String, String>): String {
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
    fun ircc(code: String) {
        val b = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>$code</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>"""
        http("/sony/IRCC", b, mapOf("Content-Type" to "text/xml; charset=UTF-8", "SOAPAction" to "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\""))
    }
    private const val PS = """{"method":"getPowerStatus","id":1,"params":[],"version":"1.0"}"""
    private fun status(): String = try {
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
            Adb.reset(); ok()
        }
        "/api/sony" -> { val c = b.optString("cmd"); if (c == "power") Tv.power() else Tv.ircc(Tv.codes[c] ?: throw Exception("Lệnh TV không hỗ trợ")); ok() }
        "/api/box" -> { Adb.sh("input keyevent " + (Box.KEY[b.optString("key")] ?: throw Exception("Phím không hỗ trợ"))); ok() }
        "/api/box/text" -> { val t = b.optString("text"); if (t.isEmpty() || t.length > 200) throw Exception("Nội dung không hợp lệ"); Box.text(t, b.optBoolean("enter")); ok() }
        "/api/box/search" -> { val q = b.optString("q").trim(); if (q.isEmpty()) throw Exception("Thiếu nội dung"); Box.search(q.take(200)); ok() }
        "/api/box/open" -> { Box.open(); ok() }
        "/api/status" -> js(JSONObject().put("ok", true).put("sony", Tv.diag())
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
                    val now = System.currentTimeMillis(); if (Cfg["follow"] != "1" || now - last < 3000) return; last = now
                    val on = it.action == Intent.ACTION_SCREEN_ON
                    Thread { try { if (on) Tv.on() else Tv.off() } catch (e: Exception) {} }.start()
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
            if (i?.getBooleanExtra("boot", false) == true) Thread {
                if (Cfg["follow"] == "1") Tv.on()
                // Nếu Box có root: bật ADB TCP sau mỗi lần khởi động (không root thì bỏ qua)
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop service.adb.tcp.port 5555;stop adbd;start adbd")).waitFor() } catch (e: Exception) {}
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
