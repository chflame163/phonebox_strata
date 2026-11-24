// Dry-run hex-diff harness for the Miio client (开发计划 §5.1: 协议字节级比对，防"手搓出 bug").
// golden.json comes from miio_hex_golden.py on the server (python-miio 0.5.12 Utils/EncryptionAdapter = truth).
// Layered contract (see golden docstring): our OWN scope = key/iv recipe, encrypted body bytes for the exact
// plaintext python sends (json.dumps(payload)+NUL), and decoding of device reply bodies; construct's frame
// stitching (header/checksum/ts clock) is pinned by the LIVE read-only round-trip against the real plug.
//   VM:  ./kotlinc -cp json.jar Miio.kt MiioHexTest.kt -include-runtime -d hextest.jar
//        java -cp hextest.jar:json.jar net.oxog.phonebox.deskpanel.MiioHexTestKt golden.json
import org.json.JSONObject
import java.io.File
import net.oxog.phonebox.deskpanel.MiioClient

fun main(args: Array<String>) {
    val golden = JSONObject(File(args[0]).readText())
    var fails = 0
    fun check(name: String, mine: String, theirs: String) {
        if (mine == theirs) println("OK   $name") else { println("FAIL $name\n  kotlin=${mine.take(90)}\n  python=${theirs.take(90)}"); fails++ }
    }
    val tok = golden.getString("token")
    // 1) key/iv derivation — MUST match Utils.key_iv exactly
    val (k, i) = MiioClient.derive(tok)
    check("key", MiioClient.toHex(k), golden.getJSONObject("vectors").getString("key"))
    check("iv", MiioClient.toHex(i), golden.getJSONObject("vectors").getString("iv"))
    // 2) encrypted body for python's EXACT plaintext (double-encoded json + NUL, PKCS7): EncryptionAdapter._encode
    //    hands Utils.encrypt the bytes of json.dumps(payload_string)+"\0" — JSONObject.quote gives us that quoting.
    val b = golden.getJSONObject("body")
    val body = MiioClient.encryptBodyPlaintext(JSONObject.quote(b.getString("payload_json")) + "\u0000", tok)
    check("body", MiioClient.toHex(body), b.getString("enc_hex"))
    // 3) request payload shape = {"id": N, "method": M, "params": P} with python-miio's dict field order and
    // json.dumps default spacing (", " / ": "). org.json reorders keys on re-serialize, so rebuild the golden's
    // text in its OWN insertion order (id→method→params) instead of round-tripping through JSONObject.
    val gj3 = JSONObject(b.getString("payload_json"))
    fun objSpaced(o: JSONObject): String {
        val parts = ArrayList<String>()
        for (k2 in arrayOf("siid", "piid")) if (o.has(k2)) parts.add("\"" + k2 + "\": " + o.get(k2))
        return "{" + parts.joinToString(", ") + "}"
    }
    val praw = gj3.getJSONArray("params")
    val pl = ArrayList<String>()
    for (i2 in 0 until praw.length()) pl.add(objSpaced(praw.getJSONObject(i2)))
    val paramsSpaced = "[" + pl.joinToString(", ") + "]"
    val pyText = "{\"id\": " + gj3.getInt("id") + ", \"method\": \"" + gj3.getString("method") + "\", \"params\": " + paramsSpaced + "}"
    check("payload_shape",
        MiioClient.requestPayload(12345, "get_properties", "[{\"siid\":2,\"piid\":1},{\"siid\":11,\"piid\":2}]"),
        pyText)
    // 4) reply body decode: decrypt + strip NUL + parse id/segment/result exactly like python-miio's _decode+_unpack
    val r = golden.getJSONObject("reply")
    try {
        val obj = MiioClient.decodeReplyBodyPlaintext(MiioClient.hexToBytes(r.getString("enc_hex")), tok)
        check("reply_json", obj.toString(), JSONObject(r.getString("result_json")).toString())
    } catch (e: Exception) { println("FAIL reply_decode: $e"); fails++ }
    // 5) FULL-FRAME pin vs a real python-miio get_properties capture (fresh-capture byte diff certified the shape:
    // field=whole-16, digest eats data[0..16), cipher resumes at 32; plaintext = RAW json + NUL inside encrypt). Our
    // buildFrame must reproduce that frame EXACTLY from payload text + did + ts — no lenf folklore anymore.
    val wf = golden.optJSONObject("wire_frame")
    if (wf != null) {
        try {
            val ours = MiioClient.buildFrameForTest(wf.getString("payload_json"), tok,
                MiioClient.hexToBytes(wf.getString("did4")), wf.getLong("ts"))
            check("fullframe", MiioClient.toHex(ours), wf.getString("hex"))
        } catch (e: Exception) { println("FAIL fullframe: $e"); fails++ }
    }
    println(if (fails == 0) "HEXDIFF_PASS" else "HEXDIFF_FAIL ($fails)")
}
