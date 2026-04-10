package com.example.myplugin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myplugin.core.ToolManager;
import com.example.myplugin.util.AIHelper;

import org.json.JSONArray;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginEditText;
import bin.mt.plugin.api.ui.PluginEditTextWatcher;
import bin.mt.plugin.api.ui.PluginUI;
import bin.mt.plugin.api.ui.PluginView;
import bin.mt.plugin.api.ui.dialog.PluginDialog;

public class AIConversationDialog {
    private static AIConversationDialog activeSession;
    private static final String ANALYSIS_ONLY_INSTRUCTION = "当前请求仅做分析，不要调用任何工具。";

    private final PluginUI pluginUI;
    private final String code;
    private final String customPrompt;
    private final String title;
    private final TextEditor editor;
    private final StringBuilder chatHistory = new StringBuilder();
    private final ToolManager toolManager = new ToolManager();

    private JSONArray messages;
    private PluginEditText historyEdit;
    private PluginEditText inputEdit;
    private PluginDialog conversationDialog;
    private PluginDialog miniDialog;
    private volatile boolean sending;
    private volatile boolean stopRequested;
    private Thread requestThread;
    private boolean ignoreInputWatcher;
    private String liveReasoning = "";
    private String liveContent = "";
    private String draftInput = "";

    private AIConversationDialog(
            @NonNull PluginUI pluginUI,
            @NonNull String code,
            @Nullable String customPrompt,
            @Nullable TextEditor editor,
            @NonNull String title) {
        this.pluginUI = pluginUI;
        this.code = code;
        this.customPrompt = customPrompt;
        this.editor = editor;
        this.title = title;
        registerTools();
    }

    private void registerTools() {
        toolManager.registerTool("replace_selection",
                "替换文本。参数: content (新内容，和 replacement 二选一), replacement (新内容), target (要替换的原文本，可选), occurrence (第几次出现，默认1)。如果当前有选区，则优先替换选区；如果没有选区，则按 target 精确查找替换。禁止用空内容执行删除。",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String content = tool.params.get("content");
                    String replacement = tool.params.get("replacement");
                    String newText = replacement != null ? replacement : content;
                    if (newText == null) {
                        return new ToolManager.ToolResult(tool.name, false, "缺少 content 或 replacement 参数");
                    }
                    if (newText.isEmpty()) {
                        return new ToolManager.ToolResult(tool.name, false,
                                "replace_selection 不支持空内容删除，请使用 delete_text 或 delete_in_method");
                    }

                    int start = editor.getSelectionStart();
                    int end = editor.getSelectionEnd();

                    if (start != end) {
                        AIHelper.runOnMainThread(() -> {
                            editor.replaceText(start, end, newText);
                            editor.setSelection(start, start + newText.length());
                            editor.ensureSelectionVisible(true);
                        });
                        return new ToolManager.ToolResult(tool.name, true,
                                "成功替换当前选中区域，新长度: " + newText.length());
                    }

                    String target = tool.params.get("target");
                    if (target == null || target.isEmpty()) {
                        return new ToolManager.ToolResult(tool.name, false,
                                "当前没有选区，且缺少 target 参数，无法确定要替换的位置");
                    }

                    int occ = 1;
                    try {
                        occ = Integer.parseInt(tool.params.getOrDefault("occurrence", "1"));
                    } catch (Exception ignored) {
                    }

                    String fullText = editor.subText(0, editor.length());
                    int index = findTextIndexSmart(fullText, target, occ);
                    if (index < 0) {
                        return new ToolManager.ToolResult(tool.name, false,
                                "未找到 target 指定的文本: " + target);
                    }

                    AIHelper.runOnMainThread(() -> {
                        editor.replaceText(index, index + target.length(), newText);
                        editor.setSelection(index, index + newText.length());
                        editor.ensureSelectionVisible(true);
                    });

                    return new ToolManager.ToolResult(tool.name, true,
                            "已自动定位并替换第 " + occ + " 处目标文本，范围: "
                                    + index + "-" + (index + target.length()));
                });

