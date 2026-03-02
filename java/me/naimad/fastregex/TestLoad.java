package me.naimad.fastregex;

public class TestLoad {
    public static void main(String[] args) {
        try {
            long handle = FastRegex.compile("test");
            System.out.println("Native library loaded and compiled successfully! Handle: " + handle);
            FastRegex.release(handle);
            System.out.println("Success.");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
