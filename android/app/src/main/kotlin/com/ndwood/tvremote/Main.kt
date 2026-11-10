package com.ndwood.tvremote

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import android.view.*
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Cfg {
    lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.getSharedPreferences("cfg", 0) }
    private val def = mapOf("sonyIp" to "", "psk" to "", "tvMac" to "", "boxIp" to "", "boxMac" to "",
        "adbPort" to "5555", "searchApp" to "com.teamsmart.videomanager.tv", "hdmi" to "hdmi1", "tv360App" to "")
    val keys: Set<String> get() = def.keys
    operator fun get(k: String): String = sp.getString(k, def[k] ?: "") ?: ""
    operator fun set(k: String, v: String) { sp.edit().putString(k, v).apply() }
}

fun myIp(): String = try {
    val ifs = NetworkInterface.getNetworkInterfaces().toList()
    (ifs.filter { it.name.startsWith("wlan") || it.name.startsWith("eth") } + ifs).flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "?"
} catch (e: Exception) { "?" }

// ---------- Sony BRAVIA (IRCC + Wake-on-LAN) ----------
object Tv {
    private fun ir(id: Int) = "AAAAAQAAAAEAAAA" + (65 + id).toChar() + "Aw=="
    val codes = mutableMapOf("power" to ir(21), "tv" to ir(36), "input" to ir(37),
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
        try {
            send(code)
        } catch (first: Exception) {
            // Nút bấm không được kích hoạt quét cả mạng (có thể mất 15-30 giây).
            // Thử xác nhận IP hiện tại và gửi lại đúng một lần.
            if (Cfg["sonyIp"].isBlank()) throw first
            if (!Disc.isSony(Cfg["sonyIp"])) throw Exception("Không liên lạc được TV tại ${Cfg["sonyIp"]}; không tự quét toàn mạng. ${first.message}")
            try { send(code) } catch (second: Exception) {
                throw Exception("TV không nhận lệnh IRCC tại ${Cfg["sonyIp"]}. ${second.message}")
            }
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
    // Tìm Android TV Box: máy nào trong /24 mở cổng ADB 5555
    fun findBox(): List<String> {
        val base = myIp().substringBeforeLast('.'); if (base.count { it == '.' } != 2) return emptyList()
        val ex = Executors.newFixedThreadPool(64)
        val fs = (1..254).map { i -> ex.submit(Callable { val ip = "$base.$i"; if (Adb.portOpen(ip, 5555, 500)) ip else null }) }
        val res = fs.mapNotNull { it.get() }; ex.shutdown(); return res
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
                val cand = if (a.isNotEmpty()) a.keys.toList() else { val b = myIp().substringBeforeLast('.'); (1..254).map { "$b.$it" } }
                val ex = Executors.newFixedThreadPool(64)
                val fs = cand.map { k -> ex.submit(Callable { isSony(k) }) }
                val found = cand.filterIndexed { i, _ -> fs[i].get() }
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
                // Không cho Box ngủ nếu TV chưa nhận lệnh tắt.
                Tv.off(assumeOn = true)
                Thread.sleep(500)
                Box.sleep()
                Cfg["sceneState"] = "sleep"
                "Đã gửi lệnh tắt TV, sau đó gửi lệnh ngủ TV Box"
            } else {
                // WoL/ADB đánh thức Box trước; sau đó bật TV và chọn HDMI.
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
// Mọi lệnh đều có timeout; kết nối treo/chết ngầm bị đóng và dựng lại thay vì làm đơ cả app.
object Adb {
    private var d: Dadb? = null
    lateinit var dir: File
    private val lock = Any()
    private var lastUse = 0L
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    fun reset() { val x = d; d = null; try { x?.close() } catch (e: Exception) {} }
    private fun target(): Pair<String, Int> {
        val host = Cfg["boxIp"].trim()
        if (host.isEmpty()) throw Exception("Chưa nhập IP Android TV Box trong Cài đặt")
        val port = Cfg["adbPort"].toIntOrNull()?.takeIf { it in 1..65535 } ?: throw Exception("Cổng ADB không hợp lệ")
        return Pair(host, port)
    }
    fun portOpen(host: String, port: Int, ms: Int = 1500): Boolean =
        try { Socket().use { it.connect(InetSocketAddress(host, port), ms); true } } catch (e: Exception) { false }
    private fun conn(): Dadb {
        d?.let { return it }
        val (host, port) = target()
        val pri = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!pri.exists()) AdbKeyPair.generate(pri, pub)
        val x = Dadb.create(host, port, AdbKeyPair.read(pri, pub)); d = x; return x
    }
    private fun run(cmd: String, ms: Long): String {
        val f = pool.submit(Callable { conn().shell(cmd).allOutput.trim() })
        try { return f.get(ms, TimeUnit.MILLISECONDS) }
        catch (e: TimeoutException) { f.cancel(true); reset(); throw Exception("ADB không phản hồi sau ${ms / 1000}s (kết nối treo, đã đặt lại)") }
        catch (e: ExecutionException) { reset(); throw Exception(e.cause?.message ?: e.message ?: "lỗi ADB") }
    }
    fun sh(cmd: String, ms: Long = 6000): String = synchronized(lock) {
        val (host, port) = target()
        // Kết nối nằm im lâu có thể đã chết ngầm (Wi-Fi tiết kiệm pin, Box ngủ): kiểm tra nhanh trước khi dùng.
        if (d != null && System.currentTimeMillis() - lastUse > 15000) { try { run("echo 1", 1500) } catch (e: Exception) { reset() } }
        var last = ""
        for (attempt in 1..2) {
            if (d == null && !portOpen(host, port)) { last = "Box không mở cổng ADB $port tại $host"; Thread.sleep(300); continue }
            try { val out = run(cmd, ms); lastUse = System.currentTimeMillis(); return@synchronized out }
            catch (e: Exception) { last = e.message ?: "lỗi ADB" }
        }
        throw Exception("$last. Kiểm tra: Box đang bật, cùng Wi-Fi với điện thoại, ADB qua mạng (cổng $port) còn bật, IP Box đúng; chấp nhận khóa RSA (Luôn cho phép).")
    }
}

object Box {
    val KEY = mutableMapOf("UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23, "BACK" to 4, "EXIT" to 111, "HOME" to 3,
        "POWER" to 26, "MENU" to 82, "VOL+" to 24, "VOL-" to 25, "MUTE" to 164, "*" to 17, "#" to 18).also { for (i in 0..9) it["$i"] = 7 + i }
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
    fun launch(pkg: String) {
        if (!pkg.matches(Regex("[\\w.]+"))) throw Exception("Tên gói không hợp lệ: $pkg")
        fun a(c: String) = Adb.sh("am start -a android.intent.action.MAIN -c android.intent.category.$c -p $pkg")
        var o = a("LEANBACK_LAUNCHER"); if (o.contains("Error")) o = a("LAUNCHER")
        if (o.contains("Error")) { o = Adb.sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1"); if (o.contains("No activities found")) throw Exception("Không mở được $pkg") }
    }
    fun open() = launch(Cfg["searchApp"].ifEmpty { "com.google.android.youtube.tv" })
    // TV360: dùng gói đã nhập, nếu để trống thì tự dò gói có chứa "tv360" trên Box
    fun openTv360() {
        val cfg = Cfg["tv360App"]
        val pkgs = (if (cfg.isNotBlank()) cfg.split(",") else Adb.sh("pm list packages").lines().map { it.removePrefix("package:") })
            .map { it.trim() }.filter { it.matches(Regex("[\\w.]+")) && (cfg.isNotBlank() || it.contains("tv360", true)) }
        if (pkgs.isEmpty()) throw Exception("Box chưa cài TV360 (không thấy gói nào chứa tv360). Nhập tên gói trong Cài đặt nếu khác.")
        var err: Exception? = null
        for (p in pkgs) { try { launch(p); return } catch (e: Exception) { err = e } }
        throw err ?: Exception("Không mở được TV360")
    }
    fun isAwake(): Boolean {
        var p = Adb.sh("dumpsys power | grep -i wakefulness", 9000); if (!p.contains("akefulness", true)) p = Adb.sh("dumpsys power", 9000)
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
    // Phím gửi qua hàng đợi: nhiều phím bấm dồn lại được gom vào MỘT lệnh "input keyevent a b c" (input trên Box rất chậm)
    @Volatile var onError: ((String) -> Unit)? = null
    private val q = LinkedBlockingQueue<Int>()
    fun pending() = q.size
    private val worker = Thread {
        while (true) {
            try {
                val batch = ArrayList<Int>(); batch.add(q.take()); q.drainTo(batch, 7)
                Adb.sh("input keyevent " + batch.joinToString(" "), 5000)
            } catch (e: InterruptedException) { break }
            catch (e: Exception) { q.clear(); onError?.invoke(e.message ?: "Lỗi ADB") }
        }
    }.apply { isDaemon = true; start() }
    fun key(key: String) { val code = KEY[key] ?: throw Exception("Phím không hỗ trợ: $key"); q.put(code) }
}

// ---------- Ép mọi kết nối LAN đi qua Wi-Fi (tránh Samsung/Android tự chuyển sang 4G khi Wi-Fi "không có Internet") ----------
fun bindWifi(c: Context) {
    if (Build.VERSION.SDK_INT < 23) return
    try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val cap = cm.getNetworkCapabilities(n)
            if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { cm.bindProcessToNetwork(n); return }
        }
    } catch (e: Exception) {}
}

// ---------- Biểu tượng vẽ bằng Canvas ----------
object Icons {
    private val path = Path()
    private val rf = RectF()
    fun draw(c: Canvas, p: Paint, name: String, x: Float, y: Float, s: Float, col: Int) {
        p.color = col; p.style = Paint.Style.STROKE; p.strokeWidth = s * .26f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fun ln(a: Float, b: Float, cc: Float, d: Float) = c.drawLine(x + a * s, y + b * s, x + cc * s, y + d * s, p)
        fun poly(fill: Boolean, close: Boolean, vararg v: Float) {
            path.reset(); path.moveTo(x + v[0] * s, y + v[1] * s)
            var i = 2; while (i < v.size) { path.lineTo(x + v[i] * s, y + v[i + 1] * s); i += 2 }
            if (close) path.close(); p.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE; c.drawPath(path, p); p.style = Paint.Style.STROKE
        }
        when (name) {
            "power" -> { rf.set(x - s * .8f, y - s * .7f, x + s * .8f, y + s * .9f); c.drawArc(rf, 310f, 280f, false, p); ln(0f, -1f, 0f, -.1f) }
            "tv" -> { rf.set(x - s, y - s * .7f, x + s, y + s * .5f); c.drawRoundRect(rf, s * .18f, s * .18f, p); ln(-.4f, .9f, .4f, .9f) }
            "hdmi" -> { poly(false, false, .2f, -.65f, .8f, -.65f, .8f, .65f, .2f, .65f); ln(-1f, 0f, .3f, 0f); poly(false, false, -.3f, -.4f, .3f, 0f, -.3f, .4f) }
            "mic" -> { p.style = Paint.Style.FILL; rf.set(x - s * .33f, y - s, x + s * .33f, y + s * .3f); c.drawRoundRect(rf, s * .33f, s * .33f, p); p.style = Paint.Style.STROKE
                rf.set(x - s * .7f, y - s * .55f, x + s * .7f, y + s * .75f); c.drawArc(rf, 0f, 180f, false, p); ln(0f, .75f, 0f, 1f); ln(-.4f, 1f, .4f, 1f) }
            "kbd" -> { rf.set(x - s, y - s * .6f, x + s, y + s * .6f); c.drawRoundRect(rf, s * .18f, s * .18f, p)
                p.style = Paint.Style.FILL; for (i in -2..2) c.drawCircle(x + i * s * .38f, y - s * .2f, s * .09f, p); p.style = Paint.Style.STROKE; ln(-.5f, .25f, .5f, .25f) }
            "mute" -> { poly(true, true, -1f, -.35f, -.45f, -.35f, .1f, -.8f, .1f, .8f, -.45f, .35f, -1f, .35f); ln(.45f, -.35f, .95f, .35f); ln(.95f, -.35f, .45f, .35f) }
            "back" -> { ln(1f, 0f, -1f, 0f); poly(false, false, -.1f, -.7f, -1f, 0f, -.1f, .7f) }
            "home" -> { poly(false, false, -.95f, 0f, 0f, -.85f, .95f, 0f); poly(false, false, -.7f, -.15f, -.7f, .8f, .7f, .8f, .7f, -.15f) }
            "exit" -> { ln(-.7f, -.7f, .7f, .7f); ln(.7f, -.7f, -.7f, .7f) }
            "menu" -> { ln(-.8f, -.6f, .8f, -.6f); ln(-.8f, 0f, .8f, 0f); ln(-.8f, .6f, .8f, .6f) }
            "plus" -> { ln(-1f, 0f, 1f, 0f); ln(0f, -1f, 0f, 1f) }
            "minus" -> ln(-1f, 0f, 1f, 0f)
            "tri" -> poly(false, true, 0f, -.8f, 1f, .7f, -1f, .7f)
            "yt" -> { p.style = Paint.Style.FILL; p.color = if (col == 0xFF3CC58F.toInt()) 0xFFB00000.toInt() else 0xFFFF0000.toInt()
                rf.set(x - s * 1.15f, y - s * .78f, x + s * 1.15f, y + s * .78f); c.drawRoundRect(rf, s * .38f, s * .38f, p)
                p.color = 0xFFFFFFFF.toInt(); poly(true, true, -.35f, -.42f, .5f, 0f, -.35f, .42f) }
            "t360" -> { p.color = if (col == 0xFF3CC58F.toInt()) col else 0xFFFF8A00.toInt()
                rf.set(x - s * 1.1f, y - s * .72f, x + s * 1.1f, y + s * .72f); c.drawRoundRect(rf, s * .3f, s * .3f, p)
                p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                p.textSize = s * 1f; c.drawText("360", x, y + s * .35f, p); p.style = Paint.Style.STROKE }
            "remote" -> { rf.set(x - s * .55f, y - s, x + s * .55f, y + s); c.drawRoundRect(rf, s * .55f, s * .55f, p)
                rf.set(x - s * .2f, y - s * .62f, x + s * .2f, y - s * .15f); c.drawRoundRect(rf, s * .2f, s * .2f, p) }
            "keypad" -> { p.style = Paint.Style.FILL
                for (r in -1..1) for (q in -1..1) { rf.set(x + q * s * .75f - s * .21f, y + r * s * .72f - s * .5f, x + q * s * .75f + s * .21f, y + r * s * .72f - s * .08f); c.drawRoundRect(rf, s * .08f, s * .08f, p) }
                rf.set(x - s * .21f, y + s * .98f - s * .5f + s * .72f - s * .72f, x + s * .21f, y + s * .98f - s * .08f); c.drawRoundRect(rf, s * .08f, s * .08f, p); p.style = Paint.Style.STROKE }
            "gear" -> { c.drawCircle(x, y, s * .72f, p); c.drawCircle(x, y, s * .28f, p)
                for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); c.drawLine(x + (Math.cos(a) * s * .72).toFloat(), y + (Math.sin(a) * s * .72).toFloat(), x + (Math.cos(a) * s * 1.05).toFloat(), y + (Math.sin(a) * s * 1.05).toFloat(), p) } }
            "refresh" -> { rf.set(x - s * .8f, y - s * .8f, x + s * .8f, y + s * .8f); c.drawArc(rf, -40f, 290f, false, p); poly(true, true, 1f, -.95f, 1f, -.25f, .35f, -.55f) }
        }
        p.style = Paint.Style.FILL
    }
}

class IconView(ctx: Context, private val kind: String) : View(ctx) {
    var on = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) { Icons.draw(c, p, kind, width / 2f, height / 2f, minOf(width, height) * .27f, if (on) 0xFF3CC58F.toInt() else 0xFFF1F5F8.toInt()) }
}

