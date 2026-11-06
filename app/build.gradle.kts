import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 签名
//
// 唯一来源是 .signing/keystore.properties（模板见 .signing/keystore.properties.example），
// 密钥库默认是同目录的 release.jks。Android Studio 与 CI 共用这一条路径。
//
// 口令文件、四个配置项、密钥库文件三者必须同时在位，缺任何一样都在配置阶段直接失败。
// 静默降级只会换来一个用本机 debug key 签名、或者干脆没签名的包，然后被 App 的运行期签名
// 自校验拒掉——而构建日志里不会留下任何提示。
// ---------------------------------------------------------------------------
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val signingProperties = rootProject.layout.projectDirectory.file(".signing/keystore.properties")

/** 走 providers.fileContents 读取，所以是 Gradle 的受管输入：换口令或换密钥库都会让配置缓存
 *  失效，不会沿用上一轮读到的旧值。文件不存在时留空，交给下面的检查统一报错。 */
val keystore = runCatching {
    providers.fileContents(signingProperties).asText.get()
}.getOrNull()
    ?.let { text -> Properties().apply { load(text.reader()) } }
    ?: Properties()

fun keystoreSetting(name: String): String? =
    keystore.getProperty(name)?.takeIf { it.isNotBlank() }

/** storeFile 相对仓库根目录解析；绝对路径（CI 用 $GITHUB_WORKSPACE 拼出来的）原样使用。 */
val signingStoreFile = keystoreSetting("storeFile")?.let { rootProject.file(it) }

val signingProblem = when {
    !signingProperties.asFile.isFile ->
        "找不到 .signing/keystore.properties：从 .signing/keystore.properties.example 复制一份并填写。"
    signingKeys.any { keystoreSetting(it) == null } ->
        ".signing/keystore.properties 必须填全：${signingKeys.joinToString(" / ")}。"
    signingStoreFile?.isFile != true ->
        ".signing/keystore.properties 里的 storeFile 指向的密钥库不存在：${signingStoreFile?.absolutePath}"
    else -> null
}
signingProblem?.let { error("签名不可用：$it") }

// 上面的检查保证这三项都有值，requireNotNull 只是把类型收敛成非空。
val keystoreFile: File = requireNotNull(signingStoreFile)
val keystorePassword = requireNotNull(keystoreSetting("storePassword"))
val keystoreAlias = requireNotNull(keystoreSetting("keyAlias"))
val keystoreKeyPassword = requireNotNull(keystoreSetting("keyPassword"))

// ---------------------------------------------------------------------------
// 版本号与提交号
//
// versionCode 完全由签出派生，不再手工维护：20000 + 提交数（单调递增），本机与 CI 一致。
// 对外展示的 versionName 用「主版本.次版本.提交数」：20072 只是构建序号，被当成
// 「第两万零七十二个版本」读起来不像正式软件，而 1.0.72 既像版本号又没丢可追溯性。
// 主/次版本是唯二手写的地方——要发 1.1 或 2.0 就改这两个常量，补丁位继续跟着提交数。
// ---------------------------------------------------------------------------
/** Runs git through providers.exec, so the result is a tracked configuration-cache input rather
 * than an untracked process launch. Null when git is missing or this is not a checkout. */
fun gitOutput(vararg args:String):String? = runCatching {
    providers.exec{commandLine("git",*args);isIgnoreExitValue=true}.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf{it.isNotBlank()}

val gitCommitCount = gitOutput("rev-list","--count","HEAD")?.toIntOrNull()

/** 手写的两位，其余全部派生。 */
val versionMajor = 1
val versionMinor = 0

/** 20000 + 提交数，保证 versionCode 在任何机器、任何 CI 上都单调且一致。 */
val releaseVersionCode = (gitCommitCount ?: 1)+20000
val releaseVersionName = "$versionMajor.$versionMinor."+(gitCommitCount ?: 1)

/** 提交号优先取 CI 环境变量，其次读签出本身，都不是 git 仓库时回退 local。 */
val buildCommit = providers.environmentVariable("COMMIT_SHA").orNull?.takeIf { it.isNotBlank() }
    ?: gitOutput("rev-parse","HEAD")?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }
    ?: "local"

// ---------------------------------------------------------------------------
// 签名指纹
// ---------------------------------------------------------------------------
/** 真正给这个包签名的证书的 SHA-256。
 *
 * 声明成 ValueSource 是为了让 Gradle 把密钥库当构建输入追踪：换密钥会让配置缓存失效，
 * 而不是悄悄沿用上一轮的旧指纹。这里现算而不是签入一份固定值，所以 release 包不可能带上
 * 一份和自己的签名对不上的指纹。 */
abstract class SigningCertificateDigest : ValueSource<String, SigningCertificateDigest.Parameters> {
    interface Parameters : ValueSourceParameters {
        @get:InputFile
        val storeFile: RegularFileProperty

        @get:Input
        val storePassword: Property<String>

        @get:Input
        val keyAlias: Property<String>
    }

