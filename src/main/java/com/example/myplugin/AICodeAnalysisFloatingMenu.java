package com.example.myplugin;

import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

import com.example.myplugin.util.AIHelper;

import bin.mt.plugin.api.drawable.MaterialIcons;
import bin.mt.plugin.api.editor.BaseTextEditorFloatingMenu;
import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginUI;

public class AICodeAnalysisFloatingMenu extends BaseTextEditorFloatingMenu {

    @NonNull
    @Override
    public String name() {
        return "AI快速分析";
    }

    @NonNull
    @Override
    public Drawable icon() {
        return MaterialIcons.get("psychology");
    }

    @Override
    public boolean checkVisible(@NonNull TextEditor editor) {
        return editor.hasTextSelected();
    }

    @Override
    public void onMenuClick(@NonNull PluginUI pluginUI, @NonNull TextEditor editor) {
        int start = editor.getSelectionStart();
        int end = editor.getSelectionEnd();
        String selectedText = editor.subText(start, end);

        if (selectedText.trim().isEmpty()) {
            pluginUI.showToast("请先选择要分析的代码");
            return;
        }
        String shortPrompt = AIHelper.getShortPrompt(pluginUI.getContext());
        AIConversationDialog.start(pluginUI, selectedText, shortPrompt, editor, "AI快速分析");
    }
}
