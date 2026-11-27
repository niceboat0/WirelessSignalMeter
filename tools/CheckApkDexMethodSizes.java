import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

class CheckApkDexMethodSizes {
    // ART's pathological-method cutoff is UINT16_MAX / 4, inclusive.
    private static final int ART_LIMIT = 16_383;
    private static final Pattern DEX_ENTRY = Pattern.compile("classes[0-9]*\\.dex");

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Pass at least one APK to check.");
        }
        boolean passed = true;
        for (String arg : args) {
            passed &= check(Path.of(arg));
        }
        if (!passed) {
            System.exit(1);
        }
    }

    private static boolean check(Path apk) throws IOException {
        int methodCount = 0;
        int oversizedCount = 0;
        int maxCodeUnits = 0;
        String largestMethod = "";
        try (var zip = new ZipFile(apk.toFile())) {
            var dexEntries = zip.stream().filter(entry -> DEX_ENTRY.matcher(entry.getName()).matches()).toList();
            for (var entry : dexEntries) {
                try (var stream = new BufferedInputStream(zip.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(null, stream);
                    for (var cls : dex.getClasses()) {
                        for (var method : cls.getMethods()) {
                            var code = method.getImplementation();
                            if (code == null) continue;
                            methodCount++;
                            int codeUnits = code.getInstructionsSize();
                            if (codeUnits > maxCodeUnits) {
                                maxCodeUnits = codeUnits;
                                largestMethod = entry.getName() + ": " + method;
                            }
                            if (codeUnits >= ART_LIMIT) {
                                oversizedCount++;
                                System.err.printf("%s %s: %s uses %d DEX code units (must be < %d)%n",
                                        apk.getFileName(), entry.getName(), method, codeUnits, ART_LIMIT);
                            }
                        }
                    }
                }
            }
            if (methodCount == 0) {
                throw new IOException("No DEX methods with code found in " + apk);
            }
            System.out.printf("%s: checked %d methods in %d DEX files; largest: %s (%d code units)%n",
                    apk.getFileName(), methodCount, dexEntries.size(), largestMethod, maxCodeUnits);
        }
        return oversizedCount == 0;
    }
}
