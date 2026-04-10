package com.example.myplugin.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolManager {
    public static class Tool {
        public final String name;
        public final String description;
        public final Map<String, String> params = new HashMap<>();

        public Tool(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    public static class ToolResult {
        public final String toolName;
        public final boolean success;
        public final String content;

        public ToolResult(String toolName, boolean success, String content) {
            this.toolName = toolName;
            this.success = success;
            this.content = content;
        }

        public String toXml() {
            return "<tool_result name=\"" + toolName + "\">\n" + content + "\n</tool_result>";
        }
    }

    public interface ToolExecutor {
        @NonNull ToolResult execute(@NonNull Tool tool);
    }

    private final Map<String, ToolExecutor> executors = new HashMap<>();
    private final List<Tool> toolDefinitions = new ArrayList<>();

    public void registerTool(String name, String description, ToolExecutor executor) {
        executors.put(name, executor);
        toolDefinitions.add(new Tool(name, description));
    }

    public String getToolsPrompt() {
        StringBuilder sb = new StringBuilder("\n\n可用工具：\n");
        for (Tool tool : toolDefinitions) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
        }
        sb.append("\n使用格式：\n<tool name=\"tool_name\">\n  <param name=\"param_name\">value</param>\n</tool>\n");
        return sb.toString();
    }

    public List<Tool> parseToolCalls(String text) {
        List<Tool> tools = new ArrayList<>();
        Pattern toolPattern = Pattern.compile("<tool\\s+name=\\\"([^\\\"]+)\\\">([\\s\\S]*?)</tool>", Pattern.DOTALL);
        Pattern paramPattern = Pattern.compile("<param\\s+name=\\\"([^\\\"]+)\\\">([\\s\\S]*?)</param>", Pattern.DOTALL);

        Matcher toolMatcher = toolPattern.matcher(text);
        while (toolMatcher.find()) {
            String name = toolMatcher.group(1);
            Tool tool = new Tool(name, "");
            String paramsText = toolMatcher.group(2);
            Matcher paramMatcher = paramPattern.matcher(paramsText);
            while (paramMatcher.find()) {
                tool.params.put(paramMatcher.group(1), paramMatcher.group(2).trim());
            }
            tools.add(tool);
        }
        return tools;
    }

    @NonNull
    public ToolResult executeTool(@NonNull Tool tool) {
        ToolExecutor executor = executors.get(tool.name);
        if (executor == null) {
            return new ToolResult(tool.name, false, "工具未找到: " + tool.name);
        }
        try {
            return executor.execute(tool);
        } catch (Exception e) {
            return new ToolResult(tool.name, false, "执行错误: " + e.getMessage());
        }
    }
}
