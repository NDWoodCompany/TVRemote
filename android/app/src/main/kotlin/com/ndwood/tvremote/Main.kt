package com.ndwood.tvremote

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import android.view.Gravity
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object Cfg {
    lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("cfg", 0) }
    private val def = mapOf("sonyIp" to "", "psk" to "", "tvMac" to "", "boxIp" to "", "boxMac" to "",
        "adbPort" to "5555", "searchApp" to "com.teamsmart.videomanager.tv", "hdmi" to "hdmi1")
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
    val codes = mutableMapOf("power" to ir(21),
        "volUp" to ir(18), "volDown" to ir(19), "mute" to ir(20),
        "hdmi1" to "AAAAAgAAABoAAABaAw==", "hdmi2" to "AAAAAgAAABoAAABbAw==",
        "hdmi3" to "AAAAAgAAABoAAABcAw==", "hdmi4" to "AAAAAgAAABoAAABdAw==").also { for (i in 0..9) it["num$i"] = ir((i + 9) % 10) }
    const val PS = """{"method":"getPowerStatus","id":1,"params":[],"version":"1.0"}"""

    fun http(path: String, body: String, h: Map<String, String>, timeoutMs: Int = 4000): String {
        if (Cfg["sonyIp"].isEmpty()) Disc.ensure()
        if (Cfg["sonyIp"].isEmpty()) throw Exception("Chưa tìm thấy TV – bấm \"Tự tìm TV\" hoặc nhập IP trong Cài đặt")
        val c = URL("http://${Cfg["sonyIp"]}$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = "POST"; c.doOutput = true
            if (Cfg["psk"].isNotEmpty()) c.setRequestProperty("X-Auth-PSK", Cfg["psk"])
            c.setRequestProperty("Connection", "close")
            h.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code >= 400) throw Exception("TV trả HTTP $code" + if (code == 403) " – sai/thiếu PSK hoặc chưa bật IP Control" else "")
            return txt
        } finally { c.disconnect() }
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
    private fun findStatus(v: Any?): String {
        return when (v) {
            is JSONObject -> {
                val direct = v.optString("status", "")
                if (direct.equals("active", true) || direct.equals("standby", true)) direct.lowercase()
                else v.keys().asSequence().mapNotNull { findStatus(v.opt(it)).takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()
            }
            is org.json.JSONArray -> (0 until v.length()).asSequence().mapNotNull { findStatus(v.opt(it)).takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()
            is String -> if (v.equals("active", true) || v.equals("standby", true)) v.lowercase() else ""
            else -> ""
        }
    }
    private fun jsonRequest(method: String, params: String = "[]", timeoutMs: Int = 2500): JSONObject {
        val body = """{"method":"$method","id":1,"params":$params,"version":"1.0"}"""
        val raw = http("/sony/system", body, mapOf("Content-Type" to "application/json; charset=UTF-8"), timeoutMs)
        val result = JSONObject(raw)
        val error = result.optJSONArray("error")
        if (error != null && error.length() > 0) {
            val item = error.opt(1)?.toString() ?: error.toString()
            throw Exception("$method bị TV từ chối: $item")
        }
        return result
    }
    fun status(): String = try { findStatus(jsonRequest("getPowerStatus").opt("result")) } catch (e: Exception) { "" }
    fun diag(): String = try {
        val st = status().ifEmpty { "không đọc được" }
        val keys = try {
            val raw = jsonRequest("getRemoteControllerInfo").opt("result")
            listOf("Power", "PowerOff", "PowerOn", "WakeUp").mapNotNull { key ->
                findRemoteCode(raw, setOf(key))?.let { "$key=$it" }
            }.joinToString("; ").ifEmpty { "không công bố mã nguồn riêng" }
        } catch (e: Exception) { "API remote info không hỗ trợ: ${e.message}" }
        "TV ${Cfg["sonyIp"]}: trạng thái=$st\nIRCC/REST: kết nối được\nMã nguồn TV: $keys"
    } catch (e: Exception) { "Không kết nối được (${e.message})" }
    fun wol(): Boolean {
        val m = Cfg["tvMac"].replace(Regex("[^0-9a-fA-F]"), "")
        if (m.length != 12) return false
        val mac = ByteArray(6) { m.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val packet = ByteArray(102)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
        val broadcast = Cfg["sonyIp"].substringBeforeLast('.', "").let { if (it.isNotEmpty()) "$it.255" else "255.255.255.255" }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (address in listOf("255.255.255.255", broadcast)) {
                for (port in listOf(9, 7)) {
                    try { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(address), port)) } catch (_: Exception) {}
                }
            }
        }
        return true
    }
    private fun setPowerStatus(on: Boolean) {
        jsonRequest("setPowerStatus", """[{"status":$on}]""", 4500)
    }
    private fun findRemoteCode(v: Any?, wanted: Set<String>): String? {
        return when (v) {
            is JSONObject -> {
                val name = v.optString("name", "")
                val code = v.optString("value", "")
                if (wanted.any { it.equals(name, true) } && code.isNotBlank()) code
                else v.keys().asSequence().mapNotNull { findRemoteCode(v.opt(it), wanted) }.firstOrNull()
            }
            is org.json.JSONArray -> (0 until v.length()).asSequence().mapNotNull { findRemoteCode(v.opt(it), wanted) }.firstOrNull()
            else -> null
        }
    }
    private fun modelPowerCode(names: Set<String>): String? = try {
        findRemoteCode(jsonRequest("getRemoteControllerInfo").opt("result"), names)
    } catch (e: Exception) { null }
    private fun sendPowerOff() {
        try {
            setPowerStatus(false)
            return
        } catch (restError: Exception) {
            val discrete = modelPowerCode(setOf("PowerOff", "Power off", "PowerOffKey"))
            if (discrete != null) {
                ircc(discrete)
                return
            }
            if (status() == "active") {
                ircc(codes["power"]!!)
                return
            }
            throw Exception("Không tắt được TV bằng REST API hoặc IRCC. ${restError.message}")
        }
    }
    private fun sendPowerOn() {
        try {
            setPowerStatus(true)
            return
        } catch (restError: Exception) {
            val discrete = modelPowerCode(setOf("PowerOn", "Power on", "WakeUp"))
            if (discrete != null) {
                ircc(discrete)
                return
            }
            if (status() == "standby") {
                ircc(codes["power"]!!)
                return
            }
            throw Exception("Không bật được TV bằng REST API hoặc IRCC. ${restError.message}")
        }
    }
    // Nút nguồn là toggle có kiểm tra trạng thái; dùng REST API nguồn trước, IRCC làm dự phòng.
    fun power() {
        when (status()) {
            "active" -> sendPowerOff()
            "standby" -> { wol(); sendPowerOn() }
            else -> {
                // Khi API trạng thái không trả lời, gửi IRCC Power đúng mã Sony chuẩn.
                ircc(codes["power"]!!)
            }
        }
    }
    fun on(assumeOff: Boolean = false) {
        if (status() == "active") return
        wol()
        try { sendPowerOn() } catch (e: Exception) {
            if (assumeOff) ircc(codes["power"]!!) else throw e
        }
    }
    fun off(assumeOn: Boolean = false) {
        if (status() == "standby") return
        try { sendPowerOff() } catch (e: Exception) {
            if (assumeOn) ircc(codes["power"]!!) else throw e
        }
    }
    fun selectConfiguredHdmi() { ircc(codes[Cfg["hdmi"]] ?: codes["hdmi1"]!!) }
    fun toggleHdmi(): String {
        val next = if (Cfg["hdmi"] == "hdmi1") "hdmi2" else "hdmi1"
        ircc(codes[next]!!)
        Cfg["hdmi"] = next
        return "Đã chuyển sang ${next.uppercase()}"
    }
    fun waitActive() {
        for (i in 0 until 6) {
            if (status() == "active") { Thread.sleep(500); return }
            Thread.sleep(700)
        }
        // Không chặn thao tác chỉ vì firmware Sony không cung cấp trạng thái nguồn.
        Thread.sleep(1200)
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
    @Synchronized fun toggle(c: Context): String {
        if (busy) throw Exception("Đang xử lý lệnh trước đó")
        busy = true
        return try {
            val awake = try { Box.isAwake() } catch (e: Exception) {
                when (Cfg["sceneState"]) {
                    "awake" -> true
                    "sleep" -> false
                    else -> throw e
                }
            }
            if (awake) {
                // Khi Box đang thức, coi TV là màn hình đang sử dụng: TV ngủ trước, Box ngủ sau.
                Tv.off(assumeOn = true)
                Thread.sleep(700)
                Box.sleep()
                Cfg["sceneState"] = "sleep"
                "Đã tắt TV trước, sau đó đưa TV Box vào chế độ ngủ"
            } else {
                // Đánh thức Box trước; chỉ bật TV sau khi ADB phản hồi trở lại.
                Box.wake()
                Tv.on(assumeOff = true)
                Tv.waitActive()
                Tv.selectConfiguredHdmi()
                Cfg["sceneState"] = "awake"
                "Đã đánh thức TV Box, bật TV và chuyển sang ${Cfg["hdmi"].uppercase()}"
            }
        } finally { busy = false }
    }
}

