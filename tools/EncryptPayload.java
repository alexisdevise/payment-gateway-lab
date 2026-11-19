import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * What a consumer app does before calling the payment API. Single-file program, no build needed:
 *
 *   java tools/EncryptPayload.java <base64Key> <merchantId> <idempotencyKey> <payload.json>
 *
 * Prints base64(IV || AES-256-GCM ciphertext || tag), with AAD = "merchantId|idempotencyKey".
 */
public class EncryptPayload {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: java tools/EncryptPayload.java <base64Key> <merchantId> <idempotencyKey> <payload.json>");
            System.exit(1);
        }
        byte[] key = Base64.getDecoder().decode(args[0]);
        byte[] aad = (args[1] + "|" + args[2]).getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = Files.readAllBytes(Path.of(args[3]));

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        byte[] sealed = cipher.doFinal(plaintext);

        System.out.println(Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array()));
    }
}
