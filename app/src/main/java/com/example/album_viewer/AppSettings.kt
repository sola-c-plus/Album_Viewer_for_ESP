package com.example.album_viewer

import android.content.Context

/**
 * アプリ設定の保存先。
 *
 * これまでアプリ内に設定の永続化の仕組みは無かったため、承認通知の文言差し替えで
 * 使う「アシスタント名」のフォールバック用に、最小限の SharedPreferences
 * ラッパーをここに置く。読み書きはここ経由に統一する。
 */
object AppSettings {
    private const val PREFS_NAME = "album_viewer_settings"
    private const val KEY_ASSISTANT_NAME = "assistant_name"

    /** 設定されたアシスタント名。未設定・空欄なら "" を返す。 */
    fun getAssistantName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ASSISTANT_NAME, "")?.trim().orEmpty()
    }

    fun setAssistantName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ASSISTANT_NAME, name.trim()).apply()
    }
}
