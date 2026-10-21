import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** java -cp apksig.jar:. Sign <keystore.p12> <password> <alias> <in.apk> <out.apk> — APK Signature Scheme v2, aligns stored entries */
public class Sign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[0])) {
            ks.load(in, a[1].toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(a[2], a[1].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[2]);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("lunch", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(a[3]))
                .setOutputApk(new File(a[4]))
                .setMinSdkVersion(29)
                .setV1SigningEnabled(false) /* v2 only: v1 needs JDK internals removed in newer Java, and minSdk 29 never uses it */
                .build()
                .sign();
    }
}
