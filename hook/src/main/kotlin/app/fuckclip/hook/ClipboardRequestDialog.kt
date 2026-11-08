package app.fuckclip.hook

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.fuckclip.hook.dialog.DialogLocale
import app.fuckclip.hook.dialog.DialogStrings
import app.fuckclip.hook.dialog.DialogThemePalette
import app.fuckclip.hook.dialog.MaxHeightScrollView
import app.fuckclip.hook.dialog.createCardBackground
import app.fuckclip.hook.dialog.createRippleDrawable
import app.fuckclip.hook.dialog.createStyledButton

/** Backward-compatible typealias for internal dialog locale resolution. */
internal typealias DialogLocale = app.fuckclip.hook.dialog.DialogLocale

/** Backward-compatible typealias for dialog localized strings. */
internal typealias Strings = app.fuckclip.hook.dialog.DialogStrings

/**
 * System alert dialog asking the user to allow or deny clipboard access for an application.
 *
 * 在 system_server 进程中弹出的系统级悬浮对话框，询问用户放行或拒绝应用的剪贴板操作。
 */
internal class ClipboardRequestDialog(
    context: Context,
    preferredLanguage: String = "",
    colorMode: Int = 0,
    keyColor: Int = 0
) : Dialog(context, THEME) {

    private val isNight: Boolean = when (colorMode) {
        1 -> false // Light
        2, 3 -> true // Dark, AMOLED
        else -> (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private val dialogLocale: DialogLocale = DialogLocale.resolve(preferredLanguage)
    private val strings: DialogStrings = DialogStrings.load(context, dialogLocale)
    private val palette = DialogThemePalette(isNight, keyColor)

    // Header views
    private val appIconFrame: FrameLayout
    private val appIconView: ImageView
    private val appNameView: TextView
    private val packageNameView: TextView
    private val actionBadgeView: TextView

    // Preview views
    private val promptNoticeView: TextView
    private val previewContainer: LinearLayout
    private val previewHeaderLabel: TextView
    private val previewCountLabel: TextView
    private val previewBodyText: TextView

    // Remember choice views
    private val rememberRow: LinearLayout
    private val rememberCheckBox: CheckBox
    private val rememberTitleView: TextView
    private val rememberSubtitleView: TextView

    // Action buttons
    private val denyButton: Button
    private val allowButton: Button

    private var onDecisionCallback: ((granted: Boolean, remember: Boolean) -> Unit)? = null
    private var decisionHandled = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        configureWindowAttributes(this)

        window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.55f)
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setWindowAnimations(android.R.style.Animation_Dialog)
        }

        // --- Root Card Container ---
        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground(
                fillColor = palette.surfaceBg,
                strokeColor = palette.surfaceBorder,
                radiusPx = dp(24f),
                strokeWidthPx = dp(1f)
            )
            val padding = dp(20f)
            setPadding(padding, padding, padding, padding)
            elevation = dp(10f).toFloat()
        }

        // 1. Header (Icon + App Title + Package Name + Action Badge)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        appIconFrame = FrameLayout(context).apply {
            val size = dp(46f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(12f)
            }
            background = createCardBackground(
                fillColor = palette.iconBg,
                strokeColor = palette.iconBorder,
                radiusPx = dp(13f),
                strokeWidthPx = dp(1f)
            )
        }
        appIconView = ImageView(context).apply {
            val iconSize = dp(34f)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        appIconFrame.addView(appIconView)
        headerRow.addView(appIconFrame)

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8f)
            }
        }
        appNameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            setTextColor(palette.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        packageNameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(palette.textSecondary)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2f)
            }
        }
        titleContainer.addView(appNameView)
        titleContainer.addView(packageNameView)
        headerRow.addView(titleContainer)

        actionBadgeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            val padH = dp(8f)
            val padV = dp(3f)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
        }
        headerRow.addView(actionBadgeView)
        rootCard.addView(headerRow)

        // 2. Middle Scrollable Content (Notice + Preview Card)
        val middleScrollView = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(14f)
                bottomMargin = dp(14f)
            }
        }
        val middleContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        promptNoticeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.textSecondary)
            setLineSpacing(0f, 1.15f)
        }
        middleContent.addView(promptNoticeView)

        // Preview card
        previewContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground(
                fillColor = palette.previewBg,
                strokeColor = palette.previewBorder,
                radiusPx = dp(12f),
                strokeWidthPx = dp(1f)
            )
            val pad = dp(12f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
        }
        val previewHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewHeaderLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(palette.accentColor)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        previewCountLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(palette.textTertiary)
        }
        previewHeaderRow.addView(previewHeaderLabel)
        previewHeaderRow.addView(previewCountLabel)
        previewContainer.addView(previewHeaderRow)

        val previewScrollView = MaxHeightScrollView(context, dp(110f)).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6f)
            }
        }
        previewBodyText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.textPrimary)
            setLineSpacing(0f, 1.18f)
            setTextIsSelectable(false)
        }
        previewScrollView.addView(previewBodyText)
        previewContainer.addView(previewScrollView)
        middleContent.addView(previewContainer)

        // Remember row
        rememberRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRippleDrawable(
                normalColor = palette.previewBg,
                pressedColor = palette.rememberPressed,
                radiusPx = dp(10f),
                strokeColor = palette.previewBorder,
                strokeWidthPx = dp(1f)
            )
            val padH = dp(12f)
            val padV = dp(9f)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }
        rememberCheckBox = CheckBox(context).apply {
            buttonTintList = ColorStateList.valueOf(palette.accentColor)
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8f)
            }
        }
        val rememberTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rememberTitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            text = strings.rememberTitle
        }
        rememberSubtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(palette.textSecondary)
            text = strings.rememberSubtitle
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(1f)
            }
        }
        rememberTextCol.addView(rememberTitleView)
        rememberTextCol.addView(rememberSubtitleView)
        rememberRow.addView(rememberCheckBox)
        rememberRow.addView(rememberTextCol)
        rememberRow.setOnClickListener {
            rememberCheckBox.isChecked = !rememberCheckBox.isChecked
        }
        middleContent.addView(rememberRow)

        middleScrollView.addView(middleContent)
        rootCard.addView(middleScrollView)

        // 3. Action Buttons (Deny / Allow)
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        denyButton = createStyledButton(
            context = context,
            bgColor = palette.denyBtnBg,
            pressedColor = palette.denyBtnPressed,
            strokeColor = palette.denyBtnBorder,
            textColor = palette.denyBtnFg,
            label = strings.deny,
            radiusPx = dp(22f),
            strokeWidthPx = dp(1f)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply {
                marginEnd = dp(10f)
            }
            setOnClickListener {
                dispatchDecision(granted = false)
            }
        }

        allowButton = createStyledButton(
            context = context,
            bgColor = palette.allowBtnBg,
            pressedColor = palette.allowBtnPressed,
            strokeColor = 0,
            textColor = palette.allowBtnFg,
            label = strings.allow,
            radiusPx = dp(22f),
            strokeWidthPx = dp(1f)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f)
            setOnClickListener {
                dispatchDecision(granted = true)
            }
        }

        buttonRow.addView(denyButton)
        buttonRow.addView(allowButton)
        rootCard.addView(buttonRow)

        setContentView(rootCard)

        setOnCancelListener {
            dispatchDecision(granted = false)
        }
        setOnDismissListener {
            if (!decisionHandled) {
                dispatchDecision(granted = false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window?.let { win ->
            val dm = context.resources.displayMetrics
            val widthPx = (dm.widthPixels * 0.88f).toInt().coerceAtMost(dp(390f))
            win.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.CENTER)
        }
    }

    /**
     * Binds application details and request metadata to the dialog views.
     *
     * 将应用程序详情与请求元数据绑定至对话框视图。
     */
    fun bindRequest(
        appLabel: CharSequence,
        packageName: String,
        appIcon: Drawable?,
        isWrite: Boolean,
        isClear: Boolean,
        clipContent: CharSequence?,
        callback: (granted: Boolean, remember: Boolean) -> Unit
    ) {
        this.onDecisionCallback = callback
        this.decisionHandled = false

        appNameView.text = if (appLabel.isBlank()) strings.unknownApp else appLabel
        packageNameView.text = packageName

        if (appIcon != null) {
            appIconView.setImageDrawable(appIcon)
            appIconFrame.visibility = View.VISIBLE
        } else {
            appIconFrame.visibility = View.GONE
        }

        if (isWrite) {
            actionBadgeView.text = strings.badgeWrite
            actionBadgeView.setTextColor(palette.badgeWriteFg)
            actionBadgeView.background = createCardBackground(
                fillColor = palette.badgeWriteBg,
                strokeColor = palette.badgeWriteBorder,
                radiusPx = dp(8f),
                strokeWidthPx = dp(1f)
            )

            promptNoticeView.text = if (isClear) strings.promptNoticeClear else strings.promptNoticeWrite
            previewHeaderLabel.text = strings.previewHeaderWrite
        } else {
            actionBadgeView.text = strings.badgeRead
            actionBadgeView.setTextColor(palette.badgeReadFg)
            actionBadgeView.background = createCardBackground(
                fillColor = palette.badgeReadBg,
                strokeColor = palette.badgeReadBorder,
                radiusPx = dp(8f),
                strokeWidthPx = dp(1f)
            )

            promptNoticeView.text = strings.promptNoticeRead
            previewHeaderLabel.text = strings.previewHeaderRead
        }

        if (!isWrite) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        previewContainer.visibility = if (!isClear && (isWrite || !clipContent.isNullOrEmpty())) View.VISIBLE else View.GONE
        val text = clipContent?.toString()
        if (text.isNullOrEmpty()) {
            previewBodyText.text = strings.previewEmpty
            previewBodyText.setTextColor(palette.textTertiary)
            previewCountLabel.text = ""
        } else {
            previewCountLabel.text = String.format(strings.previewCharsFormat, text.length)
            previewBodyText.text = if (text.length > 500) {
                text.substring(0, 500) + "\n... (" + String.format(strings.previewCharsFormat, text.length) + ")"
            } else {
                text
            }
            previewBodyText.setTextColor(palette.textPrimary)
        }
    }

    private fun dispatchDecision(granted: Boolean) {
        if (decisionHandled) return
        decisionHandled = true
        val remember = rememberCheckBox.isChecked
        dismiss()
        onDecisionCallback?.invoke(granted, remember)
    }

    private fun dp(value: Float): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    internal companion object {
        /**
         * Resource ID of the base dialog theme.
         *
         * 基础对话框所使用的主题资源 ID。
         */
        const val THEME = android.R.style.Theme_Material_Light_Dialog_NoActionBar_MinWidth

        /**
         * Resolves the appropriate [DialogLocale] for the given [preferredLanguage] tag.
         *
         * 为指定的 [preferredLanguage] 语言标签解析匹配的 [DialogLocale]。
         */
        fun resolveLocale(preferredLanguage: String): DialogLocale = DialogLocale.resolve(preferredLanguage)

        /**
         * Configures system alert window attributes and flags on [dialog].
         *
         * 为 [dialog] 配置系统级悬浮弹窗窗口属性与标记位。
         */
        fun configureWindowAttributes(dialog: Dialog) {
            val win = dialog.window ?: return
            win.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            win.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            val attrs = win.attributes
            attrs.title = "FuckClipRequestDialog"
            win.attributes = attrs
        }
    }
}
