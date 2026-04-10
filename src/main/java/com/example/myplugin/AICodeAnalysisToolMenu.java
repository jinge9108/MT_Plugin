package com.example.myplugin;

import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

import bin.mt.plugin.api.drawable.MaterialIcons;
import bin.mt.plugin.api.editor.BaseTextEditorToolMenu;
import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginUI;

public class AICodeAnalysisToolMenu extends BaseTextEditorToolMenu {

    @NonNull
    @Override
    public String name() {
        return "AI代码分析";
    }

    @NonNull
    @Override
    public Drawable icon() {
        return MaterialIcons.get("psychology");
    }

    @Override
    public boolean checkVisible(@NonNull TextEditor editor) {
        return true;
    }

    @Override
    public void onMenuClick(@NonNull PluginUI pluginUI, @NonNull TextEditor editor) {
        String fullText = editor.subText(0, editor.length());
        AIConversationDialog.start(pluginUI, fullText, null, editor, "AI代码分析");
    }
}