    override fun obtain(): String {
        val store = parameters.storeFile.get().asFile
        val password = parameters.storePassword.get()
        val alias = parameters.keyAlias.get()
        val types = if (store.extension.equals("p12", true) || store.extension.equals("pfx", true))
            listOf("PKCS12", "JKS") else listOf("JKS", "PKCS12")
        for (type in types) {
            val digest = runCatching {
                val keyStore = KeyStore.getInstance(type)
                store.inputStream().use { keyStore.load(it, password.toCharArray()) }
                keyStore.getCertificate(alias)?.let {
                    MessageDigest.getInstance("SHA-256")
                        .digest(it.encoded).joinToString("") { byte -> "%02x".format(byte) }
                }
            }.getOrNull()
            if (digest != null) return digest
        }
        return ""
    }
}

/** 逐字符 XOR 0x5A，免得指纹能在打好的 APK 里被当文本搜出来。 */
fun encodeFingerprint(fingerprint: String): String = fingerprint
    .lowercase().map { (it.code xor 0x5a).toString(16).padStart(2, '0') }.joinToString("")

val signingFingerprint: String = run {
    val digest = providers.of(SigningCertificateDigest::class) {
        parameters {
            storeFile.set(keystoreFile)
            storePassword.set(keystorePassword)
            keyAlias.set(keystoreAlias)
        }
    }.get()
    check(digest.isNotBlank()) { "密钥库没读出证书指纹：${keystoreFile.absolutePath}" }
    encodeFingerprint(digest)
}

// ---------------------------------------------------------------------------
// Android
// ---------------------------------------------------------------------------
android {
    namespace = "io.github.iamr0s.carvex"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            keyPassword = keystoreKeyPassword
            // AGP 9 默认只开 v2，v3 是关的，必须显式打开：签名校验（开源版本已移除自校验，但保留 v3 产物）
            // verifiedFromSigningHistory 要靠 v3 带出的 proof-of-rotation 记录。
            // minSdk 29 用不到 v1；v4 是增量安装用的，这里不产出。
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.baidu.carlife.xiaomi"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "COMMIT", "\"$buildCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "brand"
    productFlavors {
        create("xiaomi") { dimension = "brand"; applicationId = "com.baidu.carlife.xiaomi" }
        create("oppo") { dimension = "brand"; applicationId = "com.baidu.carlife.oppo" }
        create("vivo") { dimension = "brand"; applicationId = "com.baidu.carlife.vivo" }
        create("samsung") { dimension = "brand"; applicationId = "com.baidu.carlife.samsung" }
        create("honor") { dimension = "brand"; applicationId = "com.baidu.carlife.honor" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            // 与 release 共用同一把密钥：同一台设备上两者互相覆盖时才不会签名冲突。
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            // 与 release 相同的 pin：debug 也用这把密钥签，签名自校验在这里同样生效。
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // kotlinx-coroutines 的调试探针，release 用不到，去掉少一个可被识别的包内资源。
        resources.excludes += "DebugProbesKt.bin"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

/** Publish the signed bundle under its distribution name without changing its bytes/signature. */
abstract class NameBundleTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    @TaskAction
    fun copyBundle() {
        val target = outputBundle.get().asFile
        target.parentFile.mkdirs()
        inputBundle.get().asFile.copyTo(target, overwrite = true)
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val brand = variant.productFlavors.single { it.first == "brand" }.second
        val artifactName = "CarVex_${brand}_${variant.buildType}_$releaseVersionName"
        variant.outputs.forEach { output ->
            output.outputFileName.set("$artifactName.apk")
        }
        val nameBundle = tasks.register<NameBundleTask>("name${variant.name.replaceFirstChar { it.uppercaseChar() }}Bundle")
        variant.artifacts.use(nameBundle)
            .wiredWithFiles(NameBundleTask::inputBundle, NameBundleTask::outputBundle)
            .withName("$artifactName.aab")
            .toTransform(SingleArtifact.BUNDLE)
    }
}

dependencies {
    // 编排与协议实现；:app 直接依赖 :bridge，不再经过兼容壳模块。
    implementation(project(":bridge"))
    // 宿主注册表与各生态插件：只有本模块（组装层）认识具体宿主。
    implementation(project(":host-api"))
    implementation(project(":host-carlife-mixing"))
    // 依赖注入：只在组装层使用，库模块不启动 Koin。
    implementation(libs.koin.android)
    // 没有系统权限的机器上，用它读本机蓝牙 MAC（Settings.Secure.bluetooth_address）。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // 页面切换用 Navigation-Compose：自带平台习惯的转场动画，并且自动接管
    // 系统返回键与预测式返回（predictive back）。之前试过自己写转场，
    // 那条路要维护返回栈 + 手动驱动动画，得不偿失，已回退。
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    // 首页两段列表的去重/排序规则有单测（app/src/test），需要 JUnit。
    // 之前 app 模块没有 test 源集，CI 里的 :app:testDebugUnitTest 是空操作。
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
