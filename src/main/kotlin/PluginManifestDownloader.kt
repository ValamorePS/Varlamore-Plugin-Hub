import PluginHubManifest.ManifestFull
import PluginHubManifest.ManifestLite
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.gson.Gson
import com.mashape.unirest.http.HttpResponse
import com.mashape.unirest.http.JsonNode
import com.mashape.unirest.http.Unirest
import jakarta.annotation.Nullable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.createDirectory
import kotlin.io.path.fileSize
import kotlin.system.measureTimeMillis

object PluginManifestDownloader {

    @JvmStatic
    fun main(args: Array<String>) {
        val startTracking: String = "1.10.3"
        val response: HttpResponse<JsonNode> =
            Unirest.get("https://api.github.com/repos/runelite/runelite/tags").asJson()
        val max = response.body.array.getJSONObject(0).getString("name").replace("runelite-parent-", "")

        var result = mutableListOf<String>()

        val minParts = startTracking.split(".").map { it.toInt() }
        val maxParts = max.split(".").map { it.toInt() }

        for (i in minParts.indices) {
            val start = minParts[i]
            val end = maxParts.getOrElse(i) { if (i == 0) start else 0 }

            for (j in start..end) {
                val newValue = minParts.take(i).plus(j).joinToString(".")
                result.add(newValue)
            }
        }

        val location = File("./plugin-hub/cacheManifests/")
        result = result.drop(2).dropLast(1).toMutableList()
        val time = measureTimeMillis {
            result.drop(2).dropLast(1).forEach {
                val name = "${it}.json"
                if (!File(location, name).exists()) {
                    println("Downloading: $it")
                    File(location, "${it}.json").writeText(Utils.getFullManifest(it))
                }
            }
        }
        println("Downloaded ${result.size} Manifests in ${time}ms")
    }

}