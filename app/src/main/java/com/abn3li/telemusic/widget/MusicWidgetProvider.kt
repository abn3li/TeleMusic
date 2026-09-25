package com.abn3li.telemusic.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Shared by the three sizes: any add/remove/redraw request just redraws from the current
 * state (see [MusicWidgets]). No periodic updates - updatePeriodMillis is 0. */
open class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        MusicWidgets.onWidgetsChanged(context)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = MusicWidgets.onWidgetsChanged(context)
    override fun onEnabled(context: Context) = MusicWidgets.onWidgetsChanged(context)
    override fun onDisabled(context: Context) = MusicWidgets.onWidgetsChanged(context)
}

class SmallMusicWidget : MusicWidgetProvider()
class MediumMusicWidget : MusicWidgetProvider()
class LargeMusicWidget : MusicWidgetProvider()
