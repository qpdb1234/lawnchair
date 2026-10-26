package app.lawnchair.icons

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.BitmapFactory
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Icon pack backed by user-imported images stored on disk under
 * [iconDir]. Each stored image is a normalized square PNG whose file name is
 * used as the [IconEntry.name]. This lets arbitrary images picked from the
 * gallery, file manager or other apps be used as app icons.
 */
class CustomImageIconPack(context: Context, packPackageName: String) : IconPack(context, packPackageName) {

    override val label = context.getString(R.string.custom_icons_label)

    init {
        startLoad()
    }

    override fun getIcon(componentName: ComponentName): IconEntry? = null
    override fun getCalendar(componentName: ComponentName): IconEntry? = null
    override fun getClock(entry: IconEntry): ClockMetadata? = null

    override fun getCalendars(): MutableSet<ComponentName> = mutableSetOf()
    override fun getClocks(): MutableSet<ComponentName> = mutableSetOf()

    override fun getIcon(iconEntry: IconEntry, iconDpi: Int): Drawable? {
        val file = File(iconDir(context), iconEntry.name)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    override fun getAllIcons(): Flow<List<IconPickerCategory>> = flow {
        val dir = iconDir(context)
        val items = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".png") }
            ?.map { file ->
                IconPickerItem(
                    packPackageName = PACK_PACKAGE_NAME,
                    drawableName = file.name,
                    label = file.nameWithoutExtension,
                    type = IconType.Custom,
                )
            }
            ?.sortedBy { it.label }
            .orEmpty()
        emit(categorize(items))
    }.flowOn(Dispatchers.IO)

    override fun loadInternal() {
    }

    companion object {
        // Reserved sentinel used as IconPickerItem.packPackageName for user-imported images.
        const val PACK_PACKAGE_NAME = "app.lawnchair.custom_icons"

        fun iconDir(context: Context): File =
            File(context.filesDir, "custom_icons").apply { if (!exists()) mkdirs() }
    }
}
