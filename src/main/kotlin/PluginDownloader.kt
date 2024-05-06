import PluginHubManifest.DisplayData
import PluginHubManifest.JarData
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
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
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

object PluginDownloader {

    val GSON: Gson = Gson()

    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    //private val wantedPlugins = arrayListOf(
    //    "117hd",
    //    "zulrah-helper",
    //    "skills-tab-progress-bars",
    //    "resource-packs",
    //    "the-gauntlet",
    //    "equipment-inspector"
    //)

    @JvmStatic
    fun main(args: Array<String>) {
        val keyValidator = KeyValidator()

        if (!keyValidator.execute()) {
            error("You need to have valid keys in order to proceed with the plugin downloader.")
        }

        val wantedPluginsConfigFile = Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, "wanted-plugins.yml")
        if (!Files.exists(wantedPluginsConfigFile)) {
            error("No config file exists for wanted plugins at: ${wantedPluginsConfigFile.toAbsolutePath()}")
        }

        val wantedPlugins: Array<String> = mapper.readValue(wantedPluginsConfigFile.toFile())

        if (!Files.exists(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY))) {
            Files.createDirectory(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY))
        }

        // clean the output folder
        Files.walk(Path.of(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY))
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete)

        val pluginToManifest: MutableMap<String, MutableList<Pair<String, String>>> = mutableMapOf()
        val manifestData: MutableMap<String, ManifestFull> = mutableMapOf()

        val manifestFull = ManifestFull()
        val manifestLite = ManifestLite()

        File("./plugin-hub/cacheManifests/").listFiles()?.forEach {
            val build = it.nameWithoutExtension
            val data = Gson().fromJson(it.readText(),ManifestFull().javaClass)
            manifestData[build] = data
            data.display.forEach { display ->
                val pairList = pluginToManifest.getOrPut(display.internalName) { mutableListOf() }
                pairList.add(display.version to build)
            }
        }

        loadManifestData(pluginToManifest, manifestData)

        val latestVersion = getLatestRuneLiteVersion()
        val progressBar = ProgressBarBuilder()
            .setInitialMax(wantedPlugins.size.toLong())
            .setStyle(ProgressBarStyle.ASCII)
            .setTaskName("Processing Plugins")
            .showSpeed()
            .build()

        progressBar.maxHint(wantedPlugins.size.toLong())

        wantedPlugins.forEach { plugin ->
            val (name, ver) = parsePluginNameAndVersion(plugin)

            progressBar.extraMessage = " ${name}: ${ver ?: "Latest"}"

            if (!pluginToManifest.contains(name)) {
                println("Unable to find plugin: $name")
                progressBar.step()
                return@forEach
            }

            try {
                val (manifestID, manifestDisplayData, manifestJars) = if (ver != null) {
                    pluginToManifest[name]!!.find { it.first == ver }?.let { pair ->
                        val manifestID = pair.second
                        Triple(manifestID, manifestData[manifestID]!!.display.find { it.internalName == name }!!,
                            manifestData[manifestID]!!.jars.find { it.internalName == name }!!)
                    } ?: run {
                        println("Version $ver not found for plugin $name")
                        return@forEach
                    }
                } else {
                    val manifest = GSON.fromJson(getLatestManifestFull(latestVersion), ManifestFull().javaClass)
                    Triple("", manifest.display.find { it.internalName == name }!!, manifest.jars.find { it.internalName == name }!!)
                }

                downloadAndProcessManifest(manifestDisplayData, manifestJars, manifestFull, manifestLite)
                progressBar.step()
            } catch (e: Exception) {
                println("Error processing plugin: $plugin - ${e.message}")
                e.printStackTrace()
            }
        }
        progressBar.close()

        Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY, "manifest").createDirectory()
        writeLiteManifest(manifestLite)
        writeFullManifest(manifestFull)
        commitFiles("https://github.com/ValamorePS/hosting.git",args.first(),Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY))
    }

    fun loadManifestData(pluginToManifest: MutableMap<String, MutableList<Pair<String, String>>>, manifestData: MutableMap<String, ManifestFull>) {
        File("./plugin-hub/cacheManifests/").listFiles()?.forEach { file ->
            val build = file.nameWithoutExtension
            val data = Gson().fromJson(file.readText(), ManifestFull().javaClass)
            manifestData[build] = data
            data.display.forEach { display ->
                val pairList = pluginToManifest.getOrPut(display.internalName) { mutableListOf() }
                pairList.add(display.version to build)
            }
        }
    }

    fun parsePluginNameAndVersion(plugin: String): Pair<String, String?> {
        return if (plugin.contains(":")) {
            val (name, ver) = plugin.split(":")
            name to if (ver.isNotBlank()) ver else null
        } else {
            plugin to null
        }
    }

    private fun downloadAndProcessManifest(
        manifestDisplayData: DisplayData,
        manifestJars: JarData,
        manifestFull: ManifestFull,
        manifestLite: ManifestLite
    ) {
        val path = Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY, "jar", "${manifestDisplayData.internalName}_${manifestJars.jarHash}.jar")

        download("jar", manifestDisplayData.internalName, "${manifestDisplayData.internalName}_${manifestJars.jarHash}.jar")
        download("icon", manifestDisplayData.internalName, "${manifestDisplayData.internalName}_${manifestDisplayData.iconHash}.png")
        manifestJars.jarSize = path.fileSize().toInt()
        manifestFull.display.add(manifestDisplayData)
        manifestFull.jars.add(manifestJars)
        manifestLite.jars.add(manifestJars)
    }

    private fun writeLiteManifest(manifest: ManifestLite) {
        val output = GSON.toJson(manifest).encodeToByteArray()

        val signature1 = Signature.getInstance("SHA256withRSA")
        val privateKey: PrivateKey = get(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_KEY_PATH, PluginHubConstants.PLUGIN_HUB_PRIVATE_KEY).toString())
        signature1.initSign(privateKey)
        signature1.update(output)
        val signature = signature1.sign()

        val buffer = ByteBuffer.allocate(signature.size + 4 + output.size)

        buffer.putInt(signature.size)
        buffer.put(signature)
        buffer.put(output)

        buffer.rewind()
        val arr = ByteArray(buffer.remaining())

        buffer[arr]

        Files.write(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY, "manifest" , "1.0.0_lite.js"), arr)
    }

    private fun writeFullManifest(filteredList: ManifestFull) {
        val output = GSON.toJson(filteredList).encodeToByteArray()

        val signature1 = Signature.getInstance("SHA256withRSA")
        val privateKey: PrivateKey = get(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_KEY_PATH, PluginHubConstants.PLUGIN_HUB_PRIVATE_KEY).toString())
        signature1.initSign(privateKey)
        signature1.update(output)
        val signature = signature1.sign()

        val buffer = ByteBuffer.allocate(signature.size + 4 + output.size)

        buffer.putInt(signature.size)
        buffer.put(signature)
        buffer.put(output)

        buffer.rewind()
        val arr = ByteArray(buffer.remaining())

        buffer[arr]

        Files.write(Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY, "manifest" , "1.0.0_full.js"), arr)
    }

    private fun download(type : String, plugin: String, file: String) {
        val response = Unirest.get("https://repo.runelite.net/plugins/${type}/$file").asBinary()

        val path = Paths.get(PluginHubConstants.PLUGIN_HUB_BASE_PATH, PluginHubConstants.PLUGIN_HUB_OUTPUT_DIRECTORY, type, file)
        Files.createDirectories(path.parent)

        Files.write(path, response.body.readAllBytes())
    }

    private fun getLatestManifestLite(version: String): JsonNode {
        val response: HttpResponse<InputStream> =
            Unirest.get("https://repo.runelite.net/plugins/manifest/${version}_lite.js").asBinary()
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

        return JsonNode(String(buffer))
    }

    private fun getLatestManifestFull(version: String): String {
        val response: HttpResponse<InputStream> =
            Unirest.get("https://repo.runelite.net/plugins/manifest/${version}_full.js").asBinary()
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

    private fun getLatestManifestFul(version: String): JsonNode {
        val response: HttpResponse<InputStream> =
            Unirest.get("https://repo.runelite.net/plugins/manifest/${version}_full.js").asBinary()
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

        return JsonNode(String(buffer))
    }

    private fun getLatestRuneLiteVersion(): String {
        val response: HttpResponse<JsonNode> =
            Unirest.get("https://api.github.com/repos/runelite/runelite/tags").asJson()
        return response.body.array.getJSONObject(0).getString("name").replace("runelite-parent-", "")
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

    fun get(filename: String): PrivateKey {
        val key = StringBuilder()
        BufferedReader(FileReader(filename)).use { reader ->
            reader.forEachLine { line ->
                if (!line.contains("-----BEGIN PRIVATE KEY-----") && !line.contains("-----END PRIVATE KEY-----")) {
                    key.append(line)
                }
            }
        }

        val encoded = Base64.getDecoder().decode(key.toString())
        val kf = KeyFactory.getInstance("RSA")
        val keySpec = PKCS8EncodedKeySpec(encoded)
        return kf.generatePrivate(keySpec)
    }

    private fun commitFiles(repositoryUrl: String, token: String, directoryPath: Path) {
        try {
            val tmpDir = Files.createTempDirectory("git_").toFile()
            val git = Git.cloneRepository()
                .setURI(repositoryUrl)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
                .setDirectory(tmpDir)
                .call()

            val subPath = "plugins"
            // Copy files from the directory to the repository
            directoryPath.toFile().walk().forEach { file ->
                if (file.isFile) {
                    val relativePath = directoryPath.relativize(file.toPath()).toString()
                    val destFile = File("${tmpDir.absolutePath}/${subPath}/", relativePath)
                    destFile.parentFile.mkdirs()
                    Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }

            // Add all files to the repository
            git.add().addFilepattern(".").call()

            // Get the list of changed files
            val status = git.status().call()
            val addedFiles = status.added
            val changedFiles = status.changed

            val files : MutableList<String> = emptyList<String>().toMutableList()

            files.addAll(addedFiles)
            files.addAll(changedFiles)

            var commitDescription = "===============\n"

            files.filter { it.endsWith(".jar") }.forEach { entry ->
                println(entry.replace("${subPath}/jar/","").substringBefore("_") + "\n")
                commitDescription += entry.replace("${subPath}/jar/","").substringBefore("_") + "\n"
            }

            val currentDateTime = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val formattedDateTime = currentDateTime.format(formatter)

            val commitMessage = "Updated RL Plugins ($formattedDateTime)"

            git.commit().setMessage("$commitMessage\n\n$commitDescription").call()

            git.push().setCredentialsProvider(UsernamePasswordCredentialsProvider(token, "")).call()

        } catch (e: GitAPIException) {
            println("Error committing files: ${e.message}")
        }
    }

}