// ---------- ADB qua Wi-Fi từ điện thoại tới Android TV Box ----------
object Adb {
    private var d: Dadb? = null
    lateinit var dir: File
    fun reset() { try { d?.close() } catch (e: Exception) {}; d = null }
    private fun conn(): Dadb {
        val existing = d
        if (existing != null) return existing
        val host = Cfg["boxIp"].trim()
        if (host.isEmpty()) throw Exception("Chưa nhập IP Android TV Box trong Cài đặt")
        val port = Cfg["adbPort"].toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw Exception("Cổng ADB không hợp lệ")
        val pri = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!pri.exists()) AdbKeyPair.generate(pri, pub)
        val x = Dadb.create(host, port, AdbKeyPair.read(pri, pub)); d = x; return x
    }
    @Synchronized fun sh(cmd: String): String {
        try { return conn().shell(cmd).allOutput.trim() } catch (e: Exception) { reset() }
        try { return conn().shell(cmd).allOutput.trim() } catch (e: Exception) {
            throw Exception("ADB không kết nối được tới ${Cfg["boxIp"]}:${Cfg["adbPort"]}. Kiểm tra Box và điện thoại cùng Wi-Fi; bật ADB qua mạng/TCP 5555 trên Box; chấp nhận khóa RSA và chọn Luôn cho phép. Chi tiết: ${e.message}")
        }
    }
}