        toolManager.registerTool("find_text",
                "在文件中查找文本并定位。参数: query (查找内容), occurrence (第几次出现，默认1)",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String query = tool.params.get("query");
                    if (query == null) {
                        return new ToolManager.ToolResult(tool.name, false, "缺少 query 参数");
                    }

                    int occ = 1;
                    try {
                        occ = Integer.parseInt(tool.params.getOrDefault("occurrence", "1"));
                    } catch (Exception ignored) {
                    }

                    String fullText = editor.subText(0, editor.length());
                    int index = findTextIndexSmart(fullText, query, occ);
                    if (index < 0) {
                        return new ToolManager.ToolResult(tool.name, false, "未找到目标文本: " + query);
                    }

                    final int finalIndex = index;
                    final int finalOcc = occ;
                    AIHelper.runOnMainThread(() -> {
                        editor.setSelection(finalIndex, finalIndex + query.length());
                        editor.ensureSelectionVisible(true);
                    });
                    return new ToolManager.ToolResult(tool.name, true,
                            "已定位到第 " + finalOcc + " 处匹配，偏移量: " + finalIndex);
                });

        toolManager.registerTool("select_method",
                "查找并选中 Smali 或 Java 方法。参数: method (方法名或签名)",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String methodName = tool.params.get("method");
                    if (methodName == null) {
                        return new ToolManager.ToolResult(tool.name, false, "缺少 method 参数");
                    }

                    String fullText = editor.subText(0, editor.length());
                    Range range = findMethodRange(fullText, methodName, null, 1);
                    if (range == null) {
                        return new ToolManager.ToolResult(tool.name, false, "未找到方法: " + methodName);
                    }

                    AIHelper.runOnMainThread(() -> {
                        editor.setSelection(range.start, range.end);
                        editor.ensureSelectionVisible(true);
                    });
                    return new ToolManager.ToolResult(tool.name, true,
                            "已选中方法 " + methodName + "，范围: " + range.start + "-" + range.end);
                });

        toolManager.registerTool("replace_text",
                "仅替换指定文本片段，而不是整个方法。参数: target (原文本), replacement (新文本), occurrence (第几次出现，默认1)",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String target = tool.params.get("target");
                    String replacement = tool.params.get("replacement");
                    if (target == null || replacement == null) {
                        return new ToolManager.ToolResult(tool.name, false, "缺少 target 或 replacement 参数");
                    }

                    int occ = 1;
                    try {
                        occ = Integer.parseInt(tool.params.getOrDefault("occurrence", "1"));
                    } catch (Exception ignored) {
                    }

                    String fullText = editor.subText(0, editor.length());
                    int index = findTextIndexSmart(fullText, target, occ);
                    if (index < 0) {
                        return new ToolManager.ToolResult(tool.name, false, "未找到目标片段: " + target);
                    }

                    AIHelper.runOnMainThread(() -> {
                        editor.replaceText(index, index + target.length(), replacement);
                        editor.setSelection(index, index + replacement.length());
                        editor.ensureSelectionVisible(true);
                    });

                    return new ToolManager.ToolResult(tool.name, true,
                            "已替换第 " + occ + " 处目标片段，范围: "
                                    + index + "-" + (index + target.length()));
                });

        toolManager.registerTool("replace_in_method",
                "仅在指定方法体内部替换最小必要代码片段，禁止用于整体替换整个方法。参数: method (方法名或签名), target (原文本), replacement (新文本), occurrence (方法内第几次出现，默认1)",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String method = tool.params.get("method");
                    String target = tool.params.get("target");
                    String replacement = tool.params.get("replacement");
                    if (method == null || target == null || replacement == null) {
                        return new ToolManager.ToolResult(tool.name, false,
                                "缺少 method、target 或 replacement 参数");
                    }

                    int occ = 1;
                    try {
                        occ = Integer.parseInt(tool.params.getOrDefault("occurrence", "1"));
                    } catch (Exception ignored) {
                    }

                    String fullText = editor.subText(0, editor.length());
                    Range methodRange = findMethodRange(fullText, method, null, 1);
                    if (methodRange == null) {
                        return new ToolManager.ToolResult(tool.name, false, "未找到方法: " + method);
                    }

                    String methodText = fullText.substring(methodRange.start, methodRange.end);
                    int localIndex = findTextIndexSmart(methodText, target, occ);
                    if (localIndex < 0) {
                        return new ToolManager.ToolResult(tool.name, false,
                                "在方法内未找到目标片段: " + target);
                    }

                    int globalStart = methodRange.start + localIndex;
                    int globalEnd = globalStart + target.length();

                    AIHelper.runOnMainThread(() -> {
                        editor.replaceText(globalStart, globalEnd, replacement);
                        editor.setSelection(globalStart, globalStart + replacement.length());
                        editor.ensureSelectionVisible(true);
                    });

                    return new ToolManager.ToolResult(tool.name, true,
                            "已在方法 " + method + " 内替换片段，范围: "
                                    + globalStart + "-" + globalEnd);
                });


        toolManager.registerTool("delete_text",
                "删除指定代码片段。参数: target (要删除的原文本), occurrence (第几次出现，默认1)",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String target = tool.params.get("target");
                    if (target == null || target.isEmpty()) {
                        return new ToolManager.ToolResult(tool.name, false, "缺少 target 参数");
                    }

                    int occ = 1;
                    try {
                        occ = Integer.parseInt(tool.params.getOrDefault("occurrence", "1"));
                    } catch (Exception ignored) {
                    }

                    String fullText = editor.subText(0, editor.length());
                    int index = findTextIndexSmart(fullText, target, occ);
                    if (index < 0) {
                        return new ToolManager.ToolResult(tool.name, false, "未找到要删除的目标片段: " + target);
                    }

                    AIHelper.runOnMainThread(() -> {
                        editor.replaceText(index, index + target.length(), "");
                        editor.setSelection(Math.max(0, index), Math.max(0, index));
                        editor.ensureSelectionVisible(true);
                    });

                    return new ToolManager.ToolResult(tool.name, true,
                            "已删除第 " + occ + " 处目标片段，范围: "
                                    + index + "-" + (index + target.length()));
                });

        toolManager.registerTool("delete_in_method",
                "仅在指定方法体内部删除代码片段，不删除整个方法。参数: method (方法名或签名), target (要删除的原文本), occurrence (方法内第几次出现，默认1)",
                tool -> {
                    if (editor == null) {
                        return new ToolManager.ToolResult(tool.name, false, "当前没有可用的编辑器");
                    }

                    String method = tool.params.get("method");
                    String target = tool.params.get("target");

                    if (method == null || method.isEmpty() || target == null || target.isEmpty()) {
                        return new ToolManager.ToolResult(tool.name, false, "缺少 method 或 target 参数");
                    }

                    int occ = 1;
                    try {
                        occ = Integer.parseInt(tool.params.getOrDefault("occurrence", "1"));
                    } catch (Exception ignored) {
                    }

                    String fullText = editor.subText(0, editor.length());
                    Range methodRange = findMethodRange(fullText, method, null, 1);
                    if (methodRange == null) {
                        return new ToolManager.ToolResult(tool.name, false, "未找到方法: " + method);
                    }

                    String methodText = fullText.substring(methodRange.start, methodRange.end);
                    int localIndex = findTextIndexSmart(methodText, target, occ);
                    if (localIndex < 0) {
                        return new ToolManager.ToolResult(tool.name, false,
                                "在方法内未找到要删除的目标片段: " + target);
                    }

                    int globalStart = methodRange.start + localIndex;
                    int globalEnd = globalStart + target.length();

                    AIHelper.runOnMainThread(() -> {
                        editor.replaceText(globalStart, globalEnd, "");
                        editor.setSelection(Math.max(0, globalStart), Math.max(0, globalStart));
                        editor.ensureSelectionVisible(true);
                    });

                    return new ToolManager.ToolResult(tool.name, true,
                            "已在方法 " + method + " 内删除目标片段，范围: "
                                    + globalStart + "-" + globalEnd);
                });
    }

    public static void start(
            @NonNull PluginUI pluginUI,
            @NonNull String code,
            @Nullable String customPrompt,
            @Nullable TextEditor editor,
            @NonNull String title) {
        try {
            if (activeSession != null) {
                activeSession.restoreFromEntry();
                pluginUI.showToast("已恢复后台会话");
                return;
            }

            AIConversationDialog controller =
                    new AIConversationDialog(pluginUI, code, customPrompt, editor, title);
            activeSession = controller;
            controller.buildAndShow();
            controller.startInitialAnalysis();
        } catch (Exception e) {
            pluginUI.showToast("初始化会话失败: " + e.getMessage());
        }
    }

    private void buildAndShow() throws Exception {
        if (miniDialog != null && miniDialog.isShowing()) {
            miniDialog.dismiss();
        }

        if (messages == null) {
            messages = AIHelper.createAnalysisMessages(
                    pluginUI.getContext(),
                    getAnalysisTargetCode(),
                    customPrompt
            );
        }

        int buttonHeight = pluginUI.dp2px(36);
        int smallMargin = pluginUI.dp2px(6);
        int horizontalPadding = pluginUI.dp2px(8);
        String initialHistory = chatHistory.length() > 0 ? chatHistory.toString() : "准备开始分析...\n";

        PluginView view = pluginUI.buildVerticalLayout()
                .addEditBox("history_edit")
                .text(initialHistory)
                .textSize(11)
                .readOnly()
                .minLines(14)
                .maxLines(24)
                .widthMatchParent()
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                .marginBottom(smallMargin)
                .addEditBox("input_edit")
                .hint("在这里继续提问...")
                .textSize(11)
                .minLines(3)
                .maxLines(6)
                .widthMatchParent()
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                .marginBottom(smallMargin)
                .addHorizontalLayout("action_row").children(row -> row
                        .addButton("send_btn").text("发送").height(buttonHeight).marginRight(smallMargin)
                        .addButton("stop_btn").text("停止").height(buttonHeight).marginRight(smallMargin)
                        .addButton("min_btn").text("隐藏").height(buttonHeight)
                )
                .paddingHorizontal(horizontalPadding)
                .paddingVertical(pluginUI.dialogPaddingVertical())
                .build();

        historyEdit = view.requireViewById("history_edit");
        inputEdit = view.requireViewById("input_edit");
        if (!draftInput.isEmpty()) {
            inputEdit.setText(draftInput);
            inputEdit.selectEnd();
        }

        view.requireViewById("send_btn").setOnClickListener(v -> sendFromInput());

        inputEdit.addTextChangedListener(new PluginEditTextWatcher.Simple() {
            @Override
            public void onTextChanged(PluginEditText editText, CharSequence s, int start, int before, int count) {
                if (ignoreInputWatcher || count <= 0 || s == null) {
                    return;
                }

                int end = Math.min(start + count, s.length());
                if (start >= 0 && start < end) {
                    CharSequence changed = s.subSequence(start, end);
                    if (changed.toString().contains("\n")) {
                        handleEnterSend(s.toString());
                    }
                }
            }
        });

        view.requireViewById("stop_btn").setOnClickListener(v -> {
            if (!sending) {
                pluginUI.showToast("当前没有进行中的请求");
                return;
            }
            stopRequested = true;
            if (requestThread != null) {
                requestThread.interrupt();
            }
            appendStatus("正在停止生成...");
        });

        view.requireViewById("min_btn").setOnClickListener(v -> hideToBackground());

        conversationDialog = pluginUI.buildDialog()
                .setTitle(title)
                .setView(view)
                .setNegativeButton("关闭", (d, w) -> closeSession())
                .setNeutralButton("隐藏", (d, w) -> hideToBackground())
                .show();
    }

    private void hideToBackground() {
        if (inputEdit != null) {
            draftInput = inputEdit.getText().toString();
        }
        if (conversationDialog != null) {
            conversationDialog.dismiss();
        }
        conversationDialog = null;
        pluginUI.showToast("会话已隐藏");
    }

    private void closeSession() {
        stopRequested = true;
        if (requestThread != null) {
            requestThread.interrupt();
        }
        if (conversationDialog != null) {
            conversationDialog.dismiss();
        }
        activeSession = null;
        pluginUI.showToast("会话已关闭");
    }

    private void restoreFromEntry() {
        try {
            if (conversationDialog != null && conversationDialog.isShowing()) {
                return;
            }
            buildAndShow();
        } catch (Exception e) {
            pluginUI.showToast("恢复失败: " + e.getMessage());
        }
    }

    private void startInitialAnalysis() {
        if (sending) {
            return;
        }

        appendHistory("我", hasSelection()
                ? "请分析当前编辑器中选中的代码内容，并给出结论。"
                : "请分析当前编辑器中的整个文件内容，并给出结论。");

        requestAssistant(false, false);
    }

    private void sendQuestion(@NonNull String question) {
        if (sending) {
            return;
        }

        try {
            String scopedQuestion = buildScopedQuestion(question);
            AIHelper.appendMessage(messages, "user", scopedQuestion);
            appendHistory("我", question);
            inputEdit.setText("");
            requestAssistant(true, isModificationRequest(question));
        } catch (Exception e) {
            pluginUI.showToast("发送失败: " + e.getMessage());
        }
    }

    private void handleEnterSend(@NonNull String rawInput) {
        String question = rawInput.replace("\n", "").trim();
        ignoreInputWatcher = true;
        inputEdit.setText(question);
        ignoreInputWatcher = false;
        if (!question.isEmpty()) {
            sendQuestion(question);
        }
    }

    private void sendFromInput() {
        String question = inputEdit.getText().toString().trim();
        if (question.isEmpty()) {
            pluginUI.showToast("请输入问题");
            return;
        }
        sendQuestion(question);
    }

    private void requestAssistant(boolean rollbackOnFail, boolean allowToolCall) {
        sending = true;
        stopRequested = false;
        startLivePreview();

        String instruction = allowToolCall
                ? "你可以通过工具协助修改代码。删除内容时，除非用户明确要求删除整个方法，否则禁止删除整个方法，必须优先使用 delete_in_method 或 delete_text 删除指定代码片段。修改某个方法时，只能修改方法体内必要的语句、返回值或条件判断，禁止输出完整方法并整体替换。优先使用 replace_in_method、replace_text、delete_in_method、delete_text。当前选区仅用于分析上下文，不代表应该整体替换选区。调用 replace_selection 时，如果没有当前选区，必须提供 target 来精确替换目标代码，而且不得用空内容删除。"
                + toolManager.getToolsPrompt()
                : ANALYSIS_ONLY_INSTRUCTION;

        requestThread = new Thread(() -> {
            try {
                int turnCount = 0;
                boolean shouldContinue = true;

                while (shouldContinue && turnCount < 5 && !stopRequested) {
                    turnCount++;

                    JSONArray requestMessages = cloneMessages(messages);
                    AIHelper.appendMessage(requestMessages, "system", instruction);

                    AIHelper.AIResponse response = AIHelper.chatWithMessagesDetailed(
                            pluginUI.getContext(),
                            requestMessages,
                            true,
                            (reasoning, content) -> AIHelper.runOnMainThread(
                                    () -> updateLivePreview(reasoning, content))
                    );

                    AIHelper.runOnMainThread(this::finishLivePreview);
                    AIHelper.appendMessage(messages, "assistant", response.content);
                    AIHelper.runOnMainThread(() -> appendAssistant(response));

                    List<ToolManager.Tool> toolCalls = toolManager.parseToolCalls(response.content);
                    if (toolCalls.isEmpty() || !allowToolCall) {
                        shouldContinue = false;
                    } else {
                        StringBuilder resultBuilder = new StringBuilder();
                        for (ToolManager.Tool tool : toolCalls) {
                            if (stopRequested) {
                                break;
                            }
                            AIHelper.runOnMainThread(
                                    () -> appendStatus("正在执行工具: " + tool.name));
                            ToolManager.ToolResult result = toolManager.executeTool(tool);
                            resultBuilder.append(result.toXml()).append("\n");
                        }

                        AIHelper.appendMessage(messages, "user", resultBuilder.toString());
                        AIHelper.runOnMainThread(
                                () -> appendStatus("工具执行完毕，正在等待 AI 下一步决策..."));
                    }
                }

                AIHelper.runOnMainThread(() -> sending = false);
            } catch (Exception e) {
                if (rollbackOnFail && messages != null && messages.length() > 0) {
                    messages.remove(messages.length() - 1);
                }
                AIHelper.runOnMainThread(() -> {
                    finishLivePreview();
                    appendStatus(stopRequested ? "已停止生成" : "错误: " + e.getMessage());
                    sending = false;
                });
            }
        });
        requestThread.start();
    }

    private void appendAssistant(@NonNull AIHelper.AIResponse response) {
        if (!response.reasoning.isEmpty()) {
            appendHistory("AI分析逻辑", response.reasoning);
        }
        appendHistory("AI结果", response.content);
    }

    private boolean isModificationRequest(@NonNull String question) {
        String t = question.toLowerCase();
        return t.contains("修改")
                || t.contains("替换")
                || t.contains("插入")
                || t.contains("删除")
                || t.contains("追加")
                || t.contains("定位")
                || t.contains("修复")
                || t.contains("改这段")
                || t.contains("改这里");
    }

    private JSONArray cloneMessages(JSONArray source) {
        try {
            return new JSONArray(source.toString());
        } catch (Exception e) {
            return source;
        }
    }

    private static class Range {
        final int start;
        final int end;

        Range(int s, int e) {
            start = s;
            end = e;
        }
    }

    private boolean hasSelection() {
        if (editor == null) return false;
        try {
            return editor.getSelectionEnd() > editor.getSelectionStart();
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    private String getAnalysisTargetCode() {
        if (editor == null) {
            return code != null ? code : "";
        }

        try {
            int start = editor.getSelectionStart();
            int end = editor.getSelectionEnd();

            if (end > start) {
                return editor.subText(start, end);
            }

            return editor.subText(0, editor.length());
        } catch (Exception e) {
            return code != null ? code : "";
        }
    }

    @NonNull
    private String getScopeDescription() {
        return hasSelection() ? "当前选中的代码片段" : "当前文件的全部代码";
    }

    @NonNull
    private String buildScopedQuestion(@NonNull String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前分析范围是").append(getScopeDescription()).append("：\n");
        sb.append("```java\n");
        sb.append(getAnalysisTargetCode());
        sb.append("\n```\n\n");
        sb.append("我的问题是：\n");
        sb.append(question);
        return sb.toString();
    }

    private int findTextIndexSmart(@NonNull String fullText, @NonNull String query, int occurrence) {
        int from = 0;
        int count = 0;
        while (from < fullText.length()) {
            int idx = fullText.indexOf(query, from);
            if (idx < 0) {
                break;
            }
            if (++count == occurrence) {
                return idx;
            }
            from = idx + query.length();
        }
        return -1;
    }

    @Nullable
    private Range findMethodRange(
            @NonNull String fullText,
            @NonNull String methodName,
            @Nullable String preferred,
            int occ) {

        String pattern;
        if (methodName.contains("(")) {
            pattern = "(?m)^\\s*\\.method[^\\n]*" + Pattern.quote(methodName) + "[^\\n]*$";
        } else {
            pattern = "(?m)^\\s*\\.method[^\\n]*\\b" + Pattern.quote(methodName) + "\\b[^\\n]*$";
        }

        Matcher m = Pattern.compile(pattern).matcher(fullText);
        int count = 0;
        while (m.find()) {
            count++;
            if (count != occ) {
                continue;
            }

            int start = m.start();
            int end = fullText.indexOf(".end method", m.end());
            if (end > start) {
                int lineEnd = fullText.indexOf('\n', end);
                if (lineEnd < 0) {
                    lineEnd = fullText.length();
                } else {
                    lineEnd += 1;
                }
                return new Range(start, lineEnd);
            }
        }
        return null;
    }

    private void appendHistory(String role, String content) {
        if (chatHistory.length() > 0) {
            chatHistory.append("\n\n");
        }
        chatHistory.append(role).append("：\n").append(content);
        if (historyEdit != null) {
            historyEdit.setText(chatHistory.toString());
            historyEdit.selectEnd();
        }
    }

    private void startLivePreview() {
        liveReasoning = "";
        liveContent = "";
        renderLivePreview("AI 正在思考...");
    }

    private void updateLivePreview(String r, String c) {
        liveReasoning = r;
        liveContent = c;
        renderLivePreview(null);
    }

    private void finishLivePreview() {
        liveReasoning = "";
        liveContent = "";
        if (historyEdit != null) {
            historyEdit.setText(chatHistory.toString());
        }
    }

    private void renderLivePreview(String waiting) {
        if (historyEdit == null) {
            return;
        }

        StringBuilder sb = new StringBuilder(chatHistory)
                .append("\n\nAI逻辑：\n")
                .append(liveReasoning.isEmpty() ? waiting : liveReasoning)
                .append("\n\nAI内容：\n")
                .append(liveContent);

        historyEdit.setText(sb.toString());
        historyEdit.selectEnd();
    }

    private void appendStatus(String text) {
        String s = chatHistory.toString()
                + (chatHistory.length() > 0 ? "\n\n" : "")
                + "[" + text + "]";
        if (historyEdit != null) {
            historyEdit.setText(s);
            historyEdit.selectEnd();
        }
    }
}