// ---------- Remote vẽ tay: tỉ lệ theo màn hình, luôn vừa 1 màn, không cuộn ----------
class RemoteView(ctx: Context, private val press: (String, Boolean) -> Unit, private val longPress: (String) -> Unit) : View(ctx) {
    var hdmiLabel = "HDMI 2"
    private val bg = 0xFF1C202C.toInt(); private val sf = 0xFF4A5365.toInt(); private val ln = 0xFF4D566A.toInt()
    private val hi = 0xFF3A4254.toInt(); private val white = 0xFFF4F5F8.toInt(); private val green = 0xFF3CC58F.toInt(); private val red = 0xFFFD4743.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rects = LinkedHashMap<String, RectF>()
    private var k = 1f; private var rcx = 0f; private var rcy = 0f; private var rr = 0f
    private var down: String? = null; private var fired = false
    private val h = Handler(Looper.getMainLooper())
    private val rep = setOf("tvup", "tvdn", "bxup", "bxdn", "up", "down", "left", "right")
    private val repeater = object : Runnable { override fun run() { val d = down; if (d != null && d in rep) { press(d, true); h.postDelayed(this, 170) } } }
    private val longer = Runnable { val d = down; if (d != null) { fired = true; longPress(d); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }

    override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
        k = minOf(w / 100f, hh / 113f)
        val extra = (hh - k * 113f).coerceAtLeast(0f)
        val g = minOf(extra / 3f, 4f * k)
        val x0 = (w - 100f * k) / 2f
        val y0 = k * 1.5f + (extra - 3 * g).coerceAtLeast(0f) * .35f
        val b1 = y0 + 14.5f * k + g; val b2 = y0 + 63.5f * k + 2 * g
        rects.clear()
        fun a(id: String, base: Float, x: Float, y: Float, ww: Float, h2: Float) { rects[id] = RectF(x0 + x * k, base + y * k, x0 + (x + ww) * k, base + (y + h2) * k) }
        a("tv", y0, 6f, 0f, 22f, 11.5f); a("pw", y0, 39f, 0f, 22f, 11.5f); a("hdmi", y0, 72f, 0f, 22f, 11.5f)
        a("tvvol", b1, 6f, 0f, 22f, 46f); a("bxvol", b1, 72f, 0f, 22f, 46f)
        a("mic", b1, 39f, 0f, 22f, 15f); a("kbd", b1, 39f, 19f, 22f, 11.5f); a("mute", b1, 39f, 34.5f, 22f, 11.5f)
        a("yt", b2, 6f, 0f, 18f, 11.5f); a("home", b2, 6f, 18.25f, 18f, 11.5f); a("back", b2, 6f, 36.5f, 18f, 11.5f)
        a("t360", b2, 76f, 0f, 18f, 11.5f); a("menu", b2, 76f, 18.25f, 18f, 11.5f); a("exit", b2, 76f, 36.5f, 18f, 11.5f)
        rr = 24f * k; rcx = x0 + 50f * k; rcy = b2 + 24f * k
    }

