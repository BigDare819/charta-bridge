import java.io.*;
import java.nio.file.*;

/** Probes which overwrite strategy survives a running game holding the deployed jar open. */
public class LockTest {
    public static void main(String[] args) throws Exception {
        Path dst = Path.of(args[0]);
        Path payload = Path.of(args[1]);
        byte[] want = Files.readAllBytes(payload);

        // A: atomic move over the top
        Path stagedA = dst.resolveSibling(dst.getFileName() + ".stagedA");
        Files.copy(payload, stagedA, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(stagedA, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("A atomic move      : OK");
        } catch (Exception e) {
            System.out.println("A atomic move      : FAIL " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        System.out.println("A content correct  : " + java.util.Arrays.equals(Files.readAllBytes(dst), want));

        // B: stream copy onto the existing file handle (CREATE_ALWAYS, no delete)
        try (InputStream in = Files.newInputStream(payload);
             OutputStream out = Files.newOutputStream(dst,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
            System.out.println("B stream in-place  : OK");
        } catch (Exception e) {
            System.out.println("B stream in-place  : FAIL " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        System.out.println("B content correct  : " + java.util.Arrays.equals(Files.readAllBytes(dst), want));

        // C: Files.copy REPLACE_EXISTING (delete-then-create) for reference
        try {
            Files.copy(payload, dst, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("C Files.copy repl  : OK");
        } catch (Exception e) {
            System.out.println("C Files.copy repl  : FAIL " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        System.out.println("C content correct  : " + java.util.Arrays.equals(Files.readAllBytes(dst), want));
    }
}