object Box {
    val KEY = mutableMapOf("UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23, "BACK" to 4, "EXIT" to 111, "HOME" to 3,
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
    fun isAwake(): Boolean {
        val p = Adb.sh("dumpsys power")
        val m = Regex("(?i)(?:mWakefulness|Wakefulness)\\s*[=:]\\s*(Awake|Asleep|Dozing|Dreaming)").find(p)
        val state = m?.groupValues?.get(1)?.lowercase()
        return when (state) {
            "awake", "dreaming" -> true
            "asleep", "dozing" -> false
            else -> when (Cfg["sceneState"]) {
                "awake" -> true
                "sleep" -> false
                else -> throw Exception("Không xác định được Box đang thức hay ngủ. Chi tiết trạng thái: ${p.take(700)}")
            }
        }
    }
    private fun wakeOnLan() {
        val raw = Cfg["boxMac"].trim()
        if (raw.isEmpty()) return
        val hex = raw.replace(":", "").replace("-", "")
        if (!hex.matches(Regex("(?i)[0-9a-f]{12}"))) throw Exception("MAC TV Box không hợp lệ; dùng dạng AA:BB:CC:DD:EE:FF")
        val mac = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val packet = ByteArray(6 + 16 * mac.size)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * mac.size, mac.size)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val target = InetAddress.getByName("255.255.255.255")
            for (port in listOf(9, 7)) socket.send(DatagramPacket(packet, packet.size, target, port))
        }
    }
    fun wake() {
        wakeOnLan()
        try { Adb.sh("input keyevent 224") } catch (first: Exception) {
            Adb.reset()
            var last: Exception = first
            for (i in 1..5) {
                try { Thread.sleep(350L * i); Adb.sh("input keyevent 224"); last = first; break }
                catch (e: Exception) { last = e }
            }
            if (last !== first) throw last
        }
        // Chờ Box thật sự chuyển sang trạng thái Awake, không chỉ chờ ADB trả lời.
        for (i in 0 until 12) {
            try { if (isAwake()) return } catch (e: Exception) {}
            Thread.sleep(500)
        }
        throw Exception("Đã gửi lệnh đánh thức nhưng Box chưa báo trạng thái Awake. Có thể firmware tắt Wi-Fi/ADB khi ngủ hoặc không hỗ trợ KEYCODE_WAKEUP.")
    }
    fun sleep() { Adb.sh("input keyevent 223") }
    fun key(key: String) { val code = KEY[key] ?: throw Exception("Phím không hỗ trợ: $key"); Adb.sh("input keyevent $code") }
}

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var tabArea: LinearLayout
    private var selectedTab = 0
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val bg = 0xFF101820.toInt(); private val panel = 0xFF1D2A35.toInt(); private val fg = 0xFFF1F5F8.toInt()
    override fun onCreate(b: Bundle?) { super.onCreate(b); Cfg.init(this); Adb.dir = filesDir; buildUi() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun label(t: String, size: Float = 15f) = TextView(this).apply { text=t; textSize=size; setTextColor(fg); gravity=Gravity.CENTER; setPadding(dp(6),dp(8),dp(6),dp(8)) }
    private fun button(t: String, action: () -> Unit): Button = Button(this).apply { text=t; isAllCaps=false; textSize=15f; setTextColor(fg); setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel)); setOnClickListener { action() } }
    private fun row(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) { val r=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; weightSum=items.size.toFloat() }; items.forEach { (t,a) -> r.addView(button(t,a), LinearLayout.LayoutParams(0,dp(58),1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }; parent.addView(r) }
    private fun action(name: String, block: () -> Any?) {
        status.text = "Đang thực hiện: $name…"
        commandExecutor.execute {
            val result = try {
                val value = block()
                if (value == null || value === Unit || value.toString().isBlank()) "Đã gửi: $name" else value.toString()
            } catch (e: Exception) { "Lỗi $name: ${e.message}" }
            runOnUiThread { if (!isFinishing) status.text = result }
        }
    }
    private fun buildUi() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(12),dp(8),dp(12),dp(8)) }
        val head=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        head.addView(label("TVRemote",22f),LinearLayout.LayoutParams(0,dp(52),1f)); head.addView(button("⟳ Tìm thiết bị") { discover() },LinearLayout.LayoutParams(-2,dp(48)))
        root.addView(head); status=label("Sẵn sàng điều khiển",12f).apply { gravity=Gravity.START; setBackgroundColor(panel) }; root.addView(status)
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }; listOf("ĐIỀU KHIỂN","BÀN PHÍM","CÀI ĐẶT").forEachIndexed { i,t -> tabs.addView(button(t) { selectedTab=i; renderTab() },LinearLayout.LayoutParams(0,dp(48),1f)) }; root.addView(tabs)
        tabArea=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(8),0,dp(12)) }
        val scroll=ScrollView(this).apply { isFillViewport=true; addView(tabArea) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root); renderTab(); refreshStatus()
    }
    private fun renderTab() { tabArea.removeAllViews(); when(selectedTab) {
        0 -> {
            row(tabArea,"⏻ NGUỒN / SCENE" to { action("Nguồn TV + Box") { Scene.toggle(this) } })
            row(tabArea,"TV NGUỒN" to { action("TV Power") { Tv.power() } },"TV TẮT" to { action("TV Tắt") { Tv.off() } },"TV BẬT" to { action("TV Bật") { Tv.on() } })
            row(tabArea,"HDMI 1 ⇄ 2" to { action("Chuyển HDMI") { Tv.toggleHdmi() } })
            row(tabArea,"TV VOL +" to { action("TV Vol+") { Tv.ircc(Tv.codes["volUp"]!!) } },"TV VOL −" to { action("TV Vol-") { Tv.ircc(Tv.codes["volDown"]!!) } },"TV MUTE" to { action("TV Mute") { Tv.ircc(Tv.codes["mute"]!!) } })
            row(tabArea,"BOX VOL +" to { action("Box Vol+") { Box.key("VOL+") } },"BOX VOL −" to { action("Box Vol-") { Box.key("VOL-") } },"BOX MUTE" to { action("Box Mute") { Box.key("MUTE") } })
            row(tabArea,"BACK" to { action("Back") { Box.key("BACK") } },"HOME" to { action("Home") { Box.key("HOME") } },"EXIT" to { action("Exit") { Box.key("EXIT") } })
            val pad=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
            row(pad,"▲" to { action("Up") { Box.key("UP") } })
            row(pad,"◀" to { action("Left") { Box.key("LEFT") } },"OK" to { action("OK") { Box.key("OK") } },"▶" to { action("Right") { Box.key("RIGHT") } })
            row(pad,"▼" to { action("Down") { Box.key("DOWN") } })
            tabArea.addView(pad)
            row(tabArea,"MỞ YOUTUBE" to { action("Mở YouTube") { Box.open() } },"🎙 GIỌNG NÓI" to { voice() })
        }
        1 -> { row(tabArea,"1" to { sendKey("1") },"2" to { sendKey("2") },"3" to { sendKey("3") }); row(tabArea,"4" to { sendKey("4") },"5" to { sendKey("5") },"6" to { sendKey("6") }); row(tabArea,"7" to { sendKey("7") },"8" to { sendKey("8") },"9" to { sendKey("9") }); row(tabArea,"*" to { sendKey("*") },"0" to { sendKey("0") },"#" to { sendKey("#") }); row(tabArea,"CH +" to { action("Channel+") { Box.key("UP") } },"CH −" to { action("Channel-") { Box.key("DOWN") } }) }
        else -> settingsUi()
    } }
    private fun sendKey(k:String) = action("Phím $k") { Box.key(k) }
    private fun discover() = action("Tìm thiết bị") { val r=Disc.ensure(true); "Quét xong: $r · TV ${Cfg["sonyIp"]}".also { runOnUiThread { refreshStatus() } } }
    private fun checkConnections() = action("Kiểm tra TV + Box") {
        val tv = Tv.diag()
        val box = try { "OK · " + Adb.sh("getprop ro.product.model").ifBlank { "ADB đã kết nối" } }
                   catch (e: Exception) { "LỖI · ${e.message}" }
        "TV: $tv\nBox: $box"
    }
    private fun refreshStatus() { status.text="TV: ${Cfg["sonyIp"].ifEmpty { "chưa tìm thấy" }}   ·   Box: ${Cfg["boxIp"]}:${Cfg["adbPort"]}\nĐiều khiển trực tiếp · không chạy nền" }
    private fun settingsUi() {
        fun field(title:String,key:String,hint:String=""): EditText { tabArea.addView(label(title).apply { gravity=Gravity.START }); val e=EditText(this).apply { setText(Cfg[key]); setSingleLine(true); setTextColor(fg); setHintTextColor(0xFF9AAAB5.toInt()); setHint(hint); setPadding(dp(12),0,dp(12),0); setBackgroundColor(panel) }; tabArea.addView(e,LinearLayout.LayoutParams(-1,dp(48))); return e }
        val tv=field("IP TV Sony","sonyIp","Ví dụ 192.168.1.234")
        val box=field("IP Android TV Box","boxIp","Ví dụ 192.168.1.233")
        val psk=field("Sony PSK (nếu đã bật IP Control)","psk","Để trống nếu không dùng")
        val mac=field("MAC TV (tùy chọn)","tvMac","AA:BB:CC:DD:EE:FF")
        val boxMac=field("MAC TV Box (Wake-on-LAN, nếu hỗ trợ)","boxMac","AA:BB:CC:DD:EE:FF")
        val search=field("Gói ứng dụng tìm kiếm trên Box","searchApp","com.teamsmart.videomanager.tv")
        row(tabArea,"LƯU CẤU HÌNH" to {
            Cfg["sonyIp"]=tv.text.toString().trim()
            Cfg["boxIp"]=box.text.toString().trim()
            Cfg["psk"]=psk.text.toString().trim()
            Cfg["tvMac"]=mac.text.toString().trim()
            Cfg["boxMac"]=boxMac.text.toString().trim()
            Cfg["searchApp"]=search.text.toString().trim()
            Adb.reset(); refreshStatus(); status.text="Đã lưu cấu hình thiết bị"
        })
        row(tabArea,"TEST TV + BOX" to { checkConnections() },"Kiểm tra ADB" to { action("ADB") { "ADB OK · "+Adb.sh("getprop ro.product.model") } })
        row(tabArea,"KIỂM TRA / CẬP NHẬT PHIÊN BẢN" to { updateApp() })
    }
    private fun updateApp() {
        val url = "https://github.com/NDWoodCompany/TVRemote/releases/download/apk-latest/TVRemote.apk"
        AlertDialog.Builder(this).setTitle("Cập nhật TVRemote")
            .setMessage("Tải phiên bản TVRemote.apk mới nhất từ GitHub Releases?")
            .setNegativeButton("Để sau", null)
            .setPositiveButton("Cập nhật") { _, _ ->
                status.text = "Đang tải TVRemote.apk từ GitHub..."
                Thread {
                    var conn: HttpURLConnection? = null
                    try {
                        val file = File(cacheDir, "TVRemote-update.apk")
                        conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 20000
                            readTimeout = 30000
                            requestMethod = "GET"
                            setRequestProperty("Cache-Control", "no-cache")
                        }
                        conn.connect()
                        if (conn.responseCode != HttpURLConnection.HTTP_OK)
                            throw Exception("GitHub trả HTTP ${conn.responseCode}")
                        val length = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    total += n
                                    if (length > 0) {
                                        val percent = (total * 100 / length).toInt()
                                        runOnUiThread { status.text = "Đang tải cập nhật: $percent%" }
                                    }
                                }
                            }
                        }
                        if (file.length() < 100_000) throw Exception("File tải về quá nhỏ, có thể GitHub chưa có APK")
                        file.inputStream().use { input ->
                            val magic = ByteArray(2)
                            if (input.read(magic) != 2 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte())
                                throw Exception("File tải về không phải APK hợp lệ")
                        }
                        runOnUiThread {
                            try {
                                if (Build.VERSION.SDK_INT >= 26 &&
                                    !packageManager.canRequestPackageInstalls()) {
                                    status.text = "Cần cho phép TVRemote cài ứng dụng không rõ nguồn"
                                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:$packageName")))
                                    Toast.makeText(this, "Cho phép cài đặt rồi quay lại bấm cập nhật lần nữa", Toast.LENGTH_LONG).show()
                                    return@runOnUiThread
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    this, "$packageName.provider", file)
                                val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                    data = uri
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(install)
                                status.text = "Đã tải APK; xác nhận cài đặt trong Android"
                            } catch (e: Exception) {
                                status.text = "Không mở được trình cài đặt: ${e.message}"
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Cập nhật thất bại: ${e.message}" }
                    } finally {
                        conn?.disconnect()
                    }
                }.start()
            }.show()
    }
    private fun voice() { try { val i=Intent("android.speech.action.RECOGNIZE_SPEECH").putExtra("android.speech.extra.LANGUAGE","vi-VN").putExtra("android.speech.extra.LANGUAGE_MODEL","free_form").putExtra("android.speech.extra.PROMPT","Nói nội dung cần tìm trên YouTube"); startActivityForResult(i,41) } catch(e:Exception) { status.text="Thiết bị chưa có dịch vụ nhận dạng giọng nói tiếng Việt" } }
    override fun onActivityResult(req:Int,res:Int,data:Intent?) { super.onActivityResult(req,res,data); if(req==41 && res==RESULT_OK) { val q=data?.getStringArrayListExtra("android.speech.extra.RESULTS")?.firstOrNull().orEmpty(); if(q.isNotBlank()) action("Tìm kiếm: $q") { Box.search(q) } } }
    override fun onResume() { super.onResume(); if(::status.isInitialized) refreshStatus() }
    override fun onBackPressed() { finish() }
    override fun onDestroy() { commandExecutor.shutdownNow(); Adb.reset(); super.onDestroy() }
}