    private fun hit(x: Float, y: Float): String? {
        val dx = x - rcx; val dy = y - rcy; val d = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (d <= rr * 1.05f) return if (d <= rr * .52f) "ok" else if (Math.abs(dx) > Math.abs(dy)) (if (dx < 0) "left" else "right") else (if (dy < 0) "up" else "down")
        val min = 44f * resources.displayMetrics.density
        var best: String? = null; var bd = Float.MAX_VALUE
        for ((id, r) in rects) {
            val ir = RectF(r); ir.inset(-((min - r.width()) / 2f).coerceAtLeast(0f), -((min - r.height()) / 2f).coerceAtLeast(0f))
            if (!ir.contains(x, y)) continue
            val dd = Math.hypot((x - r.centerX()).toDouble(), (y - r.centerY()).toDouble()).toFloat()
            if (dd < bd) { bd = dd; best = id }
        }
        return when (best) {
            "tvvol" -> if (y < rects["tvvol"]!!.centerY()) "tvup" else "tvdn"
            "bxvol" -> if (y < rects["bxvol"]!!.centerY()) "bxup" else "bxdn"
            else -> best
        }
    }

    override fun onDraw(c: Canvas) {
        fun outline(id: String, fill: Int? = null) {
            val r = rects[id] ?: return; val rad = r.height() / 2f
            if (fill != null || down == id) { p.style = Paint.Style.FILL; p.color = fill ?: hi; c.drawRoundRect(r, rad, rad, p) }
            p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(1.5f, k * .35f); p.color = fill ?: ln; c.drawRoundRect(r, rad, rad, p)
        }
        fun ic(id: String, name: String, col: Int = white, scale: Float = 3.6f, dy: Float = 0f, pc: Int = green) {
            val r = rects[id] ?: return; Icons.draw(c, p, name, r.centerX(), r.centerY() + dy, k * scale, if (down == id) pc else col)
        }
        outline("tv"); ic("tv", "tv", scale = 3.6f)
        outline("pw", red); ic("pw", "power", white, 3.8f, 0f, 0xFFFFD6D4.toInt())
        outline("hdmi"); ic("hdmi", "hdmi", white, 3f, -k * 1.7f)
        rects["hdmi"]?.let { r -> p.style = Paint.Style.FILL; p.color = white; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); p.textSize = k * 2.9f; c.drawText(hdmiLabel, r.centerX(), r.centerY() + k * 4.3f, p) }
        fun vol(id: String, lab: String, up: String, dn: String) {
            val r = rects[id] ?: return; p.style = Paint.Style.FILL; p.color = sf; c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, p)
            Icons.draw(c, p, "plus", r.centerX(), r.top + r.height() * .17f, k * 3.2f, if (down == up) green else white)
            Icons.draw(c, p, "minus", r.centerX(), r.bottom - r.height() * .17f, k * 3.2f, if (down == dn) green else white)
            p.style = Paint.Style.FILL; p.color = white; p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); p.textSize = k * 5.2f; c.drawText("VOL", r.centerX(), r.centerY() + k * .4f, p)
            p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); p.textSize = k * 3.2f; c.drawText(lab, r.centerX(), r.centerY() + k * 5.4f, p)
        }
        vol("tvvol", "TV", "tvup", "tvdn"); vol("bxvol", "BOX", "bxup", "bxdn")
        outline("mic"); ic("mic", "mic", 0xFFFF2A2A.toInt(), 4.4f)
        outline("kbd"); ic("kbd", "kbd", scale = 3.4f)
        outline("mute"); ic("mute", "mute", scale = 3.4f)
        p.style = Paint.Style.FILL; p.color = sf; c.drawCircle(rcx, rcy, rr, p)
        p.color = if (down == "ok") 0xFF2A3040.toInt() else bg; c.drawCircle(rcx, rcy, rr * .52f, p)
        p.color = if (down == "ok") green else white; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); p.textSize = k * 5.4f
        c.drawText("OK", rcx, rcy + k * 1.9f, p)
        for ((id, ang) in listOf("up" to 0f, "right" to 90f, "down" to 180f, "left" to 270f)) {
            c.save(); c.rotate(ang, rcx, rcy); Icons.draw(c, p, "tri", rcx, rcy - rr * .76f, k * 3.4f, if (down == id) green else white); c.restore()
        }
        outline("yt"); ic("yt", "yt", scale = 3.4f); outline("t360"); ic("t360", "t360", scale = 3.2f)
        outline("home"); ic("home", "home", scale = 3.4f); outline("menu"); ic("menu", "menu", scale = 3.4f)
        outline("back"); ic("back", "back", scale = 3.4f); outline("exit"); ic("exit", "exit", scale = 3.2f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = hit(e.x, e.y) ?: return true
                down = id; fired = false; invalidate(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (id in rep) { press(id, false); h.postDelayed(repeater, 380) } else if (id == "pw") h.postDelayed(longer, 650)
            }
            MotionEvent.ACTION_MOVE -> { if (down != null && hit(e.x, e.y) != down) cancel() }
            MotionEvent.ACTION_UP -> {
                val id = down; val same = id != null && hit(e.x, e.y) == id; val f = fired; cancel()
                if (same && !f && id != null && id !in rep) press(id, false)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
    private fun cancel() { h.removeCallbacks(repeater); h.removeCallbacks(longer); down = null; invalidate() }
    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var container: FrameLayout
    private lateinit var tabArea: LinearLayout
    private val navs = ArrayList<IconView>()
    private var remote: RemoteView? = null
    private var selectedTab = 0
    private val tvExecutor = Executors.newSingleThreadExecutor()
    private val boxExecutor = Executors.newSingleThreadExecutor()
    private val tvBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val bg = 0xFF1C202C.toInt(); private val panel = 0xFF2B3243.toInt(); private val fg = 0xFFF1F5F8.toInt()
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Cfg.init(this); Adb.dir = filesDir; bindWifi(this)
        window.statusBarColor = bg; window.navigationBarColor = bg
        Box.onError = { m -> runOnUiThread { if (!isFinishing) status.text = "Lỗi Box: $m" } }
        buildUi()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun label(t: String, size: Float = 15f) = TextView(this).apply { text = t; textSize = size; setTextColor(fg); gravity = Gravity.CENTER; setPadding(dp(6), dp(8), dp(6), dp(8)) }
    private fun button(t: String, action: () -> Unit): Button = Button(this).apply { text = t; isAllCaps = false; textSize = 13f; minHeight = 0; minimumHeight = 0; setTextColor(fg); setPadding(dp(2), 0, dp(2), 0); setBackgroundTintList(android.content.res.ColorStateList.valueOf(panel)); setOnClickListener { action() } }
    private fun row(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; weightSum = items.size.toFloat() }
        items.forEach { (t, a) -> r.addView(button(t, a), LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }) }
        parent.addView(r)
    }
    private fun action(name: String, target: String = "box", block: () -> Any?) {
        status.text = "Đang thực hiện: $name…"
        (if (target == "tv") tvExecutor else boxExecutor).execute {
            val result = try {
                val value = block()
                if (value == null || value === Unit || value.toString().isBlank()) "Đã gửi: $name" else value.toString()
            } catch (e: Exception) { "Lỗi $name: ${e.message}" }
            runOnUiThread { if (!isFinishing) status.text = result }
        }
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(4), 0) }
        status = TextView(this).apply { textSize = 12f; setTextColor(0xFF9AA3B5.toInt()); maxLines = 3 }
        head.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconView(this, "refresh").apply { setOnClickListener { discover() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(head, LinearLayout.LayoutParams(-1, dp(52)))
        container = FrameLayout(this); root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("remote", "keypad", "gear").forEachIndexed { i, kind -> val v = IconView(this, kind); v.setOnClickListener { selectedTab = i; renderTab() }; navs.add(v); nav.addView(v, LinearLayout.LayoutParams(0, dp(54), 1f)) }
        root.addView(nav)
        setContentView(root); renderTab(); refreshStatus()
    }
    private fun hdmiLabel() = Cfg["hdmi"].let { if (it == "tv") "TV" else it.uppercase().replace("HDMI", "HDMI ") }
    private fun renderTab() {
        container.removeAllViews(); navs.forEachIndexed { i, v -> v.on = i == selectedTab; v.invalidate() }
        if (selectedTab == 0) {
            val r = RemoteView(this, { id, rep -> onPress(id, rep) }, { id -> onLong(id) }); r.hdmiLabel = hdmiLabel(); remote = r
            container.addView(r, FrameLayout.LayoutParams(-1, -1))
        } else {
            remote = null
            tabArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)); gravity = Gravity.CENTER_HORIZONTAL }
            val sv = ScrollView(this); sv.addView(tabArea); container.addView(sv, FrameLayout.LayoutParams(-1, -1))
            if (selectedTab == 1) keypadUi() else settingsUi()
        }
    }
    private fun tvKey(code: String, repeat: Boolean) {
        if (repeat && tvBusy.get()) return
        tvBusy.set(true)
        tvExecutor.execute {
            try { Tv.ircc(Tv.codes[code]!!) } catch (e: Exception) { runOnUiThread { status.text = "Lỗi TV: ${e.message}" } } finally { tvBusy.set(false) }
        }
    }
    private fun boxKey(k: String, repeat: Boolean) {
        if (repeat && Box.pending() > 0) return   // giữ nút: chỉ gửi tiếp khi Box đã xử lý xong phím trước
        try { Box.key(k) } catch (e: Exception) { status.text = "Lỗi: ${e.message}" }
    }
    private fun onPress(id: String, rep: Boolean) {
        when (id) {
            "tv" -> action("Nguồn TV", "tv") { Tv.power() }
            "pw" -> action("Nguồn: Box → TV → HDMI") { Scene.toggle(this) }
            "hdmi" -> chooseInput()
            "tvup" -> tvKey("volUp", rep); "tvdn" -> tvKey("volDown", rep)
            "mute" -> action("Tắt tiếng TV", "tv") { Tv.ircc(Tv.codes["mute"]!!) }
            "bxup" -> boxKey("VOL+", rep); "bxdn" -> boxKey("VOL-", rep)
            "up", "down", "left", "right", "back", "home", "exit", "menu" -> boxKey(id.uppercase(), rep)
            "ok" -> boxKey("OK", rep)
            "mic" -> voice()
            "kbd" -> textDialog()
            "yt" -> action("Mở YouTube") { Box.open() }
            "t360" -> action("Mở TV360") { Box.openTv360() }
        }
    }
    private fun onLong(id: String) {
        if (id == "pw") action("Nguồn Box") { if (Box.isAwake()) { Box.sleep(); "Đã gửi lệnh ngủ cho TV Box" } else { Box.wake(); "Đã đánh thức TV Box" } }
    }
    private fun chooseInput() {
        val names = arrayOf("TV", "HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4", "Chuyển vòng (INPUT)")
        val keys = listOf("tv", "hdmi1", "hdmi2", "hdmi3", "hdmi4", "input")
        AlertDialog.Builder(this).setTitle("Chọn nguồn vào của TV").setItems(names) { _, i ->
            action("Nguồn vào ${names[i]}", "tv") {
                Tv.ircc(Tv.codes[keys[i]]!!)
                if (i < 5) { Cfg["hdmi"] = keys[i]; runOnUiThread { remote?.hdmiLabel = hdmiLabel(); remote?.invalidate() } }
                "Đã chuyển TV sang ${names[i]}"
            }
        }.show()
    }
    private fun textDialog() {
        val et = EditText(this).apply { setSingleLine(true); hint = "Nội dung cần tìm / gõ"; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle("Gửi chữ sang Box").setView(et)
            .setPositiveButton("Tìm trên Box") { _, _ -> val q = et.text.toString().trim(); if (q.isNotEmpty()) action("Tìm: $q") { Box.search(q) } }
            .setNeutralButton("Gõ vào ô nhập") { _, _ -> val q = et.text.toString().trim(); if (q.isNotEmpty()) action("Gõ: $q") { Box.text(q, true) } }
            .setNegativeButton("Hủy", null).show()
    }
    private fun keypadUi() {
        val k = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        for (r in 0 until 4) row(tabArea, *(0 until 3).map { c -> k[r * 3 + c] to { boxKey(k[r * 3 + c], false) } }.toTypedArray())
    }
    private fun discover() = action("Tìm thiết bị") {
        val r = Disc.ensure(true)
        val boxes = Disc.findBox()
        if (boxes.isNotEmpty() && Cfg["boxIp"] !in boxes) Cfg["boxIp"] = boxes[0]
        Adb.reset(); runOnUiThread { refreshStatus() }
        "Quét xong · TV: ${Cfg["sonyIp"].ifEmpty { "không thấy ($r)" }} · Box: ${if (boxes.isEmpty()) "không thấy máy nào mở cổng ADB 5555" else boxes.joinToString()}"
    }
    private fun checkConnections() = action("Kiểm tra TV + Box") {
        val tv = Tv.diag()
        val t0 = System.currentTimeMillis()
        val box = try { "OK · " + Adb.sh("getprop ro.product.model").ifBlank { "ADB đã kết nối" } + " (${System.currentTimeMillis() - t0} ms)" }
                   catch (e: Exception) { "LỖI · ${e.message}" }
        "TV: $tv\nBox: $box"
    }
    private fun refreshStatus() { status.text = "TV: ${Cfg["sonyIp"].ifEmpty { "chưa tìm thấy" }}   ·   Box: ${Cfg["boxIp"].ifEmpty { "chưa nhập" }}:${Cfg["adbPort"]}" }
    private fun settingsUi() {
        fun field(title: String, key: String, hint: String = ""): EditText {
            tabArea.addView(label(title).apply { gravity = Gravity.START })
            val e = EditText(this).apply { setText(Cfg[key]); setSingleLine(true); setTextColor(fg); setHintTextColor(0xFF9AAAB5.toInt()); setHint(hint); setPadding(dp(12), 0, dp(12), 0); setBackgroundColor(panel) }
            tabArea.addView(e, LinearLayout.LayoutParams(-1, dp(48))); return e
        }
        val tv = field("IP TV Sony", "sonyIp", "Ví dụ 192.168.1.234")
        val box = field("IP Android TV Box", "boxIp", "Ví dụ 192.168.1.25")
        val port = field("Cổng ADB của Box", "adbPort", "5555")
        val psk = field("Sony PSK (nếu đã bật IP Control)", "psk", "Để trống nếu không dùng")
        val mac = field("MAC TV (tùy chọn)", "tvMac", "AA:BB:CC:DD:EE:FF")
        val boxMac = field("MAC TV Box (Wake-on-LAN, nếu hỗ trợ)", "boxMac", "AA:BB:CC:DD:EE:FF")
        val search = field("Gói ứng dụng YouTube/tìm kiếm trên Box", "searchApp", "com.teamsmart.videomanager.tv")
        val t360 = field("Gói TV360 (trống = tự dò)", "tv360App", "tự dò gói có chữ tv360")
        row(tabArea, "LƯU CẤU HÌNH" to {
            Cfg["sonyIp"] = tv.text.toString().trim(); Cfg["boxIp"] = box.text.toString().trim()
            Cfg["adbPort"] = port.text.toString().trim().ifEmpty { "5555" }; Cfg["psk"] = psk.text.toString().trim()
            Cfg["tvMac"] = mac.text.toString().trim(); Cfg["boxMac"] = boxMac.text.toString().trim()
            Cfg["searchApp"] = search.text.toString().trim(); Cfg["tv360App"] = t360.text.toString().trim()
            Adb.reset(); refreshStatus(); status.text = "Đã lưu cấu hình thiết bị"
        })
        row(tabArea, "TEST TV + BOX" to { checkConnections() }, "ĐẶT LẠI ADB" to { Adb.reset(); status.text = "Đã đóng kết nối ADB; lệnh sau sẽ kết nối mới" })
        row(tabArea, "KIỂM TRA / CẬP NHẬT PHIÊN BẢN" to { updateApp() })
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
    override fun onResume() { super.onResume(); bindWifi(this); if (::status.isInitialized) refreshStatus() }
    override fun onBackPressed() { finish() }
    override fun onDestroy() { tvExecutor.shutdownNow(); boxExecutor.shutdownNow(); Adb.reset(); super.onDestroy() }
}
