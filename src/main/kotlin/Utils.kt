import com.mashape.unirest.http.HttpResponse
import com.mashape.unirest.http.Unirest
import java.io.DataInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory

object Utils {

    fun getFullManifest(version: String): String {
        val response: HttpResponse<InputStream> =
            Unirest.get("https://repo.runelite.net/plugins/manifest/${version}_full.js").asBinary()
        println("https://repo.runelite.net/plugins/manifest/${version}_full.js")
        val stream = DataInputStream(response.body)

        val signatureSize = stream.readInt()
        val signatureBuffer = ByteArray(signatureSize)
        stream.read(signatureBuffer, 0, signatureSize)

        val remaining = stream.available()
        val buffer = ByteArray(remaining)
        stream.read(buffer, 0, remaining)

        val s = Signature.getInstance("SHA256withRSA")
        s.initVerify(loadRuneLiteCertificate())
        s.update(buffer)

        if (!s.verify(signatureBuffer)) {
            throw RuntimeException("Unable to verify external plugin manifest")
        }

        return String(buffer)
    }

    private fun loadRuneLiteCertificate(): Certificate? {
        if (!Files.exists(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_PUBLIC_KEY))) {
            error(
                "You need to grab RuneLite's externalplugins.crt in order to download plugins from RuneLite's plugin hub!\n" + "Put it here: ${Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH).toAbsolutePath()}"
            )
        }

        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            certFactory.generateCertificate(
                Files.newInputStream(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_PUBLIC_KEY))
            )
        } catch (e: CertificateException) {
            throw RuntimeException(e)
        }
    }

}
