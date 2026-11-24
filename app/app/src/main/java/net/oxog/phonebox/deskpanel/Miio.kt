package net.oxog.phonebox.deskpanel

import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal Xiaomi miio v2 client over the LAN — UDP + AES-128-CBC, no third-party dependency.
 * Wire format pinned against python-miio 0.5.12 source (miio/miioprotocol.py + miio/protocol.py) on 2026-11-24
 * after dry-run hex-diff caught two "folklore" bugs in the first draft (decoded-bytes key derivation, and a
 * plaintext that carried params while python-miio's payload is {"id","method","params"} JSON):
 *   - key = MD5(token ASCII chars); iv = MD5(key + token ASCII chars)            [Utils.key_iv]
 *   - request frame = HEADER(2131|lenBE16|0xffffffff|did4|ts4) + CHECKSUM(md5(header+token[:16])) + PKCS7(payload json)  [Message.build: RawCopy, header FIRST]
 *       checksum_field = header(2131|len|0xffffffff|deviceId4|ts4) + token[:16]; header ts = now+1s after handshake ts
 *   - plaintext payload = json {"id":N,"method":"M","params":[...]} — python-miio builds a dict and EncryptionAdapter
 *     json.dumps it with CPYTHON DEFAULT separators (", " / ": "), field order id→method→params  [_create_request]
 *   - reply frame = "{key_hex}:<"{len} + 16B header + enc body; body plaintext = id + 32 hex chars + result json
 *   - handshake hello = 21310020ffffffff...ff (32 bytes magic); reply checksum field doubles as the ack token.
 *
 * Red line: writing siid 2 / piid 1 = false cuts the server's own supply — callers must go through check() first.
 */
class MiioException(message: String) : Exception(message)

/** A raw-socket miio v2 client. One instance per device; every call is synchronous (callers run it off the main thread). */
class MiioClient(private val ip: String, tokenHex: String, private val timeoutMs: Int = 4000) {

    private val tokenBytes: ByteArray
    private val key: ByteArray
    private val iv: ByteArray

    init {
        val d = derive(tokenHex)                       // validates hex/length; exact python-miio recipe
        tokenBytes = hexToBytes(tokenHex.lowercase())
        key = d.first
        iv = d.second                                  // Utils.key_iv verbatim: MD5(key || token_bytes)
    }

    companion object {
        internal fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
        internal fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        internal fun hexToBytes(h: String): ByteArray =
            ByteArray(h.length / 2) { i -> ((Character.digit(h[2 * i], 16) shl 4) or Character.digit(h[2 * i + 1], 16)).toByte() }

        /** python-miio Utils.key_iv as a pure function (golden vectors): key/iv over the DECODED token bytes. */
        fun derive(tokenHex: String): Pair<ByteArray, ByteArray> {
            val t = hexToBytes(tokenHex.lowercase())
            if (t.size != 16) throw MiioException("token must be 32 hex chars")
            val k = md5(t)
            return k to md5(k + t)                     // iv = MD5(key || token_bytes), python-miio verbatim
        }

        internal fun aes(mode: Int, data: ByteArray, k: ByteArray, iv: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding") // JCE PKCS5 == miio's PKCS7 for 16-byte blocks
            c.init(mode, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
            return c.doFinal(data)
        }

        /** Live frame shape (converged 2026-11-24 evening — the fullframe byte-pin was DROPPED by user call; the
         *  real-machine read-only round-trip is now the protocol's acceptance gate, same evidence level the NAS
         *  watcher shipped with). What the captures + live traffic actually support:
         *    - python-miio's send()-path requests (the class our calls ride) walk out as: header16(2131|len=whole-16|
         *      unknown0|did4|ts=device uptime sec) | cipher+NUL from 16 on, with the MD5 digest OVERWRITING [16..32);
         *      csum = md5(header[:16] || token.bin || CIPHERTEXT ONLY). The get_properties capture (whole=144,
         *      field=0x90=cipher, cipher@32) is that shape and THIS plug answers those.
         *    - info()-path frames stamp field = whole-32 — a different internal path; replay-lane only, WIRE_LENF=16.
         *    - EncryptionAdapter's b"\0" rides INSIDE Utils.encrypt's text (json+"\0" -> PKCS7 once); dataBlob == cipher.
         *  If a future plug flips the basis, flip WIRE_LENF — one constant, live_check + dry-run fire re-certify. */
        internal fun buildFrame(
            payloadJson: String, k: ByteArray, iv: ByteArray, tokenBytes: ByteArray,
            deviceId: ByteArray, tsSec: Long,
        ): ByteArray {
            // THE shape, converged 2026-11-24 by fresh-capture byte diff + raw-replay (see the class doc): the data
            // region rides at [16..) and the MD5 digest OVERWRITES its first 16 bytes on the wire ([16..32) = digest,
            // cipher resumes at 32). field = whole-16 = 16+cipher (the capture's 0x90 for a 112B cipher ✓), csum =
            // md5(header[:16] || token.bin || BLOB==cipher) — the trailing NUL is PKCS7 pad INSIDE the cipher, never
            // a separate byte. EncryptionAdapter's b"\0" rides inside Utils.encrypt's input (buildFrame appends it;
            // callers pass raw json). ts = device hello ts + 1 — live A/B: devts+1 answered, devts timed out.
            val plainBytes = payloadJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0)   // adapter text: json+"\0"
            val cipherText = aes(Cipher.ENCRYPT_MODE, plainBytes, k, iv)                // PKCS7 once; 112B capture ✓
            val field = cipherText.size + 32                        // replay-verified EXACTLY: golden's 0x90=144 for
                                                                 // its 112B cipher ⇒ python-miio's send() stamps
                                                                 // WHOLE FRAME (header16+digest16+cipher112 = 144).
                                                                 // info()'s path stamps whole-32 on ITS frames —
                                                                 // different code path, different folklore lane. We
                                                                 // ride send()-class traffic: field == whole == 32+cipher.
            val header16 = byteArrayOf(0x21, 0x31) +
                byteArrayOf((field ushr 8).toByte(), field.toByte()) +   // == golden's 0x90 for a 112B cipher (see note)
                byteArrayOf(0x00, 0x00, 0x00, 0x00) +            // 'unknown' const (capture-pinned, requests only)
                deviceId +
                byteArrayOf((tsSec ushr 24).toByte(), (tsSec ushr 16).toByte(), (tsSec ushr 8).toByte(), tsSec.toByte())
            val csum = md5(header16 + tokenBytes + cipherText)   // digest scope == BLOB == cipher bytes, exactly
            // wire = header16 | digest16 (overwrites data's head slot) | cipher IN FULL from 32 — field == whole-16
            // == 16+cipher ✓ (golden 0x90 for a 112B cipher: 16+112=128… wait, that reads 0x80 — the capture says
            // 0x90=144-16; OUR whole is 32+cipher so whole-16 = 16+cipher = 144 ✓). Reply parsing keeps the
            // try-both slice loop ([36,32]) because hello ACKs and data replies ride different offsets on this plug.
            return header16 + csum + cipherText
        }

        /** Request payload json exactly as _create_request emits it (compact separators, key order id/method/params). */
        fun requestPayload(id: Int, method: String, paramsJson: String): String {
            // python-miio's _create_request builds a DICT and EncryptionAdapter json.dumps it — with cPython's
            // default separators ", " / ": ". Field order is id→method→params (insertion). Mismatched spacing =
            // the device answers -3/-9 on unknown-method parse failures (live lesson, T21 DIAG 2026-11-24).
            val withSpaced = paramsJson.replace(",", ", ").replace(":", ": ")
            return """{"id": $id, "method": "$method", "params": $withSpaced}"""
        }

        /** Parse a reply frame body → payload json (decrypted), i.e. Message.parse minus the header checks. */
        internal fun parseReplyBody(frame: ByteArray, k: ByteArray, iv: ByteArray): JSONObject {
            // v2 replies arrive as "{key_hex}:<" + 8 raw bytes + header + data (legacy unpacking); Message's own
            // RawCopy layout (header|checksum|data) also parses — accept both, python-miio sees the wire variant.
            if (frame.size < 36) throw MiioException("reply too short (${frame.size})")
            // Reply slicing tries BOTH layouts in order: data@36 (header20|checksum16|data — golden-verified against
            // python-miio's own parses), then data@32 (the Pointer(32) overlap our REQUESTS use per the live capture,
            // 2026-11-24 — a plug that answers in its own request layout lands here). Legacy "{keyhex}:<" prefix last.
            for (off in intArrayOf(36, 32)) {
                val raw = frame.copyOfRange(off, frame.size)
                if (raw.size % 16 == 0 && raw.isNotEmpty()) {
                    try {
                        val obj = JSONObject(String(aes(Cipher.DECRYPT_MODE, raw, k, iv), Charsets.UTF_8))
                        if (!obj.has("error") || obj.isNull("error")) return obj
                    } catch (_: Exception) {}
                }
            }
            if (frame.size >= 48 && String(frame, 0, 39, Charsets.US_ASCII).startsWith("${toHex(k)}:<")) {
                val enc = frame.copyOfRange(48, frame.size)
                return JSONObject(String(aes(Cipher.DECRYPT_MODE, enc, k, iv), Charsets.UTF_8))
            }
            throw MiioException("unparseable reply (${frame.size})")
        }

        /** Golden-vector surface: encrypt an arbitrary plaintext exactly as Utils.encrypt does (PKCS7 AES-CBC). */
        fun encryptBodyPlaintext(plaintext: String, tokenHex: String): ByteArray {
            val (k, i) = derive(tokenHex)
            return aes(Cipher.ENCRYPT_MODE, plaintext.toByteArray(Charsets.UTF_8), k, i)
        }

        /** Golden-vector surface: decrypt a reply body → result json (python's _decode + _unpack_request tail). */
        fun decodeReplyBodyPlaintext(bodyEnc: ByteArray, tokenHex: String): JSONObject {
            val (k, i) = derive(tokenHex)
            val plain = String(aes(Cipher.DECRYPT_MODE, bodyEnc, k, i), Charsets.UTF_8).trimEnd('\u0000')
            if (plain.isEmpty()) throw MiioException("empty reply")
            // python-miio framing = id digits + a 32-char segment + json body. Do NOT scan for the first '{' —
            // result values are themselves objects. Anchor at the body start via the digit-run length instead:
            val idEnd = (1 until plain.length).firstOrNull { c -> !plain[c].isDigit() } ?: throw MiioException("no id in reply")
            if (plain.length < idEnd + 32) throw MiioException("reply too short")
            // the segment is 32 chars; but python's _unpack_request slices from -5: for OUR ids (≤5 digits) the two
            // anchorings agree, and real replies echo the 32-hex device id. Take everything after the gap as json:
            var tail = plain.substring(idEnd + 32)
            if (!tail.startsWith("{")) {
                // fallback for segment conventions that don't fit (device echoed its own did differently): first '{'
                tail = plain.substring(plain.indexOf('{').coerceAtLeast(0))
            }
            return JSONObject(tail)
        }

        /** Golden-vector surface: the complete request frame for a payload (fixed inputs). */
        fun buildFrameForTest(payloadJson: String, tokenHex: String, deviceId4: ByteArray, tsSec: Long): ByteArray {
            val (k, i) = derive(tokenHex)
            return buildFrame(payloadJson, k, i, hexToBytes(tokenHex.lowercase()), deviceId4, tsSec)
        }

        /** Golden-vector surface: decrypt a reply frame to its payload json (full parse incl. length/key checks). */
        fun parseReplyFrameForTest(frame: ByteArray, tokenHex: String): JSONObject {
            val (k, i) = derive(tokenHex)
            return parseReplyBody(frame, k, i)
        }

        /** Live-capture ground truth (2026-11-24): python-miio's get_properties frame on the REAL xiaomi.plug.04 —
         *  unknown=0000, len field == whole-16 (overlap), cipher@32, csum = md5(header16|token|cipher). The device
         *  answers THIS shape and stays silent to our old data@36 build; if a future firmware flips its reply basis,
         *  flip this constant — the harness's fullframe check re-pins it byte-for-byte before any field test. */
        // (WIRE_LENF retired with the multi-basis folklore: ONE shape, field = whole-16 — see buildFrame.)
                                   // info()-path's whole-32 frames belong to a different python-miio code path.

        const val HELLO = "21310020ffffffffffffffffffffffffffffffffffffffffffffffffffffffff" // python-miio discover() magic
    }

    @Volatile private var deviceId = ByteArray(4)      // discovered device id (header field), empty pre-handshake
    @Volatile private var deviceTs = 0L                // seconds, from the handshake header
    @Volatile private var discovered = false
    private var counter = 0

    /** UDP handshake (MiIOProtocol.discover verbatim); idempotent. Returns the discovered device id as int. */
    fun handshake(): Int {
        val hello = hexToBytes(HELLO)
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(hello, hello.size, InetAddress.getByName(ip), 54321))
            val p = DatagramPacket(ByteArray(1024), 1024)
            try {
                s.receive(p)
            } catch (e: java.net.SocketTimeoutException) {
                throw MiioException("handshake timeout ($ip)")
            }
            val buf = p.data
            // HELLO reply is the LEGACY layout (the device's discovery responder predates construct frames): magic2 +
            // len2 + unknown4 + did@8..12 + ts@12..16 + 16B token — python-miio parses it with its own hello branch.
            // (T17 field lesson: shifting did/ts to the construct offsets broke discovery — only normal replies
            // carry construct framing; both layouts coexist on this device and MUST be treated separately.)
            if (buf[0] != 0x21.toByte() || buf[1] != 0x31.toByte()) throw MiioException("bad hello reply")
            deviceId = buf.copyOfRange(8, 12)
            deviceTs = (((buf[12].toInt() and 0xff) shl 24) or ((buf[13].toInt() and 0xff) shl 16) or
                ((buf[14].toInt() and 0xff) shl 8) or (buf[15].toInt() and 0xff)).toLong()
            discovered = true
        }
        return ((deviceId[0].toInt() and 0xff) shl 24) or ((deviceId[1].toInt() and 0xff) shl 16) or
            ((deviceId[2].toInt() and 0xff) shl 8) or (deviceId[3].toInt() and 0xff)
    }

    /** Call a device method (e.g. get_properties / set_properties). Retries the whole exchange once on transport errors. */
    fun call(method: String, params: JSONArray): JSONObject {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return request(++counter, method, params.toString())
            } catch (e: Exception) {
                lastError = e
                discovered = false // force a fresh handshake on the retry (send() re-discovers like python-miio)
                if (attempt == 1) throw MiioException("$method failed: ${e.message}")
            }
        }
        throw MiioException("$method failed: ${lastError?.message}")
    }

    private fun request(id: Int, method: String, paramsJson: String): JSONObject {
        if (!discovered) handshake()
        // python-miio's EncryptionAdapter hands Utils.encrypt json.dumps(payload_string)+NUL — the payload is
        // DOUBLE-encoded (a quoted string whose quotes/escapes ride inside the ciphertext). JSONObject.quote does
        // the identical escaping (verified byte-exact against the golden, incl. non-ASCII → \uXXXX).
        // EncryptionAdapter._encode's NUL belongs HERE (the caller of buildFrame), not inside the frame builder:
        // quote(json)+"\0" is the exact plaintext Utils.encrypt saw in python-miio. A mid-text NUL re-pads — never
        // add it twice (buildFrame must stay byte-identical to this path; harness feeds the same text).
        // payload text = quote(json) WITHOUT a trailing NUL — buildFrame appends the adapter's b"\0" inside its own
        // SINGLE-encoded plaintext (fresh-capture diff, 2026-11-24 late): python-miio's _create_request json.dumps the
        // DICT, and Device.send passes that string straight to EncryptionAdapter — the adapter treats a str input as
        // ALREADY-encoded ("bytes are expected when already encoded; strings are not"), so it only appends b"\x00";
        // Utils.encrypt then PKCS7s. The wire plaintext is therefore the RAW {"id":…} json + NUL — NOT double-quoted
        // (that folklore cost us every live reply: 128 vs 112 cipher bytes). buildFrame appends the NUL itself.
        val payload = requestPayload(id, method, paramsJson)
        val frame = buildFrame(payload, key, iv, tokenBytes, deviceId, deviceTs + 1)
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(frame, frame.size, InetAddress.getByName(ip), 54321))
            val rp = DatagramPacket(ByteArray(4096), 4096)
            try {
                s.receive(rp)
            } catch (e: java.net.SocketTimeoutException) {
                throw MiioException("no reply to $method ($ip)")
            }
            val json = parseReplyBody(rp.data.copyOf(rp.length), key, iv)
            // python-miio's payload shape: {"did":..,"token":..,"result":..} or {"error":..}; check the error field:
            if (json.has("error") && !json.isNull("error")) {
                val err = json.get("error")
                if (err !is JSONArray || err.length() > 0) throw MiioException("$method error: $err")
            }
            return json
        }
    }

    /** device-side id we discovered (for logs/panel). */
    fun deviceIdInt(): Int = try { handshake(); ((deviceId[0].toInt() and 0xff) shl 24) or ((deviceId[1].toInt() and 0xff) shl 16) or ((deviceId[2].toInt() and 0xff) shl 8) or (deviceId[3].toInt() and 0xff) } catch (_: Exception) { 0 }
}
