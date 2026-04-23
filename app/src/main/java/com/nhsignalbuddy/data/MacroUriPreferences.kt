package com.nhsignalbuddy.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * (1) 단일 파일: [KEY_FILE_URI] — SAF [ACTION_OPEN_DOCUMENT]
 * (2) 폴더(트리): [KEY_TREE_URI] — [ACTION_OPEN_DOCUMENT_TREE] (kiwoom 등 안의 macro_scenario_input.json)
 * 둘 중 하나만 사용. 한쪽 저장 시 다른 쪽은 제거.
 */
class MacroUriPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedUriString(): String? =
        prefs.getString(KEY_FILE_URI, null)?.takeIf { it.isNotBlank() }

    fun getSavedUri(): Uri? = getSavedUriString()?.let { Uri.parse(it) }

    fun getTreeUriString(): String? =
        prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }

    fun getTreeUri(): Uri? = getTreeUriString()?.let { Uri.parse(it) }

    /** 외장 소스(파일 또는 트리)가 지정돼 있으면 true — 내장 안내/스낵바 분기. */
    fun hasExternalMacroSource(): Boolean =
        getSavedUriString() != null || getTreeUriString() != null

    /** 단일 json 파일. 저장 시 트리 URI 제거. */
    fun saveUri(uri: Uri) {
        prefs.edit()
            .remove(KEY_TREE_URI)
            .putString(KEY_FILE_URI, uri.toString())
            .apply()
    }

    /** Syncthing `kiwoom` 등 폴더. 저장 시 단일 파일 URI 제거. */
    fun saveTreeUri(uri: Uri) {
        prefs.edit()
            .remove(KEY_FILE_URI)
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    fun clearUri() {
        prefs.edit()
            .remove(KEY_FILE_URI)
            .remove(KEY_TREE_URI)
            .apply()
    }

    companion object {
        @Deprecated("Use KEY_FILE_URI", ReplaceWith("KEY_FILE_URI"))
        const val KEY_URI = "pref_macro_json_uri"

        const val KEY_FILE_URI = "pref_macro_json_uri"
        const val KEY_TREE_URI = "pref_macro_tree_uri"
        private const val PREFS_NAME = "nh_signal_buddy_macro"
    }
}
