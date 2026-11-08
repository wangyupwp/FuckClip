package app.fuckclip.hook

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.util.SparseArray

/** Reads an existing AOSP clipboard slot for the authorization UI only. */
internal class ReadClipboardPreview private constructor(
    private val binder: Any,
    private val callerUid: Int
) {
    fun snapshot(context: Context): ClipData? = try {
        readUnlockedOwnerClipboard(context)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun readUnlockedOwnerClipboard(context: Context): ClipData? {
        if (!canShow(context)) return null

        val ownerField = binder.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val service = ownerField.get(binder) ?: return null
        if (service.javaClass.name != CLIPBOARD_SERVICE) return null
        val storage = service.javaClass.getDeclaredField("mClipboards")
            .apply { isAccessible = true }.get(service) ?: return null
        val lock = try {
            service.javaClass.getDeclaredField("mLock").apply { isAccessible = true }.get(service)
        } catch (_: NoSuchFieldException) {
            // Android 10 guards Binder access with ClipboardImpl and its map with mClipboards.
            if (android.os.Build.VERSION.SDK_INT == 29) binder else return null
        } ?: return null

        val clip = when (storage) {
            is SparseArray<*> -> synchronized(lock) {
                synchronized(storage) { primaryClip(storage.get(OWNER_USER)) }
            }
            else -> {
                if (storage.javaClass.name != "android.util.SparseArrayMap") return null
                val intType = Int::class.javaPrimitiveType
                val resolveDevice = service.javaClass.getDeclaredMethod("getIntendingDeviceId", intType, intType)
                    .apply { isAccessible = true }
                if (resolveDevice.invoke(service, DEFAULT_DEVICE, callerUid) != DEFAULT_DEVICE) return null
                val getSlot = storage.javaClass.getMethod("get", intType, Any::class.java)
                synchronized(lock) { primaryClip(getSlot.invoke(storage, OWNER_USER, DEFAULT_DEVICE)) }
            }
        }
        return clip?.takeIf { canShow(context) }
    }

    private fun primaryClip(slot: Any?): ClipData? = slot?.let {
        it.javaClass.getDeclaredField("primaryClip").apply { isAccessible = true }.get(it) as? ClipData
    }

    companion object {
        private const val OWNER_USER = 0
        private const val DEFAULT_DEVICE = 0
        private const val CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService"
        private const val CLIPBOARD_BINDER = "$CLIPBOARD_SERVICE\$ClipboardImpl"

        fun canShow(context: Context): Boolean = try {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            keyguard != null && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
                ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) == OWNER_USER
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        fun forRequest(binder: Any?, args: List<Any?>, callerUid: Int): ReadClipboardPreview? {
            if (binder == null || binder.javaClass.name != CLIPBOARD_BINDER ||
                UserHandle.getUserHandleForUid(callerUid) != Process.myUserHandle()
            ) return null
            val userId = when (args.size) {
                2 -> args[1]
                3 -> args[2]
                4 -> if (args[3] == DEFAULT_DEVICE) args[2] else return null
                else -> return null
            }
            return if (userId == OWNER_USER) ReadClipboardPreview(binder, callerUid) else null
        }
    }
}
