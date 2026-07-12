import java.util.jar.JarFile;
import java.util.Enumeration;
import java.util.jar.JarEntry;

public class NetApiCheck {
    public static void main(String[] args) throws Exception {
        // find the jar
        java.io.File home = new java.io.File(System.getProperty("user.home"));
        java.io.File cache = new java.io.File(home, ".gradle/caches");
        java.util.ArrayList<String> jars = new java.util.ArrayList<>();
        findJars(cache, jars);
        for (String j : jars) {
            System.out.println("=== " + j + " ===");
            try (JarFile jf = new JarFile(j)) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    String name = en.nextElement().getName();
                    if (name.contains("ServerPlayNetworking") || name.contains("ClientPlayNetworking")) {
                        System.out.println(name);
                    }
                }
            }
        }
    }
    static void findJars(java.io.File dir, java.util.ArrayList<String> out) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) findJars(f, out);
            else if (f.getName().contains("fabric-networking-api-v1") && f.getName().endsWith(".jar")) out.add(f.getAbsolutePath());
        }
    }
}
