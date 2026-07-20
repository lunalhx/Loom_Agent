package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ShellCommandProperties {
    private String shellSyntaxLevel = "HIGH_RISK_CONFIRM";
    private String shellInterpreter = "/bin/sh";
    private Boolean sessionGrantsEnabled = true;
    private List<String> readOnly = new ArrayList<>(List.of("pwd", "ls", "cat", "head", "tail", "wc", "grep", "sort", "uniq", "which", "file", "du", "df", "echo", "rg"));
    private List<String> write = new ArrayList<>(List.of("mkdir", "cp", "mv", "touch", "chmod", "date", "printf"));
    private List<String> highRisk = new ArrayList<>(List.of("curl", "wget", "npm", "yarn", "pip", "pip3", "docker", "ssh", "scp", "rsync", "chown", "kill", "systemctl", "sed", "awk", "tar", "zip", "unzip"));
    private List<String> deny = new ArrayList<>(List.of("rm", "rmdir", "find", "python", "python3"));
    private String unknownLevel = "HIGH_RISK_CONFIRM";
}
