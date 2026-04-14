package me.naimad.fastregex;

public class TestLoad {
    public static void main(String[] args) {
        try (FastRegex.Regex regex = FastRegex.compile("test")) {
            System.out.println("Native library loaded and compiled successfully! Handle: " + regex.handle());
            System.out.println("Success.");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
