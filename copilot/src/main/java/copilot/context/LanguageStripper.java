package copilot.context;

@FunctionalInterface
public interface LanguageStripper {
    String strip(String content, String fileName);
}
