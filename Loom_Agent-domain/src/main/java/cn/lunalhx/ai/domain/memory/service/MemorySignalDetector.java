package cn.lunalhx.ai.domain.memory.service;

import java.util.regex.Pattern;

public final class MemorySignalDetector {

    private static final Pattern[] CORRECTION_PATTERNS = {
            Pattern.compile("(?i)that(?:'s| is) (?:wrong|incorrect)"),
            Pattern.compile("(?i)you misunderstood|try again|redo"),
            Pattern.compile("不对|你理解错了|你理解有误|重试|重新来|换一种|改用|其实|我说错了|应该是")
    };
    private static final Pattern[] REINFORCEMENT_PATTERNS = {
            Pattern.compile("(?i)yes[,.]?\\s+(?:exactly|perfect|that(?:'s| is) (?:right|correct|it))"),
            Pattern.compile("(?i)perfect(?:[.!?]|$)|exactly\\s+(?:right|correct)"),
            Pattern.compile("(?i)that(?:'s| is)\\s+(?:exactly\\s+)?(?:right|correct|what i (?:wanted|needed|meant))"),
            Pattern.compile("(?i)keep\\s+(?:doing\\s+)?that"),
            Pattern.compile("对[，,]?\\s*就是这样|完全正确|就是这个意思|正是我想要的|继续保持")
    };

    private MemorySignalDetector() {
    }

    public static boolean detectCorrection(String message) {
        return matches(message, CORRECTION_PATTERNS);
    }

    public static boolean detectReinforcement(String message) {
        return matches(message, REINFORCEMENT_PATTERNS);
    }

    private static boolean matches(String message, Pattern[] patterns) {
        if (message == null || message.isBlank()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }
}
