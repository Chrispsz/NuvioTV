package com.nuvio.tv.updater

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File
import java.security.MessageDigest

object ApkInstaller {

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    }

    /**
     * Installs [apkFile] as an update of this app through a PackageInstaller session, adding the APK's own dex
     * metadata so the new version is compiled at install time. A [background] install asks the system to skip the
     * confirmation screen, which Android 12+ allows for an app updating itself; elsewhere the system still asks.
     * The outcome arrives in [UpdateInstallResultReceiver].
     */
    fun install(
        context: Context,
        apkFile: File,
        background: Boolean,
        withDexMetadata: Boolean = true
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Sessions default to internal-only, which the system rejects for an app the user moved to adopted
            // storage. Auto keeps the update on the volume the app is already on.
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            if (background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val dexMetadata = if (withDexMetadata) {
            UpdateDexMetadata.extract(apkFile, Build.VERSION.SDK_INT, File(apkFile.parentFile, "update.dm"))
        } else {
            null
        }
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.writeFile("base.apk", apkFile)
                dexMetadata?.let { session.writeFile("base.dm", it) }
                session.commit(
                    UpdateInstallResultReceiver.statusReceiver(
                        context = context,
                        sessionId = sessionId,
                        apkFile = apkFile,
                        background = background,
                        withDexMetadata = dexMetadata != null
                    ).intentSender
                )
            }
        } catch (e: Exception) {
            packageInstaller.abandonSession(sessionId)
            throw e
        } finally {
            dexMetadata?.delete()
        }
    }

    /** Hands the APK to the system installer screen, used when the session install cannot be completed. */
    fun launchSystemInstaller(context: Context, apkFile: File) {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    // ── Reinstalação guiada (conflito de assinatura) ────────────────────────
    //
    // O Android bloqueia instalar por cima um APK assinado com chave diferente
    // ("pacote em conflito com um pacote já existente" / INSTALL_FAILED_UPDATE
    // _INCOMPATIBLE). Detectamos o caso ANTES de chamar o instalador e conduzimos
    // o usuário: copia o APK para Downloads (sobrevive à desinstalação) e abre a
    // tela de desinstalação do sistema.

    /** SHA-256 da assinatura do app em execução, ou null se não detectável. */
    fun installedSignatureFingerprint(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256Hex)
            }.getOrNull()
        }
        @Suppress("DEPRECATION")
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            info.signatures?.firstOrNull()?.toByteArray()?.let(::sha256Hex)
        }.getOrNull()
    }

    /** SHA-256 da assinatura de um APK em disco (o baixado), ou null se não detectável. */
    fun apkSignatureFingerprint(context: Context, apkPath: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val info = context.packageManager.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256Hex)
        }.getOrNull()
    }

    /** Abre a tela do sistema para desinstalar o próprio app (1º passo da reinstalação guiada). */
    fun launchSelfUninstall(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Copia o APK para a pasta Downloads do usuário (via MediaStore, sem permissão
     * no Android 10+). O arquivo sobrevive à desinstalação do app, permitindo
     * reinstalar manualmente em Arquivos → Downloads.
     */
    fun copyApkToDownloads(context: Context, apkFile: File, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out ->
                apkFile.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun PackageInstaller.Session.writeFile(name: String, file: File) {
        file.inputStream().use { input ->
            openWrite(name, 0, file.length()).use { output ->
                input.copyTo(output)
                fsync(output)
            }
        }
    }
}
